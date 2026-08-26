# ADR-0010: Knex 查询 + Flyway 管理 Schema

- **状态**：Accepted
- **日期**：2026-08-26

## 上下文

DSD 曾写「Prisma 或 Knex」。Flyway 已定为迁移工具（ADR-0006），若再用 Prisma Migrate 或 `schema.prisma` 作为 DDL 来源，会出现 **双源漂移**。

## 决策

1. **Flyway** 为唯一 DDL 来源：`backend/db/migrations/V*.sql`
2. **Knex** 仅作查询构建与 Repository 层访问，不使用 Knex migrations
3. 类型：手写 Repository 接口 + Zod DTO；可选从 OpenAPI/手写 types 定义行类型
4. 禁止 Repository 外裸 SQL；复杂报表可用命名 SQL 文件 + Knex.raw 参数绑定

## 备选方案

| 方案 | 结论 |
|------|------|
| Prisma 全栈 | Schema 与 Flyway 冲突 → 拒绝 |
| 纯 pg + SQL | 可行但缺少查询构建 → 不采纳 |
| Knex + Flyway | 职责清晰 → **采纳** |

## 后果

- 正面：DBA 可审 SQL；迁移可回滚脚本；与国资 DBA 流程契合
- 负面：无自动类型生成；需维护 Repository 与文档同步

## 关联

- [database/数据库设计.md](../database/数据库设计.md)
- DSD §3.2、§5
