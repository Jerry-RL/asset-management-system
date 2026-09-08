-- 抵押解押审批 / 资产拆分合并 / Agent

ALTER TABLE mortgage ADD COLUMN IF NOT EXISTS release_status VARCHAR(20);
ALTER TABLE mortgage ADD COLUMN IF NOT EXISTS release_remark VARCHAR(500);
ALTER TABLE mortgage ADD COLUMN IF NOT EXISTS released_at TIMESTAMPTZ;

ALTER TABLE asset ADD COLUMN IF NOT EXISTS structure_status VARCHAR(20) DEFAULT 'active';
-- active / frozen / merged_out
ALTER TABLE asset ADD COLUMN IF NOT EXISTS root_asset_id BIGINT;
ALTER TABLE asset ADD COLUMN IF NOT EXISTS old_asset_no VARCHAR(64);

CREATE TABLE IF NOT EXISTS asset_structure_log (
    id              BIGSERIAL PRIMARY KEY,
    op_type         VARCHAR(20) NOT NULL, -- split / merge
    source_asset_ids TEXT NOT NULL,
    result_asset_ids TEXT,
    mapping_json    TEXT,
    remark          VARCHAR(500),
    operator_id     BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS asset_code_mapping (
    id              BIGSERIAL PRIMARY KEY,
    old_asset_no    VARCHAR(64) NOT NULL,
    new_asset_id    BIGINT NOT NULL,
    new_asset_no    VARCHAR(64),
    op_type         VARCHAR(20),
    structure_log_id BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_asset_code_mapping_old ON asset_code_mapping (old_asset_no);
CREATE INDEX IF NOT EXISTS idx_asset_structure_root ON asset (root_asset_id);

ALTER TABLE agent_report ADD COLUMN IF NOT EXISTS content_html TEXT;

INSERT INTO approval_flow_def (biz_type, name, definition, version, enabled)
SELECT 'mortgage_release', '解押审批',
       '{"bizType":"mortgage_release","nodes":[{"id":"asset_mgr","type":"serial","role":"approver"},{"id":"finance","type":"serial","role":"finance"}]}',
       1, TRUE
WHERE NOT EXISTS (SELECT 1 FROM approval_flow_def WHERE biz_type = 'mortgage_release');
