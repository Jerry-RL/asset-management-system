package com.ams.platform.observability.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ams.platform.observability.AppLog;
import com.ams.platform.observability.dto.AppLogIngestRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 上报内容校验与截断测试（设计 §7.2）。
 *
 * <p>本测试守着一条核心原则：<strong>宁可存一条字段被截断的日志，也不要丢弃整条</strong>。
 * 日志的价值在于事后能查到；因为某个字段太长/格式不对就整条丢弃，等于在最需要线索的时候
 * 把线索扔掉。只有「无法归属到任何端/来源/级别」这种无法补救的情况才拒绝。
 */
class AppLogIngestGuardTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 12, 20, 0);

    private final AppLogIngestGuard guard = new AppLogIngestGuard(new ObjectMapper());

    private AppLogIngestRequest baseRequest() {
        AppLogIngestRequest request = new AppLogIngestRequest();
        request.setTraceId("3f2a1c9b8e7d6f5a4b3c2d1e0f9a8b7c");
        request.setLevel("ERROR");
        request.setAppType("h5-tenant");
        request.setSource("api");
        request.setMessage("GET /api/v1/bills 失败：500");
        request.setOccurredAt("2026-09-12T11:55:00Z");
        return request;
    }

    @Test
    @DisplayName("合法请求：字段原样归一化，clientIp 来自服务端参数")
    void normalizesValidRequest() {
        AppLog log = guard.normalize(baseRequest(), "10.1.2.3", NOW);

        assertThat(log.getAppType()).isEqualTo("h5-tenant");
        assertThat(log.getSource()).isEqualTo("api");
        assertThat(log.getLevel()).isEqualTo("ERROR");
        assertThat(log.getTraceId()).isEqualTo("3f2a1c9b8e7d6f5a4b3c2d1e0f9a8b7c");
        assertThat(log.getClientIp()).isEqualTo("10.1.2.3");
        assertThat(log.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("level 缺省为 ERROR")
    void defaultsLevelToError() {
        AppLogIngestRequest request = baseRequest();
        request.setLevel(null);
        assertThat(guard.normalize(request, "1.1.1.1", NOW).getLevel()).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("appType 非法 -> 拒绝（400）")
    void rejectsUnknownAppType() {
        AppLogIngestRequest request = baseRequest();
        request.setAppType("x");
        assertThatThrownBy(() -> guard.normalize(request, "1.1.1.1", NOW))
                .isInstanceOf(AppLogIngestGuard.RejectedException.class);
    }

    @Test
    @DisplayName("appType=backend 由端侧上报 -> 拒绝：伪造「后端故障」会把排查引向错误方向")
    void rejectsBackendAppTypeFromClient() {
        AppLogIngestRequest request = baseRequest();
        request.setAppType("backend");
        assertThatThrownBy(() -> guard.normalize(request, "1.1.1.1", NOW))
                .isInstanceOf(AppLogIngestGuard.RejectedException.class);
    }

    @Test
    @DisplayName("source / level 非法 -> 拒绝（400）")
    void rejectsUnknownSourceAndLevel() {
        AppLogIngestRequest badSource = baseRequest();
        badSource.setSource("sql");
        assertThatThrownBy(() -> guard.normalize(badSource, "1.1.1.1", NOW))
                .isInstanceOf(AppLogIngestGuard.RejectedException.class);

        AppLogIngestRequest badLevel = baseRequest();
        badLevel.setLevel("FATAL");
        assertThatThrownBy(() -> guard.normalize(badLevel, "1.1.1.1", NOW))
                .isInstanceOf(AppLogIngestGuard.RejectedException.class);
    }

    @Test
    @DisplayName("traceId 非法/缺失 -> 服务端生成，而不是拒绝整条日志")
    void replacesInvalidTraceId() {
        for (String bad : new String[] {null, "", "short", "有中文的traceid", "with space here"}) {
            AppLogIngestRequest request = baseRequest();
            request.setTraceId(bad);
            AppLog log = guard.normalize(request, "1.1.1.1", NOW);
            assertThat(log.getTraceId())
                    .as("traceId=%s 应被替换为服务端生成值", bad)
                    .matches("^[0-9a-f]{32}$");
        }
    }

    @Test
    @DisplayName("超长字段被截断而非丢弃整条")
    void truncatesLongFields() {
        AppLogIngestRequest request = baseRequest();
        request.setMessage("m".repeat(5000));
        request.setUrl("u".repeat(2000));
        request.setUa("a".repeat(2000));

        AppLog log = guard.normalize(request, "1.1.1.1", NOW);

        assertThat(log.getMessage()).hasSize(AppLogIngestGuard.MAX_MESSAGE_LENGTH);
        assertThat(log.getUrl()).hasSize(AppLogIngestGuard.MAX_URL_LENGTH);
        assertThat(log.getUa()).hasSize(AppLogIngestGuard.MAX_UA_LENGTH);
    }

    @Test
    @DisplayName("extra 超限 -> 整块丢弃并标记 truncated（保留半个 JSON 只会误导）")
    void dropsOversizedExtra() {
        AppLogIngestRequest request = baseRequest();
        request.setExtra(Map.of("stack", "s".repeat(AppLogIngestGuard.MAX_EXTRA_BYTES + 100)));

        AppLog log = guard.normalize(request, "1.1.1.1", NOW);

        assertThat(log.getExtra()).isEqualTo("{\"truncated\":true}");
    }

    @Test
    @DisplayName("extra 正常时原样序列化，并保留客户端字段")
    void serializesExtra() {
        AppLogIngestRequest request = baseRequest();
        request.setExtra(Map.of("method", "GET", "status", 500));

        AppLog log = guard.normalize(request, "1.1.1.1", NOW);

        assertThat(log.getExtra()).contains("\"method\":\"GET\"").contains("\"status\":500");
    }

    @Test
    @DisplayName("端侧时钟偏差超过 24h -> 用服务端时间并在 extra 标记 clockSkew")
    void flagsClockSkew() {
        AppLogIngestRequest request = baseRequest();
        request.setOccurredAt("2026-01-01T00:00:00Z");
        request.setExtra(Map.of("method", "GET"));

        AppLog log = guard.normalize(request, "1.1.1.1", NOW);

        assertThat(log.getOccurredAt()).isEqualTo(NOW);
        assertThat(log.getExtra()).contains("\"clockSkew\":true");
    }

    @Test
    @DisplayName("occurredAt 缺失或不可解析 -> 用服务端时间，不标记偏差")
    void fallsBackToServerTime() {
        AppLogIngestRequest missing = baseRequest();
        missing.setOccurredAt(null);
        assertThat(guard.normalize(missing, "1.1.1.1", NOW).getOccurredAt()).isEqualTo(NOW);

        AppLogIngestRequest malformed = baseRequest();
        malformed.setOccurredAt("2026/09/12 20:00");
        AppLog log = guard.normalize(malformed, "1.1.1.1", NOW);
        assertThat(log.getOccurredAt()).isEqualTo(NOW);
        assertThat(log.getExtra()).isNull();
    }

    @Test
    @DisplayName("occurredAt 在容忍范围内 -> 采用端侧时间（保留发生时刻的线索）")
    void keepsClientTimeWithinTolerance() {
        AppLogIngestRequest request = baseRequest();
        // NOW 是 2026-09-12 20:00（本地时区），用同一时刻的 ISO 字符串
        request.setOccurredAt(NOW.atZone(java.time.ZoneId.systemDefault()).toInstant().toString());

        AppLog log = guard.normalize(request, "1.1.1.1", NOW);

        assertThat(log.getOccurredAt()).isEqualTo(NOW);
        assertThat(log.getExtra()).isNull();
    }
}
