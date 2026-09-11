package com.ams.modules.asset;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import java.util.List;
import java.util.Set;

/**
 * 占用类型（{@code asset_occupancy.occupancy_type}）——四种「占用本质」，互斥。
 *
 * <p>与 {@link LeaseControlStatus} 的区别：租控状态是<b>派生结果</b>（含招租意向与生命周期），
 * 占用类型是<b>事实</b>。业务代码只应写占用，租控状态统一由
 * {@link com.ams.modules.asset.service.LeaseStatusDeriver} 从占用集合派生（ADR-0019 决策 B/C）。
 *
 * <p>以下四种之外的语义不进入本表：
 * <ul>
 *   <li>招租中（{@code leasing}）是「意向」，可并行多个，由 {@code lease_listing} 表达；</li>
 *   <li>抵押（{@code mortgage}）是「权属负担」，不与租赁互斥，只与处置/拆分互斥；</li>
 *   <li>已退出（{@code exited}）是资产生命周期终态，见 {@code asset.lifecycle_status}。</li>
 * </ul>
 */
public final class OccupancyType {

    private OccupancyType() {
    }

    /** 合同占用（含退租中）。 */
    public static final String CONTRACT = "contract";
    /** 自用占用。 */
    public static final String SELF_USE = "self_use";
    /** 临时占用。 */
    public static final String OCCUPATION = "occupation";
    /** 处置冻结。 */
    public static final String DISPOSAL = "disposal";

    public static final Set<String> ALL = Set.of(CONTRACT, SELF_USE, OCCUPATION, DISPOSAL);

    /**
     * 派生优先级（前者压制后者）：处置 &gt; 占用 &gt; 自用 &gt; 合同。
     * 用于「整资产全部单元被占用」时选取资产层展示状态。
     */
    public static final List<String> PRIORITY = List.of(DISPOSAL, OCCUPATION, SELF_USE, CONTRACT);

    /** 校验占用类型合法（非法值直接拒绝，避免脏值进入受约束的表）。 */
    public static void assertValid(String type) {
        if (type == null || !ALL.contains(type)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "占用类型非法: " + type);
        }
    }

    /** 面向用户的中文标签（错误提示与时间轴展示）。 */
    public static String label(String type) {
        return switch (type == null ? "" : type) {
            case CONTRACT -> "合同占用";
            case SELF_USE -> "自用";
            case OCCUPATION -> "临时占用";
            case DISPOSAL -> "处置";
            default -> String.valueOf(type);
        };
    }
}
