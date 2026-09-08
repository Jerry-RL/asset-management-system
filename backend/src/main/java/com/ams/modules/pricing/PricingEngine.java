package com.ams.modules.pricing;

import com.ams.modules.contract.entity.Contract;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 定价与缴费计划引擎（DSD §4.2 / FR-PRICE-*）。
 *
 * 支持：
 *  - 缴费周期切分（月/季/年，月缴按自然月切分）
 *  - 首末月不足月按日折算（FR-PRICE-007，分母 calendar 当月自然日 / fixed_30 固定 30 天）
 *  - 免租期扣除
 *  - 递增（简化：按年递增比例）
 *  - 尾差调整（FR-PRICE-008）：每期金额四舍五入到 2 位小数后，舍入尾差并入末期，
 *    保证全周期应收合计 = 合同总额精确相等。
 */
@Component
public class PricingEngine {

    /**
     * 生成缴费计划项（不落库，由调用方持久化）。
     */
    public List<PlanItem> generate(Contract contract) {
        List<Period> periods = splitByCycle(contract.getStartDate(), contract.getEndDate(),
                contract.getPaymentCycle());

        // 原始金额（未舍入）
        List<BigDecimal> rawAmounts = new ArrayList<>();
        for (Period p : periods) {
            rawAmounts.add(rawAmount(p, contract));
        }
        BigDecimal totalRaw = rawAmounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        // 每期舍入到 2 位小数
        List<BigDecimal> rounded = new ArrayList<>();
        for (BigDecimal raw : rawAmounts) {
            rounded.add(raw.setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal sumRounded = rounded.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        // 尾差调整：并入末期
        BigDecimal tailDiff = totalRaw.subtract(sumRounded);
        if (!rounded.isEmpty() && tailDiff.compareTo(BigDecimal.ZERO) != 0) {
            int last = rounded.size() - 1;
            rounded.set(last, rounded.get(last).add(tailDiff).setScale(2, RoundingMode.HALF_UP));
        }

        List<PlanItem> items = new ArrayList<>();
        for (int i = 0; i < periods.size(); i++) {
            Period p = periods.get(i);
            items.add(new PlanItem(p.start(), p.end(), rounded.get(i),
                    p.start().minusDays(contract.getGraceDays() == null ? 0 : contract.getGraceDays())));
        }
        return items;
    }

    /** 合同全周期租金总额（对齐尾差基准）。 */
    public BigDecimal totalAmount(Contract contract) {
        List<Period> periods = splitByCycle(contract.getStartDate(), contract.getEndDate(),
                contract.getPaymentCycle());
        return periods.stream().map(p -> rawAmount(p, contract))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 原始金额（未舍入，含免租/递增/折算）。 */
    private BigDecimal rawAmount(Period p, Contract contract) {
        BigDecimal monthly = monthlyRent(contract);
        long days = daysInPeriod(p);
        int freeDays = contract.getFreeRentDays() == null ? 0 : contract.getFreeRentDays();
        if (freeDays > 0) {
            days = Math.max(0, days - Math.min(freeDays, days));
        }
        if (days <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = applyIncrease(contract, p.start());
        BigDecimal base = monthly.multiply(rate);
        if (isPartialPeriod(p)) {
            long denom = denominator(p.start(), contract);
            return base.multiply(BigDecimal.valueOf(days))
                    .divide(BigDecimal.valueOf(denom), 8, RoundingMode.HALF_UP);
        }
        return base;
    }

    /** 月租金口径（不同租金类型归一为月租金）。 */
    private BigDecimal monthlyRent(Contract contract) {
        BigDecimal amount = contract.getRentAmount();
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        return switch (contract.getRentType()) {
            case "fixed_yearly" -> amount.divide(BigDecimal.valueOf(12), 8, RoundingMode.HALF_UP);
            case "per_area" -> amount.multiply(
                    contract.getLeaseArea() == null ? BigDecimal.ONE : contract.getLeaseArea());
            default -> amount; // fixed_monthly / per_unit / negotiable
        };
    }

    private BigDecimal applyIncrease(Contract contract, LocalDate start) {
        BigDecimal rate = contract.getIncreaseRate() == null ? BigDecimal.ZERO : contract.getIncreaseRate();
        if (rate.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ONE;
        }
        long years = start.getYear() - contract.getStartDate().getYear();
        if (years <= 0) {
            return BigDecimal.ONE;
        }
        return BigDecimal.ONE.add(rate).pow((int) years);
    }

    /** 周期是否为整月/整周期（首末不足整期才折算）。 */
    private boolean isPartialPeriod(Period p) {
        boolean startIsFirst = p.start().getDayOfMonth() == 1;
        boolean endIsLast = p.end().getDayOfMonth() == p.end().lengthOfMonth();
        return !startIsFirst || !endIsLast;
    }

    private long denominator(LocalDate start, Contract contract) {
        if ("fixed_30".equals(contract.getProrationBase())) {
            return 30;
        }
        return start.lengthOfMonth(); // calendar 当月自然日
    }

    private long daysInPeriod(Period p) {
        return java.time.temporal.ChronoUnit.DAYS.between(p.start(), p.end()) + 1;
    }

    /**
     * 周期切分：
     *  - monthly：按自然月切分（首末月不足整月即折算）
     *  - quarterly / yearly：自起租日按整季/整年滚动
     */
    private List<Period> splitByCycle(LocalDate start, LocalDate end, String cycle) {
        List<Period> periods = new ArrayList<>();
        if ("monthly".equals(cycle)) {
            LocalDate cursor = start;
            while (!cursor.isAfter(end)) {
                LocalDate monthEnd = cursor.withDayOfMonth(cursor.lengthOfMonth());
                if (monthEnd.isAfter(end)) {
                    monthEnd = end;
                }
                periods.add(new Period(cursor, monthEnd));
                cursor = monthEnd.plusDays(1);
            }
            return periods;
        }
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            LocalDate periodEnd = cycleEnd(cursor, cycle);
            if (periodEnd.isAfter(end)) {
                periodEnd = end;
            }
            periods.add(new Period(cursor, periodEnd));
            cursor = periodEnd.plusDays(1);
        }
        return periods;
    }

    private LocalDate cycleEnd(LocalDate start, String cycle) {
        return switch (cycle) {
            case "yearly" -> start.plusYears(1).minusDays(1);
            case "quarterly" -> start.plusMonths(3).minusDays(1);
            default -> start.plusMonths(1).minusDays(1);
        };
    }

    /** 周期切分结果。 */
    public record Period(LocalDate start, LocalDate end) {
    }

    /** 缴费计划项。 */
    public record PlanItem(LocalDate periodStart, LocalDate periodEnd, BigDecimal amount, LocalDate dueDate) {
    }
}
