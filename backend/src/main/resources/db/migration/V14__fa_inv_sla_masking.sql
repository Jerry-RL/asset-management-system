-- Batch 11: 固资盘点 / 报修 SLA / 脱敏配置

CREATE TABLE IF NOT EXISTS fa_inventory_plan (
    id           BIGSERIAL PRIMARY KEY,
    plan_no      VARCHAR(64) NOT NULL,
    company_id   BIGINT,
    title        VARCHAR(200),
    status       VARCHAR(20) NOT NULL DEFAULT 'draft', -- draft/counting/closed
    planned_date DATE,
    closed_at    TIMESTAMPTZ,
    created_by   BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS fa_inventory_item (
    id            BIGSERIAL PRIMARY KEY,
    plan_id       BIGINT NOT NULL,
    fixed_asset_id BIGINT NOT NULL,
    book_status   VARCHAR(30),
    actual_status VARCHAR(30), -- matched/surplus/deficit/missing
    remark        VARCHAR(500),
    scanned_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_fa_inv_item_plan ON fa_inventory_item (plan_id);

ALTER TABLE repair_order ADD COLUMN IF NOT EXISTS sla_breached BOOLEAN NOT NULL DEFAULT FALSE;

INSERT INTO config_version (config_key, config_value, effective_date, version, operator_id, old_value, new_value)
SELECT 'repair.sla_response_hours', '2', CURRENT_DATE, 1, NULL, NULL, '2'
WHERE NOT EXISTS (SELECT 1 FROM config_version WHERE config_key = 'repair.sla_response_hours');

INSERT INTO config_version (config_key, config_value, effective_date, version, operator_id, old_value, new_value)
SELECT 'repair.sla_complete_hours', '48', CURRENT_DATE, 1, NULL, NULL, '48'
WHERE NOT EXISTS (SELECT 1 FROM config_version WHERE config_key = 'repair.sla_complete_hours');

INSERT INTO config_version (config_key, config_value, effective_date, version, operator_id, old_value, new_value)
SELECT 'masking_rule',
       '{"default":"mask","roles":{"admin":"plain","finance":"mask","viewer":"omit"},"fields":{"phone":"mask","id_no":"mask","bank_card":"mask"}}',
       CURRENT_DATE, 1, NULL, NULL,
       '{"default":"mask"}'
WHERE NOT EXISTS (SELECT 1 FROM config_version WHERE config_key = 'masking_rule');
