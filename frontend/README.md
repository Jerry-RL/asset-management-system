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

## 演示账号（demo profile）

统一密码：`admin123`。需启动后端 `demo` profile，重启后由 `DemoDataConsistencyEnricher` 幂等补齐。

### 用户端（h5-tenant / miniprogram-tenant）

| 账号 | 身份 | 手机号 | 说明 |
|------|------|--------|------|
| `tenant` | 张三（个人） | 13800000001 | 有生效合同与账单 |
| `tenant_corp` | 某商贸有限公司 | 13800000002 | 招租报名 / 信用良好 |

### 工作端（h5-worker / miniprogram-worker）

| 账号 | 角色 | 手机号 | 推荐场景 |
|------|------|--------|----------|
| `clerk` | 办事员 | 13900000008 | 外勤任务、催缴、现场收款 |
| `maintenance` | 维修管理员 | 13900000006 | 巡检、报修工单 |
| `approver` | 审批人员 | 13900000007 | 合同与流程审批 |
| `operator` | 运营管理员 | 13900000002 | 招租运营 |

微信绑定演示：工作端用上表手机号；用户端用租户手机号。

### PC 管理后台（admin-web）

| 账号 | 角色 |
|------|------|
| `admin` | 系统管理员 |
| `assetmgr` / `finance` / `leader` 等 | 同名角色 |

## 约定

- OpenAPI 契约位于 `../docs/api/openapi.yaml`。
- **Web 应用**（admin-web / h5-tenant / h5-worker）：React 18 + Vite + TS + Tailwind，JWT 统一封装，`@/` 路径别名指向 `src/`。
- **微信小程序**（miniprogram-tenant / miniprogram-worker）：微信原生开发，使用微信开发者工具打开对应目录，不在 pnpm/ESLint/Prettier 工具链范围内。
- 后端为独立 Maven 工程（`../backend`），不在本 workspace 内。
