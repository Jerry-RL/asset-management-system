package com.ams.modules.map.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.config.AmsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 地址 → 经纬度（高德地理编码 FR-AST-001）。
 *
 * <p>走高德 Web 服务 API，供 PC/H5/小程序端统一复用，避免各端各自持有 Key 与签名逻辑。
 */
@Component
public class GeocodeService {

    private static final Logger log = LoggerFactory.getLogger(GeocodeService.class);

    private static final String ENDPOINT = "https://restapi.amap.com/v3/geocode/geo";

    private final AmsProperties.Map props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public GeocodeService(AmsProperties amsProperties, ObjectMapper objectMapper) {
        this.props = amsProperties.getMap();
        this.objectMapper = objectMapper;
    }

    /** 地理编码结果：坐标为高德 GCJ-02 坐标系。 */
    public record GeocodeResult(
            BigDecimal longitude,
            BigDecimal latitude,
            String formattedAddress,
            String province,
            String city,
            String district,
            String level) {
    }

    /**
     * 按地址解析坐标。
     *
     * @param address 完整地址（省市区 + 详细地址）
     * @param city    城市限定词，提升匹配精度，可为空
     */
    public GeocodeResult geocode(String address, String city) {
        if (address == null || address.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请先填写详细地址");
        }
        String key = props.resolveWebServiceKey();
        if (key == null || key.isBlank()) {
            throw new AppException(ErrorCode.INTERNAL_ERROR,
                    "未配置高德地图 Key（AMAP_KEY），无法解析地址坐标");
        }
        try {
            StringBuilder url = new StringBuilder(ENDPOINT)
                    .append("?key=").append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                    .append("&address=")
                    .append(URLEncoder.encode(address.trim(), StandardCharsets.UTF_8));
            if (city != null && !city.isBlank()) {
                url.append("&city=").append(URLEncoder.encode(city.trim(), StandardCharsets.UTF_8));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode node = objectMapper.readTree(response.body());
            if (!"1".equals(node.path("status").asText())) {
                String info = node.path("info").asText("UNKNOWN");
                log.warn("高德地理编码失败: info={}, address={}", info, address);
                throw new AppException(ErrorCode.THIRD_PARTY_ERROR, "地址解析失败：" + info);
            }
            JsonNode geocodes = node.path("geocodes");
            if (!geocodes.isArray() || geocodes.isEmpty()) {
                throw new AppException(ErrorCode.NOT_FOUND,
                        "未匹配到该地址的坐标，请补充详细地址或手动填写");
            }
            JsonNode first = geocodes.get(0);
            // location 形如 "119.032872,33.591158"
            String[] parts = first.path("location").asText("").split(",");
            if (parts.length != 2) {
                throw new AppException(ErrorCode.THIRD_PARTY_ERROR, "地址解析结果缺少坐标，请手动填写");
            }
            return new GeocodeResult(
                    new BigDecimal(parts[0].trim()),
                    new BigDecimal(parts[1].trim()),
                    first.path("formatted_address").asText(null),
                    first.path("province").asText(null),
                    first.path("city").asText(null),
                    first.path("district").asText(null),
                    first.path("level").asText(null));
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ErrorCode.THIRD_PARTY_ERROR, "地址解析调用失败: " + e.getMessage());
        }
    }
}
