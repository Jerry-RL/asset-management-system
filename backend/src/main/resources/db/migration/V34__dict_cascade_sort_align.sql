-- 级联规则展示顺序对齐
--
-- 背景：V33 建立「项目属性 → 资产来源」级联后，级联命中的下拉按「规则 sort」展示
--       （字典页可调整先后）。V33 的房产类规则 sort 沿用了 V20 的旧顺序，
--       与「资产来源」字典项的既有顺序不一致，会让房产类下拉顺序变化。
-- 本迁移把房产类规则 sort 对齐为字典项顺序，保证「房产类不变」；
-- 土地类规则 sort 为 出让 / 行政划拨 / 其他。
--
-- 幂等：仅按目标值设置固定 sort，重复执行不再产生变更。

-- 房产类：与 sys_dict_item.sort 一致（投资建设/收储/移交资产/划入/租入/自筹建设/托管/其他）
UPDATE sys_dict_relation r
SET sort = v.sort, updated_at = NOW()
FROM (VALUES
    ('investment_construction',  1),
    ('acquisition_reserve',      2),
    ('transferred',              3),
    ('allocated_in',             4),
    ('leased_in',                5),
    ('self_funded_construction', 6),
    ('entrusted',                7),
    ('other',                    8)
) AS v(child_value, sort)
JOIN sys_dict_type pt ON pt.code = 'project_property'
JOIN sys_dict_item pi ON pi.type_id = pt.id AND pi.value = 'property'
JOIN sys_dict_type tt ON tt.code = 'asset_source'
JOIN sys_dict_item ti ON ti.type_id = tt.id AND ti.value = v.child_value
WHERE r.source_item_id = pi.id
  AND r.target_type_id = tt.id
  AND r.target_item_id = ti.id
  AND r.sort IS DISTINCT FROM v.sort;

-- 土地类：出让 / 行政划拨 / 其他
UPDATE sys_dict_relation r
SET sort = v.sort, updated_at = NOW()
FROM (VALUES
    ('land_grant',                1),
    ('administrative_allocation', 2),
    ('other',                    3)
) AS v(child_value, sort)
JOIN sys_dict_type pt ON pt.code = 'project_property'
JOIN sys_dict_item pi ON pi.type_id = pt.id AND pi.value = 'land'
JOIN sys_dict_type tt ON tt.code = 'asset_source'
JOIN sys_dict_item ti ON ti.type_id = tt.id AND ti.value = v.child_value
WHERE r.source_item_id = pi.id
  AND r.target_type_id = tt.id
  AND r.target_item_id = ti.id
  AND r.sort IS DISTINCT FROM v.sort;
