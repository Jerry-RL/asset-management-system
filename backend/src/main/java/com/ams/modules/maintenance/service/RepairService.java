package com.ams.modules.maintenance.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.maintenance.entity.InspectionRecord;
import com.ams.modules.maintenance.entity.MaintenanceVendor;
import com.ams.modules.maintenance.entity.RepairOrder;
import com.ams.modules.maintenance.mapper.InspectionRecordMapper;
import com.ams.modules.maintenance.mapper.MaintenanceVendorMapper;
import com.ams.modules.maintenance.mapper.RepairOrderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 巡检维修（FR-MNT-*、FR-MNT-SLA-*）：报修状态机 + SLA + 维修公司 + 巡查。
 */
@Service
public class RepairService {

    private final RepairOrderMapper repairOrderMapper;
    private final MaintenanceVendorMapper vendorMapper;
    private final InspectionRecordMapper inspectionRecordMapper;

    public RepairService(
            RepairOrderMapper repairOrderMapper,
            MaintenanceVendorMapper vendorMapper,
            InspectionRecordMapper inspectionRecordMapper) {
        this.repairOrderMapper = repairOrderMapper;
        this.vendorMapper = vendorMapper;
        this.inspectionRecordMapper = inspectionRecordMapper;
    }

    // ---- 报修 ----
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

    /** 派工（FR-MNT-002）：设置维修公司/人员 + SLA 截止时间。 */
    public RepairOrder dispatch(Long repairId, Long vendorId, Long assigneeId,
            LocalDateTime responseDeadline, LocalDateTime completeDeadline) {
        RepairOrder order = require(repairId);
        order.setStatus("dispatched");
        order.setVendorId(vendorId);
        order.setAssigneeId(assigneeId);
        order.setSlaResponseDeadline(responseDeadline);
        order.setSlaCompleteDeadline(completeDeadline);
        repairOrderMapper.updateById(order);
        return order;
    }

    /** 完工（FR-MNT-SLA-003）：待验收。 */
    public RepairOrder complete(Long repairId, String resultRemark) {
        RepairOrder order = require(repairId);
        order.setStatus("pending_accept");
        order.setResultRemark(resultRemark);
        order.setCompletedAt(LocalDateTime.now());
        repairOrderMapper.updateById(order);
        return order;
    }

    /** 验收/关闭。 */
    public RepairOrder accept(Long repairId, boolean accepted) {
        RepairOrder order = require(repairId);
        order.setStatus(accepted ? "completed" : "rejected");
        repairOrderMapper.updateById(order);
        return order;
    }

    public RepairOrder get(Long id) {
        return require(id);
    }

    private RepairOrder require(Long id) {
        RepairOrder order = repairOrderMapper.selectById(id);
        if (order == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return order;
    }

    // ---- 维修公司 ----
    public List<MaintenanceVendor> listVendors() {
        return vendorMapper.selectList(
                new LambdaQueryWrapper<MaintenanceVendor>().orderByAsc(MaintenanceVendor::getId));
    }

    public MaintenanceVendor createVendor(MaintenanceVendor vendor) {
        vendorMapper.insert(vendor);
        return vendor;
    }

    // ---- 巡查 ----
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
}
