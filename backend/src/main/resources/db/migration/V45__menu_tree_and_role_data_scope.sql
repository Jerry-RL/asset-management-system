-- ============================================================================
-- V45 菜单树 + 角色数据范围（设计见 docs/superpowers/specs/2026-09-12-menu-and-role-permission-design.md）
--
-- 三件事：
--   1. menu：code 唯一约束 + 按 modules.tsx 的 MENU 落种子（24 目录 / 64 菜单）
--   2. role_permission：绑定 menu_id、动作词表归一、删除从未使用的 data_scope
--   3. 新增 role_data_exclude（角色级数据范围排除清单）+ 权限回填
--
-- 破坏性变更：role_permission.data_scope 被删除，不可回滚（该列自建表起从未被读写）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. menu：code 是权限命名空间，必须全局唯一
-- ---------------------------------------------------------------------------
ALTER TABLE menu ADD CONSTRAINT uk_menu_code UNIQUE (code);

-- ---------------------------------------------------------------------------
-- 2. 菜单种子
--
-- 口径：
--   - 目录 code = 模块名；菜单 code = 目录 code + '.' + 页面名（点号分层）。
--   - 每个唯一 path 只落一行：modules.tsx 中 /dashboard/consolidate（首页与工作台、
--     经营分析）与 /tenants（资产招租、运营管理）各出现两次，归属目录按下列规则固定：
--       /dashboard/consolidate -> home（首页与工作台）
--       /tenants               -> ops（运营管理，与 SRS §3.3 一致）
--     否则侧边栏会出现两个指向同一页面的入口，权限矩阵多出无意义行。
--   - 目录无 path；菜单必须挂在目录下。
--   - 图标只给目录：菜单留空，侧边栏回退到前端路由注册表的 path 图标。
--   - system.* 的 code 必须与 SystemController 上的 @RequiresPerm 注解逐字一致，
--     否则 strict-perm 一生效，系统管理接口对所有角色 403。
-- ---------------------------------------------------------------------------

-- 2.1 目录（24 个）
INSERT INTO menu (code, name, menu_type, path, icon, sort, parent_id) VALUES
    ('home',         '首页与工作台', 'dir', NULL, 'app',         10, NULL),
    ('analysis',     '经营分析',     'dir', NULL, 'chart',       20, NULL),
    ('risk',         '风险管控',     'dir', NULL, 'alert',       30, NULL),
    ('asset',        '资产台账',     'dir', NULL, 'ledger',      40, NULL),
    ('deed',         '资债权证',     'dir', NULL, 'certificate', 50, NULL),
    ('lease',        '资产招租',     'dir', NULL, 'listing',     60, NULL),
    ('operation',    '资产运营',     'dir', NULL, 'operation',   70, NULL),
    ('contract',     '合同管理',     'dir', NULL, 'contract',    80, NULL),
    ('billing',      '定价与计费',   'dir', NULL, 'bill',        90, NULL),
    ('finance',      '收费与发票',   'dir', NULL, 'payment',    100, NULL),
    ('dunning',      '履约催缴',     'dir', NULL, 'dunning',    110, NULL),
    ('maintenance',  '巡检维修',     'dir', NULL, 'repair',     120, NULL),
    ('task',         '任务中心',     'dir', NULL, 'task',       130, NULL),
    ('revitalize',   '空置盘活',     'dir', NULL, 'revitalize', 140, NULL),
    ('compliance',   '合规监管',     'dir', NULL, 'report',     150, NULL),
    ('message',      '消息待办',     'dir', NULL, 'notify',     160, NULL),
    ('fixedasset',   '固定资产',     'dir', NULL, 'fixedasset', 170, NULL),
    ('intangible',   '无形资产',     'dir', NULL, 'intangible', 180, NULL),
    ('org',          '组织架构',     'dir', NULL, 'org',        190, NULL),
    ('ops',          '运营管理',     'dir', NULL, 'tenant',     200, NULL),
    ('config',       '系统配置',     'dir', NULL, 'config',     210, NULL),
    ('system',       '系统管理',     'dir', NULL, 'setting',    220, NULL),
    ('intelligence', '智能中心',     'dir', NULL, 'ai',         230, NULL),
    ('migration',    '期初迁移',     'dir', NULL, 'migration',  240, NULL)
ON CONFLICT (code) DO NOTHING;

-- 2.2 菜单（64 个，按唯一 path 去重）
INSERT INTO menu (code, name, menu_type, path, icon, sort, parent_id)
SELECT v.code, v.name, 'menu', v.path, NULL, v.sort, d.id
FROM (VALUES
    -- 首页与工作台
    ('home.center',            '应用中心',           '/',                            10, 'home'),
    ('home.calendar',          '经营日历',           '/ops-calendar',                20, 'home'),
    ('home.dashboard',         '经营看板',           '/dashboard',                   30, 'home'),
    ('home.consolidate',       '集团合并看板',       '/dashboard/consolidate',       40, 'home'),
    -- 经营分析
    ('analysis.plan',          '经营计划与预算',     '/business-plans',              10, 'analysis'),
    ('analysis.report',        '数据报表',           '/reports',                     20, 'analysis'),
    -- 风险管控
    ('risk.rule',              '预警配置',           '/alerts/rules',                10, 'risk'),
    ('risk.record',            '预警提醒与记录',     '/alerts/records',              20, 'risk'),
    -- 资产台账
    ('asset.project',          '项目管理',           '/projects',                    10, 'asset'),
    ('asset.ledger',           '资产台账',           '/assets',                      20, 'asset'),
    ('asset.structureLog',     '拆分合并日志',       '/assets/structure-logs',       30, 'asset'),
    ('asset.map',              '资产地图',           '/asset-map',                   40, 'asset'),
    -- 资债权证
    ('deed.certificate',       '权证信息',           '/certificates',                10, 'deed'),
    ('deed.mortgage',          '抵押列表',           '/mortgages',                   20, 'deed'),
    ('deed.transfer',          '资产调拨',           '/asset-transfers',             30, 'deed'),
    ('deed.evaluation',        '评估申请',           '/evaluations',                 40, 'deed'),
    -- 资产招租
    ('lease.listing',          '招租管理',           '/lease-listings',              10, 'lease'),
    ('lease.tender',           '公开招租',           '/tender/announcements',        20, 'lease'),
    -- 资产运营
    ('operation.disposal',     '资产处置',           '/disposals',                   10, 'operation'),
    ('operation.occupation',   '临时占用',           '/occupations',                 20, 'operation'),
    ('operation.selfUse',      '资产自用',           '/self-uses',                   30, 'operation'),
    ('operation.audit',        '经营性盘点',         '/asset-audits',                40, 'operation'),
    -- 合同管理
    ('contract.ledger',        '合同管理',           '/contracts',                   10, 'contract'),
    ('contract.vacate',        '退租清场与保证金',   '/vacate-orders',               20, 'contract'),
    ('contract.bundle',        '组合/拆分租赁',      '/lease-bundles',               30, 'contract'),
    ('contract.template',      '合同模板',           '/contract-templates',          40, 'contract'),
    -- 定价与计费
    ('billing.bill',           '账单（收费大厅）',   '/billing/bills',               10, 'billing'),
    ('billing.meter',          '表计档案与抄表',     '/meters',                      20, 'billing'),
    ('billing.apportion',      '公摊配置',           '/apportion-configs',           30, 'billing'),
    ('billing.relief',         '费用减免',           '/adjustments/fee-reliefs',     40, 'billing'),
    ('billing.rentAdjust',     '租金调价',           '/adjustments/rent-adjusts',    50, 'billing'),
    -- 收费与发票
    ('finance.payment',        '收款记录',           '/payments',                    10, 'finance'),
    ('finance.paymentConfirm', '现场收款待确认',     '/payments/pending-confirm',    20, 'finance'),
    ('finance.refund',         '退款冲正',           '/refunds',                     30, 'finance'),
    ('finance.invoice',        '发票管理',           '/invoices',                    40, 'finance'),
    ('finance.taxRate',        '发票税率',           '/invoice-tax-rates',           50, 'finance'),
    ('finance.bankFlow',       '银行对账',           '/finance/bank-flows',          60, 'finance'),
    ('finance.voucher',        '财务凭证',           '/finance/vouchers',            70, 'finance'),
    -- 履约催缴
    ('dunning.record',         '催缴记录',           '/dunning/records',             10, 'dunning'),
    ('dunning.auto',           '自动化催缴',         '/dunning/auto',                20, 'dunning'),
    -- 巡检维修
    ('maintenance.repair',     '报修工单',           '/repairs',                     10, 'maintenance'),
    ('maintenance.vendor',     '维修公司',           '/vendors',                     20, 'maintenance'),
    ('maintenance.inspection', '巡查记录',           '/inspections',                 30, 'maintenance'),
    -- 任务中心
    ('task.manage',            '任务管理',           '/tasks',                       10, 'task'),
    ('task.approval',          '审批中心',           '/approvals',                   20, 'task'),
    -- 空置盘活
    ('revitalize.task',        '盘活任务',           '/revitalization',              10, 'revitalize'),
    -- 合规监管
    ('compliance.report',      '监管报送',           '/regulation/reports',          10, 'compliance'),
    -- 消息待办
    ('message.notify',         '消息通知',           '/notifications',               10, 'message'),
    -- 固定资产
    ('fixedasset.ledger',      '固资清单',           '/fixed-assets',                10, 'fixedasset'),
    ('fixedasset.inventory',   '固资盘点',           '/fixed-assets/inventories',    20, 'fixedasset'),
    -- 无形资产
    ('intangible.ledger',      '无形资产台账',       '/intangible-assets',           10, 'intangible'),
    -- 组织架构
    ('org.structure',          '组织架构图谱',       '/org/structure',               10, 'org'),
    ('org.company',            '公司管理',           '/org/companies',               20, 'org'),
    ('org.department',         '部门管理',           '/org/departments',             30, 'org'),
    ('org.user',               '人员维护',           '/system/users',                40, 'org'),
    -- 运营管理
    ('ops.tenant',             '租户管理',           '/tenants',                     10, 'ops'),
    -- 系统配置
    ('config.version',         '参数版本留痕',       '/config/versions',             10, 'config'),
    -- 系统管理
    ('system.role',            '角色权限',           '/system/roles',                10, 'system'),
    ('system.menu',            '菜单管理',           '/system/menus',                20, 'system'),
    ('system.dict',            '系统字典',           '/system/dict',                 30, 'system'),
    -- 智能中心
    ('intelligence.template',  '报告模板',           '/intelligence/templates',      10, 'intelligence'),
    ('intelligence.report',    'Agent 报告',         '/intelligence/reports',        20, 'intelligence'),
    ('intelligence.session',   'Agent 会话',         '/intelligence/sessions',       30, 'intelligence'),
    -- 期初迁移
    ('migration.batch',        '迁移批次与试算平衡', '/migrations/batches',          10, 'migration')
) AS v(code, name, path, sort, parent_code)
JOIN menu d ON d.code = v.parent_code
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. role_permission
-- ---------------------------------------------------------------------------

-- 3.1 绑定 menu_id
ALTER TABLE role_permission ADD COLUMN menu_id BIGINT;

-- 3.2 动作词表归一：旧注释里的 add/edit 收敛到固定词表（view/create/update/delete/
--     export/import/approve/audit/assign），必须在加唯一约束之前做，否则同一
--     (role_id, menu_id) 上会同时存在 add 与 create 造成约束冲突。
UPDATE role_permission SET action = 'create' WHERE action = 'add';
UPDATE role_permission SET action = 'update' WHERE action = 'edit';

-- 3.3 回填 menu_id；解析不到的旧行（引用了已不存在的 menu_code）直接清理，
--     否则它们会成为永远不生效的僵尸授权。
UPDATE role_permission rp SET menu_id = m.id FROM menu m WHERE m.code = rp.menu_code;
DELETE FROM role_permission WHERE menu_id IS NULL;

-- 3.4 去重 + 唯一约束
DELETE FROM role_permission a
    USING role_permission b
    WHERE a.id > b.id
      AND a.role_id = b.role_id
      AND a.menu_id = b.menu_id
      AND a.action = b.action;
ALTER TABLE role_permission ADD CONSTRAINT uk_role_menu_action UNIQUE (role_id, menu_id, action);
CREATE INDEX idx_role_permission_menu ON role_permission (menu_id);

-- 3.5 删除从未使用的 data_scope（破坏性，不可回滚）
--     代码侧必须同批同步：RolePermission 实体新增 menuId、移除 dataScope，
--     否则 MyBatis-Plus 仍会查询已删除的列，每个已认证请求都抛「列不存在」。
ALTER TABLE role_permission DROP COLUMN data_scope;

-- ---------------------------------------------------------------------------
-- 4. 角色级数据范围排除清单
--    语义：勾选即排除，命中即整棵子树不可见（见设计 5.2）。
-- ---------------------------------------------------------------------------
CREATE TABLE role_data_exclude (
    id         BIGSERIAL PRIMARY KEY,
    role_id    BIGINT      NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    company_id BIGINT      NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_role_data_exclude UNIQUE (role_id, company_id)
);
CREATE INDEX idx_role_data_exclude_role ON role_data_exclude (role_id);

-- ---------------------------------------------------------------------------
-- 5. 权限回填
--
--    目的：本次是「首次给权限表灌数据」，回填不当会造成两类上线事故：
--      (a) 只回填 view -> 注解一生效，所有非超管账号在首批模块上的写操作全部 403；
--      (b) 向所有角色无差别回填 -> 业务角色凭空获得 system.role:view 等系统管理读权限。
--    因此按敏感性分区回填。
-- ---------------------------------------------------------------------------

-- 5.1 导航类菜单：向所有非超管角色回填 view。
--     今天静态侧边栏对所有人全量可见，回填 view 是为了保持这一导航可见性不变。
--
--     敏感菜单必须排除在外（设计 7.1 的「不回填」清单）。除 system.* 之外还要显式排除
--     org.user：modules.tsx 把「人员维护」(/system/users) 归在「组织架构」目录下，
--     其 code 因此是 org.user 而不是设计示例里的 system.user —— 只按 system.% 前缀排除
--     会让这个页面连同 org.user:view 一起回填给全部业务角色，等于对所有人敞开人员管理。
INSERT INTO role_permission (role_id, menu_id, menu_code, action)
SELECT r.id, m.id, m.code, 'view'
FROM role r
CROSS JOIN menu m
WHERE r.code <> 'super_admin'
  AND m.menu_type = 'menu'
  AND m.code NOT LIKE 'system.%'
  AND m.code <> 'org.user'
ON CONFLICT DO NOTHING;

-- 5.2 系统管理菜单 + 人员维护：只授予原有运维/管理员角色（operator），不向业务角色敞开。
--     super_admin 走 isSuperAdmin() 旁路，无需数据行；system.role:assign 等提权能力
--     同样不在迁移里授予，由 super_admin 在角色权限页显式下发（见设计 4.5）。
INSERT INTO role_permission (role_id, menu_id, menu_code, action)
SELECT r.id, m.id, m.code, 'view'
FROM role r
CROSS JOIN menu m
WHERE r.code = 'operator'
  AND m.menu_type = 'menu'
  AND (m.code LIKE 'system.%' OR m.code = 'org.user')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 5.3 写动作**故意不回填** —— 上线前置人工步骤（设计 7.1 的 (b) 分支）
--
--     设计 7.1 要求「动作级回填」(a) 按角色现有可达能力补回写动作，或 (b) 把
--     「矩阵补动作」列为上线前置人工步骤，二者择一，不能只回填 view。
--     实测 (a) 在当前数据上**不可执行**：本迁移之前 menu / role_permission 均为空表，
--     且全仓没有任何 @PreAuthorize / @RequiresPerm（基线 1f7ddd7 计数为 0）——
--     即「现有可达能力」等于「谁都能写」。照此回填等于给每个业务角色授予全模块
--     create/update/delete，包括 leader（决策层，本应只读）与 approver（审批人）。
--     因此本迁移取 (b)，并以本注释作为该项的落盘记录。
--
--     上线前置步骤（必须与注解生效同批执行，否则业务角色写操作大面积 403）：
--       1) 以 super_admin 登录 → 系统管理 → 角色权限；
--       2) 逐个非超管角色，在「功能权限」矩阵上勾选其应有的 create/update/delete
--          等动作并保存（矩阵会标出哪些动作已接入强制校验）；
--       3) 动作词表固定为 view/create/update/delete/export/import/approve/audit/assign。
--
--     未执行步骤 2 的后果是可观测且可回滚的：写接口返回 403，前置导航与只读不受影响。
--     反过来，误回填的宽权限是静默的、且会长期留在库里没人复核 —— 故宁可选择
--     「明确的中断」而不是「安静的越权」。
-- ---------------------------------------------------------------------------
