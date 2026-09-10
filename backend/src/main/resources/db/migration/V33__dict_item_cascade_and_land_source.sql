-- 字典级联（字典项级）：父字典的「某个字典项」决定子字典的可见项白名单
--
-- 背景：V20 曾有 sys_dict_cascade（父字典编码 + 父字典值 → 子字典可见项），V21 改用
--       sys_dict_relation（字典 → 另一字典的若干个字典值）时丢失了字典项粒度，
--       现有「关联字典值」挂在字典层级、仅描述性、不参与校验（且当前无数据）。
-- 本迁移把关联细化到字典项：sys_dict_relation 增加 source_item_id。
--   source_item_id IS NULL      → 字典级关联（沿用原有语义，兼容既有数据/接口）
--   source_item_id IS NOT NULL  → 字典项级级联规则，作为子字典下拉的白名单
--
-- 语义（沿用 V20 白名单约定）：
--   存在规则时，子字典只暴露规则内的字典项；完全无规则时不做限制（向后兼容）。
--
-- 本次规则：「项目属性」→「资产来源」（FR-AST-001 资产来源）
--   房产类(property)：保持原有 8 项不变
--   土地类(land)    ：出让 / 行政划拨 / 其他
-- 幂等：DDL 使用 IF NOT EXISTS / IF EXISTS；种子数据 ON CONFLICT DO NOTHING；刷新带 IS DISTINCT FROM。

-- ============================================================================
-- 1) sys_dict_relation 支持字典项级关联
-- ============================================================================
ALTER TABLE sys_dict_relation ADD COLUMN IF NOT EXISTS source_item_id BIGINT;
COMMENT ON COLUMN sys_dict_relation.source_item_id
    IS '父字典项（NULL 表示挂在整本字典上，即字典级关联）';

CREATE INDEX IF NOT EXISTS idx_sys_dict_relation_source_item
    ON sys_dict_relation (source_item_id);

-- 唯一键从 (source_type_id, target_item_id) 细化为 (source_type_id, source_item_id, target_item_id)。
-- source_item_id 可空，普通唯一约束无法约束 NULL，故用 COALESCE 表达式唯一索引。
ALTER TABLE sys_dict_relation DROP CONSTRAINT IF EXISTS uk_sys_dict_relation;
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_dict_relation_item
    ON sys_dict_relation (source_type_id, COALESCE(source_item_id, 0), target_item_id);

-- ============================================================================
-- 2) 「资产来源」补充土地类专属字典项：出让 / 行政划拨
-- ============================================================================
INSERT INTO sys_dict_item (type_id, value, label, sort, status)
SELECT t.id, v.value, v.label, v.sort, 1
FROM sys_dict_type t
JOIN (VALUES
    ('land_grant',                '出让',    9),
    ('administrative_allocation', '行政划拨', 10)
) AS v(value, label, sort) ON TRUE
WHERE t.code = 'asset_source'
ON CONFLICT (type_id, value) DO NOTHING;

-- ============================================================================
-- 3) 种子级联规则：项目属性 → 资产来源
--    房产类：原有 8 项（新增的「出让 / 行政划拨」不进入房产类，故房产类选项不变）
--    土地类：出让 / 行政划拨 / 其他
-- ============================================================================
INSERT INTO sys_dict_relation (source_type_id, source_item_id, target_type_id, target_item_id, sort, status)
SELECT pt.id, pi.id, tt.id, ti.id, v.sort, 1
FROM (VALUES
    -- 房产类：不变
    ('property', 'investment_construction',  1),
    ('property', 'self_funded_construction', 2),
    ('property', 'acquisition_reserve',      3),
    ('property', 'transferred',              4),
    ('property', 'allocated_in',             5),
    ('property', 'leased_in',                6),
    ('property', 'entrusted',                7),
    ('property', 'other',                    8),
    -- 土地类：出让 / 行政划拨 / 其他
    ('land',     'land_grant',               1),
    ('land',     'administrative_allocation', 2),
    ('land',     'other',                    3)
) AS v(parent_value, child_value, sort)
JOIN sys_dict_type pt ON pt.code = 'project_property'
JOIN sys_dict_item pi ON pi.type_id = pt.id AND pi.value = v.parent_value
JOIN sys_dict_type tt ON tt.code = 'asset_source'
JOIN sys_dict_item ti ON ti.type_id = tt.id AND ti.value = v.child_value
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 4) 土地类资产来源口径对齐
--    既有土地类资产来源为「划入(allocated_in)」，不在土地类新口径（出让/行政划拨/其他）内，
--    会导致编辑页下拉无法回显，故按语义对齐为「行政划拨」。
--    房产类资产的来源均落在房产类 8 项内，无需调整。
-- ============================================================================
UPDATE asset
SET source_type = 'administrative_allocation',
    updated_at = now()
WHERE deleted_at IS NULL
  AND asset_type = 'land'
  AND source_type IS DISTINCT FROM 'administrative_allocation';
