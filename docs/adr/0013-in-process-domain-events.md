# ADR-0013: 进程内领域事件驱动通知

- **状态**：Accepted
- **日期**：2026-08-26

## 上下文

SRS V2.2 要求消息通知闭环（FR-NOTIF-*）及十三条业务闭环联动。各域在审批通过、账单逾期、预警触发等节点需通知用户并创建待办。若各 Service 直接调用 NotificationService，耦合高、易遗漏。

## 决策

1. 在应用层引入 **DomainEventBus**（基于 `eventemitter2` 或轻量自封装）
2. 领域服务在事务 **提交成功后** 发布事件，如：
   - `ContractApproved`、`BillOverdue`、`AlertTriggered`、`DisposalCompleted`
3. `NotificationHandler`、`TaskHandler` 订阅事件，写 `notification` 表、创建 `task`
4. 一期 **不引入 Kafka/RabbitMQ**；跨进程仅 BullMQ 用于重任务
5. 事件 payload 含 `traceId`、`userId`、`companyId`、`bizType`、`bizId`

## 备选方案

| 方案 | 结论 |
|------|------|
| 直接调用 NotificationService | 耦合、遗漏 → 拒绝 |
| Kafka 事件总线 | 过重 → 暂缓 |
| In-process EventBus | 简单可靠、同事务边界清晰 → **采纳** |

## 后果

- 正面：通知闭环统一入口；易单元测试（断言事件）
- 负面：多 API 实例时事件仅本进程（通知写 DB 后无影响）；拆微服务时需改 MQ

## 关联

- DSD §4.10
- SRS FR-NOTIF-*、FR-ALERT-LC-*
