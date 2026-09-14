-- ============================================================================
-- V49 资产 / 项目 / 分区「后续记录」扩展：成本信息 + 评估信息
--   沿用 V46 的多态宿主模型：owner_type + owner_id 定位宿主。
--
--   biz_cost_record        成本信息（1:N），每条下挂费用明细
--   biz_cost_item          费用明细（成本信息的子表，1:N）
--   biz_evaluation_info    评估信息（1:N，一次评估一条，含有效期限）
--
-- 可重复执行：表用 IF NOT EXISTS，字典按 code / (type_id, value) 幂等。
-- ============================================================================

-- ---------------- 成本信息（1:N） ----------------
CREATE TABLE IF NOT EXISTS biz_cost_record (
    id          BIGSERIAL PRIMARY KEY,
    owner_type  VARCHAR(20)  NOT NULL,          -- asset | project | zone
    owner_id    BIGINT       NOT NULL,
    amount_wan  NUMERIC(18,2),                  -- 成本金额(万元)
    cost_date   DATE,                           -- 成本日期
    remark      VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    created_by  BIGINT,
    updated_by  BIGINT,
    deleted_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_biz_cost_record_owner ON biz_cost_record (owner_type, owner_id, id);

-- ---------------- 费用明细（成本信息子表，1:N） ----------------
CREATE TABLE IF NOT EXISTS biz_cost_item (
    id          BIGSERIAL PRIMARY KEY,
    cost_id     BIGINT       NOT NULL,          -- biz_cost_record.id，由服务端赋值
    fee_name    VARCHAR(200),                   -- 费用名称
    cost_type   VARCHAR(50),                    -- 字典 sys_dict_type.code = cost_type
    amount      NUMERIC(18,2),                  -- 金额
    remark      VARCHAR(500),
    sort        INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    created_by  BIGINT,
    updated_by  BIGINT,
    deleted_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_biz_cost_item_cost ON biz_cost_item (cost_id, sort);

-- ---------------- 评估信息（1:N） ----------------
CREATE TABLE IF NOT EXISTS biz_evaluation_info (
    id              BIGSERIAL PRIMARY KEY,
    owner_type      VARCHAR(20)   NOT NULL,     -- asset | project | zone
    owner_id        BIGINT        NOT NULL,
    institution     VARCHAR(200),               -- 评估机构（多为外部单位，自由文本）
    asset_value     NUMERIC(18,2),              -- 资产价值
    rent_unit_price NUMERIC(18,2),              -- 租赁单价
    rent_price      NUMERIC(18,2),              -- 租赁价格
    evaluate_date   DATE,                       -- 评估时间
    valid_from      DATE,                       -- 评估有效期限起
    valid_to        DATE,                       -- 评估有效期限止
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    created_by      BIGINT,
    updated_by      BIGINT,
    deleted_at      TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_biz_evaluation_info_owner ON biz_evaluation_info (owner_type, owner_id, id);

COMMENT ON COLUMN biz_cost_record.amount_wan IS '成本金额(万元)，2 位小数';
COMMENT ON COLUMN biz_cost_item.cost_type IS '成本类型，取字典 sys_dict_type.code = cost_type';
COMMENT ON COLUMN biz_evaluation_info.valid_from IS '评估有效期限起（含）';
COMMENT ON COLUMN biz_evaluation_info.valid_to IS '评估有效期限止（含）';

-- ============================================================================
-- 字典：成本类型（挂在「资产管理字典」模块下，沿用 V19/V46 写法）
-- ============================================================================
INSERT INTO sys_dict_type (module_id, code, name, sort)
SELECT m.id, v.code, v.name, v.sort
FROM sys_dict_module m
JOIN (VALUES
    ('cost_type', '成本类型', 15)
) AS v(code, name, sort) ON TRUE
WHERE m.code = 'asset_management'
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_dict_item (type_id, value, label, sort)
SELECT t.id, v.value, v.label, v.sort
FROM sys_dict_type t
JOIN (VALUES
    ('cost_type', 'decoration',  '装修',   1),
    ('cost_type', 'maintenance', '维修',   2),
    ('cost_type', 'assessment',  '评估费', 3),
    ('cost_type', 'tax',         '税费',   4),
    ('cost_type', 'agency',      '中介费', 5),
    ('cost_type', 'operation',   '运营',   6),
    ('cost_type', 'other',       '其他',   7)
) AS v(type_code, value, label, sort) ON v.type_code = t.code
ON CONFLICT (type_id, value) DO NOTHING;
