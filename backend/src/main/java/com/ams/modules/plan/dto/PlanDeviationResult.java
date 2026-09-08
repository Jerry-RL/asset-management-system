package com.ams.modules.plan.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 经营计划 vs 实际偏差结果（FR-PLAN）。 */
@Data
public class PlanDeviationResult {

    private Long planId;
    private Long companyId;
    private Integer planYear;
    private Integer planMonth;
    private BigDecimal threshold;
    private boolean overdue;
    private List<MetricDeviation> metrics = new ArrayList<>();

    @Data
    public static class MetricDeviation {
        private String metric;
        private BigDecimal target;
        private BigDecimal actual;
        private BigDecimal deviationRatio;
        private boolean exceeded;
    }
}
