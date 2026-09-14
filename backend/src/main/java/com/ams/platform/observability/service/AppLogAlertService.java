package com.ams.platform.observability.service;

import com.ams.platform.observability.AppLog;
import com.ams.platform.observability.ObservabilityProperties;
import com.ams.platform.observability.webhook.WebhookMessageFormatter;
import com.ams.platform.observability.webhook.WebhookNotifier;
import com.ams.platform.observability.webhook.WebhookPayload;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * ERROR 告警：滑动窗口计数 + 同指纹冷却（设计 §10.1）。
 *
 * <p>状态全部在内存里，因此<strong>多实例部署时各自计数</strong>（阈值需按实例数折算）。
 * 这是设计 §17 R5 已登记的取舍：为告警引入 Redis 会破坏「零运维」的目标，而低流量下
 * 「偶尔多发一条告警」的代价远低于「引入 Redis 后没配好，告警全哑」。
 *
 * <p><strong>不用 {@code @Async}</strong>：本仓没有任何 {@code @EnableAsync}，标注了也不会异步，
 * 只会让人误以为已经异步。这里用一个显式的有界单线程执行器，只把真正阻塞的 HTTP 发送挪走；
 * 计数本身是内存操作，留在调用线程即可（顺带避免执行器积压影响计数准确性）。
 *
 * <p>必须有内存上限：窗口表与冷却表都限制在 {@link #MAX_TRACKED_FINGERPRINTS} 个指纹以内，
 * 否则「防轰炸」机制自己就成了内存泄漏源。
 */
@Service
public class AppLogAlertService {

    private static final Logger log = LoggerFactory.getLogger(AppLogAlertService.class);

    /** 指纹维度内存上限：超出按「最后一次命中时刻」淘汰最久未出现的。 */
    static final int MAX_TRACKED_FINGERPRINTS = 2000;

    /**
     * 告警发送队列上限。
     *
     * <p>有界队列 + 丢弃策略是刻意的：Webhook 不可达时发送会堆积，无界队列会把内存吃光。
     * 告警是可丢的（日志已经落库，事后能查），内存不是。
     */
    static final int SEND_QUEUE_CAPACITY = 200;

    private final ObservabilityProperties.Alert config;
    private final WebhookNotifier notifier;
    private final LongSupplier nowMillis;

    /** fingerprint → 窗口内的命中时刻（毫秒）。 */
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    /** fingerprint → 最后一次命中时刻（毫秒），仅用于淘汰排序。 */
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    /** fingerprint → 上次告警时刻（毫秒），用于冷却。 */
    private final Map<String, Long> lastAlertAt = new ConcurrentHashMap<>();

    private final ExecutorService sendExecutor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(SEND_QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "app-log-alert");
                // 守护线程：告警不该阻止 JVM 关闭
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    public AppLogAlertService(ObservabilityProperties.Alert config, WebhookNotifier notifier, LongSupplier nowMillis) {
        this.config = config;
        this.notifier = notifier;
        this.nowMillis = nowMillis;
    }

    /**
     * Spring 用的构造器（委托给上面那个，时间源取系统时钟）。
     *
     * <p><strong>为什么必须显式 {@code @Autowired}</strong>：本类同时保留了带时间源的构造器
     * 供测试注入时钟。Java 一旦存在多个构造器，Spring 就不再自动选择「那个唯一的构造器」，
     * 而是退回去找无参构造器，并以
     * {@code Failed to instantiate ...: No default constructor found} 让**整个应用起不来**。
     *
     * <p><strong>为什么注入 {@link ObservabilityProperties} 本体而不是 {@code Alert}</strong>：
     * {@code Alert} 是嵌套 record，而 {@code @ConfigurationPropertiesScan} 只注册顶层类
     * —— 上下文里没有 {@code Alert} 类型的 bean，直接注入它同样会启动失败。
     */
    @Autowired
    public AppLogAlertService(ObservabilityProperties properties, WebhookNotifier notifier) {
        this(properties.alert(), notifier, System::currentTimeMillis);
    }

    /**
     * 记录一条日志并按需告警。
     *
     * <p>只处理 {@code ERROR}：本期端侧也只产生 ERROR（WARN/INFO 留给二期业务埋点）。
     *
     * <p><strong>不抛异常</strong>：调用方是日志写入链路，而日志系统绝不能反过来影响业务。
     *
     * @param saved 已落库的日志（需要它的 fingerprint / traceId / message 等）
     */
    public void onRecord(AppLog saved) {
        if (saved == null || !"ERROR".equalsIgnoreCase(saved.getLevel())) {
            return;
        }
        try {
            evaluate(saved);
        } catch (Exception ex) {
            log.warn("应用日志告警评估失败：{}", ex.getMessage());
        }
    }

    private void evaluate(AppLog saved) {
        String fingerprint = saved.getFingerprint();
        if (fingerprint == null || fingerprint.isBlank()) {
            return;
        }
        long now = nowMillis.getAsLong();

        Deque<Long> window = windows.computeIfAbsent(fingerprint, key -> new ArrayDeque<>());
        int count;
        synchronized (window) {
            long cutoff = now - config.windowMinutes() * 60_000L;
            while (!window.isEmpty() && window.peekFirst() < cutoff) {
                window.pollFirst();
            }
            window.addLast(now);
            count = window.size();
        }
        lastSeen.put(fingerprint, now);
        evictIfNeeded();

        if (count < config.threshold()) {
            return;
        }

        long cooldownMillis = config.cooldownMinutes() * 60_000L;
        Long lastAlert = lastAlertAt.get(fingerprint);
        if (lastAlert != null && now - lastAlert < cooldownMillis) {
            // 冷却期内静默：这是「防轰炸」的核心，不是异常分支
            return;
        }
        lastAlertAt.put(fingerprint, now);
        dispatch(saved, count);
    }

    private void dispatch(AppLog saved, int count) {
        if (!config.sendable()) {
            // 已达阈值但未配置 Webhook：必须留下痕迹。
            // 完全静默会让人以为「没告警 = 没错误」，这是最危险的配置错误。
            log.warn(
                    "应用日志告警已触发但未配置 Webhook（fingerprint={}, count={}）：{}",
                    saved.getFingerprint(),
                    count,
                    saved.getMessage());
            return;
        }
        WebhookPayload payload = new WebhookPayload(
                saved.getLevel(),
                saved.getMessage(),
                saved.getTraceId(),
                saved.getAppType(),
                saved.getSource(),
                saved.getUrl(),
                saved.getFingerprint(),
                count,
                LocalDateTime.now());
        String title = "❌ 应用报错告警（" + count + " 次/" + config.windowMinutes() + " 分钟）";
        String body = WebhookMessageFormatter.renderMarkdown(title, payload);
        try {
            sendExecutor.execute(() -> notifier.notify(title, body, payload));
        } catch (Exception ex) {
            // DiscardPolicy 不抛异常，但显式兜底以免将来换策略时漏掉
            log.warn("应用日志告警派发失败：{}", ex.getMessage());
        }
    }

    /**
     * 超上限时淘汰「最久未出现」的指纹。
     *
     * <p>同时清理三个表，避免只清一个导致另外两个缓慢增长。
     */
    private void evictIfNeeded() {
        if (windows.size() <= MAX_TRACKED_FINGERPRINTS) {
            return;
        }
        List<Map.Entry<String, Long>> byLastSeen = lastSeen.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry::getValue))
                .toList();
        int toRemove = windows.size() - MAX_TRACKED_FINGERPRINTS;
        for (int i = 0; i < toRemove && i < byLastSeen.size(); i++) {
            String key = byLastSeen.get(i).getKey();
            windows.remove(key);
            lastSeen.remove(key);
            lastAlertAt.remove(key);
        }
    }

    @PreDestroy
    void shutdown() {
        sendExecutor.shutdownNow();
    }
}
