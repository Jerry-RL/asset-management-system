-- ============================================================================
-- V60 资产运营人员管理（asset_operator）
--
-- 需求两条：
--   ① 「运营管理」菜单改名为「资产运营人员管理」；
--   ② 补充子模块「资产运营人员管理」：新增运营人员时填写
--      **人员选择 / 角色选择 / 资产运营范围选择（项目、分区、资产）**。
--
-- 为什么单建三张表而不是往 `user` 上加列：
--   · 一份档案挂**多个**角色、**多个**运营范围（且范围跨三种类型），是典型的一对多；
--     往 `user` 上塞 `role_ids` / `scope_ids` 这类拼接字符串，会让「按范围反查运营人员」
--     退化成全表 LIKE，且没有唯一约束兜住重复项。
--   · 运营范围沿用 V56 抵押记录的 `target_type` 口径（project / zone / asset）：
--     同一个语义在全仓只有一种写法，报表与前端都能复用同一套类型标签。
--
-- **与 `user_role` 无关（已确认口径）**：本模块的「角色选择」只**记录**在运营人员档案里，
-- 不写 `user_role`、不产生任何实际授权。真正给账号授权仍在「系统管理 → 角色权限」
-- （`system.role:assign`）与「人员维护」里做 —— 若这里同步写 `user_role`，
-- 一个业务模块的页面就等于多了一条提权路径，而它的权限码不是提权类权限码。
--
-- **本期不接入越权拦截（已确认口径）**：`asset_operator_scope` 只作登记与展示，
-- `RbacService` 的公司级数据范围不受影响。表结构已按可扩展口径建（类型 + id 分开存），
-- 后续若要接入按标的过滤，不需要改表。
--
-- 三条口径（与 V54 / V55 一致）：
--   1. 无单号列，界面用 `#id`；
--   2. 无外键约束 —— 归属由应用层断言，避免 FK 让迁移顺序变脆弱；
--   3. 软删用 `deleted_at TIMESTAMPTZ` + 显式过滤，不用 @TableLogic
--      （全局逻辑删除字段口径是 deleted:0/1，与 deleted_at 范式不一致）。
-- 可重复执行：CREATE ... IF NOT EXISTS + ON CONFLICT DO NOTHING + 只改名的 UPDATE。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 运营人员档案（一个人一行）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS asset_operator (
    id         BIGSERIAL PRIMARY KEY,
    -- 人员（sys_user.id）。不设 FK：人员被软删后档案仍需可读（历史留痕）
    user_id    BIGINT       NOT NULL,
    -- 1 启用 / 0 停用。停用不删档案：运营范围是历史信息，删掉就只能靠翻审计日志还原
    status     SMALLINT     NOT NULL DEFAULT 1,
    remark     VARCHAR(500),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ,
    created_by BIGINT,
    updated_by BIGINT,
    deleted_at TIMESTAMPTZ
);

-- 一个人员只能有一份**有效**档案。
-- 用部分唯一索引而不是普通唯一索引：软删后再新增同一人必须放行（否则人会永久占位），
-- 而未软删的行仍然互斥（否则同一个人会出现两条档案，界面上看不出哪条生效）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_operator_user
    ON asset_operator (user_id)
 WHERE deleted_at IS NULL;

-- 列表页按状态筛选 + id 倒序（最近登记的在最前）
CREATE INDEX IF NOT EXISTS idx_asset_operator_status ON asset_operator (status, id DESC);

COMMENT ON COLUMN asset_operator.user_id IS '人员（sys_user.id）';
COMMENT ON COLUMN asset_operator.status IS '1 启用 / 0 停用（停用不删档案）';
COMMENT ON COLUMN asset_operator.deleted_at IS '软删时间；仅 null 的行可见，且参与「一人一份有效档案」的唯一约束';

-- ---------------------------------------------------------------------------
-- 2. 角色选择（一对多）
--
-- 明细表**不设软删列**：编辑时全量替换（先删后插），留软删只会制造永不清理的孤儿行。
-- 代价是每次编辑都会换新 id —— 因此明细行的 id **不能**对外当引用
-- （与 `asset_transfer_record_asset` 同口径）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS asset_operator_role (
    id          BIGSERIAL PRIMARY KEY,
    operator_id BIGINT      NOT NULL,
    role_id     BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    created_by  BIGINT,
    updated_by  BIGINT
);

-- 同一份档案不允许重复挂同一个角色（请求体去重之外的服务端兜底）
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_operator_role
    ON asset_operator_role (operator_id, role_id);
-- 反查「某角色被哪些运营人员登记」（角色被停用时排查影响面）
CREATE INDEX IF NOT EXISTS idx_asset_operator_role_role ON asset_operator_role (role_id);

-- ---------------------------------------------------------------------------
-- 3. 资产运营范围（项目 / 分区 / 资产，可混选）
--
-- `scope_type` + `scope_id` 两列而不是三个可空列（project_id / zone_id / asset_id）：
-- 三个可空列无法用一条唯一约束表达「同类型同标的只出现一次」，只能靠三列组合，
-- 而其中两列恒为 NULL —— PG 的唯一索引把 NULL 视为互不相等，去重会**静默失效**
-- （V56 引入 target_type / target_id 正是同一个理由）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS asset_operator_scope (
    id          BIGSERIAL PRIMARY KEY,
    operator_id BIGINT      NOT NULL,
    -- project → project.id / zone → project_zone.id / asset → asset.id
    scope_type  VARCHAR(20) NOT NULL,
    scope_id    BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    created_by  BIGINT,
    updated_by  BIGINT
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_operator_scope
    ON asset_operator_scope (operator_id, scope_type, scope_id);
-- 反查「某项目 / 分区 / 资产由哪些运营人员负责」——本期只展示，后续接入拦截时是主查询
CREATE INDEX IF NOT EXISTS idx_asset_operator_scope_target
    ON asset_operator_scope (scope_type, scope_id);

COMMENT ON COLUMN asset_operator_scope.scope_type IS '范围类型：project / zone / asset';
COMMENT ON COLUMN asset_operator_scope.scope_id IS '范围 id：project.id / project_zone.id / asset.id';

-- ---------------------------------------------------------------------------
-- 4. 目录改名：运营管理 → 资产运营人员管理
--
-- **只改 name，不改 code**：`ops` 是权限命名空间的一部分（`ops.tenant`）与
-- `frontend/admin-web/src/lib/pathToCode.ts` 镜像的键来源，改 code 会同时打断
-- `role_permission.menu_code` 与镜像一致性检查（见 V58 对改名的完整清单）。
-- 目录本身没有 path，改名不影响任何书签。
-- ---------------------------------------------------------------------------
UPDATE menu SET name = '资产运营人员管理' WHERE code = 'ops';

-- ---------------------------------------------------------------------------
-- 5. 新菜单：资产运营人员管理
--
-- 挂在自己所在目录（`ops`）下，sort 5 排在「租户管理」(10) **之前** ——
-- 目录名就是本页名，把同名的子页面排在它的次要页面之后会让人以为进错了地方。
-- icon 留空：与 V45 / V54 / V55 / V57 / V59 口径一致
-- （图标只给目录，菜单为空时侧栏回退前端 PATH_ICONS 的路由图标）。
-- ---------------------------------------------------------------------------
INSERT INTO menu (code, name, menu_type, path, icon, sort, parent_id)
SELECT 'ops.assetOperator', '资产运营人员管理', 'menu', '/asset-operators', NULL, 5, d.id
FROM menu d WHERE d.code = 'ops'
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 6. 权限回填：只给运维角色 operator，且只回填 view
--
-- **不按 V55 / V57 / V59「所有非超管角色」的口径回填**：本页能给人登记角色，
-- 是权限面的入口（人员 + 角色 + 负责范围三样个人信息同屏），与 `org.user`
-- 属同一类敏感菜单 —— V45 §5.1 已把 `org.user` 显式排除在通用回填之外，
-- 理由正是「不能让全部业务角色看到人员管理」。这里沿用同一个理由与同一批角色。
--
-- 写动作一律不回填（V45 §5.3：动作级回填是上线前置人工步骤）。
-- super_admin 走 isSuperAdmin() 旁路，回填反而是脏数据。
-- ---------------------------------------------------------------------------
INSERT INTO role_permission (role_id, menu_id, menu_code, action)
SELECT r.id, m.id, m.code, 'view'
FROM role r
CROSS JOIN menu m
WHERE r.code = 'operator'
  AND m.code = 'ops.assetOperator'
ON CONFLICT DO NOTHING;
