# 应用日志（端侧可观测性）—— 极简单机·直连数据库 设计

**日期**：2026-09-12
**状态**：已确认，待实现
**范围应用**：`backend`（新增 `com.ams.platform.observability`）+ `frontend/packages/log-sdk`（新建）+ 5 个前端（`admin-web` / `h5-tenant` / `h5-worker` / `miniprogram-tenant` / `miniprogram-worker`）

**关联设计**：

- `docs/superpowers/specs/2026-09-12-menu-and-role-permission-design.md` —— 菜单/权限矩阵、`@RequiresPerm` 与 `PATH_TO_CODE` 镜像口径，本设计沿用
- `docs/superpowers/specs/2026-09-12-list-return-state-design.md` —— 查询页的筛选/分页必须进 URL，本设计沿用
- `docs/adr/0004-postgresql-primary-store.md`、`docs/adr/0006-flyway-schema-migration.md`、`docs/adr/0017-mybatis-plus-flyway.md` —— 存量存储与迁移机制，本设计不引入新存储
- 外部输入：用户提供的《极简版前端可观测性方案》（**砍掉 ES / Kibana / Kafka / Nginx 集群，保留全链路 TraceId**）

---

## 1. 背景与目标

### 1.1 现状盘点

| 能力 | 现状 | 位置 |
|------|------|------|
| TraceId 生成 / 透传 | **已有**：读请求头 `X-Trace-Id`，缺省用 `UUID.randomUUID().replace("-","")` 生成；写 `MDC` 与 `TraceIdUtil`(ThreadLocal)；回写响应头 | `config/TraceIdFilter.java` |
| 日志模板带 traceId | **已有**：`logging.pattern.console` 含 `traceId=%X{traceId}` | `application.yml` |
| 响应体携带 traceId | **已有**：`ApiResponse.traceId`，`GlobalExceptionHandler` 每条错误响应都带 | `common/web/ApiResponse.java` |
| 端侧发送 traceId | **缺失**：5 个前端**没有任何一处**发送 `X-Trace-Id` | `frontend/*/src/lib/api.ts`、`frontend/miniprogram-*/utils/api.js` |
| 端侧错误上报 | **缺失**：线上 H5 / 小程序的 JS 报错完全不可见 | —— |
| 后端业务审计 | **已有（只写）**：`operation_log` + `OperationLogAspect`（`@Audited`），**全仓无任何查询接口** | `platform/security/OperationLogAspect.java` |
| 后端异常排查 | 只有日志文件（带 traceId），不落库、无查询入口 | —— |
| 运维告警 | **无**。`alert` 模块是业务预警（合同到期/欠费），`notification` 只有站内信 | `modules/alert`、`modules/notification` |
| 周期任务机制 | `@Scheduled`（`ScheduledJobs`，13 个 cron）。**JobRunr 已在 `pom.xml` 引入并启用，但全仓零使用** | `platform/job/ScheduledJobs.java` |

**结论**：全链路 TraceId 的**服务端那一半已经建好**，缺的是端侧那一半，以及「看得见」的查询与告警。本设计只补这三件事，**不重做**已存在的 TraceId、审计、通知设施。

### 1.2 目标

1. 线上 H5 / 小程序的 JS 运行时错误、Promise 未捕获、API 失败，能在 admin-web 上按时间/端/级别查到，含 URL、UA、堆栈、用户自报 ID。
2. 后端未捕获异常与端侧报错**共用 `trace_id`**，一次查询看到同一条链路上的全部记录。
3. ERROR 达阈值时推送企业 IM（企微 / 钉钉 / 通用 Webhook），同一错误在冷却期内不轰炸。
4. **零新增中间件**；后端**零新增运行时依赖**；全部能力落在 PostgreSQL + 进程内计数。

### 1.3 非目标（明确不做）

- **不做 `track()` 业务埋点**。后端 `operation_log` + `@Audited` 已记录接口级业务动作（带用户身份），端侧再埋一遍是重复。
- **不做性能埋点**（Web Vitals / LCP / CLS / 路由耗时）。需要时用 Chrome DevTools / Lighthouse 本地测。
- **不做按月分区**。低流量下定时 `DELETE` 足够；分区是为百万级日志准备的。
- **不引入 ES / Kibana / Kafka / Nginx 集群**。
- **不做 SDK 远程配置下发**（采样率、开关等）。
- **不做 sourceMap 上传与堆栈还原**（见 §17 风险，这是本期最大的能力缺口，也是刻意留到二期的部分）。
- **不暴露 `operation_log` 的读取路径**（见 §9.3 的重要边界）。

---

## 2. 已确认决策

| # | 项 | 决策 | 理由 |
|---|----|------|------|
| D1 | 模块落点 | 后端新建 `com.ams.platform.observability`；表 `app_log` | 与 `platform/event`、`platform/health` 同级（跨切面基础设施）。`platform/approval` 已有「平台包内含菜单绑定 Controller」的先例，不冲突 |
| D2 | 与 `operation_log` 的关系 | **分表，不合并**，靠 `trace_id` 关联 | `operation_log` 是**合规审计**（带用户身份、长期保留）；`app_log` 是**运维排查**（无身份、自动过期）。混表后「清理 30 天前日志」会变成「删除合规留痕」 |
| D3 | 端侧接入范围 | **5 端全接** | SDK 共用一份，边际成本低；C 端才是看不到 console 的重灾区 |
| D4 | 采集内容 | **JS 运行时错误 + Promise 未捕获 + API 失败**三类 | 用户确认。不含 `track()`、不含性能埋点 |
| D5 | 上报形态 | **发生即发**，不做队列、不做批量、不做 `/batch` 端点、不做 `flush()` | 批量是为已被裁掉的 `INFO` 埋点设计的；剩下的三类全是 ERROR，本就是实时上报。留一个永不攒满的队列 + 空转的 `flush()`（含「小程序 `onHide` 要 flush」）是纯负担与误导 |
| D6 | traceId 粒度 | **请求级**：每次 API 调用生成新 traceId 放 `X-Trace-Id`，错误上报时带该 traceId | 后端 `TraceIdFilter` 是**按请求**透传/生成的。会话级单一 traceId 会让「前端报错 ↔ 后端那条日志」失去 1:1 对应，排查时捞出大量无关记录 |
| D7 | 查询入口 | admin-web 新增**专用页** `/system/app-logs` + 菜单 + 权限点 | 排查时需按 traceId 聚合、看 `extra` JSON、手动清理，通用 `ResourcePage` 撑不住 |
| D8 | 告警出口 | **通用 Webhook**：`type = wecom \| dingtalk \| generic` | 不确定实际用哪个 IM；消息体适配仅约 30 行，且换 IM 不改代码 |
| D9 | 后端错误落库范围 | **只记 `GlobalExceptionHandler` 的未捕获异常（5xx）** | `AppException`（业务 4xx）与参数校验失败是预期内的用户错误，记了就是噪声、且会让 ERROR 告警失真。不用 logback Appender（避递归日志 / DB 连接压力 / 关闭期丢日志三个坑） |
| D10 | 保留策略 | 定时清理，默认 **30 天**，可配；接入现有 `@Scheduled` 机制 | 低流量下最简；不建分区 |
| D11 | 周期任务机制 | 用 Spring **`@Scheduled`**，不用 JobRunr | 项目全部 13 个周期任务都在 `ScheduledJobs` 里走 `@Scheduled`；JobRunr 虽在 `pom.xml` 且 `enabled=true`，但**全仓零使用**。新增 JobRunr 用法会引入本项目从未验证过的运行路径（见 §17 风险 R4） |
| D12 | 查询页状态 | 筛选/分页**进 URL**，复用 `useListQuery` / `useUrlParam` | 沿用 `2026-09-12-list-return-state-design.md` 已确立的全站约定 |
| D13 | 畸形 JSON 的响应 | 新增 `HttpMessageNotReadableException` 处理器返回 **400**，且不落 `app_log`、不告警 | **刻意的全局行为变更**（现状是 500 + 记日志）。不这么改，匿名攻击者可循环发送畸形 JSON 无限灌库并刷爆 ERROR 告警（放大面见 §7.2.1）。400 也才是正确的 HTTP 语义 |

---

## 3. 架构

```
┌─ 5 端 SDK（@ams/log-sdk）───────────────────────────────────────────┐
│  window.onerror / App.onError                    → source='js'      │
│  unhandledrejection / App.onUnhandledRejection    → source='promise' │
│  api.ts / utils/api.js 拦截器                     → source='api'     │
│          │                                                           │
│          ├─ 指纹去重（同指纹 5s 内只发第一条，抑制数下次带出）        │
│          └─ 发生即发：fetch(keepalive) → 失败回退 sendBeacon → 再失败丢弃│
└──────────────────────────┬──────────────────────────────────────────┘
                           │ POST /api/v1/public/app-logs（单条；免鉴权，已在白名单）
                           ▼
   AppLogIngestFilter ──► AppLogIngestController ──► AppLogIngestGuard ──► AppLogService.record()
   （解析前：413/429）        （@RequestBody 绑定）     （解析后：白名单/截断）      │（吞异常）
                                                             ▼
                                                        app_log（PostgreSQL）
                                                             │
                        GlobalExceptionHandler 5xx ──► AppLogRecorder ──────────┤ app_type='backend'
                                                             │
                                                             ▼
                                          AppLogAlertService（内存窗口计数 + 冷却）
                                                             │
                                                             ▼
                                              WebhookNotifier（wecom|dingtalk|generic）

  admin-web /system/app-logs ──GET /api/v1/system/app-logs…──► 列表 / 详情 / trace 聚合
  @Scheduled(0 30 3 * * *)   ──► AppLogPurgeJob ──► 分批 DELETE created_at < now() - N 天
```

### 3.1 四条硬边界

1. **`app_log` ≠ `operation_log`**（D2）。分表、分保留期、靠 `trace_id` 关联。
2. **日志写入必须吞异常**。`GlobalExceptionHandler` 落库失败只 `log.warn` —— 日志系统故障**绝不能**变成业务 500。
3. **ingest 免鉴权但不可滥用**。走已有的 `/api/v1/public/**` 白名单（`SecurityWhitelist.PATTERNS` 已含该前缀），因此**不改白名单**、`strict-perm=true` 时也不受影响。安全性靠：body 上限 + 字段截断 + 进程内限流 + 内存表上限。
4. **SDK 只采三类**（D4），并带客户端去重防「渲染错误循环刷爆库」。

---

## 4. 数据模型

### 4.1 `V48__app_log.sql`

> **JSON 一律存 `TEXT`**：`V4__jsonb_to_text.sql` 已明确把全仓 JSONB 改成 TEXT，原因是 MyBatis-Plus 实体字段是 `String`。因此 `extra` 用 `TEXT`，**不用 `jsonb`**。

```sql
CREATE TABLE IF NOT EXISTS app_log (
    id          BIGSERIAL PRIMARY KEY,
    trace_id    VARCHAR(64)  NOT NULL,
    level       VARCHAR(16)  NOT NULL,
    app_type    VARCHAR(32)  NOT NULL,
    source      VARCHAR(24)  NOT NULL,
    fingerprint VARCHAR(64)  NOT NULL,
    message     TEXT,
    extra       TEXT,
    ua          VARCHAR(512),
    url         VARCHAR(512),
    user_id     BIGINT,
    client_ip   VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_app_log_level  CHECK (level IN ('ERROR','WARN','INFO')),
    CONSTRAINT ck_app_log_source CHECK (source IN ('js','promise','api','backend'))
);

CREATE INDEX IF NOT EXISTS idx_app_log_trace       ON app_log (trace_id);
CREATE INDEX IF NOT EXISTS idx_app_log_created     ON app_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_app_log_level_time  ON app_log (level, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_app_log_app_time    ON app_log (app_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_app_log_fingerprint ON app_log (fingerprint, created_at DESC);

COMMENT ON TABLE app_log IS
    '端侧与后端应用日志（运维排查用，非合规审计）：operation_log 才是审计留痕，两者靠 trace_id 关联';
COMMENT ON COLUMN app_log.occurred_at IS
    '端侧发生时刻，时钟不可信，仅作排查线索；排序/清理一律用 created_at';
COMMENT ON COLUMN app_log.client_ip IS 'ingest 由服务端取 RemoteAddr 写入，不信任请求体';
COMMENT ON COLUMN app_log.user_id   IS '客户端自报，仅排查用，不作鉴权依据';
```

### 4.2 字段语义与三个刻意设计点

| 字段 | 取值 | 说明 |
|------|------|------|
| `trace_id` | 32 位 hex | 与后端 `TraceIdFilter` 同格式；端侧来自 SDK，后端异常来自 `TraceIdUtil.get()` |
| `level` | `ERROR` / `WARN` / `INFO` | 本期只产生 `ERROR`；`WARN`/`INFO` 留给二期 |
| `app_type` | `admin-web` `h5-tenant` `h5-worker` `tenant-mp` `worker-mp` `backend` | Java 侧 `AppType` 枚举白名单校验，**不加 DB CHECK** —— 加一个前端就改 DDL 不划算，而 ingest 是唯一入口 |
| `source` | `js` / `promise` / `api` / `backend` | 与 `level` 不同维：`source` 说明**是什么错了**，`level` 说明**多严重** |
| `fingerprint` | 32 位 hex | `sha256(source \| app_type \| message \| stack 前 200 字符)` 取前 32 位 |
| `extra` | JSON 字符串 | 堆栈、请求方法/路径/状态码、`suppressed` 计数等 |

1. **`occurred_at` 与 `created_at` 分离**。端侧时钟不可信（手机时间可能是错的）。查询/排序/清理**一律用服务端 `created_at`**；`occurred_at` 只作排查线索。用 `created_at` 清理还能保证「钟偏的客户端不会让日志立刻被删或永不删除」。
2. **`fingerprint` 落库**（而非仅内存）。它让「同一错误最近出现几次、集中在哪些端」成为一条 SQL，并作为告警冷却的键。
3. **`client_ip` 由服务端取 `RemoteAddr`**，不接受请求体传入，避免伪造。

---

## 5. 端侧 SDK 契约（`frontend/packages/log-sdk`）

### 5.1 为什么是共享包

`frontend/pnpm-workspace.yaml` **已经声明了 `packages/*`**（目录当前不存在）。5 端各写一份 ~80 行必然漂移；共享包是这个 workspace 已经预留的落点。

```
frontend/packages/log-sdk/
├── package.json          name=@ams/log-sdk, main=src/index.ts, type=module
│                         scripts: build:mp (tsc -p tsconfig.mp.json) · test (node --test test/*.test.mjs)
├── tsconfig.json
├── tsconfig.mp.json      仅产出小程序用的 CommonJS
├── src/index.ts          单文件、零运行时依赖（web + 小程序环境内探测）
├── test/log-sdk.test.mjs 纯逻辑断言（node:test，与 scripts/test-list-query-params.mjs 同风格）
└── dist/log-sdk.mp.js    构建产物（提交入库，供两个小程序拷贝）
```

**单文件 + 环境探测**（`typeof window !== 'undefined'` / `typeof wx !== 'undefined'`）让 web 与小程序共用同一份逻辑，避免两套实现漂移。

### 5.2 公共接口

```ts
type AppType = 'admin-web' | 'h5-tenant' | 'h5-worker' | 'tenant-mp' | 'worker-mp';
type LogSource = 'js' | 'promise' | 'api';

interface LogSdkOptions {
  endpoint: string;                  // '/api/v1/public/app-logs'（小程序传绝对地址）
  appType: AppType;
  getUserId?: () => number | null;   // 自报，可空
  dedupeWindowMs?: number;           // 默认 5000
  debug?: boolean;                   // true 时只 console，不发请求
}

const logger = createLogger(opts);

logger.newTraceId(): string;                          // 32 位 hex
logger.install(): () => void;                         // 装全局钩子，返回卸载函数
logger.captureError(err, extra?): void;               // source='js'
logger.captureRejection(reason, extra?): void;        // source='promise'
logger.captureApiFailure(info): void;                 // source='api'
//   info = { traceId, method, path, status?, error?, message? }
```

### 5.3 traceId 生成

```ts
const newTraceId = () => {
  // 与后端 TraceIdFilter 的 UUID.replace("-","") 同格式（32 位 hex）
  const uuid = globalThis.crypto?.randomUUID?.();
  if (uuid) return uuid.replace(/-/g, '');
  // 兜底：小程序与老浏览器无 randomUUID
  return Date.now().toString(16).padStart(12, '0') + Math.random().toString(16).slice(2, 22);
};
```

### 5.4 去重与「爆发可见性」

- `fingerprint` 由 `source | appType | message | stack 前 200 字符` 计算（SDK 内不依赖 Web Crypto，用确定性 djb2/简单 hash 即可）。
  **注意：这里算出的指纹只用于客户端去重，不上报、不参与服务端口径。** 落库的 `app_log.fingerprint` 由服务端**自己**算（§7.3），否则客户端可伪造指纹绕过告警冷却。
- 同 fingerprint 在 `dedupeWindowMs`（默认 5s）内**只发第一条**，其余只累加计数。
- 被抑制的次数**不丢弃、也不触发额外请求**：下一次同 fingerprint 上报时通过 `extra.suppressed` 带出并清零。
  - 例：渲染错误循环炸 30 次 → 第一条立即上报（`suppressed: 0`）；5s 后第一次再炸时，带上 `suppressed: 30`。
  - 代价是「爆发后彻底安静」时最后一次计数不会上报；可接受，因为爆发期的第一条已经入库。
- 这样既不会刷爆库，也能看出「这是个循环错误」。

### 5.5 传输

```ts
const send = (log) => {
  const body = JSON.stringify(log);
  if (isMiniProgram()) {
    wx.request({ url: endpoint, method: 'POST', data: log,
                 header: { 'Content-Type': 'application/json' },
                 fail: () => {/* 直接丢弃 */} });
    return;
  }
  fetch(endpoint, { method: 'POST', body, keepalive: true,
                    headers: { 'Content-Type': 'application/json' } })
    .catch(() => {
      // 页面卸载场景兜底；再失败就丢弃（低流量，不做重试队列）
      navigator.sendBeacon?.(endpoint, new Blob([body], { type: 'application/json' }));
    });
};
```

- **失败即丢弃**，不做重试队列（对齐用户文档「失败就丢弃，反正量小」）。
- `keepalive: true` 让请求在页面卸载后仍可完成；`sendBeacon` 仅作兜底。
- 不做批量（D5），因此**不需要** `onHide` flush。

---

## 6. 五个前端的接入点

### 6.1 web 三端（`admin-web` / `h5-tenant` / `h5-worker`）

各端 `src/lib/api.ts` 的 `request()` 是唯一请求出口（含 `upload` / `download` 两条附加链路），改造如下：

```ts
async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const traceId = logger.newTraceId();                  // ← 请求级（D6）
  const headers = buildHeaders(options);
  headers.set('X-Trace-Id', traceId);                   // ← 新增

  let resp: Response;
  try {
    resp = await fetch(`${API_BASE}${path}`, { ...options, headers });
  } catch (e) {
    logger.captureApiFailure({ traceId, method: options.method ?? 'GET', path, error: e });
    throw e;
  }

  let body: ApiResponse<T>;
  try {
    body = (await resp.json()) as ApiResponse<T>;
  } catch {
    logger.captureApiFailure({ traceId, method: options.method ?? 'GET', path,
                               status: resp.status, message: '响应解析失败' });
    throw new ApiError(resp.status || 50000, resp.ok ? '响应解析失败' : `请求失败(${resp.status})`);
  }

  // 只报「服务端故障」：HTTP ≥ 500 或业务码 ≥ 50000（ErrorCode.INTERNAL_ERROR）
  // 4xx / 业务校验失败不报 —— 那是预期内的用户错误，报了全是噪声
  if (resp.status >= 500 || body.code >= 50000) {
    logger.captureApiFailure({ traceId: body.traceId ?? traceId,
                               method: options.method ?? 'GET', path,
                               status: resp.status, message: body.message });
  }

  // ...其余保持现状（401 跳登录、code !== 0 抛 ApiError、返回 body.data）
}
```

`main.tsx` 里 `logger.install()` 一次（装 `window.onerror` + `unhandledrejection`）。

**注意**：`upload` / `download` 也要各自 `headers.set('X-Trace-Id', ...)`（它们不走 `request()`），并在 `resp.ok === false` 时报 `captureApiFailure`。

### 6.2 小程序两端（`miniprogram-tenant` / `miniprogram-worker`）

- `utils/log-sdk.js`：由 `packages/log-sdk/dist/log-sdk.mp.js` 拷贝而来（小程序构建器不解析 monorepo 路径，故用拷贝）。**必须与产物一致**，见 §17 R3 的防漂移守卫。
- `utils/api.js`：`header['X-Trace-Id'] = logger.newTraceId()`；`fail(err)` 与 `res.statusCode >= 500` / `body.code >= 50000` → `logger.captureApiFailure(...)`。
- `app.js`：

```js
const logger = require('./utils/log-sdk').createLogger({
  endpoint: BASE_URL + '/public/app-logs',   // 小程序需绝对地址
  appType: 'tenant-mp',
});
App({
  onError(err)            { logger.captureError(err); },
  onUnhandledRejection(r) { logger.captureRejection(r); },
});
```

- 小程序后台「服务器域名」需包含 API 域名（与现有请求同域，部署时确认，见 §13）。
- **无 `onHide` flush**：因为无队列（D5）。

---

## 7. 后端 ingest

### 7.1 端点

`POST /api/v1/public/app-logs`

- 落在已存在的 `SecurityWhitelist.PATTERNS` 的 `/api/v1/public/**` 内 → **不改白名单**，`strict-perm=true` 也不会被拒。
- **必须免鉴权**：登录页的报错也要能上报，否则最关键的「登录失败」反而收不到。
- 请求体是**单个 log 对象**（D5，无批量）：

```json
{
  "traceId": "3f2a1c9b8e7d6f5a4b3c2d1e0f9a8b7c",
  "level": "ERROR",
  "appType": "h5-tenant",
  "source": "api",
  "message": "GET /api/v1/bills 失败：500",
  "extra": { "method": "GET", "path": "/api/v1/bills", "status": 500, "suppressed": 0 },
  "ua": "Mozilla/5.0 ...",
  "url": "https://app.example.com/bills?page=2",
  "userId": 42,
  "occurredAt": "2026-09-12T11:22:33.000Z"
}
```

响应：`ApiResponse.ok(null)`（HTTP 200）。SDK 不读响应，用统一信封只为与全仓一致。

### 7.2 防护分两段：解析前 Filter + 解析后 Guard

**为什么必须分两段**：`@RequestBody` 的 JSON 解析发生在 Controller 方法体之前。若把限流与体积检查写在方法体内，攻击者用畸形 JSON 就能**绕过限流**并让每次请求都产生一次解析开销。因此：

| 阶段 | 组件 | 职责 |
|------|------|------|
| **解析前** | `AppLogIngestFilter`（`OncePerRequestFilter`，仅注册在 `/api/v1/public/app-logs`） | body 体积上限（413）、按 IP 令牌桶限流（429）、受限流的请求直接短路，**不进 Spring MVC** |
| **解析后** | `AppLogIngestGuard`（纯逻辑类，可单测） | 白名单、截断、时钟偏差、`extra` 上限，由 Controller 调用 |

**`AppLogIngestFilter`**

| 检查 | 规则 | 违规处理 |
|------|------|----------|
| body 上限 | `getContentLengthLong() > maxBodyBytes`（默认 65536） | **413**，不读 body |
| 限流 | 进程内按 IP 令牌桶，默认 120 次/分钟 | **429**（`ErrorCode.RATE_LIMITED`），**不抛异常**（抛异常会被 `handleOther` 记成 backend 日志，形成自激） |
| 内存上限 | 限流表最多 2000 键，超出淘汰最旧 | 防伪造 IP 刷爆内存 |

> `Content-Length` 对 `fetch` 的 POST 一定存在；分块传输（chunked）可绕过该检查，此时由后端容器自身的请求体限制兜底，且 §7.3 的 `extra` 16KB 上限仍生效。本期的取舍是「不引入 streaming 解析」，在低流量场景下可接受。

**`AppLogIngestGuard`**

| 检查 | 规则 | 违规处理 |
|------|------|----------|
| `appType` | ∈ 6 值白名单（`admin-web` `h5-tenant` `h5-worker` `tenant-mp` `worker-mp` `backend`） | **400** |
| `source` | ∈ `js`/`promise`/`api`/`backend` | **400** |
| `level` | ∈ `ERROR`/`WARN`/`INFO`；缺省 `ERROR` | **400** |
| `traceId` | 匹配 `^[A-Za-z0-9-]{8,64}$` | 否则**服务端生成**（不拒绝：宁可丢关联，不可丢日志） |
| `message` | 截断到 2000 | 截断 |
| `url` / `ua` | 截断到 512 | 截断 |
| `extra` | 序列化后 > 16KB | **整块丢弃**并置 `extra={"truncated":true}` |
| `occurredAt` | 缺省 `now()`；与 `now()` 相差超过 ±24h 视为钟偏 | 存 `now()`，并在 `extra.clockSkew` 标记 |

`client_ip` 由服务端 `request.getRemoteAddr()` 写入，**不取请求体**。

### 7.2.1 畸形 JSON 必须映射为 400，且不得记入 `app_log`

**这是本设计必须堵的一个放大面**：项目当前**没有** `HttpMessageNotReadableException` 的处理器，畸形 JSON 会落到 `handleOther(Exception)` → 返回 500 **并且**（按 §9 的改动）写入一行 `app_type=backend` 的日志、还可能触发告警。于是一个匿名攻击者只要循环发送畸形 JSON，就能：

- 向 `app_log` 灌入无限后端日志；
- 把 ERROR 告警刷爆（绕过限流是因为畸形请求在解析前就已通过 §7.2 的 Filter —— 体积小、频率在限额内）。

因此必须新增：

```java
@ExceptionHandler(HttpMessageNotReadableException.class)
public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
    // 只 warn，不落 app_log：客户端畸形输入是可预期的，不是服务端故障
    log.warn("unreadable request body: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error(ErrorCode.BAD_REQUEST, TraceIdUtil.get()));
}
```

**这是一处刻意的全局行为变更**：全仓所有接口的畸形 JSON 响应从 `500 + 未捕获异常日志`（现状）变为 `400 + 一条 warn`。这才是正确的 HTTP 语义，且它把「客户端错误」与「服务端故障」分开 —— 否则后端 ERROR 告警里会混入大量由外部输入引起的噪声，告警也就失去意义。`AppException` / 参数校验 / 重复键的既有行为不变（§9 已规定它们不落库）。

### 7.3 `AppLogService.record()`

- **吞异常**：内部 `catch (Throwable) → log.warn`，绝不向上抛。
- `fingerprint` 由**服务端**用 `sha256(source | app_type | message | stack 前 200 字符)` 计算（客户端不传），避免客户端伪造绕过告警冷却。
- 写入成功后触发 `AppLogAlertService.onRecord(saved)`。

### 7.4 被 `X-Forwarded-For` 影响的风险

`RemoteAddr` 在反代后是代理 IP，会导致限流把所有客户端算作同一个。本期按「单机直连 / 一层反代」处理：若部署在反代后，需在部署文档中启用 `server.forward-headers-strategy=framework` 并信任代理。**本期不实现 XFF 解析**（引入可伪造的限流绕过面），仅在 §13 部署清单里登记该前置条件。

---

## 8. 后端查询接口（RBAC）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/v1/system/app-logs` | `system.appLog:view` | 分页列表。参数：`level`、`appType`、`source`、`keyword`（`message` ILIKE）、`traceId`、`from`、`to`（对 `created_at`）、`page`、`pageSize` |
| GET | `/api/v1/system/app-logs/{id}` | `system.appLog:view` | 单条详情，`extra` 解析为 JSON 对象返回 |
| GET | `/api/v1/system/app-logs/trace/{traceId}` | `system.appLog:view` | **同 traceId 全部记录**，按 `created_at` 升序（端侧与 `backend` 行混排，即「全链路」） |
| GET | `/api/v1/system/app-logs/stats` | `system.appLog:view` | 近 24h 各 `level` 计数 + Top `fingerprint`（含 `app_type` 分布） |
| POST | `/api/v1/system/app-logs/purge` | `system.appLog:delete` | 手动清理。body：`before`（**必填**）+ 可选 `appType`/`level`；**必填 `before` 是防误删全表的安全阀** |

- 用 `POST /purge` 而非 `DELETE` 带 body：`DELETE` 的 body 语义在各层代理上不一致；两者在 `strict-perm` 下都需注解，选更明确的。
- 分页沿用 `common/web/PageRequest` 与 `PageResult`（与全仓一致）。
- 列表默认按 `created_at DESC`，`pageSize` 上限 200（防一次拉爆）。

### 8.1 `operation_log` 的读取边界（重要）

`GET /trace/{traceId}` **只返回 `app_log` 行，不返回 `operation_log` 行**。

原因：`operation_log` 目前**全仓只写不读**（`OperationLogMapper` 只被 `OperationLogAspect` 用于 insert），没有任何查询接口与对应权限点。若在日志模块里顺带把它查出来展示，等于**第一次**给审计数据（`detail_json` 含接口入参、IP、用户名、模块/动作）开了一条读取通道，而调用者只持有 `system.appLog:view`。这是一次隐蔽的权限扩张。

本期做法：trace 抽屉里把 `trace_id` 做成可复制的文本，并注明「相同 traceId 的业务审计记录可在 `operation_log` 中检索」。**是否开放审计读取、用哪个权限点，是独立决策，另案设计。**

---

## 9. 后端异常落库（`GlobalExceptionHandler`）

### 9.1 改动点

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ApiResponse<Void>> handleOther(Exception ex) {
    log.error("unhandled exception", ex);
    // 端侧 + 后端同表同 traceId，一条 SQL 看全链路；内部吞异常，绝不影响本次响应
    appLogRecorder.recordBackendException(ex, TraceIdUtil.get());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, TraceIdUtil.get()));
}
```

`AppException`、`MethodArgumentNotValidException`、`DuplicateKeyException`、`HttpMessageNotReadableException` **不记**（D9：预期内的用户错误；畸形 JSON 的处理见 §7.2.1）。

### 9.2 事务与循环依赖的两条硬约束

- **不用 `REQUIRES_NEW`**。`@Transactional` 的 Service 在 Controller advice 层已回滚完毕、此处无事务上下文，普通 `insert` 即自动提交。加 `REQUIRES_NEW` 只会多占一个连接。**这条推理必须写进 JavaDoc**，否则后人会「顺手加上」。
- **禁止自激**：`AppLogRecorder` 内部 `catch (Throwable) → log.warn`；且 `recordBackendException` 不得调用任何会再次抛未捕获异常的组件。`level=ERROR`、`app_type=backend`、`source=backend`、`message = ex.getClass().getSimpleName() + ": " + ex.getMessage()`、`extra = { exception, stack(前 30 行), path, method }`。

---

## 10. 告警

### 10.1 触发与冷却

- `level=ERROR` 的记录写入后，按 `fingerprint` 在**内存滑动窗口**计数（默认 `window-minutes=5`，`threshold=10`）。
- **冷却**：同 `fingerprint` 在 `cooldown-minutes`（默认 5）内只发一次。
- 内存表上限 2000 键，超出淘汰最旧，防被大量不同 fingerprint 刷爆内存。
- 派发**异步**（`@Async` 或专用单线程 executor），不得阻塞 ingest 响应。

### 10.2 `WebhookNotifier`

| `webhook-type` | 消息体 |
|----------------|--------|
| `wecom` | `{"msgtype":"markdown","markdown":{"content":"..."}}` |
| `dingtalk` | `{"msgtype":"markdown","markdown":{"title":"...","text":"..."}}` |
| `generic` | `{"level":"ERROR","message":...,"traceId":...,"appType":...,"url":...,"count":N,"fingerprint":...,"time":...}` |

- 出口用 Spring 6.1 自带的 `RestClient`（项目已有 `spring-boot-starter-web`）—— **后端零新增依赖**。
- 超时 3s，失败只 `log.warn`，不重试、不落库。
- 默认 `enabled=false`；`enabled=true` 但 `webhook-url` 为空时，启动 `log.warn` 明确提示「告警未配置」，**不允许静默哑掉**。

---

## 11. 迁移 V48 与权限接线

### 11.1 `V48__app_log.sql`（除 §4.1 建表外的其余部分）

1. `menu` 插一行：
   - `code='system.appLog'`、`name='应用日志'`、`menu_type='menu'`、`path='/system/app-logs'`、`icon=NULL`；
   - 父目录按 **`code='system'` 解析**，不硬编码 `parent_id`（对齐 `V47__project_zone_menu.sql` 口径）；
   - `sort` 排在 `system.dict` 之后；
   - `ON CONFLICT (code) DO NOTHING`。
2. `role_permission` 回填 `view`：`ON CONFLICT DO NOTHING`，**显式排除 `super_admin`**，且**不回填 `delete`**（对齐 V45/V47 口径：动作级回填是上线前置人工步骤，误回填的宽权限是静默的）。

### 11.2 `PermissionRegistry` 的启动断言

`@RequiresPerm("system.appLog:view")` 引用了一个 `menu` 表中必须存在的 code。`PermissionRegistry.assertMenuCodesExist` 在启动时断言，不存在则**直接抛异常终止启动**。Flyway 在启动时先于 bean 初始化执行，顺序天然正确 —— 因此**接口与迁移必须同一提交**。

### 11.3 组件清单与权限码

| 组件 | 位置 | 权限 |
|------|------|------|
| `AppLog`（实体）/ `AppLogLevel` / `AppLogSource` / `AppType` | `observability/` | —— |
| `AppLogFingerprint`（纯函数，sha-256 → 32 hex） | `observability/` | —— |
| `AppLogIngestFilter`（解析前：体积 + 限流） | `observability/ingest/` | 无（公开白名单） |
| `AppLogIngestGuard`（解析后：白名单 + 截断） | `observability/ingest/` | 无（公开白名单） |
| `AppLogIngestController` | `observability/ingest/` | 无（公开白名单） |
| `AppLogRecorder`（供 `GlobalExceptionHandler` 调用） | `observability/service/` | —— |
| `AppLogService`（写入 / 查询 / 清理） | `observability/service/` | —— |
| `AppLogAlertService`（窗口计数 + 冷却 + 异步派发） | `observability/service/` | —— |
| `WebhookNotifier`（wecom / dingtalk / generic） | `observability/webhook/` | —— |
| `AppLogController`（列表 / 详情 / trace / stats / purge） | `observability/query/` | 类级 `@RequiresPerm("system.appLog:view")`；`purge` 方法级 `@RequiresPerm("system.appLog:delete")` |
| `AppLogPurgeJob`（`@Scheduled(cron = "0 30 3 * * *")`） | `observability/job/` | —— |
| `AppLogMapper` / dto（`AppLogIngestRequest` / `AppLogView` / `AppLogStats` / `AppLogPurgeRequest`） | `observability/mapper/`、`observability/dto/` | —— |
| `GlobalExceptionHandler`（改 1 处 + 加 1 个 handler） | `common/exception/` | —— |

> **类级 + 方法级覆盖**：`PermissionRegistry.collect` 同时扫描类级与方法级注解（`getAllDeclaredMethods`），并在 `purge` 上按方法级注解生效。这是项目既有模式。

### 11.4 前端五处必改（否则 `pnpm check:perm` 直接失败）

| 文件 | 改动 |
|------|------|
| `frontend/admin-web/src/App.tsx` | 加 `<Route path="system/app-logs" element={<AppLogPage />} />` |
| `frontend/admin-web/src/lib/routeRegistry.ts` | `STANDALONE_ROUTES` 加 `'/system/app-logs'` |
| `frontend/admin-web/src/lib/pathToCode.ts` | 加 `'/system/app-logs': 'system.appLog'`（必须与 DB 菜单一致，否则 `checkCodeMappingDrift` 告警） |
| `frontend/admin-web/src/pages/modules.tsx` | 静态 `MENU` 的「系统管理」组加 `{ path: '/system/app-logs', title: '应用日志' }` |
| `frontend/admin-web/src/lib/menuIcons.tsx` | `PATH_ICONS['/system/app-logs']`（如 `<FileSearchOutlined />`） |

---

## 12. 查询页（`frontend/admin-web/src/pages/AppLogPage.tsx`）

```
顶部统计条  近 24h：ERROR n · WARN n · 涉及端 m    [刷新] [清理…]
筛选行      时间范围(默认近24h) · level · appType · source · keyword · traceId
表格        时间(created_at) | level(Tag) | appType | source | message(省略) | url | traceId(可点)
行点击      → 详情抽屉：单条全部字段 + extra 格式化 JSON
traceId 点击 → 链路抽屉：GET /system/app-logs/trace/{traceId}，按时间升序展示全部记录
清理         → 弹窗：必选「保留到此时间之前」+ 二次确认，带 system.appLog:delete 权限门槛
```

- 权限用 `usePermByPath()`（页面在 `PATH_TO_CODE` 镜像里，无需写死 code），与 `SystemDictionaryPage` 同模式。
- **筛选与分页进 URL**，复用 `lib/listQuery.ts` 的 `useListQuery`（`filterKeys` = level/appType/source/keyword/traceId）与 `useUrlParam`（时间范围）。这样从链路抽屉返回、刷新、分享链接都能还原（D12）。
- 链路抽屉用 `Drawer`，不新增路由（避免再进 `STANDALONE_ROUTES` 镜像维护）。
- `extra` 用 `<pre>` 展示 `JSON.stringify(obj, null, 2)`，并提供复制按钮。
- 时间列显示 `created_at`（服务端时间）；若 `occurredAt` 与 `createdAt` 相差 > 60s，在 `extra` 视图里高亮提示「端侧时钟可能不准」。

---

## 13. 配置与部署前置

### 13.1 `application.yml`

```yaml
ams:
  observability:
    retention-days: ${AMS_LOG_RETENTION_DAYS:30}
    ingest:
      max-body-bytes: 65536
      rate-limit-per-minute: ${AMS_LOG_RATE_LIMIT:120}
    alert:
      enabled: ${AMS_LOG_ALERT_ENABLED:false}
      webhook-type: ${AMS_LOG_WEBHOOK_TYPE:generic}   # wecom | dingtalk | generic
      webhook-url: ${AMS_LOG_WEBHOOK_URL:}
      threshold: ${AMS_LOG_ALERT_THRESHOLD:10}
      window-minutes: 5
      cooldown-minutes: 5
```

### 13.2 部署清单

| # | 事项 | 说明 |
|---|------|------|
| 1 | 迁移 `V48` 随应用启动执行 | 无需手工步骤；`menu` 与 `role_permission` 一并落库 |
| 2 | 小程序「服务器域名」含 API 域名 | 与现有请求同域，通常无需新增；上线前确认 |
| 3 | 反代场景需启用 `server.forward-headers-strategy=framework` | 否则限流会按代理 IP 汇总（§7.4）。本期不实现 XFF 解析 |
| 4 | 角色权限：给需要的角色勾 `system.appLog:view` | 迁移只回填 `view`，`delete` 需人工授予 |
| 5 | 告警：`AMS_LOG_ALERT_ENABLED=true` + `AMS_LOG_WEBHOOK_URL` | 未配置时启动会 `log.warn` |

---

## 14. 数据流（一条完整路径）

```
用户在 h5-tenant 打开账单页（未登录态）
  → api.ts 生成 traceId=3f2a…，带 X-Trace-Id 请求 GET /api/v1/bills
  → 后端 TraceIdFilter 复用该 traceId，写入 MDC
  → BillService 抛未捕获异常
      · GlobalExceptionHandler log.error（文件日志含 traceId）
      · AppLogRecorder 落 app_log 一行：app_type=backend, source=backend, trace_id=3f2a…
      · 响应体 ApiResponse{code:50000, traceId:"3f2a…"} 且响应头 X-Trace-Id: 3f2a…
  → 前端 request() 见 status>=500 → captureApiFailure({traceId: body.traceId ?? 3f2a…})
      · 落 app_log 一行：app_type=h5-tenant, source=api, trace_id=3f2a…
  → AppLogAlertService 按 fingerprint 计数达阈值 → WebhookNotifier 推企微

排查者：admin-web → /system/app-logs?level=ERROR&traceId=3f2a…
  → 点 traceId → GET /system/app-logs/trace/3f2a…
  → 抽屉里同时看到「h5-tenant 的 API 失败」与「backend 的异常」两行 —— 全链路闭环
```

---

## 15. 边界与错误处理

| 场景 | 处理 |
|------|------|
| ingest 落库失败（DB 不可用） | 吞异常 + `log.warn`；ingest 仍回 200（SDK 本就不读响应）；业务请求不受影响 |
| `GlobalExceptionHandler` 落库失败 | 吞异常 + `log.warn`；仍正常返回 500 给调用方（**关键回归用例**，见 §16） |
| 限流触发 | 429 + `ErrorCode.RATE_LIMITED`，**不抛异常**（避免被 `handleOther` 记成 backend 日志而自激） |
| body 超限 | 413，不读 body（在解析前由 Filter 判定，见 §7.2） |
| 畸形 JSON（`HttpMessageNotReadableException`） | **400**，只 `log.warn`，**不落 `app_log`**、不触发告警（§7.2.1；全仓行为变更：原为 500） |
| 非法 `appType` / `source` / `level` | 400，不落库 |
| `traceId` 缺失或格式非法 | 服务端生成（宁丢关联，不丢日志） |
| `extra` 超大 | 整块丢弃，置 `extra={"truncated":true}` |
| 端侧时钟严重偏差（>±24h） | `occurred_at` 存 `now()`，`extra.clockSkew=true`；**清理仍按 `created_at`** |
| 错误循环（渲染错误每帧触发） | SDK 指纹去重（5s 窗口）+ 服务端限流（双层） |
| 攻击者伪造大量不同 `fingerprint` | 冷却表 2000 键上限 + 限流；淘汰最旧 |
| 攻击者提交超大 `extra` | 16KB 上限 + 64KB body 上限 |
| `webhook-url` 未配置但 `enabled=true` | 启动 `log.warn`；运行期不发送 |
| Webhook 超时 / 返回非 2xx | 只 `log.warn`，不重试、不落库 |
| 小程序 `wx.request` 上报失败 | 直接丢弃（低流量，不做重试队列） |
| 查询页手改 URL 传入非法 `level` | 忽略该筛选（不传后端），UI 显示为「全部」 |

---

## 16. 测试

### 16.1 后端

| 用例 | 断言重点 |
|------|----------|
| `AppLogFingerprintTest` | 同输入同指纹；`message`/`source`/`appType` 任一不同则指纹不同；stack 只取前 200 字符（尾部差异不影响指纹） |
| `AppLogIngestGuardTest` | 白名单拒绝；截断边界（2000/512/16KB）；`traceId` 非法时被服务端替换；时钟偏差标记 |
| `AppLogIngestFilterTest` | 令牌桶窗口与补充；限流表上限淘汰；`Content-Length` 超限短路 413（不读 body）；**限流在解析前生效**（畸形 body + 超限 → 429 而非 400/500） |
| `AppLogIngestControllerTest`（MockMvc） | **无 token 也能 200**（白名单回归）；非法 `appType` 400；超限 429；`client_ip` 来自 `RemoteAddr` 而非请求体；**畸形 JSON → 400 且 `app_log` 不新增行**（§7.2.1 放大面回归） |
| `AppLogControllerTest` | 无权限 403；有 `view` 可查；`trace/{id}` 返回端侧 + `backend` 混排；`purge` 缺 `before` 400、有 `delete` 才通 |
| `WebhookNotifierTest` | 三种 `type` 的消息体 JSON 断言；超时/非 2xx 不抛 |
| `AppLogAlertServiceTest` | 达阈值触发一次；冷却期内不再触发；不同 fingerprint 各自独立 |
| `AppLogPurgeJobTest` | 只删 `created_at` 超期行；分批不超限；未超期行不删 |
| `GlobalExceptionHandlerLogTest` | **关键回归 ①**：构造未捕获异常 → `app_log` 多一行 `app_type=backend`，且 `trace_id` 与响应体 `traceId` 一致；**关键回归 ②**：mock `AppLogMapper` 抛异常 → 业务仍正常返回 500 且响应体不变 |

### 16.2 前端

- `log-sdk` 纯逻辑断言（`node --test test/log-sdk.test.mjs`，**不进 CI** —— 沿用 `progress.md` 对「纯逻辑脚本不进 CI」的既有裁决）：
  - `newTraceId()` 为 32 位 hex；
  - 同指纹 5s 内只发一次、`suppressed` 累加并在下次带出；
  - `message` / `extra` 超限截断；
  - `debug=true` 时不发请求。
- 门禁：`pnpm build:all` · `pnpm lint`（须仍为既有 4 warning / 0 error）· `pnpm format:check` · `pnpm check:perm` · 后端 `mvn test`。

### 16.3 人工 E2E（集中一次做）

1. admin-web 控制台 `throw new Error('e2e-test')` → `/system/app-logs` 出现记录（`source=js`）。
2. 控制台 `Promise.reject(new Error('e2e-reject'))` → 出现 `source=promise` 记录。
3. 停掉后端 → 页面触发一次请求 → 出现 `source=api` 记录（网络异常分支，`traceId` 为前端生成）。
4. 用某个会 500 的接口 → 点 traceId 打开链路抽屉 → **同时**看到端侧 `api` 行与 `backend` 行，两行 `trace_id` 相同。
5. h5-tenant / h5-worker 各复现第 1 条。
6. 微信开发者工具对 `miniprogram-tenant` 触发 `App.onError` → 出现 `app_type=tenant-mp` 记录；`miniprogram-worker` 同。
7. 告警：把 `threshold` 临时调到 1、`enabled=true` 并配好机器人 URL → 收到消息；再连发 20 条同错误 → 冷却期内**只收到 1 条**。
8. 清理：把 `retention-days` 调成 0 重启，点页面「清理…」→ 记录清空；再手工插一条超期记录，等定时任务（或直接调 `purge`）→ 只剩未超期行。
9. 权限：用只授 `view` 的角色登录 → 能看到列表与链路抽屉，**看不到**「清理」按钮、直接调 `POST /purge` 得 403。
10. URL 规则：在 `/system/app-logs?level=ERROR&page=2` 打开链路抽屉后返回 → 条件与页码还原；F5 后一致。

---

## 17. 风险与回退

| # | 风险 | 影响 | 缓解 / 回退 |
|---|------|------|-------------|
| R1 | **生产 JS 已压缩，无 sourceMap 还原** | 前端错误的堆栈基本不可读，是本期最大的能力缺口 | 本期仍能拿到 `message`、`url`、`traceId`、`appType`、UA、错误类型，并可与后端异常关联，足以定位大部分问题。sourceMap 上传/还原需「构建产物上传 + 按 release 拉取 + 服务端还原栈」一整套，列为二期 |
| R2 | `@ams/log-sdk` 作为 workspace 包被 Vite 消费时可能解析异常（linked TS 源码） | 三个 web 端构建失败 | 首选 `main` 指向 `src/index.ts`（Vite 可转译 linked 包内的 TS）；若不通过，回退为 SDK 增加 `build` 产出 `dist/index.js` + `dist/index.d.ts`，`main`/`types` 指向产物 |
| R3 | 小程序 `utils/log-sdk.js` 是从 `dist/log-sdk.mp.js` **拷贝**的，可能静默漂移 | 两端行为不一致且难以发现 | 在 `scripts/` 增加一个守卫脚本（比对拷贝文件与产物是否一致，不一致则失败），并纳入门禁；与 `check-perm-invariants.mjs` 同风格 |
| R4 | 选用 `@Scheduled` 而非 JobRunr（D11） | 若将来统一迁到 JobRunr，需改一次 | 迁移成本极低（一个方法）；反过来说，为单一清理任务首次引入未验证的 JobRunr 运行路径，风险高于收益 |
| R5 | 限流按 `RemoteAddr`，反代后失效（§7.4） | 反代部署下限流把所有人算作一个 IP | 部署清单登记 `forward-headers-strategy`（§13.2 第 3 项）；本期不解析 XFF（避免可伪造的限流绕过面） |
| R6 | 客户端自报 `userId` 可伪造 | 排查时可能被误导 | `client_ip` 由服务端写入；`user_id` 明确标注为「自报，不作鉴权依据」；查询页加提示 |
| R7 | 前端新增 1 个 devDependency（`typescript`，仅用于产出小程序 CommonJS 产物） | 与「零新增依赖」表述有出入 | 后端**零新增运行时依赖**；前端新增的仅是构建期工具。若需完全避免，可改为在小程序侧手写适配层，但会引入 5 份漂移 —— 不采用 |

---

## 18. 明确不做（与 §1.3 呼应，此处列出被评估后否决的方案）

| 被否决的方案 | 否决理由 |
|--------------|----------|
| 扩展 `operation_log` 承载端侧日志（方案 B） | 混淆合规审计与运维日志；「清理 30 天日志」会误删审计留痕 |
| 各端内联 ~80 行 SDK（方案 C） | 5 份重复代码必然漂移 |
| 用 logback Appender 把所有 ERROR 写库 | 递归日志、DB 连接压力、关闭期丢日志三个坑 |
| 会话级单一 traceId（用户文档原方案） | 与后端按请求透传/生成的机制失配，排查时混出大量无关记录（D6） |
| 保留批量端点与队列（用户文档原方案） | 批量是为已裁掉的 `INFO` 埋点服务的，本期全是 ERROR（D5） |
| MySQL 按天分区 / PG 按月分区 | 低流量下定时 `DELETE` 足够 |
| ES + Kibana + Kafka + Nginx 集群 | 日活/月活不高的场景下运维成本远超收益 |
| 在日志模块里暴露 `operation_log` 读取 | 隐蔽的权限扩张（§8.1），应另案设计 |
| SDK 远程配置下发（采样率 / 开关） | 当前无此需求；配置项写在各端初始化处即可 |
| 端侧 `track()` 业务埋点 | 与后端 `operation_log` + `@Audited` 重复 |

---

## 19. 验收标准

1. 5 端（admin-web / h5-tenant / h5-worker / miniprogram-tenant / miniprogram-worker）的 JS 运行时错误、Promise 未捕获、API 失败均能落库，并在 `/system/app-logs` 查到。
2. 一条 `trace_id` 对应一次 API 调用：后端 `app_log(backend)` 与端侧 `app_log` 的 `trace_id` 相同，`GET /system/app-logs/trace/{traceId}` 能同时返回两者。
3. 后端业务异常（`AppException` 4xx、参数校验失败、重复键）**不**进入 `app_log`。
4. 日志系统故障（表不可写）时，业务接口的响应与状态码**不受任何影响**（`GlobalExceptionHandlerLogTest` 回归）。
5. ERROR 达阈值推送 Webhook；同 `fingerprint` 在冷却期内只推一次。
6. `retention-days` 到期的记录被定时任务清理；未到期的不被删。
7. 只有 `system.appLog:view` 的角色能看到列表与链路抽屉，看不到清理入口且直接调 `purge` 得 403；迁移为除 `super_admin` 外所有角色回填 `view`。
8. 查询页的筛选/分页进 URL：抽屉返回、刷新、分享链接都能还原。
9. 非法入参（appType / source / level）返回 400 且不落库；超限返回 413 / 429。
10. 畸形 JSON 返回 **400**（不再是 500）且**不产生** `app_log` 行、**不触发**告警（§7.2.1）。
11. `pnpm build:all` · `pnpm lint`（既有 4 warning / 0 error）· `pnpm format:check` · `pnpm check:perm` · `mvn test` 全绿。
12. `docs/superpowers/plans/` 下的实施计划中，R3 的小程序产物一致性守卫脚本存在且纳入门禁。
