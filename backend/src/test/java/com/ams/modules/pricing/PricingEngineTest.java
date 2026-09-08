package com.ams.modules.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.ams.modules.contract.entity.Contract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 定价引擎单元测试（FR-PRICE-007 首末月折算、FR-PRICE-008 尾差）。
 */
class PricingEngineTest {

    private final PricingEngine engine = new PricingEngine();

    private Contract contract(LocalDate start, LocalDate end, String rentType, BigDecimal amount, String cycle) {
        Contract c = new Contract();
        c.setStartDate(start);
        c.setEndDate(end);
        c.setRentType(rentType);
        c.setRentAmount(amount);
        c.setPaymentCycle(cycle);
        c.setFreeRentDays(0);
        c.setGraceDays(0);
        c.setProrationBase("calendar");
        c.setIncreaseRate(BigDecimal.ZERO);
        return c;
    }

    @Test
    void fullYearMonthlyGeneratesTwelvePlans() {
        Contract c = contract(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "fixed_monthly", new BigDecimal("1000"), "monthly");
        List<PricingEngine.PlanItem> plans = engine.generate(c);
        assertThat(plans).hasSize(12);
        BigDecimal sum = plans.stream().map(PricingEngine.PlanItem::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(new BigDecimal("12000.00"));
    }

    @Test
    void partialFirstMonthProratesByCalendarDays() {
        // 3/15 起租，首月 3/15~3/31 = 17 天，分母 31 天自然日
        Contract c = contract(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 31),
                "fixed_monthly", new BigDecimal("3100"), "monthly");
        List<PricingEngine.PlanItem> plans = engine.generate(c);
        assertThat(plans).hasSize(1);
        // 3100 * 17 / 31 = 1700.00
        assertThat(plans.get(0).amount()).isEqualByComparingTo(new BigDecimal("1700.00"));
    }

    @Test
    void fixed30ProrationDenominator() {
        Contract c = contract(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 31),
                "fixed_monthly", new BigDecimal("3000"), "monthly");
        c.setProrationBase("fixed_30");
        List<PricingEngine.PlanItem> plans = engine.generate(c);
        // 3000 * 17 / 30 = 1700.00
        assertThat(plans.get(0).amount()).isEqualByComparingTo(new BigDecimal("1700.00"));
    }

    @Test
    void tailDifferenceIsAbsorbedInLastPeriod() {
        // 年租 10000，月缴 → 月租金 = 10000/12 = 833.3333...
        // 12 期舍入后合计 9999.96，尾差 0.04 并入末期 → 末期 833.37
        Contract c = contract(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "fixed_yearly", new BigDecimal("10000"), "monthly");
        List<PricingEngine.PlanItem> plans = engine.generate(c);
        assertThat(plans).hasSize(12);
        BigDecimal sum = plans.stream().map(PricingEngine.PlanItem::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Σ计划 = 合同总额 10000.00
        assertThat(sum).isEqualByComparingTo(new BigDecimal("10000.00"));
        // 末期吸收尾差
        assertThat(plans.get(11).amount()).isEqualByComparingTo(new BigDecimal("833.37"));
    }
}
