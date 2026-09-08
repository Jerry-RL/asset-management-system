-- 费用减免 / 租金调价 / 数电发票增强

CREATE TABLE IF NOT EXISTS fee_relief_request (
    id              BIGSERIAL PRIMARY KEY,
    bill_id         BIGINT NOT NULL,
    contract_id     BIGINT,
    tenant_id       BIGINT,
    relief_amount   NUMERIC(18,2) NOT NULL,
    reason          VARCHAR(500),
    file_ids        VARCHAR(500),
    major_flag      BOOLEAN NOT NULL DEFAULT FALSE,
    status          VARCHAR(20) NOT NULL DEFAULT 'draft', -- draft/approving/approved/rejected/applied
    approval_biz_type VARCHAR(50),
    applied_at      TIMESTAMPTZ,
    remark          VARCHAR(500),
    created_by      BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    updated_by      BIGINT
);
CREATE INDEX IF NOT EXISTS idx_fee_relief_bill ON fee_relief_request (bill_id);
CREATE INDEX IF NOT EXISTS idx_fee_relief_status ON fee_relief_request (status);

CREATE TABLE IF NOT EXISTS rent_adjust_request (
    id              BIGSERIAL PRIMARY KEY,
    contract_id     BIGINT NOT NULL,
    old_rent_amount NUMERIC(18,2),
    new_rent_amount NUMERIC(18,2) NOT NULL,
    effective_date  DATE,
    issued_strategy VARCHAR(20) NOT NULL DEFAULT 'keep', -- keep / diff_bill
    reason          VARCHAR(500),
    file_ids        VARCHAR(500),
    status          VARCHAR(20) NOT NULL DEFAULT 'draft',
    applied_at      TIMESTAMPTZ,
    remark          VARCHAR(500),
    created_by      BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    updated_by      BIGINT
);
CREATE INDEX IF NOT EXISTS idx_rent_adjust_contract ON rent_adjust_request (contract_id);

ALTER TABLE bill ADD COLUMN IF NOT EXISTS reduced_amount NUMERIC(18,2) DEFAULT 0;

ALTER TABLE invoice ADD COLUMN IF NOT EXISTS platform_code VARCHAR(50);
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS pdf_url VARCHAR(500);
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS fail_reason VARCHAR(500);

INSERT INTO approval_flow_def (biz_type, name, definition, version, enabled)
SELECT 'fee_relief', '费用减免审批',
       '{"bizType":"fee_relief","nodes":[{"id":"finance","type":"serial","role":"finance"}]}',
       1, TRUE
WHERE NOT EXISTS (SELECT 1 FROM approval_flow_def WHERE biz_type = 'fee_relief');

INSERT INTO approval_flow_def (biz_type, name, definition, version, enabled)
SELECT 'rent_adjust', '租金调价审批',
       '{"bizType":"rent_adjust","nodes":[{"id":"finance","type":"serial","role":"finance"},{"id":"leader","type":"serial","role":"admin"}]}',
       1, TRUE
WHERE NOT EXISTS (SELECT 1 FROM approval_flow_def WHERE biz_type = 'rent_adjust');
