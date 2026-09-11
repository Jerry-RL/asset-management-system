package com.ams.modules.notification.listener;

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
import com.ams.platform.security.SecurityUtils;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 领域事件 → 站内通知（FR-NOTIF-001、DSD §4.8）。
 *
 * <p><b>覆盖 DSD §4.8 全部 8 类事件</b>（此前只订阅 2 类，通知闭环实际只通 2 条链路，
 * 见评审 P0-8）。
 *
 * <p><b>幂等与事务（两条缺一不可）</b>：
 * <ul>
 *   <li>方法标注 {@code @Transactional} —— outbox 重放会导致重复投递，幂等台账必须与
 *       通知写入<b>同事务</b>提交，否则会出现「台账已记已消费、通知却未写出」的永久丢失；</li>
 *   <li>入口先调 {@link EventConsumptionGuard#tryConsume} —— 已消费则直接返回。</li>
 * </ul>
 * {@code EventConsumptionGuard} 会主动校验「必须在事务内调用」，因此漏标 {@code @Transactional}
 * 会立刻抛错而非静默退化为非原子。
 *
 * <p>注：事件→<b>待办</b>的投影目前仅由 {@code AlertService}（预警）与 {@code DunningService}
 * （催缴）在业务侧创建，其余事件尚未生成待办；本类只负责通知，不越权创建待办。
 */
@Component
public class NotificationEventListener {

    /** 幂等台账的消费者标识。 */
    private static final String CONSUMER = "notification";

    private final NotificationService notificationService;
    private final EventConsumptionGuard consumptionGuard;

    public NotificationEventListener(NotificationService notificationService,
            EventConsumptionGuard consumptionGuard) {
        this.notificationService = notificationService;
        this.consumptionGuard = consumptionGuard;
    }

    @EventListener
    @Transactional
    public void onApprovalCompleted(ApprovalCompletedEvent event) {
        if (consumed(event.getEventId())) {
            return;
        }
        notificationService.sendByTemplate(
                "approval_completed",
                SecurityUtils.currentUserIdOrNull(),
                Map.of(
                        "bizType", nullToEmpty(event.getBizType()),
                        "bizId", String.valueOf(event.getBizId()),
                        "result", event.isApproved() ? "通过" : "驳回"),
                event.getBizType(),
                event.getBizId());
    }

    @EventListener
    @Transactional
    public void onPaymentRegistered(PaymentRegisteredEvent event) {
        if (consumed(event.getEventId())) {
            return;
        }
        notificationService.sendByTemplate(
                "payment_registered",
                SecurityUtils.currentUserIdOrNull(),
                Map.of("paymentNo", String.valueOf(event.getPaymentId()), "amount", "-"),
                "payment",
                event.getPaymentId());
    }

    /** 出账 → 租户缴费提醒（闭环 1 经营）。 */
    @EventListener
    @Transactional
    public void onBillIssued(BillIssuedEvent event) {
        if (consumed(event.getEventId())) {
            return;
        }
        notificationService.sendByTemplate(
                "bill_issued",
                SecurityUtils.currentUserIdOrNull(),
                Map.of(
                        "billNo", nullToEmpty(event.getBillNo()),
                        "amount", String.valueOf(event.getAmount()),
                        "dueDate", String.valueOf(event.getDueDate())),
                "bill",
                event.getBillId());
    }

    /** 账单逾期 → 催缴通知（闭环 4 风险 / 催缴）。 */
    @EventListener
    @Transactional
    public void onBillOverdue(BillOverdueEvent event) {
        if (consumed(event.getEventId())) {
            return;
        }
        notificationService.sendByTemplate(
                "bill_overdue",
                SecurityUtils.currentUserIdOrNull(),
                Map.of(
                        "billNo", nullToEmpty(event.getBillNo()),
                        "amount", String.valueOf(event.getAmount()),
                        "overdueDays", String.valueOf(event.getOverdueDays()),
                        "dunningLevel", String.valueOf(event.getDunningLevel())),
                "bill",
                event.getBillId());
    }

    /** 预警触发 → 处置提醒（闭环 4 风险）。 */
    @EventListener
    @Transactional
    public void onAlertTriggered(AlertTriggeredEvent event) {
        if (consumed(event.getEventId())) {
            return;
        }
        notificationService.sendByTemplate(
                "alert_triggered",
                SecurityUtils.currentUserIdOrNull(),
                Map.of(
                        "title", nullToEmpty(event.getTitle()),
                        "alertType", nullToEmpty(event.getAlertType()),
                        "level", String.valueOf(event.getLevel())),
                "alert",
                event.getAlertId());
    }

    /** 处置完成 → 备案提醒（闭环 6 处置）。 */
    @EventListener
    @Transactional
    public void onDisposalCompleted(DisposalCompletedEvent event) {
        if (consumed(event.getEventId())) {
            return;
        }
        notificationService.sendByTemplate(
                "disposal_completed",
                SecurityUtils.currentUserIdOrNull(),
                Map.of(
                        "disposalId", String.valueOf(event.getDisposalId()),
                        "assetId", String.valueOf(event.getAssetId())),
                "disposal",
                event.getDisposalId());
    }

    /** 合同到期 → 续签/挂账提醒（闭环 1 经营）。 */
    @EventListener
    @Transactional
    public void onContractExpired(ContractExpiredEvent event) {
        if (consumed(event.getEventId())) {
            return;
        }
        notificationService.sendByTemplate(
                "contract_expired",
                SecurityUtils.currentUserIdOrNull(),
                Map.of(
                        "contractNo", nullToEmpty(event.getContractNo()),
                        "endDate", String.valueOf(event.getEndDate())),
                "contract",
                event.getContractId());
    }

    /** 调拨完成 → 双方对账提醒（FR-CERT-002）。 */
    @EventListener
    @Transactional
    public void onAssetTransferred(AssetTransferredEvent event) {
        if (consumed(event.getEventId())) {
            return;
        }
        notificationService.sendByTemplate(
                "asset_transferred",
                SecurityUtils.currentUserIdOrNull(),
                Map.of(
                        "assetId", String.valueOf(event.getAssetId()),
                        "toCompanyId", String.valueOf(event.getToCompanyId())),
                "asset_transfer",
                event.getTransferId());
    }

    /**
     * 幂等判定：已消费返回 {@code true}（调用方应跳过）。
     *
     * <p>登记与后续通知写入同事务；若通知写入失败，事务回滚会一并撤销登记，重放可再执行。
     */
    private boolean consumed(String eventId) {
        return !consumptionGuard.tryConsume(eventId, CONSUMER);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
