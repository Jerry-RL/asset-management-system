package com.ams.platform.event.outbox;

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
import java.util.Map;

/**
 * 事件类型 ↔ 具体类的显式登记表（outbox 重放用）。
 *
 * <p>刻意<b>不使用 {@code Class.forName}</b>：按字符串反射加载类会让「新增事件忘记登记」
 * 在运行期才暴露，且无法被编译期检查。此处用显式映射，未知类型直接抛错。
 *
 * <p><b>键即 {@code getClass().getSimpleName()} 的字面值（含 {@code Event} 后缀）</b>，
 * 不做任何名称变换——曾因「登记键去掉后缀、而 nameOf 返回带后缀的类名」导致全部类型对不上、
 * 重放全部置 dead。{@code EventTypeRegistryTest} 会逐一断言 8 类事件可往返。
 *
 * <p><b>新增领域事件时必须在此登记</b>，否则 outbox 重放会因无法反序列化而置 {@code dead}。
 */
public final class EventTypeRegistry {

    private static final Map<String, Class<? extends DomainEvent>> TYPES = Map.of(
            ApprovalCompletedEvent.class.getSimpleName(), ApprovalCompletedEvent.class,
            PaymentRegisteredEvent.class.getSimpleName(), PaymentRegisteredEvent.class,
            BillIssuedEvent.class.getSimpleName(), BillIssuedEvent.class,
            BillOverdueEvent.class.getSimpleName(), BillOverdueEvent.class,
            AlertTriggeredEvent.class.getSimpleName(), AlertTriggeredEvent.class,
            DisposalCompletedEvent.class.getSimpleName(), DisposalCompletedEvent.class,
            ContractExpiredEvent.class.getSimpleName(), ContractExpiredEvent.class,
            AssetTransferredEvent.class.getSimpleName(), AssetTransferredEvent.class);

    private EventTypeRegistry() {
    }

    /** 类型的登记名（与 {@link #typeOf} 取值一致）。 */
    public static String nameOf(DomainEvent event) {
        return event.getClass().getSimpleName();
    }

    /** 全部已登记的类型名（健康检查与测试用）。 */
    public static java.util.Set<String> registeredTypes() {
        return TYPES.keySet();
    }

    /**
     * 反序列化事件载荷，并用 outbox 行的身份信息还原 eventId / occurredAt。
     *
     * <p>还原身份是必须的：重放若生成新 eventId，下游幂等台账会把重放当成新事件。
     */
    public static DomainEvent deserialize(OutboxEvent row, ObjectMapper objectMapper) {
        Class<? extends DomainEvent> type = TYPES.get(row.getEventType());
        if (type == null) {
            throw new IllegalStateException(
                    "未登记的领域事件类型: " + row.getEventType() + "（新增事件须在 EventTypeRegistry 登记）");
        }
        DomainEvent event;
        try {
            event = objectMapper.readValue(row.getPayload(), type);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "领域事件载荷反序列化失败: type=" + row.getEventType() + ", eventId=" + row.getEventId(), ex);
        }
        event.restoreIdentity(row.getEventId(), row.getCreatedAt());
        return event;
    }
}
