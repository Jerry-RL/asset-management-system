package com.ams.platform.observability.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 令牌桶限流测试（设计 §7.2）。
 *
 * <p>时间源是注入的，因此这里断言的是<strong>真实的时间窗口行为</strong>
 * （初始满额、耗尽拒绝、按时间补充、跨窗口恢复），而不是「睡眠 1 秒再试」那种既慢又易抖的写法。
 */
class IpRateLimiterTest {

    @Test
    @DisplayName("初始额度用尽后拒绝，同一窗口内不恢复")
    void rejectsAfterCapacityExhausted() {
        AtomicLong clock = new AtomicLong(0);
        IpRateLimiter limiter = new IpRateLimiter(3, clock::get);

        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();
    }

    @Test
    @DisplayName("按时间线性补充：过 1/3 分钟补回 1 个令牌")
    void refillsOverTime() {
        AtomicLong clock = new AtomicLong(0);
        IpRateLimiter limiter = new IpRateLimiter(3, clock::get);

        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("1.1.1.1");
        }
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();

        // 3 个/分钟 => 每 20 秒补 1 个
        clock.addAndGet(20_000);
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();
    }

    @Test
    @DisplayName("补充不超过桶容量（长时间空闲不会攒出额外额度）")
    void refillIsCappedAtCapacity() {
        AtomicLong clock = new AtomicLong(0);
        IpRateLimiter limiter = new IpRateLimiter(2, clock::get);

        clock.addAndGet(10 * 60_000L);
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();
    }

    @Test
    @DisplayName("不同 key 各自独立计数")
    void keysAreIndependent() {
        AtomicLong clock = new AtomicLong(0);
        IpRateLimiter limiter = new IpRateLimiter(1, clock::get);

        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();
        assertThat(limiter.tryAcquire("2.2.2.2")).isTrue();
    }

    @Test
    @DisplayName("取不到来源 IP 时放行：认不出是谁就限流会把所有这类请求一起误伤")
    void allowsUnknownKey() {
        IpRateLimiter limiter = new IpRateLimiter(1);
        assertThat(limiter.tryAcquire(null)).isTrue();
        assertThat(limiter.tryAcquire("")).isTrue();
        assertThat(limiter.tryAcquire("  ")).isTrue();
    }

    @Test
    @DisplayName("key 数量超上限时淘汰，内存不会无限增长（伪造源 IP 的防护）")
    void evictsBeyondMaxKeys() {
        AtomicLong clock = new AtomicLong(0);
        IpRateLimiter limiter = new IpRateLimiter(60, clock::get);

        // 上限 2000：灌 2500 个不同的「源 IP」
        for (int i = 0; i < 2500; i++) {
            clock.addAndGet(1);
            limiter.tryAcquire("10.0." + (i / 256) + "." + (i % 256));
        }

        assertThat(limiter.trackedKeys())
                .as("超过 MAX_KEYS 必须淘汰，否则限流器自己会成为 OOM 的来源")
                .isLessThanOrEqualTo(IpRateLimiter.MAX_KEYS);
    }
}
