-- 资产来源口径回归：「项目属性」级联的判定依据是项目类型，与资产类型无关
--
-- 背景：V33 建立「项目属性 → 资产来源」级联（父项 = 项目属性的字典项，即 project.type）。
--       V33 第 4 步按「资产类型 = land」把 4 条土地类资产的来源改成了「行政划拨」，
--       但这 4 条资产所属项目均为「房产类」，在房产类口径下「行政划拨」不在可见项内，
--       会导致资产编辑页下拉无法回显（越界）。级联既然以项目属性为准，就不应按资产类型改写。
--
-- 处理：把这 4 条资产的来源改回「划入(allocated_in)」（房产类 8 项之一）。
--       「土地类 → 出让 / 行政划拨 / 其他」的级联规则保留，待存在「土地类」项目时自然生效。
-- 幂等：条件带 IS DISTINCT FROM，重复执行不再改动。

UPDATE asset
SET source_type = 'allocated_in',
    updated_at  = now()
WHERE deleted_at IS NULL
  AND asset_no IN (
      'AST-2026-005',   -- 临时周转地块
      'AST-HA-103',     -- 周恩来纪念馆配套停车场地块
      'AST-HA-202',     -- 王营货场周转地
      'AST-HA-407'      -- 湖畔休闲广场地块
  )
  AND source_type IS DISTINCT FROM 'allocated_in';
