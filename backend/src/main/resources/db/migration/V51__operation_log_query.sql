-- ============================================================================
-- V51 操作日志（审计留痕）查询能力
--   设计：docs/superpowers/specs/2026-09-13-operation-log-design.md
--     §4 数据模型、§12 迁移与权限接线
--
--   本迁移补齐 FR-COM-004（P0）缺的那一半：operation_log / login_log 此前全仓
--   **只写不读**（两个 Mapper 仅被 insert 调用，无接口、无权限点、无菜单、无页面）。
--
--   与 app_log 的关系（app-log 设计 D2）：**分表，不合并**。
--     operation_log = 合规审计（带用户身份、**不可删**、保留 ≥3 年，NFR-DSEC-016）
--     app_log       = 运维排查（无身份、按 retention-days 自动过期）
--   两者靠 trace_id 关联。本模块**纯只读**：不提供删除入口，也不加清理任务。
--
--   本迁移只碰三张表：operation_log 加两列、加两条索引、向 menu 插一行、
--   向 role_permission 回填 view。无业务 DDL、无业务 DML、无 DELETE。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) operation_log 加两列：成败可辨识
--
--    success 刻意**可空**，不用 NOT NULL DEFAULT true：
--    存量行（V51 之前）当时没有记录成败，NULL = 未知。若默认 true，
--    历史上那些失败的操作会被**谎报为成功** —— 这是审计里最不能出错的方向。
--
--    error 存失败原因（切面的 t.getMessage() 截断 500）；成功行为 NULL。
--    此前失败信息埋在 detail_json.error 里，想筛「哪些操作失败了」必须查 JSON，
--    而这是审计最核心的问题之一。
--
--    两处 ALTER 都加 IF NOT EXISTS：与 V48 的索引口径一致，重跑不报错。
-- ---------------------------------------------------------------------------
ALTER TABLE operation_log ADD COLUMN IF NOT EXISTS success BOOLEAN;
ALTER TABLE operation_log ADD COLUMN IF NOT EXISTS error VARCHAR(500);

COMMENT ON COLUMN operation_log.success IS
    '本次操作是否成功；NULL = V51 之前的存量行（当时未记录成败），不代表成功';
COMMENT ON COLUMN operation_log.error IS
    '失败原因（异常 message 截断 500）；成功行为 NULL';

-- ---------------------------------------------------------------------------
-- 2) 索引：审计页最常用的两个筛选维度 + 时间排序
--
--    只给「等值筛选」建复合索引；username / ip / keyword 的 ILIKE 刻意不建
--    （审计表体量与业务写操作同阶，远比 app_log 小，LIMIT + 分页已足够；
--      将来量大了再上 pg_trgm，属独立优化）。
--
--    已有的 idx_operation_log_user(user_id) / idx_operation_log_created(created_at) /
--    idx_login_log_created(created_at) 保持不动：ORDER BY created_at DESC 可由
--    反向扫描复用，不需要再建一份 DESC 索引。
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_operation_log_module_created
    ON operation_log (module, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_login_log_result_created
    ON login_log (result, created_at DESC);

-- ---------------------------------------------------------------------------
-- 3) 菜单行
--
--    icon 留空：与 V45 / V48 口径一致（图标只给目录，菜单行留空，侧边栏回退到
--    前端路由注册表的 path 图标；前端 PATH_ICONS['/system/operation-logs'] 兜底）。
--    父目录按 code 解析，不硬编码 parent_id —— 各环境 menu.id 不保证一致。
--    sort=50：排在 system.role(10) / system.menu(20) / system.dict(30) / system.appLog(40) 之后。
-- ---------------------------------------------------------------------------
INSERT INTO menu (code, name, menu_type, path, icon, sort, parent_id)
SELECT 'system.operationLog', '操作日志', 'menu', '/system/operation-logs', NULL, 50, d.id
FROM menu d
WHERE d.code = 'system'
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4) view 回填
--
--    **只授给 operator，不向业务角色敞开。**
--    V45 §5.2 明确把 system.* 归为敏感菜单，只授予运维/管理员角色；operation_log
--    含用户名、IP、traceId 与接口入参（detail_json），属审计数据，按同一口径处理。
--    （V47 对 asset.projectZone 用的是「所有非超管角色」口径，那是业务导航类菜单，
--      与本表的敏感性不同，不要照抄。）
--
--    super_admin 走 isSuperAdmin() 旁路，无需数据行。
--
--    **不回填任何写动作，尤其 delete**：本模块纯只读 —— 用户故事地图明确
--    「操作日志不可删」，NFR-DSEC-016 要求保留 ≥3 年。V45 §5.3 也把「动作级回填」
--    列为上线前置人工步骤：误回填的宽权限是静默的，且会长期留在库里没人复核。
-- ---------------------------------------------------------------------------
INSERT INTO role_permission (role_id, menu_id, menu_code, action)
SELECT r.id, m.id, m.code, 'view'
FROM role r
CROSS JOIN menu m
WHERE r.code = 'operator'
  AND m.code = 'system.operationLog'
ON CONFLICT DO NOTHING;
