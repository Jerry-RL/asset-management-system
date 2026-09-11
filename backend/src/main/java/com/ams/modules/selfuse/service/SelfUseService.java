package com.ams.modules.selfuse.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.OccupancyType;
import com.ams.modules.asset.entity.AssetUnit;
import com.ams.modules.asset.service.AssetOccupancyService;
import com.ams.modules.asset.service.AssetUnitService;
import com.ams.modules.alert.entity.AlertRecord;
import com.ams.modules.alert.service.AlertService;
import com.ams.modules.selfuse.entity.SelfUseOrder;
import com.ams.modules.selfuse.mapper.SelfUseOrderMapper;
import com.ams.modules.revitalization.service.RevitalizationService;
import com.ams.platform.approval.ApprovalEngine;
import com.ams.platform.event.ApprovalCompletedEvent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资产自用闭环（FR-SELF-*）：空置 → 自用申请 → 审批 → 自用 → 结束 → 空置。
 *
 * <p>与 {@code OccupationService} 同构：占用粒度是<b>计租单元</b>，写入占用生效层，
 * 租控状态由 {@code LeaseStatusDeriver} 派生（ADR-0019 决策 A/C）。
 * 原实现经 {@code LeaseControlService.transition} 改整资产状态，属改造清单触点 6。
 */
@Service
public class SelfUseService {

    /** 来源单据类型（{@code asset_occupancy.subject_type}），与 V40 回填口径一致。 */
    private static final String SUBJECT = AssetOccupancyService.SUBJECT_SELF_USE;

    private final SelfUseOrderMapper selfUseOrderMapper;
    private final AssetOccupancyService occupancyService;
    private final AssetUnitService assetUnitService;
    private final ApprovalEngine approvalEngine;
    private final RevitalizationService revitalizationService;
    private final AlertService alertService;

    public SelfUseService(
            SelfUseOrderMapper selfUseOrderMapper,
            AssetOccupancyService occupancyService,
            AssetUnitService assetUnitService,
            ApprovalEngine approvalEngine,
            RevitalizationService revitalizationService,
            AlertService alertService) {
        this.selfUseOrderMapper = selfUseOrderMapper;
        this.occupancyService = occupancyService;
        this.assetUnitService = assetUnitService;
        this.approvalEngine = approvalEngine;
        this.revitalizationService = revitalizationService;
        this.alertService = alertService;
    }

    public List<SelfUseOrder> list() {
        return selfUseOrderMapper.selectList(
                new LambdaQueryWrapper<SelfUseOrder>().orderByDesc(SelfUseOrder::getId));
    }

    /** 自用申请：语义同为「资产存在可租单元即可发起」，支持部分自用（原为整资产必须空置）。 */
    @Transactional
    public SelfUseOrder create(SelfUseOrder order) {
        if (order.getAssetId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "资产不能为空");
        }
        resolveUnit(order.getAssetId());
        order.setStatus("draft");
        selfUseOrderMapper.insert(order);
        return order;
    }

    /** 提交审批：登记排他预留（防并发超租）。 */
    @Transactional
    public SelfUseOrder submit(Long id) {
        SelfUseOrder order = require(id);
        AssetUnit unit = resolveUnit(order.getAssetId());
        occupancyService.reserve(unit.getId(), OccupancyType.SELF_USE, SUBJECT, id,
                order.getStartDate(), order.getEndDate(), "自用申请提交审批");
        order.setStatus("approving");
        selfUseOrderMapper.updateById(order);
        approvalEngine.start("self_use", id);
        return order;
    }

    /** 审批通过 → 自用：预留转生效。 */
    @Transactional
    public SelfUseOrder approve(Long id) {
        SelfUseOrder order = require(id);
        if (occupancyService.activateBySubject(SUBJECT, id) == 0) {
            // 未经预留直接审批（历史数据 / 人工端点跳过 submit）：补建生效占用
            AssetUnit unit = resolveUnit(order.getAssetId());
            occupancyService.occupy(unit.getId(), OccupancyType.SELF_USE, SUBJECT, id,
                    order.getStartDate(), order.getEndDate(), null, "自用审批通过（补建）");
        }
        order.setStatus("self_use");
        selfUseOrderMapper.updateById(order);
        return order;
    }

    /**
     * 撤销申请（审批驳回 / 撤回）：收口预留，单据回到草稿。
     *
     * <p><b>为什么必须有可达路径</b>：{@link #submit} 会写入排他预留行；若驳回后不收口，
     * 该行会永久锁住单元（{@code EXCLUDE} 拒绝任何后续占用），与 P0-5 同类。
     * 因此本方法同时暴露为控制器端点，并由 {@link #onApprovalCompleted} 在驳回时自动调用。
     */
    @Transactional
    public SelfUseOrder withdraw(Long id, String remark) {
        SelfUseOrder order = require(id);
        occupancyService.cancelReservation(SUBJECT, id, LocalDate.now(), remark);
        order.setStatus("draft");
        selfUseOrderMapper.updateById(order);
        return order;
    }

    /** 审批驳回 → 自动收口预留（审批通过仍由既有 {@code /approve} 端点推进）。 */
    @EventListener
    @Transactional
    public void onApprovalCompleted(ApprovalCompletedEvent event) {
        if (event.isApproved() || !"self_use".equals(event.getBizType())) {
            return;
        }
        SelfUseOrder order = selfUseOrderMapper.selectById(event.getBizId());
        if (order == null || !"approving".equals(order.getStatus())) {
            return;
        }
        withdraw(order.getId(), "审批驳回，释放预留");
    }

    /** 结束自用：区间收口。 */
    @Transactional
    public SelfUseOrder end(Long id) {
        SelfUseOrder order = require(id);
        order.setStatus("ended");
        selfUseOrderMapper.updateById(order);
        occupancyService.releaseBySubject(SUBJECT, id, null, "结束自用");
        revitalizationService.createOnVacant(order.getAssetId(), "自用结束", "招租盘活");
        return order;
    }

    /** 到期扫描（FR-SELF-003）。 */
    @Transactional
    public int scanExpiry(int withinDays) {
        LocalDate today = LocalDate.now();
        LocalDate threshold = today.plusDays(withinDays);
        List<SelfUseOrder> list = selfUseOrderMapper.selectList(
                new LambdaQueryWrapper<SelfUseOrder>()
                        .eq(SelfUseOrder::getStatus, "self_use")
                        .isNotNull(SelfUseOrder::getEndDate)
                        .le(SelfUseOrder::getEndDate, threshold));
        int n = 0;
        for (SelfUseOrder o : list) {
            boolean expired = o.getEndDate().isBefore(today) || o.getEndDate().isEqual(today);
            AlertRecord r = new AlertRecord();
            r.setAlertType("self_use_expiry");
            r.setSubType(expired ? "expired" : "expiring");
            r.setLevel(expired ? 3 : 2);
            r.setBizType("self_use");
            r.setBizId(o.getId());
            r.setTitle(expired ? "自用已到期未结束" : "自用即将到期");
            r.setContent("自用单 #" + o.getId() + " 资产 " + o.getAssetId()
                    + " 到期日 " + o.getEndDate());
            alertService.trigger(r);
            n++;
        }
        return n;
    }

    /** 解析标的计租单元（承载原 {@code assertVacant} 的校验职责）。 */
    private AssetUnit resolveUnit(Long assetId) {
        return assetUnitService.resolveForLease(assetId, null);
    }

    private SelfUseOrder require(Long id) {
        SelfUseOrder order = selfUseOrderMapper.selectById(id);
        if (order == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return order;
    }
}
