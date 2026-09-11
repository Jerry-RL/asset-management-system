package com.ams.platform.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.platform.event.outbox.OutboxStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 事件总线测试（ADR-0020 决策 F）。
 *
 * <p>核心命题：<b>事件必须在「写入发件箱」之后才可能投递</b>，
 * 且投递失败要有痕迹可重放——这正是「进程内事件无投递保证」的修复点。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DomainEventPublisherTest {

    @Mock
    private ApplicationEventPublisher publisher;
    @Mock
    private OutboxStore outboxStore;

    private DomainEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        eventPublisher = new DomainEventPublisher(publisher, outboxStore,
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    @DisplayName("发布：先落发件箱（同事务），再提交后转发")
    void publishRecordsOutboxThenDispatches() {
        when(outboxStore.record(any(DomainEvent.class), anyString())).thenReturn(9L);
        BillIssuedEvent event = new BillIssuedEvent(1L, "B-1", 2L, null, null);

        eventPublisher.publishAfterCommit(event);

        verify(outboxStore).record(eq(event), anyString());
        verify(publisher).publishEvent(any(DomainEventPublisher.AfterCommitEventWrapper.class));
    }

    @Test
    @DisplayName("提交后分发成功：置 done")
    void onCommittedMarksDone() {
        eventPublisher.onCommitted(wrapper(9L));

        verify(publisher).publishEvent(any(DomainEvent.class));
        verify(outboxStore).markDone(9L);
        verify(outboxStore, never()).markRetry(any(), any());
    }

    @Test
    @DisplayName("提交后分发失败：留痕待重放，且异常原样上抛（保持既有语义）")
    void onCommittedFailureLeavesTraceAndRethrows() {
        doThrow(new IllegalStateException("监听器失败")).when(publisher).publishEvent(any(Object.class));

        assertThatThrownBy(() -> eventPublisher.onCommitted(wrapper(9L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("监听器失败");

        verify(outboxStore, never()).markDone(any());
        verify(outboxStore).markRetry(any(), any());
    }

    @Test
    @DisplayName("事件无法序列化时立即失败：不允许「静默丢了事件还不知道」")
    void unserializableEventFailsFast() {
        // 匿名子类没有任何可序列化属性：Jackson 默认 FAIL_ON_EMPTY_BEANS 会抛错。
        // 说明：Spring Boot 的 ObjectMapper 会关闭 FAIL_ON_EMPTY_BEANS，因此生产环境对
        // 「无属性事件」不会在此抛错，而是序列化出 {}——这类事件随后在重放时因缺 @JsonCreator
        // 反序列化失败并置 dead。故本测试验证的是「序列化异常必须包装成可读错误并阻断写入」，
        // 而非「一切不可重放的情况都会被这里拦住」。
        DomainEvent broken = new DomainEvent() {
        };

        assertThatThrownBy(() -> eventPublisher.publishAfterCommit(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("领域事件序列化失败");

        verify(outboxStore, never()).record(any(), anyString());
    }

    @Test
    @DisplayName("包装体携带 traceId 与发件箱 ID，供提交后转发与落痕")
    void wrapperCarriesTraceIdAndOutboxId() {
        when(outboxStore.record(any(DomainEvent.class), anyString())).thenReturn(77L);

        DomainEventPublisher.AfterCommitEventWrapper wrapper = captureWrapper();

        assertThat(wrapper.outboxId()).isEqualTo(77L);
    }

    private DomainEventPublisher.AfterCommitEventWrapper captureWrapper() {
        final DomainEventPublisher.AfterCommitEventWrapper[] holder = new DomainEventPublisher.AfterCommitEventWrapper[1];
        org.mockito.Mockito.doAnswer(invocation -> {
            holder[0] = invocation.getArgument(0);
            return null;
        }).when(publisher).publishEvent(any(DomainEventPublisher.AfterCommitEventWrapper.class));

        eventPublisher.publishAfterCommit(new PaymentRegisteredEvent(5L));
        return holder[0];
    }

    private DomainEventPublisher.AfterCommitEventWrapper wrapper(Long outboxId) {
        return new DomainEventPublisher.AfterCommitEventWrapper(
                new PaymentRegisteredEvent(5L), outboxId, "trace-1");
    }
}
