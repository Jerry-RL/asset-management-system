-- 项目类型口径统一（FR-AST-001）
-- 前端「新增/编辑项目」的类型下拉已改为取自
--   「系统字典 → 资产管理字典 → 项目属性」（sys_dict_type.code = 'project_property'，
--    当前字典取值：property 房产类 / land 土地类）。
-- 历史演示数据存在 park（园区）/ building（楼宇）等旧口径，与字典不匹配，
-- 会导致编辑页下拉无法回显，故统一刷新为 property（房产类）。
--
-- 规则：仅刷新「不在 project_property 字典内」的取值 → 'property'；
--       已在字典内的取值（property / land）保持不变。
-- 幂等：刷新后所有取值都落在字典内，重复执行不会再改动任何行。

UPDATE project p
SET type = 'property',
    updated_at = now()
WHERE p.type IS NOT NULL
  AND p.type <> ''
  AND NOT EXISTS (
      SELECT 1
      FROM sys_dict_item i
      JOIN sys_dict_type t ON t.id = i.type_id
      WHERE t.code = 'project_property'
        AND i.value = p.type
  );
