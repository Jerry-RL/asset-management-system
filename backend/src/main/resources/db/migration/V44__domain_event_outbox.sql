-- ============================================================================
-- V44 领域事件 outbox 与消费幂等台账
--   决策依据：docs/adr/0020-bounded-contexts-and-aggregate-boundaries.md 决策 F
--   设计依据：docs/design/领域划分与限界上下文设计.md §12.4
--   评审依据：docs/design/领域划分与限界上下文评审报告.md P0-8、docs/design/业务闭环编排设计.md §3.3
--
--   背景：DomainEventPublisher 走 @TransactionalEventListener(AFTER_COMMIT)。若提交后
--        进程崩溃、重启或监听器抛异常，事件<永远不投递>，而下游只读投影会永久陈旧且无人知晓。
--        ADR-0013 明确不引入 MQ，因此用 outbox 表在应用内提供投递保证。
--
--   语义：
--     1) 业务写与 outbox 行<b>同事务</b>插入 —— 业务回滚则事件也不该发（原子）；
--     2) AFTER_COMMIT 同步分发（低延迟）；失败则留 pending 由兜底调度重放；
--     3) 兜底调度用 SKIP LOCKED + 租约（next_retry_at 前推）领取，避免多实例重复领取；
--     4) 重放会再次投递，因此下游<b>必须</b>按 (event_id, consumer) 幂等 —— 见 event_consumption。
--
--   幂等：CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS / DDL 与种子均带守卫。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 事件发件箱
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS domain_event_outbox (
    id              BIGSERIAL PRIMARY KEY,
    -- 事件唯一标识；重放时保持稳定，是消费幂等的依据
    event_id        VARCHAR(64)  NOT NULL,
    -- 事件类型（类名），调度重放时据此反序列化
    event_type      VARCHAR(80)  NOT NULL,
    -- 聚合标识（便于排障与按业务反查）
    aggregate_type  VARCHAR(40),
    aggregate_id    BIGINT,
    -- 事件载荷（仅领域字段；event_id/occurred_at 由本表列承载，不重复存）
    payload         TEXT         NOT NULL,
    -- pending 待投递 / done 已投递 / dead 重试超限需人工
    status          VARCHAR(20)  NOT NULL DEFAULT 'pending',
    retry_count     INTEGER      NOT NULL DEFAULT 0,
    -- 同时充当「重试时刻」与「领取租约」：领取时前推，避免多实例重复领取
    next_retry_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    error           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    dispatched_at   TIMESTAMPTZ,
    CONSTRAINT ck_outbox_status CHECK (status IN ('pending', 'done', 'dead'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_outbox_event
    ON domain_event_outbox (event_id);

-- 投递扫描索引：只索引 pending，done 行不占用
CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON domain_event_outbox (next_retry_at) WHERE status = 'pending';

-- 运维清单：dead 必须为 0，非 0 即告警
CREATE INDEX IF NOT EXISTS idx_outbox_dead
    ON domain_event_outbox (created_at) WHERE status = 'dead';

COMMENT ON TABLE domain_event_outbox IS
    '领域事件发件箱（ADR-0020 决策 F）：业务写同事务插入，投递失败由调度重放；dead 非 0 需告警';
COMMENT ON COLUMN domain_event_outbox.next_retry_at IS
    '下一次可投递时刻；领取时前推作为租约，实例崩溃后租约到期由他实例接手';
COMMENT ON COLUMN domain_event_outbox.payload IS
    '事件载荷（仅领域字段）；event_id/occurred_at 由本表列承载';

-- ---------------------------------------------------------------------------
-- 2) 消费幂等台账
--    重放会重复投递，下游必须按 (event_id, consumer) 去重。
--    仅记录「已成功消费」——插入与业务效果在同一事务，失败一并回滚，保证重试可重入。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS event_consumption (
    id          BIGSERIAL PRIMARY KEY,
    event_id    VARCHAR(64) NOT NULL,
    -- 消费者标识（如 notification / task / projection），同一事件可有多个消费者
    consumer    VARCHAR(80) NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_event_consumption UNIQUE (event_id, consumer)
);

COMMENT ON TABLE event_consumption IS
    '事件消费幂等台账：唯一键 (event_id, consumer)；与消费效果同事务，失败可安全重试';

-- ---------------------------------------------------------------------------
-- 3) 新增 6 类事件的站内通知模板（补齐 DSD §4.8 的事件目录 2/8 → 8/8）
--    NotificationService.sendByTemplate 在模板缺失时会降级为「模板码 + 变量串」，
--    因此模板是体验问题而非正确性问题；此处补齐使文案可配置。
-- ---------------------------------------------------------------------------
INSERT INTO notification_template (code, channel, title_tpl, body_tpl)
SELECT 'bill_issued', 'in_app', '账单已生成',
       '账单 {{billNo}} 已出账，应收 {{amount}}，缴费截止日 {{dueDate}}'
WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE code = 'bill_issued');

INSERT INTO notification_template (code, channel, title_tpl, body_tpl)
SELECT 'bill_overdue', 'in_app', '账单已逾期',
       '账单 {{billNo}} 已逾期 {{overdueDays}} 天，金额 {{amount}}，催缴等级 L{{dunningLevel}}'
WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE code = 'bill_overdue');

INSERT INTO notification_template (code, channel, title_tpl, body_tpl)
SELECT 'alert_triggered', 'in_app', '预警待处理',
       '{{title}}（类型 {{alertType}}，等级 L{{level}}）'
WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE code = 'alert_triggered');

INSERT INTO notification_template (code, channel, title_tpl, body_tpl)
SELECT 'disposal_completed', 'in_app', '资产处置已完成',
       '处置单 #{{disposalId}} 已完成，资产 #{{assetId}} 状态更新为已退出，请及时备案'
WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE code = 'disposal_completed');

INSERT INTO notification_template (code, channel, title_tpl, body_tpl)
SELECT 'contract_expired', 'in_app', '合同已到期',
       '合同 {{contractNo}} 已于 {{endDate}} 到期且未续签，请确认续签或终止'
WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE code = 'contract_expired');

INSERT INTO notification_template (code, channel, title_tpl, body_tpl)
SELECT 'asset_transferred', 'in_app', '资产调拨已完成',
       '资产 #{{assetId}} 已调拨至公司 #{{toCompanyId}}，欠费/保证金/预收余额随合同迁移，请双方对账'
WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE code = 'asset_transferred');
