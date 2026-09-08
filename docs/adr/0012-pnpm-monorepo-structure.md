# ADR-0012: 混合 Monorepo 工程结构

- **状态**：Accepted（2026-08-27 更新：后端 Maven，前端 pnpm）
- **日期**：2026-08-26

## 上下文

PC、双小程序、后端 API 需版本协同。后端切换 Java Spring Boot（ADR-0016）后，不再使用 pnpm 管理 backend。

## 决策

采用 **混合 Monorepo**（后端 Maven，前端 pnpm workspaces 收拢至 `frontend/`）：

```
asset-management-system/
├── backend/                 # Java Maven Spring Boot（ADR-0016）
├── frontend/                # 前端 Monorepo（pnpm workspaces）
│   ├── package.json         # 文档工具 + lint/format + workspace 脚本
│   ├── pnpm-workspace.yaml
│   ├── admin-web/           # React + Vite（pnpm）
│   ├── miniprogram-tenant/
│   ├── miniprogram-worker/
│   └── packages/
│       └── shared/          # 可选：共享常量 JSON/TS（小程序可读）
├── docs/
├── docker/
└── deploy/k8s/
```

**前端** pnpm workspaces（`frontend/pnpm-workspace.yaml`）：

```yaml
packages:
  - admin-web
  - miniprogram-tenant
  - miniprogram-worker
  - packages/*
```

**后端** Maven：`cd backend && mvn verify`

**OpenAPI**：`docs/api/openapi.yaml` 为契约；前端 `openapi-typescript` 生成类型；后端 springdoc 与契约对齐。

## 备选

| 方案 | 结论 |
|------|------|
| 全 pnpm（含 backend TS） | 已废弃 → ADR-0016 |
| 前后端分仓 | 文档与版本难同步 → 不采纳 |
| 混合 Monorepo | 各取所长 → **采纳** |

## 后果

- CI 需 **Maven + pnpm** 双流水线步骤
- `frontend/package.json` 保留文档工具与前端 lint（工作目录 `frontend/`）
