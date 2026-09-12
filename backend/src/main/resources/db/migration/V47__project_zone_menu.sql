-- ============================================================================
-- V47 项目分区管理菜单
-- 设计：docs/superpowers/specs/2026-09-12-project-zone-management-design.md §3.1
--
-- 新增侧栏页「项目分区管理」（code = asset.projectZone，路径 /project-zones），
-- 挂在「资产台账」(asset) 目录下，排序 15 —— 落在「项目管理」(10) 与「资产台账」(20) 之间。
--
-- 本迁移只碰 menu 与 role_permission 两张权限表：
--   - 不新增任何 @RequiresPerm 引用的编码（本页复用 asset.project:* 与 asset.ledger:*）；
--   - 分区、项目、资产等业务表无 DDL、无 DML。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 菜单行
--
--    icon 留空：与 V45 口径一致（「图标只给目录：菜单留空，侧边栏回退到前端路由注册表的
--    path 图标」），前端 PATH_ICONS['/project-zones'] 提供兜底图标。
--    父目录按 code 解析，不硬编码 parent_id —— 各环境 menu.id 不保证一致。
-- ---------------------------------------------------------------------------
INSERT INTO menu (code, name, menu_type, path, icon, sort, parent_id)
SELECT 'asset.projectZone', '项目分区管理', 'menu', '/project-zones', NULL, 15, d.id
FROM menu d
WHERE d.code = 'asset'
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. view 回填
--
--    本菜单在 V45 §5.1 的批量回填之后才出现，不回填则除 super_admin 外所有角色都看不到
--    入口 —— 新功能会表现为「没做出来」，而不是报错。
--
--    与 V45 §5.1 保持同一口径：只回填 view、显式排除 super_admin、范围只限本菜单。
--    写动作（create / update / delete）一律不回填 —— V45 §5.3 明确把「动作级回填」列为
--    上线前置人工步骤，误回填的宽权限是静默的且会长期留在库里。
-- ---------------------------------------------------------------------------
INSERT INTO role_permission (role_id, menu_id, menu_code, action)
SELECT r.id, m.id, m.code, 'view'
FROM role r
CROSS JOIN menu m
WHERE r.code <> 'super_admin'
  AND m.code = 'asset.projectZone'
ON CONFLICT DO NOTHING;
