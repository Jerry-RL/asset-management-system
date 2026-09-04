# CI/CD 方案

| 版本 | V1.1 |
|------|------|
| 日期 | 2026-08-26 |
| 关联 | DSD V1.6 §10、[技术选型说明书.md](./技术选型说明书.md) |

---

## 1. 分支策略

与 [代码规范与Git规范.md](../代码规范与Git规范.md) 一致：

- `main`：生产就绪，保护分支
- `develop`：集成
- `feature/*`、`fix/*` → PR → `develop` → `release/*` → `main`

---

## 2. GitHub Actions 流水线

### 2.1 PR 检查（`.github/workflows/ci.yml`）

| 步骤 | 命令 |
|------|------|
| 安装 | `pnpm install` + `cd backend && mvn -B verify` |
| Lint | `pnpm lint` + `pnpm format:check` |
| OpenAPI | `pnpm api:lint` |
| 后端 | `mvn -B verify`（`backend/`） |

### 2.2 main 分支

PR 检查 + Docker 镜像构建（`ams-api`、`ams-worker`）推送 registry（tag: `sha`、`latest`）。

### 2.3 发版

- Tag `v*.*.*` 触发 STAGING 部署（Helm/K8s 或 docker compose）
- UAT 通过后人工批准 PROD

---

## 3. 环境晋升

```
DEV (自动) → TEST (自动) → STAGING (tag) → PROD (manual approval)
```

数据库：Flyway migrate 作为部署 Job 第一步；**先 STAGING 验证再 PROD**。

---

## 4. 质量门禁

| 门禁 | 标准 |
|------|------|
| PR | 无 ESLint error；OpenAPI valid；单测通过 |
| 集成 | TC-LOOP 冒烟脚本（可选 nightly） |
| 发版 | CHANGELOG 更新；迁移脚本 Review；回滚方案 |

---

## 5. 密钥管理

- GitHub Secrets / 云密钥服务存储 `DATABASE_URL`、`JWT_*`、微信密钥
- 禁止写入仓库；`.env.example` 仅占位

---

## 6. 回滚

- 应用：K8s `kubectl rollout undo` 或上一镜像 tag
- 数据库：仅 forward migrate；回滚用补偿 `U*` 脚本（Flyway undo 可选）

---

**说明**：`.github/workflows/ci.yml` 为流水线实现；本文档为设计说明。
