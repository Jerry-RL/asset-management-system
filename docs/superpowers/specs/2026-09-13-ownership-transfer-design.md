# 权属流转 设计

**日期**：2026-09-13
**状态**：待用户复核
**范围应用**：`backend`（1 个迁移 `V54` + 新模块 `com.ams.modules.ownership` + 事件/通知/档案联动）+ `frontend/admin-web`
**关联需求**：`docs/需求规格说明书.md` §4.6 资债权证（FR-CERT-001/002 的延伸：§4.6 只定义了单资产「资产调拨」，本设计补多资产「权属流转」）
**关联设计**：

- `docs/superpowers/specs/2026-09-12-record-forms-design.md`（§4.4 内员/外部人员口径、§6.4 `AttachmentField`、`biz_attachment` 多态宿主）
- `docs/superpowers/specs/2026-09-12-project-zone-management-design.md`（§3.2 前端注册清单 6 处、菜单 `view` 回填口径）
- `docs/superpowers/specs/2026-09-13-operation-log-design.md`（写接口必须 `@Audited`）
- `docs/adr/0019-asset-unit-and-occupancy-model.md`（「已退出」是生命周期终态，不是占用状态 —— 决定 §5.4）

---

## 1. 背景与目标

「资债权证」目录下已有的「资产调拨」（`asset_transfer`）只覆盖**单资产、调经营公司**这一条口径；而产权在集团内公司之间划转、或对集团外单位有偿/无偿转出，需要**一张单同时处理多个资产**，并留全字段的流转档案（方向、权属类型、金额、原因、凭证附件）。

**目标**：新增侧栏页「权属流转」（`/ownership-transfers`），一张单可选择**多个资产**，按「权属类型」决定改写资产的产权公司 / 经营公司，并把结果回写到资产档案时间线。

**核心动作一句话**：草稿里选定「原公司 → 新公司 + 权属类型 + 一批资产」，点「生效」把每个资产的对应公司字段改成新公司。

---

## 2. 已确认决策

用户逐条确认，理由列一并记录，供实现与评审对照。

| # | 项 | 决策 | 理由 |
|---|----|------|------|
| D1 | 审批 | **本期不接审批引擎** | 用户先选「接引擎」，随后改选「砍范围」，明确本轮只做「登记 + 改产权」，审批、收件箱联动、超期提醒、撤回全部往后放（见 §3.2） |
| D2 | 目标公司 | **两种方向都从公司下拉选「新产权公司」** | 用户确认；外部受让方需先在组织架构里建公司档案，不引入「自由文本受让方」第二套口径 |
| D3 | 流转方向的作用 | **参与校验 + 带动行为** | 用户确认；内/外由公司树判定（§5.2），且外部流转额外给资产打「已对外转出」标记 |
| D4 | 权属类型 → 字段 | **产权→只改 `property_company_id`；经营权→只改 `operating_company_id`；经营权且产权→两个都改** | 用户确认；与 `asset` 表已有字段一一对应，不新增业务字段 |
| D5 | 资产下拉 | **远程搜索 + 分页（每页 50），按所选原公司联动过滤，展示 `项目 · 分区 · 楼层 · 资产名称`** | 用户确认；资产量大时不预加载全量 |
| D6 | 前置约束 | **抵押中禁止流转（复用 `assertNotMortgaged`）；在租资产允许流转**，生效时生成交接清单快照；账单/合同归属**本期不自动迁移** | 用户确认；与 `TransferService` 的实际行为一致（SRS 写的「归属同步迁移」目前并无实现） |
| D7 | 页面形态 | **独立列表页 + 独立表单页** | 用户确认；`ResourcePage` 的 `fields` 只有 text/number/date/select/textarea/boolean，表达不了「资产远程多选 + 4 段 label」与附件字段（§6.1） |
| D8 | 状态机 | **`draft → completed`；先存草稿（可改可删），点「生效」才改产权** | 用户确认；多资产跨法人变更不应一次点错就落库 |
| D9 | 附件 | **保留**，复用 `AttachmentField` + `biz_attachment` | 用户确认；组件与多态宿主表都已存在，接入近零成本，而权属变更缺凭证是实质缺失 |
| D10 | 审批截止时间 | **保留字段，降级为纯记录**：不校验、不提醒 | 用户确认；本期无审批环节，该字段仅作业务留痕 |
| D11 | 与「资产调拨」的关系 | **并存，职责分工**：调拨 = 单资产调经营公司；权属流转 = 多资产调产权/经营公司 | 用户确认；不动存量 `asset_transfer` 数据 |

---

## 3. 范围

### 3.1 本期做

- 迁移 `V54`：2 张新表 + `asset` 加 1 列 + 3 个字典（各含字典项）+ 菜单行 + `view` 回填 + 1 条通知模板
- 后端新模块 `com.ams.modules.ownership`：列表 / 详情 / 资产下拉 / 新建 / 改 / 删 / 生效
- 生效时：按权属类型改资产公司字段、外部流转打标记、生成交接清单快照、发事件与通知
- 资产档案（一物一档）时间线新增「权属流转」类型
- 前端：列表页、表单页、8 处注册清单、字典/状态标签

### 3.2 本期明确不做

| 不做项 | 原因 | 后续接入点 |
|--------|------|------------|
| 接 `ApprovalEngine`、审批收件箱联动 | 用户裁决砍范围 | 加 `POST /{id}/approve` + `onApprovalCompleted`；`status` 增加 `approving`（见 §10 决策记录 D1） |
| 超期提醒（定时扫描 + 站内通知） | 依赖审批环节 | 依赖 `approving` 状态 |
| 撤回申请 | 引擎无 `cancel`，撤回会留下永久 `pending` 实例 | 需先给 `ApprovalEngine` 加 cancel |
| 生效后撤销（回滚产权） | 用户选择「草稿 + 生效」而非「草稿 + 生效 + 撤销」 | 需引入反向单据或原值快照回写 |
| 账单 / 合同 / 数据范围自动迁移 | `AssetTransferredEvent` 目前只触发通知，无迁移监听器；`TransferService` 同样未做 | 领域事件 + 监听器 |
| 金额按资产分摊 | 用户未要求 | — |
| 草稿之间的并发占用（预留行） | **刻意不做**：本仓已为「预留永不收口」付过代价（见 `OccupationService.withdraw` 的注释、改造清单 P0-5），草稿不写预留才不会重犯；冲突改由「生效时按原公司重新校验」自然消解（§5.3 规则 4） | — |

---

## 4. 数据模型

### 4.1 主单 `ownership_transfer`

沿用 `disposal_order` / `asset_transfer` 的「无单号、界面用 `#id`」风格；无外键约束（与 `disposal_order`、`asset_transfer` 一致，靠应用层断言）。

| 列 | 类型 | 说明 |
|----|------|------|
| `id` | BIGSERIAL PK | |
| `direction` | VARCHAR(20) NOT NULL | 流转方向：`internal` / `external`，字典 `transfer_direction` |
| `transfer_scope` | VARCHAR(20) NOT NULL | 权属类型：`both` / `property` / `operating`，字典 `transfer_scope` |
| `from_company_id` | BIGINT NOT NULL | 原产权公司（`company.id`） |
| `to_company_id` | BIGINT NOT NULL | 新产权公司（`company.id`） |
| `transfer_mode` | VARCHAR(30) NOT NULL | 流转类型：`allocate` / `purchase` / `auction`，字典 `transfer_mode` |
| `applicant_user_id` | BIGINT | 变更申请人（内员时为 `sys_user.id`，外部人员为 NULL） |
| `applicant_name` | VARCHAR(100) NOT NULL | 姓名快照（内员由后端用 `sys_user.name` 回填，外部人员手填） |
| `approval_deadline` | TIMESTAMPTZ | 审批截止时间（D10：纯记录，不校验不提醒） |
| `amount_wan` | NUMERIC(18,2) | 金额(万元)，2 位小数 |
| `reason` | VARCHAR(1000) | 流转原因 |
| `handover_json` | TEXT | 生效时生成的交接清单快照（欠费/保证金/预收/在租合同），形态沿用 `asset_transfer.handover_json` |
| `status` | VARCHAR(20) NOT NULL DEFAULT `draft` | `draft` / `completed` |
| `effected_at` | TIMESTAMPTZ | 生效时间 |
| `created_at` / `updated_at` / `created_by` / `updated_by` | | 审计四件套 |
| `deleted_at` | TIMESTAMPTZ | 软删；**仅 `draft` 可删** |

索引：`(status, id DESC)`、`(from_company_id)`

### 4.2 明细 `ownership_transfer_asset`

| 列 | 类型 | 说明 |
|----|------|------|
| `id` | BIGSERIAL PK | |
| `transfer_id` | BIGINT NOT NULL | |
| `asset_id` | BIGINT NOT NULL | |
| `from_property_company_id` | BIGINT | **生效时的原值快照**：生效后再流转 / 公司改名也能追溯 |
| `from_operating_company_id` | BIGINT | 同上 |
| `created_at` / `updated_at` / `created_by` / `updated_by` | | 审计四件套 |

唯一索引 `(transfer_id, asset_id)`；普通索引 `(asset_id)`（反查「某资产被哪些流转单改过」，资产档案与排查都要用）。

无 `deleted_at`：草稿改明细时按 `syncForAsset` 的做法**全量 diff、真删**——明细行未被任何外部对象引用（附件挂在主单上）。

### 4.3 `asset` 加列

```sql
ALTER TABLE asset ADD COLUMN IF NOT EXISTS ownership_status VARCHAR(20) NOT NULL DEFAULT 'in_group';
```

`in_group` 集团内 / `transferred_out` 已对外转出。**为什么必须新列**：外部流转生效后 `lifecycle_status = 'exited'`，而处置完成也是 `exited`；没有这一列就无法在资产上区分「卖掉了」和「转出去了」。

### 4.4 附件

复用 `biz_attachment`：新增枚举值 `AttachmentOwner.OWNERSHIP_TRANSFER("ownership_transfer", "transfer_attach")`，`owner_id = 主单 id`。`biz_attachment.biz_type` **必须取自 `AttachmentOwner.bizType()`**，不写字符串字面量（这是 record-forms 设计立下的约定 —— 枚举是 `biz_type` 的唯一来源；`check-record-contracts.mjs` 只校验「引用到的枚举常量都已声明」，不校验字面量，所以这条靠评审守）。

**需要先泛化 `RecordSheetService` 的附件读写口**：现有的 `syncOrderAttachments(Long, List)` / `orderAttachments(Long)` 内部**硬编码** `AttachmentOwner.DISPOSAL_ORDER`（`RecordSheetService.java:544-551`），是处置单专用方法，**不能**直接给权属流转复用（复用了会让权属流转的附件写进处置单的宿主）。做法：

1. 把私有的 `syncAttachments(AttachmentOwner, Long, List)` 与 `toAttachmentRefs(AttachmentOwner, Long)` 提为 `public`（或加一对 public 重载）；
2. 既有的 `syncOrderAttachments` / `orderAttachments` 保留为委托 `DISPOSAL_ORDER` 的薄包装，**不动处置侧调用点**；
3. 权属流转用 `syncAttachments(OWNERSHIP_TRANSFER, ...)` / `toAttachmentRefs(OWNERSHIP_TRANSFER, ...)`。

`OwnerEnumTest` 需按既有逐条断言风格补 `OWNERSHIP_TRANSFER` 的 `code()` / `bizType()` 两行断言。

**归属权限**：`AttachmentOwner` 的既有约定是「附件的读写权限跟宿主单据走」。本模块的附件的读写权限跟 `deed.ownershipTransfer:view` / `:update`，不跟资产。

### 4.5 字典（挂 `asset_management` 模块）

| `sys_dict_type.code` | 名称 | 项 |
|----------------------|------|-----|
| `transfer_direction` | 流转方向 | `internal` 内部流转 / `external` 外部流转 |
| `transfer_scope` | 权属类型 | `both` 经营权且产权 / `property` 产权 / `operating` 经营权 |
| `transfer_mode` | 流转类型 | `allocate` 直接划拨 / `purchase` 购买流转 / `auction` 拍卖流转 |

`sort` 取 16 / 17 / 18（已占用 1、2、3、9、10、12、13、14、15）。

### 4.6 菜单与权限种子

```sql
INSERT INTO menu (code, name, menu_type, path, icon, sort, parent_id)
SELECT 'deed.ownershipTransfer', '权属流转', 'menu', '/ownership-transfers', NULL, 45, d.id
FROM menu d WHERE d.code = 'deed'
ON CONFLICT (code) DO NOTHING;
```

- 归属目录 `deed`，`sort = 45` → 排在「评估申请」(40) 之后。
- 目录名此时已是「资债权证记录」（依赖 `V53__rename_deed_menu.sql`；本迁移只按 `code = 'deed'` 定位，不依赖名称，故两份迁移的先后顺序不影响正确性，但**必须都先于本迁移提交**）。
- `icon` 留空：与 V45 口径一致（图标只给目录；菜单为空时侧栏回退前端 `PATH_ICONS`）。
- **`view` 回填**：与 V45 §5.1 / V47 同口径 —— 只回填 `view`、显式排除 `super_admin`。不回填则除超管外所有角色看不到入口，新功能表现为「没做出来」。**动作级（create/update/delete）刻意不回填**：V45 §5.3 已把动作级回填列为上线前置人工步骤，误回填的宽权限是静默的且会长期留在库里。

### 4.7 通知模板

```sql
INSERT INTO notification_template (code, channel, title_tpl, body_tpl)
SELECT 'ownership_transferred', 'in_app', '资产权属流转已完成',
       '资产 #{{assetId}} 权属已由公司 #{{fromCompanyId}} 流转至公司 #{{toCompanyId}}，方向 {{direction}}，请双方对账'
WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE code = 'ownership_transferred');
```

写法沿用 V44 §`asset_transferred`（无参数字典表）。

---

## 5. 后端

### 5.1 模块与接口

新包 `com.ams.modules.ownership`（对齐 `disposal` / `occupation` / `selfuse` 的「一个业务单 = 一个模块」分层）：`entity` / `mapper` / `dto` / `service` / `controller`。

| 方法 | 路径 | 权限码 | `@Audited(module, action)` |
|------|------|--------|---------------------------|
| GET | `/api/v1/ownership-transfers` | `deed.ownershipTransfer:view` | — |
| GET | `/api/v1/ownership-transfers/asset-options` | `deed.ownershipTransfer:view` | — |
| GET | `/api/v1/ownership-transfers/{id}` | `deed.ownershipTransfer:view` | — |
| POST | `/api/v1/ownership-transfers` | `deed.ownershipTransfer:create` | `ownership_transfer`,`create` |
| PUT | `/api/v1/ownership-transfers/{id}` | `deed.ownershipTransfer:update` | `ownership_transfer`,`update` |
| DELETE | `/api/v1/ownership-transfers/{id}` | `deed.ownershipTransfer:delete` | `ownership_transfer`,`delete` |
| POST | `/api/v1/ownership-transfers/{id}/effect` | `deed.ownershipTransfer:update` | `ownership_transfer`,`effect` |

**路由顺序注意**：`/asset-options` 必须声明在 `/{id}` **之前**，否则会被 `@PathVariable Long id` 抢匹配并因 `"asset-options"` 无法转 `Long` 而 500。

列表查询参数：`page`、`pageSize`、`status`、`direction`、`fromCompanyId`、`keyword`（匹配原因 / 申请人姓名）。返回 `PageResult<OwnershipTransferView>`，`view` 内联公司名、申请人、资产数、附件。

**`effect` 为什么用 `:update` 而不是 `:approve`**：本期没有审批环节，仓内 `:approve` 的语义特指「审批别人的单」（处置/占用/自用），滥用会让未来加审批时语义打架。仓内「推进状态机」一律用 `:update`（`operation.disposal` 的 execute / complete 即是）。代价是「能改草稿的人就能生效」——本模块面向的是一线权属岗，接受；若后续要分离，只需新增 `:effect` 或改用 `:approve`。

### 5.2 方向判定：内 / 外

**不新增任何表或列**，用现成的公司树（`CompanyTreeService`）：

```
原公司 的根 = ancestorIds(from_company_id)[0]
新公司 的根 = ancestorIds(to_company_id)[0]
同根 → 内部流转（internal）        不同根 → 外部流转（external）
```

- `CompanyTreeService.ancestorIds()` 已存在且含防环保护；方法内部已容忍「上级不存在」的脏数据（按根处理）。
- 外部受让方在组织架构建档时 `parent_id` 留空 → 自成一根 → 天然判为**外部**。这正是 D2 允许「外部受让方也走公司下拉」的前提。
- **不**用 `CompanyTreeService.rootCompany()`：它取 `parent_id IS NULL` 中 `sort,id` 最小的那一行，一旦有第二棵树（外部公司）就可能取错。
- **不**用 `company_type` 字典：V22 定义的值是 `provincial_sasac` / `public_institution` / `state_owned` / `private_enterprise`，而演示种子写的是 `group` / `subsidiary`，两边本就不一致，不能作为判定依据。

校验要求**方向与公司树一致**：选了 `internal` 而目标公司在树外 → 400；反之亦然。

### 5.3 业务规则

**新建 / 编辑草稿**（`create` / `update`）

1. `status` 必须为 `draft`，否则 `CONFLICT`（「只有草稿可改」）。
2. `from_company_id`、`to_company_id`、`direction`、`transfer_scope`、`transfer_mode` 必填；`to ≠ from`；两个公司都必须**存在且启用**（复用 `CompanyTreeService.isActiveCompany` —— 不校验的话 `ancestorIds` 对不存在的公司会把它自己当成根，静默通过方向校验）。
3. 方向与公司树一致性校验（§5.2）。
4. 资产明细 **≥ 1 条**、去重、且每条：
   - 资产存在、未软删、`lifecycle_status ≠ 'exited'`；
   - **按 `transfer_scope` 对应的字段**属于 `from_company_id`：
     `property` / `both` 校验 `property_company_id`；`operating` 校验 `operating_company_id`；
   - `certificateService.assertNotMortgaged(assetId)`（在押资产禁止流转，与处置/调拨同口径）。
5. 申请人：DTO 字段 `applicant`（`{ userId?, name }`）走 `ActorField` 口径 —— 内员（`userId` 非空）由后端用 `sys_user.name` **覆盖** `applicant_name` 快照；外部人员只存 `name` 且要求非空。
6. 附件 ≤ 10（与 `AttachmentField` 的 `maxCount` 一致，服务端兜底）。
7. `amount_wan` 若传入，小数位 > 2 直接 400 —— **不要**依赖 `NUMERIC(18,2)`，PG 会静默四舍五入，用户看到的金额与实际入账不一致。

**草稿之间不互相阻塞**（§3.2）：保存草稿不写任何预留行、不检查「该资产是否已被另一张草稿选中」。两个人可以同时对同一资产起草；谁先生效谁赢，第二个人生效时会在规则 4 上失败（该资产的产权公司已不是本单的原公司）。

**删除草稿**：软删（`deleted_at`）；非 `draft` 一律拒绝。

**生效**（`POST /{id}/effect`，`@Transactional`）

1. 单据必须处于 `draft`，否则 `CONFLICT`（幂等兜底：`completed` 直接返回，不重复改资产）。
2. **重跑 §5.3 的全部校验**（草稿可能已躺了很久，公司树、抵押、资产公司都可能变过）。这一步同时是「防重」机制。
3. 逐资产写入：

   | `transfer_scope` | 写入 |
   |------------------|------|
   | `property` | `property_company_id = to_company_id` |
   | `operating` | `operating_company_id = to_company_id` |
   | `both` | 两个都写 |

4. `direction = external` 时额外：`ownership_status = 'transferred_out'`、`lifecycle_status = 'exited'`。
5. 明细行写 `from_property_company_id` / `from_operating_company_id` **原值快照**（在改写之前取）。
6. 生成交接清单 JSON 快照（欠费 / 保证金 / 预收 / 在租合同），字段口径沿用 `TransferService.buildHandover`；结果写回主单（复用 `asset_transfer.handover_json` 的 JSON 字符串形态，存 `TEXT`）。
7. `status = 'completed'`、`effected_at = now()`。
8. 逐资产发布 `OwnershipTransferredEvent(transferId, assetId, fromCompanyId, toCompanyId, direction)`。

**并发**：资产用 `asset.version` 乐观锁（`assetMapper.updateById` 返回 0 即抛出 `CONFLICT`「资产已被并发修改，请刷新重试」），整单事务回滚 —— 不会出现「一半资产改了、一半没改」。

### 5.4 外部流转为什么**不**改租控状态

**不改 `lease_control_status`。** 两条硬理由：

1. `LeaseControlStatus.canTransition` 只允许 `DISPOSING → EXITED`。外部流转直接置「已退出」会被状态机以 `CONFLICT` 拒绝；要绕过就得先造一个 `DISPOSING`，那是伪造业务状态。
2. `ADR-0019` 已明确「已退出」是**资产生命周期终态**而非占用状态，落点是 `asset.lifecycle_status`（处置完成也是落到这里）。

所以外部流转的落点是 `lifecycle_status = 'exited'` + `ownership_status = 'transferred_out'`，租控状态保持原值不动。

> 用户原话是「租控置退出」，本设计按上述两条事实改为生命周期落点。这是**唯一一处刻意偏离用户措辞**的地方，已在 §10 登记。

### 5.5 草稿校验清单与生效校验清单同源

§5.3 的校验必须**只有一份实现**（一个 `validateDraft(transfer, assets)` 私有方法），`create` / `update` / `effect` 三处共用。仓内已为「同一语义两处判定漂移」付过代价（`replaceZones` vs `deleteProjectZone` 的 `assertZoneRemovable` 收敛过程，见分区管理设计 §2.1），本模块从一开始就不留第二份。

### 5.6 领域事件与通知

- 新事件类 `com.ams.platform.event.OwnershipTransferredEvent`（`extends DomainEvent`），与 `AssetTransferredEvent` 并列。
- **必须**同步 `EventTypeRegistry`（`EventTypeRegistry.class.getSimpleName()` 注册行）与 `EventTypeRegistryTest` 的事件名清单断言 —— 漏改会让 `EventTypeRegistryTest` 失败（这是好事，是它存在的意义）。
- `NotificationEventListener` 新增 `onOwnershipTransferred`，订阅 `OwnershipTransferredEvent`，复用模板 `ownership_transferred`，并按既有 8 类订阅的**幂等守卫**写法（同一事件重复投递不重复推送）。
- 每个资产一条事件（而非一张单一条）：通知与下游投影都按资产粒度消费。

### 5.7 资产档案联动

- `AssetDossierService` 新增 `ownershipTransfers` 段：注入 `OwnershipTransferAssetMapper`，按 `asset_id` 反查明细（`(asset_id)` 索引就是为这条查询建的），join 主单取状态/方向/金额/公司名，按主单 `id DESC`；软删的单据不显示。
- 该段需同时加进档案的 DTO/视图与 `AssetDossierService` 的时间线拼装（既有 `transfer` 那段的相邻位置）。
- 时间线新增一类 `ownership_transfer`，标题形如 `权属流转 #12`，与既有 `transfer`（调拨）**并列显示但类型不同**——两者语义不同（D11），合并成一类会让档案里无法区分。
- 前端 `pages/AssetDossierPage.tsx` 的时间线渲染需要为新类型加一个分支（图标 + 点击跳转到 `/ownership-transfers/:id`），否则该类条目会落进 default 分支、显示成无图标无链接的裸文本。
- `OwnershipTransferredEvent` 生效时发布，档案无需额外刷新逻辑（档案是读时聚合）。

---

## 6. 前端

### 6.1 为什么是独立页面而不是 `RESOURCES`

`ResourcePage` 的表单由 `FieldConfig[]` 驱动，`type` 只有 `text | number | date | select | textarea | boolean`；`optionsPath` 只能拉一个平铺选项列表、`optionsLabelExtraKey` 只能再拼**一个**额外字段。它**表达不了**：

1. 资产远程搜索多选 + 自定义 4 段 label（`项目 · 分区 · 楼层 · 资产名称`）；
2. 附件字段（没有 attachment 类型）；
3. 「原公司一改就清空已选资产并重拉下拉」的联动重置。

处置/占用/自用/评估/调拨那些 `RESOURCES` 配置是「assetId + 几个字段」的占位级表单，不构成可复用先例。故走 `AssetFormPage` / `ZoneDetailPage` 那类独立页面。

### 6.2 页面

**列表页 `/ownership-transfers`**

- 筛选：状态、流转方向、原产权公司、关键字；分页（URL 化，复用 `useListQuery`）。
- 列：`#id`、流转方向、权属类型、原公司 → 新公司、流转类型、申请人、金额(万元)、审批截止时间、状态、生效时间。
- 行展开：资产明细表（`项目 · 分区 · 楼层 · 资产名称`）。
- 操作（按状态 + 权限显隐）：新增、编辑（`draft` + `:update`）、删除（`draft` + `:delete`）、**生效**（`draft` + `:update`，二次确认「将把 N 个资产的产权/经营公司改为 X，操作不可撤销」）、查看。

**表单页 `/ownership-transfers/new`、`/ownership-transfers/:id/edit`**

- 字段：流转方向（`Select`，字典）、权属类型（`Select`，字典）、原产权公司（`Select`，`/org/companies`）、新产权公司（同上）、流转类型（`Select`，字典）、变更申请人（`ActorField`）、审批截止时间（`DatePicker showTime`）、金额(万元)（`InputNumber`，2 位小数）、流转原因（`TextArea`）、附件（`AttachmentField` accept 多类型、maxCount 10）、资产列表（`Select mode="multiple"`，远程搜索 `asset-options`，`labelInValue`，已选跨搜索保留）。
- **联动**：`原产权公司` 或 `权属类型` 变化 → 清空已选资产 + 重拉 `asset-options`（`companyId` + `scope` 是请求参数）。
- 保存为 `draft`；「生效」在列表页，不在表单页（避免「保存即落库」的误操作，D8）。
- 标签文案随「权属类型」自适应：`operating` 时显示「原经营公司 / 新经营公司」，否则「原产权公司 / 新产权公司」。

### 6.3 注册清单（8 处，缺一即静默失效或有门禁失败）

| # | 文件 | 变更 | 缺了的后果 |
|---|------|------|-----------|
| 1 | `App.tsx` | 3 条 `<Route>`：`ownership-transfers`、`ownership-transfers/new`、`ownership-transfers/:id/edit` | 点菜单落回首页 |
| 2 | `lib/routeRegistry.ts` | `STANDALONE_ROUTES` 加 `/ownership-transfers`（**只加菜单路径**；`/new` 与 `/:id/edit` 是钻取页，与 `/assets/create` 同口径不登记） | 侧栏过滤掉该入口（DB 行被当脏数据忽略） |
| 3 | `lib/pathToCode.ts` | 在「资债权证」段加 `'/ownership-transfers': 'deed.ownershipTransfer'` | `canByPath` 恒真 → 页面级守卫静默失效；降级菜单缺入口 |
| 4 | `pages/modules.tsx` | `MENU` 的「资债权证记录」组加菜单项 | 接口失败时的降级静态菜单缺入口 |
| 5 | `lib/menuIcons.tsx` | `PATH_ICONS['/ownership-transfers']` 给一个图标（建议 `SwapOutlined`） | 回退成通用图标 |
| 6 | `pages/AssetDossierPage.tsx` | 时间线新增 `ownership_transfer` 分支（图标 + 跳转） | 档案里的权属流转条目变成无图标无链接的裸文本 |
| 7 | `lib/labels.ts` | `TRANSFER_DIRECTION` / `TRANSFER_SCOPE` / `TRANSFER_MODE` / `OWNERSHIP_TRANSFER_STATUS` 标签表 | 列表与详情显示原始英文码 |
| 8 | `V54` | 菜单行 + `view` 回填 | 菜单不存在 → 接口 403 |

`scripts/check-perm-invariants.mjs` 会硬失败第 2、3 项遗漏（镜像完整性 + `STANDALONE_ROUTES` 必须有对应 `<Route>`）。

**权限声明约束**：前端任何 `perm` 声明必须是后端 `@RequiresPerm` 里存在的码（`check-perm-invariants.mjs` 第 2 条硬失败）。故 `:create` / `:update` / `:delete` 三个码必须真的挂在 §5.1 的接口上，不能只在页面上声明。

---

## 7. 与既有模块的边界

| 维度 | 资产调拨 `asset_transfer`（既有） | 权属流转 `ownership_transfer`（本次） |
|------|-----------------------------------|---------------------------------------|
| 资产范围 | 单资产（`asset_id` NOT NULL） | 多资产（明细表） |
| 改的字段 | `operating_company_id` | 按 `transfer_scope` 改 `property_company_id` / `operating_company_id` |
| 流转类型语义 | `with_contract` / `vacant`（在租与否） | `allocate` / `purchase` / `auction`（对价形态） |
| 状态机 | `draft`/`approving`/`approved`/`completed` | `draft`/`completed` |
| 审批 | 自有 `/asset-transfers/{id}/approve` 端点，**未接** `ApprovalEngine` | 本期无审批（D1） |
| 附件 | 无 | `biz_attachment` 多态宿主 |
| 资产档案时间线 | `transfer` 类型「调拨 #id」 | `ownership_transfer` 类型「权属流转 #id」 |

**并存，不合并**（D11）：不动存量 `asset_transfer` 数据，两个菜单项并列。「资产调拨」本身的既有问题（§10 观察 2）不在本设计范围内。

---

## 8. 权限模型

| 操作 | 判定码 | 判定方式 | 对应接口 |
|------|--------|----------|----------|
| 进入列表/表单页 | `deed.ownershipTransfer:view` | `AdminLayout` 的 `<RequirePerm>`，由 path 经镜像推导 | — |
| 列表 / 详情 / 资产下拉 | `deed.ownershipTransfer:view` | 是否发起请求 | `GET /ownership-transfers*` |
| 新增草稿 | `deed.ownershipTransfer:create` | `usePerm('deed.ownershipTransfer','create')` | `POST /ownership-transfers` |
| 编辑草稿 / **生效** | `deed.ownershipTransfer:update` | 同上 | `PUT /{id}`、`POST /{id}/effect` |
| 删除草稿 | `deed.ownershipTransfer:delete` | 同上 | `DELETE /{id}` |

**`view` 由迁移回填给所有非超管角色；三个写动作不回填**（§4.6），上线时按 V45 §5.3 的前置人工步骤授权。

---

## 9. 测试与门禁

### 9.1 自动化测试

| 类型 | 文件 | 覆盖 |
|------|------|------|
| 迁移契约 | `V54OwnershipTransferMigrationContractTest` | 2 张表的列与声明、4 个索引（两表各 2 个）、3 个字典 × 9 个字典项、菜单行 + `view` 回填范围、`asset.ownership_status` 的默认值、通知模板 |
| 迁移链 | `MigrationChainPostgresTest` | 真库上 `V54` 落库结果（仿既有 V51/V52 的断言块）+ `MIN_MIGRATIONS` 计数 |
| 权限 | `OwnershipTransferPermissionTest` | 只有 `:update` 不能新增（403）；只有 `:create` 不能生效（403）；无 `:view` 不能读列表 |
| 业务 | `OwnershipTransferServiceTest` | 方向 × 公司树 4 种组合；`transfer_scope` × 字段映射 3 种；在押拦截；资产不属于原公司拦截；非 `draft` 不可改/删/生效；生效幂等；生效重跑校验（草稿期间资产被改 → 生效失败）；`asset.version` 乐观锁冲突回滚；外部流转打标记 + `lifecycle_status='exited'` 且**不改租控**；内部流转不改 `ownership_status`；`amount_wan` 超 2 位小数被拒；不存在的公司被拒 |
| 附件宿主 | `OwnerEnumTest`（扩充） | 新枚举值 `OWNERSHIP_TRANSFER` 的 `code()` / `bizType()` 逐条断言 |
| 附件读写 | `RecordSheetServiceTest`（扩充） | 泛化后的 `syncAttachments` / `toAttachmentRefs` 按 `OWNERSHIP_TRANSFER` 读写**不串到处置单宿主**；处置侧既有断言仍全绿 |
| 事件 | `EventTypeRegistryTest`（扩充） | 新事件已注册 |

### 9.2 门禁命令（全绿才算完成）

```bash
cd frontend && pnpm --filter admin-web build && pnpm lint && pnpm format:check
cd frontend && pnpm check:perm && pnpm check:audit && pnpm check:backend && pnpm check:record
cd frontend && pnpm test:list-query   # 列表 URL 参数改造的既有断言
cd backend && mvn -q test
```

- `pnpm lint` 基线是**恰好 4 warning / 0 error**，不得新增。
- `check:audit` 要求 §5.1 的每个写接口都带 `@Audited`（新增接口漏注解会 exit 1）。

### 9.3 手工 E2E（交给人工，子代理不用浏览器）

1. `/ownership-transfers` → 新增 → 选「内部流转 + 产权 + 原公司 A + 新公司 B」→ 资产下拉只能选到 A 名下资产，label 为 `项目 · 分区 · 楼层 · 名称` → 保存草稿。
2. 点「生效」→ 列表状态变「已完成」；去 `/assets` 查这些资产，`property_company_id` 已变 B，`ownership_status` 仍 `in_group`，租控状态**未变**。
3. 外部流转那单生效后：资产 `ownership_status = 已对外转出`、`lifecycle_status = 已退出`。
4. 抵押中的资产无法被选入 / 生效被拒（提示「资产处于在押状态，须先解押」）。
5. 在押解除后重试可生效。
6. 草稿 A 生效后，另一张含同一资产的草稿点生效 → 被拒（该资产产权公司已不是其原公司）。
7. 一物一档时间线出现「权属流转 #id」条目，且与既有「调拨 #id」并列可区分。
8. 原公司下拉改选 → 已选资产被清空、下拉重新按新公司拉取。
9. 无 `:update` 的账号看不到「编辑」「生效」按钮，直接调接口返回 403。

---

## 10. 决策记录与遗留

### 10.1 对本设计的三处刻意修正（均已与用户对齐）

| # | 用户原措辞 | 本设计 | 依据 |
|---|-----------|--------|------|
| 1 | 「外部流转……租控置退出」 | 改为 `asset.lifecycle_status = 'exited'`，**租控不动** | `LeaseControlStatus.canTransition` 仅允许 `DISPOSING→EXITED`；ADR-0019 已把「已退出」归还给生命周期 |
| 2 | 「权属状态标记」（未指定落点） | 新增 `asset.ownership_status` 列 | 否则「已对外转出」与「已处置」都是 `lifecycle_status='exited'`，无法区分 |
| 3 | 「项目-分区-楼层-**项目**名称」 | 第 4 段按**资产名称**实现 | 前 3 段已含「项目」，第 4 段重复；按语义与 `Asset.name` 对齐 |

### 10.2 范围变更记录

- 第一轮用户选「本期接审批引擎」（含 `draft→approving→completed` + 收件箱 + 超期提醒 + 撤回）。
- 随后用户改选「砍范围，本期只做登记 + 改产权」，并明确「草稿 + 生效」（D8）与「保留附件」（D9）。
- 因此本设计**没有** `approving` 状态、**没有** `approval_instance_id` / `applied_at` / `reject_reason` 列（第一轮方案里的这三列一并去掉，不留悬空字段）。

### 10.3 实现时值得注意的观察（不在本设计范围内）

1. **处置的审批链路是半接的**（本次未修，仅记录）：`DisposalController.approve` 直接调 `disposalService.onApproved()`，**没有**调 `approvalEngine.approve()` —— 处置单审批完，`approval_task` 里那条待办仍是 `pending`（永远挂在收件箱）；反过来从审批收件箱点通过（走通用 `/approvals/{instanceId}/approve`）又会发 `ApprovalCompletedEvent`，但没有任何处置侧监听器响应，处置单状态不推进。两条路各走一半。将来给权属流转加审批时，**不要**照抄这个写法，应当让业务端点委托引擎、由 `onApprovalCompleted` 统一落库。
2. **「资产调拨」的 SRS 承诺未实现**：SRS §4.6.1 写了「调拨后账单归属/数据范围同步迁移」，实际 `AssetTransferredEvent` 只有一个通知监听器，没有任何迁移监听器。本设计的 D6 与之一致（只出交接清单快照），不为权属流转单独补迁移。
3. **`approval_deadline` 本期是纯记录字段**（D10）。若将来恢复审批，它应当同时驱动「超期提醒」并将 `status` 扩展为 `approving`。
