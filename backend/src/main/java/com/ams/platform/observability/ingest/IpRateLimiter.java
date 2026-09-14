package com.ams.platform.observability.ingest;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 按 key（上报端点用客户端 IP）的进程内令牌桶限流器。
 *
 * <p><strong>为什么不用 Redis</strong>：设计 D3/§3.1 明确本项目是单机 + 关系库的极简架构。
 * 为限流引入 Redis 依赖会把「零运维」的目标抹掉，而单机进程内计数在低流量下完全够用。
 * 代价是多实例部署时每个实例各有一份配额（限流值需按实例数折算）—— 已登记在设计 §17 R5。
 *
 * <p><strong>为什么必须有容量上限</strong>：key 来自客户端可控的 IP。若不做上限，攻击者
 * 用海量伪造源 IP 就能让这张 Map 无限增长直到 OOM —— 那时「限流器」本身成了故障源。
 * 超过上限时按最后访问时间淘汰最旧的条目。
 *
 * <p>时间源可注入，便于测试不依赖 {@code Thread.sleep} 断言窗口行为。
 */
public class IpRateLimiter {

    /** 内存上限：防止伪造源 IP 把限流表撑成 OOM。 */
    static final int MAX_KEYS = 2000;

    /** token 满额的周期：1 分钟（配置项的单位就是「每分钟次数」）。 */
    private static final long REFILL_PERIOD_MILLIS = 60_000L;

    private final int capacity;
    private final LongSupplier nowMillis;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public IpRateLimiter(int capacityPerMinute) {
        this(capacityPerMinute, System::currentTimeMillis);
    }

    public IpRateLimiter(int capacityPerMinute, LongSupplier nowMillis) {
        this.capacity = Math.max(1, capacityPerMinute);
        this.nowMillis = nowMillis;
    }

    /**
     * 尝试消耗一个令牌。
     *
     * @return true 表示放行；false 表示已超限（调用方应返回 429）
     */
    public boolean tryAcquire(String key) {
        if (key == null || key.isBlank()) {
            // 取不到来源时保守放行：认不出是谁就限流，会把所有这类请求一起误伤
            return true;
        }
        long now = nowMillis.getAsLong();
        // 先腾位置再加入，保证 size 永不越界。放在加入之后淘汰的话，
        // 每次都会短暂越界 1 个，长期运行就是「上限 + 1」而非上限本身。
        makeRoom();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        return bucket.tryAcquire(now, capacity);
    }

    /** 当前跟踪的 key 数量（供测试与观测）。 */
    public int trackedKeys() {
        return buckets.size();
    }

    /**
     * 超上限时淘汰「最久未访问」的条目，为即将加入的新 key 腾出一个位置。
     *
     * <p>扫描是 O(n) 的，但只在 size 达到 {@link #MAX_KEYS} 时触发，n 上限 2000，
     * 代价可忽略；换成 LRU 链表反而要引入锁。
     */
    private void makeRoom() {
        if (buckets.size() < MAX_KEYS) {
            return;
        }
        int toRemove = buckets.size() - MAX_KEYS + 1;
        List<Map.Entry<String, Bucket>> entries = buckets.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().lastAccess.get()))
                .limit(toRemove)
                .toList();
        for (Map.Entry<String, Bucket> entry : entries) {
            buckets.remove(entry.getKey(), entry.getValue());
        }
    }

    /** 单个 key 的令牌桶。 */
    private static final class Bucket {

        private double tokens;
        private long lastRefillMillis;
        private final AtomicLong lastAccess;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastRefillMillis = now;
            this.lastAccess = new AtomicLong(now);
        }

        synchronized boolean tryAcquire(long now, int capacity) {
            lastAccess.set(now);
            long elapsed = now - lastRefillMillis;
            if (elapsed > 0) {
                // 按时间线性补充；两次补充之间最多补满一桶
                double refill = (double) capacity * elapsed / REFILL_PERIOD_MILLIS;
                tokens = Math.min(capacity, tokens + refill);
                lastRefillMillis = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
