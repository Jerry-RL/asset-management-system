-- ============================================================================
-- V61 「资产经营管理」目录 + 7 个子模块菜单（本次只落菜单骨架，功能待迭代）
--
-- 需求：新增一级目录「资产经营管理」，下挂 7 个子菜单：
--   资产招租管理 / 资产租赁签约管理 / 资产租赁风险管理 / 资产资源管理 /
--   资产其他使用管理 / 资产巡检管理 / 资产维修管理
--
-- 本次交付的是**菜单骨架 + 规划页**，不是 7 套完整功能。因此本迁移只碰菜单表，
-- 不建任何业务表、不加任何 `@RequiresPerm`（规划页是只读说明页，没有接口）。
--
-- 为什么每个子菜单都用**新 path**（`/asset-mgmt/*`）而不是指向既有页面：
--   V45 §2 的口径是「每个唯一 path 只落一行」，否则侧边栏会出现两个指向同一页面的
--   入口、权限矩阵多出无意义行。而本次这 7 个模块大多已有对应页面
--   （招租 → /lease-listings、签约 → /contracts、资源 → /assets、其他使用 → /occupations、
--     巡检 → /inspections、维修 → /repairs），直接复用 path 就会撞上这条约定。
--   新 path 的页面是规划页，页内以链接形式指向既有页面 —— 既不重复入口，
--   又让使用者今天就跳到能干活的地方。
--
-- code 命名沿用仓内口径「菜单 code = 目录 code + '.' + 页面名」：
--   目录 code = `assetmgmt`，菜单 code = `assetmgmt.leaseListing` 等。
--   与前端 lib/pathToCode.ts 的镜像逐字一致（漂移会让按钮门槛静默失效）。
--
-- 可重复执行：INSERT ... ON CONFLICT DO NOTHING（menu.code 上有 uk_menu_code 唯一约束）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 目录：资产经营管理
--
-- sort 75 落在「资产运营」(70) 之后、「合同管理」(80) 之前 ——
-- 这一组是「按经营视角把招租 / 签约 / 风险 / 资源 / 其他使用 / 巡检 / 维修串起来」，
-- 紧跟在资产运营之后读起来最顺。
--
-- icon = 'solution'：与 V45 给 24 个目录都配图标的做法一致（不是留空等前端兜底）。
-- 取值必须存在于前端 lib/menuIcons.tsx 的 ICON_BY_NAME，否则会静默退回默认图标。
-- ---------------------------------------------------------------------------
INSERT INTO menu (code, name, menu_type, path, icon, sort, parent_id)
VALUES ('assetmgmt', '资产经营管理', 'dir', NULL, 'solution', 75, NULL)
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. 7 个子菜单
--
-- 按 code = 'assetmgmt' 定位父目录（不依赖目录名）。
-- icon 留空：与 V54 / V55 / V57 / V59 / V60 口径一致
-- （图标只给目录，菜单为空时侧栏回退前端 PATH_ICONS 的路由图标）。
--
-- sort 10..70 按需求给出的顺序：招租 → 签约 → 风险 → 资源 → 其他使用 → 巡检 → 维修。
-- 这个顺序本身就是业务流程顺序，改动前请确认产品意图。
-- ---------------------------------------------------------------------------
INSERT INTO menu (code, name, menu_type, path, icon, sort, parent_id)
SELECT v.code, v.name, 'menu', v.path, NULL, v.sort, d.id
FROM (VALUES
    ('assetmgmt.leaseListing', '资产招租管理',     '/asset-mgmt/lease-listing', 10),
    ('assetmgmt.leaseSigning', '资产租赁签约管理', '/asset-mgmt/lease-signing', 20),
    ('assetmgmt.leaseRisk',    '资产租赁风险管理', '/asset-mgmt/lease-risk',    30),
    ('assetmgmt.resource',     '资产资源管理',     '/asset-mgmt/resource',      40),
    ('assetmgmt.otherUse',     '资产其他使用管理', '/asset-mgmt/other-use',     50),
    ('assetmgmt.inspection',   '资产巡检管理',     '/asset-mgmt/inspection',    60),
    ('assetmgmt.repair',       '资产维修管理',     '/asset-mgmt/repair',        70)
) AS v(code, name, path, sort)
CROSS JOIN menu d
WHERE d.code = 'assetmgmt'
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. 权限回填：导航类菜单 → 所有非超管角色回填 view
--
-- 与 V55 / V57 / V59 同口径（V45 §5.1 的通用回填规则）：这 7 页是**导航页**，
-- 不含敏感数据、也没有写操作，因此不按 V60 的「只给 operator」处理 ——
-- V60 那样做是因为那一页能给人登记角色（权限面入口）。
--
-- 写动作一律不回填（V45 §5.3：动作级回填是上线前置人工步骤）。
-- super_admin 走 isSuperAdmin() 旁路，回填反而是脏数据。
--
-- 用 LIKE 'assetmgmt.%' 而不是逐个 code 列举：本迁移只插入这 7 条，
-- 前缀与目录 code 的对应关系使两者等价，但前缀写法在日后追加子菜单时不会漏项。
-- ---------------------------------------------------------------------------
INSERT INTO role_permission (role_id, menu_id, menu_code, action)
SELECT r.id, m.id, m.code, 'view'
FROM role r
CROSS JOIN menu m
WHERE r.code <> 'super_admin'
  AND m.code LIKE 'assetmgmt.%'
ON CONFLICT DO NOTHING;
