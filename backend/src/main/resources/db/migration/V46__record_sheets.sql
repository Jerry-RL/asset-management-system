-- ============================================================================
-- V46 资产 / 项目 / 分区「后续记录」表单
--   设计见 docs/superpowers/specs/2026-09-12-record-forms-design.md
--
-- 四张记录表 + 一张通用附件表，全部用 owner_type + owner_id 多态定位宿主：
--   owner_type ∈ asset | project | zone            （记录的直接宿主）
--   owner_type ∈ receive_record | receive_issue
--              | source_info | disposal_record
--              | disposal_order                    （附件的直接宿主）
--
-- 可重复执行：表用 IF NOT EXISTS，列用 ADD COLUMN IF NOT EXISTS，
-- 字典按 code / (type_id, value) 幂等。
-- ============================================================================

-- ---------------- 通用附件关联 ----------------
CREATE TABLE IF NOT EXISTS biz_attachment (
    id          BIGSERIAL PRIMARY KEY,
    owner_type  VARCHAR(30)  NOT NULL,          -- 附件的直接宿主类型
    owner_id    BIGINT       NOT NULL,
    biz_type    VARCHAR(40)  NOT NULL,          -- 用途：receive_doc / issue_scene / source_attach / disposal_attach
    file_id     BIGINT       NOT NULL,          -- file_metadata.id
    sort        INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    created_by  BIGINT,
    updated_by  BIGINT,
    deleted_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_biz_attachment_owner ON biz_attachment (owner_type, owner_id, sort);
CREATE INDEX IF NOT EXISTS idx_biz_attachment_file  ON biz_attachment (file_id);

-- ---------------- 接收信息（1:N） ----------------
CREATE TABLE IF NOT EXISTS biz_receive_record (
    id                 BIGSERIAL PRIMARY KEY,
    owner_type         VARCHAR(20)  NOT NULL,
    owner_id           BIGINT       NOT NULL,
    handover_type      VARCHAR(50),             -- 字典 sys_dict_type.code = handover_type
    doc_name           VARCHAR(200),
    handover_user_id   BIGINT,                  -- sys_user.id，外部人员为空
    handover_user_name VARCHAR(100),            -- 姓名快照，内员选择时后端回填
    handover_date      DATE,
    remark             VARCHAR(500),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ,
    created_by         BIGINT,
    updated_by         BIGINT,
    deleted_at         TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_biz_receive_record_owner ON biz_receive_record (owner_type, owner_id, id);

-- ---------------- 遗留问题（接收信息子表，1:N） ----------------
CREATE TABLE IF NOT EXISTS biz_receive_issue (
    id              BIGSERIAL PRIMARY KEY,
    receive_id      BIGINT        NOT NULL,
    issue_type      VARCHAR(50),              -- 字典 sys_dict_type.code = issue_type
    description     VARCHAR(1000),
    discoverer_id   BIGINT,                   -- sys_user.id，外部人员为空
    discoverer_name VARCHAR(100),
    sort            INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    created_by      BIGINT,
    updated_by      BIGINT,
    deleted_at      TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_biz_receive_issue_receive ON biz_receive_issue (receive_id, sort);

-- ---------------- 来源明细（1:1） ----------------
CREATE TABLE IF NOT EXISTS biz_source_info (
    id                 BIGSERIAL PRIMARY KEY,
    owner_type         VARCHAR(20)   NOT NULL,
    owner_id           BIGINT        NOT NULL,
    source_person_id   BIGINT,                  -- sys_user.id，外部人员为空
    source_person_name VARCHAR(100),
    source_unit        VARCHAR(200),            -- 自由文本：原产权/移交单位多为外部单位
    source_date        DATE,
    source_desc        VARCHAR(1000),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ,
    created_by         BIGINT,
    updated_by         BIGINT,
    deleted_at         TIMESTAMPTZ
);
-- 部分唯一索引：1:1 约束只作用在「未软删」的行上，软删后允许重新录入
CREATE UNIQUE INDEX IF NOT EXISTS uk_biz_source_info_owner
    ON biz_source_info (owner_type, owner_id) WHERE deleted_at IS NULL;

-- ---------------- 处置台账（项目 / 分区） ----------------
CREATE TABLE IF NOT EXISTS biz_disposal_record (
    id                 BIGSERIAL PRIMARY KEY,
    owner_type         VARCHAR(20)  NOT NULL,   -- 本期仅 project / zone
    owner_id           BIGINT       NOT NULL,
    disposal_type      VARCHAR(50),             -- 字典 sys_dict_type.code = disposal_type
    disposal_user_id   BIGINT,
    disposal_user_name VARCHAR(100),
    amount_wan         NUMERIC(18,2),           -- 处置金额(万元)
    disposal_date      DATE,
    remark             VARCHAR(500),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ,
    created_by         BIGINT,
    updated_by         BIGINT,
    deleted_at         TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_biz_disposal_record_owner ON biz_disposal_record (owner_type, owner_id, id);

COMMENT ON COLUMN biz_disposal_record.amount_wan IS '处置金额(万元)，2 位小数';
COMMENT ON COLUMN biz_attachment.biz_type IS '附件用途：receive_doc/issue_scene/source_attach/disposal_attach';

-- ---------------- 资产处置：扩展现有表 ----------------
-- 资产的处置仍走 disposal_order（带状态机与资产退出语义），只补表单需要的展示字段；
-- disposal_order.actual_amount 的存量单位未在库中标注，本设计不做换算（设计 §4.2）。
ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS disposal_user_id   BIGINT;
ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS disposal_user_name VARCHAR(100);
ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS disposal_date      DATE;
ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS remark             VARCHAR(500);
COMMENT ON COLUMN disposal_order.disposal_date IS '处置日期';
COMMENT ON COLUMN disposal_order.disposal_user_name IS '处置人姓名快照，内员选择时后端回填';

-- ============================================================================
-- 字典：处置类型 / 交接类型 / 问题类型（挂在「资产管理字典」模块下，沿用 V19/V28 写法）
-- ============================================================================
INSERT INTO sys_dict_type (module_id, code, name, sort)
SELECT m.id, v.code, v.name, v.sort
FROM sys_dict_module m
JOIN (VALUES
    ('disposal_type', '处置类型', 12),
    ('handover_type', '交接类型', 13),
    ('issue_type', '问题类型', 14)
) AS v(code, name, sort) ON TRUE
WHERE m.code = 'asset_management'
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_dict_item (type_id, value, label, sort)
SELECT t.id, v.value, v.label, v.sort
FROM sys_dict_type t
JOIN (VALUES
    ('disposal_type', 'sale',     '出售',     1),
    ('disposal_type', 'scrap',    '报废',     2),
    ('disposal_type', 'transfer', '划转',     3),
    ('disposal_type', 'other',    '其他',     4),
    ('handover_type', 'receive',  '接收',     1),
    ('handover_type', 'handover', '移交',     2),
    ('handover_type', 'internal', '内部交接', 3),
    ('issue_type', 'ownership',   '权属',     1),
    ('issue_type', 'certificate', '证照',     2),
    ('issue_type', 'facility',    '设施',     3),
    ('issue_type', 'arrears',     '欠费',     4),
    ('issue_type', 'other',       '其他',     5)
) AS v(type_code, value, label, sort) ON v.type_code = t.code
ON CONFLICT (type_id, value) DO NOTHING;
