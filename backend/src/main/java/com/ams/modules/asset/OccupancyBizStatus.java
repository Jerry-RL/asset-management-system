package com.ams.modules.asset;

/**
 * 占用单据状态（{@code asset_occupancy.biz_status}）。
 *
 * <p>{@code vacating} 表示「退租中」：租户尚未腾空，占用区间<b>仍然有效</b>。
 * 它不是独立的租控状态，而是合同占用自身的阶段——这是 ADR-0019 决策 C 的核心，
 * 也是既有 {@code lease_control_status} 把「阶段」与「占用」混为一谈的修正。
 *
 * <p>{@code reserving} 表示「审批中预留」（ADR-0020 决策 E）：单据<b>提交审批</b>时即写入，
 * {@code exclusive = true} 因而与生效占用一样参与 {@code EXCLUDE} 互斥，堵住
 * 「两个 approving 合同同时签同一单元」的并发超租窗口。
 *
 * <p><b>两个口径必须区分</b>（混淆即产生缺陷）：
 * <ul>
 *   <li><b>可用性口径</b>（{@code OccupancyPort.isAvailable} / {@code assertUnitAvailable}）：
 *       预留算「被占」，因此查询 {@code selectOverlapping} 不得过滤 {@code reserving}；</li>
 *   <li><b>展示口径</b>（{@code v_unit_lease_status}）：预留<b>不算</b>在租，
 *       由 {@code reserved_area} 单列表达，避免「审批中即显示在租」（评审 P0-6）。</li>
 * </ul>
 */
public final class OccupancyBizStatus {

    private OccupancyBizStatus() {
    }

    /** 生效中。 */
    public static final String ACTIVE = "active";

    /** 退租中（仅 contract 使用）：占用仍在，等待区间收口。 */
    public static final String VACATING = "vacating";

    /** 审批中预留：排他占位，未批准前不计入租控展示口径。 */
    public static final String RESERVING = "reserving";
}
