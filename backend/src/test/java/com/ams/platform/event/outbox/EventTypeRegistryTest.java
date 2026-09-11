package com.ams.platform.event.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ams.platform.event.AlertTriggeredEvent;
import com.ams.platform.event.ApprovalCompletedEvent;
import com.ams.platform.event.AssetTransferredEvent;
import com.ams.platform.event.BillIssuedEvent;
import com.ams.platform.event.BillOverdueEvent;
import com.ams.platform.event.ContractExpiredEvent;
import com.ams.platform.event.DisposalCompletedEvent;
import com.ams.platform.event.DomainEvent;
import com.ams.platform.event.PaymentRegisteredEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 事件类型登记表与载荷往返测试（ADR-0020 决策 F）。
 *
 * <p>这是 outbox 重放能力的地基：<b>登记名与实际类名错位、或事件类缺 {@code @JsonCreator}，
 * 都会让重放失败并把事件置 dead</b>。本测试对 DSD §4.8 全部 8 类事件逐一验证。
 */
class EventTypeRegistryTest {

    /** 复刻 Spring Boot 的 ObjectMapper 能力（注册 JavaTimeModule 等）。 */
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private List<DomainEvent> allEvents() {
        return List.of(
                new ApprovalCompletedEvent("contract", 1001L, true),
                new PaymentRegisteredEvent(2002L),
                new BillIssuedEvent(3003L, "BILL-20261001", 1001L, new BigDecimal("1200.00"),
                        LocalDate.of(2026, 10, 10)),
                new BillOverdueEvent(3004L, "BILL-20260901", 1001L, new BigDecimal("800.50"), 3, 45L),
                new AlertTriggeredEvent(4004L, "contract_expiry", "renewable", 2,
                        "contract", 1001L, "合同即将到期需续签"),
                new DisposalCompletedEvent(5005L, 6006L, "sale"),
                new ContractExpiredEvent(1002L, "CON-2025-001", LocalDate.of(2026, 9, 1)),
                new AssetTransferredEvent(7007L, 6006L, 11L, 22L));
    }

    @Test
    @DisplayName("DSD §4.8 全部 8 类事件已登记，登记名即事件类简单名")
    void allDsdEventsAreRegistered() {
        assertThat(EventTypeRegistry.registeredTypes())
                .containsExactlyInAnyOrder(
                        "ApprovalCompletedEvent", "PaymentRegisteredEvent", "BillIssuedEvent",
                        "BillOverdueEvent", "AlertTriggeredEvent", "DisposalCompletedEvent",
                        "ContractExpiredEvent", "AssetTransferredEvent");

        for (DomainEvent event : allEvents()) {
            assertThat(EventTypeRegistry.registeredTypes())
                    .as("事件 %s 必须已登记", event.getClass().getSimpleName())
                    .contains(EventTypeRegistry.nameOf(event));
        }
    }

    @Test
    @DisplayName("全部 8 类事件可序列化 → 反序列化往返，且领域字段不丢失")
    void everyEventSurvivesRoundTrip() throws Exception {
        for (DomainEvent event : allEvents()) {
            String payload = objectMapper.writeValueAsString(event);

            OutboxEvent row = new OutboxEvent();
            row.setEventId(event.getEventId());
            row.setEventType(EventTypeRegistry.nameOf(event));
            row.setPayload(payload);
            row.setCreatedAt(event.getOccurredAt());

            DomainEvent restored = EventTypeRegistry.deserialize(row, objectMapper);

            // 同一类型 + 字段相等（用序列化结果比对，避免为每个事件写 equals）
            assertThat(restored.getClass()).isEqualTo(event.getClass());
            assertThat(objectMapper.writeValueAsString(restored))
                    .as("事件 %s 往返后字段应一致", event.getClass().getSimpleName())
                    .isEqualTo(payload);
        }
    }

    @Test
    @DisplayName("重放还原事件身份：eventId / occurredAt 取自 outbox 列，重放不换身份")
    void deserializeRestoresIdentity() {
        DomainEvent event = new BillIssuedEvent(3003L, "BILL-1", 1001L, new BigDecimal("10.00"),
                LocalDate.of(2026, 10, 10));
        String payload = writeQuietly(event);

        OutboxEvent row = new OutboxEvent();
        row.setEventId("fixed-event-id");
        row.setEventType("BillIssuedEvent");
        row.setPayload(payload);
        row.setCreatedAt(java.time.LocalDateTime.of(2026, 9, 11, 10, 0));

        DomainEvent restored = EventTypeRegistry.deserialize(row, objectMapper);

        assertThat(restored.getEventId()).isEqualTo("fixed-event-id");
        assertThat(restored.getOccurredAt()).isEqualTo(java.time.LocalDateTime.of(2026, 9, 11, 10, 0));
    }

    @Test
    @DisplayName("身份不进 payload：避免 eventId 出现「列」与「载荷」两个来源")
    void identityIsExcludedFromPayload() {
        String payload = writeQuietly(new PaymentRegisteredEvent(2002L));

        assertThat(payload).doesNotContain("eventId");
        assertThat(payload).doesNotContain("occurredAt");
        assertThat(payload).contains("paymentId");
    }

    @Test
    @DisplayName("未登记类型重放失败并给出可操作提示（而非静默丢事件）")
    void unknownTypeFailsWithActionableMessage() {
        OutboxEvent row = new OutboxEvent();
        row.setEventId("evt-1");
        row.setEventType("BrandNewEvent");        row.setPayload("{}");

        assertThatThrownBy(() -> EventTypeRegistry.deserialize(row, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未登记的领域事件类型")
                .hasMessageContaining("EventTypeRegistry");
    }

    @Test
    @DisplayName("载荷损坏时抛出带 eventId 的异常，便于定位 dead 事件")
    void brokenPayloadFailsWithContext() {
        OutboxEvent row = new OutboxEvent();
        row.setEventId("evt-broken");
        row.setEventType("BillIssuedEvent");
        row.setPayload("{not-json");

        assertThatThrownBy(() -> EventTypeRegistry.deserialize(row, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evt-broken");
    }

    private String writeQuietly(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
