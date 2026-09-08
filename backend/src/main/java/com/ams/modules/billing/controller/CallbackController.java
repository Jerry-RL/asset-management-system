package com.ams.modules.billing.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.billing.service.WechatPayService;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.service.ESignService;
import com.ams.modules.invoice.entity.Invoice;
import com.ams.modules.invoice.service.InvoiceService;
import com.ams.platform.integration.esign.ESignAdapter;
import com.ams.platform.integration.invoice.DigitalInvoiceAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第三方回调（微信支付 / 电子签 / 数电发票等）。
 */
@RestController
@RequestMapping("/api/v1/callbacks")
public class CallbackController {

    private final WechatPayService wechatPayService;
    private final ESignService eSignService;
    private final ESignAdapter eSignAdapter;
    private final InvoiceService invoiceService;
    private final DigitalInvoiceAdapter invoiceAdapter;
    private final ObjectMapper objectMapper;

    public CallbackController(
            WechatPayService wechatPayService,
            ESignService eSignService,
            ESignAdapter eSignAdapter,
            InvoiceService invoiceService,
            DigitalInvoiceAdapter invoiceAdapter,
            ObjectMapper objectMapper) {
        this.wechatPayService = wechatPayService;
        this.eSignService = eSignService;
        this.eSignAdapter = eSignAdapter;
        this.invoiceService = invoiceService;
        this.invoiceAdapter = invoiceAdapter;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/wechat-pay")
    public ApiResponse<Map<String, String>> wechatPay(
            @RequestBody String rawBody,
            @RequestHeader Map<String, String> headers) throws Exception {
        JsonNode node = objectMapper.readTree(rawBody == null || rawBody.isBlank() ? "{}" : rawBody);
        // 兼容简化回调：{ outTradeNo, transactionId } 与微信 resource 解密后结构
        String outTradeNo = text(node, "outTradeNo", "out_trade_no");
        String txnId = text(node, "transactionId", "transaction_id");
        if (node.has("resource") && node.get("resource").has("out_trade_no")) {
            outTradeNo = node.get("resource").path("out_trade_no").asText(outTradeNo);
            txnId = node.get("resource").path("transaction_id").asText(txnId);
        }
        Map<String, String> normalizedHeaders = new HashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> normalizedHeaders.put(k, v));
        }
        wechatPayService.handleNotify(outTradeNo, txnId, normalizedHeaders, rawBody);
        return ApiResponse.ok(Map.of("result", "SUCCESS"), TraceIdUtil.get());
    }

    @PostMapping("/esign")
    public ApiResponse<Contract> esign(
            @RequestBody String rawBody,
            @RequestHeader Map<String, String> headers) throws Exception {
        Map<String, String> normalizedHeaders = new HashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> normalizedHeaders.put(k, v));
        }
        if (!eSignAdapter.verifyCallback(normalizedHeaders, rawBody)) {
            return ApiResponse.ok(null, TraceIdUtil.get());
        }
        JsonNode node = objectMapper.readTree(rawBody == null || rawBody.isBlank() ? "{}" : rawBody);
        String flowId = text(node, "flowId", "flow_id", "signFlowId");
        String status = text(node, "status", "signStatus");
        Long evidenceFileId = node.hasNonNull("evidenceFileId")
                ? node.get("evidenceFileId").asLong()
                : null;
        return ApiResponse.ok(eSignService.handleCallback(flowId, status, evidenceFileId), TraceIdUtil.get());
    }

    @PostMapping("/invoice")
    public ApiResponse<Invoice> invoice(
            @RequestBody String rawBody,
            @RequestHeader Map<String, String> headers) throws Exception {
        Map<String, String> normalizedHeaders = new HashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> normalizedHeaders.put(k, v));
        }
        if (!invoiceAdapter.verifyCallback(normalizedHeaders, rawBody)) {
            return ApiResponse.ok(null, TraceIdUtil.get());
        }
        JsonNode node = objectMapper.readTree(rawBody == null || rawBody.isBlank() ? "{}" : rawBody);
        String thirdPartyNo = text(node, "thirdPartyNo", "third_party_no", "platformNo");
        String status = text(node, "status");
        String invoiceNo = text(node, "invoiceNo", "invoice_no");
        String pdfUrl = text(node, "pdfUrl", "pdf_url");
        String failReason = text(node, "failReason", "fail_reason");
        return ApiResponse.ok(
                invoiceService.handleCallback(thirdPartyNo, status, invoiceNo, pdfUrl, failReason),
                TraceIdUtil.get());
    }

    private static String text(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node.hasNonNull(key)) {
                return node.get(key).asText();
            }
        }
        return null;
    }
}
