# Changelog

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- **项目新增两步走（FR-AST-001）**：
  - 第一步基本信息：项目名称、详细地址、公司、省市区、类型、状态（正常/停用）、图片、经纬度
  - 第二步项目分区配置：分区名称、分区编码、排序、备注（面积改为只读统计，见下）
  - 后端：`project` 表扩展省市区/类型/图片字段，新增 `project_zone` 表；`POST /projects`、`PUT /projects/{id}` 支持「基本信息 + 分区」一次保存，新增 `GET /projects/{id}/zones` 与 `DELETE /projects/{id}`
  - 前端：新增 `ProjectFormPage` 两步向导（`/projects/create`、`/projects/:id/edit`），`ResourcePage` 支持 `createLink`/`editLink` 跳转独立页面
  - 公司下拉改为组织结构树（母公司 → 子公司）选择，支持名称/简称搜索过滤，停用公司置灰
  - 省市区改为真实行政区划数据（31 省 / 342 市 / 3056 区县，含行政区划代码）级联选择并支持按名称或代码搜索；数据按需动态加载（`admin-web/src/data/chinaRegions.ts`，独立分包）
  - 经纬度改为按地址自动解析：新增 `GET /api/v1/map/geocode?address=&city=`（高德 Web 服务地理编码，Key 与签名收敛在服务端，PC/H5/小程序可复用，`AMAP_WEB_SERVICE_KEY` 为空时回退 `AMAP_KEY`）；表单填写详细地址后失焦自动回填，也可点「根据地址获取经纬度」手动触发，结果仍可手动微调
  - 前端抽出共享模块 `admin-web/src/lib/amap.ts`（高德脚本单例加载 + 地图配置 + 地理编码），`AssetMapPage` 复用同一加载器
  - **分区面积改由资产统计得出**：移除手工填写的「面积」字段（`project_zone.area` 已删列），`GET /projects/{id}/zones` 返回只读的 `assetArea`（分区下资产面积合计）与 `assetCount`
    - 资产新增「所属分区」`asset.zone_id`：资产表单「所属项目 → 所属分区」联动下拉，资产列表增加「分区」列
    - `ResourcePage` 表单字段支持依赖型远程下拉（`optionsDependsOn` + `optionsPath` 的 `{字段名}` 占位），依赖字段变更时自动重载并清空已选值
    - 分区保存改为按 id 增量写入（不再先删后插），避免分区 id 变化导致资产 `zone_id` 失效；被删除分区的资产归属自动置空
    - 分区「用途」字段去除（`project_zone.usage_type` 已删列）：与资产自身「用途」字段重复，维护口径以资产为准
- **资产管理字典补充（系统管理 → 系统字典 → 资产管理字典）**：
  - 新增字典「资产用途」`asset_usage`：办公 / 商业 / 住宅 / 车库
  - 新增字典「资产户型」`asset_house_type`：一室一厅 / 两室一厅 / 三室两厅 / 四室两厅
  - 资产新增「户型」字段（`asset.house_type`）；资产台账的「用途」「户型」改为引用上述字典的下拉选择，列表增加对应列并回显中文
  - 新增字典「建筑规划」`building_plan`（住宅/商业/办公/工业/公共/综合建筑）；「资产户型」字典更名为「资产房型」以对齐业务口径
- **资产新增/修改表单重构（独立分组表单页 `/assets/create`、`/assets/:id/edit`）**：
  - 归属信息：产权公司、资产公司（组织结构树，可搜索）→ 项目（按资产公司级联，可搜索）→ 分区（按项目级联）
  - 基本信息：资产名称、资产编号、资产坐落、分区楼层、资产面积(㎡)、租赁面积(㎡)
  - 资产属性：资产类型 / 部分租赁状态 / 资产性质 / 资产房型 / 资产用途 / 资产来源 / 资产权属 / 建筑规划 / 建筑结构，全部取自「资产管理字典」，并提供「维护字典」入口，字典项补充后表单自动同步
  - 管理信息：登记入库时间、原值(万元)、责任部门（按资产公司级联其下属部门）、责任人（按责任部门级联其下员工）
  - 计量与图片：水表号、电表号、资产图片（缩略图 + 点击预览 + 可删除）
  - 后端：`asset` 新增 `asset_company_id`、`partial_lease_status`、`asset_nature`、`building_plan`、`floor_no`、`lease_area`、`registered_at`、`responsible_department_id`、`responsible_user_id`、`image_url`、`image_file_id`
  - 服务端级联校验（不依赖前端联动，防越权写入）：项目须属于所选资产公司、分区须属于项目、责任部门须属于资产公司、责任人须属于责任部门
  - 列表/详情回显名称：新增 `assetCompanyName`、`propertyCompanyName`、`responsibleDepartmentName`、`responsibleUserName`，避免表格露出裸 ID
  - 存量数据回填：`asset_company_id` 按 `operating_company_id` 补齐
  - 资产公司作为表单权威归属，保存时同步镜像到 `operating_company_id`，保证数据范围隔离 / 经营看板 / 监管报送口径一致（`operating_company_id` 为既有锚点，被 6 个模块引用）
  - 前端抽出共享模块 `lib/org.ts`（组织结构公司树 + 列表归一化）与 `lib/upload.ts`（附件上传 + 图片校验），`ProjectFormPage` 改为复用，消除重复实现
- **全局公司切换（页头公司下拉）**：
  - 页头「资管云平台 · 管理后台」旁新增公司下拉（组织结构树、可搜索），切换后**全局生效**：路由页面整体重挂载，所有列表 / 统计 / 看板按新公司范围重新拉取
  - 前端 `lib/company.tsx`（`CompanyProvider` + `useCompany`）保存当前公司与 `scopeVersion`；`lib/api.ts` 统一在请求头带上 `X-Company-Id`，登录/登出清理选择，避免跨会话串数据
  - 后端 `JwtAuthenticationFilter` 读取 `X-Company-Id` 并交由 `RbacService` 校验，**服务端强校验**：仅允许切到本公司及其下级（或数据范围不限的账号可切全部）且公司须为启用状态，越权头一律忽略并回落默认范围，无法通过改请求头提权
  - `RbacService` 新增 `isSwitchable` / `switchableCompanyIds` / `applyCompanyScope`，`companyScope` 区分「已切换 / 未切换」，并把公司范围过滤铺到 asset、plan、task、alert、fixedasset、org 等各业务服务
  - 新增 `GET /api/v1/auth/companies` 返回可切换公司、归属公司与当前选中公司，供下拉初始化
  - 演示数据按公司分布（V37），使公司切换有可观察的数据差异
- **项目管理：统计维度 + 卡片视图**：
  - 列表与统计条补充「项目数 / 资产宗数 / 资产利用率 / 闲置宗数」，并新增「资产面积、盘活宗数」列
  - 新增 `GET /api/v1/projects/summary`，统计条与列表共用同一套筛选条件实时聚合，不写死数字；`ResourcePage` 新增 `statsSource` 配置项，任意资源页可接入动态统计
  - 新增「列表 / 卡片」视图切换（`ResourcePage` 的 `card` 配置）：卡片展示项目图片、名称、类型角标、资产数、面积、盘活宗数、闲置总数与地址
  - 统计口径统一收敛到 `AssetLeaseGroups`（闲置 = 空置 + 招租中，盘活 = 在租 + 部分出租），列表 / 统计条 / 卡片 / 详情页共用一套定义，避免多处各算一套导致对不上账
- **项目详情页（`/projects/:id`，对齐设计稿）**：
  - 新增 `GET /api/v1/projects/{id}/overview` 一次返回项目主体 + 三类指标 + 分区汇总：资产信息（封面、所属公司、地址、资产状态 / 类型 / 经营状况、二维码、创建时间）、资产基本信息（利用率、固定资产、租控状态与资产类型环形图）、资产创收（累计 / 本年实收 + 近一年每月实收）、租赁概况（出租率、上月收缴率、实际出租 / 未出租、上月应收 / 实收）
  - 分区与楼层区：左侧分区导航（含「全部分区」）、随分区联动的 KPI 卡、租控状态图例 chips（点击即筛选）、「按楼层 / 列表」两种展示；楼层分组展示各层面积与宗数，并可直接进入资产「一物一档」
  - 聚合策略：分组 SQL 覆盖「项目总计 + 分区维度 + 租控状态分布」，跨月实收在内存归月，未引入自研 join mapper；无分区归属的资产单独归入「未划分区」，保证各区合计与项目总数对得上
  - 统计口径刻意复用既有定义：出租率按宗数 `(在租 + 部分出租) / 总数`（与 `DashboardService#leasedRate` 一致）；实收按核销时间 `bill_payment.allocated_at` 归月（现金实现制），收缴率按账单维度 `已核销本金 / (应收 - 减免)`，两者各自可对账
  - 项目列表行点击 / 卡片点击 / 「详情」按钮统一进入详情页（`detailLink`）
  - 新增共享组件 `components/charts.tsx`（内联 SVG 环形图 / 柱状图 / 占比条 / 图例）与 `components/CoverImage.tsx`（封面 + 占位图），未引入图表库
  - 资产接口补充 `projectId` / `zoneId` 过滤，供详情页底部取数
- **资产台账筛选项优化**：「项目属性」「资产权属」改从「系统字典 → 资产管理字典」取值；「来源类型」按「项目属性」级联过滤（土地类 → 出让 / 行政划拨 / 其他，房产类保持原有口径），字典维护后筛选项自动同步
- **资产图片公开访问**：新增 `GET /api/v1/files/object/{*objectKey}` 提供对象存储公开读（`/api/v1/files/object/**` 放行匿名），修复上传图片在 `<img>` 中无法展示的问题；安全性由不可猜测的 `objectKey`（随机 UUID）与路径穿越防护保证
- **演示数据补齐**：`DemoProjectImageSeeder` 为缺少封面的项目生成 SVG 封面并入库；V38 为「洪泽湖畔商业综合体 / 湖滨商务区」补充一栋多楼层商务大厦（1F/2F/20F/23F/24F/25F，覆盖在租 / 招租中 / 空置 / 部分出租），使详情页「分区 → 楼层」分组与面积筛选具备真实分布
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
- **后端补齐模块**：固定资产、无形资产、表计档案/抄表、发票税率、水电公摊配置（FR-FA-_/FR-IA-_/FR-UTIL-_/FR-INV-_）
- 本地 JDK 21 工具链（`.tools/`，已加入 .gitignore）

### Fixed

- **资产可选字段无法清空**：`PUT /assets/{id}` 原先仅有 `zone_id` 做了显式置空处理，其余字段因 MyBatis-Plus 默认更新策略跳过 `null`，导致「用途 / 户型 / 来源 / 权属 / 结构 / 省市区 / 地址 / 租金」等一旦填写便无法清空。现统一改为显式 `SET` 写入，支持下拉「清空」操作
- **资产并发修改被静默丢弃**：`updateAsset` 未检查 `@Version` 乐观锁命中行数，版本过期时更新 0 行却仍返回 200（实际未生效）。现命中 0 行返回 `40900 状态冲突，请刷新后重试`
- **资产列表 500**：责任部门/责任人名称回填用 `Map.of()` 作空集合兜底，而 `Map.of()` 是 ImmutableCollections，`get(null)` 会抛 NPE —— 存量资产均未设置责任部门，导致列表在无责任部门数据时整体报错。改用 `Collections.emptyMap()`

### Changed

- `pom.xml`：新增 spring-boot-starter-aop、mybatis-plus-jsqlparser（分页插件依赖）
- `frontend/package.json`：文档脚本路径改为 `../docs/`，新增 `dev`/`build` workspace 脚本，`"type": "module"`
- `.github/workflows/ci.yml`：前端 Job 增加 `working-directory: frontend`，pnpm 版本升 9，缓存路径指向 `frontend/pnpm-lock.yaml`
- 文档同步：README、ADR-0012、代码规范、技术选型、概要设计、CI-CD、部署手册、文档交付清单中 `admin-web/` 路径与 `pnpm` 命令目录更新为 `frontend/`
- 测试配置：`application-test.yml` 关闭调度、排除 JobRunr 自动配置

- **收费算法与期初迁移（SRS V2.4）**：期初数据迁移（§4.23.28 FR-MIG-_）、收款核销分配（§4.23.29 FR-PAY-_）、首末月不足月折算（FR-PRICE-007/008）、滞纳金计算（FR-DUN-LATE-*）、组合/拆分租赁计费（FR-OPS-006/FR-MDM-004）
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
