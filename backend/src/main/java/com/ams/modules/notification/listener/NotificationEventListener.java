package com.ams.modules.notification.listener;

import com.ams.modules.notification.service.NotificationService;
import com.ams.platform.event.ApprovalCompletedEvent;
import com.ams.platform.event.PaymentRegisteredEvent;
import com.ams.platform.security.SecurityUtils;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 领域事件 → 站内通知（FR-NOTIF-001）。
 */
@Component
public class NotificationEventListener {

    private final NotificationService notificationService;

    public NotificationEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @EventListener
    public void onApprovalCompleted(ApprovalCompletedEvent event) {
        Long userId = SecurityUtils.currentUserIdOrNull();
        notificationService.sendByTemplate(
                "approval_completed",
                userId,
                Map.of(
                        "bizType", event.getBizType() == null ? "" : event.getBizType(),
                        "bizId", String.valueOf(event.getBizId()),
                        "result", event.isApproved() ? "通过" : "驳回"),
                event.getBizType(),
                event.getBizId());
    }

    @EventListener
    public void onPaymentRegistered(PaymentRegisteredEvent event) {
        Long userId = SecurityUtils.currentUserIdOrNull();
        notificationService.sendByTemplate(
                "payment_registered",
                userId,
                Map.of(
                        "paymentNo", String.valueOf(event.getPaymentId()),
                        "amount", "-"),
                "payment",
                event.getPaymentId());
    }
}
