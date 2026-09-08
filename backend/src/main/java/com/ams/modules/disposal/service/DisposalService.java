package com.ams.modules.disposal.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.service.LeaseControlService;
import com.ams.modules.disposal.entity.DisposalOrder;
import com.ams.modules.disposal.mapper.DisposalOrderMapper;
import com.ams.platform.approval.ApprovalEngine;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资产处置全生命周期闭环（FR-DISP-*，§4.24.5）。
 * 流程：处置申请 → 审批 → 执行 → 已退出 → 档案封存。
 */
@Service
public class DisposalService {

    private final DisposalOrderMapper disposalOrderMapper;
    private final LeaseControlService leaseControlService;
    private final ApprovalEngine approvalEngine;

    public DisposalService(
            DisposalOrderMapper disposalOrderMapper,
            LeaseControlService leaseControlService,
            ApprovalEngine approvalEngine) {
        this.disposalOrderMapper = disposalOrderMapper;
        this.leaseControlService = leaseControlService;
        this.approvalEngine = approvalEngine;
    }

    public PageResult<DisposalOrder> page(long page, long pageSize, String status) {
        Page<DisposalOrder> result = disposalOrderMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<DisposalOrder>()
                        .eq(status != null, DisposalOrder::getStatus, status)
                        .orderByDesc(DisposalOrder::getId));
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    /** 处置申请（FR-DISP-001）。 */
    public DisposalOrder create(DisposalOrder order) {
        // 在租/占用资产须先处理（FR-DISP-004）
        order.setStatus("draft");
        disposalOrderMapper.insert(order);
        return order;
    }

    /** 提交审批（FR-DISP-002）。 */
    @Transactional
    public DisposalOrder submit(Long id) {
        DisposalOrder order = require(id);
        order.setStatus("approving");
        disposalOrderMapper.updateById(order);
        approvalEngine.start("disposal", id);
        return order;
    }

    /** 审批通过 → 待执行 + 租控处置中。 */
    @Transactional
    public void onApproved(Long id) {
        DisposalOrder order = require(id);
        order.setStatus("pending_execute");
        disposalOrderMapper.updateById(order);
        leaseControlService.transition(order.getAssetId(), LeaseControlStatus.DISPOSING,
                "disposal", id, "处置审批通过");
    }

    /** 执行登记（FR-DISP-003）。 */
    @Transactional
    public DisposalOrder execute(Long id, BigDecimal actualAmount, String counterparty) {
        DisposalOrder order = require(id);
        order.setStatus("executing");
        order.setActualAmount(actualAmount);
        order.setCounterparty(counterparty);
        disposalOrderMapper.updateById(order);
        return order;
    }

    /** 完成 → 已退出（FR-DISP-004/005）。 */
    @Transactional
    public DisposalOrder complete(Long id) {
        DisposalOrder order = require(id);
        order.setStatus("completed");
        disposalOrderMapper.updateById(order);
        leaseControlService.transition(order.getAssetId(), LeaseControlStatus.EXITED,
                "disposal", id, "处置完成，资产已退出");
        return order;
    }

    private DisposalOrder require(Long id) {
        DisposalOrder order = disposalOrderMapper.selectById(id);
        if (order == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return order;
    }
}
