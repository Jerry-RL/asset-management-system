package com.ams.modules.alert.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.alert.entity.AlertRecord;
import com.ams.modules.alert.entity.AlertRule;
import com.ams.modules.alert.mapper.AlertRecordMapper;
import com.ams.modules.alert.mapper.AlertRuleMapper;
import com.ams.modules.asset.entity.Mortgage;
import com.ams.modules.asset.service.CertificateService;
import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.contract.ContractStatus;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.task.entity.Task;
import com.ams.modules.task.service.TaskService;
import com.ams.platform.event.AlertTriggeredEvent;
import com.ams.platform.event.DomainEventPublisher;
import com.ams.platform.security.RbacService;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预警管理（FR-ALERT-*、FR-ALERT-LC-*）：规则、触发、指派、处理、关闭、升级；触发联动任务中心。
 */
@Service
public class AlertService {

    private final AlertRuleMapper ruleMapper;
    private final AlertRecordMapper recordMapper;
    private final TaskService taskService;
    private final ContractMapper contractMapper;
    private final BillMapper billMapper;
    private final CertificateService certificateService;
    private final RbacService rbacService;
    private final DomainEventPublisher eventPublisher;

    public AlertService(
            AlertRuleMapper ruleMapper,
            AlertRecordMapper recordMapper,
            TaskService taskService,
            ContractMapper contractMapper,
            BillMapper billMapper,
            CertificateService certificateService,
            RbacService rbacService,
            DomainEventPublisher eventPublisher) {
        this.ruleMapper = ruleMapper;
        this.recordMapper = recordMapper;
        this.taskService = taskService;
        this.contractMapper = contractMapper;
        this.billMapper = billMapper;
        this.certificateService = certificateService;
        this.rbacService = rbacService;
        this.eventPublisher = eventPublisher;
    }

    public List<AlertRule> listRules(String alertType) {
        LambdaQueryWrapper<AlertRule> wrapper = new LambdaQueryWrapper<AlertRule>()
                .eq(alertType != null, AlertRule::getAlertType, alertType)
                .orderByAsc(AlertRule::getId);
        // 全局公司切换：预警规则按所属公司收敛
        rbacService.applyCompanyScope(wrapper, SecurityUtils.current(), AlertRule::getCompanyId);
        return ruleMapper.selectList(wrapper);
    }

    public AlertRule createRule(AlertRule rule) {
        if (rule.getEnabled() == null) {
            rule.setEnabled(true);
        }
        ruleMapper.insert(rule);
        return rule;
    }

    public AlertRule updateRule(Long id, AlertRule rule) {
        rule.setId(id);
        ruleMapper.updateById(rule);
        return ruleMapper.selectById(id);
    }

    public List<AlertRecord> listRecords(String status, Long assigneeId) {
        LambdaQueryWrapper<AlertRecord> wrapper = new LambdaQueryWrapper<AlertRecord>()
                .eq(status != null, AlertRecord::getStatus, status)
                .eq(assigneeId != null, AlertRecord::getAssigneeId, assigneeId)
                .orderByDesc(AlertRecord::getId);
        // 全局公司切换：预警记录按所属公司收敛
        rbacService.applyCompanyScope(wrapper, SecurityUtils.current(), AlertRecord::getCompanyId);
        return recordMapper.selectList(wrapper);
    }

    @Transactional
    public AlertRecord trigger(AlertRecord record) {
        // 幂等：同 bizType+bizId+alertType 且未关闭的不重复生成
        if (record.getBizType() != null && record.getBizId() != null && record.getAlertType() != null) {
            Long exists = recordMapper.selectCount(
                    new LambdaQueryWrapper<AlertRecord>()
                            .eq(AlertRecord::getBizType, record.getBizType())
                            .eq(AlertRecord::getBizId, record.getBizId())
                            .eq(AlertRecord::getAlertType, record.getAlertType())
                            .ne(AlertRecord::getStatus, "closed"));
            if (exists != null && exists > 0) {
                return recordMapper.selectOne(
                        new LambdaQueryWrapper<AlertRecord>()
                                .eq(AlertRecord::getBizType, record.getBizType())
                                .eq(AlertRecord::getBizId, record.getBizId())
                                .eq(AlertRecord::getAlertType, record.getAlertType())
                                .ne(AlertRecord::getStatus, "closed")
                                .last("LIMIT 1"));
            }
        }
        record.setStatus("pending");
        record.setCreatedAt(LocalDateTime.now());
        if (record.getLevel() == null) {
            record.setLevel(1);
        }
        recordMapper.insert(record);

        Task task = new Task();
        task.setTaskType("alert");
        task.setRefId(record.getId());
        task.setRefNo(record.getAlertType());
        task.setCompanyId(record.getCompanyId());
        task.setAssigneeId(record.getAssigneeId());
        task.setDeadline(LocalDateTime.now().plusDays(3));
        task.setStatus("pending");
        taskService.create(task);
        // DSD §4.8：预警生成 → AlertTriggered（处置待办与通知的下游入口）
        eventPublisher.publishAfterCommit(new AlertTriggeredEvent(
                record.getId(), record.getAlertType(), record.getSubType(),
                record.getLevel() == null ? 1 : record.getLevel(),
                record.getBizType(), record.getBizId(), record.getTitle()));
        return record;
    }

    /** 扫描合同到期 / 欠费并自动触发预警（供定时任务调用）。 */
    @Transactional
    public int scanAndTrigger() {
        int count = 0;
        LocalDate today = LocalDate.now();
        List<Contract> renewable = contractMapper.selectList(
                new LambdaQueryWrapper<Contract>()
                        .eq(Contract::getStatus, ContractStatus.RENEWABLE));
        for (Contract c : renewable) {
            AlertRecord r = new AlertRecord();
            r.setAlertType("contract_expiry");
            r.setSubType("renewable");
            r.setLevel(2);
            r.setBizType("contract");
            r.setBizId(c.getId());
            r.setTitle("合同即将到期需续签");
            r.setContent("合同 " + c.getContractNo() + " 将于 " + c.getEndDate() + " 到期");
            trigger(r);
            count++;
        }
        List<Contract> expired = contractMapper.selectList(
                new LambdaQueryWrapper<Contract>()
                        .eq(Contract::getStatus, ContractStatus.EXPIRED));
        for (Contract c : expired) {
            AlertRecord r = new AlertRecord();
            r.setAlertType("contract_expiry");
            r.setSubType("expired");
            r.setLevel(3);
            r.setBizType("contract");
            r.setBizId(c.getId());
            r.setTitle("合同已到期未处理");
            r.setContent("合同 " + c.getContractNo() + " 已于 " + c.getEndDate() + " 到期，请挂账或退租");
            trigger(r);
            count++;
        }
        List<Bill> overdueBills = billMapper.selectList(
                new LambdaQueryWrapper<Bill>()
                        .in(Bill::getStatus, BillStatus.UNPAID, BillStatus.PARTIAL_PAID)
                        .lt(Bill::getDueDate, today.minusDays(7)));
        for (Bill bill : overdueBills) {
            AlertRecord r = new AlertRecord();
            r.setAlertType("rent_overdue");
            r.setSubType("overdue_7d");
            r.setLevel(2);
            r.setBizType("bill");
            r.setBizId(bill.getId());
            r.setTitle("租金欠费超期预警");
            r.setContent("账单 " + bill.getBillNo() + " 到期日 " + bill.getDueDate() + " 仍未结清");
            trigger(r);
            count++;
        }
        // FR-MORT-002 抵押到期预警
        List<Mortgage> expiringMortgages = certificateService.listExpiring(30);
        for (Mortgage m : expiringMortgages) {
            AlertRecord r = new AlertRecord();
            r.setAlertType("mortgage_expiry");
            r.setSubType(m.getEndDate() != null && m.getEndDate().isBefore(today) ? "expired" : "expiring");
            r.setLevel(m.getEndDate() != null && m.getEndDate().isBefore(today) ? 3 : 2);
            r.setBizType("mortgage");
            r.setBizId(m.getId());
            r.setTitle("抵押到期预警");
            r.setContent("资产 " + m.getAssetId() + " 抵押给 " + m.getMortgagee()
                    + "，到期日 " + m.getEndDate());
            trigger(r);
            count++;
        }
        return count;
    }

    public AlertRecord assign(Long recordId, Long assigneeId) {
        AlertRecord record = require(recordId);
        record.setAssigneeId(assigneeId);
        record.setStatus("processing");
        recordMapper.updateById(record);
        return record;
    }

    public AlertRecord process(Long recordId, String remark) {
        AlertRecord record = require(recordId);
        record.setStatus("processing");
        record.setHandleRemark(remark);
        recordMapper.updateById(record);
        return record;
    }

    @Transactional
    public AlertRecord close(Long recordId, String remark) {
        AlertRecord record = require(recordId);
        if (remark == null || remark.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "关闭预警须填写处理说明");
        }
        record.setStatus("closed");
        record.setHandleRemark(remark);
        record.setHandledAt(LocalDateTime.now());
        recordMapper.updateById(record);
        for (Task t : taskService.listPendingByRef("alert", record.getId())) {
            taskService.complete(t.getId());
        }
        return record;
    }

    public AlertRecord escalate(Long recordId) {
        AlertRecord record = require(recordId);
        record.setStatus("escalated");
        record.setLevel((record.getLevel() == null ? 1 : record.getLevel()) + 1);
        recordMapper.updateById(record);
        Task task = new Task();
        task.setTaskType("alert_escalate");
        task.setRefId(record.getId());
        task.setRefNo(record.getAlertType());
        task.setCompanyId(record.getCompanyId());
        task.setDeadline(LocalDateTime.now().plusDays(1));
        task.setStatus("pending");
        taskService.create(task);
        return record;
    }

    private AlertRecord require(Long id) {
        AlertRecord record = recordMapper.selectById(id);
        if (record == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return record;
    }
}
