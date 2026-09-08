package com.ams.modules.maintenance.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.alert.entity.AlertRecord;
import com.ams.modules.alert.service.AlertService;
import com.ams.modules.config.service.ConfigVersionService;
import com.ams.modules.maintenance.entity.InspectionRecord;
import com.ams.modules.maintenance.entity.MaintenanceVendor;
import com.ams.modules.maintenance.entity.RepairOrder;
import com.ams.modules.maintenance.mapper.InspectionRecordMapper;
import com.ams.modules.maintenance.mapper.MaintenanceVendorMapper;
import com.ams.modules.maintenance.mapper.RepairOrderMapper;
import com.ams.modules.task.entity.Task;
import com.ams.modules.task.service.TaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 巡检维修（FR-MNT-*、FR-MNT-SLA-*）：报修状态机 + SLA 超时升级。
 */
@Service
public class RepairService {

    private final RepairOrderMapper repairOrderMapper;
    private final MaintenanceVendorMapper vendorMapper;
    private final InspectionRecordMapper inspectionRecordMapper;
    private final ConfigVersionService configVersionService;
    private final AlertService alertService;
    private final TaskService taskService;

    public RepairService(
            RepairOrderMapper repairOrderMapper,
            MaintenanceVendorMapper vendorMapper,
            InspectionRecordMapper inspectionRecordMapper,
            ConfigVersionService configVersionService,
            AlertService alertService,
            TaskService taskService) {
        this.repairOrderMapper = repairOrderMapper;
        this.vendorMapper = vendorMapper;
        this.inspectionRecordMapper = inspectionRecordMapper;
        this.configVersionService = configVersionService;
        this.alertService = alertService;
        this.taskService = taskService;
    }

    public PageResult<RepairOrder> page(long page, long pageSize, String status, String keyword) {
        Page<RepairOrder> result = repairOrderMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<RepairOrder>()
                        .eq(status != null, RepairOrder::getStatus, status)
                        .like(keyword != null && !keyword.isBlank(), RepairOrder::getDescription, keyword)
                        .orderByDesc(RepairOrder::getId));
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    public RepairOrder create(RepairOrder order) {
        order.setStatus("pending_review");
        repairOrderMapper.insert(order);
        return order;
    }

    /** 派工（FR-MNT-002）：SLA 截止时间缺省读配置。 */
    public RepairOrder dispatch(Long repairId, Long vendorId, Long assigneeId,
            LocalDateTime responseDeadline, LocalDateTime completeDeadline) {
        RepairOrder order = require(repairId);
        order.setStatus("dispatched");
        order.setVendorId(vendorId);
        order.setAssigneeId(assigneeId);
        LocalDateTime now = LocalDateTime.now();
        int respH = parseInt(configVersionService.getValue("repair.sla_response_hours", "2"), 2);
        int compH = parseInt(configVersionService.getValue("repair.sla_complete_hours", "48"), 48);
        order.setSlaResponseDeadline(responseDeadline != null ? responseDeadline : now.plusHours(respH));
        order.setSlaCompleteDeadline(completeDeadline != null ? completeDeadline : now.plusHours(compH));
        order.setSlaBreached(false);
        repairOrderMapper.updateById(order);
        return order;
    }

    public RepairOrder complete(Long repairId, String resultRemark) {
        RepairOrder order = require(repairId);
        order.setStatus("pending_accept");
        order.setResultRemark(resultRemark);
        order.setCompletedAt(LocalDateTime.now());
        repairOrderMapper.updateById(order);
        return order;
    }

    public RepairOrder accept(Long repairId, boolean accepted) {
        RepairOrder order = require(repairId);
        order.setStatus(accepted ? "completed" : "rejected");
        repairOrderMapper.updateById(order);
        return order;
    }

    public RepairOrder get(Long id) {
        return require(id);
    }

    /** SLA 超时扫描（FR-MNT-SLA-002）：预警 + 督办任务。 */
    @Transactional
    public int scanSlaBreaches() {
        LocalDateTime now = LocalDateTime.now();
        List<RepairOrder> open = repairOrderMapper.selectList(
                new LambdaQueryWrapper<RepairOrder>()
                        .notIn(RepairOrder::getStatus, "completed", "rejected")
                        .and(w -> w
                                .lt(RepairOrder::getSlaResponseDeadline, now)
                                .or()
                                .lt(RepairOrder::getSlaCompleteDeadline, now)));
        int n = 0;
        for (RepairOrder order : open) {
            boolean responseLate = order.getSlaResponseDeadline() != null
                    && order.getSlaResponseDeadline().isBefore(now)
                    && order.getAcceptedAt() == null
                    && !"pending_accept".equals(order.getStatus())
                    && !"completed".equals(order.getStatus());
            boolean completeLate = order.getSlaCompleteDeadline() != null
                    && order.getSlaCompleteDeadline().isBefore(now)
                    && order.getCompletedAt() == null;
            if (!responseLate && !completeLate) {
                continue;
            }
            if (Boolean.TRUE.equals(order.getSlaBreached())) {
                continue; // 幂等：已升级过
            }
            order.setSlaBreached(true);
            repairOrderMapper.updateById(order);

            AlertRecord alert = new AlertRecord();
            alert.setAlertType("repair_sla");
            alert.setSubType(completeLate ? "complete_overdue" : "response_overdue");
            alert.setLevel(completeLate ? 3 : 2);
            alert.setBizType("repair");
            alert.setBizId(order.getId());
            alert.setTitle("报修 SLA 超时");
            alert.setContent("报修单 #" + order.getId() + " "
                    + (completeLate ? "完工时限" : "响应时限") + "已超时");
            alert.setAssigneeId(order.getAssigneeId());
            alertService.trigger(alert);

            if (taskService.listPendingByRef("repair_sla", order.getId()).isEmpty()) {
                Task task = new Task();
                task.setTaskType("repair_sla");
                task.setRefId(order.getId());
                task.setRefNo("SLA");
                task.setAssigneeId(order.getAssigneeId());
                task.setDeadline(now.plusDays(1));
                task.setStatus("pending");
                taskService.create(task);
            }
            n++;
        }
        return n;
    }

    private RepairOrder require(Long id) {
        RepairOrder order = repairOrderMapper.selectById(id);
        if (order == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return order;
    }

    public List<MaintenanceVendor> listVendors() {
        return vendorMapper.selectList(
                new LambdaQueryWrapper<MaintenanceVendor>().orderByAsc(MaintenanceVendor::getId));
    }

    public MaintenanceVendor createVendor(MaintenanceVendor vendor) {
        vendorMapper.insert(vendor);
        return vendor;
    }

    public InspectionRecord createInspection(InspectionRecord record) {
        record.setStatus("pending");
        inspectionRecordMapper.insert(record);
        return record;
    }

    public List<InspectionRecord> listInspections(Long assetId) {
        return inspectionRecordMapper.selectList(
                new LambdaQueryWrapper<InspectionRecord>()
                        .eq(assetId != null, InspectionRecord::getAssetId, assetId)
                        .orderByDesc(InspectionRecord::getId));
    }

    private static int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return def;
        }
    }
}
