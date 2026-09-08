-- Batch 9: 评估联动 / 处置损益 / 提前解约罚则 / 公摊

ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS pnl_amount NUMERIC(18,2);
ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS pnl_type VARCHAR(20);
ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS payment_id BIGINT;
ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS voucher_id BIGINT;

ALTER TABLE vacate_order ADD COLUMN IF NOT EXISTS penalty_amount NUMERIC(18,2) DEFAULT 0;

ALTER TABLE meter ADD COLUMN IF NOT EXISTS shared BOOLEAN NOT NULL DEFAULT FALSE;

INSERT INTO config_version (config_key, config_value, effective_date, version, operator_id, old_value, new_value)
SELECT 'vacate.fund_pool_order',
       'damage,penalty,rent,utility,late_fee,deposit,prepay',
       CURRENT_DATE, 1, NULL, NULL,
       'damage,penalty,rent,utility,late_fee,deposit,prepay'
WHERE NOT EXISTS (SELECT 1 FROM config_version WHERE config_key = 'vacate.fund_pool_order');

INSERT INTO config_version (config_key, config_value, effective_date, version, operator_id, old_value, new_value)
SELECT 'contract.early_terminate_penalty_months', '1', CURRENT_DATE, 1, NULL, NULL, '1'
WHERE NOT EXISTS (SELECT 1 FROM config_version WHERE config_key = 'contract.early_terminate_penalty_months');
