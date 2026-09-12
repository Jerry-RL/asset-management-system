# 资产 / 项目 / 项目分区「后续记录」表单 设计

**日期**：2026-09-12
**状态**：已确认，待实现
**范围应用**：`backend`（`com.ams.modules.asset`、`com.ams.modules.disposal`、`com.ams.modules.record`、`com.ams.platform.security`、`com.ams.platform.storage`）+ `frontend/admin-web`
**关联需求**：`docs/需求规格说明书.md` FR-AST-001/002/003、FR-CERT-001/002、FR-DISP-*；ADR `0007-object-storage-attachments`、`0009-lightweight-approval-engine`、`0015-data-classification-and-encryption`、`0019-asset-unit-and-occupancy-model`
**关联设计**：`docs/superpowers/specs/2026-09-12-menu-and-role-permission-design.md`（动作词表、`@RequiresPerm` 口径、对象级数据范围）

---

## 1. 背景与问题

需求：在「新增 / 编辑资产」表单后补充一个步骤，包含三个模块；同样三个模块也要挂到「项目」和「项目分区」上。

| 模块 | 字段 |
|------|------|
| 处置记录 | 处置类型、处置人、处置金额(万元)、处置日期、备注、附件（多个） |
| 接收信息 | 交接类型、文档名称、交接人、交接文件、遗留问题 |
| 遗留问题（动态列表，接收信息的子表） | 问题类型、问题描述、发现人、现场文件 |
| 来源信息 | 来源人、来源单位、来源日期、来源描述、附件 |

代码核对后确认的现状与缺口：

| 维度 | 现状 | 问题 |
|------|------|------|
| 资产表单 | `AssetFormPage.tsx` 为**单页卡片**（归属/基本/属性/管理/计量 5 张卡），底部一个提交按钮 | 没有「下一步」概念，需先改造成分步表单 |
| 项目表单 | `ProjectFormPage.tsx` 为**两步** `Steps`，第二步是分区**内联表格** | 塞不下三模块；分区没有独立入口 |
| 项目分区 | 后端**已有分区 CRUD**（`GET/POST /projects/{id}/zones`、`PUT/DELETE /projects/{id}/zones/{zoneId}`，权限 `asset.project:view/update`，写接口均 `@Audited`）；另有项目 PUT 的 `zones` 数组做全量保存 | 缺**分区详情页**，三模块无处录入；三条删除路径语义不一致：两条各自为政且**都是物理删除**，第三条（删项目）绕过守卫并硬删全部分区（见 §7.3） |
| 处置 | `disposal_order` + `DisposalController`（submit/approve/execute/complete），`asset.lifecycle_status` 处置完成置 `exited`；`disposal_order.asset_id` **NOT NULL** | 资产专属、带审批与生命周期语义，套不到项目/分区 |
| 接收信息 | SRS §4.6 定义为「资产接收、移交建档记录」（FR-CERT-001）；代码侧只有 `asset_transfer.handover_json`（交接清单 JSON） | **无表、无接口、无页面**；与调拨单职责重叠 |
| 来源信息 | 资产已有 `source_type` 字段 + `asset_source` 字典（含 移交资产/划入/托管…）；项目/分区无此字段 | 命名与概念撞车（同一表单会出现两个「来源」） |
| 附件 | 只有 `file_metadata` 表 + 各表**单个** `file_id` 列（少数用 `VARCHAR` 存 id 列表）；前端 `ImageUploadField` 只支持单图 | **无多附件能力**，「附件（多个）」无落点 |
| 删除口径 | 全局 `logic-delete-field: deleted`，但表范式是 `deleted_at`，实体未映射 → 逻辑删除**实际不生效** | `AssetService.replaceZones` 对未提交分区执行 `deleteBatchIds` 是**物理删除**，分区挂记录后会产生孤儿或外键冲突 |
| 需求文档 | `docs/需求规格说明书.md` 检索不到 来源人/来源单位/处置金额/交接类型/问题类型/发现人 | 需求未入册，无 FR 编号 |

**目标**：

1. 三种主体（资产 / 项目 / 项目分区）共用一套「后续记录」能力，避免 3×3=9 张表。
2. 统一多附件模型，结束「一列一个 file_id」的散装做法。
3. 资产处置**复用**现有 `disposal_order` 审批闭环，不另起一套；项目/分区的处置只做台账。
4. 补充分区详情页（分区 CRUD 已存在，直接复用），为三模块提供录入入口。
5. 全部新接口接入 `@RequiresPerm` 与对象级数据范围校验，不复制 `CertificateController` 那种「无权限注解」的现状。

---

## 2. 已确认决策

| 项 | 决策 | 理由 |
|----|------|------|
| 三模块归属 | **共享表 + `owner_type(asset/project/zone) + owner_id`** | 一套表覆盖三种主体，避免 9 张表；代价是丢外键，用应用层校验 + 复合索引补偿 |
| 资产处置 | **复用 `disposal_order`**，扩展 `disposal_user_id / disposal_user_name / disposal_date / remark`，保留 submit→approve→execute→complete，仍驱动 `lifecycle_status` | 处置口径单一，不产生第二套处置数据 |
| 项目/分区处置 | **走共享台账表 `biz_disposal_record`**，不接审批、不影响生命周期 | 项目/分区不是资产生命周期对象，套 `exited` 无意义 |
| 表单内处置能力 | **可走完整流程**（新建草稿 → 提交审批 → 执行 → 完成） | 需求方明确要求在表单内闭环 |
| 接收信息 | **新建独立表**，1:N（多次交接）；遗留问题为其子表 | 与 `asset_transfer`（调拨单）职责分离：调拨是权属流转，接收是建档留痕 |
| 来源信息 | **1:1 明细补充**，不取代 `source_type` 字典字段；术语改用「来源明细」 | 保留「资产属性 → 资产来源」既有筛选与级联口径，不动字典关系 |
| 遗留问题 | **纯登记，不做整改闭环** | 本期只留痕；整改状态/责任人/期限列为后续 |
| 相对人取值 | **内员选择 + 允许外部文本**：`xxx_id`（可空）+ `xxx_name`（姓名快照）；交接人 / 发现人 / 处置人**必填**，来源人**可为空**（来源信息常只知来源单位而不知具体人） | 处置人/交接人/发现人/来源人都有外部的现实情况 |
| 附件 | **新建通用关联表 `biz_attachment`**，全模块复用 | 一处解决多附件、排序、用途区分 |

---

## 3. 术语与范围

| 术语 | 定义 |
|------|------|
| 主体（owner） | 记录挂载的对象，取值 `asset` / `project` / `zone` |
| 后续记录 | 处置记录 / 接收信息 / 来源信息 三者的统称 |
| 来源明细 | 「来源信息」模块的正式术语，避免与既有「资产来源」字典字段撞车 |
| 物理删除 vs 软删 | 新表统一用 `deleted_at` 软删；`deleted_at IS NULL` 为有效行 |

**范围**：三种主体 × 三模块的录入与维护、通用附件、分区详情页与分区删除守卫、字典种子、权限注解与数据范围。

**不在本期范围**：遗留问题整改闭环；项目/分区处置的审批流；附件孤儿清理定时任务；资产/项目详情页的展示改造（见 §12）；字典维护页改造（复用现有页面）。

---

## 4. 数据模型

### 4.1 通用约定

- 共享表统一 `biz_` 前缀（`biz` = 跨主体共享业务表），与资产专属表（`asset_*`、`disposal_order`）在命名上区分。
- 所有新表带审计列 `created_at / updated_at / created_by / updated_by`，以及 `deleted_at`。
- **不使用 `@TableLogic`**：全局 `logic-delete-field: deleted`（`application.yml:35`）与表范式 `deleted_at` 不一致，是已知缺陷（见 `MigrationService` §2.5#27 注释）。新实体沿用 `AssetUnitMapper` 的显式口径 —— 删除写 `deleted_at = now()`，查询显式 `isNull("deleted_at")`，并在实体 Javadoc 写明原因。
- **不建外键**，与现有库一致；一致性由服务层校验，配复合索引。
- `owner_type` 统一小写枚举，本期取值 `asset` / `project` / `zone`。

### 4.2 表 DDL（迁移 `V46__record_sheets.sql`）

```sql
-- ---------------- 通用附件关联 ----------------
CREATE TABLE IF NOT EXISTS biz_attachment (
    id          BIGSERIAL PRIMARY KEY,
    owner_type  VARCHAR(30)  NOT NULL,  -- asset/project/zone/receive_record/receive_issue/source_info/disposal_order/disposal_record
    owner_id    BIGINT       NOT NULL,
    biz_type    VARCHAR(40)  NOT NULL,  -- receive_doc/issue_scene/source_attach/disposal_attach
    file_id     BIGINT       NOT NULL,
    sort        INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    created_by  BIGINT,
    updated_by  BIGINT,
    deleted_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_biz_attachment_owner ON biz_attachment (owner_type, owner_id, sort);
CREATE INDEX IF NOT EXISTS idx_biz_attachment_file  ON biz_attachment (file_id);

-- ---------------- 接收信息（1:N） ----------------
CREATE TABLE IF NOT EXISTS biz_receive_record (
    id                 BIGSERIAL PRIMARY KEY,
    owner_type         VARCHAR(20)  NOT NULL,
    owner_id           BIGINT       NOT NULL,
    handover_type      VARCHAR(50),             -- 字典 sys_dict_type.code = handover_type
    doc_name           VARCHAR(200),
    handover_user_id   BIGINT,                  -- sys_user.id，外部人员为空
    handover_user_name VARCHAR(100),            -- 姓名快照，内员选择时后端回填
    handover_date      DATE,
    remark             VARCHAR(500),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ,
    created_by         BIGINT,
    updated_by         BIGINT,
    deleted_at         TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_biz_receive_record_owner ON biz_receive_record (owner_type, owner_id, id);

-- ---------------- 遗留问题（接收信息子表，1:N） ----------------
CREATE TABLE IF NOT EXISTS biz_receive_issue (
    id              BIGSERIAL PRIMARY KEY,
    receive_id      BIGINT        NOT NULL,
    issue_type      VARCHAR(50),              -- 字典 sys_dict_type.code = issue_type
    description     VARCHAR(1000),
    discoverer_id   BIGINT,                   -- sys_user.id，外部人员为空
    discoverer_name VARCHAR(100),
    sort            INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    created_by      BIGINT,
    updated_by      BIGINT,
    deleted_at      TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_biz_receive_issue_receive ON biz_receive_issue (receive_id, sort);

-- ---------------- 来源明细（1:1） ----------------
CREATE TABLE IF NOT EXISTS biz_source_info (
    id                 BIGSERIAL PRIMARY KEY,
    owner_type         VARCHAR(20)   NOT NULL,
    owner_id           BIGINT        NOT NULL,
    source_person_id   BIGINT,                  -- sys_user.id，外部人员为空
    source_person_name VARCHAR(100),
    source_unit        VARCHAR(200),            -- 自由文本：原产权/移交单位多为外部单位
    source_date        DATE,
    source_desc        VARCHAR(1000),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ,
    created_by         BIGINT,
    updated_by         BIGINT,
    deleted_at         TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_biz_source_info_owner
    ON biz_source_info (owner_type, owner_id) WHERE deleted_at IS NULL;

-- ---------------- 处置台账（项目 / 分区） ----------------
CREATE TABLE IF NOT EXISTS biz_disposal_record (
    id                 BIGSERIAL PRIMARY KEY,
    owner_type         VARCHAR(20)  NOT NULL,   -- 本期仅 project/zone
    owner_id           BIGINT       NOT NULL,
    disposal_type      VARCHAR(50),             -- 字典 sys_dict_type.code = disposal_type
    disposal_user_id   BIGINT,
    disposal_user_name VARCHAR(100),
    amount_wan         NUMERIC(18,2),           -- 处置金额(万元)
    disposal_date      DATE,
    remark             VARCHAR(500),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ,
    created_by         BIGINT,
    updated_by         BIGINT,
    deleted_at         TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_biz_disposal_record_owner ON biz_disposal_record (owner_type, owner_id, id);

-- ---------------- 资产处置：扩展现有表 ----------------
ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS disposal_user_id   BIGINT;
ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS disposal_user_name VARCHAR(100);
ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS disposal_date      DATE;
ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS remark             VARCHAR(500);
COMMENT ON COLUMN biz_disposal_record.amount_wan IS '处置金额(万元)，2 位小数';
COMMENT ON COLUMN disposal_order.disposal_date   IS '处置日期';
```

> `disposal_order.actual_amount` 的现有单位未在库中标注（V2 无 `COMMENT`）。本设计**不擅自改它的口径**；实现前需确认它当前存的是元还是万元，再决定是否补注释或做换算，避免与 `biz_disposal_record.amount_wan`（万元）混用。

**字典种子**（沿用 `V19` 的字典写法，带存在性守卫）：

| 字典码 | 名称 | 初始项 |
|--------|------|--------|
| `disposal_type` | 处置类型 | `sale` 出售 / `scrap` 报废 / `transfer` 划转 / `other` 其他 |
| `handover_type` | 交接类型 | `receive` 接收 / `handover` 移交 / `internal` 内部交接 |
| `issue_type` | 问题类型 | `ownership` 权属 / `certificate` 证照 / `facility` 设施 / `arrears` 欠费 / `other` 其他 |

> 现有 `disposal_order.disposal_type` 是硬编码 `sale/scrap/transfer`（无字典）。本期把它挂到 `disposal_type` 字典上，历史值不迁移（值域兼容）。

### 4.3 附件模型

- `biz_attachment.owner_type` 是**多态**的：除三种主体外，还包含子实体 `receive_record` / `receive_issue` / `source_info` / `disposal_order` / `disposal_record`，使「交接文件」「现场文件」「来源附件」「处置附件」共用一张表。
- `biz_type` 表达用途，与 `file_metadata.biz_type`（上传时的业务类型）职责不同：前者是「这笔附件属于哪个字段」，后者是「上传来源」。
- 写入流程：先 `POST /files/upload`（复用现有 `FileController`）拿到 `fileId`，再把 `{fileId, sort}` 内嵌进 record-sheet 请求体（或 `POST /disposals` 的请求体），由聚合写做**全量 diff**（`sort` 保序）。**不提供独立的附件 CRUD 端点**（理由见 §5.2）。
- **附件归属口径**（写入路径）：新增一条引用时，`fileId` 必须存在，且上传者（`file_metadata.created_by`）是当前用户本人，或 `created_by` 为空（应用启动 seed 没有安全上下文，历史 / 种子文件天然为空，允许被引用）。**已挂在该宿主上的旧引用不重复校验** —— 本期不做孤儿文件清理，存量脏引用（`file_metadata` 已被清理但关联行仍在）必须能原样往返，否则整张 record-sheet 再也保存不了。两条失败路径（不存在 / 非本人上传）用**同一个 `BAD_REQUEST` 与同一句文案**，不把 fileId 的存在性与归属变成可探测信息（与 §5.3 越权收敛同理）。
- 删除：diff 中未出现的关联行软删，**不删** `file_metadata`（可能被多处引用）。
- 孤儿文件清理：本期**不做**，登记为后续（需扫描无有效关联的 `file_metadata`）。

### 4.4 相对人字段口径（混合）

- 每个相对人字段成对出现：`xxx_id BIGINT`（可空，内员则填 `sys_user.id`）+ `xxx_name VARCHAR(100)`（姓名快照）。
- **必填范围**：交接人 / 发现人 / 处置人的 `xxx_name` **必填** —— `xxx_id` 为空时必须给出外部姓名，否则 400。**来源人可为空**：来源信息常只知来源单位而不知具体人；且「`isEmptySource` 全空 = 清空该行」这条语义与「来源人必填」互相矛盾 —— 若来源人必填，「传入全空对象」这个清空信号只能变成 400，无法表达清空。
- DB 列 `source_person_name VARCHAR(100)` **可空**，与上述口径一致。
- 前端组件 `ActorField`：默认搜系统内员工；搜不到时切「外部人员」手填姓名。
- 后端落库时：`xxx_id` 非空则用员工姓名**覆盖** `xxx_name`（存姓名快照），保证人员改名/离职后历史记录仍可追溯。

---

## 5. 后端设计

### 5.1 模块划分

新建 `com.ams.modules.record`，避免 `asset` 模块继续膨胀：

```
modules/record/
  controller/RecordSheetController.java     # 三主体的 record-sheet（GET/PUT）
  entity/BizAttachment.java
  entity/ReceiveRecord.java
  entity/ReceiveIssue.java
  entity/SourceInfo.java
  entity/DisposalRecord.java
  mapper/*.java
  dto/RecordSheetRequest.java               # 聚合写请求
  dto/RecordSheetView.java                  # 聚合读视图
  dto/AttachmentRef.java                    # {fileId, sort}
  service/RecordOwner.java                  # ownerType/ownerId 值对象 + 解析
  service/OwnerResolver.java                # ownerType/ownerId → 归属公司 + 权限码
  service/RecordSheetService.java           # 聚合读写 + 全量 diff
  service/RecordPresenceChecker.java        # 「该 owner 是否已有记录」（供分区删除守卫）
```

另新增 `modules/disposal` 侧：`DisposalOrderView`（含附件回显）+ `GET /assets/{id}/disposals`。
分区 CRUD 已落在现有 `AssetController` / `AssetService`（`modules/asset`）；本设计只在其上扩展 record-sheet 路由与「可删性」校验，不新建分区控制器。

### 5.2 接口清单

**设计取舍：三模块按「一张表单 = 一个聚合」暴露，而不是每个模块一组 CRUD。**

理由：§5.4 要求「宿主表单一次提交 = 一个事务，包含主体字段 + 三模块子记录 + 附件」。若同时提供「每模块独立 PUT」，就会出现两条语义冲突的写入路径（聚合提交与逐模块提交），并发下互相覆盖；而按模块拆 CRUD 会让三种主体 × 三个模块展开成近 40 个端点。因此聚合成**一张 record-sheet**：读一次、写一次。

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/v1/assets/{id}/record-sheet` | `asset.ledger:view` | 资产后续记录聚合读 |
| PUT | `/api/v1/assets/{id}/record-sheet` | `asset.ledger:update` | 资产后续记录聚合写（单事务，全量 diff） |
| GET/PUT | `/api/v1/projects/{pid}/record-sheet` | `asset.project:view/update` | 项目后续记录 |
| GET/PUT | `/api/v1/projects/{pid}/zones/{zoneId}/record-sheet` | `asset.project:view/update` | 分区后续记录 |
| GET | `/api/v1/assets/{id}/disposals` | `asset.ledger:view` | 该资产处置单列表（只读，流程走下面的既有端点） |
| POST | `/api/v1/disposals`（沿用） | `operation.disposal:create` | 新建处置单（draft） |
| POST | `/api/v1/disposals/{id}/submit`（沿用） | `operation.disposal:create` | 提交审批 |
| POST | `/api/v1/disposals/{id}/approve`（沿用） | `operation.disposal:approve` | 审批 |
| POST | `/api/v1/disposals/{id}/execute`（沿用） | `operation.disposal:update` | 执行 |
| POST | `/api/v1/disposals/{id}/complete`（沿用） | `operation.disposal:update` | 完成（置 `lifecycle_status=exited`） |

**附件不单独开端点**：文件本体走既有 `POST /files/upload` 取到 `fileId`，关联关系**内嵌在 record-sheet 的请求体里**（每条记录带 `attachments: [{fileId, sort}]`），随聚合写做全量 diff。这样附件与宿主的写入天然同事务，不会出现「记录存了、附件丢了」的半成品状态。

**record-sheet 请求体形状**（三段全量提交，`id` 存在即更新、缺失即新增、未出现即软删）：

```json
{
  "receives": [
    {
      "id": 12,
      "handoverType": "receive",
      "docName": "移交清单",
      "handoverUserId": 8,
      "handoverUserName": "张三",
      "handoverDate": "2026-08-01",
      "remark": "",
      "attachments": [{ "fileId": 101, "sort": 0 }],
      "issues": [
        {
          "id": 31,
          "issueType": "ownership",
          "description": "土地证未过户",
          "discovererId": null,
          "discovererName": "外部测绘单位",
          "attachments": [{ "fileId": 102, "sort": 0 }]
        }
      ]
    }
  ],
  "sourceInfo": {
    "sourcePersonId": 9,
    "sourcePersonName": "李四",
    "sourceUnit": "淮安市财政局",
    "sourceDate": "2026-07-15",
    "sourceDesc": "无偿划转",
    "attachments": [{ "fileId": 103, "sort": 0 }]
  },
  "disposalRecords": [
    {
      "id": null,
      "disposalType": "sale",
      "disposalUserId": 8,
      "disposalUserName": "张三",
      "amountWan": 1200.5,
      "disposalDate": "2026-08-20",
      "remark": "",
      "attachments": [{ "fileId": 104, "sort": 0 }]
    }
  ]
}
```

- **资产主体**：`disposalRecords` 段**被忽略**（资产的处置一律走 `disposal_order` 与上面的流转端点，保持审批与生命周期语义单一）。资产的 `disposal_order` 附件通过 `POST /disposals` 请求体里的 `attachments` 提交。
- **项目 / 分区主体**：`disposalRecords` 段落到 `biz_disposal_record` 台账（无审批、不改生命周期）。
- `sourceInfo` 为 `null` 或整体缺失时表示不修改；传 `{}` 表示清空该行。

**动作映射**（避免把「能删资产」和「能删一条记录」绑在一起）：

| 操作 | 动作 | 理由 |
|------|------|------|
| 读 record-sheet | `view` | 常规 |
| 写 record-sheet（含新增 / 修改 / 删除子记录与附件） | `update` | 全部子记录操作收敛到一个 `update`：删除的是一条后续记录，**不改资产本体**；占用 `asset.ledger:delete` 会让「能删资产」成为「能删记录」的前置，权限过宽 |
| 处置流转 | `operation.disposal:create/update/approve` | 与资产业务权限解耦，见 §5.3 |

- 分区记录路由**嵌套在既有分区资源下**（`/projects/{pid}/zones/{zoneId}/...`），归属可由路径直接解析，且与已上线的分区 CRUD 保持一致（现有分区端点位于 `AssetController` 第 111-150 行）。
- 分区端点复用 `asset.project:*` 权限码，**不新增菜单**（分区详情是钻取页，不在 `PATH_TO_CODE` 镜像里，按 `ProjectFormPage` 的既有做法显式取码）。
- 现在 `DisposalController` 与 `CertificateController` 多数接口**没有** `@RequiresPerm`；本期给处置流转补上注解，是「表单内可推进流程」的前置条件。

### 5.3 权限与数据范围

- 全部写接口加 `@Audited`，模块名 `record` / `disposal`。
- **对象级数据范围**（对齐权限设计 §6.1 第 1/3 条）：
  1. 每个端点先解析 `ownerType + ownerId` → 归属公司，再校验调用者是否有权访问该公司。
  2. `OwnershipResolver` 新增 `ofZone(Long zoneId)`：`zone → project → company_id`；推导不到时返回 `null`，对受限账号按拒绝处理。
  3. 子实体（附件、遗留问题）必须先回溯到宿主主体再断言，**不得**用请求体里的公司做校验。
  4. **宿主必须未软删**：三种主体统一按「未软删才算存在」判定（资产 / 项目 / 分区各自显式过滤 `deleted_at IS NULL`）。已删除的主体的 record-sheet 视为不存在。
  5. **越权一律收敛为 404，不返回 403**。受限账号访问范围外宿主时，响应与「宿主不存在」**完全一致**（同状态码、同文案），避免用 `403` / `404` 之差把宿主 id 的存在性当探针探测。代价是这里不再有「越权」语义上的区分，排障要看服务端日志与审计。
     - 注：这与「无权限（菜单 / 动作）→ 403」是两回事。`@RequiresPerm` 拦下的是功能权限，仍返回 403；本条规定的是**对象级数据范围**拦截。
- 处置的**职责分离**：`approve` 用 `operation.disposal:approve`，与 `asset.ledger:update` 解耦。前端按 `can('operation.disposal','approve')` 显隐审批按钮，避免「能编辑资产就能自提自批」。
- 新接口使用的是既有菜单码 + 既有动作词表（`view/create/update/delete/approve`），**无需新增菜单**；但需要按权限设计 §7 的「动作级回填」把对应动作授予相关角色，否则注解一生效全部 403。

### 5.4 事务与并发

- **保存语义**：record-sheet 的 PUT = **一个事务**，包含三模块子记录与各自的附件。子表用**按 id 增量 diff**（保留 id 更新、无 id 新增、未提交的软删），沿用 `replaceZones` 范式，不先删后插。
- **与主体表单的边界**：主体字段（资产 / 项目本体）仍由各自既有端点保存，record-sheet 只管后续记录。因此资产表单第 3 步的「保存」会触发**两个请求**（主体 PUT + record-sheet PUT）；顺序为先主体后记录（记录依赖主体已存在）。新增态第 3 步禁用（§6.1），不存在先记后主的情形。
- **并发**：主体沿用 `version` 乐观锁（`Asset` / 项目现状）。子表**不引入独立版本号**，同一次 record-sheet 写入以最后提交为准。
- **遗留问题** 挂在 `receive_id` 下，随所属接收记录一起 diff；请求体里出现在某条接收记录下的 issue，其 `receive_id` 必须由服务端按该接收记录 id 赋值，**不接受客户端传入**，从结构上杜绝跨主体拼接。

---

## 6. 前端设计

### 6.1 资产表单改分步（`AssetFormPage.tsx`）

- 由单页卡片改为 `Steps`，与 `ProjectFormPage` 对齐：
  1. 归属与基本信息（归属信息 + 基本信息）
  2. 资产属性与管理信息（资产属性 + 管理信息 + 计量与图片）
  3. **后续记录**（处置记录 / 接收信息 / 来源明细）
- 新增态：第 3 步**可进入但禁用录入**，显示「保存资产后可录入后续记录」——资产还没有 id，处置与接收都无主体。
- 提交按钮移到第 3 步底部；沿用现有 `version` 回传逻辑。
- 「保存」的请求编排：先 `PUT /assets/{id}`（主体，带 `version`），成功后 `PUT /assets/{id}/record-sheet`（后续记录）。两步都成功才提示保存成功；主体成功而记录失败时明确提示「资产已保存，后续记录保存失败，请重试」，避免用户误以为整体回滚。
- 步骤间切换保持「隐藏不卸载」，避免表单值丢失（与项目表单一致）。

### 6.2 项目表单加第 3 步（`ProjectFormPage.tsx`）

- `Steps` 增加 `{ title: '后续记录' }`，内容为项目级三模块。
- 第二步的分区表格新增「详情」操作，跳转分区详情页。
- 分区表格其余字段（名称/编码/排序/备注/资产面积）保持不变。
- 提交同样为两步编排：`PUT /projects/{id}`（含 `zones`）→ `PUT /projects/{id}/record-sheet`。

### 6.3 分区详情页（新增 `ZoneDetailPage.tsx`）

- 路由：`/projects/:projectId/zones/:zoneId`（钻取页，不挂侧边栏，**不进** `STANDALONE_ROUTES` / `PATH_TO_CODE`）。
- 上半部分：分区基本信息（名称、编码、排序、备注；资产面积/资产宗数只读），保存走已上线的 `PUT /projects/{pid}/zones/{zoneId}`。
- 下半部分：三个模块（处置记录 / 接收信息 / 来源明细），读写走 `GET/PUT /projects/{pid}/zones/{zoneId}/record-sheet`。
- 保存按钮只提交 record-sheet（分区本体有独立保存），避免把两件事塞进一次交互。

### 6.4 附件组件（新增 `AttachmentField.tsx`）

- 受控、多文件、支持上传/预览/下载/删除/排序；值形态 `{ fileId, url, name }[]`。
- 复用 `lib/upload.ts` 的 `uploadFile`；类型与大小上限沿用现有约定（jpg/png/pdf，单文件 ≤ 5MB，单字段 ≤ N 个，N 待定见 §12）。
- 与 `ImageUploadField` 并存：图片单图仍用前者，多附件用后者。

### 6.5 相对人组件（新增 `ActorField.tsx`）

- 受控，值形态 `{ userId?: number; name: string }`。
- 默认远程搜索 `sys_user`；无匹配时提供「使用外部人员：<输入值>」。

### 6.6 三模块复用组件（新增 `RecordSheetSections.tsx`）

- 一个组件吃一个 `ownerType`（`asset` / `project` / `zone`）+ 读写路径，内部渲染三个模块，供资产表单第 3 步、项目表单第 3 步、分区详情页三处复用。
- 处置模块按 `ownerType` 切换数据源：`asset` → 只读列表 + 状态与操作按钮（走 `disposal_order` 流程）；`project` / `zone` → 可编辑台账（走 record-sheet 的 `disposalRecords` 段）。
- 该组件不直接画 UI，而是组合 `AttachmentField` / `ActorField` 与 antd 表单，保持「一处修改三处生效」。

---

## 7. 关键流程

### 7.1 资产处置（复用 `disposal_order`，表单内闭环）

```
资产编辑页第3步
  → 新建处置单（draft，落 disposal_order + biz_attachment[owner_type=disposal_order]）
  → submit（待审批，需 operation.disposal:create）
  → approve（需 operation.disposal:approve；无此权限不显示按钮）
  → execute（需 operation.disposal:update）
  → complete（需 operation.disposal:update）→ asset.lifecycle_status = exited
```

- 第 3 步的处置面板 = 列表（状态、类型、日期、金额、附件）+ 按状态和权限显隐的操作按钮。
- 项目/分区处置 = `biz_disposal_record` 台账，只有登记与编辑，无状态机。

### 7.2 新建资产的顺序约束

新增态下第 3 步禁用（见 6.1）。任何子记录接口在 `owner_id` 不存在时返回 400，不得静默写入悬空记录。

### 7.3 分区删除的级联（必须改造 `replaceZones`）

现状：分区有**三条删除路径**，删除语义不一致，且删除都是物理删除。分区一旦挂了记录，三条路径都会绕过保护。

| 路径 | 现状 | 问题 |
|------|------|------|
| `DELETE /projects/{pid}/zones/{zoneId}` → `AssetService.deleteProjectZone` | 分区下有资产时**拒绝**（400「该分区下有 N 项资产，无法删除」），否则 `deleteById` | 方向正确，但**不感知后续记录** |
| `PUT /projects/{pid}` → `AssetService.replaceZones` | 未提交的分区 `deleteBatchIds`，并把区下资产 `zone_id` **静默置空** | 绕过上面的守卫，静默丢失资产归属，也会丢掉/悬空后续记录 |
| `DELETE /projects/{id}` → `AssetService.deleteProject` | 只拦「项目下有资产」，随即 `projectZoneMapper.delete` 硬删全部分区 + 硬删项目 | 绕过分区守卫，且**不感知后续记录** → 硬删出孤儿记录 |

三条路径的删除都是**物理删除**：全局 `logic-delete-field: deleted` 与表范式 `deleted_at` 不一致、实体未映射，逻辑删除实际不生效（见 §4.1）。

改造要求：

1. 抽一个共享的「分区可删性」校验，**三处**都调用：存在有效的 `biz_receive_record` / `biz_receive_issue` / `biz_source_info` / `biz_disposal_record` 记录（**只看记录，不看附件** —— 附件总是挂在某条记录或主体的某个字段上，没有脱离记录的孤立附件）→ **拒绝删除**，返回明确错误（如「分区 X 已有后续记录，请先处理后再删除」）。项目删除路径（`deleteProject`）还需先查**项目自身**挂的后续记录（`RecordOwnerType.PROJECT`），因为记录可以直接挂在项目上、而不是只挂在分区上。
2. `replaceZones` 不得再静默置空：未提交的分区**只要满足「有资产」或「有后续记录」任一条件就整单拒绝**并指出是哪个分区、什么原因，由使用者先显式处理。两个条件与 `deleteProjectZone` 的守卫**完全对齐**（后者今日只查资产），从而彻底消除「同一个分区在一条路径上删不掉、在另一条路径上被静默抹掉」的不一致。
3. 分区**自身**的两条删除路径（`deleteProjectZone` / `replaceZones`）统一改为**软删**（写 `deleted_at`），与 §4.1 口径一致；分区业务代码里不再保留 `deleteBatchIds` 硬删调用。**显式例外**：`DELETE /projects/{id}` → `deleteProject` 保留物理删除（分区 + 项目都硬删）—— 守卫通过即证明整棵子树既无资产也无记录，是空壳，硬删不可能遗留孤儿行；而把项目改成软删要牵动项目列表 / `getProject` / 地图 / 看板 / `OwnershipResolver` 五处读路径，属于「项目软删」独立专项，本期不并入。
4. 分区存在性查询（`requireProjectZone` / `selectById`）显式带 `deleted_at IS NULL`，否则软删后仍会被判为存在。
5. 前端在分区表格与项目提交失败时给出可读提示（哪个分区、什么原因）。

### 7.4 记录与附件删除

- 记录删除 = 软删该行 + 软删其 `biz_attachment`（按 `owner_type + owner_id`）。
- 遗留问题删除同样软删，并在其附件上同步。
- 硬删只允许在「无任何附件、且在同一事务内」时由后台任务执行，本期不做。

---

## 8. 迁移与实施顺序

1. **迁移 `V46`**：5 张新表 + `disposal_order` 扩列 + 3 组字典种子（全部带存在性守卫，保证可重复执行）。
2. **后端**：
   1. `modules/record` 实体 / Mapper；
   2. `RecordOwner` / `OwnerResolver`（多态归属解析 + 权限码映射）；
   3. `RecordSheetService`（聚合读写 + 全量 diff）与 `RecordSheetController`（6 个端点）；
   4. `RecordPresenceChecker`；`OwnershipResolver.ofZone`；抽出共享的「分区可删性」校验，**三条**删除路径接入（就地删除 / 项目整体保存 / 项目删除）；
   5. `replaceZones` 去静默置空 + 子记录校验；分区自身的两条删除路径转软删（`deleteProject` 保留硬删，理由见 §7.3 第 3 条的例外说明）；
   6. `GET /assets/{id}/disposals`；`DisposalController` 补 `@RequiresPerm` 与 `@Audited`；`POST /disposals` 接收 `attachments`。
3. **权限**：新接口使用既有菜单码 + 既有动作词表；把 `asset.ledger:update`、`asset.project:update`、`operation.disposal:create/update/approve` 授予相关角色与测试夹具（**必须与注解上线同批**，否则存量角色全部 403）。`PermissionRegistry` 启动即校验菜单码存在性，`operation.disposal` / `asset.ledger` / `asset.project` 均已在 V45 种子中，无需新增菜单。
4. **前端**：`AttachmentField` / `ActorField` → `RecordSheetSections` → `AssetFormPage` 分步 → `ProjectFormPage` 第 3 步 → `ZoneDetailPage` + 路由 + 分区「详情」入口。
5. **文档**：把本设计口径回写 `docs/需求规格说明书.md`（补 FR 编号与字段表），更新 `docs/database/ams.dbml`。

---

## 9. 验收标准

1. 资产新增态第 3 步不可录入；保存后重新进入可录入三模块，附件支持多传、预览、删除。
2. 资产、项目、分区三处录入的三模块数据互相隔离，且都能被同一套组件渲染。
3. 资产处置从资产编辑页可完成 draft→…→complete 全链路，完成后 `lifecycle_status = exited`；无 `operation.disposal:approve` 的账号看不到审批按钮，直接调接口返回 403。
4. 项目/分区处置只落 `biz_disposal_record`，不产生 `disposal_order`，不改 `lifecycle_status`；资产的 record-sheet 提交 `disposalRecords` 段**不产生任何行**（该段对 asset 被忽略）。
5. 来源明细为 1:1：重复保存不产生第二行（唯一索引生效）。
6. 遗留问题随接收信息保存/删除同步；`receive_id` 由服务端按所属接收记录赋值，客户端传入被忽略。
7. 三条删除路径一致：有**资产**或**后续记录**的分区，无论走 `DELETE /zones/{id}`、项目 `PUT` 的移除，还是走 `DELETE /projects/{id}`，都被拒绝并给出「哪个分区、什么原因」；`DELETE /projects/{id}` 在**项目自身**有资产或后续记录时同样整单拒绝；不再出现项目 PUT 静默置空资产 `zone_id` 的情形；无资产无记录的分区删除为软删，`deleted_at` 落值且分区列表与资产表单的分区下拉中都不再出现（`DELETE /projects/{id}` 保留硬删的例外见 §7.3 第 3 条）。
8. 越权访问不泄露存在性：用 A 公司账号访问 B 公司的资产/项目/分区 record-sheet（含仅凭 id 直取的场景），响应与「该宿主不存在」**完全一致**（同 404 状态码、同文案）；与「无功能权限 → 403」区分开。
9. 所有新写接口在 `operation_log` / 审计中留有 `@Audited` 记录。
10. record-sheet 的 PUT 是原子的：请求体中间某条记录非法（如 issue 描述超长）时，**整单不落库**，不出现半成品。

---

## 10. 本期不做（Out of Scope）

- 遗留问题的整改闭环（状态、整改责任人、期限、整改结果）。
- 项目/分区处置的审批流。
- 附件孤儿文件清理任务。
- 资产/项目**详情页**的展示改造（本期只做录入；详情页 Tab 见 §12）。
- 字典维护页改造（复用现有「系统字典」页维护三组新字典）。
- 历史数据回填（新字段全部可空，存量数据不受影响）。

---

## 11. 风险与取舍

| 风险 | 说明 | 缓解 |
|------|------|------|
| 处置模型不对称 | 资产的处置记录来自 `disposal_order`（带状态机），项目/分区来自 `biz_disposal_record`（台账）。同一个前端组件要吃两种数据源 | `RecordSheetSections` 内部按 `ownerType` 切换数据源与编辑能力，对外暴露统一的 `{type, user, amount, date, remark, attachments, status?, actions[]}` 形状 |
| 表单内审批 = 自提自批 | 「可走完整流程」会让能编辑资产的人同时看到审批按钮 | 审批用独立权限码 `operation.disposal:approve`，按钮按权限显隐 + 后端强校验 |
| 无外键的一致性风险 | `owner_type/owner_id` 多态归属无法用数据库约束 | 服务层统一 `OwnerResolver` 校验 + 复合索引；所有写入必须过 `RecordSheetService`，禁止直接写 Mapper |
| 相对人快照与 `sys_user` 脱节 | 存姓名快照后，员工改名历史记录不跟着变 | 这是**有意为之**（历史凭证应保留当时姓名）；查询时若有 `xxx_id` 可同时回显当前名称并标注差异 |
| 物理删除缺陷被放大 | 全局 `logic-delete-field` 与表范式不一致，新表若不显式处理会重蹈覆辙 | 新实体统一显式 `isNull("deleted_at")`，并在实体 Javadoc 写明；`V46` 后单独提一个修复全局口径的条目 |
| 权限回填遗漏 | 新增注解 + 未回填动作 → 存量角色全 403 | 按权限设计 §7 与注解同批回填，并纳入上线清单 |
| **既有的**文件越权读取缺口（不在本期范围） | `FileController` 的 `GET /files/{id}` 与 `GET /files/{id}/download` **既无权限注解、也无归属校验**，而 `file_metadata.id` 是 `BIGSERIAL`、可枚举；`file_metadata` 也没有公司列，无法按公司过滤。任意登录用户可凭 id 直接读取/下载他人的文件 | 本期只堵新增的**关联**路径（§4.3 附件归属口径：新 `fileId` 必须存在且上传者是本人或 `created_by` 为空）。读端点的修复**不在本期范围**，需另立专项（可枚举 id + 无公司列的复合问题，涉及 `file_metadata` 加列与读端点鉴权）。 |

---

## 12. 待确认与假设

1. **详情页是否展示**：本期只做表单录入；`AssetDossierPage` / `ProjectDetailPage` 是否要加「接收信息 / 来源明细 / 处置记录」Tab，待确认。
2. **交接日期是否必填**：本设计按「**前端必填、DB 可空**」处理（`handover_date DATE` 不加 `NOT NULL`，历史与迁移数据不受影响），最终以需求方确认为准。
3. **单字段附件数量上限 N**：本文未定，默认沿用「单图 ≤5MB」的大小限制，数量上限待确认。
4. **字典初值**：`disposal_type` / `handover_type` / `issue_type` 的初始项是建议值，需业务确认后再落 `V46`。
5. **`biz_` 前缀**：若团队希望与现有命名更一致（如 `asset_receive_record`），可在实现前改名，不影响结构。
6. **分区详情页路由**：`/projects/:projectId/zones/:zoneId` 为建议路径，需与前端路由表确认。
