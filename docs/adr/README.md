# 架构决策记录（ADR）

本目录记录资产经营管理系统的重要技术决策。格式参考 [Michael Nygard ADR](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)。

| ID | 标题 | 状态 |
|----|------|------|
| [ADR-0001](./0001-record-architecture-decisions.md) | 使用 ADR 记录架构决策 | Accepted |
| [ADR-0002](./0002-monolith-modular-backend.md) | 模块化单体优先于微服务 | Accepted |
| [ADR-0003](./0003-jwt-auth-and-rbac.md) | JWT 鉴权与 RBAC 数据权限 | Accepted |
| [ADR-0004](./0004-postgresql-primary-store.md) | PostgreSQL 作为主业务库 | Accepted |
| [ADR-0005](./0005-openapi-contract-first.md) | OpenAPI 契约优先的 API 设计 | Accepted |
| [ADR-0006](./0006-flyway-schema-migration.md) | Flyway 管理数据库版本 | Accepted |
| [ADR-0007](./0007-object-storage-attachments.md) | 附件走对象存储 | Accepted |
| [ADR-0008](./0008-llm-agent-architecture.md) | 私有化 LLM Agent 与 Tool-First 编排 | Accepted |
| [ADR-0009](./0009-lightweight-approval-engine.md) | 轻量自研审批引擎 | Accepted |
| [ADR-0010](./0010-knex-with-flyway.md) | Knex 查询 + Flyway 管理 Schema | Superseded by [0017](./0017-mybatis-plus-flyway.md) |
| [ADR-0011](./0011-bullmq-async-jobs.md) | BullMQ 异步任务队列 | Superseded by [0018](./0018-jobrunr-async-jobs.md) |
| [ADR-0012](./0012-pnpm-monorepo-structure.md) | 混合 Monorepo（Maven + pnpm） | Accepted |
| [ADR-0013](./0013-in-process-domain-events.md) | 进程内领域事件驱动通知 | Accepted |
| [ADR-0014](./0014-wechat-native-miniprogram.md) | 微信小程序原生开发（一期） | Accepted |
| [ADR-0015](./0015-data-classification-and-encryption.md) | 数据分类分级与加密策略 | Accepted |
| [ADR-0016](./0016-java-spring-boot-backend.md) | Java Spring Boot 后端主栈 | Accepted |
| [ADR-0017](./0017-mybatis-plus-flyway.md) | MyBatis-Plus + Flyway | Accepted |
| [ADR-0018](./0018-jobrunr-async-jobs.md) | JobRunr 异步任务 | Accepted |

**状态说明**：Proposed / Accepted / Deprecated / Superseded

**新建 ADR**：复制 [template.md](./template.md) 为 `NNNN-short-title.md`，更新本表索引。
