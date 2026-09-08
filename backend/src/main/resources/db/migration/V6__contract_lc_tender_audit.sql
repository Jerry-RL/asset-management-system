-- 合同特批 / 招租备案 / 经营性盘点

ALTER TABLE contract ADD COLUMN IF NOT EXISTS special_approval_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE contract ADD COLUMN IF NOT EXISTS below_floor_cleared BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE tender_announcement ADD COLUMN IF NOT EXISTS filing_package_json TEXT;
ALTER TABLE tender_application ADD COLUMN IF NOT EXISTS deposit_refunded_at TIMESTAMPTZ;
ALTER TABLE tender_application ADD COLUMN IF NOT EXISTS deposit_refund_remark VARCHAR(500);

INSERT INTO approval_flow_def (biz_type, name, definition, version, enabled)
SELECT 'contract_low_price', '超低价签约特批',
       '{"bizType":"contract_low_price","nodes":[{"id":"dept","type":"serial","role":"approver"},{"id":"leader","type":"serial","role":"admin"}]}',
       1, TRUE
WHERE NOT EXISTS (SELECT 1 FROM approval_flow_def WHERE biz_type = 'contract_low_price');

INSERT INTO approval_flow_def (biz_type, name, definition, version, enabled)
SELECT 'asset_audit_variance', '盘点差异审批',
       '{"bizType":"asset_audit_variance","nodes":[{"id":"asset_mgr","type":"serial","role":"approver"}]}',
       1, TRUE
WHERE NOT EXISTS (SELECT 1 FROM approval_flow_def WHERE biz_type = 'asset_audit_variance');

CREATE TABLE IF NOT EXISTS asset_audit_plan (
    id            BIGSERIAL PRIMARY KEY,
    plan_no       VARCHAR(64) NOT NULL UNIQUE,
    title         VARCHAR(200) NOT NULL,
    company_id    BIGINT,
    project_id    BIGINT,
    scope_type    VARCHAR(20) NOT NULL DEFAULT 'full',
    status        VARCHAR(20) NOT NULL DEFAULT 'draft',
    planned_start DATE,
    planned_end   DATE,
    remark        VARCHAR(500),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    created_by    BIGINT,
    updated_by    BIGINT
);
CREATE INDEX IF NOT EXISTS idx_asset_audit_plan_status ON asset_audit_plan (status);
CREATE INDEX IF NOT EXISTS idx_asset_audit_plan_project ON asset_audit_plan (project_id);

CREATE TABLE IF NOT EXISTS asset_audit_item (
    id              BIGSERIAL PRIMARY KEY,
    plan_id         BIGINT NOT NULL,
    asset_id        BIGINT NOT NULL,
    expected_status VARCHAR(30),
    actual_status   VARCHAR(30),
    variance_type   VARCHAR(30),
    scan_code       VARCHAR(100),
    scanned_at      TIMESTAMPTZ,
    scanned_by      BIGINT,
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
    remark          VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    created_by      BIGINT,
    updated_by      BIGINT
);
CREATE INDEX IF NOT EXISTS idx_asset_audit_item_plan ON asset_audit_item (plan_id);
CREATE INDEX IF NOT EXISTS idx_asset_audit_item_asset ON asset_audit_item (asset_id);
CREATE INDEX IF NOT EXISTS idx_asset_audit_item_status ON asset_audit_item (status);
