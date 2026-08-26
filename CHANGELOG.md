# Changelog

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- **数据安全强化（SRS V2.3）**：§5.8 NFR-DSEC-001~025；§5.4 扩展；§10.2 数据安全验收项
- [数据安全设计说明书.md](docs/design/数据安全设计说明书.md)：分类分级、加密、脱敏、导出、生命周期、合规
- ADR-0015：数据分类分级与加密策略
- DSD V1.4 §7.2 数据安全实现；HLD §9 扩展；应急预案 V1.1 §5.4
- 技术选型 V1.1 §6 数据安全选型；测试用例 §14 TC-DSEC-001~009
- DSD V1.3：Knex+Flyway、BullMQ、DomainEventBus、审批引擎、Monorepo、缓存/幂等/监控
- HLD V1.1、技术选型说明书、CI-CD方案、技术方案评审报告
- ADR-0009~0014（审批、Knex、BullMQ、Monorepo、EventBus、小程序）
- OpenAPI：disposals、occupations、refunds、approvals、notifications
- 库表：approval_*、notification；pnpm-workspace.yaml；GitHub Actions CI
- SRS V2.2：第二轮闭环评审（十三条业务闭环）
- 业务闭环评审报告、用户故事地图 V1.1、测试用例 §14（TC-LOOP-*）
- FR-TENDER-006 报名保证金退还
- SRS V2.1：智能服务与决策支持（§4.26）、智能化非功能需求（§5.7）
- 资产管理垂直领域 Agent 技术方案（`docs/design/智能Agent技术方案.md`）
- ADR-0008：私有化 LLM Agent 与 Tool-First 编排
- Intelligence API 契约、Agent 数据表、智能测试用例 TC-AI-*
- Postman Collection + 环境变量（`docs/api/postman/`）
- 数据库 DBML + SchemaSpy 配置（`docs/database/`）
- K8s 部署清单（`deploy/k8s/`）
- Docker Compose 本地依赖（`docker/`）
- ESLint + Prettier 根配置（`eslint.config.js`、`.prettierrc`）
- TestRail/Xray 导入 CSV（`docs/testing/testcases.csv`）
- ADR 新建模板（`docs/adr/template.md`）

## [0.1.0] - 2026-08-26

### Added

- 项目脚手架：`README.md`、`.gitignore`
- 需求规格说明书 V1.0（设计稿与功能清单）
- 设计稿原始材料 `docs/source-material/`
- GitHub 远程仓库初始化

[Unreleased]: https://github.com/Jerry-RL/asset-management-system/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Jerry-RL/asset-management-system/releases/tag/v0.1.0
