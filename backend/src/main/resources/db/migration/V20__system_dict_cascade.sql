-- 字典级联规则：父字典取值决定子字典可见项（如「项目属性」→「资产来源」）
-- 白名单语义：某 (parentTypeCode, parentValue, childTypeCode) 组合下存在启用规则时，
-- 子字典只暴露规则内的 childValue；该组合完全没有规则时不做限制（向后兼容）。

CREATE TABLE IF NOT EXISTS sys_dict_cascade (
    id               BIGSERIAL PRIMARY KEY,
    parent_type_code VARCHAR(64)  NOT NULL,
    parent_value     VARCHAR(64)  NOT NULL,
    child_type_code  VARCHAR(64)  NOT NULL,
    child_value      VARCHAR(64)  NOT NULL,
    sort             INT          NOT NULL DEFAULT 0,
    status           INT          NOT NULL DEFAULT 1,
    remark           VARCHAR(500),
    created_by       BIGINT,
    updated_by       BIGINT,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_sys_dict_cascade
        UNIQUE (parent_type_code, parent_value, child_type_code, child_value)
);

CREATE INDEX IF NOT EXISTS idx_sys_dict_cascade_lookup
    ON sys_dict_cascade (child_type_code, parent_type_code, parent_value);

-- 对齐 project_property 字典值与 asset.asset_type 既有取值（property / land），
-- 避免级联规则匹配不上。
UPDATE sys_dict_item i
SET value = 'property', updated_at = NOW()
FROM sys_dict_type t
WHERE i.type_id = t.id
  AND t.code = 'project_property'
  AND i.value = 'house';

-- 种子规则：项目属性 → 资产来源。
-- 说明：以下为基于业务惯例的初始拆分（土地类不含「投资建设/自筹建设/托管」等building类来源），
-- 可在「系统字典 → 级联规则」中按实际业务调整。
INSERT INTO sys_dict_cascade (parent_type_code, parent_value, child_type_code, child_value, sort)
SELECT v.parent_type_code, v.parent_value, v.child_type_code, v.child_value, v.sort
FROM (VALUES
    ('project_property', 'property', 'asset_source', 'investment_construction', 1),
    ('project_property', 'property', 'asset_source', 'self_funded_construction', 2),
    ('project_property', 'property', 'asset_source', 'acquisition_reserve', 3),
    ('project_property', 'property', 'asset_source', 'transferred', 4),
    ('project_property', 'property', 'asset_source', 'allocated_in', 5),
    ('project_property', 'property', 'asset_source', 'leased_in', 6),
    ('project_property', 'property', 'asset_source', 'entrusted', 7),
    ('project_property', 'property', 'asset_source', 'other', 8),
    ('project_property', 'land', 'asset_source', 'acquisition_reserve', 1),
    ('project_property', 'land', 'asset_source', 'transferred', 2),
    ('project_property', 'land', 'asset_source', 'allocated_in', 3),
    ('project_property', 'land', 'asset_source', 'leased_in', 4),
    ('project_property', 'land', 'asset_source', 'other', 5)
) AS v(parent_type_code, parent_value, child_type_code, child_value, sort)
ON CONFLICT (parent_type_code, parent_value, child_type_code, child_value) DO NOTHING;
