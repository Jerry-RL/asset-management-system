# ADR-0012: pnpm Monorepo 工程结构

- **状态**：Accepted
- **日期**：2026-08-26

## 上下文

三端（PC + 2 小程序）+ 统一 API + OpenAPI 生成类型，需避免多仓库版本漂移。

## 决策

采用 **pnpm workspaces** 单仓库：

```yaml
# pnpm-workspace.yaml
packages:
  - backend
  - admin-web
  - miniprogram-tenant
  - miniprogram-worker
  - packages/*
```

- `packages/api-types`：由 `@openapitools/openapi-generator-cli` 或 `openapi-typescript` 从 `docs/api/openapi.yaml` 生成
- `packages/shared`：租控/账单状态枚举、错误码常量，与 SRS §4.24 一致
- 根目录统一 ESLint、Prettier、EditorConfig（已有）
- CI 按 package 过滤构建：`pnpm --filter backend build`

## 备选方案

| 方案 | 结论 |
|------|------|
| Multi-repo | 契约同步难 → 拒绝 |
| npm/yarn | pnpm 磁盘与速度更优 → 选用 pnpm |

## 后果

- 正面：一次 PR 可改 API + 前端类型 + 文档
- 负面：仓库体积增大；需规范 package 边界

## 关联

- [技术选型说明书.md](../design/技术选型说明书.md) §4
