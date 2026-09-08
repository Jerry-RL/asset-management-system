package com.ams.modules.occupation.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.service.LeaseControlService;
import com.ams.modules.alert.entity.AlertRecord;
import com.ams.modules.alert.service.AlertService;
import com.ams.modules.occupation.entity.OccupationOrder;
import com.ams.modules.occupation.mapper.OccupationOrderMapper;
import com.ams.modules.revitalization.service.RevitalizationService;
import com.ams.platform.approval.ApprovalEngine;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 临时占用闭环（FR-OCC-*，§4.24.6）：空置 → 占用申请 → 审批 → 占用 → 解除 → 空置。
 */
@Service
public class OccupationService {

    private final OccupationOrderMapper occupationOrderMapper;
    private final LeaseControlService leaseControlService;
    private final ApprovalEngine approvalEngine;
    private final RevitalizationService revitalizationService;
    private final AlertService alertService;

    public OccupationService(
            OccupationOrderMapper occupationOrderMapper,
            LeaseControlService leaseControlService,
            ApprovalEngine approvalEngine,
            RevitalizationService revitalizationService,
            AlertService alertService) {
        this.occupationOrderMapper = occupationOrderMapper;
        this.leaseControlService = leaseControlService;
        this.approvalEngine = approvalEngine;
        this.revitalizationService = revitalizationService;
        this.alertService = alertService;
    }

    public List<OccupationOrder> list() {
        return occupationOrderMapper.selectList(
                new LambdaQueryWrapper<OccupationOrder>().orderByDesc(OccupationOrder::getId));
    }

    /** 占用申请（FR-OCC-001）：仅空置资产可发起。 */
    @Transactional
    public OccupationOrder create(OccupationOrder order) {
        leaseControlService.assertVacant(order.getAssetId());
        order.setStatus("draft");
        occupationOrderMapper.insert(order);
        return order;
    }

    /** 提交审批。 */
    @Transactional
    public OccupationOrder submit(Long id) {
        OccupationOrder order = require(id);
        order.setStatus("approving");
        occupationOrderMapper.updateById(order);
        approvalEngine.start("occupation", id);
        return order;
    }

    /** 审批通过 → 占用（FR-OCC-002）。 */
    @Transactional
    public OccupationOrder approve(Long id) {
        OccupationOrder order = require(id);
        order.setStatus("occupied");
        occupationOrderMapper.updateById(order);
        leaseControlService.transition(order.getAssetId(), LeaseControlStatus.OCCUPIED,
                "occupation", id, "占用审批通过");
        return order;
    }

    /** 解除占用（FR-OCC-003）→ 空置。 */
    @Transactional
    public OccupationOrder release(Long id) {
        OccupationOrder order = require(id);
        order.setStatus("released");
        occupationOrderMapper.updateById(order);
        leaseControlService.transition(order.getAssetId(), LeaseControlStatus.VACANT,
                "occupation", id, "解除占用");
        revitalizationService.createOnVacant(order.getAssetId(), "占用解除", "招租盘活");
        return order;
    }

    /** 到期扫描（FR-OCC-003）：临期/超期生成预警。 */
    @Transactional
    public int scanExpiry(int withinDays) {
        LocalDate today = LocalDate.now();
        LocalDate threshold = today.plusDays(withinDays);
        List<OccupationOrder> list = occupationOrderMapper.selectList(
                new LambdaQueryWrapper<OccupationOrder>()
                        .eq(OccupationOrder::getStatus, "occupied")
                        .isNotNull(OccupationOrder::getEndDate)
                        .le(OccupationOrder::getEndDate, threshold));
        int n = 0;
        for (OccupationOrder o : list) {
            boolean expired = o.getEndDate().isBefore(today) || o.getEndDate().isEqual(today);
            AlertRecord r = new AlertRecord();
            r.setAlertType("occupation_expiry");
            r.setSubType(expired ? "expired" : "expiring");
            r.setLevel(expired ? 3 : 2);
            r.setBizType("occupation");
            r.setBizId(o.getId());
            r.setTitle(expired ? "占用已到期未解除" : "占用即将到期");
            r.setContent("占用单 #" + o.getId() + " 资产 " + o.getAssetId()
                    + " 到期日 " + o.getEndDate() + (expired ? "，请尽快解除" : ""));
            alertService.trigger(r);
            n++;
        }
        return n;
    }

    private OccupationOrder require(Long id) {
        OccupationOrder order = occupationOrderMapper.selectById(id);
        if (order == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return order;
    }
}
