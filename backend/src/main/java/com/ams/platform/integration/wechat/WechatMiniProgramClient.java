package com.ams.platform.integration.wechat;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.config.AmsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 微信小程序 code2session（FR-MPU-001）。未配置 appId/secret 时走开发 Mock。
 */
@Component
public class WechatMiniProgramClient {

    private static final Logger log = LoggerFactory.getLogger(WechatMiniProgramClient.class);

    private final AmsProperties.Wechat.Miniapp props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public WechatMiniProgramClient(AmsProperties amsProperties, ObjectMapper objectMapper) {
        this.props = amsProperties.getWechat().getMiniapp();
        this.objectMapper = objectMapper;
    }

    public SessionResult code2session(String code) {
        if (code == null || code.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "缺少微信授权 code");
        }
        if (props.isMock()) {
            String openid = "mock_openid_" + sha256Short(code);
            log.info("WeChat miniapp mock code2session, openid={}", openid);
            return new SessionResult(openid, "mock_session_key", null);
        }
        try {
            String url = "https://api.weixin.qq.com/sns/jscode2session"
                    + "?appid=" + URLEncoder.encode(props.getAppId(), StandardCharsets.UTF_8)
                    + "&secret=" + URLEncoder.encode(props.getAppSecret(), StandardCharsets.UTF_8)
                    + "&js_code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                    + "&grant_type=authorization_code";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode node = objectMapper.readTree(response.body());
            if (node.hasNonNull("errcode") && node.get("errcode").asInt() != 0) {
                throw new AppException(ErrorCode.THIRD_PARTY_ERROR,
                        "微信登录失败: " + node.path("errmsg").asText());
            }
            String openid = node.path("openid").asText(null);
            if (openid == null || openid.isBlank()) {
                throw new AppException(ErrorCode.THIRD_PARTY_ERROR, "微信未返回 openid");
            }
            return new SessionResult(openid, node.path("session_key").asText(null),
                    node.path("unionid").asText(null));
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ErrorCode.THIRD_PARTY_ERROR, "微信 code2session 调用失败: " + e.getMessage());
        }
    }

    private static String sha256Short(String input) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", dig[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    public record SessionResult(String openid, String sessionKey, String unionid) {
    }
}
