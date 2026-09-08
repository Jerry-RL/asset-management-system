package com.ams.modules.dunning.service;

import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.entity.LateFee;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.billing.mapper.LateFeeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 滞纳金计息（FR-DUN-LATE-*）：每日滚动计息（单利，默认不复利）。
 * 仅对本金欠费计息；结清/减免/终止/作废停止；利率快照留痕。
 */
@Service
public class LateFeeService {

    private final BillMapper billMapper;
    private final LateFeeMapper lateFeeMapper;

    public LateFeeService(BillMapper billMapper, LateFeeMapper lateFeeMapper) {
        this.billMapper = billMapper;
        this.lateFeeMapper = lateFeeMapper;
    }

    /**
     * 每日计息任务（DSD §4.4 lateFeeJob）。
     * @param dailyRate 日利率（可配，默认 0.0005）
     */
    @Transactional
    public int accrueLateFees(BigDecimal dailyRate) {
        BigDecimal rate = dailyRate == null ? new BigDecimal("0.0005") : dailyRate;
        List<Bill> bills = billMapper.selectList(
                new LambdaQueryWrapper<Bill>()
                        .in(Bill::getStatus, BillStatus.UNPAID, BillStatus.PARTIAL_PAID)
                        .lt(Bill::getDueDate, LocalDate.now()));
        int accrued = 0;
        for (Bill bill : bills) {
            BigDecimal principalDue = bill.getAmount().subtract(bill.getPaidAmount());
            if (principalDue.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            long overdueDays = ChronoUnit.DAYS.between(bill.getDueDate(), LocalDate.now());
            if (overdueDays <= 0) {
                continue;
            }
            // fee = principal * rate * days（单利）
            BigDecimal fee = principalDue.multiply(rate)
                    .multiply(BigDecimal.valueOf(overdueDays))
                    .setScale(2, RoundingMode.HALF_UP);

            LateFee lateFee = new LateFee();
            lateFee.setBillId(bill.getId());
            lateFee.setRateSnapshot(rate);
            lateFee.setPeriodStart(bill.getDueDate().plusDays(1));
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
}
