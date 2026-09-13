# 操作日志（审计留痕）—— 覆盖补齐 + 管理端查询 设计

**日期**：2026-09-13
**状态**：已确认，待实现
**范围应用**：`backend`（`operation_log` 加列、审计查询接口、切面改造、审计覆盖守卫）+ `frontend/admin-web`（新增「操作日志」页）

**关联设计**：

- `docs/superpowers/specs/2026-09-12-app-log-observability-design.md` —— **前置**。该设计把 `operation_log` 定性为「合规审计（带用户身份、长期保留）」，与 `app_log`（运维排查、按 retention 过期）**分表不合并**，靠 `trace_id` 关联；其 §8.1 明确**不在日志模块里暴露 `operation_log` 的读取路径**，把「是否开放审计读取、用哪个权限点」列为**独立决策、另案设计**。**本设计就是那个另案**，并据此新建权限点、不复用 `system.appLog:view`。
- `docs/superpowers/specs/2026-09-12-menu-and-role-permission-design.md` —— 权限码 `menuCode:action`、动作词表 `PermissionAction`、`@RequiresPerm` 与 `PATH_TO_CODE` 镜像口径、启动期 `PermissionRegistry.assertMenuCodesExist` 断言，本设计全部沿用。
- `docs/superpowers/specs/2026-09-12-list-return-state-design.md` —— 查询页的筛选/分页**必须进 URL**（D12），`useListQuery` / `useUrlParam` / `useUrlParams` 的写入语义与陷阱，本设计沿用。
- `docs/需求规格说明书.md` §4.1.4 **FR-COM-004**（P0）：记录用户登录 + 关键业务操作，**且**支持按时间、用户、模块查询（系统日志、登录日志）——本设计的验收基准。
- `docs/requirements/用户故事地图.md`：「作为安全审计，关键操作可追溯（**操作日志不可删**）」。
- `docs/需求规格说明书.md` **NFR-DSEC-016**：操作日志、安全事件、导出审计保留 **≥3 年**。
- `docs/adr/0004-postgresql-primary-store.md`、`0006-flyway-schema-migration.md`、`0017-mybatis-plus-flyway.md` —— 存储与迁移机制，本设计不引入新存储。

---

## 1. 背景与目标

### 1.1 现状盘点（实测）

| 能力 | 现状 | 位置 |
|------|------|------|
| 写审计记录 | **已有**：`@Audited` + `OperationLogAspect`，记录 user / module / action / 入参 / IP / traceId | `platform/security/OperationLogAspect.java` |
| 审计覆盖率 | **207 个写接口中 198 个已加 `@Audited`**（缺 9 个，见 §7） | 全仓 `controller/` |
| 登录日志 | **已有（只写）**：成功 / 失败 / 锁定 / 停用四类，`AuthService.recordLoginLog` | `platform/auth/AuthService.java` |
| 审计读取入口 | **完全没有**：`OperationLogMapper` / `LoginLogMapper` 全仓只被用于 `insert`，无接口、无权限点、无菜单、无页面 | —— |
| 失败可辨识性 | **无**：失败只写在 `detail_json.error` 里，想筛「哪些操作失败了」必须查 JSON | —— |
| `operation_log.ref_id` | **死列**：切面从未写入 | —— |
| 审计写入失败 | **完全静默**：`catch (Exception ignored)`，与 app_log「绝不静默」的口径相反 | —— |
| 覆盖率守卫 | **无**：漏接 `@Audited` 不会被任何门禁发现（本次 9 个缺口就是这么攒出来的） | —— |

**结论**：记录能力已有且覆盖率高，缺的是**可查询**、**可判成败**、**漏接会被拦住**这三件事。本设计只补这三件，**不重做**已有的切面、表结构与脱敏。

### 1.2 目标

1. 管理员能在 **admin-web** 里按**时间 / 用户 / 模块**查询操作日志与登录日志（FR-COM-004 的 P0 查询能力）。
2. 操作日志能区分**成功 / 失败**，并能按结果筛选。
3. 审计覆盖成为**会失败的门禁**：新增写接口漏接 `@Audited` 时 CI 变红，而不是靠人工巡检。
4. 审计数据只对**显式授权**的角色开放（新建权限点，不复用 `system.appLog:view`）。
5. 审计模块**纯只读**：无删除、无清理任务（对齐「操作日志不可删」与 ≥3 年保留）。

### 1.3 非目标（明确不做）

- **不做 `operation_log` 的清理 / 归档 / 分区**。保留期 ≥3 年，本模块不提供任何删除入口，也不加定时任务。
- **不做 `ref_id` 提取**（见 D4）。
- **不做导出**（Excel / CSV）。需要时用查询接口 + 浏览器复制。
- **不做统计图表 / 顶部统计条**（app_log 页的统计条是因为它要在「整体有没有在炸」与「找那一条」之间切换；审计没有这个需求，列表的 `共 N 条` 足够）。
- **不做按 `action` 的精确筛选参数**。FR-COM-004 只要求时间/用户/模块；`action` 由 `keyword` 覆盖（见 §5.2）。
- **不修 `login_log.fail_reason` 的语义重载**（成功行存的是登录方式 `wechat` / `wechat_bind`，失败行才是失败原因）。这是既有数据模型问题，本设计只在 UI 上如实标注（§6.3），改表另案。
- **不改 `AppLogPage` 的链路抽屉**去反向链接审计页。它与在途的 app-log 改动同文件，避免冲突；登记为 follow-up（§14）。
- **不做审计读取的字段级脱敏矩阵**。`detail_json` 已由切面脱敏（`password` / `token` / `idNo` / `phone`），本模块按「有 `view` 即可见完整入参」处理，页面上明确提示含接口入参。

---

## 2. 已确认决策

| # | 项 | 决策 | 理由 |
|---|----|------|------|
| D1 | 页面形态 | 一个菜单「操作日志」`/system/operation-logs`，页内**两个 Tab**（操作日志 / 登录日志），**共用一个权限点** `system.operationLog:view` | 与 `/system/app-logs` 同模式（专用页 + 专用权限点 + 菜单），改动面最小；FR-COM-004 把两者并列为「系统日志、登录日志」，同页两 Tab 正好对应 |
| D2 | 权限点归属 | **新建** `system.operationLog:view`，**不复用** `system.appLog:view` | app-log 设计 §8.1：给审计数据开读取通道是一次独立决策，挂在运维日志权限下是隐蔽提权 |
| D3 | 记录覆盖缺口处置 | **只补 2 个真配置写入**（`InvoiceTaxRateController.save`、`ApportionConfigController.save`）；其余 **7 个用 `@AuditedExempt("理由")` 显式豁免**并写明理由 | 7 个缺口里 3 个是无登录用户的第三方回调、2 个是不落库的只读预览、2 个是低价值高频操作（AI 会话创建、通知已读）。硬塞 `@Audited` 会产出 `username=null` 的幽灵行或噪声行，反而降低审计可信度 |
| D4 | `ref_id` | **维持空列**，登记为已知缺口 | 可靠提取需要 SpEL 或逐接口显式声明（见 §7.3），收益低于成本；`detail_json` 已含入参，能还原操作对象 |
| D5 | 成败可辨识 | **加列**：`success BOOLEAN`（可空）+ `error VARCHAR(500)`；切面按成败分别写，**存量行 `success IS NULL` 表示「当时未记录」** | 审计最核心的问题之一是「哪些操作失败了」，埋在 JSON 里等于查不了。用可空而非 `NOT NULL DEFAULT true`：默认 true 会把历史失败行**谎报为成功** |
| D6 | 审计写入失败 | `OperationLogAspect` 的 `catch (Exception ignored)` → **`log.warn` 带 traceId** | 现状是审计写入失败完全不可见，与 app_log「绝不静默」的口径相反。审计是合规证据，静默丢失必须留下痕迹 |
| D7 | 覆盖守卫 | 新增 `@AuditedExempt` 注解 + `scripts/check-audit-coverage.mjs`，接 `pnpm check:audit` 与 CI | 一次性扫一遍挡不住下次漏；本仓已有 `check-perm-invariants` / `check-backend-contracts` 的静态守卫范式 |
| D8 | 分页上限 | `pageSize` 服务端封顶 **200**，与 `AppLogController` 一致 | 日志类列表页码可选 20/50/100/200；`common/web/PageRequest` 的 `@Max(100)` 是给业务列表的口径，日志页沿用 `AppLogController` 的裸参数 + 服务端封顶 |
| D9 | 时间字段语义 | 筛选与排序**一律用 `operation_log.created_at` / `login_log.created_at`**（服务端时间） | 两表都只有服务端时间，不存在 app_log 那种「端侧时钟不可信」问题；但仍要明确只有这一个基准 |
| D10 | 时间参数格式 | ISO **本地时间、不带时区后缀**（`YYYY-MM-DDTHH:mm:ss`），后端 `LocalDateTime` + `@DateTimeFormat(iso = DATE_TIME)` | 与 `AppLogController`、`/ops-calendar` 全仓现有一致。送 `...Z` 会解析失败 → 400（表现为「选完时间就报错」） |
| D11 | 页内状态 | 两 Tab 各自的分页/筛选**全部进 URL**，用 `useListQuery({ prefix })` 前缀键；当前 Tab 用 `useUrlParam('tab')` | list-return-state 设计 D12：抽屉返回、刷新、分享链接都要还原 |
| D12 | 模块下拉 | 提供 `GET /operation-logs/modules` 返回去重模块清单 | 模块名（`asset` / `system` / `billing`…）在前端没有权威枚举，手输必然查不出东西 |
| D13 | 权限回填 | 迁移只为 `operator` 回填 `view`；**不回填任何写动作、不创建 `delete` 权限** | V48 同口径：`system.*` 属敏感菜单（V45 §5.2），审计数据含接口入参与用户名；且本模块无写操作可授权 |

---

## 3. 架构

```
┌─ 写侧（已有，本设计只加 2 个注解 + 改切面落库）───────────────────────────┐
│                                                                          │
│  Controller 方法 @Audited(module, action)                                │
│        │                                                                 │
│        ├─ OperationLogAspect.around()                                    │
│        │    成功 → success=true,  error=null                             │
│        │    异常 → success=false, error=<message 截断 500>               │
│        │    （detail_json 只放 args；不再把 error 塞进 JSON）            │
│        │    落库失败 → log.warn（不再静默）                              │
│        │         │                                                       │
│        │         ▼                                                       │
│        │    operation_log（PostgreSQL）                                  │
│        │                                                                 │
│  @AuditedExempt("理由") ← 7 个显式豁免（回调 / 只读预览 / 低价值高频）   │
│                                                                          │
│  AuthService.recordLoginLog() ──► login_log（已有，不变）                │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
┌─ 读侧（本设计新增）───────────────▼──────────────────────────────────────┐
│                                                                          │
│  AuditLogController  @ /api/v1/system                                    │
│    @RequiresPerm("system.operationLog:view")   ← 类级，两个 Tab 共用     │
│      GET /operation-logs            分页列表（时间/用户/模块/结果/关键字/traceId）│
│      GET /operation-logs/{id}       单条详情（detail 解析为 JSON 对象）   │
│      GET /operation-logs/modules    去重模块清单（筛选下拉数据源）        │
│      GET /login-logs                分页列表（时间/用户名/结果/IP）       │
│                                                                          │
│  admin-web /system/operation-logs?tab=op|login                            │
│    Tab「操作日志」：筛选 + 表格 + 详情抽屉 + traceId → /system/app-logs   │
│    Tab「登录日志」：筛选 + 表格                                          │
└──────────────────────────────────────────────────────────────────────────┘

CI：node scripts/check-audit-coverage.mjs
     每个写接口必须带 @Audited( 或 @AuditedExempt(  → 否则 exit 1
```

### 3.1 三条硬边界

1. **审计表与运维表分家**（app-log 设计 D2）。`operation_log` 带用户身份、不可删、保留 ≥3 年；`app_log` 无身份、按期过期。本设计**不动 `app_log`**，也不动它的清理策略。
2. **审计写入失败不得影响业务，但不得静默**。切面内部 `catch (Throwable) → log.warn`，绝不向上抛（审计故障不能变成业务 500）；同时必须留下 `warn` 痕迹（D6）。
3. **只读**。本模块没有写接口、没有删除入口、没有清理任务、迁移不回填写动作（D13）。

---

## 4. 数据模型：`V51__operation_log_query.sql`

### 4.1 加两列

```sql
-- success 可空：存量行（V51 之前）当时没有记录成败，NULL = 未知。
-- 刻意不用 NOT NULL DEFAULT true —— 那会把历史失败行谎报为成功（D5）。
ALTER TABLE operation_log ADD COLUMN IF NOT EXISTS success BOOLEAN;
-- 失败原因：切面写 t.getMessage()，截断到 500。成功行为 NULL。
ALTER TABLE operation_log ADD COLUMN IF NOT EXISTS error VARCHAR(500);

COMMENT ON COLUMN operation_log.success IS
    '本次操作是否成功；NULL = V51 之前的存量行（当时未记录成败），不代表成功';
COMMENT ON COLUMN operation_log.error IS
    '失败原因（t.getMessage() 截断 500）；成功行为 NULL';
```

### 4.2 加索引

```sql
-- 模块筛选 + 时间排序（审计页最常用的筛选维度）
CREATE INDEX IF NOT EXISTS idx_operation_log_module_created
    ON operation_log (module, created_at DESC);
-- 登录结果筛选 + 时间排序（登录日志页最常用的筛选维度）
CREATE INDEX IF NOT EXISTS idx_login_log_result_created
    ON login_log (result, created_at DESC);
```

已有的 `idx_operation_log_user (user_id)`、`idx_operation_log_created (created_at)`、`idx_login_log_created (created_at)` **保持不动**（`ORDER BY created_at DESC` 可由反向扫描复用，无需新建 DESC 索引）。

### 4.3 不加的索引（刻意）

| 查询 | 为什么不加索引 |
|------|----------------|
| `username` / `ip` / `keyword` 的 ILIKE | 审计表数据量远小于 `app_log`（每接口一行且仅在写路径产生），`LIMIT` + 分页已足够。将来量大再加 `pg_trgm`，属独立优化 |
| `success` 单列 | 选择性低（绝大多数为 true），单独建索引反而拖慢写入 |

### 4.4 字段语义（读侧口径）

| 字段 | 取值 | 页面口径 |
|------|------|----------|
| `success` | `true` / `false` / `null` | `true` → 绿「成功」；`false` → 红「失败」；`null` → 灰「—」（未知），`Tooltip` 说明「该记录产生于成败字段上线之前」 |
| `error` | 文本 / NULL | 详情抽屉独立一栏；`success=true` 时为 NULL，不显示 |
| `ref_id` | **恒为 NULL** | 不展示（登记为已知缺口，见 D4） |
| `detail_json` | JSON 文本 | 详情抽屉以 `<pre>` 格式化 + 复制按钮；明确提示「含接口入参，已脱敏 password/token/idNo/phone」 |
| `trace_id` | 32 位 hex | 表格末列，可复制；提供「到应用日志查看」跳 `/system/app-logs?traceId=…` |

---

## 5. 后端查询接口

### 5.1 组件与权限

| 组件 | 位置 | 权限 |
|------|------|------|
| `AuditLogController` | `modules/system/controller/` | 类级 `@RequiresPerm("system.operationLog:view")`（`operation-logs` 与 `login-logs` 共用） |
| `AuditLogService` | `modules/system/service/` | —— |
| `OperationLogView` / `LoginLogView` | `modules/system/dto/` | —— |
| `OperationLog` / `LoginLog` 实体、两个 Mapper | **既有**，仅 `OperationLog` 加两个字段 | —— |

- 用 MyBatis-Plus `LambdaQueryWrapper`，**不写 XML**。
- 分页沿用 `PageResult.of(list, total, page, pageSize)`；`pageSize` 服务端封顶 `MAX_PAGE_SIZE = 200`（D8）。
- **类级注解同时覆盖两个 Tab**：`PermissionRegistry.collect` 会扫类级与方法级注解，本控制器只有类级一处声明，无需方法级覆盖。

### 5.2 `GET /api/v1/system/operation-logs`

| 参数 | 类型 | 语义 |
|------|------|------|
| `from` / `to` | `LocalDateTime`（ISO DATE_TIME） | 作用于 `created_at`，闭区间；缺省不设界 |
| `username` | `String` | `ILIKE %v%`（FR-COM-004 的「按用户查询」） |
| `module` | `String` | 等值匹配（下拉取值来自 `/modules`） |
| `success` | `Boolean` | `true` / `false`；缺省不过滤（含 NULL 存量行） |
| `keyword` | `String` | `(username ILIKE %v%) OR (action ILIKE %v%) OR (detail_json ILIKE %v%)` |
| `traceId` | `String` | 等值匹配（从 app_log 页跳过来的入口） |
| `page` / `pageSize` | `long` | `page` 从 1 起，`pageSize` 封顶 200 |

- 排序：`created_at DESC, id DESC`（同秒订单靠 `id` 兜底，否则分页会漏行/重行）。
- **不提供 `action` 精确参数**（FR-COM-004 未要求；`keyword` 覆盖）。见 §1.3。
- **不提供 `userId` 参数**：UI 用 `username` ILIKE 已覆盖「按用户查」；`user_id` 精确筛选需要 UI 提供用户选择器（本仓无此通用组件），登记为将来能力。

### 5.3 `GET /api/v1/system/operation-logs/{id}`

单条详情。`detail_json` 由服务端解析为 JSON 对象放在 `OperationLogView.detail`（解析失败时退回原始字符串，**不抛错**——一条脏数据不该让整页 500）。

### 5.4 `GET /api/v1/system/operation-logs/modules`

返回去重、升序的模块清单（`List<String>`），即
`SELECT DISTINCT module FROM operation_log WHERE module IS NOT NULL ORDER BY module`。

取值是 `@Audited(module = …)` 里实际写过的字符串（如 `asset` / `system` / `billing` / `utility` /
`contract_template` / `ops-calendar`），**具体清单由数据库现状决定，不在设计里写死** ——
写死必然与注解漂移，而筛选下拉的价值恰恰是「只给确实查得到的值」。因此这里**不落静态枚举**，
也不加 `menu` / 字典种子。

> 路径 `/{id}` 与字面量 `/modules` 并存：Spring 的路径匹配**字面量优先于模板**，同 `AppLogController` 的 `/stats` 与 `/{id}` 并存的既有先例。`GET /operation-logs/abc` 会因 `Long` 转换失败得 400 —— 可接受（手改 URL 的非法输入）。

### 5.5 `GET /api/v1/system/login-logs`

| 参数 | 类型 | 语义 |
|------|------|------|
| `from` / `to` | `LocalDateTime` | 作用于 `created_at` |
| `username` | `String` | `ILIKE %v%` |
| `result` | `String` | 等值，**取值必须是小写 `success` / `failed`**（见 §6.3） |
| `ip` | `String` | 等值匹配（安全排查：某 IP 试了多少次） |
| `page` / `pageSize` | `long` | 同上 |

排序同上 `created_at DESC, id DESC`。

---

## 6. 前端查询页：`frontend/admin-web/src/pages/OperationLogPage.tsx`

### 6.1 结构

```
标题        操作日志            [刷新]
Tab         ┌ 操作日志 ┐┌ 登录日志 ┐        ← tab 进 URL（tab=op 缺省 / tab=login）

[操作日志 Tab]
筛选行      时间范围(预设+自定义) · 用户(回车) · 模块(Select) · 结果(Select) · 关键字(回车) · TraceId(回车)
表格        时间 | 用户 | 模块 | 动作 | 结果(Tag) | IP | 详情(省略) | TraceId(可点)
行点击      → 详情抽屉：全字段 + detail 格式化 JSON + error + traceId 复制/跳应用日志
分页        pageSize 20/50/100/200

[登录日志 Tab]
筛选行      时间范围(预设+自定义) · 用户名(回车) · 结果(Select) · IP(回车)
表格        时间 | 用户名 | 用户ID | IP | 结果(Tag) | 原因/方式
分页        同上
```

**只读**：没有新增 / 编辑 / 删除 / 清理按钮。权限判定用 `usePermByPath()`（页面在 `PATH_TO_CODE` 镜像里），与 `SystemDictionaryPage` / `AppLogPage` 同模式。

### 6.2 URL 键（D11）

| Tab | 键 |
|-----|-----|
| 公共 | `tab`（`op` 缺省 → 不写 URL；`login` 才写） |
| 操作日志 | `opPage`、`opPageSize`、`opKeyword`、`opUsername`、`opModule`、`opSuccess`、`opTraceId` + 时间范围 `opRange` / `opFrom` / `opTo` |
| 登录日志 | `loginPage`、`loginPageSize`、`loginUsername`、`loginResult`、`loginIp` + 时间范围 `loginRange` / `loginFrom` / `loginTo` |

- 两 Tab 各用 `useListQuery({ prefix: 'op' })` / `{ prefix: 'login' }`，靠 `buildKey` 的驼峰拼接得到上表键名（已实测：带前缀得 `assetPage` / `projectKeyword`，与不带前缀的精确 `page` / `keyword` 并存）。
- 时间范围用 `useUrlParam` + `STAMP_CODEC`（`YYYY-MM-DDTHH:mm:ss`），预设 `1h/24h/7d/30d/custom/all`，默认 `24h`。
- **两 Tab 懒加载**：首次切到某 Tab 才发请求，切回来不重拉（各自 key 不变时依赖不触发）。
- **一次交互只写一次 URL**：改时间范围要同时动 `range`/`from`/`to`/`page` 四个键，必须走 `useUrlParams()` 原子批量写入（`listQuery.ts` 顶部已把「连调两个 setter 会互相覆盖」写死；`OpsCalendarPage` 踩过这个坑）。
- **白名单化枚举筛选**（`opSuccess` / `loginResult`）：手改 URL 传入白名单外的值时当作「没选」，不发给后端 —— 与 `AppLogPage.pickEnum` 同口径，否则会出现「筛选框显示全部却查不出任何东西」。

### 6.3 `login_log.result` 与 `fail_reason` 的如实标注

- `result` 的真实取值是**小写** `success` / `failed`（`AuthService.recordLoginLog` 六处调用，实测：`"failed"` ×3、`"success"` ×4）。UI 的 Select 选项值必须是 `success` / `failed`，**不能**用 `SUCCESS` / `FAIL` —— 否则筛选恒为空且不报错。
- `fail_reason` **语义重载**：成功行存的是**登录方式**（`wechat` / `wechat_bind` / `wechat_bind_worker`），失败行才是失败原因（「用户名或密码错误」等）。列名用**「原因 / 方式」**，并加 `Tooltip`：「成功后此列记录登录方式（wechat / wechat_bind），失败后记录失败原因」。**不改表**（§1.3）。

### 6.4 与 app_log 页的互链

详情抽屉的 `traceId` 提供「到应用日志查看」→ `/system/app-logs?traceId=<值>`（app_log 页的 `filterKeys` 已含 `traceId`，可直接还原视图）。这补上了 app-log 设计 §8.1 那句「相同 traceId 的业务审计记录可在 operation_log 中检索」的**正向**跳转；反向链接（app_log → 审计页）不做（§1.3）。

### 6.5 前端 5 处必改（漏改会让 `pnpm check:perm` 直接失败）

| 文件 | 改动 |
|------|------|
| `src/App.tsx` | 加 `<Route path="system/operation-logs" element={<OperationLogPage />} />` |
| `src/lib/routeRegistry.ts` | `STANDALONE_ROUTES` 加 `'/system/operation-logs'` |
| `src/lib/pathToCode.ts` | 加 `'/system/operation-logs': 'system.operationLog'`（必须与 DB 菜单逐字一致） |
| `src/pages/modules.tsx` | 静态 `MENU` 的「系统管理」组加 `{ path: '/system/operation-logs', title: '操作日志' }` |
| `src/lib/menuIcons.tsx` | `PATH_ICONS['/system/operation-logs']` |

> 这 5 处正是 `check-perm-invariants.mjs` 的四道检查所覆盖的：镜像完整性（第 3 道）、`STANDALONE_ROUTES` ↔ `App.tsx` 双写一致性（第 4 道）、前端 perm 声明 ⊆ 后端 `@RequiresPerm`（第 2 道）。

---

## 7. 记录覆盖补齐

### 7.1 补 2 个（D3）

| 接口 | 注解 |
|------|------|
| `InvoiceTaxRateController.save`（`POST /api/v1/invoice-tax-rates`，`INSERT invoice_tax_rate`） | `@Audited(module = "invoice", action = "tax_rate_save")` |
| `ApportionConfigController.save`（`POST /api/v1/apportion-configs`，`INSERT apportion_config`） | `@Audited(module = "utility", action = "apportion_config_save")` |

模块/动作命名沿用同文件既有口径（`InvoiceController` 用 `module="invoice", action="issue"/"red_flush"/"create_title"`；`ApportionConfigController.compute` 用 `module="utility", action="apportion"`），动作一律 snake_case。

### 7.2 豁免 7 个（D3）

| # | 接口 | 豁免理由（写进注解，可评审） |
|---|------|------------------------------|
| 1 | `CallbackController.wechatPay` | 微信支付回调：无登录用户（`SecurityUtils.current()` 为 null）→ 会产出 `username=null` 的幽灵行；且渠道会重放/重试，同一事件会重复落库 |
| 2 | `CallbackController.esign` | 电子签回调：同上 |
| 3 | `CallbackController.invoice` | 数电发票回调：同上 |
| 4 | `ContractTemplateController.previewTemplate` | 只读渲染预览，不落库；用 POST 仅因为要传 `slots`（`GET` 无法带 body） |
| 5 | `ContractTemplateController.draftPreview` | 草稿预览，方法注释明写「不落库」 |
| 6 | `IntelligenceController.createSession` | 创建 AI 会话容器（瞬时），非关键业务操作；同一模块的 `chat` / `generate_report` 已审计 |
| 7 | `NotificationController.markRead` | 用户标记自己的站内信已读：高频、低价值，落库会淹没真正的关键操作 |

> 被豁免的是**记录**，不是**权限**：这 7 个接口的 `@RequiresPerm` 与鉴权行为一律不变。

### 7.3 `@AuditedExempt`

```java
package com.ams.platform.security;

/**
 * 审计豁免（FR-COM-004 的显式出口）。
 *
 * <p>{@code scripts/check-audit-coverage.mjs} 要求每个写接口必须带 {@link Audited} 或本注解，
 * 因此豁免理由**必须写在端点旁**、随代码评审，而不是藏在某个脚本的白名单文件里。
 *
 * <p>只影响「记不记审计」，不影响鉴权（{@code @RequiresPerm}）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditedExempt {

    /** 豁免理由：必填；静态守卫要求非空且长度 ≥ 8。 */
    String value();
}
```

**为什么不提 `ref_id`**（D4）：现有 `@Audited(module, action)` 拿不到「被操作对象的 ID」——`updateAsset(Long assetId, Asset body)` 的 ID 在第 1 个参数，`createAsset(Asset)` 的结果里才有 ID，`Map<String,Object>` 入参更是无从推断。可靠做法只有两条：加 SpEL 表达式属性（每个接口都要维护一段字符串，写错不报错）或从返回值反射取 `id`（对 `Map` / `List` / 非标准返回类型要逐个兜底）。两条的成本都高于收益，而 `detail_json` 已含全部入参，人工排查足以还原操作对象。因此维持空列并在此登记。

---

## 8. 切面改造：`OperationLogAspect`

### 8.1 改动点

```java
private static final Logger log = LoggerFactory.getLogger(OperationLogAspect.class); // ← 新增

@Around("@annotation(audited)")
public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
    try {
        Object result = pjp.proceed();
        record(audited, pjp, true, null);            // ← 成败由调用点显式传入
        return result;
    } catch (Throwable t) {
        record(audited, pjp, false, t.getMessage());
        throw t;                                     // ← 异常必须继续上抛：审计不改变业务行为
    }
}

/**
 * @param success 显式传入，**不得**用 {@code error == null} 反推：异常的
 *                {@code getMessage()} 本身就可能为 null（如 {@code NullPointerException}），
 *                那样会把失败的操作记成成功 —— 这是审计里最不能出错的方向
 */
private void record(Audited audited, ProceedingJoinPoint pjp, boolean success, String error) {
    try {
        LoginUser user = SecurityUtils.current();
        // 局部变量不复用 `log` 这个名字：本类现在有 SLF4J 的 `log`，同名会把 warn 写串
        OperationLog entry = new OperationLog();
        entry.setUserId(user == null ? null : user.getUserId());
        entry.setUsername(user == null ? null : user.getUsername());
        entry.setModule(audited.module());
        entry.setAction(audited.action());
        entry.setTraceId(TraceIdUtil.get());
        entry.setCreatedAt(LocalDateTime.now());
        entry.setSuccess(success);                       // ← 新增
        entry.setError(truncate(error, 500));            // ← 新增；truncate 对 null 返回 null
        HttpServletRequest request = currentRequest();
        if (request != null) {
            entry.setIp(request.getRemoteAddr());
        }
        try {
            // detail_json 只放 args：error 已独立成列，两处都写会产生两套口径
            entry.setDetailJson(objectMapper.writeValueAsString(
                    Map.of("args", sanitize(pjp.getArgs()))));
        } catch (Exception ignored) {
            entry.setDetailJson("{}");
        }
        operationLogMapper.insert(entry);
    } catch (Throwable t) {
        // 审计写入失败绝不能影响业务，但也绝不能静默（D6）：
        // 审计是合规证据，丢失必须留下可排查的痕迹
        log.warn("操作审计落库失败：module={} action={} traceId={}",
                audited.module(), audited.action(), TraceIdUtil.get(), t);
    }
}
```

配套改动：
- 新增 SLF4J logger 字段；**实体局部变量从 `log` 改名**（否则与 logger 同名，极易把 warn 写到歧义处）。
- `around()` 的 `catch` 块不再把 `t.getMessage()` 当「成败判据」，改传显式 `boolean`（见上）。
- `around()` 的 `finally` 块里**算了却从未使用**的 `duration` 局部变量删掉（无行为变化）。**不新增 `duration_ms` 列**（§1.3）。
- `detail_json` 形状从 `{"args":[...],"error":"..."}` 变为 `{"args":[...]}`。**当前全仓无人读取该列**（写侧唯一消费者是本切面），因此这是安全的；失败信息改由 `error` 列承载。

### 8.2 两条硬约束（必须写进 JavaDoc）

1. **不用 `REQUIRES_NEW`**。`@Audited` 落在 Controller 上，切面在 MVC 层执行、此处无事务上下文，普通 `insert` 即自动提交。加 `REQUIRES_NEW` 只会多占一个连接。
2. **禁止自激**：`record()` 内部 `catch (Throwable)` 且只调 `log.warn`，不得调用任何会再次抛未捕获异常的组件（与 `AppLogRecorder` 同口径）。

### 8.3 事务边界的既有行为（保持，仅记录）

切面在 Controller 层记录。若某服务方法自身回滚（`@Transactional` 抛异常），切面仍会记一行 `success=false` —— 这正是期望语义（「尝试过的操作」也要留痕）。**不改**为「只在提交成功后记录」：那会漏掉全部失败尝试，而失败尝试恰恰是审计最想看到的。

---

## 9. 覆盖守卫：`scripts/check-audit-coverage.mjs`

### 9.1 检查项

| # | 检查 | 违规处置 |
|---|------|----------|
| 1 | 扫描 `backend/src/main/java/com/ams/**/controller/*.java` 的每个 `@(Post\|Put\|Delete\|Patch)Mapping`，向前扫到方法签名，必须出现 `@Audited(` 或 `@AuditedExempt(` | **失败** |
| 2 | 同一个方法同时出现 `@Audited(` 与 `@AuditedExempt(` | **失败**（自相矛盾的声明） |
| 3 | `@AuditedExempt("…")` 的理由非空且长度 ≥ 8 | **失败**（「TODO」「暂不」不算理由） |
| 4 | 每个 `@AuditedExempt(` 都必须落在某个写接口上（未消费的注解 = 接口已删/改名后留下的幽灵豁免） | **失败** |
| 5 | 数量下限自检：解析出的写接口总数 ≥ 190、`@Audited` ≥ 190、`@AuditedExempt` ≥ 1 | **失败**（正则失配会让所有断言恒真 —— 本仓静态守卫的通用要求）。**豁免数量刻意不写死**：它是会随业务变化的真实数量，钉成常量只会让「新增一个只读预览接口」变成必须改脚本的伪失败；豁免清单的**完整性**由第 4 项（注解必须落在写接口上）而不是计数来保证 |

输出：`写接口 N 个 / 已审计 M 个 / 已豁免 K 个`，并**逐条列出豁免清单及理由**，便于评审时一眼扫过。

### 9.2 接线

- `frontend/package.json`：`"check:audit": "node ../scripts/check-audit-coverage.mjs"`。
- `.github/workflows/ci.yml` 的 `frontend-docs` job 加一步（`working-directory: .`，与 `check-perm-invariants.mjs` / `check-backend-contracts.mjs` 并列）。

> `check-audit-coverage.mjs` **刻意独立成脚本**，不塞进 `check-backend-contracts.mjs`：后者是「迁移 ↔ 实体 ↔ DTO」的契约守卫，本脚本是「注解覆盖」的守卫，关注点不同；本仓已有「一关注点一脚本」的范式（`check-perm-invariants` / `check-record-contracts`）。

### 9.3 已知盲区（登记）

`check-backend-contracts.mjs` 的 `MIGRATION_SPECS` 按「**单个**迁移文件 ↔ 实体」比对列，`operation_log` 的列分散在 `V2`（建表）与 `V51`（加列）两个文件里，因此**该脚本不覆盖本表的列契约**。`OperationLog` 新增的 `success` / `error` 两列由 §10.1 的 `V51OperationLogMigrationContractTest` 断言。这是既有守卫的结构性盲区，本设计不修（改造成本高于收益）。

---

## 10. 测试

### 10.1 后端

| 用例 | 断言重点 |
|------|----------|
| `V51OperationLogMigrationContractTest` | 逐条对齐 V48 契约测试的写法：`ALTER TABLE operation_log` 只加 `success` / `error` 两列（**且断言 ALTER 存在**，与 V48「不得 ALTER」相反）；两条新索引幂等（`IF NOT EXISTS`）；菜单 `code`/`name`/`type`/`path`/`sort=50` 落种子、父目录按 `d.code = 'system'` 解析、`ON CONFLICT (code) DO NOTHING`；`role_permission` 只回填 `'view'`、只给 `r.code = 'operator'`、`doesNotContain("r.code <> 'super_admin'")`、`m.code = 'system.operationLog'`、`ON CONFLICT DO NOTHING`、`doesNotContain` 全部写动作词；**不得**出现 `DROP TABLE` / `INSERT INTO operation_log` / `DELETE FROM operation_log` / `INSERT INTO login_log`；只断言去掉 `--` 注释后的真实语句 |
| `AuditLogControllerPermissionTest` | 无 `system.operationLog:view` → `operation-logs` 与 `login-logs` 均 403；有 `view` → 两者均 200；**`system.appLog:view` 不足以访问**（D2 的回归）；超管旁路可访问 |
| `AuditLogQueryTest` | `username` ILIKE；`module` 等值；`success=true/false` 各只返回对应行；`success` 缺省时**包含** `NULL` 存量行；`keyword` 命中 `action` 与 `detail_json`；`traceId` 等值；`from`/`to` 闭区间作用于 `created_at`；`pageSize=1000` 被压到 200；排序 `created_at DESC, id DESC` |
| `AuditLogDetailTest` | `detail_json` 为合法 JSON → `detail` 是对象；**为脏字符串 → 退回字符串且不抛错** |
| `AuditLogModulesTest` | 返回去重、已排序、不含 NULL |
| `LoginLogQueryTest` | `result=success` / `failed` 各自只返回对应行（**用小写取值**）；`ip` 精确；`username` ILIKE；时间窗 |
| `OperationLogAspectTest` | 方法正常返回 → `success=true`、`error` 为 NULL；方法抛异常 → `success=false`、`error` 非空、**异常仍向上抛**（业务不受影响）；**抛出 `getMessage()` 为 null 的异常（如裸 `NullPointerException`）→ 仍记 `success=false`**（§8.1 的成败判据回归）；`error` 超 500 字符被截断；`detail_json` 只有 `args` 且 `password` / `token` / `idNo` / `phone` 被脱敏；**Mapper 抛异常时 → 业务正常返回且留下一行 `warn`** |

### 10.2 前端 / 静态

- `pnpm check:perm`（新增菜单/路由/镜像一致）、`pnpm check:backend`、`pnpm check:audit`（新增；含「故意删掉一个 `@Audited` 会失败」的人工验证）、`pnpm lint`（须仍为既有 4 warning / 0 error）、`pnpm format:check`、`pnpm build:all`。

### 10.3 人工 E2E（集中一次做）

1. 在 `/invoice-tax-rates` 新增一条税率 → `/system/operation-logs` 出现 `module=invoice` / `action=tax_rate_save` 且结果「成功」。
2. 新增一条公摊配置 → 出现 `action=apportion_config_save`。
3. 制造一次失败写操作（如删除被引用的字典项）→ 该行结果「失败」，详情抽屉有失败原因。
4. 用错误密码登录一次 → 「登录日志」Tab 出现 `failed` 行，原因/方式列显示失败原因。
5. 用微信登录成功一次 → 出现 `success` 行，原因/方式列显示 `wechat`（验证语义重载的标注是否讲清楚）。
6. 「结果」筛选选「失败」→ 只剩失败行；选「全部」→ 也包含 `success IS NULL` 的存量行（结果显示「—」）。
7. 某行点 `traceId` → 跳 `/system/app-logs?traceId=…` 并还原到该链路视图。
8. 筛选/分页进 URL：切 Tab、翻页、刷新、复制链接打开，全部还原（`tab=login&loginPage=2`）。
9. 手改 `?opSuccess=xxx` / `?loginResult=SUCCESS` → 该筛选被忽略（不报错、不返回空集）。
10. 只用 `system.appLog:view` 的角色登录 → 「操作日志」菜单不可见，直接调 `/api/v1/system/operation-logs` 得 403。
11. 「操作日志」页**没有**任何删除/清理入口。

---

## 11. 边界与错误处理

| 场景 | 处理 |
|------|------|
| 审计落库失败（DB 不可用） | 切面 `catch (Throwable) → log.warn`；业务接口的响应与状态码**不受影响**（关键回归用例） |
| `detail_json` 是脏字符串 | 详情接口退回原始字符串，**不抛错**（一条脏数据不该让整页 500） |
| 手改 URL 传非法 `success` / `result` | 前端白名单之外一律当「没选」，不发给后端（不报错、不返回空集） |
| `pageSize` 传 1000 | 服务端压到 200 |
| `page` 传 0 / 负数 | 压到 1 |
| `from` 晚于 `to` | 返回空集（不报错）；前端 RangePicker 的 `allowEmpty` 允许单边，不额外校验 |
| `success` 缺省 | **不过滤**（含 `NULL` 存量行）；只有显式 `false` 才排除未知行 |
| 超管 | 依 `isSuperAdmin()` 旁路，无需 `role_permission` 数据行 |
| 迁移只用 `operator` 回填 `view` | 业务角色须由管理员在角色权限页显式授予 |

---

## 12. 迁移与权限接线清单

| 事项 | 内容 |
|------|------|
| 迁移 | `V51__operation_log_query.sql`：加 2 列 + 2 索引 + 1 条菜单 + 1 条 `role_permission` 回填 |
| 菜单 | `code='system.operationLog'`、`name='操作日志'`、`menu_type='menu'`、`path='/system/operation-logs'`、`icon=NULL`、`sort=50`、父目录按 `code='system'` 解析 |
| 权限点 | `system.operationLog:view`（`PermissionAction.VIEW` 已在词表内，无需改枚举）；**无** `delete`，**无**其它写动作 |
| 后端测试夹具 | `backend/src/test/java/com/ams/support/RbacFixtures.java` 的「写权限授予」区块按需加 `system.operationLog:view`（`AuditLogControllerPermissionTest` 要用它装配「有 view」的角色）。`check-perm-invariants.mjs` 第 2b 道会校验夹具里的码 ⊆ 后端 `@RequiresPerm`，因此**注解必须先落**（顺序：Controller → 夹具 → 测试） |
| 启动断言 | `PermissionRegistry.assertMenuCodesExist` 要求 `menu` 表中存在 `system.operationLog` → **接口与迁移必须同一提交**（Flyway 在 bean 初始化前执行，顺序天然正确） |
| 前端 5 处 | 见 §6.5 |
| 部署 | 迁移随应用启动执行，无需手工步骤；角色权限页给需要的角色勾 `system.operationLog:view`（`super_admin` 走旁路） |

---

## 13. 数据流（一次完整路径）

```
管理员在 /invoice-tax-rates 新增一条税率
  → POST /api/v1/invoice-tax-rates（带 X-Trace-Id）
  → InvoiceTaxRateController.save  ← @Audited(module="invoice", action="tax_rate_save")
  → OperationLogAspect：成功 → operation_log 一行
       {module: invoice, action: tax_rate_save, username: admin, ip: 10.0.0.5,
        trace_id: 3f2a…, success: true, error: NULL, detail_json: {"args":[…]} }

审计员在 /system/operation-logs
  → GET /api/v1/system/operation-logs?module=invoice&success=false&from=2026-09-13T00:00:00
  → 结果「失败」标签 → 点行 → 详情抽屉看 error 与 detail
  → 点 traceId「到应用日志查看」→ /system/app-logs?traceId=3f2a…
  → 同一条链路上的后端异常与端侧报错并排出现（app_log 的既有能力）
```

---

## 14. 风险与回退

| # | 风险 | 影响 | 缓解 / 回退 |
|---|------|------|-------------|
| R1 | 本设计改的 5 个前端文件（`App.tsx` / `routeRegistry.ts` / `pathToCode.ts` / `modules.tsx` / `menuIcons.tsx`）与**在途未提交**的 app-log 改动重叠 | 合并冲突 | 两者表、权限点、页面互不相干，冲突只在「同文件新增一行」级别；建议 app-log 提交后再合本设计，或按文件顺序 rebase |
| R2 | `success` 为可空列，存量行永远显示「—」 | 审计员可能误读为「失败」 | UI 用灰色「—」+ `Tooltip` 明确「该记录产生于成败字段上线之前」；`success` 缺省筛选**保留** NULL 行 |
| R3 | `keyword` 命中 `detail_json` 的 ILIKE 无索引 | 数据量增大后变慢 | 审计表体量与业务写操作同阶（远小于 app_log）；`LIMIT` + 分页；量大后再加 `pg_trgm` |
| R4 | 豁免清单依赖人工判断，可能把该记的接口豁免掉 | 审计缺口重新出现 | `@AuditedExempt` 的理由写在端点旁、随代码评审；守卫输出逐条列出豁免清单，评审时可见 |
| R5 | 为审计数据开了读取通道 | 权限扩张风险 | 新建独立权限点（不复用 `system.appLog:view`）、迁移只回填 `operator`、`AuditLogControllerPermissionTest` 断言「只用 appLog 权限访问审计接口得 403」 |
| R6 | `login_log.fail_reason` 语义重载 | 审计员把登录方式误读为失败原因 | UI 列名「原因 / 方式」+ `Tooltip` 说明；改表登记为 follow-up |
| R7 | 切面改为 `log.warn` 后，DB 故障期间会产生大量 warn | 日志噪声 | 只在审计落库失败时触发，与业务异常同量级；比「静默丢失合规证据」可接受 |

---

## 15. 明确不做（与 §1.3 呼应）

| 被否决的方案 | 否决理由 |
|--------------|----------|
| 复用 `system.appLog:view` 访问审计数据 | app-log 设计 §8.1 已定性为隐蔽提权；审计数据含用户名与接口入参 |
| 在 `/system/app-logs` 里加「审计」Tab | 同上：页面共用会让权限点跟着共用 |
| 给 3 个第三方回调加 `@Audited` | 无登录用户 → `username=null` 的幽灵行；回调重放 → 重复行；且不是用户操作 |
| 给只读预览（`previewTemplate` / `draftPreview`）加 `@Audited` | 不落库、不改变系统状态，记了只是噪声 |
| 给 `markRead` / `createSession` 加 `@Audited` | 高频低价值，会淹没关键操作 |
| `success` 用 `NOT NULL DEFAULT true` | 会把历史失败行谎报为成功 |
| 提取 `ref_id`（SpEL 或反射） | 成本高于收益；`detail_json` 已含入参（D4） |
| 新增 `duration_ms` 列 | 审计关心「做了什么、成没成」，不关心耗时；耗时是性能埋点的事 |
| 审计表的清理 / 归档 / 分区 | 「操作日志不可删」+ 保留 ≥3 年；本模块纯只读 |
| 导出 Excel / CSV | 无明确需求；浏览器复制足够 |
| 顶部统计条 / 图表 | app_log 页的统计条服务于「有没有在炸」，审计无此诉求 |
| 按 `action` 的精确筛选参数 | FR-COM-004 只要求时间/用户/模块；`keyword` 已覆盖 |
| 修 `login_log.fail_reason` 的语义重载（加 `login_method` 列） | 改既有审计表的数据模型属独立决策，本设计只读它 |
| app_log 链路抽屉 → 审计页的反向链接 | 与在途 app-log 改动同文件；登记为 follow-up |
| 从返回值反射提取 `refId` | 同 `ref_id`：`Map` / `List` / 非标准返回类型要逐个兜底 |

---

## 16. 验收标准

1. `/system/operation-logs` 可按**时间 / 用户 / 模块**查询操作日志，按**时间 / 用户名 / 结果 / IP** 查询登录日志；日志页的筛选与分页进 URL，刷新 / 分享 / 抽屉返回都能还原（D11）。
2. 操作日志能区分成功 / 失败并按结果筛选；存量 `NULL` 行显示为「—」（未知），**在「结果」不筛选时照常出现**，只有显式选「成功」时才被排除（`success = true` 的定义使然，§5.2 / §11）。
3. `InvoiceTaxRateController.save` 与 `ApportionConfigController.save` 各自产生审计行；其余 7 个接口带 `@AuditedExempt("理由")` 且**不**产生审计行。
4. `node scripts/check-audit-coverage.mjs` 通过；**故意**删掉任一 `@Audited` 时该脚本失败（人工验证一次）。脚本接 `pnpm check:audit` 与 CI。
5. 只有持有 `system.operationLog:view` 的角色能访问两个列表接口；只持有 `system.appLog:view` 访问审计接口得 **403**；迁移只为 `operator` 回填 `view`，无任何写动作。
6. 本模块**没有**删除 / 清理入口，迁移里**没有** `DELETE FROM operation_log`，也没有新增清理定时任务。
7. 审计落库失败时业务接口的响应与状态码不受影响，且留下一行 `warn`。
8. `V51OperationLogMigrationContractTest` 全绿；`mvn test` 全绿。
9. `pnpm build:all` · `pnpm lint`（既有 4 warning / 0 error）· `pnpm format:check` · `pnpm check:perm` · `pnpm check:backend` · `pnpm check:audit` 全绿。
10. §10.3 的 11 条人工 E2E 全部通过（由用户执行）。
