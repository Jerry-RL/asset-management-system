package com.ams.modules.occupation.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.OccupancyType;
import com.ams.modules.asset.entity.AssetUnit;
import com.ams.modules.asset.service.AssetOccupancyService;
import com.ams.modules.asset.service.AssetUnitService;
import com.ams.modules.alert.entity.AlertRecord;
import com.ams.modules.alert.service.AlertService;
import com.ams.modules.occupation.entity.OccupationOrder;
import com.ams.modules.occupation.mapper.OccupationOrderMapper;
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
 * 临时占用闭环（FR-OCC-*，§4.24.6）：空置 → 占用申请 → 审批 → 占用 → 解除 → 空置。
 *
 * <p><b>占用粒度是计租单元，不是资产</b>（ADR-0019 决策 A/C）。原实现经
 * {@code LeaseControlService.transition} 把<b>整个资产</b>置为占用，导致：
 * 部分占用无法表达；且与占用生效层（{@code asset_occupancy}）形成两处真相。
 * 现改为：
 * <ul>
 *   <li>提交审批 → {@code reserve}（{@code biz_status=reserving}，排他占位，
 *       审批期即进入 {@code EXCLUDE} 判定，堵住「两个申请同时签同一单元」）；</li>
 *   <li>审批通过 → {@code activateBySubject} 转正；</li>
 *   <li>解除 → {@code releaseBySubject} 收口；</li>
 *   <li>租控状态由 {@code LeaseStatusDeriver} 从占用集合派生，本类不写状态列。</li>
 * </ul>
 */
@Service
public class OccupationService {

    /** 来源单据类型（{@code asset_occupancy.subject_type}），与 V40 回填口径一致。 */
    private static final String SUBJECT = AssetOccupancyService.SUBJECT_OCCUPATION;

    private final OccupationOrderMapper occupationOrderMapper;
    private final AssetOccupancyService occupancyService;
    private final AssetUnitService assetUnitService;
    private final ApprovalEngine approvalEngine;
    private final RevitalizationService revitalizationService;
    private final AlertService alertService;

    public OccupationService(
            OccupationOrderMapper occupationOrderMapper,
            AssetOccupancyService occupancyService,
            AssetUnitService assetUnitService,
            ApprovalEngine approvalEngine,
            RevitalizationService revitalizationService,
            AlertService alertService) {
        this.occupationOrderMapper = occupationOrderMapper;
        this.occupancyService = occupancyService;
        this.assetUnitService = assetUnitService;
        this.approvalEngine = approvalEngine;
        this.revitalizationService = revitalizationService;
        this.alertService = alertService;
    }

    public List<OccupationOrder> list() {
        return occupationOrderMapper.selectList(
                new LambdaQueryWrapper<OccupationOrder>().orderByDesc(OccupationOrder::getId));
    }

    /**
     * 占用申请（FR-OCC-001）。
     *
     * <p>语义修正：原为「仅<b>整资产</b>空置可发起」（{@code assertVacant}），
     * 现为「资产存在<b>可租单元</b>即可发起」——支持部分占用场景；
     * 区间是否冲突由提交审批时的 {@code EXCLUDE} 约束给出确定性结论（见 {@link #submit}）。
     */
    @Transactional
    public OccupationOrder create(OccupationOrder order) {
        if (order.getAssetId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "资产不能为空");
        }
        resolveUnit(order.getAssetId());
        order.setStatus("draft");
        occupationOrderMapper.insert(order);
        return order;
    }

    /**
     * 提交审批：登记排他预留（防并发超租）。
     *
     * <p>预留失败（单元在该区间已被占）会抛 {@code CONFLICT}，单据保持 draft 不进入审批——
     * 这正是「约束优先于纪律」：冲突由数据库判定，而非靠服务层先查后写。
     */
    @Transactional
    public OccupationOrder submit(Long id) {
        OccupationOrder order = require(id);
        AssetUnit unit = resolveUnit(order.getAssetId());
        occupancyService.reserve(unit.getId(), OccupancyType.OCCUPATION, SUBJECT, id,
                order.getStartDate(), order.getEndDate(), "占用申请提交审批");
        order.setStatus("approving");
        occupationOrderMapper.updateById(order);
        approvalEngine.start("occupation", id);
        return order;
    }

    /** 审批通过 → 占用（FR-OCC-002）：预留转生效。 */
    @Transactional
    public OccupationOrder approve(Long id) {
        OccupationOrder order = require(id);
        if (occupancyService.activateBySubject(SUBJECT, id) == 0) {
            // 未经预留直接审批（历史数据 / 人工端点跳过 submit）：补建生效占用，
            // 否则会出现「单据已占用、占用表无记录」的两处真相。
            AssetUnit unit = resolveUnit(order.getAssetId());
            occupancyService.occupy(unit.getId(), OccupancyType.OCCUPATION, SUBJECT, id,
                    order.getStartDate(), order.getEndDate(), null, "占用审批通过（补建）");
        }
        order.setStatus("occupied");
        occupationOrderMapper.updateById(order);
        return order;
    }

    /**
     * 撤销申请（审批驳回 / 撤回）：收口预留，单据回到草稿。
     *
     * <p><b>为什么必须有可达路径</b>：{@link #submit} 会写入排他预留行；若驳回后不收口，
     * 该行会永久锁住单元（{@code EXCLUDE} 拒绝任何后续占用），与 P0-5「组合租赁占用永不收口」
     * 是同类缺陷。因此本方法同时暴露为控制器端点，并由 {@link #onApprovalCompleted} 在驳回时自动调用。
     */
    @Transactional
    public OccupationOrder withdraw(Long id, String remark) {
        OccupationOrder order = require(id);
        occupancyService.cancelReservation(SUBJECT, id, LocalDate.now(), remark);
        order.setStatus("draft");
        occupationOrderMapper.updateById(order);
        return order;
    }

    /**
     * 审批驳回 → 自动收口预留。
     *
     * <p>只处理 {@code approved=false}：审批通过仍由既有 {@code /approve} 端点推进
     * （保持前端既有交互不变），本监听器仅负责消除预留泄漏。
     */
    @EventListener
    @Transactional
    public void onApprovalCompleted(ApprovalCompletedEvent event) {
        if (event.isApproved() || !"occupation".equals(event.getBizType())) {
            return;
        }
        OccupationOrder order = occupationOrderMapper.selectById(event.getBizId());
        if (order == null || !"approving".equals(order.getStatus())) {
            return;
        }
        withdraw(order.getId(), "审批驳回，释放预留");
    }

    /** 解除占用（FR-OCC-003）→ 空置。 */
    @Transactional
    public OccupationOrder release(Long id) {
        OccupationOrder order = require(id);
        order.setStatus("released");
        occupationOrderMapper.updateById(order);
        // 按单据全量收口：occupation_order 目前单单元，但接口按单元集合设计（组合场景不回归）
        occupancyService.releaseBySubject(SUBJECT, id, null, "解除占用");
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

    /** 解析标的计租单元；无可租单元时抛出可读错误（承载原 {@code assertVacant} 的校验职责）。 */
    private AssetUnit resolveUnit(Long assetId) {
        return assetUnitService.resolveForLease(assetId, null);
    }

    private OccupationOrder require(Long id) {
        OccupationOrder order = occupationOrderMapper.selectById(id);
        if (order == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return order;
    }
}
