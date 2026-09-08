package com.ams.modules.selfuse.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.service.LeaseControlService;
import com.ams.modules.selfuse.entity.SelfUseOrder;
import com.ams.modules.selfuse.mapper.SelfUseOrderMapper;
import com.ams.platform.approval.ApprovalEngine;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资产自用闭环（FR-SELF-*）：空置 → 自用申请 → 审批 → 自用 → 结束 → 空置。
 */
@Service
public class SelfUseService {

    private final SelfUseOrderMapper selfUseOrderMapper;
    private final LeaseControlService leaseControlService;
    private final ApprovalEngine approvalEngine;

    public SelfUseService(
            SelfUseOrderMapper selfUseOrderMapper,
            LeaseControlService leaseControlService,
            ApprovalEngine approvalEngine) {
        this.selfUseOrderMapper = selfUseOrderMapper;
        this.leaseControlService = leaseControlService;
        this.approvalEngine = approvalEngine;
    }

    public List<SelfUseOrder> list() {
        return selfUseOrderMapper.selectList(
                new LambdaQueryWrapper<SelfUseOrder>().orderByDesc(SelfUseOrder::getId));
    }

    @Transactional
    public SelfUseOrder create(SelfUseOrder order) {
        leaseControlService.assertVacant(order.getAssetId());
        order.setStatus("draft");
        selfUseOrderMapper.insert(order);
        return order;
    }

    @Transactional
    public SelfUseOrder submit(Long id) {
        SelfUseOrder order = require(id);
        order.setStatus("approving");
        selfUseOrderMapper.updateById(order);
        approvalEngine.start("self_use", id);
        return order;
    }

    @Transactional
    public SelfUseOrder approve(Long id) {
        SelfUseOrder order = require(id);
        order.setStatus("self_use");
        selfUseOrderMapper.updateById(order);
        leaseControlService.transition(order.getAssetId(), LeaseControlStatus.SELF_USE,
                "self_use", id, "自用审批通过");
        return order;
    }

    @Transactional
    public SelfUseOrder end(Long id) {
        SelfUseOrder order = require(id);
        order.setStatus("ended");
        selfUseOrderMapper.updateById(order);
        leaseControlService.transition(order.getAssetId(), LeaseControlStatus.VACANT,
                "self_use", id, "结束自用");
        return order;
    }

    private SelfUseOrder require(Long id) {
        SelfUseOrder order = selfUseOrderMapper.selectById(id);
        if (order == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return order;
    }
}
