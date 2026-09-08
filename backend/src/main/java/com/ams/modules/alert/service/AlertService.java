package com.ams.modules.alert.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.alert.entity.AlertRecord;
import com.ams.modules.alert.entity.AlertRule;
import com.ams.modules.alert.mapper.AlertRecordMapper;
import com.ams.modules.alert.mapper.AlertRuleMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预警管理（FR-ALERT-*、FR-ALERT-LC-*，§4.24.4）：规则配置、预警生成、指派、处理、关闭、升级。
 */
@Service
public class AlertService {

    private final AlertRuleMapper ruleMapper;
    private final AlertRecordMapper recordMapper;

    public AlertService(AlertRuleMapper ruleMapper, AlertRecordMapper recordMapper) {
        this.ruleMapper = ruleMapper;
        this.recordMapper = recordMapper;
    }

    // ---- 规则 ----
    public List<AlertRule> listRules(String alertType) {
        return ruleMapper.selectList(
                new LambdaQueryWrapper<AlertRule>()
                        .eq(alertType != null, AlertRule::getAlertType, alertType)
                        .orderByAsc(AlertRule::getId));
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

    // ---- 记录 ----
    public List<AlertRecord> listRecords(String status, Long assigneeId) {
        return recordMapper.selectList(
                new LambdaQueryWrapper<AlertRecord>()
                        .eq(status != null, AlertRecord::getStatus, status)
                        .eq(assigneeId != null, AlertRecord::getAssigneeId, assigneeId)
                        .orderByDesc(AlertRecord::getId));
    }

    /** 预警生成（FR-ALERT-LC-001）：规则触发生成 pending 记录。 */
    public AlertRecord trigger(AlertRecord record) {
        record.setStatus("pending");
        record.setCreatedAt(LocalDateTime.now());
        recordMapper.insert(record);
        return record;
    }

    /** 指派责任人（FR-ALERT-LC-002）。 */
    public AlertRecord assign(Long recordId, Long assigneeId) {
        AlertRecord record = require(recordId);
        record.setAssigneeId(assigneeId);
        record.setStatus("processing");
        recordMapper.updateById(record);
        return record;
    }

    /** 处理（FR-ALERT-LC-003）。 */
    public AlertRecord process(Long recordId, String remark) {
        AlertRecord record = require(recordId);
        record.setStatus("processing");
        record.setHandleRemark(remark);
        recordMapper.updateById(record);
        return record;
    }

    /** 关闭（须填处理说明）。 */
    public AlertRecord close(Long recordId, String remark) {
        AlertRecord record = require(recordId);
        if (remark == null || remark.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "关闭预警须填写处理说明");
        }
        record.setStatus("closed");
        record.setHandleRemark(remark);
        record.setHandledAt(LocalDateTime.now());
        recordMapper.updateById(record);
        return record;
    }

    /** 升级（FR-ALERT-LC-004）。 */
    public AlertRecord escalate(Long recordId) {
        AlertRecord record = require(recordId);
        record.setStatus("escalated");
        record.setLevel((record.getLevel() == null ? 1 : record.getLevel()) + 1);
        recordMapper.updateById(record);
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
