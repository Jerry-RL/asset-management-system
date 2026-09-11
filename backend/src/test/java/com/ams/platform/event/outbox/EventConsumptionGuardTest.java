package com.ams.platform.event.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事件消费幂等守卫测试（ADR-0020 决策 F）。
 *
 * <p>守卫的价值取决于两个约束，本测试各自锁死：
 * <ol>
 *   <li><b>必须在事务内调用</b>——否则幂等台账与业务效果不同事务，
 *       「台账已记、效果回滚」会让重试被误判为重复而永久丢通知；</li>
 *   <li>已消费返回 false，调用方跳过。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class EventConsumptionGuardTest {

    @Mock
    private EventConsumptionMapper mapper;

    @InjectMocks
    private EventConsumptionGuard guard;

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("无事务调用立即抛错：不允许静默退化为非原子")
    void outsideTransactionThrows() {
        assertThatThrownBy(() -> guard.tryConsume("evt-1", "notification"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须在事务内调用")
                .hasMessageContaining("@Transactional");

        verify(mapper, never()).tryInsert(anyString(), anyString());
    }

    @Test
    @DisplayName("事务内首次消费返回 true")
    void firstConsumptionReturnsTrue() {
        inTransaction();
        when(mapper.tryInsert("evt-1", "notification")).thenReturn(1);

        assertThat(guard.tryConsume("evt-1", "notification")).isTrue();
    }

    @Test
    @DisplayName("事务内重复消费返回 false（重放场景）")
    void duplicateConsumptionReturnsFalse() {
        inTransaction();
        when(mapper.tryInsert("evt-1", "notification")).thenReturn(0);

        assertThat(guard.tryConsume("evt-1", "notification")).isFalse();
    }

    @Test
    @DisplayName("不同消费者各自消费同一事件：互不影响")
    void differentConsumersAreIndependent() {
        inTransaction();
        when(mapper.tryInsert("evt-1", "notification")).thenReturn(1);
        when(mapper.tryInsert("evt-1", "task")).thenReturn(1);

        assertThat(guard.tryConsume("evt-1", "notification")).isTrue();
        assertThat(guard.tryConsume("evt-1", "task")).isTrue();
    }

    @Test
    @DisplayName("事件无身份时放行且不写台账：宁可重复通知也不静默丢弃")
    void nullEventIdPassesThrough() {
        inTransaction();

        assertThat(guard.tryConsume(null, "notification")).isTrue();
        verify(mapper, never()).tryInsert(anyString(), anyString());
    }

    private void inTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
}
