# ADR-0020: 限界上下文划分与聚合边界

- **状态**：Proposed
- **日期**：2026-09-11
- **决策者**：技术架构组
- **修订**：2026-09-11 — 依[领域划分与限界上下文评审报告](../design/领域划分与限界上下文评审报告.md)修正上下文清单，并增补**决策 E（占用预留态）**与**决策 F（事件 outbox）**
- **关联前置**：[ADR-0019](./0019-asset-unit-and-occupancy-model.md)（本文补充其未明确的聚合边界、上下文归属与审批期互斥语义；两者冲突时以本文为准）

## 上下文

系统已按 `com.ams.modules` 下 35 个模块实现，模块划分基本对齐 **PC 后台菜单**（`asset`/`lease`/`contract`/`billing`/`invoice`/`dunning`/`maintenance`/`task`…）。这一划分在业务扩张中暴露三个结构性问题：

1. **模块按菜单分包，不按领域。** "资产状态"被 6 个模块直写；业务规则散落，改一个字段需全库检索。
2. **资产只有一层实体，下游全部挂 `asset_id`。** 见 ADR-0019；其决策 A/C 已建立"资产 → 计租单元 → 占用"三层模型（V39–V41 已交付扩展层），但**未明确聚合边界**：`AssetUnit` 属于 `Asset` 聚合还是独立聚合，直接决定并发隔离与不变量落点。
3. **依赖可以向上。** 现状 `billing → contract`、`AlertService → ContractMapper/BillMapper`、`ComplianceService → contract/lease`、`BusinessPlanService → dashboard`、`OpsCalendarService → occupation/selfuse` 共五处反向依赖，合同域改字段会震到账单域与预警，无法独立演进。

此外还有两个 ADR-0019 未覆盖的空洞，经评审确认：

- **审批期不互斥**：`AssetOccupancyService.occupy` 的 Javadoc 明确"调用时机：单据**审批通过**时（不是提交时）…审批中占用的面积预留属于合同链路的校验职责（见 `LeaseAreaGroups.AREA_HOLDING`），不进入本表"。因此审批中的合同不写占用、**不参与 `EXCLUDE` 判定**，两个 `approving` 合同可同时通过并签同一单元——"约束优先于纪律"在审批期不成立。
- **投影无投递保证**：ADR-0013 为进程内 `afterCommit` 事件，提交后进程崩溃即永久丢失，下游只读投影将陈旧且不可发现。
- **组合租赁释放缺陷**：`AssetOccupancyService.findOpenBySubject` 带 `.last("LIMIT 1")`，`releaseBySubject`/`markVacating` 因此只处理**一条**占用；组合租赁（一合同 N 单元）退租后其余单元占用永不收口，被 `EXCLUDE` 永久锁死。

需求侧对应：FR-OPS-006（组合/拆分计费口径与单资产追溯）、FR-CON-LC-004（面积变更）、FR-MDM-001~004、FR-AST-002、FR-OPS-001、SRS §4.27 十三条闭环。

## 决策

### 决策 A：按限界上下文重组模块，并建立 L0–L5 分层依赖规则

将 35 个模块收敛为 **18 个限界上下文 + 平台工具**，按变更频率与不变量划分（完整清单与模块归属映射见[领域划分与限界上下文设计](../design/领域划分与限界上下文设计.md) §4）：

- **L0 通用**：组织与权限、**流程与协同（纯能力，不读业务表）**
- **L1 主数据**：资产主数据、权证与权属
- **L2 空间**：占用与租控（`occupation`/`selfuse`/`disposal`/`revitalization` 合并）、**资产盘点**
- **L3 经营**：客商与招租、合同、定价与计费
- **L4 资金**：收费结算（含退款冲正）、发票、催缴法务、业财一体
- **L5 分析**：经营分析（含监管报送）、**智能服务**、**风险与合规**、**经营计划与督办**
- **横切**：运维服务

两条 L0 硬约束（本次修订新增，用以纠正"流程协同"实际读业务表的问题）：

1. L0 只提供能力（审批、待办、通知），**不得 import 任何业务域的实体或 Mapper**；
2. 一切"读业务数据才能判定的规则"必须移出 L0：`alert`/`compliance`/`opscalendar` → L5 风险与合规，`plan` → L5 经营计划与督办。

依赖**只能向下**。现状 `billing → contract` 的直调改为事件：合同发布 `ContractActivated`（含条款与合同行快照），收费落地本地投影。

### 决策 B：`AssetUnit` 为**独立聚合根**（补充 ADR-0019）

- `AssetUnit` 不放进 `Asset` 聚合，而是独立聚合根，跨聚合不变量（INV-1：Σ 单元面积 ≤ 资产面积）由服务层校验 + 视图诊断；
- 单元互斥（INV-4）继续由数据库 `EXCLUDE`（`ex_occupancy_unit_no_overlap`）硬保证，不依赖聚合内事务；
- `Asset` 与 `AssetUnit` 通过 `asset_id` 关联；单元写入不要求 `Asset` 聚合参与同一事务，跨聚合一致性由服务层校验 + `v_asset_unit_budget` 诊断兜底。

### 决策 C：合同引入 `ContractLine`，替换"一合同一单元"与 `lease_bundle_item` 两套表达

- `ContractLine` 是 `Contract` 聚合内实体，每行引用一个 `asset_unit_id`，携带 `leaseArea`/`rentAmount`/`apportionRatio`/`lineNo`；
- 聚合不变量：`Σ line.leaseArea == contract.leaseArea`；同一合同内 `assetUnitId` 不重复；
- 占用仍是**单元级逐条写入** `asset_occupancy`（`subject_id = contract.id`，`asset_unit_id = line.assetUnitId`），`ContractLine` 只承载法律与计费口径；
- 部分变更（FR-CON-LC-004）通过 `replaceLines` 原子增减行并联动占用（见决策 E）；
- `lease_bundle_item` 降级为只读兼容视图，不再作为真源。

### 决策 D：跨上下文只允许五种交互

① 同步 Port 只读查询；② 领域事件（投递保证见决策 F）；③ 只读投影（存上游快照，禁止跨域 `JOIN` 写）；④ 编排器（`ProcessManager`，承载时序与补偿，不承载领域规则）；⑤ **依赖倒置（前置校验）**——上层提供实现、下层定义接口（如 `contract` 定义 `SubmissionPolicy`，`risk` 实现并注入），使"提交前查合规"不构成向上依赖。

**明令禁止**：跨上下文直呼 Service 写库、跨上下文 `ON DELETE CASCADE`、共享可变实体、一个事务改两个上下文的聚合、L0 依赖任何业务域。

### 决策 E：占用引入**预留态** `biz_status='reserving'`（补 ADR-0019 的审批期空洞）

占用在**单据提交审批时**即写入，`biz_status='reserving'`、`exclusive=true`：

| 时点 | 动作 | `biz_status` |
|------|------|-------------|
| 提交审批 | `occupy(...)` | `reserving`（占位，防并发超租） |
| 审批通过 | 原地转生效 | `active` |
| 审批驳回 / 撤回 | 区间收口 | 收口 |
| 退租申请 | 标记 | `vacating` |
| 退租完成 / 处置完成 | 区间收口 | 收口 |

必须同步的实现后果：

1. `OccupancyBizStatus` 新增 `RESERVING`；
2. `v_unit_lease_status` / `v_asset_lease_status_derived` **必须排除 `biz_status='reserving'`**（**已交付**：`V42__occupancy_reservation.sql`），否则未批准合同会让单元显示为"在租"；
3. `EXCLUDE` 约束的 `WHERE (exclusive)` 与 `ck_occupancy_type` **均无需修改**（预留是状态不是类型）——这是本方案成本低的关键；
4. `LeaseAreaGroups.AREA_HOLDING` 降级为查询视图，避免与预留行形成双份真相；
5. `AssetOccupancyService` 内部释放按单元进行，删除 `findOpenBySubject` 的 `LIMIT 1` 假设。

### 决策 F：新增 `domain_event_outbox` 作为事件投递保证

**已交付**（`V44__domain_event_outbox.sql` + `platform/event/outbox/`）。

业务写与事件行**同事务**插入 `domain_event_outbox`（`OutboxStore.record` 走 `REQUIRED`）；投递器轮询投递（`OutboxDispatcher`，`SKIP LOCKED` + 租约领取），失败重试后置 `dead` 并告警（`markRetry` 走 `REQUIRES_NEW`，因它在 `AFTER_COMMIT` 之后、原事务已结束）；下游按 `(eventId, consumer)` 幂等消费（`event_consumption` + `EventConsumptionGuard`，走 `REQUIRED` 以保证与消费效果同事务）。

字段：`id`、`event_id`、`event_type`、`aggregate_type`、`aggregate_id`、`payload`(TEXT)、`status`(pending/done/dead)、`retry_count`、`next_retry_at`、`error`、`created_at`、`dispatched_at`。

与初稿的两处差异（实现后修正）：

1. `next_retry_at` **同时充当租约**，领取时前推一个租约期——避免新增 `dispatching` 状态与卡死恢复逻辑；实例崩溃后租约到期由他实例接手。
2. `payload` 用 **TEXT** 而非 JSONB，与 `V4` 的 `jsonb→text` 既有约定一致。

**降级选项**：若日后移除 outbox，必须显式声明"投影允许最终陈旧"，并以 `AssetRef.version` 比对作业作为**可发现**手段，且不得声称"投影随事件一致"。

## 备选方案

| 方案 | 优点 | 缺点 |
|------|------|------|
| 保持菜单式模块划分 | 改动为零 | 问题 1/3 不解决；五处反向依赖永久化 |
| 直接按域拆微服务 | 边界物理隔离彻底 | 当前规模（200 在线）无收益；分布式事务成本高；与 ADR-0002 冲突 |
| **上下文内聚 + 模块化单体（采纳）** | 边界可机器校验；改动可分期；与 ADR-0002/0019 一致 | 需要 ArchUnit 门禁与包结构重组 |
| `AssetUnit` 作为 `Asset` 聚合内实体 | 面积不变量天然强一致 | 一栋楼 200 单元串行化；合同生效/退租锁整个资产聚合 |
| 保留 `contract.asset_unit_id`，组合租赁继续用 N 合同 | 改动最小 | 单元身份依附合同；账单无法回溯单资产（违反 FR-OPS-006） |
| 审批期超租用服务层锁 + `AREA_HOLDING` 计数（决策 E 备选 C） | 无 DD 变更 | 回到"纪律优先"，导入/迁移/并发仍可绕过；双份真相 |
| 审批期用独立 `unit_reservation` 预留表（决策 E 备选 B） | 占用表语义不混入未批准行 | 两处互斥逻辑，易漂移 |
| **预留态复用 `asset_occupancy`（采纳）** | `EXCLUDE` 直接生效，无需新约束；成本低 | 派生视图必须排除预留（已列为实现后果）；占用表含未批准行，查询需注意 |
| 事件直接投递（不引入 outbox） | 无新表 | 崩溃即丢事件；投影陈旧不可发现 |

## 后果

### 正面

- **边界可机器校验**：ArchUnit 让"依赖方向"与"L0 不读业务表"从注释纪律变为 CI 门禁，回归即失败。
- **并发超租从"靠纪律"变为"靠约束"**：预留态使审批期也进入 `EXCLUDE` 判定，服务层锁不再是唯一防线。
- **占用只有一个写入口**：`asset_occupancy` 仅由占用上下文写，"合同已生效却忘了置占用"在结构上不可能发生。
- **组合租赁成为一等公民**：`ContractLine` + 逐单元释放让"一合同多单元""一单元多合同（分时）"都能表达，账单可回溯到单资产（FR-OPS-006），并修掉 `LIMIT 1` 导致的永久占用缺陷。
- **并发隔离改善**：`AssetUnit` 独立聚合后，单单元签约/退租不再锁整栋资产。
- **投影一致性可发现**：outbox 提供投递保证，dead 队列可监控，不再依赖"希望事件送达"。
- **可演进的拆分路径**：L4（`billing`）与 L3（`contract`）边界清晰后，未来可按域拆服务。

### 负面

- 包结构重组期间需维护"旧模块 → 新上下文"的映射，短期存在两套路径。
- `AssetRef` 投影仍存在**短暂不一致窗口**（主数据变更与下游刷新之间），outbox 只是保证"最终会送达"。
- `asset_occupancy` 将包含**未批准**的预留行，所有查询（报表、看板、导出）都需明确是否需要计入预留，存在误用风险。
- 上下文由 14 增至 18，短期看包数量增加；换来的是一次性把"读业务表的假 L0"清理干净。
- `contract_line` 迁移需从 `contract.asset_unit_id` 与 `lease_bundle_item` 双源回填，存在数据裁决工作量。
- 分层依赖规则在初期会暴露大量既存违规，需分批清理而非一次修完。

### 风险与缓解

- **派生视图未排除 `reserving` 就上线**：会使未批准合同显示为在租。缓解：`V42` 与 `OccupancyBizStatus.RESERVING` 已同批发布，并加验收 SQL ⑨（设计 §14）。
- **预留行被业务查询误计入**：报表/看板口径漂移。缓解：`Availability` 语义统一为"预留 + 生效都算被占"，展示口径另设 `is_effective` 过滤；对外只暴露 `OccupancyPort`，不暴露表。
- **outbox 表膨胀**：`done` 行长期堆积。缓解：定期归档 `done`（保留 30 天），`dead` 必须为 0 并告警。
- **绕过写入口未清理**：若 `ImportExportService` / `MigrationService` / `DemoDataSeeder` 仍直写 `lease_control_status`，决策 B 的核心收益不成立。缓解：P0 按[改造清单](../design/资产单元与占用模型改造清单.md) §2.3 清理，并以设计 §14 标准 3 的 `rg` 门禁守住（注意必须同时匹配 Java setter）。
- **ArchUnit 规则过严导致阻断交付**：缓解：门禁分档——`contextsAreIsolated`/`l0MustNotTouchBusinessDomain` 为 error，`layering` 初期为 warn，逐期收紧。
- **`AssetRef` 版本漂移**：缓解：下游投影存 `source_version`，每日比对 + `POST /internal/projections/rebuild` 重放。
- **`contract_line` 回填冲突**：同一合同在 `asset_unit_id` 与 `lease_bundle_item` 中单元集合不一致。缓解：先出不一致报告人工核对，不自动裁决；回填后再加面积守恒校验（INV-11）。
- **报表口径变化**：按单元聚合与按资产聚合的面积口径不同（`lease_area` vs `area`），切换读路径前需逐个报表确认。

## 关联

- 需求：[需求规格说明书](../需求规格说明书.md) FR-OPS-006、FR-CON-LC-004、FR-MDM-001~004、FR-AST-002、SRS §4.27
- 设计：[领域划分与限界上下文设计](../design/领域划分与限界上下文设计.md)、[领域划分与限界上下文评审报告](../design/领域划分与限界上下文评审报告.md)、[详细设计说明书](../详细设计说明书.md) §3.4/§3.5、[数据库设计](../database/数据库设计.md) §3.2
- 施工：[资产单元与占用模型改造清单](../design/资产单元与占用模型改造清单.md)
- 迁移：`V39__asset_unit.sql`、`V40__asset_occupancy.sql`、`V41__asset_unit_occupancy_views.sql`、`V42__occupancy_reservation.sql`（决策 E 已交付：派生视图排除 `reserving` + 预留孤儿诊断）、**`V43__process_step.sql`（补偿编排）、`V44__domain_event_outbox.sql`（决策 F 已交付：发件箱 + 消费幂等台账 + 6 类新事件通知模板）**；`contract_line`（决策 C）见后续 `V45+`
- 相关 ADR：[ADR-0002 模块化单体](./0002-monolith-modular-backend.md)、[ADR-0013 进程内领域事件](./0013-in-process-domain-events.md)、[ADR-0019 资产单元与占用事件模型](./0019-asset-unit-and-occupancy-model.md)
