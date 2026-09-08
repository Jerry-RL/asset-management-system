# 前端 Monorepo

资产经营管理系统前端工作区，使用 **pnpm workspaces** 管理。

## 目录结构

```
frontend/
├── package.json            # 工作区根：文档工具 + lint/format + workspace 脚本
├── pnpm-workspace.yaml     # workspace 定义
├── eslint.config.js        # ESLint 扁平配置（TS/JS，小程序目录排除）
├── .prettierrc             # Prettier 规则
├── .prettierignore
├── admin-web/              # PC 管理后台（React + Vite + TS + Tailwind）
├── h5-tenant/              # 用户端 H5（对齐 miniprogram-tenant 功能）
├── h5-worker/              # 工作端 H5（对齐 miniprogram-worker 功能）
├── miniprogram-tenant/     # 微信用户端小程序（原生，微信开发者工具管理）
├── miniprogram-worker/     # 微信工作端小程序（原生）
└── packages/               # 共享包（api-types 等，待脚手架）
```

## 常用命令（在 `frontend/` 目录执行）

```bash
pnpm install           # 安装所有 workspace 依赖

pnpm lint              # ESLint（排除小程序原生目录）
pnpm format            # Prettier 格式化
pnpm format:check      # Prettier 检查
pnpm api:lint          # OpenAPI 契约校验（../docs/api/openapi.yaml）

pnpm dev               # admin-web 开发服务器
pnpm dev:tenant        # h5-tenant 开发服务器（:5174）
pnpm dev:worker        # h5-worker 开发服务器（:5175）

pnpm build             # 构建 admin-web
pnpm build:tenant      # 构建 h5-tenant
pnpm build:worker      # 构建 h5-worker
pnpm build:all         # 构建全部三个 Web 应用
```

## 约定

- OpenAPI 契约位于 `../docs/api/openapi.yaml`。
- **Web 应用**（admin-web / h5-tenant / h5-worker）：React 18 + Vite + TS + Tailwind，JWT 统一封装，`@/` 路径别名指向 `src/`。
- **微信小程序**（miniprogram-tenant / miniprogram-worker）：微信原生开发，使用微信开发者工具打开对应目录，不在 pnpm/ESLint/Prettier 工具链范围内。
- 后端为独立 Maven 工程（`../backend`），不在本 workspace 内。
