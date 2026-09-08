-- 经营计划督办 / 业财月结 / 电子签 / 合规备案

ALTER TABLE business_plan ADD COLUMN IF NOT EXISTS deviation_threshold NUMERIC(10,4) DEFAULT 0.05;
ALTER TABLE business_plan ADD COLUMN IF NOT EXISTS last_scanned_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS finance_month_close (
    id          BIGSERIAL PRIMARY KEY,
    period      VARCHAR(7) NOT NULL UNIQUE, -- yyyy-MM
    status      VARCHAR(20) NOT NULL DEFAULT 'open', -- open/locked
    locked_at   TIMESTAMPTZ,
    locked_by   BIGINT,
    remark      VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE contract ADD COLUMN IF NOT EXISTS esign_flow_id VARCHAR(64);
ALTER TABLE contract ADD COLUMN IF NOT EXISTS esign_signed_at TIMESTAMPTZ;
ALTER TABLE contract ADD COLUMN IF NOT EXISTS esign_evidence_file_id BIGINT;

CREATE TABLE IF NOT EXISTS compliance_filing (
    id              BIGSERIAL PRIMARY KEY,
    biz_type        VARCHAR(50) NOT NULL,
    biz_id          BIGINT NOT NULL,
    title           VARCHAR(200),
    need_meeting    BOOLEAN NOT NULL DEFAULT FALSE,
    meeting_minutes_file_id BIGINT,
    package_json    TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'draft', -- draft/ready/archived
    created_by      BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_compliance_filing_biz ON compliance_filing (biz_type, biz_id);

INSERT INTO approval_flow_def (biz_type, name, definition, version, enabled)
SELECT 'rent_relief_major', '大额减免重大事项审批',
       '{"bizType":"rent_relief_major","nodes":[{"id":"finance","type":"serial","role":"finance"},{"id":"leader","type":"serial","role":"admin"}]}',
       1, TRUE
WHERE NOT EXISTS (SELECT 1 FROM approval_flow_def WHERE biz_type = 'rent_relief_major');

INSERT INTO approval_flow_def (biz_type, name, definition, version, enabled)
SELECT 'disposal_major', '资产处置三重一大',
       '{"bizType":"disposal_major","nodes":[{"id":"asset_mgr","type":"serial","role":"approver"},{"id":"leader","type":"serial","role":"admin"}]}',
       1, TRUE
WHERE NOT EXISTS (SELECT 1 FROM approval_flow_def WHERE biz_type = 'disposal_major');
