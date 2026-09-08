-- 微信身份 / 支付 / 核销策略 / 退租结算增强

ALTER TABLE "user" ADD COLUMN IF NOT EXISTS wechat_openid VARCHAR(64);
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_wechat_openid ON "user" (wechat_openid) WHERE wechat_openid IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_user_tenant ON "user" (tenant_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_wechat_openid ON tenant (wechat_openid) WHERE wechat_openid IS NOT NULL;

ALTER TABLE payment ADD COLUMN IF NOT EXISTS out_trade_no VARCHAR(64);
ALTER TABLE payment ADD COLUMN IF NOT EXISTS third_party_txn_id VARCHAR(64);
ALTER TABLE payment ADD COLUMN IF NOT EXISTS payer_openid VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_out_trade_no ON payment (out_trade_no) WHERE out_trade_no IS NOT NULL;

ALTER TABLE contract ADD COLUMN IF NOT EXISTS allocation_strategy VARCHAR(20) NOT NULL DEFAULT 'fifo';
ALTER TABLE contract ADD COLUMN IF NOT EXISTS late_fee_first BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE vacate_order ADD COLUMN IF NOT EXISTS rent_arrears NUMERIC(18,2) DEFAULT 0;
ALTER TABLE vacate_order ADD COLUMN IF NOT EXISTS utility_arrears NUMERIC(18,2) DEFAULT 0;
ALTER TABLE vacate_order ADD COLUMN IF NOT EXISTS late_fee_arrears NUMERIC(18,2) DEFAULT 0;
ALTER TABLE vacate_order ADD COLUMN IF NOT EXISTS deposit_deducted NUMERIC(18,2) DEFAULT 0;
ALTER TABLE vacate_order ADD COLUMN IF NOT EXISTS prepay_deducted NUMERIC(18,2) DEFAULT 0;
ALTER TABLE vacate_order ADD COLUMN IF NOT EXISTS inspection_file_ids VARCHAR(500);

ALTER TABLE bill ADD COLUMN IF NOT EXISTS remark VARCHAR(500);
