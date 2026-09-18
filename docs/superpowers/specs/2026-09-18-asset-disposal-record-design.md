# 资产处置记录（V57 / V58）设计

| 项目 | 内容 |
|------|------|
| 状态 | 已交付（后端 + 前端 + 迁移 + 测试） |
| 关联 | SRS §4.5.4 / §4.8.4 / §4.23.17（FR-DISP-*）；[改造清单](../design/资产单元与占用模型改造清单.md) 触点 7 |
| 交付物 | `V57__asset_disposal_record.sql`、`V58__move_disposal_record_menu.sql`、`AssetDisposalRecord` / `AssetDisposalRecordService` / `DisposalRecordController`、`DisposalRecordsPage` |

## 1.1 菜单归属（V58 调整）

菜单最终挂在 **「资债权证记录」（`deed`）** 目录下（原先在 V57 里挂在「资产运营」）。

| 项 | 值 | 说明 |
|----|----|------|
| 挂载目录 | `deed` | 它是**记录 / 台账**（一行 = 一个被处置的资产），与权证、抵押、调拨、权属流转同属「记录」；「资产运营」的语义是「发起一次操作」 |
| `menu.code` | `deed.disposalRecord` | 由 `operation.disposalRecord` 改名 —— 仓内约定「菜单 code = 目录 code + '.' + 页面名」 |
| `sort` | 47 | 落在「资产调拨记录」(46) 之后 |
| `path` | `/disposal-records` | 不变（改 path 会让既有书签落回首页） |

**为什么必须是新迁移 V58 而不是回头改 V57**：V57 已执行过，Flyway 按 checksum 校验已应用迁移，改动它会让 `flyway.validate()` 失败、应用启动即失败（`MigrationChainPostgresTest` 钉住此点）。

**改名为什么必须连 `role_permission.menu_code` 一起改**：它是**反范式冗余列**。只改 `menu.code` 不同步它，会让「按 `menu_id` 查权限正常、按 `menu_code` 查为空」——而 V54/V55/V57 的 view 回填语句都是按 `menu_code` 写的，后续新迁移也会照此写。`menu_id` 刻意不动：菜单行本身没换，动它会让 `uk_role_menu_action` 撞唯一约束。

## 1. 需求

1. **新增「资产处置记录」菜单，展示所有被处置的资产。**
2. **某一资产（项目 / 项目分区 / 资产）被处置后，该资产不再属于原产权公司。**

## 2. 现状与缺口

| 现状 | 缺口 |
|------|------|
| 资产级处置走 `disposal_order`（带审批状态机）。完成时只把 `asset.lease_control_status` 置 `exited` | **没有**写 `lifecycle_status`，**没有**动 `property_company_id` → 处置完的资产仍挂在原产权公司名下 |
| 项目 / 分区处置走 `biz_disposal_record`（纯台账，record-sheet 全量 diff） | **完全不触达**其下资产 |
| 权属流转（V54）外部流转会置 `lifecycle_status='exited'` + `ownership_status='transferred_out'` | 处置没有对应的归属收口，也无独立台账可查「处置了哪些资产」 |

## 3. 已确认口径（产品决策）

| # | 决策 | 选择 |
|---|------|------|
| Q1 | 记录页展示什么 | **以「资产」为行**的台账；项目 / 分区被处置时其下资产也展开为行 |
| Q2 | 「不属于原产权公司」如何落库 | **清空 `asset.property_company_id`** + 置 `ownership_status='disposed'` + `lifecycle_status='exited'`；原产权公司快照进处置记录 |
| Q3 | 项目 / 分区被处置时其下资产 | **级联处置**：其下全部资产一并脱离原产权公司 |

## 4. 数据模型

### 4.1 新表 `asset_disposal_record`（一行 = 一个被处置的资产）

| 分组 | 列 |
|------|----|
| 资产 | `asset_id`（唯一索引） |
| 处置对象 | `target_type`（`asset`/`project`/`zone`）、`target_id` |
| 来源单据 | `source_order_id`（`disposal_order.id`）、`source_record_id`（`biz_disposal_record.id`） |
| 原权属快照 | `from_property_company_id`、`from_operating_company_id` |
| 处置信息 | `disposal_type`、`disposal_amount`、`amount_unit`、`disposal_date`、`disposal_user_id`、`disposal_user_name`、`remark` |
| 时点 / 审计 | `disposed_at`、`created_at`、`updated_at`、`created_by`、`updated_by` |

**设计取舍**

- **为什么单建台账**：① 处置对象有三个层级，「被处置的资产」是资产粒度，项目 / 分区的展开必须**在级联那一刻**发生（事后靠 JOIN 反推会因资产调拨 / 分区调整而错位）；② 处置**不可逆**，而 `biz_disposal_record` 走全量 diff **可被软删**——台账若不快照处置信息，删一张单据就会让已发生的处置在界面上变成空白。
- **没有 `deleted_at`**：处置不可逆，台账只增不减。
- **`amount_unit` 与金额成对**：资产级 `disposal_order.actual_amount` 是元，项目 / 分区级 `biz_disposal_record.amount_wan` 是万元，既有口径**不做换算**，用单位列显式标注。
- **`uk_asset_disposal_record_asset` 唯一索引**：既是幂等键（重复级联不得产生第二批副作用），也是并发双写兜底。

### 4.2 `asset.ownership_status` 新增取值

V54 建列时注释只写了 `in_group` / `transferred_out`。本迁移补 `disposed`：
`in_group` 集团内 / `transferred_out` 已对外转出（外部权属流转）/ `disposed` 已处置。

## 5. 写入路径

### 5.1 唯一入口

```
cascadeDispose(targetType, targetId, meta, sourceOrderId, sourceRecordId):
    assets = resolveAssets(targetType, targetId)      # asset → 单个；project/zone → 其下未软删资产
    for asset in assets:
        if asset.lifecycle_status == 'exited': continue      # 幂等：已退出即跳过
        ledger.from_property_company_id = asset.property_company_id   # 先快照
        ledger.from_operating_company_id = asset.operating_company_id
        insert(ledger)                                     # 先落台账，唯一索引先拦住并发第二个写入者
        asset.ownership_status = 'disposed'
        asset.lifecycle_status = 'exited'
        asset.property_company_id = null                   # 后清空
        updateById(asset)                                  # 乐观锁；返回 0 → 409，整单回滚
```

**经营公司刻意保留**：需求只要求脱离**产权**公司；同时 `OwnershipResolver.ofAsset` 读的是 `operating_company_id`，清空它会让资产从原经营主体的数据范围里消失（不是本次需求要动的东西）。

### 5.2 两个触发点

| 层级 | 触发 | 金额单位 |
|------|------|---------|
| 资产 | `DisposalService.complete`（处置单状态 → `completed`） | `yuan` |
| 项目 / 分区 | `RecordSheetService.syncDisposalRecords` **新增**一条 `biz_disposal_record` | `wan` |

**只在新增时级联**：编辑已有处置记录不重复处置（台账是处置那一刻的快照）；删除记录**不**回滚资产状态——要把产权公司还回去得走权属流转重新登记，不能让删一张记录表把已发生的处置抹掉。

### 5.4 租控写入的终态豁免（`LeaseControlService.transitionUnlessExited`）

`exited` 是租控状态机的**终态**，且 `canTransition` 既不允许自迁移（`from.equals(to)` 直接判非法）也不允许迁出终态。而 V57 起「处置完成」与「项目 / 分区级级联」都会把资产置为已退出 —— 于是**已处置的资产再也走不了处置流程**：

| 调用点 | 目标状态 | 旧行为 |
|--------|---------|--------|
| `DisposalService.onApproved` | `disposing` | 409「租控状态不允许从 exited 迁移到 disposing」 |
| `DisposalService.complete` | `exited` | 409「租控状态不允许从 exited 迁移到 exited」 |

用户可见表现：**资产一旦被处置，就无法再登记处置记录**（分次处置；或项目 / 分区级级联先退出、随后补登记资产级处置单）。

修法：新增 `LeaseControlService.transitionUnlessExited(...)` —— **资产当前已是 `exited` 时跳过租控写入并返回 `false`**（不留租控日志行，因为没有状态变化）；其余非法迁移照旧抛 409。

**豁免范围严格限定**：只豁免「资产当前已是 `exited`」。`leased → disposing` 仍然拒绝 —— 那才是「在租资产须先退租才能处置」这条**真实**规则，不能一并放宽。

### 5.3 存量回填

V57 之前已完成的**资产级**处置单（`disposal_order.status='completed'`）在当时**没有**动产权公司。迁移里做一次回填：

1. 按资产取**最新**一张已完成处置单（`DISTINCT ON (o.asset_id) ... ORDER BY o.id DESC`），写入台账（`amount_unit='yuan'`，`disposed_at=COALESCE(o.updated_at, now())`），`ON CONFLICT (asset_id) DO NOTHING`；
2. 同步资产状态：`ownership_status='disposed'`、`lifecycle_status='exited'`、`property_company_id=NULL`。

**刻意不回填项目 / 分区级的 `biz_disposal_record`**：旧语义下它是纯登记台账（很多只是「记录一下」），按台账行反向处置整个项目下的资产会产出**一批未经复核、且不可逆**的批量「脱离产权公司」。项目 / 分区级的级联自 V57 起只对**新增**的处置登记生效。

## 6. 读路径

`GET /api/v1/disposal-records`（`deed.disposalRecord:view`）：

- 筛选：`fromCompanyId`（原产权公司）、`targetType`、`disposalType`、`keyword`（资产编号 / 名称，经子查询 `asset` 命中，参数化绑定）
- 名称（资产 / 项目 / 分区 / 公司）**批量回填**，不逐行查

`GET /api/v1/disposal-records/{id}`：详情。**无写接口**——处置记录是已发生事实的快照，可编辑意味着可以伪造处置历史。

## 7. 不变量与验收

- **INV-1**：`asset.ownership_status = 'disposed'` ⟺ 存在 `asset_disposal_record` 行。
- **INV-2**：被处置资产的 `property_company_id IS NULL` 且 `lifecycle_status = 'exited'`。
- **INV-3**：一个资产至多一条 `asset_disposal_record`（唯一索引）。
- **INV-4**：`asset_disposal_record.disposal_amount` 非空时 `amount_unit` 必须非空且 ∈ `{yuan, wan}`。
- **INV-5**：`menu.code` = `deed.disposalRecord` ⟺ `menu.parent_id` 指向 `deed` 目录，且 `role_permission.menu_code` 无旧码残留。

```sql
-- INV-5：菜单归属与权限码必须一致（V58）
SELECT m.code, m.parent_id, d.code AS parent
  FROM menu m LEFT JOIN menu d ON d.id = m.parent_id
 WHERE m.code = 'deed.disposalRecord';
-- 期望：parent = deed，且下行必须为 0
SELECT count(*) FROM role_permission WHERE menu_code = 'operation.disposalRecord';
```

```sql
-- INV-1 / INV-2：被处置资产不得残留原产权公司
SELECT id, asset_no, ownership_status, lifecycle_status, property_company_id
  FROM asset
 WHERE ownership_status = 'disposed'
   AND (property_company_id IS NOT NULL OR lifecycle_status <> 'exited');

-- INV-1 反向：仍有产权公司的资产不得标记为已处置（同上查询已覆盖双向之一，此处查台账侧）
SELECT r.id, r.asset_id FROM asset_disposal_record r
  LEFT JOIN asset a ON a.id = r.asset_id
 WHERE a.id IS NULL;

-- INV-4：金额与单位必须成对
SELECT id, disposal_amount, amount_unit FROM asset_disposal_record
 WHERE (disposal_amount IS NOT NULL AND amount_unit IS NULL)
    OR (amount_unit IS NOT NULL AND amount_unit NOT IN ('yuan', 'wan'));
```

## 8. 测试

| 用例 | 覆盖 |
|------|------|
| `V57AssetDisposalRecordMigrationContractTest`（7） | 列集恰好 20 列、无软删列、三个索引、`ownership_status` 注释含 `disposed`、菜单 path 与 view 回填、存量回填且不回填项目/分区、幂等 |
| `AssetDisposalRecordServiceTest`（8） | 先快照后清空、经营公司保留、已退出跳过、乐观锁 409、项目 / 分区逐资产展开、非法层级 400、软删资产 404、名称批量回填 |
| `DisposalCompleteCascadeTest`（2） | 完成即级联（参数含单位 `yuan`）、状态非法时**不**级联 |
| `LeaseControlTerminalExemptionTest`（8） | 终态豁免跳过（不留日志）、空置 / 处置中照旧写入、**在租 → 处置中仍 409**、资产不存在 404、乐观锁冲突 409、根因固化（状态机确实拒绝 `exited` 自迁移/迁出） |
| `DisposalReDisposalTest`（3） | `onApproved` / `complete` 均走终态豁免入口、豁免返回 false 不中断业务主流程 |

## 9. 本次未交付

- **触点 7**：处置转 `AssetOccupancyService.occupy`（当前资产级仍走 `LeaseControlService.transition`；项目 / 分区级级联不写租控状态，只写 `lifecycle_status`）。
- 处置记录**导出**、**资产档案时间线**接入（`AssetDossier`）。
- 项目 / 分区级处置**审批**（保持既有口径：项目 / 分区处置为纯台账，不接审批引擎）。

## 10. 已确认决策：同一资产多次处置

**结论（产品已签字）：台账保持「一行 = 一个被处置的资产」**（`uk_asset_disposal_record_asset` 唯一索引不变）。

| 问题 | 结论 |
|------|------|
| 第二次处置是否新增行 | **否** —— 一行一资产，记录页保留**首次**处置的方式 / 金额 / 日期 |
| 第二次处置是否改写既有行 | **否** —— 台账是「处置那一刻」的快照，改写会丢掉首次处置的历史 |
| 第二次处置的留痕在哪 | 租控日志（`lease_control_log`）、审批中心（`approval_instance`）、以及 `disposal_order` 自身；台账**不**重复承担事件流水职责 |

**理由**：需求原文是「展示所有**被处置的资产**」——以**资产**为行才是这句话的直译；改成「一行 = 一次处置事件」会让同一资产在列表里出现多次，与需求表述相悖。「每次处置分别留痕」由租控日志与审批实例承担，台账只回答「哪些资产被处置了、从哪家公司处置出去的」。

## 11. 已确认决策：后续记录不再提供「新增处置记录」入口

**结论（产品已签字）：三种主体（资产 / 项目 / 分区）都移除后续记录面板里的「新增处置记录」按钮。**

| 变化 | 说明 |
|------|------|
| 移除 | `RecordSheetSections` 的处置段不再渲染「新增处置记录」按钮 |
| 保留 | 已存在的记录**仍可读、可改草稿（资产侧）、可删除**；资产侧四个流程按钮（提交审批 / 审批通过 / 执行 / 完成）**必须保留** |
| 空态文案 | 资产：「请在『资产处置』中登记并完成，记录会自动生成」；项目 / 分区：「新增入口已下线，处置记录由处置流程写入」 |

**理由**：处置是**由处置动作产生的事实**，不是可以随手追加的台账行。面板上点几次就能造出多张处置卡，会让这份台账与真实发生过的处置脱钩。

**为什么流程按钮必须保留**：`submit` / `approve` / `execute` / `complete` 四个动作在前端**只**出现在本面板（全仓唯一调用点是 `RecordSheetSections.runFlowActions` 的 `/disposals/{id}/{action}`）。若把整个处置段变成只读展示，**处置状态机将不可达**。因此本次只摘掉「新增」，不把面板变成只读。

**已知后果（实施时已确认接受）**：项目 / 分区处置记录**失去唯一的 UI 录入入口** —— 后端 `record-sheet` 的处置段与级联逻辑保持不变（仍可通过接口写入，且是项目 / 分区级联处置的唯一路径），前端入口待后续产品补充（例如独立的分区处置登记流程）。
