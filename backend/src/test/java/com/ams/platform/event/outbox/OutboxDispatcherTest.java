package com.ams.platform.event.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.platform.event.BillIssuedEvent;
import com.ams.platform.event.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
 * 发件箱兜底投递器测试（ADR-0020 决策 F）。
 *
 * <p>验证「重放是常态」这一前提下的行为：成功置 done、失败累加重试、超限置 dead，
 * 且<b>单条坏事件不得拖垮整批</b>。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxDispatcherTest {

    @Mock
    private OutboxEventMapper mapper;
    @Mock
    private OutboxStore store;
    @Mock
    private ApplicationEventPublisher publisher;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private OutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new OutboxDispatcher(mapper, store, publisher, objectMapper, 100, 120);
    }

    @Test
    @DisplayName("投递成功：事件被重新发布并置 done")
    void deliverSuccessMarksDone() {
        when(mapper.claimPending(100, 120)).thenReturn(List.of(row(1L, "evt-1", "BillIssuedEvent")));

        int delivered = dispatcher.dispatchOnce();

        assertThat(delivered).isEqualTo(1);
        verify(publisher).publishEvent(any(DomainEvent.class));
        verify(store).markDone(1L);
        verify(store, never()).markRetry(any(), any());
    }

    @Test
    @DisplayName("载荷损坏：不抛向外层，置重试并继续处理其余事件")
    void brokenPayloadDoesNotAbortBatch() {
        OutboxEvent broken = row(1L, "evt-broken", "BillIssuedEvent");
        broken.setPayload("{not-json");
        OutboxEvent good = row(2L, "evt-2", "BillIssuedEvent");
        when(mapper.claimPending(100, 120)).thenReturn(List.of(broken, good));
        when(store.markRetry(any(OutboxEvent.class), any())).thenReturn(false);

        int delivered = dispatcher.dispatchOnce();

        assertThat(delivered).isEqualTo(1);
        verify(store).markRetry(eq(broken), any());
        verify(store).markDone(2L);
    }

    @Test
    @DisplayName("未登记类型：同样置重试而非抛错")
    void unregisteredTypeIsRetriedNotThrown() {
        when(mapper.claimPending(100, 120))
                .thenReturn(List.of(row(1L, "evt-x", "NotRegisteredEvent")));
        when(store.markRetry(any(OutboxEvent.class), any())).thenReturn(false);

        assertThat(dispatcher.dispatchOnce()).isZero();

        verify(store).markRetry(any(OutboxEvent.class), any());
    }

    @Test
    @DisplayName("监听器抛异常：置重试，不向调度循环外抛")
    void listenerFailureIsRetried() {
        when(mapper.claimPending(100, 120)).thenReturn(List.of(row(1L, "evt-1", "BillIssuedEvent")));
        when(store.markRetry(any(OutboxEvent.class), any())).thenReturn(false);
        org.mockito.Mockito.doThrow(new IllegalStateException("下游不可用"))
                .when(publisher).publishEvent(any(Object.class));

        assertThat(dispatcher.dispatchOnce()).isZero();

        verify(store).markRetry(any(OutboxEvent.class), any());
        verify(store, never()).markDone(any());
    }

    @Test
    @DisplayName("重试超限：返回 dead 标记（供调用方告警）")
    void exceedingMaxRetryYieldsDead() {
        when(mapper.claimPending(100, 120)).thenReturn(List.of(row(1L, "evt-1", "BillIssuedEvent")));
        when(store.markRetry(any(OutboxEvent.class), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("持续失败"))
                .when(publisher).publishEvent(any(Object.class));

        assertThat(dispatcher.dispatchOnce()).isZero();

        verify(store).markRetry(any(OutboxEvent.class), any());
    }

    @Test
    @DisplayName("无待投递事件时不触碰发布器")
    void noPendingRowsIsNoop() {
        when(mapper.claimPending(anyInt(), anyInt())).thenReturn(List.of());

        assertThat(dispatcher.dispatchOnce()).isZero();

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    private OutboxEvent row(Long id, String eventId, String eventType) {
        OutboxEvent row = new OutboxEvent();
        row.setId(id);
        row.setEventId(eventId);
        row.setEventType(eventType);
        row.setRetryCount(0);
        if ("BillIssuedEvent".equals(eventType)) {
            BillIssuedEvent event = new BillIssuedEvent(3003L, "BILL-1", 1001L,
                    new BigDecimal("1200.00"), LocalDate.of(2026, 10, 10));
            try {
                row.setPayload(objectMapper.writeValueAsString(event));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
        return row;
    }
}
