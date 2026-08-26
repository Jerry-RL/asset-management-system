# ADR-0006: Flyway 管理数据库版本

- **状态**：Accepted
- **日期**：2026-08-26

## 上下文

数据库设计文档与真实库表易漂移。

## 决策

- 使用 Flyway（或 Liquibase）迁移脚本：`backend/db/migrations/V{version}__{desc}.sql`
- **禁止**手工改生产库结构；文档 ER 图与迁移脚本同步更新
- 本地/CI 启动前自动 `flyway migrate`

## 后果

- 正面：环境一致、可回滚审计
- 负面：迁移脚本需严格 Review
