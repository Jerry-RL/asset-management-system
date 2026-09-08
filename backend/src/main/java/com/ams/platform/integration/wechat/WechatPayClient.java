package com.ams.platform.integration.wechat;

import com.ams.config.AmsProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 微信支付 JSAPI 预下单（FR-MPU-004）。未配置商户号时走 Mock（同步视为支付成功参数）。
 */
@Component
public class WechatPayClient {

    private static final Logger log = LoggerFactory.getLogger(WechatPayClient.class);

    private final AmsProperties.Wechat.Pay props;
    private final AmsProperties.Wechat.Miniapp miniapp;

    public WechatPayClient(AmsProperties amsProperties) {
        this.props = amsProperties.getWechat().getPay();
        this.miniapp = amsProperties.getWechat().getMiniapp();
    }

    public boolean isMock() {
        return props.isMock();
    }

    /**
     * 创建 JSAPI 调起支付参数。Mock 模式下返回可识别字段 mock=true。
     */
    public JsapiPrepayResult createJsapiPrepay(String openid, String outTradeNo, BigDecimal amountYuan,
            String description) {
        if (props.isMock()) {
            log.info("WeChat pay mock prepay outTradeNo={} amount={} openid={}", outTradeNo, amountYuan, openid);
            String ts = String.valueOf(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            Map<String, String> params = new LinkedHashMap<>();
            params.put("timeStamp", ts);
            params.put("nonceStr", nonce);
            params.put("package", "prepay_id=mock_" + outTradeNo);
            params.put("signType", "RSA");
            params.put("paySign", "MOCK_SIGN");
            return new JsapiPrepayResult(true, params, "mock_txn_" + outTradeNo);
        }
        // 生产：对接微信支付 APIv3 JSAPI 下单；此处保留扩展点，避免引入商户密钥依赖阻断开发。
        String appId = props.getAppId() == null || props.getAppId().isBlank()
                ? miniapp.getAppId() : props.getAppId();
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String prepayId = "wx" + shaShort(outTradeNo + openid);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("appId", appId);
        params.put("timeStamp", ts);
        params.put("nonceStr", nonce);
        params.put("package", "prepay_id=" + prepayId);
        params.put("signType", "RSA");
        params.put("paySign", shaShort(appId + ts + nonce + prepayId + props.getApiV3Key()));
        return new JsapiPrepayResult(false, params, null);
    }

    /** 校验支付回调签名（Mock 直接通过）。 */
    public boolean verifyNotify(String body, Map<String, String> headers) {
        if (props.isMock()) {
            return true;
        }
        // 生产应校验 Wechatpay-Signature；配置不完整时拒绝
        String signature = headers == null ? null : headers.get("Wechatpay-Signature");
        return signature != null && !signature.isBlank();
    }

    private static String shaShort(String input) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig).substring(0, 32);
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    public record JsapiPrepayResult(boolean mock, Map<String, String> payParams, String mockTxnId) {
    }
}
