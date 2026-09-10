package com.ams.modules.asset.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 项目详情页聚合视图（FR-AST-001）。
 *
 * <p>把详情页所需的全部派生指标一次算好，避免前端为「资产基本信息 / 资产创收 / 租赁概况 /
 * 分区下资产」发起多次请求并重复聚合。统计口径与列表页保持一致，见
 * {@link com.ams.modules.asset.AssetLeaseGroups}。
 *
 * <p>金额一律以「元」返回（前端按万元换算展示），百分比保留一位小数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectOverview {

    /** 项目主体（含资产统计与所属公司名称） */
    private ProjectDetail project;

    private Metrics metrics;

    /** 分区维度汇总，用于底部「分区 / 楼层」区域与 KPI 联动 */
    private List<ZoneSummary> zones;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProjectDetail {
        private Long id;
        private String name;
        private String type;
        private Integer status;
        private String address;
        private String province;
        private String city;
        private String district;
        private BigDecimal longitude;
        private BigDecimal latitude;
        private String imageUrl;
        private Long imageFileId;
        private Long companyId;
        /** 所属公司名称 */
        private String companyName;
        private java.time.LocalDateTime createdAt;
        /** 分区数 */
        private long zoneCount;
        /** 楼层数（去重后的 floor_no 数量） */
        private long floorCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Metrics {

        // ---- 资产基本信息 ----
        /** 资产利用率(%)：按宗数计，(宗数 - 闲置宗数) / 宗数 */
        private BigDecimal utilizationRate;
        /** 固定资产(㎡)：资产面积合计 */
        private BigDecimal totalArea;
        /** 资产宗数 */
        private long assetCount;
        /** 闲置宗数：空置 + 招租中 */
        private long idleCount;
        /** 在用宗数：宗数 - 闲置宗数 */
        private long inUseCount;
        /** 租控状态分布（环形图） */
        private List<Slice> leaseStatusBreakdown;
        /** 资产类型分布（环形图） */
        private List<Slice> assetTypeBreakdown;

        // ---- 资产创收 ----
        /** 累计实收(元) */
        private BigDecimal accumulatedReceived;
        /** 本年实收(元) */
        private BigDecimal yearReceived;
        /** 近一年每月实收(元)，按时间升序、缺月补 0，固定 12 项 */
        private List<MonthlyAmount> monthlyReceived;

        // ---- 租赁概况 ----
        /** 资产出租率(%)：按宗数计（在租 + 部分出租）/ 宗数，与 DashboardService#leasedRate 口径一致 */
        private BigDecimal leaseRate;
        /** 实际出租宗数：在租 + 部分出租 */
        private long rentedCount;
        /** 未出租宗数：宗数 - 实际出租宗数 */
        private long unrentedCount;
        /** 上月收缴率(%)：上月实收 / 上月应收 */
        private BigDecimal lastMonthCollectRate;
        /** 上月应收(元)：上月应付账单本金 - 减免 */
        private BigDecimal lastMonthReceivable;
        /** 上月实收(元)：上月应付账单的已核销本金 */
        private BigDecimal lastMonthReceived;
        /** 上月月份标签，如 2026-08 */
        private String lastMonthLabel;
    }

    /** 环形图/图例切片：原始字典值 + 计数 + 面积，文案由前端按字典映射 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Slice {
        private String value;
        private long count;
        private BigDecimal area;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlyAmount {
        /** yyyy-MM */
        private String month;
        private BigDecimal amount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ZoneSummary {
        private Long id;
        private String name;
        private String code;
        private Integer sort;
        /** 分区下资产宗数 */
        private long assetCount;
        /** 分区下资产面积合计(㎡) */
        private BigDecimal assetArea;
        /** 闲置宗数：空置 + 招租中 */
        private long idleCount;
        /** 在用宗数 */
        private long inUseCount;
        /** 楼层数（去重） */
        private long floorCount;
        /** 租控状态 → 宗数（底部图例 chips） */
        private Map<String, Long> statusCounts;
    }
}
