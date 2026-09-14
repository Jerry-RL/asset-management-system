package com.ams.platform.observability.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ams.platform.observability.AppLog;
import com.ams.platform.observability.ObservabilityProperties;
import com.ams.platform.observability.webhook.WebhookNotifier;
import com.ams.platform.observability.webhook.WebhookPayload;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * 告警阈值与冷却测试（设计 §10.1）。
 *
 * <p>两条同等重要、方向相反的验收：
 * <ul>
 *   <li><b>到阈值必须发</b>：否则「有告警」是假的，故障期没人被叫醒；</li>
 *   <li><b>冷却期内必须静默</b>：否则一次循环报错会刷出成百上千条消息，
 *       结果是人把机器人静音 —— 告警等于失效，而且比没有告警更糟（有虚假的安全感）。</li>
 * </ul>
 *
 * <p>发送被刻意放在独立执行器上（不阻塞 ingest 请求线程），因此断言用 Mockito 的
 * {@code timeout()} 模式等它到达，而不是 {@code Thread.sleep} 硬等（那样要么慢、要么偶发失败）。
 * 时间源是注入的，所以窗口与冷却的推进不需要真的等 5 分钟。
 */
class AppLogAlertServiceTest {

    private static final ObservabilityProperties.Alert ALERT_CONFIG =
            new ObservabilityProperties.Alert(true, "generic", "https://hook.example/x", 3, 5, 5);

    private static final long TIMEOUT_MS = 3000L;

    private final AtomicLong clock = new AtomicLong(0);

    private AppLog error(String fingerprint) {
        AppLog log = new AppLog();
        log.setLevel("ERROR");
        log.setFingerprint(fingerprint);
        log.setMessage("boom");
        log.setTraceId("t-1");
        log.setAppType("h5-tenant");
        log.setSource("js");
        return log;
    }

    @Test
    @DisplayName("窗口内未达阈值不告警")
    void belowThresholdDoesNotAlert() {
        WebhookNotifier notifier = Mockito.mock(WebhookNotifier.class);
        AppLogAlertService service = new AppLogAlertService(ALERT_CONFIG, notifier, clock::get);

        service.onRecord(error("fp-1"));
        service.onRecord(error("fp-1"));

        verify(notifier, never()).notify(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("达到阈值即告警，且带出窗口内次数")
    void alertsAtThreshold() {
        WebhookNotifier notifier = Mockito.mock(WebhookNotifier.class);
        AppLogAlertService service = new AppLogAlertService(ALERT_CONFIG, notifier, clock::get);

        for (int i = 0; i < 3; i++) {
            service.onRecord(error("fp-1"));
        }

        ArgumentCaptor<WebhookPayload> payload = ArgumentCaptor.forClass(WebhookPayload.class);
        verify(notifier, timeout(TIMEOUT_MS)).notify(anyString(), anyString(), payload.capture());
        assertThat(payload.getValue().count()).isEqualTo(3);
        assertThat(payload.getValue().fingerprint()).isEqualTo("fp-1");
    }

    @Test
    @DisplayName("冷却期内继续报错不再发（防轰炸）")
    void silencesWithinCooldown() {
        WebhookNotifier notifier = Mockito.mock(WebhookNotifier.class);
        AppLogAlertService service = new AppLogAlertService(ALERT_CONFIG, notifier, clock::get);

        for (int i = 0; i < 3; i++) {
            service.onRecord(error("fp-1"));
        }
        verify(notifier, timeout(TIMEOUT_MS)).notify(anyString(), anyString(), any());

        // 冷却 5 分钟内再来 30 次：一次都不该发。
        // 告警决策（计数/冷却判断）是同步的，只有发送是异步的。
        for (int i = 0; i < 30; i++) {
            clock.addAndGet(1_000);
            service.onRecord(error("fp-1"));
        }
        // after(...) 而不是立即断言：若实现错误地提交了第二次发送，这里给它 200ms 落地时间，
        // 否则会因为「还没执行完」而假通过
        verify(notifier, after(200).times(1)).notify(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("冷却期后再次达阈值会重新告警（问题没解决仍要提醒）")
    void alertsAgainAfterCooldown() {
        WebhookNotifier notifier = Mockito.mock(WebhookNotifier.class);
        AppLogAlertService service = new AppLogAlertService(ALERT_CONFIG, notifier, clock::get);

        for (int i = 0; i < 3; i++) {
            service.onRecord(error("fp-1"));
        }
        verify(notifier, timeout(TIMEOUT_MS).times(1)).notify(anyString(), anyString(), any());

        // 冷却 5 分钟 => 跳过 5 分钟后再来一波
        clock.addAndGet(5 * 60_000L + 1);
        for (int i = 0; i < 3; i++) {
            service.onRecord(error("fp-1"));
        }
        verify(notifier, timeout(TIMEOUT_MS).times(2)).notify(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("不同指纹各自独立计数与冷却（新缺陷不会被旧缺陷的冷却压掉）")
    void fingerprintsAreIndependent() {
        WebhookNotifier notifier = Mockito.mock(WebhookNotifier.class);
        AppLogAlertService service = new AppLogAlertService(ALERT_CONFIG, notifier, clock::get);

        for (int i = 0; i < 3; i++) {
            service.onRecord(error("fp-1"));
        }
        verify(notifier, timeout(TIMEOUT_MS).times(1)).notify(anyString(), anyString(), any());

        // fp-2 是全新缺陷：必须立刻能触发，而不是被 fp-1 的冷却挡住
        for (int i = 0; i < 3; i++) {
            service.onRecord(error("fp-2"));
        }
        verify(notifier, timeout(TIMEOUT_MS).times(2)).notify(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("超出窗口的旧计数被滚出（阈值按窗口算，不是按累计算）")
    void windowSlidesOut() {
        WebhookNotifier notifier = Mockito.mock(WebhookNotifier.class);
        AppLogAlertService service = new AppLogAlertService(ALERT_CONFIG, notifier, clock::get);

        // 间隔 6 分钟（窗口 5 分钟）：每次进入时旧计数都已过期，窗口内始终只有 1 次 ——
        // 若实现用了累计计数，第三次就会凑够阈值并误报
        service.onRecord(error("fp-1"));
        clock.addAndGet(6 * 60_000L);
        service.onRecord(error("fp-1"));
        clock.addAndGet(6 * 60_000L);
        service.onRecord(error("fp-1"));

        verify(notifier, never()).notify(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("非 ERROR 不参与告警（WARN/INFO 留给二期业务埋点）")
    void ignoresNonError() {
        WebhookNotifier notifier = Mockito.mock(WebhookNotifier.class);
        AppLogAlertService service = new AppLogAlertService(ALERT_CONFIG, notifier, clock::get);

        AppLog warn = error("fp-1");
        warn.setLevel("WARN");
        for (int i = 0; i < 10; i++) {
            service.onRecord(warn);
        }

        verify(notifier, never()).notify(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("未配置 webhook 时不发送也不抛异常（告警配置错误不该影响上报）")
    void disabledConfigIsSafe() {
        ObservabilityProperties.Alert disabled =
                new ObservabilityProperties.Alert(false, "generic", null, 1, 5, 5);
        WebhookNotifier notifier = Mockito.mock(WebhookNotifier.class);
        AppLogAlertService service = new AppLogAlertService(disabled, notifier, clock::get);

        service.onRecord(error("fp-1"));

        verify(notifier, never()).notify(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("指纹为空的记录被忽略，不抛异常")
    void ignoresBlankFingerprint() {
        WebhookNotifier notifier = Mockito.mock(WebhookNotifier.class);
        AppLogAlertService service = new AppLogAlertService(ALERT_CONFIG, notifier, clock::get);

        service.onRecord(error(null));
        service.onRecord(null);

        verify(notifier, never()).notify(anyString(), anyString(), any());
    }
}
