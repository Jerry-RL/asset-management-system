# ADR-0019: 资产计租单元与占用事件模型

- **状态**：Proposed
- **日期**：2026-09-11
- **决策者**：技术架构组

## 上下文

现有资产域只有一层实体：`asset`（"资产台账"）。合同、招租、临时占用、自用、处置全部直接挂 `asset_id`，租控状态由单一字段 `asset.lease_control_status`（9 态）承载。这一模型在业务扩展中暴露出四个结构性问题：

1. **部分占用无法表达。** `occupation_order` / `self_use_order` 都有 `area` 字段，但服务层校验是整资产级的（`OccupationService.create` → `leaseControlService.assertVacant(assetId)`），审批后把**整个资产**置为 `occupied`，`area` 成为死数据。
2. **状态机不再可靠。** 9 个取值里混了四种本质不同的东西——真实占用（`leased`/`self_use`/`occupied`/`disposing`）、招租意向（`leasing`，可并存多个）、派生态（`vacant`/`partial_leased`）、生命周期终态（`exited`）。`VacateService.apply` 需要 `try/catch` 吞掉自身的 `CONFLICT` 才能走通，是模型不匹配的直接证据。
3. **状态机不是唯一入口。** 12 处经 `LeaseControlService.transition` 写入，另有 6 处直接改字段绕过校验（`ImportExportService:113`、`AssetStructureService:104/123/176`、`MigrationService:175/190`）。任何脚本或导入都能破坏状态一致性。
4. **区间互斥靠状态位"碰巧"保证。** 合同、占用、自用、处置都是"某主体在某区间占用某面积"，但没有任何地方校验区间不重叠。引入单元粒度后，该保证立刻失效。

同时，`lease_bundle`（组合/拆分租赁）已经把"一资产多计租单元"的事实写进了数据——`LeaseBundleService.createSplit` 用 N 份合同 `asset_id` 重复表达 N 个计租单元——但单元本身没有稳定身份，合同一旦终止，单元身份即消失。

需求侧对应：FR-MDM-001~004（拆分合并与账单映射）、FR-OCC-001~003（临时占用）、FR-CERT-002（调拨后归属迁移）、FR-AST-002（一物一档）。

## 决策

引入两层新模型，**先把既有链路收口，再谈分表**：

### 决策 A：计租单元是**原子单位**（A1）

`asset_unit` 是不可再分的最小计租单位（房间/铺位/车位）。

- 部分出租 / 部分占用**不通过填 `area` 表达，而是先拆单元**（`AssetUnitService.split`）。
- 同一单元同一时点最多一个生效占用，**由数据库 `EXCLUDE` 约束硬保证**，不依赖服务层校验纪律。
- 单层建筑整体出租时，`building` 级资产生成 1 个单元，合同仍只认单元，避免"合同既可挂资产又可挂单元"的双路径。

### 决策 B：资产层租控状态保留 9 值，但降级为**物化派生列**（B1）

`asset.lease_control_status` 保留 9 个取值，兼容现有筛选（`AssetService.pageAssets`）、统计（`AssetLeaseGroups` IDLE/REVITALIZED）与看板。但：

- `LeaseControlStatus.canTransition` **删除**，状态合法性改由占用区间约束保证。
- 写入端收敛为唯一入口 `LeaseStatusDeriver.refresh(assetId)`，其依据是 `v_asset_lease_status_derived` 视图（SQL 为单一真源，避免 Java/SQL 两套派生逻辑漂移）。
- 新增 `asset.occupancy_ratio`（占用率）与 `asset.lifecycle_status`（`in_book`/`exited`）；`exited` 从"占用状态"归还给"生命周期"，因为"资产已退出"不是一种占用。

### 决策 C：占用抽象为 `asset_occupancy`（生效层），与单据层分离

```
occupation_order / self_use_order / disposal_order / contract   ← 单据层（可多个、非互斥、含审批状态）
                              │ 审批通过时写入
                              ▼
                    asset_occupancy                             ← 生效层（互斥、只存生效区间）
```

类型集合固定为 4 种：`contract` / `self_use` / `occupation` / `disposal`。

- `vacating` 不再是独立状态，而是 `asset_occupancy.biz_status='vacating'`——租户未搬走，面积仍在占用。
- `leasing` 不进入占用表（是意向，可并存多个），由 `lease_listing` 区间 + 单元关联表达。
- 抵押（`mortgage`）不进入占用表，它是**权属负担**而非占用，不与租赁互斥，只与处置/拆分互斥。

## 备选方案

| 方案 | 优点 | 缺点 |
|------|------|------|
| A1 原子单元（**采纳**） | 互斥由 DB 保证；与"一物一档"契合；实现简单 | 单元数上升（一层 20 间 = 20 单元）；部分占用需先拆单元 |
| A2 面积级共存 | 单元数少；直接填 `area` | 互斥只能靠服务层 + 检测作业；每处写入口都要重复校验（现状超租缺陷正是此因） |
| 单表状态机扩展（在 9 态上继续加值） | 改动最小 | 无法表达"部分出租 + 部分自用"；组合态需要笛卡尔积枚举 |
| 直接按 `company_id` 分库分表 | 一步到位 | 当前规模无收益；PG 分区表无全局唯一索引，会破坏 `asset_no` 全局唯一（FR-MDM-003） |

## 后果

### 正面

- **超租/重叠占用从"靠纪律"变为"靠约束"**：`EXCLUDE` 约束对任何写入口生效，含导入脚本与迁移。
- **部分占用、分时占用成为一等公民**：`daterange` 语义天然支持"同一单元不同时段不同主体"。
- **`partial_lease_status` 字段可删除**：第三口径（字典字符串）由单元占用集合派生，不再漂移。
- **FR-MDM-004 有了落点**：拆分时单元随面积迁移，"父合同拆账单 / 终止 + 新签"可在单元粒度判定，而非当前只能在资产层数 `attachedContracts`。
- **`leasing` 与 `disposal` 语义分离**：招租意向不再占用面积，允许多个意向并存。

### 负面

- `asset_unit` 会把资产行数放大 1~3 倍（按单元粒度），报表需改为按单元聚合。
- 派生列存在**短暂不一致窗口**（`refresh` 与业务写入同事务，跨事务的批量修改需一致性作业兜底）。
- 迁移期间需维持"旧 transition 调用 + 新 occupy 调用"双写，代码短期冗余。
- 演示数据由 `ApplicationRunner`（`DemoDataSeeder` 等）在 Flyway **之后**写入，不会经过迁移回填，需配套单元补齐逻辑。

### 风险与缓解

- **回填冲突**：存量数据可能存在重叠占用（历史 bug）。回填按 `contract > self_use > occupation > disposal` 优先级并用重叠守卫跳过冲突行，随后由 `v_asset_lease_status_reconcile` 报出，人工核对后再加 `EXCLUDE` 约束。
- **`asset.lease_control_status` 出现差异**：部分出租资产存量值多为 `leased`，派生值为 `partial_leased`。这是修正而非回归，需在双写期对齐后再切换读路径。
- **绕过写入未清理**：若 `ImportExportService` / `MigrationService` 仍直写状态，派生列会被覆写。必须同步改造，否则本 ADR 的核心收益（单一入口）不成立。
- **报表口径变化**：按单元聚合与按资产聚合的面积口径不同（如按 `lease_area` 而非 `area`），需在切换读路径前逐个报表确认。

## 关联

- 需求：[需求规格说明书](../需求规格说明书.md) FR-MDM-001~004、FR-OCC-001~003、FR-CERT-002、FR-AST-002
- 设计：[详细设计说明书](../详细设计说明书.md) §4.3 状态机、[数据库设计](../database/数据库设计.md) §3.2
- 迁移：`V39__asset_unit.sql`、`V40__asset_occupancy.sql`、`V41__asset_unit_occupancy_views.sql`
- 改造清单：[资产单元与占用模型改造清单](../design/资产单元与占用模型改造清单.md)
- 相关 ADR：[ADR-0006 Flyway 迁移](./0006-flyway-schema-migration.md)、[ADR-0009 轻量审批引擎](./0009-lightweight-approval-engine.md)、[ADR-0013 进程内领域事件](./0013-in-process-domain-events.md)
