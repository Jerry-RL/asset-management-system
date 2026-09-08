package com.ams.modules.billing.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.mapper.PaymentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 收款记录（FR-BILL-005 / FR-MPW-010 现场收款到账确认）。
 * 用户端、工作端、PC 三端收款统一汇入（FR-FIN-006）。
 */
@Service
public class PaymentService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PaymentMapper paymentMapper;

    public PaymentService(PaymentMapper paymentMapper) {
        this.paymentMapper = paymentMapper;
    }

    public PageResult<Payment> page(long page, long pageSize, String channel, String confirmStatus) {
        Page<Payment> result = paymentMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<Payment>()
                        .eq(channel != null, Payment::getChannel, channel)
                        .eq(confirmStatus != null, Payment::getConfirmStatus, confirmStatus)
                        .orderByDesc(Payment::getId));
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    public Payment get(Long id) {
        Payment payment = paymentMapper.selectById(id);
        if (payment == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return payment;
    }

    /**
     * 工作端现场收款登记：status=待确认（不计实收、不核销，FR-MPW-010）。
     */
    public Payment registerWorkerPayment(Long contractId, Long tenantId, BigDecimal amount,
            String method) {
        Payment payment = new Payment();
        payment.setPaymentNo(generateNo());
        payment.setContractId(contractId);
        payment.setTenantId(tenantId);
        payment.setAmount(amount);
        payment.setMethod(method);
        payment.setChannel("worker_mp");
        payment.setConfirmStatus("pending");
        payment.setPaidAt(LocalDateTime.now());
        payment.setSource("system");
        paymentMapper.insert(payment);
        return payment;
    }

    /**
     * 财务到账确认：待确认 → 已到账（并入收款记录与统一对账）。
     */
    @Transactional
    public Payment confirmArrival(Long paymentId, Long confirmBy) {
        Payment payment = get(paymentId);
        if (!"pending".equals(payment.getConfirmStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "该收款已确认或状态异常");
        }
        payment.setConfirmStatus("confirmed");
        payment.setConfirmedAt(LocalDateTime.now());
        payment.setConfirmedBy(confirmBy);
        paymentMapper.updateById(payment);
        return payment;
    }

    /** PC/用户端直接登记已确认收款。 */
    public Payment registerConfirmed(Long contractId, Long tenantId, BigDecimal amount,
            String method, String channel) {
        Payment payment = new Payment();
        payment.setPaymentNo(generateNo());
        payment.setContractId(contractId);
        payment.setTenantId(tenantId);
        payment.setAmount(amount);
        payment.setMethod(method);
        payment.setChannel(channel == null ? "pc" : channel);
        payment.setConfirmStatus("confirmed");
        payment.setConfirmedAt(LocalDateTime.now());
        payment.setPaidAt(LocalDateTime.now());
        payment.setSource("system");
        paymentMapper.insert(payment);
        return payment;
    }

    public Payment insert(Payment payment) {
        if (payment.getPaymentNo() == null) {
            payment.setPaymentNo(generateNo());
        }
        paymentMapper.insert(payment);
        return payment;
    }

    public Payment findByOutTradeNo(String outTradeNo) {
        if (outTradeNo == null || outTradeNo.isBlank()) {
            return null;
        }
        return paymentMapper.selectOne(
                new LambdaQueryWrapper<Payment>().eq(Payment::getOutTradeNo, outTradeNo));
    }

    public void update(Payment payment) {
        paymentMapper.updateById(payment);
    }

    private String generateNo() {
        return "SK" + LocalDateTime.now().format(NO_FMT)
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }
}
