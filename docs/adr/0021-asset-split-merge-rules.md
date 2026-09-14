# ADR-0021: 资产与计租单元拆分合并规则

- **状态**：Proposed
- **日期**：2026-09-14
- **决策者**：技术架构组
- **关联前置**：[ADR-0019](./0019-asset-unit-and-occupancy-model.md)（A1 原子单元）、[ADR-0020](./0020-bounded-contexts-and-aggregate-boundaries.md)（决策 C 合同行 / 决策 E 预留态 / 决策 F outbox）
- **本文定位**：ADR-0019 定了"部分租赁用拆单元表达"，但**没定拆合的规则、前置条件、分层边界与接口契约**，也没指出既有 `AssetStructureService` 实现与之冲突。本文补齐，并给出 26 项缺陷（D-01~D-26）的处置决策。

## 上下文

### 需求侧

SRS 要求：

| 来源 | 要求 |
|------|------|
| §4.23.13 FR-MDM-001 | 资产拆分：原资产冻结；子资产继承权属；在租合同须先处理或挂接父合同 |
| §4.23.13 FR-MDM-002 | 资产合并：仅空置资产可合并；历史单据保留映射 |
| §4.23.13 FR-MDM-003 | 编码与追溯：统一编码规则；旧码映射；任意时点可查资产结构树 |
| §4.23.13 FR-MDM-004 | 拆分后的账单与合同映射：**父合同拆账单** 或 **原合同终止 + 子合同新签** 二选一，禁止无归属账单 |
| §4.8.1 列表 | 「部分租赁支持标识」+ 操作「拆分租赁 / 组合租赁」 |
| FR-OPS-006 | 组合/拆分租赁计费口径；账单须能追溯到单资产 |

业务诉求（2026-09-14 补充）：**当一个资产支持部分租赁时，用拆分/合并支撑租赁业务的开展。**

### 现状核查（2026-09-14，逐文件核对代码）

**问题一：系统里"拆分/合并"有三个不同的东西，从未打通。**

| # | 层 | 载体 | 用途 | 现状 |
|---|----|------|------|------|
| ① | **资产层** | `asset.parent_asset_id` / `root_asset_id` / `structure_status` | 产权/台账级物理分割（土地分割、楼栋分立） | `AssetStructureService` 有 API，**前端无入口**；实现绕过单元与占用 |
| ② | **计租单元层** | `asset_unit` | **表达部分租赁 / 部分占用**（ADR-0019 决策 A1 的唯一正确方式） | `AssetUnitService.split` 有代码但**无任何调用方**；单元 `merge` **完全未实现** |
| ③ | 组合/**拆分租赁** | `lease_bundle` / `lease_bundle_item` | 一份合同覆盖多单元 | 有完整页面（`/lease-bundles`），是计费视图而非结构变更 |

本次需求落在 ②，而系统当前可达的是 ① —— 两者语义与不变量都不同。

**问题二：合同链路尚未接入单元/占用模型。**

全库 `AssetOccupancyService.occupy(...)` 的调用方只有三处：`OccupationService`、`SelfUseService`、`MigrationService`。合同、招租、组合租赁、处置、退租**仍是资产级状态位写入**：

```java
// ContractService.activate —— 合同生效仍是"整资产置 LEASED"，不写占用、不写 asset_unit_id
leaseControlService.transition(contract.getAssetId(), LeaseControlStatus.LEASED,
        "contract", contract.getId(), "合同生效");
```

而 `contract.asset_unit_id` 在 Java 侧**从未被写入过**（仅 `MigrationService` 读取）。后果有二：

1. 部分租赁在**合同侧根本无法表达**——即使把单元拆开，合同仍只会把整个资产置为 `leased`；
2. `lease_control_status` 是派生列，合同写入 `LEASED` 后**没有任何合同占用行**，`LeaseStatusDeriver.refresh` 一跑就会把它按"无占用"重算回 `vacant`/`partial_leased` —— 这正是"合同刚生效状态就漂移"的根因。

**问题三：26 项缺陷（D-01~D-26）。**

> D-01~D-19 为初稿清单；**D-20~D-21、D-22~D-26 为后续复核新增**。其中 **D-22~D-26 标注为「条件性」**——它们在当前代码上**不会立即触发**（因为合同链路尚未写占用），但**切片 2（合同接占用）一旦落地就会立刻触发，且后果是单元永久锁死或续签无法生效**。它们与 D-01~D-19 的区别必须保留，否则会误判为"现有生产故障"。

| ID | 严重度 | 位置（证据） | 后果 |
|----|:------:|-------------|------|
| **D-01** | P0 | `AssetStructureService` `split`/`merge`（构造器不注入 `AssetUnitService`） | 拆出的子资产**没有单元**（破坏 INV-2）；父资产在租（`attach_parent`）时子资产被无条件置 `VACANT` → **同一物理空间可变相重复出租** |
| **D-02** | P0 | `AssetStructureService.merge`（源资产仅置 `merged_out`）+ `AssetQrService`（全库唯一认 `merged_out` 的业务处） | 源资产的 `asset_unit` 行仍是 `deleted_at IS NULL` 且 `unit_status='vacant'`，而租赁链路**无结构状态门禁** → **已合并退出的资产仍可被签约**（影子可租） |
| **D-03** | P0 | `AssetStructureService.merge`：`assetNo = "MG" + System.currentTimeMillis()`；结果资产不建单元 | 违反 FR-MDM-003（不可校验、不可排序）；破坏 INV-2 |
| **D-04** | P0 | 拆合判定用 `asset.lease_control_status`，不查 `asset_occupancy` | 派生列漂移（ADR-0019 已承认的窗口）时，可在**有生效占用**的标的上拆分/合并 → 破坏 `EXCLUDE` 互斥的前提 |
| **D-05** | P0 | `ContractService:207` 等 6 个模块共 13 处 `leaseControlService.transition` | 合同链路未接入单元/占用；部分租赁无法表达；派生状态被回滚（见"问题二"） |
| **D-06** | P1 | `cloneAsset` 整份复制 `originalValue`；`AssetUnitService.split` 整份复制 `baseRent` | 拆分后**价值/底价总额放大 N 倍**（未按面积分摊） |
| **D-07** | P1 | `cloneAsset` 未复制 `zoneId`/`assetCompanyId`/`leaseArea`/`lifecycleStatus`/`ownershipStatus`/`partialLeaseStatus`/`floorNo`/`registeredAt`/责任部门/责任人/水电表号/`assetNature`/`buildingPlan`/`houseType`/图片 | ① `zoneId` 为空 → **项目分区汇总丢数、分区/楼层筛选查不到子资产**；② `leaseArea` 为空 → `leasableAreaOf` 回退 `area`，**子资产面积预算基准与父资产不一致**（父按 `lease_area`、子按 `area`），直接改变 INV-1 判定；③ `assetCompanyId` 为空 → UI 资产公司名称空白（`fillCompanyNames` 读该列）、**调拨记录与抵押记录按资产公司筛资产时漏掉子资产**（`AssetTransferRecordService:164` / `MortgageRecordService:216`）、子资产新增抵押时 `mortgage.company_id` 为空（`CertificateService:128`）、丧失与 `operating_company_id` 的镜像一致性；④ `lifecycleStatus`/`ownershipStatus` 为空 → 生命周期与权属流转口径失真 |
| **D-08** | P1 | `Asset.partialLeaseStatus` 仅出现在表单/列表/字典，后端逻辑零引用；而 ADR-0019 与设计 §12.3 已把"删除该字段"列为 P3 | "支持部分租赁"的**门禁不存在**；且"拿它当门禁"与"删除它"的既定决策**方向冲突** |
| **D-09** | P1 | `AssetService.projectOverview`（`groupBy` 无 `structure_status`）/ `pageAssets`（无 `structure_status`、也无 `deleted_at` 过滤） | 拆分后「冻结父 + 子资产」**同时计入** → 宗数/面积重复计数；软删资产会出现在列表中 |
| **D-10** | P1 | `AssetUnitService.split` 无调用方；`AssetUnitController` 不存在；前端只有只读日志页 | **单元层拆合对用户不可达**；单元 `merge` 未实现 |
| **D-11** | P1 | 术语表 §5 定义 `AssetUnit` 为"**不可再分**"，ADR-0019 又要求"部分占用先拆单元" | 模型表述自相矛盾，编码时无判据 |
| **D-12** | P2 | `nextChildNo` 用 `UUID.substring(0,4)`；拆合无幂等键 | 编码不可反推归属；重试不可辨别；并发下合并可能产出多个结果资产 |
| **D-13** | P2 | `AssetStructureService` 直接改结构，未接 `ApprovalEngine` | 主数据变更无审批（FR-MDM 是否要求审批**待产品确认**） |
| **D-14** | P2 | `AssetStructureService` `import com.ams.modules.contract.*` | 资产主数据（L1）**向上依赖**合同（L3），违反设计 §6.3 与 `layering` ArchUnit 规则 |
| **D-15** | P2 | `AssetUnitService.split`：仅当 `src.area > 0` 才校验面积守恒 | 占位单元（`area = 0`）拆分可**突破 INV-1 面积预算**；子单元 `rentableArea = area` 丢弃原有差异 |
| **D-16** | P2 | `AssetStructureService.merge` 不校验项目/分区/资产类型一致性 | 可产出**跨项目、跨类型**的语义错误资产（`cloneAsset(first)` 只取第一个源的元数据） |
| **D-17** | P2 | `merge` 仅 `assertNotMortgaged`，不迁移权证 | 源权证挂在 `merged_out` 资产上成为**孤儿** |
| **D-18** | P3 | `AssetStructureService.buildNode` 中 `List<Asset> children` 计算后未被使用 | 死代码 + 一次浪费查询 |
| **D-19** | P3 | `attach_parent` 策略下父资产置 `frozen`，而 `requireActive` 禁止操作 frozen；且父资产仍被派生器算作 `leased` | FR-CON-LC-004 面积变更（`replaceLines`）无法把父资产单元迁到子资产，**合同与标的脱节**；`frozen` 与派生状态语义冲突 |
| **D-20** | P1 | **复核新增**：资产层拆分未定义"切分线与单元边界的关系"——父资产已有单元时"按面积随便切"会切出半个在租单元，而该单元不可拆（S3） | 此类拆分**无解**；操作者只能得到一条读不懂的报错。见决策 D A10 |
| **D-21** | P1 | **复核新增**：只交付 `split` 不交付 `merge` | 等于提供一条**单向、不可撤销**的高危操作；拆错了只能手工改库。见决策 B S12 |
| **D-22** | **P0（条件性）** | `ContractService.onApprovalCompleted` 驳回分支**只 `setStatus(DRAFT)`**，无任何占用收口 | 切片 2 落地后，若同时上"提交即预留"（决策 E），驳回会使**预留行泄漏并永久锁死单元**。与[改造清单](../design/资产单元与占用模型改造清单.md)已记录的"驳回链路缺失"同源——`OccupationService`/`SelfUseService` 已补，**合同侧未补** |
| **D-23** | **P0（条件性）** | `VacateService:263` 退租完成只写 `transition(VACANT)`，**不调 `releaseBySubject`** | 合同占用 `dateTo = null`（开区间）→ 不收口则永不结束 → 派生器重新派生为"在租"；叠加 `EXCLUDE` → **单元永久锁死，无法再租**。这比现状**更糟**（现状只是状态显示错，可人工修正） |
| **D-24** | **P0（条件性）** | 合同作废/终止路径（`voidContract` 等）只做 `contractVersionService.snapshot`，不释放占用 | 同 D-23 |
| **D-25** | **P0（条件性）** | `renew` **不改旧合同状态**（旧合同停留 `RENEWABLE`/`EXPIRING`，二者均属 `LeaseAreaGroups.OCCUPYING`），旧占用为开区间；`transfer` 同理（新草稿激活后旧合同仍 `ACTIVE`） | 新旧占用区间**必然重叠** → `EXCLUDE` 拒绝 → **续签与转租无法生效**。此条要求"接占用"必须同时定义整条生命周期的区间语义（生效 → 续签/转租 → 退租/终止），远不止"在 `activate` 里加一行 `occupy`"。见决策 I |
| **D-26** | **P0（条件性）** | **复核新增**：`ScheduledJobs.contractExpiryScan`（每日 04:00）把合同翻为 `EXPIRED` 时**不收口占用**。根因是 `v_unit_lease_status`（`V42:29-73`）**完全不引用 `contract` 表**——它只读 `asset_occupancy` / `lease_listing` / `asset.lifecycle_status`，因此"合同状态"与"派生租控状态"之间**唯一的耦合就是占用行** | 占用为开区间则该区间**永远命中** `CURRENT_DATE <@ daterange(...)` → 合同已到期而单元**永久显示在租**。这条同时说明 `expired` 必须纳入"终态"集合（验收 SQL ⑮）。见决策 I |

> **D-26 的普遍意义**：既然派生视图不读合同状态，那么**合同生命周期里每一个终止态都必须显式收口占用**——这是"对称闭合"清单为五处而非四处的根本原因（驳回/撤回、退租完成、终止作废、续签转租、**到期**）。

> 与既有文档的一致性：本清单中的 D-01（资产层绕写）与 D-05（合同链路未接入）**与[改造清单](../design/资产单元与占用模型改造清单.md) 的触点 15、触点 8~13 完全对应**，说明该清单是准确的，只是尚未施工。本 ADR 不推翻清单，而是为其补齐**规则、边界与验收口径**。

#### 核查更正记录（初稿错误推断的排除，避免后人重走）

| 初稿推断 | 核查结论 | 证据 |
|---------|---------|------|
| `assetCompanyId` 为空 → **RBAC 数据范围失效** | **错误推断**。`OwnershipResolver.ofAsset` 读的是 `operating_company_id`，而 `cloneAsset` **已复制该字段**，故数据范围过滤与 `assertCompanyAccess` 均不受影响。D-07 的真实后果见上表②③ | `OwnershipResolver:81`（读 `operatingCompanyId`）；`AssetStructureService.cloneAsset`（含 `setOperatingCompanyId`）；`AssetService:1088-1093` 的级联校验带 `!= null` 守卫，null 时**跳过**而非报错；`AssetService:1128` 的镜像是单向的（null 不覆盖） |
| `assetCompanyId` 为空 → 编辑保存时级联校验失败 | **错误推断**。校验有 null 守卫，为 null 时跳过 | `AssetService:1090-1103` |

> **教训**（本次评审的真实坑）：结论若来自"字段为空 → 下游肯定出问题"的**推断链**而非逐处核对，很容易把 P1 写成 P0、把"显示空白"写成"权限失效"。上面的两条初稿错误都属于此类。**评审输出必须给出文件:行号级证据，凡无证据的因果链一律标注"待验证"**，否则评审本身会成为新的返工来源。

## 决策

### 决策 A：拆合分三层，各自语义与不变量互不相同（术语修正）

| 层 | 何时用 | 不变量 | 是否允许"部分租赁"载体 |
|----|--------|--------|----------------------|
| **① 单元层** | 把资产的**部分**出租/自用/占用 | 同单元区间不重叠（`EXCLUDE`）；Σ 子单元面积 = 原单元面积 | ✅ **唯一载体** |
| **② 资产层** | 权证分证、土地分割、楼栋分立 | 原资产冻结；子资产继承权属；结构树可追溯 | ❌ 不得用于表达部分租赁 |
| **③ 组合租赁** | 一租户整包多单元 | 合同行集合面积守恒（FR-OPS-006） | ❌ 不产生新标的 |

**术语修正（D-11）**：`AssetUnit` 的"不可再分"应表述为——**在任一时刻，单元是合同/占用的最小挂载粒度；拆分不是"同一单元内容纳多个占用"，而是"原子单元被替换为更细粒度的单元集合，原单元失效且不可再被引用"**。术语表按此措辞更新，消除与 `split` 的表述冲突。

### 决策 B：单元拆分规则（部分租赁的唯一正确表达）

| # | 规则 | 落地 |
|---|------|------|
| S1 | **门禁**：资产必须"支持部分租赁" | 见**决策 E**（待决：保留 `partial_lease_status` 为显式开关 vs 删除后由 `unit_count > 1` 派生） |
| S2 | 拆分粒度是**单元**，不是资产 | 资产层拆分只做台账/权属边界（决策 D） |
| S3 | 前置：该单元**无未收口占用**（含 `biz_status='reserving'`） | `AssetOccupancyService.hasOpenOccupancy(unitId)` |
| S4 | 前置：该单元**无 `status='active'` 招租发布** | `lease_listing` 查询 |
| S5 | 前置：**无抵押 / 无处置中** | `CertificateService.assertNotMortgaged(assetId)` —— 权属负担与拆分互斥（ADR-0019 关键区分③） |
| S6 | **面积守恒**：Σ 子单元面积 == 原单元面积（\|Δ\| ≤ 0.01）；`area = 0` 的占位单元**一律拒绝拆分**（不得跳过校验） | 修 D-15 |
| S7 | **只拆空置部分，在租部分原地不动**——已出租的单元不参与拆分，避免合同脱标 | 决策 B 的核心：这才是"部分租赁"的正解 |
| S8 | 子单元 `unit_status` 初始化为 `vacant`，之后**只由 `LeaseStatusDeriver` 派生**，业务代码禁写 | ADR-0019 决策 B1 |
| S9 | 原单元**软删**并保留历史身份；子单元编码 `UnitNoGenerator.childOf(unitNo, idx)` | 确定性编码，修 D-12 |
| S10 | **底价按面积分摊**：`child.baseRent = src.baseRent × childArea / srcArea`；`rentableArea` 口径显式赋值 | 修 D-06/D-15 |
| S11 | 原子事务 + **幂等键** `(opType='unit_split', srcUnitId, hash(childAreas))` | 修 D-12 |
| **S12** | **可回滚窗口**（补充规则）：`merge` 是 `split` 的逆操作，**两者必须同批交付**，否则拆错了无法还原。窗口 = **子单元尚未被下游引用之前**（无合同、无招租、无未收口占用）。超窗后回滚需先解除引用（退租 / 撤招租），成本陡增。UI 必须显式区分"可回滚 / 不可回滚" | 拆分是高危且近不可逆操作，单靠"留痕"不够 |

**补充规则（初稿遗漏，经评审补入）**

| # | 规则 | 为什么重要 |
|---|------|-----------|
| S12 | 见上：`merge` 是 `split` 的逆操作，**同批交付**；可回滚窗口 = 下游未引用前 | 只做 split 不做 merge，等于提供了一条单向、不可撤销的高危操作；误操作只能靠手工改库 |
| A10 | 资产层拆分的**切分线必须与单元边界对齐**，单元不得跨子资产 | 父资产已有单元时，"按面积随便切"会切出"半个在租单元"——而该单元不可拆（S3），拆分直接无解。这条把"资产层拆分"与"单元层拆分"的耦合关系钉死 |
| A11 | 单元归属迁移（`asset_unit.asset_id`）必须与结构变更**同事务**完成 | 否则存在"父/子可租集合都不完整"的中间态，而该窗口内仍有并发签约可能 |
| — | 拆合必须**先预演再提交**（dry-run 返回：将创建/失效的单元、面积与底价拆分结果、受影响的下游引用清单），提交时校验预演快照未被并发改变 | 高危操作的最小防护：让操作者在下单前看到影响面 |

> 以上四条是**对初稿的自我修正**，均不来自既有文档，而是对照代码后补出的规则。S12 与 A10 改变了施工顺序（`merge` 不再可延后），故已同步至改造清单。

### 决策 C：单元合并规则

| # | 规则 | 落地 |
|---|------|------|
| M1 | 前置判定依据是**占用表**，不是 `asset.lease_control_status`；等价于"全部单元空置" | 修 D-04 |
| M2 | 无未收口占用、无 `active` 招租、无未结账单、无生效合同 | |
| M3 | **无抵押**；权证须一并迁移或阻断合并 | 修 D-17：权属负担不迁移即拒绝 |
| M4 | 必须**同资产、同项目、同分区、同 `asset_type` / `usage_type`**；跨资产合并走决策 D | 修 D-16 |
| M5 | 面积相加；**底价相加**（不得重复计） | 修 D-06 |
| M6 | 源单元**软删** + 编码映射；结果标的**必须存在 ≥1 个有效单元**（INV-2） | 修 D-01/D-03 |
| M7 | 编码使用 `UnitNoGenerator`，**禁止**时间戳 / 随机后缀 | 修 D-03/D-12 |

### 决策 D：资产层拆合重做（语义收窄为"台账/权属边界"）

| # | 规则 |
|---|------|
| A1 | 适用场景白名单：权证分证、土地分割、楼栋分立。**不承载部分租赁** |
| A2 | 前置判定一律扫 `asset_occupancy`（不是派生列）；有未收口占用即拒绝（保留 `attach_parent` 作为显式例外，但须同时完成 A6） |
| A3 | **子资产字段必须完整继承**（修 D-07）：`zoneId`、`assetCompanyId`、`leaseArea`、`floorNo`、`assetNature`、`buildingPlan`、`houseType`、`registeredAt`、责任部门/责任人、水/电表号、`lifecycleStatus`、`ownershipStatus`、`partialLeaseStatus`、图片字段 |
| A4 | **原值按面积分摊**：Σ 子 `originalValue` == 原 `originalValue`（修 D-06） |
| A5 | 每个子资产生成 **≥1 个 `asset_unit`**（INV-2），编码走 `UnitNoGenerator`（修 D-01/D-03） |
| A6 | 父资产转 `frozen` 时，其单元**一并软删**并生成编码映射；在租合同按 FR-MDM-004 二选一（父合同拆账单 / 终止+新签），**禁止无归属账单** |
| A7 | 合并：源资产的全部单元软删并迁移；结果资产建单元；权证迁移（修 D-17） |
| A8 | **`frozen` / `merged_out` 一律不可再被派生器写状态**，且不出现在任何可租选择器（`pickLeasable` / `resolveForLease` / 合同 / 招租入口）——修 D-02/D-19 |
| A9 | 去掉对 `contract` 模块的直接 import（修 D-14）：合同数量/状态改由 `AssetCatalogPort` 的兄弟 Port（如 `ContractRefPort`）或事件投影提供；`CertificateService` 保留（同属 L1） |
| **A10** | **切分线必须与单元边界对齐**（初稿遗漏，见下方"补充规则"）：子资产面积必须是**若干完整单元的并集**；**单元不得跨子资产**——一旦跨界的单元是在租单元，就无法再拆（S3 挡住），资产层拆分将无解。父资产的单元随所属子资产迁移（改 `asset_unit.asset_id`）并刷新编码映射 |
| **A11** | 拆分后 `parent_asset_id` 指向父资产，但**可租性由单元归属决定**，不由 `asset_id` 决定：子资产的单元未迁移完成前，父/子的可租集合都不完整。迁移必须与结构变更**同事务**，不得异步补 |

### 决策 E：`partial_lease_status` 的去留（**待决**，需产品签字）

D-08 暴露出方向冲突，必须先决策，否则 S1 无法实现：

| 选项 | 内容 | 优点 | 缺点 |
|------|------|------|------|
| **E1** | 保留为**显式开关**，并纳入门禁 | 与 SRS §4.8.1「部分租赁支持标识」字面一致；产品可直接控制 | 与 ADR-0019"第三口径漂移，应删除"冲突；须补一致性作业，防止开关与 `unit_count > 1` 脱节 |
| **E2（推荐）** | **删除字段**，"支持部分租赁"由 **`unit_count > 1` 派生** | 单一真源；无漂移；与 ADR-0019 一致 | 需改 SRS 表述与列表列口径；存量 `support` 数据需回填为"多单元" |

**本文按 E2 编写规则**，但决策 E 在获得产品确认前不得视为已定；若选 E1，S1 需替换为"`partial_lease_status = 'support'` 且该字段与单元数一致"的一致性校验。

### 决策 F：新增不变量（续设计 §7.3 的 INV-12）

| 编号 | 不变量 | 跨聚合 | 落地 | 违反可发现 |
|------|--------|:------:|------|-----------|
| **INV-13** | 拆分面积守恒：Σ 子标的面积 == 原标的面（\|Δ\| ≤ 0.01） | — | 服务层校验 | 验收 SQL ⑩ |
| **INV-14** | 拆合后"每个资产 ≥ 1 个有效单元"仍成立（INV-2 的运维延伸） | ★ | 服务层 + `v_asset_without_unit` | 视图 |
| **INV-15** | 参与拆合的标的**无未收口占用（含 reserving）且无 active 招租** | ★ | 服务层校验 | 验收 SQL ⑪ |
| **INV-16** | 价值/底价守恒：Σ 子 `originalValue` == 原 `originalValue`；Σ 子 `baseRent` == 原 `baseRent` | — | 服务层分摊 | 验收 SQL ⑫ |
| **INV-17** | `structure_status ∈ {frozen, merged_out}` 的资产**不得出现在任何可租选择器** | — | `pickLeasable` / `resolveForLease` 加门禁 | 验收 SQL ⑬ |
| **INV-18** | 拆合后编码确定性：`unit_no` / `asset_no` 可校验、可排序、可从编码反推归属 | — | `UnitNoGenerator`；禁用随机后缀 | 编码格式断言 |
| **INV-19** | **占用写入与收口对称闭合**：每一条 `asset_occupancy` 行，在其来源单据进入终态（驳回/撤回/退租完成/终止/作废/续签替换）时都必须被收口（`date_to` 非空） | ★ | 决策 I；服务层成对交付 | 收口完整性 SQL（改造清单 §3）+ 日检作业 |

### 决策 G：接口契约与幂等

| 端点 | 方法 | 权限 | 说明 |
|------|------|------|------|
| `/api/v1/assets/{assetId}/units` | GET | `asset.ledger:view` | 单元列表（含派生状态） |
| `/api/v1/assets/units/{unitId}/split` | POST | `asset.unit:update`（新增） | body：`{ childAreas: number[], reason, requestId }` |
| `/api/v1/assets/units/merge` | POST | `asset.unit:update` | body：`{ unitIds: number[], reason, requestId }` |
| `/api/v1/assets/{assetId}/split` | POST | `asset.ledger:update` | **语义收窄**为台账/权属边界（决策 D） |
| `/api/v1/assets/merge` | POST | `asset.ledger:update` | 同上 |

**幂等**：请求体携带 `requestId`（或 `Idempotency-Key` 头），落 `asset_structure_log.idempotency_key` 唯一索引；重复请求返回首次结果，不产生第二次副作用（修 D-12）。

### 决策 H：事件与投影

拆合成功后经 **outbox**（ADR-0020 决策 F）发布：

- `AssetUnitSplit` / `AssetUnitMerged`（设计 §8 规则 3 已列 `AssetUnitSplit`）
- `AssetSplit` / `AssetMerged`

下游（合同/招租/定价/看板）按事件刷新 `AssetRef` 投影，并走 §8 规则 5 的 `source_version` 比对兜底。

### 决策 I：占用写入与收口必须**对称闭合**（本次复核新增，对应 **INV-19**）

**问题**：把合同链路接入占用时，若只加"写占用"而不加"收口占用"，会制造**严格比现状更严重**的故障。推演（D-23）：

1. 合同占用的 `dateTo` 传 `null`——这是设计如此（`AssetOccupancyService` 注释：*"为空表示无固定期限（在租合同）"*）；
2. 退租完成仍只写 `transition(VACANT)`，不收口；
3. 占用行据此**永不结束**；派生器按占用集合重算 → 单元/资产**又派生回"在租"**；
4. 叠加 `EXCLUDE`（`WHERE exclusive`）→ **该单元永久锁死，再也租不出去**。

对比现状：现状是"状态显示错，但数据可修、单元还能租"；上述改法把问题升级为"结构性锁死，只能手工改库"。**所以这是接入占用的最大陷阱，而不是顺带要做的收尾工作。**

**决策**：**每新增一条占用写入路径，必须同批交付其对应的收口路径**，不得分期。

**五处**（不可缩减为四处）：

| 占用写入点 | 必须**同批**交付的收口 | 漏了的后果 | 证据 |
|-----------|----------------------|-----------|------|
| `submit` 预留（决策 E） | 驳回 / 撤回 → `cancelReservation` | 预留行泄漏，单元永久锁死 | `ContractService.onApprovalCompleted` 驳回分支仅 `setStatus(DRAFT)`（D-22） |
| `activate` → `occupy` | 退租完成 → `releaseBySubject` | 单元永久锁死 | `VacateService:263`（D-23） |
| `activate` → `occupy` | 作废 / 终止 → `releaseBySubject` | 同上 | `voidContract` 仅 `snapshot`（D-24） |
| `activate` → `occupy` | **续签 / 转租时收口旧合同占用** | 新旧区间重叠 → `EXCLUDE` 拒绝 → **续签/转租无法生效** | `ContractService.renew` 不改旧合同状态，旧合同停留 `RENEWABLE`/`EXPIRING`（∈ `LeaseAreaGroups.OCCUPYING`），旧占用为开区间（D-25） |
| `activate` → `occupy` | **合同到期（转 `EXPIRED`）→ 收口于 `endDate`** | 合同已到期而单元**永久显示在租** | `ScheduledJobs.contractExpiryScan` 仅 `setStatus(EXPIRED)`；`v_unit_lease_status` 不读 `contract`，开区间永远命中（D-26） |

**推论（对工作量的修正）**：D-25 意味着"合同接占用"不是"在 `activate` 里加一行 `occupy`"，而是**被迫先把整条合同生命周期的区间语义定义清楚**：

```text
合同生命周期 → 占用区间语义
  DRAFT/APPROVING  : 无占用（或 reserving，取决于是否上决策 E）
  提交审批         : reserve(unit, dateFrom=?, dateTo=?)        ← 待定义
  ACTIVE           : occupy(unit, dateFrom=startDate, dateTo=null)
  续签 renew       : 旧合同占用收口于 old.endDate（或新合同生效日）；新合同重新 occupy
  转租 transfer    : 新合同生效时收口旧合同占用
  退租完成         : releaseBySubject(contract, 实际腾空日)
  终止/作废        : releaseBySubject(contract, 终止日)
```

**自检问法（建议进 code review checklist）**：

> **这条占用行，谁会把它关掉？** —— 答不上来的写入点，就是一处泄漏。

**配套要求**：`LeaseStatusDeriver.refreshReconciled` 与 `v_asset_lease_status_reconcile` **已实现但未接线**（`platform/job` 下无任何调用点），因此当前"漂移"既不为零、也不可见。故决策 I 的第一个施工项不是改写入路径，而是**先把只读比对接上并告警**（见实施顺序切片 0）——把不可见变成可观测的数字，再动写入。

## 备选方案

| 方案 | 优点 | 缺点 |
|------|------|------|
| **单元层拆合 + 资产层收窄（采纳）** | 部分租赁由 `EXCLUDE` 硬保证；与 ADR-0019/0020 一致；账单可回溯单资产 | 需先补合同链路（触点 8），否则单元被合同"看不见"；且接入时必须按**决策 I 成对交付收口**，否则会造成单元永久锁死 / 续签失效（D-22~D-25） |
| 只给合同加 `occupy`、暂不做收口 | 改动最小、见效快 | **严格更糟**：占用开区间永不结束 + `EXCLUDE` → 单元永久锁死；续签/转租被 `EXCLUDE` 拒绝（决策 I / D-23~D-25）。此方案被明确否决 |
| 继续在资产层拆分表达部分租赁（现状） | 改动为零 | D-01~D-19 全部保留：重复出租、价值放大、权限失效、重复计数 |
| 在 `asset` 上加"部分出租面积"字段，不拆单元 | 表单简单 | 互斥退化为服务层纪律，回到 ADR-0019 批判的 A2 方案（超租缺陷之源） |
| 只补单元层 `split`，暂不做单元 `merge` | 工作量小 | 需求明确要求"拆分**合并**"；且合并是拆分的前置回滚手段（拆错了无法还原） |
| 保留 `lease_bundle` 作为部分租赁载体 | 有现成页面 | 单元身份依附合同，合同终止即消失；账单无法稳定回溯；与 FR-MDM-004 冲突 |

## 后果

### 正面

- **"部分租赁"从"靠资产状态位"变为"靠单元集合 + DB 约束"**，超租在结构上不可发生。
- **拆合有了唯一语义**：单元层管部分租赁，资产层管权属边界，不再混用。
- **D-07 / D-09 修掉后**，拆分不再导致分区汇总丢数、数据权限失效与报表重复计数。
- **编码确定性**（INV-18）使"旧码映射 + 结构树追溯"（FR-MDM-003）可机器校验。
- **为 FR-MDM-004 提供落点**：在单元粒度判定"父合同拆账单 / 终止+新签"，不再依赖资产级 `attachedContracts` 计数。

### 负面

- **工作量在链路上游，不在拆合本身**：必须先做**合同链路接入单元/占用**（触点 8~13），否则单元拆了合同也看不见。且如决策 I 所述，接入时必须**成对交付收口**，这一步比"加一行 `occupy`"大得多。
- 存量资产存在"状态与实际占用不符"（V39 迁移实测已暴露 `T-A3`：标记 `leased` 实际只租 30%），拆合门禁改为扫占用表后，这类数据会**首次被拦下**，需要人工核对。
- 资产层拆合"语义收窄"是一次**对外行为变更**：原先可用于表达部分租赁（虽然实现有缺陷），收窄后必须走单元层；需同步前端与用户手册。
- 单元 `merge` 是新增能力，`asset_structure_log.op_type` 需扩展取值（`unit_split` / `unit_merge`）并补前端入口。

### 风险与缓解

- **拆合使占用"失去标的"**：缓解：S3/M2 一律以 `asset_occupancy` 判定前置，且拆分只作用于空置单元（S7）。
- **父资产 `attach_parent` 后与派生器冲突（D-19）**：缓解：A8 明确 `frozen` 资产不参与派生写入；新增 `structure_status` 门禁验收 SQL ⑬。
- **`partial_lease_status` 决策未定导致 S1 悬空**：缓解：本文按 E2 编写，但**开工前必须取得产品签字**；未定期间可先实现 S2~S11（不含门禁）。
- **存量数据回填**：`asset_unit` 的拆合结果需重放 `AssetRef` 投影。缓解：复用 `POST /internal/projections/rebuild?context=X`。
- **误操作不可逆**：缓解：拆合必须留 `asset_structure_log`（含 `sourceAssetIds` / `resultAssetIds` / `mappingJson`），并提供"结构树 + 拆合日志"只读追溯页（已存在 `/assets/structure-logs`）。
- **接入占用时"只写不收口"（决策 I / D-22~D-25）**：后果是单元永久锁死或续签失效，**比不改更糟**。缓解：① 决策 I 的成对交付清单作为 code review checklist；② 切片 0 先上只读比对，让残留未收口占用**可被看见**；③ 收口完整性 SQL 纳入上线门禁（改造清单 §3）。
- **把"前置依赖"当成可协商项**：若把合同链路理解为"拆合的前置"，容易被排期讨论降级为"可以并行/可以先做半套"。缓解：ADR 明确其性质是**现有漏水点**而非依赖项；且**范围限定为部分租赁场景**（空置资产拆分不依赖它），避免论证被过度扩张。

## 实施顺序

**先纠正一个框定**：合同链路接入占用**不是**"拆分合并的前置依赖"（那种说法会引出"能不能并行"的讨论），而是**现在就存在、与拆分无关的漏水点**——`activate` 写资产级状态而派生器按占用集合重算，这条链路今天就是坏的。此外，**其范围应限定为"部分租赁"场景**：把空置资产拆开并不需要合同链路。

| 切片 | 动作 | 依赖 | 验收信号 |
|------|------|------|---------|
| **0（零风险，最先做）** | **接线只读比对**：把 `LeaseStatusDeriver.refreshReconciled` 接到周期任务，**只统计告警、不改数据** | — | 监控能报出漂移条数；`v_asset_lease_status_reconcile` 的规模从"不可见"变为"一个数字" |
| **1** | 合同创建 / 提交时解析并写入 `contract.asset_unit_id`（**只写字段，不改状态机**） | 切片 0 | 验收 SQL ⑭ 收敛；合同→单元的链路恢复 |
| **2** | `activate` **双写**（保留 `transition(LEASED)` + 新增 `occupy`），并按**决策 I** 同批交付四个收口：驳回/撤回、退租完成、终止/作废、续签与转租的旧占用收口 | 切片 1；ADR-0020 决策 C/E | 合同生效后有 `contract` 占用行；退租/终止/驳回/续签后**无残留未收口占用**；`v_asset_lease_status_reconcile` 为空 |
| **3** | "提交即预留"（决策 E 的超租保护） | 切片 2 | 并发提交两份同单元合同，第二条命中 `EXCLUDE` |
| **P1** | 决策 D 的 A3/A4/A6/A8（`cloneAsset` 补全、价值分摊、父单元收口、结构状态门禁）；D-09 汇总过滤 | — | 验收 SQL ⑫⑬；拆分后项目分区汇总不变 |
| **P2** | 决策 G 接口 + 决策 B/C 单元拆合实现（含单元 `merge`、幂等、确定性编码、预演 dry-run）+ 前端入口 | P1 | 验收 SQL ⑩⑪；拆合可端到端跑通且可回滚 |
| **P3** | 决策 E 定案并实施；D-14 依赖倒置；D-18 清理；`asset_structure_log.op_type` 扩值 | 产品签字 | `layering` ArchUnit 通过；术语表与代码一致 |

**两条硬约束**：

1. **切片 2 的四个收口不可分期**——只做写入不做收口，会造成单元永久锁死或续签失效（D-22~D-25），**比现状更糟**；
2. **其余写入域（招租/组合租赁/处置/退租，改造清单触点 8~13）同样适用决策 I**，逐域切换、逐域可回滚，不搞大爆炸。

## 关联

- 需求：[需求规格说明书](../需求规格说明书.md) §4.23.13 FR-MDM-001~004、FR-OPS-006、§4.8.1、FR-AST-002
- 设计：[领域划分与限界上下文设计](../design/领域划分与限界上下文设计.md) §5 术语表、§7.3 不变量、§7.4 拆分与合并、§8 发布语言、§12.3 数据模型增量、§14 验收标准
- 施工：[资产单元与占用模型改造清单](../design/资产单元与占用模型改造清单.md)（触点 15 重做 + 新触点 33~39）
- 前置 ADR：[ADR-0019 资产单元与占用事件模型](./0019-asset-unit-and-occupancy-model.md)、[ADR-0020 限界上下文划分与聚合边界](./0020-bounded-contexts-and-aggregate-boundaries.md)
- 代码：`AssetStructureService`、`AssetUnitService`、`AssetOccupancyService`、`LeaseStatusDeriver`、`UnitNoGenerator`、`LeaseControlService`（待删）
- 迁移：`V39__asset_unit.sql`、`V40__asset_occupancy.sql`、`V41__asset_unit_occupancy_views.sql`；后续需 `asset_structure_log.idempotency_key` 与 `op_type` 取值扩展
