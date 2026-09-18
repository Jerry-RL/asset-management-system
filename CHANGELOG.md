# Changelog

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- **资产运营人员管理（V60）**——「运营管理」目录改名为「资产运营人员管理」并补齐同级子模块，登记「谁负责哪些资产的运营」：
  - **三张新表**：`asset_operator`（一人一份档案：`user_id` / `status` / `remark` + 软删）、`asset_operator_role`（角色选择，多对多）、`asset_operator_scope`（资产运营范围）。范围沿用 V56 抵押记录的三值口径 `scope_type`（project / zone / asset）+ `scope_id` —— **不用三个可空列**：可空列的组合唯一索引里两列恒为 NULL，而 PG 把 NULL 视为互不相等，去重会静默失效。实测 `project:3` 与 `asset:3` 并存、同类型同 id 重复被拒
  - **一人一份有效档案**用**部分**唯一索引 `uk_asset_operator_user ... WHERE deleted_at IS NULL`：软删后允许重新登记（普通唯一索引会让该人员永久占位），而未软删的行仍然互斥
  - **角色只登记、不授权**（已确认口径）：本模块不写 `user_role`，不产生任何实际权限；真正授权仍在「系统管理 → 角色权限」。若在这里同步写 `user_role`，一个业务模块的页面就等于多出一条提权路径，而它的权限码不是提权类权限码。**范围只登记、不拦截**：本期不接入 `RbacService` 数据范围，表结构已按可扩展口径建（类型 + id 分开存），后续接入不需要改表
  - **后端**：`AssetOperatorController` 提供 `GET /asset-operators`（列表：支持按**人员姓名 / 手机 / 账号**、**备注**、**运营范围标的**（项目 / 分区 / 资产名）三类关键字检索）、`GET /{id}`（详情：比列表多返回范围明细与标的名）、`POST` / `PUT /{id}` / `PUT /{id}/status` / `DELETE /{id}`（软删），以及**三个独立的下拉端点** `user-options` / `role-options` / `scope-options`。下拉单独开是因为人员 / 角色 / 资产分别要 `org.user:view` / `system.role:view` / `asset.ledger:view` 三个**别的**菜单权限，复用既有端点会让缺任一项权限的人选不出东西（与 V55 调拨记录、V56 抵押记录同一取舍）；权限码固定 `ops.assetOperator:view|create|update|delete`
  - **校验**（唯一一份实现）：人员须存在且启用、至少一个**启用**角色、至少一条范围、范围标的须存在（分区额外校验其项目仍存在、资产须未退出）、同一人员不得重复登记；角色与范围都是**全量替换**（先删后插），编辑两次相同内容幂等
  - **前端** `AssetOperatorsPage`（`/asset-operators`）：列表 + 弹窗表单（人员远程搜索 / 角色多选 / 运营范围可增删的「类型 + 标的」联动行，支持三类混选）+ 详情抽屉。范围候选的**公司取自所选人员**的所属公司（不加独立的公司选择框：让人手工再选一次公司只会制造「选的人与范围不属于同一家公司」的脏组合）；人员没有所属公司时明确提示并禁用范围选择，而不是退化成一个不筛公司的候选列表。切换范围类型会清空已选标的 —— `project:3` 与 `asset:3` 是完全不同的对象
  - **菜单与权限**：「运营管理」目录**只改 `name`**（`menu.code` 仍是 `ops`）—— 改 code 会同时打断 `role_permission.menu_code` 与前端 `pathToCode` 镜像（V58 为改名的完整清单立过规矩）；新页 `ops.assetOperator` 挂在 `ops` 下、sort 5 排在「租户管理」之前。权限回填**只给 `operator` 且只回填 `view`**：本页能给人登记角色，属权限面入口，与 `org.user` 同级敏感（V45 §5.1 已把 `org.user` 排除在通用回填之外），故不用 V55/V57/V59 的「所有非超管角色」口径；写动作一律不回填
  - 前端路由 / `STANDALONE_ROUTES` / `PATH_TO_CODE` 镜像 / 侧栏图标 / 用户手册同步（`check-perm-invariants` 通过）；`docs/api/openapi.yaml` 补齐本模块全部端点与 `AssetOperatorInput` / `AssetOperatorScopeRef` schema，并顺带补齐 V59 遗漏的招租端点（`/lease-listings/{id}`、`/{id}/resubmit`、`/{id}/close`）与 `LeaseListingPublish` 表单 schema（`pnpm api:lint` 通过）
  - 测试：新增 `AssetOperatorServiceTest` 17 例（含「范围去重按 (类型,id)，project:3 与 asset:3 不能被吃掉」、停用角色 / 已退出资产 / 孤儿分区 / 非法类型拒绝、编辑全量替换、软删可见性、标的名缺失回落占位）、`AssetOperatorPermissionTest` 7 例（含「三个下拉只要求本模块 `:view`」与字面量段不被 `/{id}` 抢匹配）、`V60AssetOperatorMigrationContractTest` 8 例（列集恰好、部分唯一索引、不写 `user_role`、目录只改名、权限只回填 operator）；后端全量 678 例通过；60 个迁移在 PostgreSQL 15 全链应用通过，并实测幂等重跑与三条唯一约束
  - **顺带修的缺陷**：`AssetOperatorService` 详情对可空键取值时用了 `Map.of().get(null)`（`ImmutableCollections` 对 null 键抛 NPE）—— 人员没填「所属部门」时一打开详情就 500。收敛为可空安全的 `lookup()` 并覆盖全部取值点（仓内 `AssetService#fillResponsibleNames` 已为同一个坑立过注释）
  - **新增守卫** `scripts/check-source-encoding.mjs`（并接入 CI）：禁止 UTF-8 BOM。`javac` 见到 BOM 会报「非法字符: '\ufeff'」并**让整个 backend 编译失败**，而 BOM 是零宽字符、编辑器里看不见 —— 本仓已发生两次（V57 的 `DisposalRecordController`、V60 的 `AssetOperatorService`），两次排查成本都远高于这条检查


- **招租发布审批 + 资产租赁管理（V59）**——「发布招租」从「直接生效」改为「提交审批 → 通过后小程序端可见」，并新增按租控状态分 Tab 的资产租赁管理页：
  - **`lease_listing` 扩展（V59）**：新增 `rent_type` / `annual_rent` / `cover_image_file_id` / `cover_image_url` / `detail_images`（JSON 文本）/ `recommended` / `sort_no` / `intro` / `reject_reason` / `created_by` 共 10 列，`status` 由二值（`active` / `closed`）**扩为四值**：`pending` 待审批 / `active` 招租中 / `rejected` 已驳回 / `closed` 已关闭（旧取值语义不变，存量数据无需转换）。新增 `idx_lease_listing_pub_sort`（`status, recommended DESC, sort_no ASC, id DESC`）供端上列表直接走索引
  - **发布闭环**：`POST /lease-listings` 由「直接置 active + 租控转 leasing」改为**提交审批**（落 `pending` + `ApprovalEngine.start('lease_listing', id)`）。`biz_type='lease_listing'` 的流程定义随迁移落库；审批通过置 `active` 并 `publishedAt`，驳回把**审批意见写入 `reject_reason`**。新增 `POST /lease-listings/{id}/resubmit`（仅 `rejected` 可重报）、`GET /lease-listings/{id}`（招租字段 + 资产详情 + 发起人信息）、`GET /lease-listings?assetId=` 过滤
  - **租控由派生而非直写**：审批通过后改调 `LeaseStatusDeriver.refresh(assetId)`，不再走 `LeaseControlService.transition` —— ADR-0019 之后 `v_asset_lease_status_derived` 才是租控真源，且它正是按 `lease_listing.status='active' AND asset_unit_id = u.id` 判定 `leasing`。因此发布的招租必须挂到**计租单元**（`AssetUnitService.resolveForLease`），否则派生视图看不到新发布的招租，表现为「审批通过了但资产还是空置」
  - **审批意见进事件**：`ApprovalCompletedEvent` 新增 `comment`（保留三参构造兼容存量发件箱事件）。此前审批意见只落在 `approval_task.comment`，业务侧拿不到，「驳回填写原因」在界面上只能是空的
  - **租金量纲**：表单只收**年租金**（`annual_rent`），提交时按 12 折算写入 `rent_amount`（「元/月」的既有列，小程序与底价校验的口径），底价校验改为「年租金 vs 底价×12」——直接拿年租金对比月底价会让校验形同虚设
  - **提交前置校验**：仅空置资产可发起（`assertVacant`）、同一计租单元不得有进行中的招租（否则产生重复的发布审批）、低于底价年化额且未勾选可议价时 409
  - **前端新页 `AssetLeasingPage`（`/asset-leasing`，`operation.assetLeasing`）**：Tab = **资产租控状态**（全部 / 招租中 / 租赁中 / 自用中 / 空置 / 部分出租），列表是**资产**；行内「发布招租」（资产由行注入，需求「在列表中操作无需选择」）/「招租记录」（抽屉列出该资产全部发布审批记录，含驳回原因与重新提交）/「详情」；招租详情弹窗才露出**资产详情与发起人信息**
  - **表单能力下沉**：`ResourcePage.FieldConfig.type` 新增 `image` / `images`（`ImageUploadField` / 新增 `MultiImageUploadField`），并导出 `ResourceFormField` 供独立页复用；发布表单字段集中定义在 `lib/listingFields.ts`，三处入口（资产台账行内 / 招租管理新增 / 资产租赁管理弹窗）共用一份配置
  - 「招租管理」列表补齐资产编号 / 名称 / 封面 / 租金类型 / 年租金 / 推荐 / 排序 / 审批状态 / 驳回原因与状态筛选；`BIZ_TYPE` 与新增 `LISTING_STATUS` 标签映射、菜单 / 路由 / `PATH_TO_CODE` 镜像 / 侧栏图标 / 用户手册同步
  - 端侧（小程序 `miniprogram-tenant` / H5 `h5-tenant`）改按**年租金**展示（`¥…/年`，不再误用月均 `rentAmount`），并展示封面图、推荐标识与介绍
  - 测试：新增 `LeaseListingPublishApprovalTest` 13 例（提交只落 pending 且不刷租控、年租金→月均换算、底价年化拦截与可议价放行、同单元重复招租拦截、通过/驳回的落库与刷新、监听器幂等与 bizType 隔离、重报状态守卫、详情携带资产与发起人且发起人缺失不 500）；后端全量 640 例通过；59 个迁移在 PostgreSQL 15 全链应用通过，并实测「pending → 派生 vacant、active → 派生 leasing、closed → 回落 vacant」
  - **顺带修复**：`DisposalRecordController.java` 文件开头带 UTF-8 BOM，导致 `javac` 报「非法字符: '\ufeff'」、**整个 backend 无法编译**（V57 提交遗留），已去掉 3 字节 BOM

- **资产处置记录（V57 / V58）：处置完成后资产脱离原产权公司**——补齐「处置了哪些资产、从哪家公司处置出去」的可见性与归属收口：
  - 新增菜单「资产处置记录」（`deed.disposalRecord`，挂「资债权证记录」目录下，路径 `/disposal-records`）：以**资产**为行的只读台账，展示所有被处置的资产，支持按**原产权公司** / 处置对象（资产 / 项目 / 分区）/ 处置方式筛选与资产编号 / 名称搜索。**V58 调整**：该菜单原在「资产运营」下（V57），因它属「记录 / 台账」而非「发起操作」，移入「资债权证记录」目录；`menu.code` 随目录改名（`operation.disposalRecord` → `deed.disposalRecord`）并同步 `role_permission.menu_code`（反范式冗余列，不同步会让按码查询得到空集），`path` 保持不变
  - 新增表 `asset_disposal_record`（一行 = 一个被处置的资产）：快照处置时资产的**原产权公司 / 原经营公司**、处置方式、金额与单位、日期、处置人、备注，并记录处置对象（`asset` / `project` / `zone`）与来源单据（资产级 `disposal_order.id`、项目 / 分区级 `biz_disposal_record.id`）。**无软删列**——处置不可逆，台账只增不减；`uk_asset_disposal_record_asset` 唯一索引同时充当幂等键与并发兜底
  - 级联处置（`AssetDisposalRecordService.cascadeDispose`，唯一写入口）：把资产置 `ownership_status='disposed'`、`lifecycle_status='exited'`、**清空 `property_company_id`**（经营公司刻意保留，资产仍留在原经营主体的数据范围里）；已退出的资产被跳过，重复级联不产生第二批副作用
  - 两个触发点：① 资产级处置单**完成**（`DisposalService.complete`）——以 `disposal_order` 的处置方式 / 金额（元）/ 日期 / 处置人写台账；② 项目 / 分区**新增**一条处置台账（`RecordSheetService.syncDisposalRecords`）——逐资产展开级联。编辑 / 删除处置台账**不**回滚资产状态（要把产权公司还回去得走权属流转重新登记）
  - **存量回填**：迁移把 V57 之前已完成的**资产级**处置单按其最新一张补写台账并同步资产状态（旧规则没有动产权公司，不回填则菜单对存量数据为空、模型也不一致）；**刻意不回填项目 / 分区级** `biz_disposal_record`——旧语义下它只是登记台账，按行反向处置整个项目会产出未经复核且不可逆的批量变更
  - 跨模块依赖处理：级联端口 `DisposalCascadePort` / `DisposalCascadeSnapshot` 定义在**调用方** `record` 模块、由 `disposal` 实现，使依赖保持单向（`disposal → record`），不引入 `record ↔ disposal` 包级环
  - 后端只读端点：`GET /api/v1/disposal-records`、`GET /api/v1/disposal-records/{id}`（权限码仅 `deed.disposalRecord:view`；刻意不提供写接口——处置记录是已发生事实的快照，可编辑意味着可以伪造处置历史）
  - 前端：`DisposalRecordsPage` + `lib/disposalRecord.ts`（金额与单位成对展示：资产级是元、项目 / 分区级是万元，不做换算）；菜单 / 路由 / `PATH_TO_CODE` 镜像 / 侧栏图标 / 用户手册同步
  - 测试：新增 `V57AssetDisposalRecordMigrationContractTest` 7 例（列集恰好、唯一索引、菜单与 view 回填、存量回填口径）、`AssetDisposalRecordServiceTest` 8 例（先快照后清空、已退出跳过、乐观锁冲突、项目 / 分区逐资产展开、名称批量回填）、`DisposalCompleteCascadeTest` 2 例（完成即级联、状态非法不级联）、`V58MoveDisposalRecordMenuMigrationContractTest` 5 例（改名 + 改挂同句完成、必须用 `UPDATE ... FROM` 而非标量子查询、`role_permission.menu_code` 同步且不动 `menu_id`、不顺带授权、纯 UPDATE 幂等）
- **修复：已处置的资产无法再登记处置记录**——`exited` 是租控终态且 `canTransition` 既不允许自迁移也不允许迁出，而「处置完成」与「项目 / 分区级级联」都会把资产置为已退出，导致第二次处置在审批通过（→ `disposing`）或完成（→ `exited`）时 409。新增 `LeaseControlService.transitionUnlessExited`：**资产当前已是 `exited` 时跳过租控写入**（无状态变化即不留日志行），`DisposalService.onApproved` / `complete` 改走该入口；豁免**只**针对终态，`leased → disposing` 仍然拒绝（"在租资产须先退租才能处置"这条真实规则不受影响）
  - 测试：新增 `LeaseControlTerminalExemptionTest` 8 例（含反向用例「在租 → 处置中仍 409」与根因固化）、`DisposalReDisposalTest` 3 例；后端全量 622 例全绿
  - **本次未交付**：改造清单 [触点 7](docs/design/资产单元与占用模型改造清单.md) 要求的「处置转 `AssetOccupancyService.occupy`」（当前资产级仍走 `LeaseControlService.transition`，项目 / 分区级级联不写租控状态，只写 `lifecycle_status`）；处置记录台账的**导出**与**资产档案时间线**接入
  - **已确认口径（产品已签字）**：同一资产被**多次**处置时，台账保持「一行 = 一个被处置的资产」—— 第二次处置**不**新增行也**不**改写既有行（保留首次处置信息）。理由：需求原文是「展示所有**被处置的资产**」，以资产为行才是直译；「每次处置分别留痕」由租控日志、审批中心与 `disposal_order` 自身承担，台账只回答「哪些资产被处置了、从哪家公司处置出去的」
- **后续记录不再提供「新增处置记录」入口**（三种主体一致）：处置是**由处置动作产生的事实**，不是可以随手追加的台账行 —— 面板上点几次就能造出多张处置卡，会让「资产处置记录」台账与真实发生过的处置脱钩。因此摘掉 `RecordSheetSections` 处置段的「新增」按钮，记录一律由业务动作写入（资产走「资产处置」模块，项目 / 分区走后端 `record-sheet` 的处置段）
  - **保留**：已存在的记录仍可读、可改草稿（资产侧）、可删除；**资产侧四个流程按钮（提交审批 / 审批通过 / 执行 / 完成）必须保留** —— 它们是前端**唯一**的处置状态机入口（全仓唯一调用点），摘掉会让处置流程不可达
  - 空态文案按主体区分：资产指向「资产处置」模块；项目 / 分区说明新增入口已下线
  - **已知后果（已确认接受）**：项目 / 分区处置记录失去唯一的 UI 录入入口；后端 `record-sheet` 处置段与级联逻辑不变（项目 / 分区级联处置仍走该路径），前端入口待后续产品补充


- **计租单元拆分/合并落地（FR-MDM-001~004、FR-OPS-006）**——「资产支持部分租赁」的最小可用能力：
  - 后端 `AssetUnitController`：`GET /assets/{assetId}/units`、`POST /assets/units/{unitId}/split`、`POST /assets/units/merge`；新增 DTO `AssetUnitSplitRequest` / `AssetUnitMergeRequest`
  - 后端 `AssetUnitService.merge`（**新增能力**）：把同一资产下多个空置单元合并为一个；保留排序最靠前的单元、其余软删，面积与底价**相加**；前置按**占用表**判定（无未收口占用含预留、无生效招租、资产未在押）
  - 后端 `AssetUnitService.split` 修正：① 面积为 0 的占位单元**一律拒绝**（原实现会跳过面积守恒校验，可突破面积预算）；② 底价与可租面积**按面积比例分摊**，不再整份复制（原实现会让拆分后底价总额放大 N 倍）；③ 容差由 0.01 收紧到 0.005；④ 补权属负担互斥校验
  - 拆分/合并均写 `asset_structure_log`（`op_type` 为 `unit_split` / `unit_merge`，单元粒度信息入 `mapping_json`），满足 FR-MDM-003「任意时点可查资产结构树」
  - 前端 `AssetUnitPanel`（单元面板：勾选合并 + 逐行拆分，权限自判 `asset.ledger:update`，支持 `compact` 紧凑模式）+ `AssetUnitSplitModal`（面积列表拆分、实时守恒校验、「均分」一键填充）+ `lib/assetUnits.ts`
  - 入口（按钮）落在三处：① **资产台账列表行的展开行**（`ResourcePage.expandable`，展开控制列在最左侧，可就地查看并拆分/合并，主路径）；② 资产台账操作栏「更多 → 计租单元」（弹窗形态，覆盖卡片视图与窄屏 —— 展开行只在列表模式生效）；③ 项目分区管理（`ZoneAssetPane`）资产行的展开行与「更多 → 计租单元」弹窗。三处共用同一个面板组件与同一个行转换函数 `toAssetUnitOwner`，避免多份实现漂移
  - 权限复用既有 `asset.ledger:view` / `asset.ledger:update`：**刻意不新开权限码** —— `PermissionRegistry` 会校验注解里的菜单码在 `menu` 表中存在，新开码会让接口对所有角色 403 且应用启动即失败
  - 测试：新增 `AssetUnitSplitMergeTest` 13 例（守恒、占位单元拒绝、分摊、占用/招租/抵押前置、跨资产拒绝、去重、结果单元选取）；回归 `AssetServiceZoneTest`/`OccupationServiceTest`/`SelfUseServiceTest`/`MigrationServiceLeaseControlTest`/`AssetOccupancyServiceTest` 共 53 例全绿
  - **本次未交付**（详见 [改造清单](docs/design/资产单元与占用模型改造清单.md)）：拆合**幂等键**、**预演（dry-run）**、**可回滚窗口判定**、拆合审批、资产层拆合重做（触点 15）、以及**合同接入单元与占用**（触点 8，是"合同挂到单元上"能否生效的阻塞项——当前单元可拆，但合同侧尚未写 `asset_unit_id`）

- **拆分合并规则设计（FR-MDM-001~004、FR-OPS-006）**：
  - 新增 [ADR-0021 资产与计租单元拆分合并规则](docs/adr/0021-asset-split-merge-rules.md)：厘清系统内三套「拆分/合并」（资产层 / 计租单元层 / 组合租赁）的语义边界，明确**部分租赁的唯一载体是计租单元层**；定单元拆分规则 S1–S11、单元合并规则 M1–M7、资产层语义收窄规则 A1–A9、接口契约与幂等（决策 A–H）
  - 记录 19 项缺陷 D-01~D-19（含 4 项 P0：资产层拆合绕过单元与占用、合并后源资产单元未收口导致「影子可租」、合并结果编码违反 FR-MDM-003、拆合判定只看派生列不看占用表），以及 **D-05：合同链路未接入单元/占用**（`contract.asset_unit_id` 从未写入、合同生效不写占用，状态被 `LeaseStatusDeriver` 回滚）
  - [领域划分与限界上下文设计](docs/design/领域划分与限界上下文设计.md) V1.2：新增 §7.4 拆分与合并（三层语义 + 拆合规则）、不变量 **INV-13~INV-18**、验收 SQL ⑩~⑬ 与标准 9~13；修正术语表 `AssetUnit` 的「不可再分」措辞；§2.1 现状诊断补「写入路径未切换」的准确表述
  - [资产单元与占用模型改造清单](docs/design/资产单元与占用模型改造清单.md)：触点 15 升级为**整段重做**，新增触点 33~42（单元合并、单元端点、守恒修正、幂等、确定性编码、汇总过滤、前端入口、拆合审批、拆合预演、回滚窗口检测）、新增 §2.6「拆分合并专项缺陷 → 触点映射」、验收 SQL ⑩~⑭，并明确**触点 8 必须先于拆合施工**的依赖顺序
  - **补充规则（初稿遗漏，经复核补入）**：① 单元合并 `merge` 是拆分 `split` 的逆操作，**必须同批交付**（S12）——只做拆分等于提供一条单向、不可撤销的高危操作；② 资产层拆分的**切分线必须与单元边界对齐**、单元不得跨子资产（A10）——否则会切出半个在租单元而该单元不可再拆；③ 单元归属迁移须与结构变更**同事务**（A11）；④ 拆合必须**先预演再提交**（dry-run 返回影响面）
  - **评审更正**：初稿 D-07 曾推断「`assetCompanyId` 为空 → RBAC 数据范围失效」，经逐处核对为**错误结论**（`OwnershipResolver.ofAsset` 读的是 `operating_company_id`，而 `cloneAsset` 已复制该字段；级联校验亦带空值守卫）。已修正为准确后果（名称回显、调拨/抵押按公司筛资产漏行、抵押记录归属为空、面积预算基准改变），并在 ADR 中补「核查更正记录」与本条评审纪律：结论须追到「文件:行号」，无证据的因果链标注「待验证」
  - **ADR-0021 决策 I「占用写入与收口必须对称闭合」**：合同占用的 `dateTo` 为开区间（无固定期限），**只写不收口**会因 `EXCLUDE` 使单元**永久锁死**，或使**续签/转租直接失败**（新旧占用区间重叠）——**严格比现状更糟**。因此"合同接单元/占用"不是"在 `activate` 里加一行 `occupy`"，而是被迫先定义整条合同生命周期的**占用区间语义**（提交审批 → 生效 → 续签/转租 → 退租/终止）。新增 4 项**条件性 P0** 缺陷 D-22~D-25（当前不触发，切片 2 落地即触发）、INV-19、CR 自检问法「**这条占用行，谁会把它关掉？**」
  - **新增切片 0（零风险，最先做）**：`LeaseStatusDeriver.refreshReconciled` 与 `v_asset_lease_status_reconcile` 查询**已实现但全库无调用方**（`platform/job` 下也没有）→ 当前状态漂移**既不为零、也不可见**。先接成**只读统计 + 告警**（不改数据），把不可见变成可观测数字
  - 改造清单新增触点 43~49（只读比对接线、合同侧四处收口、合同生命周期区间语义定义、CR checklist）、验收 SQL ⑮⑯，并明确 **触点 44~47 必须与触点 8 同批交付**
  - **新增 [资产拆合落地实施方案](docs/design/资产拆合落地实施方案.md)**：把规则与缺陷转成可执行方案——批次 B0~B6（每批含改动文件级清单、验收信号、回退方式、预估）、数据回填顺序（**前置冲突检测，避免回填被 `EXCLUDE` 拒绝**）、灰度开关表、回滚预案矩阵、测试方案（单测/迁移契约/真库集成/验收门禁）、5 项待决决策及**截止时间**、附录（验收 SQL 汇总、文件清单、不变量、CR 必答项）
  - **新发现 D-26（条件性 P0）：合同到期也不收口占用**。根因：`v_unit_lease_status`（`V42:29-73`）**完全不引用 `contract` 表**，只读 `asset_occupancy`/`lease_listing`/`asset.lifecycle_status` → 开区间占用永远命中 `CURRENT_DATE <@ daterange(...)` → **合同已到期而单元永久显示在租**。因此"对称闭合"收口清单为**五处**（驳回/撤回、退租完成、终止作废、续签转租、**到期**），验收 SQL ⑮ 的终态集合须补 `expired`；改造清单新增触点 50（到期收口）、51（只读 `detectReconcile`）
  - 待决项：`asset.partial_lease_status` 去留（ADR-0021 决策 E，需产品签字）

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
