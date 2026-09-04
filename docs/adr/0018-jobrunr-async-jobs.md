# ADR-0018: JobRunr 异步任务（替代 BullMQ）

- **状态**：Accepted
- **日期**：2026-08-27
- **替代**：ADR-0011（BullMQ）在 Java 后端下 **Superseded**

## 上下文

原方案使用 Redis + BullMQ 处理出账、催缴、报告渲染等异步任务。Java 栈需等价能力，且尽量 **不新增中间件**（docker-compose 已有 PG + Redis）。

## 决策

1. **JobRunr 7.x**（`jobrunr-spring-boot-3-starter`）
   - 任务持久化于 **PostgreSQL**（JobRunr 表由 starter 管理或 Flyway 脚本）
   - 内置 Dashboard（开发环境可开）
2. **定时触发**：Spring **`@Scheduled`** 扫描业务条件后 `BackgroundJob.enqueue(...)`
3. **Redis**：仍用于会话、缓存、分布式锁（Spring Data Redis）；**不作为主任务队列**
4. **重试**：JobRunr 内置重试 + 业务幂等键
5. **traceId**：Job 执行上下文传递 MDC

## 任务类型（与 DSD 对齐）

| Job | 说明 |
|-----|------|
| `billing-generate` | 合同通过后生成缴费计划/账单 |
| `dunning-scan` | 催缴等级扫描 |
| `agent-report-render` | 报告 HTML/PPT/PDF 异步渲染 |
| `callback-retry` | 第三方回调补偿 |

## 备选

| 方案 | 结论 |
|------|------|
| BullMQ（Node） | Java 栈不适用 → 不采纳 |
| RabbitMQ | 需增中间件，当前规模过重 → 暂缓 |
| Spring @Async only | 无持久化与重试 → 不采纳 |
| JobRunr + PG | 无新组件、Spring 原生 → **采纳** |

## 后果

- K8s 可部署 **单镜像** API（内嵌 JobRunr Worker）；高负载时可 `jobrunr.background-job-server.enabled` 拆 Worker 实例
