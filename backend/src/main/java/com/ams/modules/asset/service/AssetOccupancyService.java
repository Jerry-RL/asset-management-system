package com.ams.modules.asset.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.OccupancyBizStatus;
import com.ams.modules.asset.OccupancyType;
import com.ams.modules.asset.entity.AssetOccupancy;
import com.ams.modules.asset.entity.AssetUnit;
import com.ams.modules.asset.mapper.AssetOccupancyMapper;
import com.ams.modules.asset.mapper.AssetUnitMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 占用生效层的唯一写入入口（ADR-0019 决策 C、ADR-0020 决策 E）。
 *
 * <p>合同、临时占用、自用、处置四条业务链路的「占用/解除」全部经过本类，
 * 取代原先散落在 6 个模块的 12 处 {@code LeaseControlService.transition} 调用。
 *
 * <p>正确性由两层保证：
 * <ol>
 *   <li>数据库 {@code EXCLUDE} 约束 {@code ex_occupancy_unit_no_overlap} —— 最终防线，对任何写入口生效；</li>
 *   <li>本类 {@link #assertUnitAvailable} —— 只负责把冲突转成可读提示，不作为正确性依赖。</li>
 * </ol>
 *
 * <p><b>占用写入时机（ADR-0020 决策 E）</b>：
 * <ol>
 *   <li>单据<b>提交审批</b> → {@link #reserve}（{@code biz_status = reserving}，{@code exclusive = true}）。
 *       审批期即进入 {@code EXCLUDE} 判定，堵住「两个 approving 合同同时签同一单元」的并发超租；</li>
 *   <li>审批<b>通过</b> → {@link #activateBySubject}（{@code reserving → active}）；</li>
 *   <li>审批<b>驳回/撤回</b> → {@link #cancelReservation}（区间收口）。</li>
 * </ol>
 *
 * <p><b>释放一律按单元或按单据全量</b>：组合租赁一单多单元（ADR-0020 决策 C），
 * 只释放一条会造成其余单元永久不收口（评审 P0-5）。
 */
@Service
public class AssetOccupancyService {

    /** 合同来源单据类型（与 {@code asset_occupancy.subject_type} 取值一致）。 */
    public static final String SUBJECT_CONTRACT = "contract";
    /** 来源单据类型常量，避免各调用点散落字面量。 */
    public static final String SUBJECT_OCCUPATION = "occupation_order";
    public static final String SUBJECT_SELF_USE = "self_use_order";
    public static final String SUBJECT_DISPOSAL = "disposal_order";

    /**
     * 开放区间上界哨兵：把「无期限」占用表达为可比较区间用于重叠查询。
     * 数据库中仍存 {@code NULL}（交由 {@code daterange} 解释为上无界），
     * 因此哨兵值只出现在查询参数里，不落库。
     */
    public static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);

    private final AssetOccupancyMapper occupancyMapper;
    private final AssetUnitMapper unitMapper;
    private final LeaseStatusDeriver deriver;

    public AssetOccupancyService(
            AssetOccupancyMapper occupancyMapper,
            AssetUnitMapper unitMapper,
            LeaseStatusDeriver deriver) {
        this.occupancyMapper = occupancyMapper;
        this.unitMapper = unitMapper;
        this.deriver = deriver;
    }

    // ------------------------------------------------------------------
    // 写入：占用 / 预留 / 生效 / 取消预留
    // ------------------------------------------------------------------

    /**
     * 登记一条生效占用（{@code biz_status = active}）。
     *
     * <p>调用时机：单据审批通过（或历史数据登记）。审批中的占位请用
     * {@link #reserve}，否则审批期不产生排他行，并发超租窗口会重新出现。
     *
     * @param unitId      计租单元（原子单位）
     * @param type        {@link OccupancyType} 之一
     * @param subjectType 来源单据类型（contract / occupation_order / self_use_order / disposal_order）
     * @param subjectId   来源单据 ID
     * @param dateFrom    占用开始日；为空取当天
     * @param dateTo      占用结束日；为空表示无固定期限（在租合同）
     * @param bizStatus   {@link OccupancyBizStatus}；为空取 active
     */
    @Transactional
    public AssetOccupancy occupy(Long unitId, String type, String subjectType, Long subjectId,
            LocalDate dateFrom, LocalDate dateTo, String bizStatus, String remark) {
        OccupancyType.assertValid(type);
        if (unitId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "计租单元不能为空");
        }
        if (subjectType == null || subjectType.isBlank() || subjectId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "占用来源单据不能为空");
        }

        // 行锁：并发占用同一单元时串行化「校验 → 插入」，把偶发冲突变成确定性提示
        if (unitMapper.lockForUpdate(unitId) == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "计租单元不存在: " + unitId);
        }

        LocalDate from = dateFrom == null ? LocalDate.now() : dateFrom;
        if (dateTo != null && !dateTo.isAfter(from)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "占用结束日必须晚于开始日");
        }
        assertUnitAvailable(unitId, from, dateTo);

        AssetUnit unit = unitMapper.selectById(unitId);
        AssetOccupancy occ = new AssetOccupancy();
        occ.setAssetId(unit.getAssetId());
        occ.setAssetUnitId(unitId);
        occ.setOccupancyType(type);
        occ.setSubjectType(subjectType);
        occ.setSubjectId(subjectId);
        // 决策 A1（原子单元）：占用面积恒等于单元面积，不由调用方传入，杜绝面积口径分叉
        occ.setArea(unit.getArea() == null ? BigDecimal.ZERO : unit.getArea());
        occ.setDateFrom(from);
        occ.setDateTo(dateTo);
        occ.setBizStatus(bizStatus == null ? OccupancyBizStatus.ACTIVE : bizStatus);
        occ.setExclusive(true);
        occ.setRemark(remark);

        try {
            occupancyMapper.insert(occ);
        } catch (DataIntegrityViolationException ex) {
            // EXCLUDE 约束兜底：服务层校验被绕过的路径（导入、迁移脚本、并发）在此收口
            throw new AppException(ErrorCode.CONFLICT, overlapMessage(from, dateTo));
        }

        deriver.refresh(unit.getAssetId());
        return occ;
    }

    /**
     * 审批中预留（{@code biz_status = reserving}）：单据<b>提交审批</b>时调用。
     *
     * <p>预留行 {@code exclusive = true}，与生效占用同等参与互斥，因此审批期即可拒绝
     * 第二个同单元的申请；展示口径（租控状态）排除预留，见 {@link OccupancyBizStatus}。
     */
    @Transactional
    public AssetOccupancy reserve(Long unitId, String type, String subjectType, Long subjectId,
            LocalDate dateFrom, LocalDate dateTo, String remark) {
        return occupy(unitId, type, subjectType, subjectId, dateFrom, dateTo,
                OccupancyBizStatus.RESERVING, remark);
    }

    /**
     * 审批通过：把来源单据的 {reserving} 行转为 {active}。
     *
     * @return 实际转正的行数（组合租赁为单元数）
     */
    @Transactional
    public int activateBySubject(String subjectType, Long subjectId) {
        int n = 0;
        for (AssetOccupancy occ : occupancyMapper.selectOpenBySubject(subjectType, subjectId)) {
            if (OccupancyBizStatus.RESERVING.equals(occ.getBizStatus())) {
                occ.setBizStatus(OccupancyBizStatus.ACTIVE);
                occupancyMapper.updateById(occ);
                deriver.refresh(occ.getAssetId());
                n++;
            }
        }
        return n;
    }

    /**
     * 审批驳回 / 撤回：把来源单据的 {reserving} 行做区间收口。
     *
     * <p>只处理 {@code reserving} 行——已生效占用不受审批回退影响。
     *
     * @return 实际收口的行数
     */
    @Transactional
    public int cancelReservation(String subjectType, Long subjectId, LocalDate effectiveDate,
            String remark) {
        int n = 0;
        for (AssetOccupancy occ : occupancyMapper.selectOpenBySubject(subjectType, subjectId)) {
            if (OccupancyBizStatus.RESERVING.equals(occ.getBizStatus())) {
                closeRow(occ, effectiveDate, remark == null ? "预留取消" : remark);
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 释放：按 id / 按单元 / 按单据全量
    // ------------------------------------------------------------------

    /**
     * 区间收口（不删行）。退租完成、占用解除、处置完成时调用。
     *
     * @param effectiveDate 实际腾空日；为空取当天。早于开始日时顺延一天以满足区间下界 &lt; 上界。
     */
    @Transactional
    public AssetOccupancy release(Long occupancyId, LocalDate effectiveDate, String remark) {
        AssetOccupancy occ = require(occupancyId);
        if (occ.getDateTo() != null) {
            throw new AppException(ErrorCode.CONFLICT, "该占用已收口，无需重复释放");
        }
        closeRow(occ, effectiveDate, remark);
        return occ;
    }

    /**
     * 释放来源单据的<b>全部</b>未收口占用（退租完成、占用解除、处置完成）。
     *
     * <p>组合租赁（一合同 N 单元）必须走本方法：只释放一条会让其余单元被
     * {@code EXCLUDE} 永久锁死（评审 P0-5）。
     *
     * @return 实际收口的行数
     */
    @Transactional
    public int releaseBySubject(String subjectType, Long subjectId, LocalDate effectiveDate,
            String remark) {
        int n = 0;
        for (AssetOccupancy occ : occupancyMapper.selectOpenBySubject(subjectType, subjectId)) {
            closeRow(occ, effectiveDate, remark);
            n++;
        }
        return n;
    }

    /**
     * 释放来源单据在<b>指定单元</b>上的占用（部分退租 / 合同变更减面积）。
     *
     * @return 是否存在并收口
     */
    @Transactional
    public boolean releaseUnit(String subjectType, Long subjectId, Long unitId,
            LocalDate effectiveDate, String remark) {
        AssetOccupancy occ = occupancyMapper.selectOpenBySubjectUnit(subjectType, subjectId, unitId);
        if (occ == null) {
            return false;
        }
        closeRow(occ, effectiveDate, remark);
        return true;
    }

    /**
     * 标记「退租中」：租户尚未腾空，占用区间仍然有效。
     *
     * <p>取代原先 {@code LeaseControlStatus.VACATING}——那是一个把「阶段」塞进「占用状态」的取值，
     * 导致 {@code VacateService.apply} 必须 catch 掉自身的状态冲突才能走通。
     *
     * <p>组合租赁下逐单元标记，返回标记行数。
     */
    @Transactional
    public int markVacating(Long contractId) {
        int n = 0;
        for (AssetOccupancy occ : occupancyMapper.selectOpenBySubject(SUBJECT_CONTRACT, contractId)) {
            if (!OccupancyBizStatus.VACATING.equals(occ.getBizStatus())) {
                occ.setBizStatus(OccupancyBizStatus.VACATING);
                occupancyMapper.updateById(occ);
                deriver.refresh(occ.getAssetId());
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 校验单元在指定区间可占用（给出可读错误；正确性最终由 EXCLUDE 约束保证）。 */
    public void assertUnitAvailable(Long unitId, LocalDate from, LocalDate dateTo) {
        LocalDate to = dateTo == null ? OPEN_ENDED : dateTo;
        List<AssetOccupancy> overlaps = occupancyMapper.selectOverlapping(unitId, from, to);
        if (overlaps.isEmpty()) {
            return;
        }
        AssetOccupancy cur = overlaps.get(0);
        throw new AppException(ErrorCode.CONFLICT,
                "该单元已被【" + OccupancyType.label(cur.getOccupancyType()) + "】占用："
                        + cur.getDateFrom() + " ~ "
                        + (cur.getDateTo() == null ? "长期" : cur.getDateTo()));
    }

    /** 单元是否存在未收口占用（拆分单元前的前置校验；预留也算占用）。 */
    public boolean hasOpenOccupancy(Long unitId) {
        return occupancyMapper.countOpenByUnit(unitId) > 0;
    }

    /** 查来源单据的全部未收口占用（组合租赁可能多行）。 */
    public List<AssetOccupancy> listOpenBySubject(String subjectType, Long subjectId) {
        return occupancyMapper.selectOpenBySubject(subjectType, subjectId);
    }

    /**
     * 来源单据是否已登记过<b>任意</b>占用（含已收口）。
     *
     * <p>与 {@link #listOpenBySubject} 的区别：后者只认未收口（{@code date_to IS NULL}）。
     * 期初迁移写入的是<b>带租期的历史区间</b>（{@code date_to} 非空），因此幂等判重必须用本方法，
     * 否则重跑会重复插入并与 {@code EXCLUDE} 约束冲突。
     */
    public boolean hasAnyBySubject(String subjectType, Long subjectId) {
        return occupancyMapper.selectCount(new LambdaQueryWrapper<AssetOccupancy>()
                .eq(AssetOccupancy::getSubjectType, subjectType)
                .eq(AssetOccupancy::getSubjectId, subjectId)) > 0;
    }

    /** 资产占用时间轴（资产档案用）。 */
    public List<AssetOccupancy> listByAsset(Long assetId) {
        return occupancyMapper.listByAsset(assetId);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 收口一行占用。
     *
     * <p><b>保留</b>原 {@code biz_status} 而不改写为 active：区间已收口后该值不再参与
     * 任何判定（派生视图只看 {@code CURRENT_DATE} 落在区间内的行），保留它才能让时间轴
     * 区分「生效结束」与「预留取消」。预留取消的行 {@code date_to} 非空，
     * 因此不会被 {@code v_occupancy_reserving_orphan} 误报。
     */
    private void closeRow(AssetOccupancy occ, LocalDate effectiveDate, String remark) {
        LocalDate to = effectiveDate == null ? LocalDate.now() : effectiveDate;
        if (!to.isAfter(occ.getDateFrom())) {
            to = occ.getDateFrom().plusDays(1);
        }
        occ.setDateTo(to);
        occ.setReleasedAt(LocalDateTime.now());
        if (remark != null && !remark.isBlank()) {
            occ.setRemark(remark);
        }
        occupancyMapper.updateById(occ);
        deriver.refresh(occ.getAssetId());
    }

    private AssetOccupancy require(Long id) {
        AssetOccupancy occ = occupancyMapper.selectById(id);
        if (occ == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "占用记录不存在: " + id);
        }
        return occ;
    }

    private String overlapMessage(LocalDate from, LocalDate to) {
        return "该单元在 " + from + " ~ " + (to == null ? "长期" : to)
                + " 区间已被占用，请刷新后重试";
    }
}
