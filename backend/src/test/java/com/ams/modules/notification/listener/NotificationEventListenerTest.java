package com.ams.modules.notification.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.modules.notification.service.NotificationService;
import com.ams.platform.event.AlertTriggeredEvent;
import com.ams.platform.event.ApprovalCompletedEvent;
import com.ams.platform.event.AssetTransferredEvent;
import com.ams.platform.event.BillIssuedEvent;
import com.ams.platform.event.BillOverdueEvent;
import com.ams.platform.event.ContractExpiredEvent;
import com.ams.platform.event.DisposalCompletedEvent;
import com.ams.platform.event.PaymentRegisteredEvent;
import com.ams.platform.event.outbox.EventConsumptionGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 通知闭环测试（FR-NOTIF-001、评审 P0-8）。
 *
 * <p>两件事必须成立，否则通知闭环名存实亡：
 * <ol>
 *   <li><b>DSD §4.8 的 8 类事件都产出通知</b>——此前只订阅 2 类，实际只通 2 条链路；</li>
 *   <li><b>重放不产生重复通知</b>——outbox 重放是常态，靠 {@link EventConsumptionGuard} 去重。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationEventListenerTest {

    @Mock
    private NotificationService notificationService;
    @Mock
    private EventConsumptionGuard consumptionGuard;

    private NotificationEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new NotificationEventListener(notificationService, consumptionGuard);
        when(consumptionGuard.tryConsume(any(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("8 类事件各自产出通知，模板码与事件类型一一对应")
    void allEightEventsProduceNotifications() {
        listener.onApprovalCompleted(new ApprovalCompletedEvent("contract", 1L, true));
        listener.onPaymentRegistered(new PaymentRegisteredEvent(2L));
        listener.onBillIssued(new BillIssuedEvent(3L, "B-3", 1L, new BigDecimal("100.00"),
                LocalDate.of(2026, 10, 1)));
        listener.onBillOverdue(new BillOverdueEvent(4L, "B-4", 1L, new BigDecimal("50.00"), 2, 30L));
        listener.onAlertTriggered(new AlertTriggeredEvent(5L, "contract_expiry", "renewable", 2,
                "contract", 1L, "标题"));
        listener.onDisposalCompleted(new DisposalCompletedEvent(6L, 7L, "sale"));
        listener.onContractExpired(new ContractExpiredEvent(1L, "C-1", LocalDate.of(2026, 9, 1)));
        listener.onAssetTransferred(new AssetTransferredEvent(8L, 7L, 11L, 22L));

        verify(notificationService).sendByTemplate(eq("approval_completed"), any(), anyMap(), any(), any());
        verify(notificationService).sendByTemplate(eq("payment_registered"), any(), anyMap(), any(), any());
        verify(notificationService).sendByTemplate(eq("bill_issued"), any(), anyMap(), eq("bill"), eq(3L));
        verify(notificationService).sendByTemplate(eq("bill_overdue"), any(), anyMap(), eq("bill"), eq(4L));
        verify(notificationService).sendByTemplate(eq("alert_triggered"), any(), anyMap(), eq("alert"), eq(5L));
        verify(notificationService).sendByTemplate(eq("disposal_completed"), any(), anyMap(),
                eq("disposal"), eq(6L));
        verify(notificationService).sendByTemplate(eq("contract_expired"), any(), anyMap(),
                eq("contract"), eq(1L));
        verify(notificationService).sendByTemplate(eq("asset_transferred"), any(), anyMap(),
                eq("asset_transfer"), eq(8L));
        verify(notificationService, times(8)).sendByTemplate(any(), any(), anyMap(), any(), any());
    }

    @Test
    @DisplayName("已消费的事件跳过：重放不产生重复通知")
    void alreadyConsumedEventIsSkipped() {
        when(consumptionGuard.tryConsume(any(), any())).thenReturn(false);

        listener.onBillIssued(new BillIssuedEvent(3L, "B-3", 1L, null, null));

        verify(notificationService, never()).sendByTemplate(any(), any(), anyMap(), any(), any());
    }

    @Test
    @DisplayName("一次事件只登记一次消费（幂等登记与通知同事务）")
    void consumptionRegisteredExactlyOncePerEvent() {
        BillIssuedEvent event = new BillIssuedEvent(3L, "B-3", 1L, null, null);

        listener.onBillIssued(event);

        verify(consumptionGuard, times(1)).tryConsume(eq(event.getEventId()), eq("notification"));
    }
}
