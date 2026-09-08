# Asset Management System（资产经营管理系统）

国资国企经营性资产全生命周期管理平台：PC 管理后台 + 用户端/工作端小程序。

## 文档体系

完整清单见 **[docs/README.md](docs/README.md)**（立项 → 交接全生命周期）。  
**交付对照表**：[docs/文档交付清单.md](docs/文档交付清单.md)

| 阶段 | 核心文档 |
|------|----------|
| 立项 | 项目章程、商业论证、开发计划 |
| 需求 | SRS V2.3、用户故事地图 |
| 设计 | 概要设计、DSD V1.5、**技术选型 V2.0（Java）**、OpenAPI |
| 开发 | 数据库设计、ADR-0016~0018、代码规范 |
| 测试 | 测试计划、用例 |
| 运维 | 部署手册、应急预案 |

## 技术栈

| 层次 | 选型 |
|------|------|
| **Backend** | **JDK 21, Spring Boot 3.3, MyBatis-Plus, Flyway, JobRunr** |
| Admin | React, TypeScript, Vite, Tailwind CSS |
| Mini Program | 微信用户端 / 工作端 |
| Data | PostgreSQL 15, Redis 7 |
| Auth | Spring Security + JWT |

详见 [docs/design/技术选型说明书.md](docs/design/技术选型说明书.md)、[ADR-0016](docs/adr/0016-java-spring-boot-backend.md)。

## 仓库

https://github.com/Jerry-RL/asset-management-system

# 快速命令

```bash
# 本地依赖（PostgreSQL / Redis / MinIO）
cd docker && docker compose up -d

# 后端 API（需 JDK 21 + Maven）
cd backend && mvn spring-boot:run

# 前端 Monorepo（文档校验 + admin-web + 小程序）
cd frontend && pnpm install
pnpm api:lint        # OpenAPI 校验
pnpm lint            # ESLint
pnpm build           # 构建 admin-web
```

## 工程结构

```
backend/           # Java Maven Spring Boot
frontend/          # 前端 Monorepo（pnpm workspaces）
  admin-web/       # PC 管理后台 React
  miniprogram-*/   # 微信小程序（待脚手架）
  packages/        # 共享包（api-types 等，待脚手架）
docs/              # 全生命周期文档
```
