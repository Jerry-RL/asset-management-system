# Asset Management System（资产经营管理系统）

国资国企经营性资产全生命周期管理平台：PC 管理后台 + 用户端/工作端小程序。

## 文档体系

完整清单见 **[docs/README.md](docs/README.md)**（立项 → 交接全生命周期）。

| 阶段 | 核心文档 |
|------|----------|
| 立项 | 项目章程、商业论证 |
| 需求 | SRS V2.0、用户故事地图 |
| 设计 | 概要设计、详细设计、OpenAPI |
| 开发 | 数据库设计、ADR、代码规范 |
| 测试 | 测试计划、用例、性能报告 |
| 运维 | 部署手册、应急预案 |
| 交接 | 用户手册、系统交接文档 |

## 技术栈（规划）

- Backend: Node.js, Express, PostgreSQL, Redis
- Admin: React, TypeScript, Tailwind CSS
- Mini Program: 微信用户端 / 工作端
- Auth: JWT

## 仓库

https://github.com/Jerry-RL/asset-management-system

## 快速命令

```bash
# 预览 API 文档
npx @redocly/cli preview-docs docs/api/openapi.yaml
```
