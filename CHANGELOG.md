# Changelog

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- **后端全量实现（对齐 SRS V2.5 / DSD V1.8 / 数据库设计 V1.3）**：
  - Flyway 全量 DDL（`V2__init_schema.sql`，组织/资产/权证/招租/合同/计费/收款核销/催缴滞纳金/维修/预警/任务/通知/处置/占用/自用/评估/盘活/经营计划/期初迁移/调拨/配置版本/监管报送/发票/业财/Agent 表）
  - 公共层：`ApiResponse`/`PageResult`/`ErrorCode`/`AppException`/全局异常处理（对齐 OpenAPI 错误码）
  - 认证与安全：JWT(Access 2h + Refresh 7d) + RBAC + 图形验证码 + 登录失败锁定 + Token 吊销 + 数据范围隔离 + AES-256-GCM 字段加密 + 脱敏矩阵 + 操作日志 AOP
  - 业务域模块（modules/）：org、system、asset（租控状态机+调拨）、lease（招租/公开招租/信用黑名单）、contract（合同九态状态机+退租清场保证金）、pricing（定价引擎：折算/尾差）、billing（账单/收款/核销分配 FIFO/预收/退款冲正）、dunning（催缴升级 L1-L5+滞纳金）、invoice（发票生命周期+红冲）、finance（银行对账/凭证）、maintenance（报修 SLA）、alert、task（超时升级）、notification、disposal、occupation、selfuse、evaluation、revitalization、plan、migration（期初试算平衡）、dashboard（经营/集团合并）、config（参数版本）、regulation（监管报送）、intelligence（Agent 会话/报告/溯源）
  - 审批引擎（`ApprovalEngine`）+ 领域事件（afterCommit 发布）
  - 定时任务（`ScheduledJobs`）：出账/催缴/滞纳金/合同到期/待办超时（可配置开关）
  - 单元测试：PricingEngine（折算/尾差）、租控状态机、合同状态机
- **admin-web React 脚手架**：Vite + React 18 + TS + Tailwind + react-router；登录（验证码）、经营看板、资产台账；JWT 客户端与统一响应解析
- **admin-web 全量业务模块**：通用 `ResourcePage` 组件 + 全量模块注册表（`modules.tsx`），覆盖 SRS §3.2 全部 PC 功能域（经营分析/风险管控/资产台账/资债权证/招租/运营/合同/定价计费/收费发票/催缴/巡检维修/任务/盘活/合规/消息/固资/无形/组织/运营/系统配置/系统管理/智能中心/期初迁移，共 40+ 模块页）
- **miniprogram-tenant 微信用户端**：15 页，覆盖 FR-MPU-001~011（授权登录/身份绑定/主页导航/线上缴费/报事报修/招租报名/扫码查资产/消息中心/个人中心/退租申请/电子签约/我的合同与账单明细）
- **miniprogram-worker 微信工作端**：11 页，覆盖 FR-MPW-001~010（任务中心/资产查询/资产收费/资产巡检/资产维修/资产催租/合同审批/清场验收/抄表/现场收款到账确认）
- **h5-tenant / h5-worker React 移动端**：两个 H5 应用（Vite + React + Tailwind 移动端），功能对齐用户端/工作端小程序
- **前端 Monorepo**：新建 `frontend/`，将根目录 Node.js 工程文件（`package.json`、`pnpm-workspace.yaml`、`pnpm-lock.yaml`）、JS 规范文件（`eslint.config.js`、`.prettierrc`、`.prettierignore`）与 `admin-web` 收拢至 `frontend/`，pnpm workspaces 管理
- **后端补齐模块**：固定资产、无形资产、表计档案/抄表、发票税率、水电公摊配置（FR-FA-*/FR-IA-*/FR-UTIL-*/FR-INV-*）
- 本地 JDK 21 工具链（`.tools/`，已加入 .gitignore）

### Changed

- `pom.xml`：新增 spring-boot-starter-aop、mybatis-plus-jsqlparser（分页插件依赖）
- `frontend/package.json`：文档脚本路径改为 `../docs/`，新增 `dev`/`build` workspace 脚本，`"type": "module"`
- `.github/workflows/ci.yml`：前端 Job 增加 `working-directory: frontend`，pnpm 版本升 9，缓存路径指向 `frontend/pnpm-lock.yaml`
- 文档同步：README、ADR-0012、代码规范、技术选型、概要设计、CI-CD、部署手册、文档交付清单中 `admin-web/` 路径与 `pnpm` 命令目录更新为 `frontend/`
- 测试配置：`application-test.yml` 关闭调度、排除 JobRunr 自动配置

- **收费算法与期初迁移（SRS V2.4）**：期初数据迁移（§4.23.28 FR-MIG-*）、收款核销分配（§4.23.29 FR-PAY-*）、首末月不足月折算（FR-PRICE-007/008）、滞纳金计算（FR-DUN-LATE-*）、组合/拆分租赁计费（FR-OPS-006/FR-MDM-004）
- [需求缺陷评审报告](docs/requirements/需求缺陷评审报告.md)：20 项缺陷分级 + 5 组需求补充条目
- 同步更新：DSD V1.6、HLD V1.3、数据库设计 V1.2、用户故事地图 V1.2、开发计划 V1.1、测试计划 V1.1、测试用例（TC-MIG/TC-PRICE/TC-DUN/TC-BIL 新增）
- **Java Spring Boot 后端（ADR-0016~0018）**：JDK 21、MyBatis-Plus、Flyway、JobRunr、springdoc
- `backend/` Maven 脚手架：健康检查、Security、TraceId、Flyway V1 占位
- CI：`mvn verify` + pnpm 文档 lint 双 Job
- 技术选型 V2.0、DSD V1.5、HLD V1.2；混合 Monorepo（Maven + pnpm）

### Changed

- ADR-0010/0011 Superseded；ADR-0012 更新为混合 Monorepo
- K8s API 端口 8080；`pnpm-workspace` 移除 backend

### Added (prior)

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
