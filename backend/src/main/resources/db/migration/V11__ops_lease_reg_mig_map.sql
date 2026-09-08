-- 组合/拆分租赁、监管报送溯源、迁移明细、地图配置

CREATE TABLE IF NOT EXISTS lease_bundle (
    id              BIGSERIAL PRIMARY KEY,
    bundle_no       VARCHAR(64) NOT NULL,
    bundle_type     VARCHAR(20) NOT NULL, -- combo / split
    rent_mode       VARCHAR(30) NOT NULL, -- package / apportion / area_ratio / independent
    total_rent      NUMERIC(18,2),
    master_contract_id BIGINT,
    status          VARCHAR(20) NOT NULL DEFAULT 'draft', -- draft/active/terminated
    remark          VARCHAR(500),
    created_by      BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS lease_bundle_item (
    id              BIGSERIAL PRIMARY KEY,
    bundle_id       BIGINT NOT NULL,
    asset_id        BIGINT NOT NULL,
    tenant_id       BIGINT,
    contract_id     BIGINT,
    lease_area      NUMERIC(18,4),
    rent_amount     NUMERIC(18,2),
    area_ratio      NUMERIC(10,6),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_lease_bundle_item_bundle ON lease_bundle_item (bundle_id);
CREATE INDEX IF NOT EXISTS idx_lease_bundle_item_asset ON lease_bundle_item (asset_id);

ALTER TABLE contract ADD COLUMN IF NOT EXISTS bundle_id BIGINT;
ALTER TABLE contract ADD COLUMN IF NOT EXISTS lease_mode VARCHAR(30); -- normal/combo/split

ALTER TABLE migration_batch ADD COLUMN IF NOT EXISTS report_json TEXT;
ALTER TABLE migration_batch ADD COLUMN IF NOT EXISTS receivable_amount NUMERIC(18,2);
ALTER TABLE migration_batch ADD COLUMN IF NOT EXISTS arrears_amount NUMERIC(18,2);
ALTER TABLE migration_batch ADD COLUMN IF NOT EXISTS paid_amount NUMERIC(18,2);

ALTER TABLE regulation_report ADD COLUMN IF NOT EXISTS source_json TEXT;
