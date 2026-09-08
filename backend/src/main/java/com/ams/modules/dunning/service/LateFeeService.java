package com.ams.modules.dunning.service;

import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.entity.LateFee;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.billing.mapper.LateFeeMapper;
import com.ams.modules.config.service.ConfigVersionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 滞纳金计息（FR-DUN-LATE-*）：日利率/宽限期/封顶比例可配置。
 */
@Service
public class LateFeeService {

    private final BillMapper billMapper;
    private final LateFeeMapper lateFeeMapper;
    private final ConfigVersionService configVersionService;

    public LateFeeService(
            BillMapper billMapper,
            LateFeeMapper lateFeeMapper,
            ConfigVersionService configVersionService) {
        this.billMapper = billMapper;
        this.lateFeeMapper = lateFeeMapper;
        this.configVersionService = configVersionService;
    }

    /** 从配置读取参数后计息。 */
    @Transactional
    public int accrueLateFeesFromConfig() {
        BigDecimal rate = parseDecimal(configVersionService.getValue("late_fee.daily_rate", "0.0005"),
                new BigDecimal("0.0005"));
        int graceDays = parseInt(configVersionService.getValue("late_fee.grace_days", "0"), 0);
        BigDecimal capRatio = parseDecimal(configVersionService.getValue("late_fee.cap_ratio", "0.5"),
                new BigDecimal("0.5"));
        return accrueLateFees(rate, graceDays, capRatio);
    }

    @Transactional
    public int accrueLateFees(BigDecimal dailyRate) {
        return accrueLateFees(dailyRate, 0, new BigDecimal("0.5"));
    }

    /**
     * @param dailyRate 日利率
     * @param graceDays 宽限期（天），逾期天数 ≤ 宽限不计息
     * @param capRatio  滞纳金上限 = 本金欠费 × 比例
     */
    @Transactional
    public int accrueLateFees(BigDecimal dailyRate, int graceDays, BigDecimal capRatio) {
        BigDecimal rate = dailyRate == null ? new BigDecimal("0.0005") : dailyRate;
        BigDecimal cap = capRatio == null ? new BigDecimal("0.5") : capRatio;
        List<Bill> bills = billMapper.selectList(
                new LambdaQueryWrapper<Bill>()
                        .in(Bill::getStatus, BillStatus.UNPAID, BillStatus.PARTIAL_PAID)
                        .lt(Bill::getDueDate, LocalDate.now()));
        int accrued = 0;
        for (Bill bill : bills) {
            BigDecimal paid = bill.getPaidAmount() == null ? BigDecimal.ZERO : bill.getPaidAmount();
            BigDecimal reduced = bill.getReducedAmount() == null ? BigDecimal.ZERO : bill.getReducedAmount();
            BigDecimal principalDue = bill.getAmount().subtract(paid).subtract(reduced);
            if (principalDue.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            long overdueDays = ChronoUnit.DAYS.between(bill.getDueDate(), LocalDate.now());
            if (overdueDays <= graceDays) {
                continue;
            }
            long chargeDays = overdueDays - graceDays;
            BigDecimal fee = principalDue.multiply(rate)
                    .multiply(BigDecimal.valueOf(chargeDays))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal maxFee = principalDue.multiply(cap).setScale(2, RoundingMode.HALF_UP);
            if (fee.compareTo(maxFee) > 0) {
                fee = maxFee;
            }

            LateFee lateFee = new LateFee();
            lateFee.setBillId(bill.getId());
            lateFee.setRateSnapshot(rate);
            lateFee.setPeriodStart(bill.getDueDate().plusDays(1 + graceDays));
            lateFee.setPeriodEnd(LocalDate.now());
            lateFee.setFeeAmount(fee);
            lateFee.setStatus("accruing");
            lateFeeMapper.insert(lateFee);

            bill.setLateFeeAmount(fee);
            billMapper.updateById(bill);
            accrued++;
        }
        return accrued;
    }

    public List<LateFee> listByBill(Long billId) {
        return lateFeeMapper.selectList(
                new LambdaQueryWrapper<LateFee>().eq(LateFee::getBillId, billId));
    }

    private static BigDecimal parseDecimal(String v, BigDecimal def) {
        try {
            return new BigDecimal(v);
        } catch (Exception e) {
            return def;
        }
    }

    private static int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return def;
        }
    }
}
