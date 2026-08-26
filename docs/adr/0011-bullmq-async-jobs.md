# ADR-0011: BullMQ 异步任务队列

- **状态**：Accepted
- **日期**：2026-08-26

## 上下文

系统存在定时与异步场景：账单批量出账、催缴 L1–L5 扫描、SLA 超时、Agent 报告 PPT 渲染、银行对账导入、通知批量推送。仅用 node-cron 在同一进程执行，长任务会阻塞 API，且失败难重试。

## 决策

1. **node-cron** 仅作调度触发（如每日 02:00），触发后将 job 投入 **BullMQ** 队列
2. Redis 作为 BullMQ backend（与缓存共用实例，不同 DB index 可选）
3. Worker 可与 API 同进程（dev）或独立进程 `backend/worker.ts`（prod）
4. 标准队列：`billing`、`dunning`、`notification`、`agent-report`、`finance-import`
5. Job 须幂等：带 `jobId` 或业务 dedupe key

## 备选方案

| 方案 | 结论 |
|------|------|
| 仅 node-cron | 无重试、阻塞 → 拒绝 |
| Agenda/Mongo | 引入 Mongo 依赖 → 不采纳 |
| BullMQ | Redis 已有、成熟 → **采纳** |

## 后果

- 正面：失败重试、延迟任务、并发控制、可监控 queue depth
- 负面：需部署 worker；Redis 故障影响异步（API 同步路径仍可用）

## 关联

- DSD §4.9、部署手册 Redis 配置
