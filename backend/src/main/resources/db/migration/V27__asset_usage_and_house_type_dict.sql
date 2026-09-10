-- 资产管理字典补充：资产用途 / 资产户型
--   资产用途 asset_usage：办公 / 商业 / 住宅 / 车库
--   资产户型 asset_house_type：一室一厅 / 两室一厅 / 三室两厅 / 四室两厅
--   同时为资产增加「户型」列；「用途」沿用既有 asset.usage_type，改为引用字典
-- 可重复执行：字典按 code 幂等，字典项按 (type_id, value) 幂等。

-- ---- 资产：户型字段 ----
ALTER TABLE asset ADD COLUMN IF NOT EXISTS house_type VARCHAR(50);
COMMENT ON COLUMN asset.house_type IS '户型，取值见 sys_dict_type.code = asset_house_type';

-- ============================================================================
-- 字典：挂在「资产管理字典」模块下（沿用既有 code 幂等写法）
-- ============================================================================
INSERT INTO sys_dict_type (module_id, code, name, sort)
SELECT m.id, v.code, v.name, v.sort
FROM sys_dict_module m
JOIN (VALUES
    ('asset_usage',      '资产用途', 9),
    ('asset_house_type', '资产户型', 10)
) AS v(code, name, sort) ON TRUE
WHERE m.code = 'asset_management'
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_dict_item (type_id, value, label, sort)
SELECT t.id, v.value, v.label, v.sort
FROM sys_dict_type t
JOIN (VALUES
    -- 资产用途
    ('asset_usage', 'office',      '办公', 1),
    ('asset_usage', 'commercial',  '商业', 2),
    ('asset_usage', 'residential', '住宅', 3),
    ('asset_usage', 'garage',      '车库', 4),
    -- 资产户型
    ('asset_house_type', 'one_bed_one_living',   '一室一厅', 1),
    ('asset_house_type', 'two_bed_one_living',   '两室一厅', 2),
    ('asset_house_type', 'three_bed_two_living', '三室两厅', 3),
    ('asset_house_type', 'four_bed_two_living',  '四室两厅', 4)
) AS v(type_code, value, label, sort) ON v.type_code = t.code
ON CONFLICT (type_id, value) DO NOTHING;
