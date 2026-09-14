-- ============================================================================
-- V48 应用日志（端侧可观测性）
--   设计：docs/superpowers/specs/2026-09-12-app-log-observability-design.md
--     §4 数据模型、§11 迁移与权限接线
--
--   新增 app_log 表（端侧 JS/Promise/API 报错 + 后端未捕获异常）与「应用日志」菜单。
--
--   与 operation_log 的关系（设计 D2）：**分表，不合并**。
--     operation_log = 合规审计（带用户身份、长期保留，目前只写不读）
--     app_log       = 运维排查（无身份、按 retention-days 自动过期）
--     两者靠 trace_id 关联，一次查询可串起「端侧报错 + 后端异常」。
--
--   本迁移只碰三张表：新增 app_log、向 menu 插一行、向 role_permission 回填 view。
--   业务表无 DDL、无 DML。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) app_log
--
--    extra 用 TEXT 而非 jsonb：V4__jsonb_to_text.sql 明确把全仓 JSONB 改成 TEXT，
--    原因是 MyBatis-Plus 实体字段是 String，jsonb 会在 insert 时类型不匹配。
--
--    occurred_at 与 created_at 分离：端侧时钟不可信（手机时间可能是错的），
--    排序/查询/清理一律用服务端 created_at，occurred_at 仅作排查线索。
--    用 created_at 清理还能保证钟偏的客户端不会让日志立刻被删或永不删除。
--
--    app_type 不加 CHECK：加一个前端就要改 DDL 不划算，而 ingest 是唯一入口，
--    由 Java 侧 AppType 枚举白名单校验即可。level/source 是封闭词表，加 CHECK。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_log (
    id          BIGSERIAL PRIMARY KEY,
    trace_id    VARCHAR(64)  NOT NULL,
    level       VARCHAR(16)  NOT NULL,
    app_type    VARCHAR(32)  NOT NULL,
    source      VARCHAR(24)  NOT NULL,
    -- sha256(source|app_type|message|stack 前 200 字符) 前 32 位；由服务端计算，
    -- 客户端不传（否则可伪造指纹绕过告警冷却）。
    fingerprint VARCHAR(64)  NOT NULL,
    message     TEXT,
    extra       TEXT,
    ua          VARCHAR(512),
    url         VARCHAR(512),
    user_id     BIGINT,
    client_ip   VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_app_log_level CHECK (level IN ('ERROR', 'WARN', 'INFO')),
    CONSTRAINT ck_app_log_source CHECK (source IN ('js', 'promise', 'api', 'backend'))
);

-- 链路聚合（trace 抽屉的主查询）
CREATE INDEX IF NOT EXISTS idx_app_log_trace ON app_log (trace_id);
-- 列表默认排序 + 定时清理扫描（清理谓词是 created_at < ?）
CREATE INDEX IF NOT EXISTS idx_app_log_created ON app_log (created_at DESC);
-- 按级别筛选
CREATE INDEX IF NOT EXISTS idx_app_log_level_time ON app_log (level, created_at DESC);
-- 按端筛选
CREATE INDEX IF NOT EXISTS idx_app_log_app_time ON app_log (app_type, created_at DESC);
-- Top 错误聚合 + 告警冷却排查
CREATE INDEX IF NOT EXISTS idx_app_log_fingerprint ON app_log (fingerprint, created_at DESC);

COMMENT ON TABLE app_log IS
    '端侧与后端应用日志（运维排查用，非合规审计）：operation_log 才是审计留痕，两者靠 trace_id 关联';
COMMENT ON COLUMN app_log.trace_id IS
    '全链路 ID：端侧请求级生成并放 X-Trace-Id，后端 TraceIdFilter 原样复用，两端共用同一个值';
COMMENT ON COLUMN app_log.source IS '错误来源：js 运行时错误 / promise 未捕获 / api 请求失败 / backend 后端异常';
COMMENT ON COLUMN app_log.fingerprint IS '服务端计算的错误指纹，用于 Top 错误聚合与告警冷却（客户端不可伪造）';
COMMENT ON COLUMN app_log.occurred_at IS '端侧发生时刻，时钟不可信，仅作排查线索；排序/清理一律用 created_at';
COMMENT ON COLUMN app_log.user_id IS '客户端自报，仅排查用，不作鉴权依据';
COMMENT ON COLUMN app_log.client_ip IS 'ingest 由服务端取 RemoteAddr 写入，不信任请求体';

-- ---------------------------------------------------------------------------
-- 2) 菜单行
--
--    icon 留空：与 V45 口径一致（图标只给目录，菜单行留空，侧边栏回退到前端
--    路由注册表的 path 图标）。前端 PATH_ICONS['/system/app-logs'] 提供兜底图标。
--    父目录按 code 解析，不硬编码 parent_id —— 各环境 menu.id 不保证一致。
--    sort=40：排在 system.role(10) / system.menu(20) / system.dict(30) 之后。
-- ---------------------------------------------------------------------------
INSERT INTO menu (code, name, menu_type, path, icon, sort, parent_id)
SELECT 'system.appLog', '应用日志', 'menu', '/system/app-logs', NULL, 40, d.id
FROM menu d
WHERE d.code = 'system'
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3) view 回填
--
--    **只授给 operator，不向业务角色敞开。**
--    V45 §5.2 明确把 system.* 归为敏感菜单，只授予运维/管理员角色；app_log 含端侧
--    URL、UA、用户自报 ID 与异常堆栈，属运维数据，按同一口径处理。
--    （V47 对 asset.projectZone 用的是「所有非超管角色」口径，那是业务导航类菜单，
--      与本表的敏感性不同，不要照抄。）
--
--    super_admin 走 isSuperAdmin() 旁路，无需数据行。
--    写动作（尤其 delete）一律不回填 —— V45 §5.3 把「动作级回填」列为上线前置人工
--    步骤：误回填的宽权限是静默的，且会长期留在库里没人复核。
--    手动清理所需的 system.appLog:delete 由 super_admin 在角色权限页显式下发。
-- ---------------------------------------------------------------------------
INSERT INTO role_permission (role_id, menu_id, menu_code, action)
SELECT r.id, m.id, m.code, 'view'
FROM role r
CROSS JOIN menu m
WHERE r.code = 'operator'
  AND m.code = 'system.appLog'
ON CONFLICT DO NOTHING;
