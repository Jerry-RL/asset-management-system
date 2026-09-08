package com.ams.modules.billing.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.lease.entity.Tenant;
import com.ams.modules.lease.mapper.TenantMapper;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.platform.integration.wechat.WechatPayClient;
import com.ams.platform.security.SecurityUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 微信支付统一下单与回调入账（FR-MPU-004）。
 */
@Service
public class WechatPayService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final BillMapper billMapper;
    private final PaymentService paymentService;
    private final AllocationService allocationService;
    private final WechatPayClient wechatPayClient;
    private final UserMapper userMapper;
    private final TenantMapper tenantMapper;

    public WechatPayService(
            BillMapper billMapper,
            PaymentService paymentService,
            AllocationService allocationService,
            WechatPayClient wechatPayClient,
            UserMapper userMapper,
            TenantMapper tenantMapper) {
        this.billMapper = billMapper;
        this.paymentService = paymentService;
        this.allocationService = allocationService;
        this.wechatPayClient = wechatPayClient;
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
    }

    @Transactional
    public Map<String, Object> createJsapiPayment(List<Long> billIds, String strategy) {
        if (billIds == null || billIds.isEmpty()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请选择待缴账单");
        }
        List<Bill> bills = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        Long contractId = null;
        Long tenantId = null;
        for (Long billId : billIds) {
            Bill bill = billMapper.selectById(billId);
            if (bill == null) {
                throw new AppException(ErrorCode.NOT_FOUND, "账单不存在: " + billId);
            }
            BigDecimal due = dueOf(bill);
            if (due.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (contractId == null) {
                contractId = bill.getContractId();
                tenantId = bill.getTenantId();
            } else if (!contractId.equals(bill.getContractId())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "一次支付仅支持同一合同账单");
            }
            bills.add(bill);
            total = total.add(due);
        }
        if (bills.isEmpty() || total.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(ErrorCode.BUSINESS_ERROR, "所选账单无需支付");
        }

        String openid = resolvePayerOpenid(tenantId);
        String outTradeNo = "WX" + LocalDateTime.now().format(NO_FMT)
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        Payment payment = new Payment();
        payment.setPaymentNo("SK" + outTradeNo.substring(2));
        payment.setContractId(contractId);
        payment.setTenantId(tenantId);
        payment.setAmount(total);
        payment.setMethod("wechat");
        payment.setChannel("user_mp");
        payment.setConfirmStatus("pending");
        payment.setPaidAt(LocalDateTime.now());
        payment.setSource("system");
        payment.setOutTradeNo(outTradeNo);
        payment.setPayerOpenid(openid);
        payment.setRemark("微信支付:" + billIds);
        paymentService.insert(payment);

        WechatPayClient.JsapiPrepayResult prepay = wechatPayClient.createJsapiPrepay(
                openid, outTradeNo, total, "账单缴费");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paymentId", payment.getId());
        result.put("outTradeNo", outTradeNo);
        result.put("amount", total);
        result.put("mock", prepay.mock());
        result.putAll(prepay.payParams());

        // Mock：同步回调入账，便于本地联调
        if (prepay.mock()) {
            confirmPaid(payment.getId(), prepay.mockTxnId(), strategy == null ? "specified" : strategy, billIds);
            result.put("paid", true);
        } else {
            result.put("paid", false);
            // 真实支付：客户端调起后等回调；指定账单策略暂存于 remark
            payment.setRemark("strategy=" + (strategy == null ? "specified" : strategy)
                    + ";billIds=" + billIds);
            paymentService.update(payment);
        }
        return result;
    }

    @Transactional
    public void handleNotify(String outTradeNo, String transactionId, Map<String, String> headers, String rawBody) {
        if (!wechatPayClient.verifyNotify(rawBody, headers)) {
            throw new AppException(ErrorCode.FORBIDDEN, "微信支付回调验签失败");
        }
        Payment payment = paymentService.findByOutTradeNo(outTradeNo);
        if (payment == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "支付单不存在");
        }
        if ("confirmed".equals(payment.getConfirmStatus())) {
            return; // 幂等
        }
        List<Long> billIds = parseBillIds(payment.getRemark());
        String strategy = parseStrategy(payment.getRemark());
        confirmPaid(payment.getId(), transactionId, strategy, billIds);
    }

    private void confirmPaid(Long paymentId, String txnId, String strategy, List<Long> billIds) {
        Payment payment = paymentService.confirmArrival(paymentId, null);
        if (txnId != null) {
            payment.setThirdPartyTxnId(txnId);
            paymentService.update(payment);
        }
        allocationService.allocate(payment, strategy == null ? "specified" : strategy, billIds, true);
    }

    private String resolvePayerOpenid(Long tenantId) {
        Long userId = SecurityUtils.currentUserIdOrNull();
        if (userId != null) {
            User user = userMapper.selectById(userId);
            if (user != null && user.getWechatOpenid() != null && !user.getWechatOpenid().isBlank()) {
                return user.getWechatOpenid();
            }
            if (user != null && user.getTenantId() != null) {
                tenantId = user.getTenantId();
            }
        }
        if (tenantId != null) {
            Tenant tenant = tenantMapper.selectById(tenantId);
            if (tenant != null && tenant.getWechatOpenid() != null && !tenant.getWechatOpenid().isBlank()) {
                return tenant.getWechatOpenid();
            }
        }
        if (wechatPayClient.isMock()) {
            return "mock_openid_pay";
        }
        throw new AppException(ErrorCode.UNAUTHORIZED, "当前账号未绑定微信 openid，无法发起支付");
    }

    private BigDecimal dueOf(Bill bill) {
        BigDecimal principal = nz(bill.getAmount()).subtract(nz(bill.getPaidAmount())).subtract(nz(bill.getReducedAmount()));
        BigDecimal late = nz(bill.getLateFeeAmount()).subtract(nz(bill.getLateFeePaidAmount()));
        return principal.add(late).max(BigDecimal.ZERO);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static List<Long> parseBillIds(String remark) {
        if (remark == null) {
            return List.of();
        }
        int idx = remark.indexOf("billIds=");
        if (idx < 0) {
            // createJsapiPayment mock path stores "微信支付:[1, 2]"
            int bracket = remark.indexOf('[');
            if (bracket >= 0) {
                String inside = remark.substring(bracket + 1, remark.indexOf(']'));
                List<Long> ids = new ArrayList<>();
                for (String p : inside.split(",")) {
                    String t = p.trim();
                    if (!t.isEmpty()) {
                        ids.add(Long.valueOf(t));
                    }
                }
                return ids;
            }
            return List.of();
        }
        String part = remark.substring(idx + 8);
        int end = part.indexOf(';');
        if (end > 0) {
            part = part.substring(0, end);
        }
        part = part.replace("[", "").replace("]", "");
        List<Long> ids = new ArrayList<>();
        for (String p : part.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                ids.add(Long.valueOf(t));
            }
        }
        return ids;
    }

    private static String parseStrategy(String remark) {
        if (remark == null || !remark.contains("strategy=")) {
            return "specified";
        }
        String part = remark.substring(remark.indexOf("strategy=") + 9);
        int end = part.indexOf(';');
        return end > 0 ? part.substring(0, end) : part;
    }
}
