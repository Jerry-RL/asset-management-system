-- Batch 10: 任务超时升级 / 通知模板 / 调拨交接 / 滞纳金配置

ALTER TABLE task ADD COLUMN IF NOT EXISTS reminded_at TIMESTAMPTZ;
ALTER TABLE task ADD COLUMN IF NOT EXISTS escalated_at TIMESTAMPTZ;

ALTER TABLE asset_transfer ADD COLUMN IF NOT EXISTS handover_json TEXT;

CREATE TABLE IF NOT EXISTS notification_template (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64) NOT NULL UNIQUE,
    channel     VARCHAR(20) NOT NULL DEFAULT 'in_app',
    title_tpl   VARCHAR(200) NOT NULL,
    body_tpl    VARCHAR(1000) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO notification_template (code, channel, title_tpl, body_tpl)
SELECT 'approval_completed', 'in_app', '审批结果通知',
       '业务 {{bizType}}#{{bizId}} 审批{{result}}'
WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE code = 'approval_completed');

INSERT INTO notification_template (code, channel, title_tpl, body_tpl)
SELECT 'task_remind', 'in_app', '待办即将到期',
       '任务 #{{taskId}}（{{taskType}}）将于 {{deadline}} 到期，请尽快处理'
WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE code = 'task_remind');

INSERT INTO notification_template (code, channel, title_tpl, body_tpl)
SELECT 'task_overdue', 'in_app', '待办已超时',
       '任务 #{{taskId}}（{{taskType}}）已超时 {{overdueMinutes}} 分钟，已升级督办'
WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE code = 'task_overdue');

INSERT INTO notification_template (code, channel, title_tpl, body_tpl)
SELECT 'payment_registered', 'in_app', '收款到账通知',
       '收款单 {{paymentNo}} 金额 {{amount}} 已确认入账'
WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE code = 'payment_registered');

INSERT INTO notification_template (code, channel, title_tpl, body_tpl)
SELECT 'occupation_expiry', 'in_app', '占用到期提醒',
       '占用单 #{{bizId}} 资产 {{assetId}} 将于/已于 {{endDate}} 到期'
WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE code = 'occupation_expiry');

INSERT INTO config_version (config_key, config_value, effective_date, version, operator_id, old_value, new_value)
SELECT 'late_fee.daily_rate', '0.0005', CURRENT_DATE, 1, NULL, NULL, '0.0005'
WHERE NOT EXISTS (SELECT 1 FROM config_version WHERE config_key = 'late_fee.daily_rate');

INSERT INTO config_version (config_key, config_value, effective_date, version, operator_id, old_value, new_value)
SELECT 'late_fee.grace_days', '0', CURRENT_DATE, 1, NULL, NULL, '0'
WHERE NOT EXISTS (SELECT 1 FROM config_version WHERE config_key = 'late_fee.grace_days');

INSERT INTO config_version (config_key, config_value, effective_date, version, operator_id, old_value, new_value)
SELECT 'late_fee.cap_ratio', '0.5', CURRENT_DATE, 1, NULL, NULL, '0.5'
WHERE NOT EXISTS (SELECT 1 FROM config_version WHERE config_key = 'late_fee.cap_ratio');

INSERT INTO config_version (config_key, config_value, effective_date, version, operator_id, old_value, new_value)
SELECT 'task.remind_hours', '24', CURRENT_DATE, 1, NULL, NULL, '24'
WHERE NOT EXISTS (SELECT 1 FROM config_version WHERE config_key = 'task.remind_hours');
