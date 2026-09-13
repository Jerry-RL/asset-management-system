# 权属流转 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增「权属流转」模块：一张单可选择多个资产，按权属类型改写资产的产权公司 / 经营公司，草稿 + 生效两步，生效后回写资产档案时间线。

**Architecture:** 后端新建 `com.ams.modules.ownership` 模块（对齐 `disposal` / `occupation` 的「一个业务单 = 一个模块」分层），主单 `ownership_transfer` + 明细 `ownership_transfer_asset`；内 / 外方向用现成的 `CompanyTreeService` 公司树同根判定（不新增表或列）；附件复用 `biz_attachment` 多态宿主。前端走独立列表页 + 独立表单页（`ResourcePage` 的字段类型表达不了资产远程多选与附件）。

**Tech Stack:** Java 21 / Spring Boot 3 / MyBatis-Plus / PostgreSQL 15 / Flyway；React 18 + TypeScript + antd + Vite + react-router。

**Spec:** `docs/superpowers/specs/2026-09-13-ownership-transfer-design.md`

---

## Global Constraints

以下约束是**每个 task 的隐含要求**，逐条来自 spec 或仓内既有守卫，违反会硬失败。

- **迁移只增不改**：`V54` 是本计划唯一的迁移文件。已发布的迁移文件（`V1`–`V53`）**一个字都不许改** —— 改了会让 `MigrationChainPostgresTest` 的 `flyway.validate()` 因 checksum 不一致变红。
- **迁移编号是 `V54`**：`V53__rename_deed_menu.sql`（菜单改名）已存在于工作区，必须**先于本计划提交**。
- **软删口径**：`deleted_at TIMESTAMPTZ` + 显式 `isNull` 过滤。**不要**用 `@TableLogic`（`AssetUnit` 的注释已说明：全局逻辑删除字段口径是 `deleted:0/1`，与 `deleted_at` 范式不一致）。
- **每个写接口必须带 `@Audited(module, action)`**：`scripts/check-audit-coverage.mjs` 对每个 `@Post/Put/Delete/PatchMapping` 硬失败。
- **前端任何 `perm` 声明必须是后端 `@RequiresPerm` 里存在的码**：`scripts/check-perm-invariants.mjs` 第 2 条硬失败。
- **`STANDALONE_ROUTES` 的每个 path 必须有对应 `<Route>`，且在 `PATH_TO_CODE` 镜像里**：`check-perm-invariants.mjs` 第 3 / 4 条硬失败。
- **权限码固定为 4 个**：`deed.ownershipTransfer:view` / `:create` / `:update` / `:delete`。**不要**用 `PermAction` 之外的动作（`lib/perm.tsx` 的 `PermAction` 是封闭联合类型：`view|create|update|delete|export|import|approve|audit|assign`）。
- **金额单位万元**，列类型 `NUMERIC(18,2)`；传入超过 2 位小数一律 400（**不要**依赖 PG 的静默四舍五入）。
- **域名与文案一律中文注释**，新增类/方法必须写「为什么」注释（仓内风格，见 `ProjectZoneFloor`、`LeaseControlStatus` 的注释）。
- **`pnpm lint` 基线恰好 4 warning / 0 error**，不得新增。
- **前端 API 客户端的方法是 `api.del`**（不是 `api.delete`）；`api.get/post/put` 见 `frontend/admin-web/src/lib/api.ts:191`。
- **分页响应形状是 `{ list, total, page, pageSize }`**（`PageResult.of`），前端用 `lib/org.ts` 的 `normalizeList` 兜底。
- **`asset.lifecycle_status` 的取值只有 `in_book` / `exited`**；**`lease_control_status` 只允许 `DISPOSING → EXITED`**，本模块**不动租控**（spec §5.4）。

---

## File Structure

### 后端（新增 12 个文件，改动 8 个）

| 文件 | 职责 |
|------|------|
| `backend/src/main/resources/db/migration/V54__ownership_transfer.sql` | **新增**。2 张表、`asset` 加 1 列、3 个字典、菜单 + view 回填、1 条通知模板 |
| `backend/src/main/java/com/ams/modules/ownership/entity/OwnershipTransfer.java` | **新增**。主单实体 |
| `backend/src/main/java/com/ams/modules/ownership/entity/OwnershipTransferAsset.java` | **新增**。明细实体 |
| `backend/src/main/java/com/ams/modules/ownership/mapper/OwnershipTransferMapper.java` | **新增**。主单 Mapper |
| `backend/src/main/java/com/ams/modules/ownership/mapper/OwnershipTransferAssetMapper.java` | **新增**。明细 Mapper |
| `backend/src/main/java/com/ams/modules/ownership/dto/OwnershipTransferInput.java` | **新增**。表单入参（含 `assetIds` / `attachments`） |
| `backend/src/main/java/com/ams/modules/ownership/dto/OwnershipTransferView.java` | **新增**。列表 / 详情出参 |
| `backend/src/main/java/com/ams/modules/ownership/dto/OwnershipTransferAssetView.java` | **新增**。详情里的资产行 |
| `backend/src/main/java/com/ams/modules/ownership/dto/TransferAssetOption.java` | **新增**。资产下拉项（4 段 label 的原料） |
| `backend/src/main/java/com/ams/modules/ownership/service/TransferDirectionResolver.java` | **新增**。内 / 外方向判定与企业树一致性校验（**唯一的判定点**） |
| `backend/src/main/java/com/ams/modules/ownership/service/OwnershipTransferService.java` | **新增**。草稿 CRUD + 校验 + 生效 |
| `backend/src/main/java/com/ams/modules/ownership/controller/OwnershipTransferController.java` | **新增**。7 个端点 |
| `backend/src/main/java/com/ams/platform/event/OwnershipTransferredEvent.java` | **新增**。领域事件 |
| `backend/src/main/java/com/ams/modules/asset/service/AssetHandoverBuilder.java` | **新增**。交接清单快照构造器（从 `TransferService` 抽出，两处复用） |
| `backend/src/main/java/com/ams/modules/record/AttachmentOwner.java` | **改**。加 `OWNERSHIP_TRANSFER` |
| `backend/src/main/java/com/ams/modules/record/service/RecordSheetService.java` | **改**。`syncAttachments` / `toAttachmentRefs` 由包私有改 `public`（泛化给非处置宿主用） |
| `backend/src/main/java/com/ams/modules/asset/service/TransferService.java` | **改**。改用 `AssetHandoverBuilder` |
| `backend/src/main/java/com/ams/platform/event/outbox/EventTypeRegistry.java` | **改**。登记新事件 |
| `backend/src/main/java/com/ams/modules/notification/listener/NotificationEventListener.java` | **改**。订阅新事件 |
| `backend/src/main/java/com/ams/modules/asset/service/AssetDossierService.java` | **改**。档案加 `ownershipTransfers` 段 + 时间线类型 |

### 后端测试（新增 5 个，改动 4 个）

| 文件 | 职责 |
|------|------|
| `backend/src/test/java/com/ams/modules/ownership/V54OwnershipTransferMigrationContractTest.java` | **新增**。迁移文本契约 |
| `backend/src/test/java/com/ams/modules/ownership/TransferDirectionResolverTest.java` | **新增**。方向判定单测 |
| `backend/src/test/java/com/ams/modules/ownership/service/OwnershipTransferServiceTest.java` | **新增**。草稿校验 + 生效落库 |
| `backend/src/test/java/com/ams/modules/ownership/OwnershipTransferPermissionTest.java` | **新增**。权限闭环 |
| `backend/src/test/java/com/ams/MigrationChainPostgresTest.java` | **改**。加 `V54` 的真库落库断言 |
| `backend/src/test/java/com/ams/modules/record/OwnerEnumTest.java` | **改**。补 `OWNERSHIP_TRANSFER` 断言 |
| `backend/src/test/java/com/ams/modules/record/service/RecordSheetServiceTest.java` | **改**。补「按非处置宿主读写附件」断言 |
| `backend/src/test/java/com/ams/platform/event/outbox/EventTypeRegistryTest.java` | **改**。8 类 → 9 类 |
| `backend/src/test/java/com/ams/support/RbacFixtures.java` | **改**。菜单行 + `asset_mgr` 授权 |

### 前端（新增 4 个，改动 8 个）

| 文件 | 职责 |
|------|------|
| `frontend/admin-web/src/lib/ownershipTransfer.ts` | **新增**。类型 + API 封装 + 标签 + label 拼接 |
| `frontend/admin-web/src/pages/OwnershipTransfersPage.tsx` | **新增**。列表页 |
| `frontend/admin-web/src/pages/OwnershipTransferFormPage.tsx` | **新增**。表单页 |
| `frontend/admin-web/src/pages/App.tsx` | **改**。3 条 `<Route>` |
| `frontend/admin-web/src/lib/routeRegistry.ts` | **改**。`STANDALONE_ROUTES` |
| `frontend/admin-web/src/lib/pathToCode.ts` | **改**。镜像个 1 条 |
| `frontend/admin-web/src/pages/modules.tsx` | **改**。静态 `MENU` 兜底 |
| `frontend/admin-web/src/lib/menuIcons.tsx` | **改**。`PATH_ICONS` |
| `frontend/admin-web/src/lib/labels.ts` | **改**。4 张标签表 |
| `frontend/admin-web/src/pages/AssetDossierPage.tsx` | **改**。时间线加 `ownership_transfer` 分支 |

---

## Task 1: V54 迁移 + 迁移契约测试

**Files:**

- Create: `backend/src/main/resources/db/migration/V54__ownership_transfer.sql`
- Create: `backend/src/test/java/com/ams/modules/ownership/V54OwnershipTransferMigrationContractTest.java`
- Modify: `backend/src/test/java/com/ams/MigrationChainPostgresTest.java`（`appliesAuditMigrations` 的版本断言 + 文件末尾追加一个 `@Test`）

**Interfaces:**

- Consumes: 无（第一个 task）
- Produces:
  - 表 `ownership_transfer`（列名见下；实体在 Task 6 按此建）
  - 表 `ownership_transfer_asset`
  - 列 `asset.ownership_status VARCHAR(20) NOT NULL DEFAULT 'in_group'`
  - 索引 `idx_ownership_transfer_status`、`idx_ownership_transfer_from`、`uk_ownership_transfer_asset`、`idx_ownership_transfer_asset_asset`
  - 字典 `transfer_direction` / `transfer_scope` / `transfer_mode`
  - 菜单 `deed.ownershipTransfer`，path `/ownership-transfers`
  - 通知模板 `ownership_transferred`

---

- [ ] **Step 1: 写迁移文件**

Create `backend/src/main/resources/db/migration/V54__ownership_transfer.sql`:

```sql
-- ============================================================================
-- V54 权属流转（ownership_transfer）：多资产改产权公司 / 经营公司
--
-- 设计见 docs/superpowers/specs/2026-09-13-ownership-transfer-design.md。
--
-- 与既有「资产调拨」（asset_transfer，单资产、改 operating_company_id、不接审批引擎）
-- 并存不合并：本表是多资产 + 按权属类型选改哪个公司字段 + 附件。
--
-- 三条口径：
--   1. 无单号列，界面用 `#id`（与 disposal_order / asset_transfer 一致）；
--   2. 无外键约束（同上）—— 归属与状态由应用层断言，避免 FK 让迁移顺序变得脆弱；
--   3. 软删用 `deleted_at TIMESTAMPTZ` + 显式过滤，不用 @TableLogic
--      （全局逻辑删除字段口径是 deleted:0/1，与 deleted_at 范式不一致）。
-- 可重复执行：CREATE ... IF NOT EXISTS + 字典/菜单 ON CONFLICT DO NOTHING。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 主单
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ownership_transfer (
    id                BIGSERIAL PRIMARY KEY,
    -- 流转方向：internal 内部流转 / external 外部流转，取字典 transfer_direction。
    -- 由公司树（company.parent_id）判定「目标公司与原公司是否同根」，见 TransferDirectionResolver。
    direction         VARCHAR(20)  NOT NULL,
    -- 权属类型：both 经营权且产权 / property 产权 / operating 经营权，取字典 transfer_scope。
    -- 决定生效时改写 asset 的哪个字段：property→property_company_id、
    -- operating→operating_company_id、both→两个都改。
    transfer_scope    VARCHAR(20)  NOT NULL,
    from_company_id   BIGINT       NOT NULL,
    to_company_id     BIGINT       NOT NULL,
    -- 流转类型：allocate 直接划拨 / purchase 购买流转 / auction 拍卖流转，取字典 transfer_mode。
    transfer_mode     VARCHAR(30)  NOT NULL,
    -- 变更申请人：内员时 applicant_user_id 非空且 applicant_name 是 sys_user.name 快照；
    -- 外部人员时 applicant_user_id 为 NULL、applicant_name 手填。
    applicant_user_id BIGINT,
    applicant_name    VARCHAR(100) NOT NULL,
    -- 审批截止时间：本期无审批环节，纯记录字段（不校验、不提醒）。
    approval_deadline TIMESTAMPTZ,
    -- 金额(万元)，2 位小数；服务端拒绝超过 2 位小数的入参，不依赖 PG 静默四舍五入。
    amount_wan        NUMERIC(18,2),
    reason            VARCHAR(1000),
    -- 生效时生成的交接清单快照（欠费/保证金/预收/在租合同），形态同 asset_transfer.handover_json
    -- 是 TEXT 而不是 JSONB：实体字段是 String，JSONB 会在 insert 时类型不匹配（V4 全仓改过一轮）。
    handover_json     TEXT,
    -- draft / completed（本期无审批，故无 approving）
    status            VARCHAR(20)  NOT NULL DEFAULT 'draft',
    effected_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ,
    created_by        BIGINT,
    updated_by        BIGINT,
    deleted_at        TIMESTAMPTZ
);

-- 列表页默认按状态筛选 + id 倒序
CREATE INDEX IF NOT EXISTS idx_ownership_transfer_status
    ON ownership_transfer (status, id DESC);
-- 按原公司筛选（列表页筛选项之一）
CREATE INDEX IF NOT EXISTS idx_ownership_transfer_from
    ON ownership_transfer (from_company_id);

COMMENT ON COLUMN ownership_transfer.direction IS '流转方向：internal/external，取字典 transfer_direction';
COMMENT ON COLUMN ownership_transfer.transfer_scope IS '权属类型：both/property/operating，取字典 transfer_scope';
COMMENT ON COLUMN ownership_transfer.transfer_mode IS '流转类型：allocate/purchase/auction，取字典 transfer_mode';
COMMENT ON COLUMN ownership_transfer.amount_wan IS '金额(万元)，2 位小数';
COMMENT ON COLUMN ownership_transfer.approval_deadline IS '审批截止时间（本期无审批环节，纯记录）';
COMMENT ON COLUMN ownership_transfer.status IS 'draft/completed';

-- ---------------------------------------------------------------------------
-- 明细：一张单 × 多个资产
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ownership_transfer_asset (
    id                        BIGSERIAL PRIMARY KEY,
    transfer_id               BIGINT NOT NULL,
    asset_id                  BIGINT NOT NULL,
    -- 生效时的原值快照：生效后再流转 / 公司改名，也能追溯「当时从哪家公司转出」。
    -- 不靠 asset 现值反推 —— 那是「读完就变了」的数据。
    from_property_company_id  BIGINT,
    from_operating_company_id BIGINT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ,
    created_by                BIGINT,
    updated_by                BIGINT
);

-- 同一张单不允许重复挂同一个资产（请求体去重之外的服务端兜底）
CREATE UNIQUE INDEX IF NOT EXISTS uk_ownership_transfer_asset
    ON ownership_transfer_asset (transfer_id, asset_id);
-- 反查「某资产被哪些流转单改过」：资产档案的时间线段与排查都要用这条
CREATE INDEX IF NOT EXISTS idx_ownership_transfer_asset_asset
    ON ownership_transfer_asset (asset_id);

-- ---------------------------------------------------------------------------
-- asset 加列：权属状态
--
-- 为什么必须新列：外部流转生效后 lifecycle_status = 'exited'，而处置完成也是 'exited'；
-- 没有这一列就无法在资产上区分「卖掉了」和「转出去了」。
-- 加带 DEFAULT 的 NOT NULL 列在 PG 11+ 是快操作（不重写表）。
-- ---------------------------------------------------------------------------
ALTER TABLE asset ADD COLUMN IF NOT EXISTS ownership_status VARCHAR(20) NOT NULL DEFAULT 'in_group';
COMMENT ON COLUMN asset.ownership_status IS 'in_group 集团内 / transferred_out 已对外转出（外部权属流转生效后置位）';

-- ============================================================================
-- 字典：流转方向 / 权属类型 / 流转类型（挂在「资产管理字典」模块下，沿用 V19/V46/V49 写法）
-- sort 取 16/17/18：已占用 1、2、3、9、10、12、13、14、15
-- ============================================================================
INSERT INTO sys_dict_type (module_id, code, name, sort)
SELECT m.id, v.code, v.name, v.sort
FROM sys_dict_module m
JOIN (VALUES
    ('transfer_direction', '流转方向', 16),
    ('transfer_scope',     '权属类型', 17),
    ('transfer_mode',      '流转类型', 18)
) AS v(code, name, sort) ON TRUE
WHERE m.code = 'asset_management'
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_dict_item (type_id, value, label, sort)
SELECT t.id, v.value, v.label, v.sort
FROM sys_dict_type t
JOIN (VALUES
    ('transfer_direction', 'internal',  '内部流转',     1),
    ('transfer_direction', 'external',  '外部流转',     2),
    ('transfer_scope',     'both',      '经营权且产权', 1),
    ('transfer_scope',     'property',  '产权',         2),
    ('transfer_scope',     'operating', '经营权',       3),
    ('transfer_mode',      'allocate',  '直接划拨',     1),
    ('transfer_mode',      'purchase',  '购买流转',     2),
    ('transfer_mode',      'auction',   '拍卖流转',     3)
) AS v(type_code, value, label, sort) ON v.type_code = t.code
ON CONFLICT (type_id, value) DO NOTHING;

-- ============================================================================
-- 菜单：挂在「资债权证」目录（deed）下，sort 45 落在「评估申请」(40) 之后
--
-- 按 code = 'deed' 定位父目录，不依赖目录名 —— V53 刚把该目录改名为「资债权证记录」，
-- 两份迁移的先后顺序不影响本语句的正确性。
-- icon 留空：与 V45 口径一致（图标只给目录，菜单为空时侧栏回退前端 PATH_ICONS）。
-- ============================================================================
INSERT INTO menu (code, name, menu_type, path, icon, sort, parent_id)
SELECT 'deed.ownershipTransfer', '权属流转', 'menu', '/ownership-transfers', NULL, 45, d.id
FROM menu d WHERE d.code = 'deed'
ON CONFLICT (code) DO NOTHING;

-- view 回填：本菜单在 V45 之后新增，V45 §5.1 的回填只覆盖了当时的菜单行。
-- 不回填则除 super_admin 外所有角色都看不到入口 —— 新功能表现为「没做出来」。
-- 与 V45 §5.1 / V47 同口径：只回填 view、排除 super_admin、写动作一律不回填
-- （V45 §5.3 把动作级回填列为上线前置人工步骤；误回填的宽权限是静默的且会长期留在库里）。
INSERT INTO role_permission (role_id, menu_id, menu_code, action)
SELECT r.id, m.id, m.code, 'view'
FROM role r
CROSS JOIN menu m
WHERE r.code <> 'super_admin'
  AND m.code = 'deed.ownershipTransfer'
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 通知模板：与 V44 的 asset_transferred 同写法（无参数字典表）
-- ============================================================================
INSERT INTO notification_template (code, channel, title_tpl, body_tpl)
SELECT 'ownership_transferred', 'in_app', '资产权属流转已完成',
       '资产 #{{assetId}} 权属已由公司 #{{fromCompanyId}} 流转至公司 #{{toCompanyId}}，方向 {{direction}}，请双方对账'
WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE code = 'ownership_transferred');
```

- [ ] **Step 2: 写迁移契约测试**

Create `backend/src/test/java/com/ams/modules/ownership/V54OwnershipTransferMigrationContractTest.java`:

```java
package com.ams.modules.ownership;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V54 迁移的契约守卫（权属流转）。
 *
 * <p>每条断言对应一类真实事故：
 * <ol>
 *   <li>表名 / 列名与实体 {@code @TableName} 漂移 —— MyBatis-Plus 会去查一张不存在的表；</li>
 *   <li>漏掉「同单同资产唯一」的索引 —— 一张单里同一个资产出现两次，生效时改两遍；</li>
 *   <li>漏掉 {@code asset.ownership_status} —— 外部流转与处置在资产上都表现为「已退出」，
 *       档案里无法区分「卖掉了」和「转出去了」；</li>
 *   <li>{@code handover_json} 误用 JSONB —— 实体字段是 String，insert 时类型不匹配
 *       （V4 已把全仓 JSONB 改回 TEXT，这里不能再踩一次）；</li>
 *   <li>菜单漏了 view 回填 —— 除超管外所有人看不到入口，新功能表现为「没做出来」。</li>
 * </ol>
 *
 * <p>只断言「会真正执行的语句」（去掉 {@code --} 注释）：否则一句解释性注释就能让负向断言误红。
 */
class V54OwnershipTransferMigrationContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V54__ownership_transfer.sql";

    private static final String MAIN_TABLE = "ownership_transfer";
    private static final String ASSET_TABLE = "ownership_transfer_asset";

    private static String SQL;

    @BeforeAll
    static void loadMigration() {
        SQL = readMigration();
    }

    private static String readMigration() {
        try (InputStream in =
                V54OwnershipTransferMigrationContractTest.class.getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("V54 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sqlWithoutComments(String sql) {
        return sql.lines()
                .map(line -> {
                    int idx = line.indexOf("--");
                    return idx >= 0 ? line.substring(0, idx) : line;
                })
                .collect(Collectors.joining("\n"));
    }

    // ------------------------------------------------------------------
    // 建表
    // ------------------------------------------------------------------

    @Test
    @DisplayName("主单表包含全部字段，且 status 默认 draft")
    void createsMainTable() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS " + MAIN_TABLE);
        assertThat(sql)
                .as("字段必须齐全：方向 / 权属类型 / 两个公司 / 流转类型 / 申请人 / 金额 / 交接清单 / 状态")
                .contains("direction", "transfer_scope", "from_company_id", "to_company_id",
                        "transfer_mode", "applicant_user_id", "applicant_name",
                        "approval_deadline", "amount_wan", "reason", "handover_json",
                        "effected_at", "deleted_at",
                        "created_at", "updated_at", "created_by", "updated_by");
        assertThat(sql)
                .as("status 默认 draft：本期状态机是 draft → completed，没有 approving")
                .contains("status            VARCHAR(20)  NOT NULL DEFAULT 'draft'");
    }

    @Test
    @DisplayName("明细表包含 原值快照 两列：生效后公司改名 / 再流转也能追溯从哪家转出")
    void createsAssetTableWithSnapshotColumns() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS " + ASSET_TABLE);
        assertThat(sql)
                .as("两个原值快照列不能省：靠 asset 现值反推是「读完就变了」的数据")
                .contains("transfer_id", "asset_id",
                        "from_property_company_id", "from_operating_company_id");
    }

    @Test
    @DisplayName("明细刻意不落 deleted_at：草稿改明细走全量 diff 真删，明细行无外部引用")
    void assetTableHasNoSoftDelete() {
        String sql = sqlWithoutComments(SQL);

        int assetTableStart = sql.indexOf("CREATE TABLE IF NOT EXISTS " + ASSET_TABLE);
        int assetTableEnd = sql.indexOf(";", assetTableStart);
        assertThat(assetTableStart).as("明细表必须存在").isPositive();
        assertThat(sql.substring(assetTableStart, assetTableEnd))
                .as("附件挂在主单上，明细行无外部引用；留软删只会制造永不清理的孤儿行")
                .doesNotContain("deleted_at");
    }

    // ------------------------------------------------------------------
    // 索引
    // ------------------------------------------------------------------

    @Test
    @DisplayName("四个索引都建出来，且唯一索引约束「同一张单不重复挂同一资产」")
    void createsExpectedIndexes() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("列表页默认按状态筛选 + id 倒序")
                .contains("CREATE INDEX IF NOT EXISTS idx_ownership_transfer_status")
                .contains("(status, id DESC)");
        assertThat(sql)
                .as("列表页按原公司筛选")
                .contains("CREATE INDEX IF NOT EXISTS idx_ownership_transfer_from");
        assertThat(sql)
                .as("缺了它同一张单里同一个资产会出现两次，生效时改两遍")
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_ownership_transfer_asset")
                .contains("(transfer_id, asset_id)");
        assertThat(sql)
                .as("反查「某资产被哪些流转单改过」——资产档案时间线与排查都依赖它")
                .contains("CREATE INDEX IF NOT EXISTS idx_ownership_transfer_asset_asset")
                .contains("(asset_id)");
    }

    // ------------------------------------------------------------------
    // asset 加列
    // ------------------------------------------------------------------

    @Test
    @DisplayName("asset.ownership_status 是带默认值的 NOT NULL：存量行一律「集团内」")
    void addsOwnershipStatusColumn() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("没有这一列就无法在资产上区分「已对外转出」与「已处置」")
                .contains("ALTER TABLE asset ADD COLUMN IF NOT EXISTS ownership_status")
                .contains("NOT NULL DEFAULT 'in_group'");
    }

    // ------------------------------------------------------------------
    // 字典 / 菜单 / 通知模板
    // ------------------------------------------------------------------

    @Test
    @DisplayName("三个字典类型 + 九条字典项齐全")
    void seedsDictionaries() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql).contains(
                "('transfer_direction', '流转方向', 16)",
                "('transfer_scope',     '权属类型', 17)",
                "('transfer_mode',      '流转类型', 18)");

        assertThat(List.of(
                "('transfer_direction', 'internal',  '内部流转',     1)",
                "('transfer_direction', 'external',  '外部流转',     2)",
                "('transfer_scope',     'both',      '经营权且产权', 1)",
                "('transfer_scope',     'property',  '产权',         2)",
                "('transfer_scope',     'operating', '经营权',       3)",
                "('transfer_mode',      'allocate',  '直接划拨',     1)",
                "('transfer_mode',      'purchase',  '购买流转',     2)",
                "('transfer_mode',      'auction',   '拍卖流转',     3)"))
                .as("字典项少一条，前端下拉就少一个可选项；值必须与设计 §4.5 逐字一致")
                .allSatisfy(row -> assertThat(sql).contains(row));
    }

    @Test
    @DisplayName("菜单挂在 deed 目录下：path 与前端镜像逐字一致，且只回填 view")
    void seedsMenuAndBackfillsViewOnly() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("path 写错会让点菜单落回首页；前端 PATH_TO_CODE 会是 '/ownership-transfers'")
                .contains("'deed.ownershipTransfer', '权属流转', 'menu', '/ownership-transfers'")
                .contains("FROM menu d WHERE d.code = 'deed'")
                .as("sort 45 落在「评估申请」(40) 之后")
                .contains("45, d.id");

        assertThat(sql)
                .as("不回填 view 则除超管外所有角色看不到入口")
                .contains("INSERT INTO role_permission")
                .contains("AND m.code = 'deed.ownershipTransfer'")
                .as("排除 super_admin：它由 PermissionRegistry 特判全通，回填是脏数据")
                .contains("r.code <> 'super_admin'");
        assertThat(sql)
                .as("写动作一律不回填：V45 §5.3 把动作级回填列为上线前置人工步骤")
                .doesNotContain("'create'")
                .doesNotContain("'update'")
                .doesNotContain("'delete'");
    }

    @Test
    @DisplayName("通知模板 ownership_transferred 幂等插入")
    void seedsNotificationTemplate() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .contains("INSERT INTO notification_template")
                .contains("'ownership_transferred'")
                .contains("WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE code = 'ownership_transferred')");
    }

    @Test
    @DisplayName("两处幂等兜底都在：重复执行迁移不得报错或产生重复行")
    void everyStatementIsIdempotent() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("字典类型/项 ON CONFLICT，菜单 ON CONFLICT，权限回填 ON CONFLICT")
                .contains("ON CONFLICT (code) DO NOTHING")
                .contains("ON CONFLICT (type_id, value) DO NOTHING")
                .contains("ON CONFLICT DO NOTHING");
    }
}
```

- [ ] **Step 3: 补充真库迁移链断言**

Modify `backend/src/test/java/com/ams/MigrationChainPostgresTest.java`。

第一处：`appliesAuditMigrations` 里把版本断言从 `contains("51", "52")` 扩成含 `"54"`：

```java
        assertThat(versions)
                .as("V51 加 success/error 两列与菜单，V52 加按对象查询的索引；"
                        + "V54 加权属流转两表与 asset.ownership_status；"
                        + "迁移文件躺在仓库里而从未被执行过，是本仓长期存在的状态，这里把它钉住")
                .contains("51", "52", "54");
```

第二处：在类末尾（最后一个 `@Test` 之后、类结束大括号之前）追加：

```java
    // ------------------------------------------------------------------
    // 4. V54：权属流转在真库上的落库结果
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V54：ownership_transfer 主表存在，direction/transfer_scope/transfer_mode 为 NOT NULL")
    void createsOwnershipTransferTable() {
        assertThat(columnType("ownership_transfer", "direction"))
                .as("方向是判定依据，不允许 NULL")
                .startsWith("character varying|NO|");
        assertThat(columnType("ownership_transfer", "transfer_scope"))
                .as("权属类型决定改哪个公司字段，不允许 NULL")
                .startsWith("character varying|NO|");
        assertThat(columnType("ownership_transfer", "transfer_mode"))
                .as("流转类型是字典取值，不允许 NULL")
                .startsWith("character varying|NO|");
        assertThat(columnType("ownership_transfer", "handover_json"))
                .as("必须是 text：实体字段是 String，jsonb 会在 insert 时类型不匹配")
                .startsWith("text|");
        assertThat(columnType("ownership_transfer", "applicant_user_id"))
                .as("外部人员时为空 —— 这是「内员 / 外部」两分支的区分位，必须可空")
                .startsWith("bigint|YES|");
    }

    @Test
    @DisplayName("V54：asset.ownership_status 默认 in_group，存量行全部是集团内")
    void backfillsOwnershipStatusForExistingAssets() {
        execute("INSERT INTO project (company_id, name, status) VALUES (2, 'V54 契约项目', 1)");
        execute("INSERT INTO asset (asset_company_id, asset_no, name, asset_type, status)"
                + " VALUES (2, 'V54-CONTRACT-1', 'V54 契约资产', 'property', 1)");

        assertThat(queryString(
                "SELECT ownership_status FROM asset WHERE asset_no = 'V54-CONTRACT-1'"))
                .as("存量行必须落成 in_group —— 若为 NULL，前端与查询都会判成「未知权属」")
                .isEqualTo("in_group");
    }

    @Test
    @DisplayName("V54：同一张单重复挂同一资产会被唯一索引拒绝")
    void rejectsDuplicateAssetInSameTransfer() {
        execute("INSERT INTO ownership_transfer"
                + " (direction, transfer_scope, from_company_id, to_company_id, transfer_mode,"
                + "  applicant_name, status)"
                + " VALUES ('internal', 'property', 2, 3, 'allocate', '契约申请人', 'draft')");
        long transferId = queryLong(
                "SELECT id FROM ownership_transfer WHERE applicant_name = '契约申请人' ORDER BY id DESC LIMIT 1");

        execute("INSERT INTO ownership_transfer_asset (transfer_id, asset_id) VALUES ("
                + transferId + ", 9001)");

        try {
            execute("INSERT INTO ownership_transfer_asset (transfer_id, asset_id) VALUES ("
                    + transferId + ", 9001)");
            fail("唯一索引 uk_ownership_transfer_asset 未生效：同一张单可以把同一个资产挂两次");
        } catch (AssertionError expected) {
            assertThat(expected.getCause())
                    .as("必须是唯一约束冲突，而不是别的 SQL 错误")
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    @DisplayName("V54：view 只回填给非超管角色，且没有任何写动作被回填")
    void backfillsOwnershipTransferViewOnly() {
        List<String> rows = queryStrings(
                "SELECT r.code || ':' || rp.action"
                        + " FROM role_permission rp JOIN role r ON r.id = rp.role_id"
                        + " WHERE rp.menu_code = 'deed.ownershipTransfer'"
                        + " ORDER BY r.code, rp.action");

        assertThat(rows)
                .as("只允许 view 且不含 super_admin（它由 PermissionRegistry 特判全通）")
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row).endsWith(":view"))
                .allSatisfy(row -> assertThat(row).doesNotStartWith("super_admin:"));
    }
```

- [ ] **Step 4: 跑测试，确认契约测试能守住**

Run:

```bash
cd backend && mvn -q test -Dtest='V54OwnershipTransferMigrationContractTest,MigrationChainPostgresTest' -DfailIfNoTests=false
```

Expected:
- `V54OwnershipTransferMigrationContractTest`：9 个用例全绿。
- `MigrationChainPostgresTest`：本机没有 Docker 时输出 `本机没有可用的 Docker，跳过迁移链集成测试`（`Assumptions.abort` 表现为 skipped，不是 failed）；有 Docker 时全绿。
- 若 `MigrationChainPostgresTest` 有 Docker 但红了，**先看 `flyway.migrate()` 的报错**，那是本 task 的 SQL 有真问题。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V54__ownership_transfer.sql \
        backend/src/test/java/com/ams/modules/ownership/V54OwnershipTransferMigrationContractTest.java \
        backend/src/test/java/com/ams/MigrationChainPostgresTest.java
git commit -m "feat(ownership): V54 迁移（权属流转两表 + asset.ownership_status + 字典/菜单/通知模板）"
```

---

## Task 2: `AttachmentOwner.OWNERSHIP_TRANSFER`

**Files:**

- Modify: `backend/src/main/java/com/ams/modules/record/AttachmentOwner.java`
- Modify: `backend/src/test/java/com/ams/modules/record/OwnerEnumTest.java`

**Interfaces:**

- Consumes: 无
- Produces: `AttachmentOwner.OWNERSHIP_TRANSFER`，`code() == "ownership_transfer"`，`bizType() == "transfer_attach"`（Task 3 与 Task 8 使用）

---

- [ ] **Step 1: 写失败的测试**

Modify `backend/src/test/java/com/ams/modules/record/OwnerEnumTest.java`：在既有的 `EVALUATION_INFO` 两条断言之后追加两行（保持逐条断言的风格）：

```java
        assertThat(AttachmentOwner.OWNERSHIP_TRANSFER.code()).isEqualTo("ownership_transfer");
        assertThat(AttachmentOwner.OWNERSHIP_TRANSFER.bizType()).isEqualTo("transfer_attach");
```

- [ ] **Step 2: 跑测试确认它失败**

Run:

```bash
cd backend && mvn -q test -Dtest=OwnerEnumTest -DfailIfNoTests=false
```

Expected: 编译失败 —— `cannot find symbol: variable OWNERSHIP_TRANSFER`（枚举值还没加）。

- [ ] **Step 3: 加枚举值**

Modify `backend/src/main/java/com/ams/modules/record/AttachmentOwner.java`：在 `EVALUATION_INFO(...)` 之后追加（注意前一个常量结尾的分号要改成逗号）：

```java
    /** 评估信息：附件的读写权限跟宿主走（PDF / Word 等）。 */
    EVALUATION_INFO("evaluation_info", "evaluation_attach"),
    /**
     * 权属流转主单：附件的读写权限跟**单据**走（`deed.ownershipTransfer:view` / `:update`），
     * 不跟资产走 —— 流转单是多资产的，挂到任一资产上都会让「谁能看这份附件」取决于
     * 恰好被选中的是哪个资产。
     */
    OWNERSHIP_TRANSFER("ownership_transfer", "transfer_attach");
```

- [ ] **Step 4: 跑测试确认通过**

Run:

```bash
cd backend && mvn -q test -Dtest='OwnerEnumTest,GlobalExceptionHandlerAppLogTest' -DfailIfNoTests=false
```

Expected: PASS。

- [ ] **Step 5: 跑 record 模块的契约守卫**

Run:

```bash
cd frontend && pnpm check:record
```

Expected: `检查通过`。该脚本（`scripts/check-record-contracts.mjs`）会与后端「record 模块的常量解析下限」比对 —— 它正是靠这类下限断言来发现「枚举被改名 / 被删」的。若它报出新的下限失败，说明它读的是 `AttachmentOwner` 常量集合，按报错把下限 +1（**只加上限，不要删既有条目**）。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/ams/modules/record/AttachmentOwner.java \
        backend/src/test/java/com/ams/modules/record/OwnerEnumTest.java
git commit -m "feat(record): AttachmentOwner 增加 OWNERSHIP_TRANSFER 宿主"
```

---

## Task 3: 泛化 `RecordSheetService` 的附件读写口

**Files:**

- Modify: `backend/src/main/java/com/ams/modules/record/service/RecordSheetService.java`（`syncAttachments` 与 `toAttachmentRefs` 由包私有改 `public`，并补「为什么公开」注释）
- Modify: `backend/src/test/java/com/ams/modules/record/service/RecordSheetServiceTest.java`

**Interfaces:**

- Consumes: `AttachmentOwner.OWNERSHIP_TRANSFER`（Task 2）
- Produces:
  - `public void syncAttachments(AttachmentOwner owner, Long ownerId, List<AttachmentRef> refs)`
  - `public List<AttachmentRef> toAttachmentRefs(AttachmentOwner owner, Long ownerId)`
  - 既有的 `syncOrderAttachments` / `orderAttachments` **签名与行为不变**（仍是 `DISPOSAL_ORDER` 的薄包装）

**为什么必须泛化**：`syncOrderAttachments` / `orderAttachments` 内部硬编码 `AttachmentOwner.DISPOSAL_ORDER`（`RecordSheetService.java:544-551`），是处置单专用。权属流转直接复用会把附件写进处置单的宿主 —— 两个模块的附件互相串台，且不会报任何错。

---

- [ ] **Step 1: 写失败的测试**

Modify `backend/src/test/java/com/ams/modules/record/service/RecordSheetServiceTest.java`：新增一个用例。先确认该测试类已有的 mock 装配方式（读 `setUp`），然后按同一方式加：

```java
    @Test
    @DisplayName("按 OWNERSHIP_TRANSFER 写附件：owner_type 与 biz_type 都取自枚举，不串到处置单宿主")
    void syncAttachmentsForOwnershipTransfer() {
        service.syncAttachments(
                AttachmentOwner.OWNERSHIP_TRANSFER, 77L, List.of(ref(1001L), ref(1002L)));

        ArgumentCaptor<BizAttachment> captor = ArgumentCaptor.forClass(BizAttachment.class);
        verify(bizAttachmentMapper, times(2)).insert(captor.capture());

        assertThat(captor.getAllValues())
                .as("宿主必须逐字是枚举值：写错就会把权属流转的附件挂进处置单，且没有任何报错")
                .allSatisfy(row -> {
                    assertThat(row.getOwnerType()).isEqualTo(AttachmentOwner.OWNERSHIP_TRANSFER.code());
                    assertThat(row.getBizType()).isEqualTo(AttachmentOwner.OWNERSHIP_TRANSFER.bizType());
                    assertThat(row.getOwnerId()).isEqualTo(77L);
                });
        assertThat(captor.getAllValues())
                .as("不能串到处置单宿主")
                .noneSatisfy(row -> assertThat(row.getOwnerType())
                        .isEqualTo(AttachmentOwner.DISPOSAL_ORDER.code()));
    }

    private static AttachmentRef ref(Long fileId) {
        AttachmentRef r = new AttachmentRef();
        r.setFileId(fileId);
        return r;
    }
```

> **装配提示**：该测试类的 `setUp` 里已经有 `bizAttachmentMapper` 与 `fileService` 的桩（处置单用例在用）。如果 `activeAttachments` 走的是 `selectList` 且未被桩住，Mockito 默认返回空列表 —— 这正是本用例需要的「没有任何存量附件」前置状态。

- [ ] **Step 2: 跑测试确认它失败**

Run:

```bash
cd backend && mvn -q test -Dtest=RecordSheetServiceTest -DfailIfNoTests=false
```

Expected: 编译失败 —— `syncAttachments(...) has private access` 或 `cannot find symbol`（方法当前是包私有的，测试在 `service` 包内可以调用 —— 若编译通过但 `bizAttachmentMapper` 未被 `verify` 捕获，则是桩不匹配，按 Step 1 的提示修桩）。

- [ ] **Step 3: 改可见性**

Modify `backend/src/main/java/com/ams/modules/record/service/RecordSheetService.java`。把两处方法声明改为 public，并补注释：

```java
    /**
     * 附件全量 diff 写入（`owner` 决定这行附件挂在哪条记录上）。
     *
     * <p>**public 的边界**：本方法只做「按宿主 diff」，不判权限 —— 调用方必须自己先过
     * 「该宿主单据的写权限」。处置单走 {@link #syncOrderAttachments}，权属流转走
     * `OWNERSHIP_TRANSFER`，两者共用这唯一的写入点（设计 §4.4）。
     */
    public void syncAttachments(AttachmentOwner owner, Long ownerId, List<AttachmentRef> refs) {
```

```java
    /**
     * 附件回显（与 {@link #syncAttachments} 同一套宿主口径）。
     *
     * <p>查不到 file 的引用**保留**（`fileName` / `url` 为 null）而不是丢行 —— 丢行会让该
     * fileId 在下次保存时不在请求体里，从而被 diff 当成删除。
     */
    public List<AttachmentRef> toAttachmentRefs(AttachmentOwner owner, Long ownerId) {
```

- [ ] **Step 4: 跑测试确认通过，且处置单宿主行为未变**

Run:

```bash
cd backend && mvn -q test -Dtest=RecordSheetServiceTest -DfailIfNoTests=false
```

Expected: PASS，且**既有处置单用例全绿**（`syncOrderAttachments` 的断言是对 `DISPOSAL_ORDER` 的，改可见性不该影响它们）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ams/modules/record/service/RecordSheetService.java \
        backend/src/test/java/com/ams/modules/record/service/RecordSheetServiceTest.java
git commit -m "refactor(record): 附件读写口按 AttachmentOwner 泛化，供权属流转复用"
```

---

## Task 4: 抽出 `AssetHandoverBuilder`

**Files:**

- Create: `backend/src/main/java/com/ams/modules/asset/service/AssetHandoverBuilder.java`
- Modify: `backend/src/main/java/com/ams/modules/asset/service/TransferService.java`（删掉 `buildHandover` 与 4 个依赖）

**Interfaces:**

- Consumes: `ContractMapper`、`BillMapper`、`PrepayService`、`ContractStatus`、`BillStatus`（既有）
- Produces: `public Map<String, Object> build(Asset asset, Long fromCompanyId, Long toCompanyId, Map<String, Object> extraRootFields)`
  - 返回 `LinkedHashMap`，键顺序固定为：`assetId`、`assetNo`、`fromCompanyId`、`toCompanyId`、`<extraRootFields>`、`contracts`、`totalArrears`、`totalDeposit`、`totalPrepay`
  - Task 9 用 `extraRootFields` 放 `transferScope` / `transferMode` / `direction`

**为什么抽出来**：权属流转的生效也要出交接清单（spec §5.3 规则 6），字段口径必须与调拨逐字一致。复制一份必然漂移 —— 仓内已为「同一语义两处实现」付过代价（`replaceZones` vs `deleteProjectZone`）。

**为什么要 `extraRootFields` 而不是让调用方 `put`**：交接清单的键顺序会直接影响存量 `asset_transfer.handover_json` 的 JSON 文本。让调用方事后 `put("transferType", ...)` 会把 `transferType` 挤到末尾，历史快照与新快照的键顺序就不一致了。

---

- [ ] **Step 1: 建 `AssetHandoverBuilder`**

Create `backend/src/main/java/com/ams/modules/asset/service/AssetHandoverBuilder.java`：

内容 = 原 `TransferService.buildHandover` 的逻辑，签名改为四个参数，`transferType` 由 `extraRootFields` 传入。**逐字段照搬，不要顺手改口径**：

```java
package com.ams.modules.asset.service;

import com.ams.modules.asset.entity.Asset;
import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.billing.service.PrepayService;
import com.ams.modules.contract.ContractStatus;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 资产交接清单快照（欠费 / 保证金 / 预收 / 在租合同）。
 *
 * <p>资产**换归属公司**时双方要对的那几张账，都长在这一个结构里：合同清单、每份合同的
 * 预收余额、未收账单（本金 + 未收滞纳金）、以及三个合计。调拨（{@code TransferService}）
 * 与权属流转（{@code OwnershipTransferService}）共用同一份口径 —— 复制第二份必然漂移。
 *
 * <p><b>键顺序是契约的一部分</b>：快照以 JSON 文本存进 {@code *_json} 列，键顺序变了历史快照
 * 与新快照就长得不一样。故 {@code extraRootFields} 在 {@code toCompanyId} 之后、{@code contracts}
 * 之前插入，让调用方特有的键（调拨的 {@code transferType}、权属流转的
 * {@code transferScope/transferMode/direction}）落在固定位置。
 *
 * <p><b>刻意不在此处迁移任何数据</b>：本类只做「读现状 → 生成快照」。账单归属、数据范围的
 * 迁移不在范围内（设计 §3.2），SRS 里调拨的「归属同步迁移」目前也没有实现。
 */
@Service
public class AssetHandoverBuilder {

    private final ContractMapper contractMapper;
    private final BillMapper billMapper;
    private final PrepayService prepayService;

    public AssetHandoverBuilder(
            ContractMapper contractMapper, BillMapper billMapper, PrepayService prepayService) {
        this.contractMapper = contractMapper;
        this.billMapper = billMapper;
        this.prepayService = prepayService;
    }

    /**
     * @param fromCompanyId   转出方（为 null 时回落该资产当前的经营公司）
     * @param toCompanyId     转入方
     * @param extraRootFields 调用方特有的根字段，插在 {@code toCompanyId} 之后。
     *                        传 {@code null} 表示没有，等价于空 Map。
     */
    public Map<String, Object> build(Asset asset, Long fromCompanyId, Long toCompanyId,
            Map<String, Object> extraRootFields) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("assetId", asset.getId());
        root.put("assetNo", asset.getAssetNo());
        root.put("fromCompanyId", fromCompanyId != null ? fromCompanyId : asset.getOperatingCompanyId());
        root.put("toCompanyId", toCompanyId);
        if (extraRootFields != null) {
            root.putAll(extraRootFields);
        }

        List<Contract> contracts = contractMapper.selectList(
                new LambdaQueryWrapper<Contract>()
                        .eq(Contract::getAssetId, asset.getId())
                        .in(Contract::getStatus,
                                ContractStatus.ACTIVE, ContractStatus.EXPIRING,
                                ContractStatus.RENEWABLE, ContractStatus.EXPIRED));
        List<Map<String, Object>> contractRows = new ArrayList<>();
        BigDecimal totalArrears = BigDecimal.ZERO;
        BigDecimal totalDeposit = BigDecimal.ZERO;
        BigDecimal totalPrepay = BigDecimal.ZERO;
        for (Contract c : contracts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("contractId", c.getId());
            row.put("contractNo", c.getContractNo());
            row.put("tenantId", c.getTenantId());
            row.put("status", c.getStatus());
            row.put("depositAmount", c.getDepositAmount());
            BigDecimal prepayBal = prepayService.totalBalance(c.getId());
            row.put("prepayBalance", prepayBal);
            totalDeposit = totalDeposit.add(c.getDepositAmount() == null ? BigDecimal.ZERO : c.getDepositAmount());
            totalPrepay = totalPrepay.add(prepayBal);

            List<Bill> bills = billMapper.selectList(
                    new LambdaQueryWrapper<Bill>()
                            .eq(Bill::getContractId, c.getId())
                            .in(Bill::getStatus, BillStatus.UNPAID, BillStatus.PARTIAL_PAID));
            BigDecimal arrears = BigDecimal.ZERO;
            for (Bill b : bills) {
                BigDecimal due = nz(b.getAmount()).subtract(nz(b.getPaidAmount())).subtract(nz(b.getReducedAmount()));
                BigDecimal late = nz(b.getLateFeeAmount()).subtract(nz(b.getLateFeePaidAmount()));
                arrears = arrears.add(due.max(BigDecimal.ZERO)).add(late.max(BigDecimal.ZERO));
            }
            row.put("arrears", arrears);
            totalArrears = totalArrears.add(arrears);
            contractRows.add(row);
        }
        root.put("contracts", contractRows);
        root.put("totalArrears", totalArrears);
        root.put("totalDeposit", totalDeposit);
        root.put("totalPrepay", totalPrepay);
        return root;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
```

- [ ] **Step 2: 改用新组件，删掉旧实现**

Modify `backend/src/main/java/com/ams/modules/asset/service/TransferService.java`：

1. 删掉 `buildHandover` 与 `nz` 两个私有方法；
2. 删掉不再需要的字段与构造参数：`contractMapper`、`billMapper`、`prepayService`；
3. 删掉随之无用的 import：`com.ams.modules.billing.BillStatus`、`com.ams.modules.billing.entity.Bill`、`com.ams.modules.billing.mapper.BillMapper`、`com.ams.modules.billing.service.PrepayService`、`com.ams.modules.contract.ContractStatus`、`com.ams.modules.contract.entity.Contract`、`com.ams.modules.contract.mapper.ContractMapper`、`java.math.BigDecimal`、`java.util.ArrayList`、`java.util.List`（**逐个确认还有没有别处用到**，`AssetTransfer` 与 `LambdaQueryWrapper` 仍在用）；
4. 加字段 `private final AssetHandoverBuilder handoverBuilder;`，并在构造器里注入（放在 `certificateService` 之后）；
5. `approve` 里那一行改为：

```java
        Map<String, Object> handover = handoverBuilder.build(
                asset,
                transfer.getFromCompanyId() != null
                        ? transfer.getFromCompanyId() : asset.getOperatingCompanyId(),
                transfer.getToCompanyId(),
                Map.of("transferType", transfer.getTransferType()));
```

> 注意：原实现是 `buildHandover` 内部自己回落 `asset.getOperatingCompanyId()`，而 `fromCompanyId` 的回写发生在**之后**（`if (transfer.getFromCompanyId() == null) transfer.setFromCompanyId(from);`）。为避免改动行为，这里显式把同一个回落表达式传进去 —— 效果与原来逐字一致。

- [ ] **Step 3: 编译并跑资产模块的测试**

Run:

```bash
cd backend && mvn -q test -Dtest='com.ams.modules.asset.*Test' -DfailIfNoTests=false
```

Expected: PASS。重点是 `AssetServiceZoneTest` 等既有用例不受影响（无测试构造 `TransferService`，故构造器变更无连带改动）。

- [ ] **Step 4: 跑后端契约守卫**

Run:

```bash
cd frontend && pnpm check:backend
```

Expected: `检查通过`（`check-backend-contracts.mjs` 会解析 Service 的存取器调用与 `pageAssets` 形参个数，本改动不涉及）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ams/modules/asset/service/AssetHandoverBuilder.java \
        backend/src/main/java/com/ams/modules/asset/service/TransferService.java
git commit -m "refactor(asset): 抽出 AssetHandoverBuilder，调拨与权属流转共用交接清单口径"
```

---

## Task 5: `OwnershipTransferredEvent` + 登记 + 通知订阅

**Files:**

- Create: `backend/src/main/java/com/ams/platform/event/OwnershipTransferredEvent.java`
- Modify: `backend/src/main/java/com/ams/platform/event/outbox/EventTypeRegistry.java`
- Modify: `backend/src/main/java/com/ams/modules/notification/listener/NotificationEventListener.java`
- Modify: `backend/src/test/java/com/ams/platform/event/outbox/EventTypeRegistryTest.java`

**Interfaces:**

- Consumes: 无（可在 Task 1 之后任意时刻做）
- Produces: `new OwnershipTransferredEvent(Long transferId, Long assetId, Long fromCompanyId, Long toCompanyId, String direction)`；`aggregateType() == "ownership_transfer"`。Task 9 发布它。

---

- [ ] **Step 1: 改测试为 9 类**

Modify `backend/src/test/java/com/ams/platform/event/outbox/EventTypeRegistryTest.java`：

1. 加 import `import com.ams.platform.event.OwnershipTransferredEvent;`
2. `allEvents()` 里追加一行：

```java
                new AssetTransferredEvent(7007L, 6006L, 11L, 22L),
                new OwnershipTransferredEvent(8008L, 6006L, 33L, 44L, "external"));
```

3. `allDsdEventsAreRegistered` 的期望列表加 `"OwnershipTransferredEvent"`，并把 `@DisplayName` 改成 `DSD §4.8 全部事件已登记，登记名即事件类简单名`（**去掉写死的数字** —— 写死 8 会在每次新增事件时都要改一遍文案）：

```java
        assertThat(EventTypeRegistry.registeredTypes())
                .containsExactlyInAnyOrder(
                        "ApprovalCompletedEvent", "PaymentRegisteredEvent", "BillIssuedEvent",
                        "BillOverdueEvent", "AlertTriggeredEvent", "DisposalCompletedEvent",
                        "ContractExpiredEvent", "AssetTransferredEvent",
                        "OwnershipTransferredEvent");
```

4. `everyEventSurvivesRoundTrip` 的 `@DisplayName` 同样去掉「全部 8 类」里的数字。

- [ ] **Step 2: 跑测试确认它失败**

Run:

```bash
cd backend && mvn -q test -Dtest=EventTypeRegistryTest -DfailIfNoTests=false
```

Expected: 编译失败 —— `cannot find symbol: class OwnershipTransferredEvent`。

- [ ] **Step 3: 建事件类**

Create `backend/src/main/java/com/ams/platform/event/OwnershipTransferredEvent.java`：

```java
package com.ams.platform.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * 资产权属流转完成：{@code OwnershipTransferred → 双方对账提醒}。
 *
 * <p>由 {@code OwnershipTransferService.effect} 在逐个资产改完公司字段后**按资产**发布
 * （而不是一张单一条）—— 通知与下游投影都按资产粒度消费，一张单一条会让下游还得自己拆。
 *
 * <p>与 {@link AssetTransferredEvent}（调拨，单资产、改经营公司）刻意分开：两者的业务口径
 * 与通知文案都不同，合并一个事件会让监听器必须靠额外字段去猜是哪一种。
 *
 * <p>全部字段 final + {@code @JsonCreator}：outbox 重放要求载荷能反序列化，
 * 缺 {@code @JsonCreator} 会让重放失败并把事件置 dead（见 {@code EventTypeRegistryTest}）。
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class OwnershipTransferredEvent extends DomainEvent {

    private final Long transferId;
    private final Long assetId;
    private final Long fromCompanyId;
    private final Long toCompanyId;
    /** internal / external —— 外部流转需要双方对账关注「已转出集团」。 */
    private final String direction;

    @JsonCreator
    public OwnershipTransferredEvent(
            @JsonProperty("transferId") Long transferId,
            @JsonProperty("assetId") Long assetId,
            @JsonProperty("fromCompanyId") Long fromCompanyId,
            @JsonProperty("toCompanyId") Long toCompanyId,
            @JsonProperty("direction") String direction) {
        this.transferId = transferId;
        this.assetId = assetId;
        this.fromCompanyId = fromCompanyId;
        this.toCompanyId = toCompanyId;
        this.direction = direction;
    }

    @Override
    public String aggregateType() {
        return "ownership_transfer";
    }

    @Override
    public Long aggregateId() {
        return transferId;
    }
}
```

- [ ] **Step 4: 登记事件类型**

Modify `backend/src/main/java/com/ams/platform/event/outbox/EventTypeRegistry.java`：

1. 加 import `import com.ams.platform.event.OwnershipTransferredEvent;`
2. 在 `Map.of(...)` 末尾追加一项（注意前一项结尾加逗号）：

```java
            AssetTransferredEvent.class.getSimpleName(), AssetTransferredEvent.class,
            OwnershipTransferredEvent.class.getSimpleName(), OwnershipTransferredEvent.class);
```

> **`Map.of` 的上限是 10 对**。加上这一项后正好 9 对，仍在限内；将来第 11 类事件出现时 `Map.of` 会编译失败，届时需改用 `Map.ofEntries`（这是好事，编译期就能发现）。

3. 类注释里的「{@code EventTypeRegistryTest} 会逐一断言 8 类事件可往返」改为不写数字的表述：`{@code EventTypeRegistryTest} 会逐一断言每类事件可往返`。

- [ ] **Step 5: 跑测试确认通过**

Run:

```bash
cd backend && mvn -q test -Dtest=EventTypeRegistryTest -DfailIfNoTests=false
```

Expected: PASS（含往返序列化、身份还原、未登记类型报错）。

- [ ] **Step 6: 加通知订阅**

Modify `backend/src/main/java/com/ams/modules/notification/listener/NotificationEventListener.java`：

1. 加 import `import com.ams.platform.event.OwnershipTransferredEvent;`
2. 在 `onAssetTransferred` 之后追加（**必须**带 `consumed(event.getEventId())` 幂等守卫，与既有 8 类订阅同构）：

```java
    /** 权属流转完成 → 双方对账提醒（设计 §5.6）。 */
    @EventListener
    @Transactional
    public void onOwnershipTransferred(OwnershipTransferredEvent event) {
        if (consumed(event.getEventId())) {
            return;
        }
        notificationService.sendByTemplate(
                "ownership_transferred",
                SecurityUtils.currentUserIdOrNull(),
                Map.of(
                        "assetId", String.valueOf(event.getAssetId()),
                        "fromCompanyId", String.valueOf(event.getFromCompanyId()),
                        "toCompanyId", String.valueOf(event.getToCompanyId()),
                        "direction", nullToEmpty(event.getDirection())),
                "ownership_transfer",
                event.getTransferId());
    }
```

> 模板 `ownership_transferred` 的 `body_tpl` 用了 4 个占位符，这里必须把 4 个都传进 Map —— 少一个会让 `sendByTemplate` 渲染出字面量 `{{xxx}}`。

- [ ] **Step 7: 跑通知模块测试并加一条订阅断言**

Run:

```bash
cd backend && mvn -q test -Dtest='NotificationEventListenerTest,EventTypeRegistryTest' -DfailIfNoTests=false
```

Expected: PASS。若 `NotificationEventListenerTest` 有「订阅了 N 类事件」这类计数断言，按它的报错把 8 改成 9（**只改数字，不改结构**）。

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/ams/platform/event/OwnershipTransferredEvent.java \
        backend/src/main/java/com/ams/platform/event/outbox/EventTypeRegistry.java \
        backend/src/main/java/com/ams/modules/notification/listener/NotificationEventListener.java \
        backend/src/test/java/com/ams/platform/event/outbox/EventTypeRegistryTest.java \
        backend/src/test/java/com/ams/modules/notification/listener/NotificationEventListenerTest.java
git commit -m "feat(event): OwnershipTransferredEvent 登记 + 权属流转完成通知订阅"
```

---

## Task 6: 实体 / Mapper / DTO

**Files:**

- Create: `backend/src/main/java/com/ams/modules/ownership/entity/OwnershipTransfer.java`
- Create: `backend/src/main/java/com/ams/modules/ownership/entity/OwnershipTransferAsset.java`
- Create: `backend/src/main/java/com/ams/modules/ownership/mapper/OwnershipTransferMapper.java`
- Create: `backend/src/main/java/com/ams/modules/ownership/mapper/OwnershipTransferAssetMapper.java`
- Create: `backend/src/main/java/com/ams/modules/ownership/dto/OwnershipTransferInput.java`
- Create: `backend/src/main/java/com/ams/modules/ownership/dto/OwnershipTransferView.java`
- Create: `backend/src/main/java/com/ams/modules/ownership/dto/OwnershipTransferAssetView.java`
- Create: `backend/src/main/java/com/ams/modules/ownership/dto/TransferAssetOption.java`

**Interfaces:**

- Consumes: `BaseEntity`（`createdAt/updatedAt/createdBy/updatedBy` 自动填充）
- Produces（Task 7–10 依赖，**名字必须逐字一致**）：
  - `OwnershipTransfer`：`id, direction, transferScope, fromCompanyId, toCompanyId, transferMode, applicantUserId, applicantName, approvalDeadline, amountWan, reason, handoverJson, status, effectedAt, deletedAt`
  - `OwnershipTransferAsset`：`id, transferId, assetId, fromPropertyCompanyId, fromOperatingCompanyId`
  - `OwnershipTransferInput`：`id, direction, transferScope, fromCompanyId, toCompanyId, transferMode, applicantUserId, applicantName, approvalDeadline, amountWan, reason, List<Long> assetIds, List<AttachmentRef> attachments`
  - `OwnershipTransferView`：`id, direction, transferScope, fromCompanyId, fromCompanyName, toCompanyId, toCompanyName, transferMode, applicantUserId, applicantName, approvalDeadline, amountWan, reason, status, effectedAt, createdAt, assetCount, List<OwnershipTransferAssetView> assets, List<AttachmentRef> attachments`
  - `OwnershipTransferAssetView`：`assetId, assetNo, assetName, projectName, zoneName, floorNo, fromPropertyCompanyId, fromOperatingCompanyId`
  - `TransferAssetOption`：`assetId, assetNo, name, projectName, zoneName, floorNo`

**本 task 没有独立测试** —— 纯数据载体，靠 Task 7 起的编译与用例覆盖。这是刻意的：给 8 个 Lombok DTO 写 getter 测试没有意义。

---

- [ ] **Step 1: 写两个实体**

Create `backend/src/main/java/com/ams/modules/ownership/entity/OwnershipTransfer.java`：

```java
package com.ams.modules.ownership.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 权属流转主单（{@code ownership_transfer}，V54 迁移）。
 *
 * <p>一张单选**多个**资产，按 {@link #transferScope} 决定改写资产的
 * {@code property_company_id} 还是 {@code operating_company_id}（或两个都改）。
 *
 * <p>状态机只有两态：{@code draft → completed}。本期不接审批引擎（设计 §3.2），
 * 所以没有 {@code approving}，也没有 {@code approval_instance_id} / {@code reject_reason}
 * —— 不留悬空字段。{@link #approvalDeadline} 保留但只是业务留痕（不校验、不提醒）。
 *
 * <p>软删用 {@link #deletedAt} + 显式 {@code isNull} 过滤，**不用** {@code @TableLogic}：
 * 全仓逻辑删除字段的口径是 {@code deleted:0/1}，与 {@code deleted_at} 范式不一致
 * （见 {@code AssetUnit} 的注释）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ownership_transfer")
public class OwnershipTransfer extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** internal 内部流转 / external 外部流转，取字典 transfer_direction。 */
    private String direction;

    /** both 经营权且产权 / property 产权 / operating 经营权，取字典 transfer_scope。 */
    private String transferScope;

    private Long fromCompanyId;

    private Long toCompanyId;

    /** allocate 直接划拨 / purchase 购买流转 / auction 拍卖流转，取字典 transfer_mode。 */
    private String transferMode;

    /** 内员时非空（并据此外查 sys_user.name 覆盖姓名快照）；外部人员为 null。 */
    private Long applicantUserId;

    /** 姓名快照。内员由服务端覆盖，外部人员由用户手填。 */
    private String applicantName;

    /** 审批截止时间。本期无审批环节，纯记录字段。 */
    private LocalDateTime approvalDeadline;

    /** 金额(万元)，2 位小数。 */
    private BigDecimal amountWan;

    private String reason;

    /** 生效时生成的交接清单快照（JSON 文本，形态见 AssetHandoverBuilder）。 */
    private String handoverJson;

    /** draft / completed。 */
    private String status;

    private LocalDateTime effectedAt;

    private LocalDateTime deletedAt;
}
```

Create `backend/src/main/java/com/ams/modules/ownership/entity/OwnershipTransferAsset.java`：

```java
package com.ams.modules.ownership.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 权属流转明细：一张单 × 一个资产（{@code ownership_transfer_asset}，V54 迁移）。
 *
 * <p>两个 {@code from*} 是**生效时的原值快照**，不是「读 asset 现值」——
 * 生效后资产已经改成新公司，资产再被流转一次或公司改名，靠现值反推就追溯不到了。
 *
 * <p>没有 {@code deletedAt}：草稿改明细走全量 diff 真删（同 {@code DisposalService.syncForAsset}
 * 的做法）。明细行不被任何外部对象引用（附件挂在主单上），留软删只会制造永不清理的孤儿行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ownership_transfer_asset")
public class OwnershipTransferAsset extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long transferId;

    private Long assetId;

    /** 生效时该资产的产权公司原值。 */
    private Long fromPropertyCompanyId;

    /** 生效时该资产的经营公司原值。 */
    private Long fromOperatingCompanyId;
}
```

- [ ] **Step 2: 写两个 Mapper**

Create `backend/src/main/java/com/ams/modules/ownership/mapper/OwnershipTransferMapper.java`：

```java
package com.ams.modules.ownership.mapper;

import com.ams.modules.ownership.entity.OwnershipTransfer;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OwnershipTransferMapper extends BaseMapper<OwnershipTransfer> {
}
```

Create `backend/src/main/java/com/ams/modules/ownership/mapper/OwnershipTransferAssetMapper.java`：

```java
package com.ams.modules.ownership.mapper;

import com.ams.modules.ownership.entity.OwnershipTransferAsset;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OwnershipTransferAssetMapper extends BaseMapper<OwnershipTransferAsset> {
}
```

- [ ] **Step 3: 写四个 DTO**

Create `backend/src/main/java/com/ams/modules/ownership/dto/OwnershipTransferInput.java`：

```java
package com.ams.modules.ownership.dto;

import com.ams.modules.record.dto.AttachmentRef;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 权属流转表单入参（新建 / 编辑草稿共用）。
 *
 * <p>没有 `status` 字段：状态只能由服务端的状态机推进，不接受表单写入 ——
 * 否则可以把一张已完成单改回 draft，再生效一次，把产权改第二遍。
 */
@Data
public class OwnershipTransferInput {

    /** 编辑草稿时由路径上的 id 提供；新建时忽略。 */
    private Long id;

    private String direction;
    private String transferScope;
    private Long fromCompanyId;
    private Long toCompanyId;
    private String transferMode;

    /** 内员 id；为空表示外部人员（此时 applicantName 必填）。 */
    private Long applicantUserId;
    private String applicantName;

    private LocalDateTime approvalDeadline;

    /** 金额(万元)，超过 2 位小数一律 400。 */
    private BigDecimal amountWan;

    private String reason;

    /** 选中的资产。至少 1 个，服务端去重并逐条校验归属。 */
    private List<Long> assetIds;

    private List<AttachmentRef> attachments;
}
```

Create `backend/src/main/java/com/ams/modules/ownership/dto/OwnershipTransferAssetView.java`：

```java
package com.ams.modules.ownership.dto;

import lombok.Data;

/**
 * 权属流转详情里的一行资产。
 *
 * <p>{@code projectName} / {@code zoneName} / {@code floorNo} 是为**前端 4 段展示**
 * （`项目 · 分区 · 楼层 · 资产名称`）准备的原料，由服务端一次查全 —— 让前端为每行再发请求
 * 会在展开 20 个资产时打出 20 个请求。
 */
@Data
public class OwnershipTransferAssetView {

    private Long assetId;
    private String assetNo;
    private String assetName;

    private String projectName;
    private String zoneName;
    private Integer floorNo;

    private Long fromPropertyCompanyId;
    private Long fromOperatingCompanyId;
}
```

Create `backend/src/main/java/com/ams/modules/ownership/dto/TransferAssetOption.java`：

```java
package com.ams.modules.ownership.dto;

import lombok.Data;

/**
 * 资产下拉项（`GET /ownership-transfers/asset-options`）。
 *
 * <p>刻意只回原料不拼 label：label 的拼接规则（空段如何跳过）属于展示层，
 * 放在服务端会让「改一次文案要发一次后端版本」。
 */
@Data
public class TransferAssetOption {

    private Long assetId;
    private String assetNo;
    private String name;

    private String projectName;
    private String zoneName;
    private Integer floorNo;
}
```

Create `backend/src/main/java/com/ams/modules/ownership/dto/OwnershipTransferView.java`：

```java
package com.ams.modules.ownership.dto;

import com.ams.modules.record.dto.AttachmentRef;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 权属流转列表 / 详情出参。
 *
 * <p>公司名与附件都内联：列表页每行要显示「A → B」，详情页要显示附件，
 * 让前端为每行再发两次请求会让列表页变成 N+1。
 */
@Data
public class OwnershipTransferView {

    private Long id;

    private String direction;
    private String transferScope;

    private Long fromCompanyId;
    private String fromCompanyName;
    private Long toCompanyId;
    private String toCompanyName;

    private String transferMode;

    private Long applicantUserId;
    private String applicantName;

    private LocalDateTime approvalDeadline;
    private BigDecimal amountWan;
    private String reason;

    private String status;
    private LocalDateTime effectedAt;
    private LocalDateTime createdAt;

    /** 资产数（列表页不必展开明细，故单独给一个计数）。 */
    private int assetCount;

    /** 详情才有值；列表页留空以省一次 join。 */
    private List<OwnershipTransferAssetView> assets;

    private List<AttachmentRef> attachments;
}
```

- [ ] **Step 4: 编译**

Run:

```bash
cd backend && mvn -q -o compile 2>/dev/null || mvn -q compile
```

Expected: 编译通过（`mvn compile` 会下载依赖，首次可能较慢）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ams/modules/ownership/
git commit -m "feat(ownership): 权属流转实体 / Mapper / DTO"
```

---

## Task 7: `TransferDirectionResolver`（方向判定）

**Files:**

- Create: `backend/src/main/java/com/ams/modules/ownership/service/TransferDirectionResolver.java`
- Create: `backend/src/test/java/com/ams/modules/ownership/TransferDirectionResolverTest.java`

**Interfaces:**

- Consumes: `CompanyTreeService.listCompanies()` / `ancestorIds(Long)` / `isActiveCompany(Long)`（既有，`com.ams.modules.org.service.CompanyTreeService`）
- Produces:
  - `public String resolve(Long fromCompanyId, Long toCompanyId)` → `"internal"` / `"external"`
  - `public void assertDirectionMatches(String direction, Long fromCompanyId, Long toCompanyId)` → 不一致时抛 `AppException`

**为什么单独成类**：方向判定是本模块**唯一**的「内 / 外」真相点（spec §5.2）。放进 `OwnershipTransferService` 会让它跟着服务一起长大，也会让判定规则没法和业务规则分开测。

**判定规则**：原公司与目标公司沿 `company.parent_id` 上溯到根；**同根 = 内部**，不同根 = 外部。

---

- [ ] **Step 1: 写失败的测试**

Create `backend/src/test/java/com/ams/modules/ownership/TransferDirectionResolverTest.java`：

```java
package com.ams.modules.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.service.CompanyTreeService;
import com.ams.modules.ownership.service.TransferDirectionResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 内 / 外方向判定（设计 §5.2）。
 *
 * <p>公司树：
 * <pre>
 *   1 集团（根）
 *   ├── 2 城投
 *   │   └── 3 城投商运
 *   └── 4 文旅
 *   9 外部私企（parent_id 为空 → 自成一根）
 * </pre>
 *
 * <p>关键的三条：同根是内部、跨根是外部、**目标公司在树外（自成一根）也是外部**。
 * 最后一条是「外部受让方在组织架构建档、parent_id 留空」能成立的前提。
 */
class TransferDirectionResolverTest {

    private CompanyTreeService companyTreeService;
    private TransferDirectionResolver resolver;

    @BeforeEach
    void setUp() {
        companyTreeService = mock(CompanyTreeService.class);
        when(companyTreeService.listCompanies()).thenReturn(List.of(
                company(1L, null, "集团"),
                company(2L, 1L, "城投"),
                company(3L, 2L, "城投商运"),
                company(4L, 1L, "文旅"),
                company(9L, null, "外部私企")));
        when(companyTreeService.ancestorIds(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> {
                    Long id = inv.getArgument(0);
                    List<Long> path = new java.util.ArrayList<>();
                    Long cursor = id;
                    int guard = 0;
                    while (cursor != null && guard++ < 10) {
                        path.add(0, cursor);
                        cursor = parentOf(cursor);
                    }
                    return path;
                });
        resolver = new TransferDirectionResolver(companyTreeService);
    }

    private static Long parentOf(Long id) {
        return switch (id.intValue()) {
            case 2, 4 -> 1L;
            case 3 -> 2L;
            default -> null;
        };
    }

    private static Company company(Long id, Long parentId, String name) {
        Company c = new Company();
        c.setId(id);
        c.setParentId(parentId);
        c.setName(name);
        c.setStatus(1);
        return c;
    }

    @Test
    @DisplayName("兄弟公司之间：同根 → 内部流转")
    void siblingsAreInternal() {
        assertThat(resolver.resolve(2L, 4L)).isEqualTo("internal");
    }

    @Test
    @DisplayName("母公司与孙公司之间：同根 → 内部流转")
    void ancestorAndDescendantAreInternal() {
        assertThat(resolver.resolve(1L, 3L)).isEqualTo("internal");
        assertThat(resolver.resolve(3L, 1L)).isEqualTo("internal");
    }

    @Test
    @DisplayName("目标公司自成一根（外部受让方）→ 外部流转")
    void separateRootIsExternal() {
        assertThat(resolver.resolve(2L, 9L)).isEqualTo("external");
        assertThat(resolver.resolve(9L, 2L)).isEqualTo("external");
    }

    @Test
    @DisplayName("公司不存在时拒绝：ancestorIds 会把不存在的公司当成自己的根，静默算成「外部」")
    void rejectsUnknownCompany() {
        assertThatThrownBy(() -> resolver.resolve(2L, 999L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("公司不存在");
    }

    @Test
    @DisplayName("direction 与公司树不一致时 400：声明内部但目标在树外")
    void rejectsDirectionMismatch() {
        assertThatThrownBy(() -> resolver.assertDirectionMatches("internal", 2L, 9L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("外部流转");
    }

    @Test
    @DisplayName("direction 与公司树一致时放行")
    void acceptsMatchingDirection() {
        resolver.assertDirectionMatches("internal", 2L, 3L);
        resolver.assertDirectionMatches("external", 2L, 9L);
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run:

```bash
cd backend && mvn -q test -Dtest=TransferDirectionResolverTest -DfailIfNoTests=false
```

Expected: 编译失败 —— `cannot find symbol: class TransferDirectionResolver`。

- [ ] **Step 3: 实现**

Create `backend/src/main/java/com/ams/modules/ownership/service/TransferDirectionResolver.java`：

```java
package com.ams.modules.ownership.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.service.CompanyTreeService;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 权属流转的内 / 外方向判定（设计 §5.2）—— 本模块**唯一**的「内 / 外」真相点。
 *
 * <p>规则：原公司与目标公司沿 {@code company.parent_id} 上溯到根，<b>同根 = 内部流转，
 * 不同根 = 外部流转</b>。
 *
 * <p>为什么不用 {@code CompanyTreeService.rootCompany()}：它取 {@code parent_id IS NULL} 中
 * {@code sort, id} 最小的那一行。一旦有第二棵树（外部受让方），就可能取到外部公司，
 * 「内部」的范围会随外部公司的排序变化而漂移。
 *
 * <p>为什么不用 {@code company_type} 字典：V22 定义的值是 {@code provincial_sasac} /
 * {@code public_institution} / {@code state_owned} / {@code private_enterprise}，而演示种子
 * 写的是 {@code group} / {@code subsidiary}，两边本就不一致，不能作为判定依据。
 */
@Service
public class TransferDirectionResolver {

    public static final String INTERNAL = "internal";
    public static final String EXTERNAL = "external";

    private final CompanyTreeService companyTreeService;

    public TransferDirectionResolver(CompanyTreeService companyTreeService) {
        this.companyTreeService = companyTreeService;
    }

    /**
     * 目标公司与原公司是否同根。
     *
     * <p>两个公司都必须存在且启用：{@code ancestorIds} 对不存在的公司会把它自己当成根
     * （父指针查不到就停），于是任何脏 id 都会被静默算成「外部」—— 必须先挡掉。
     */
    public String resolve(Long fromCompanyId, Long toCompanyId) {
        if (fromCompanyId == null || toCompanyId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "原公司与新公司均必填");
        }
        requireActiveCompany(fromCompanyId, "原公司");
        requireActiveCompany(toCompanyId, "新公司");
        return rootOf(fromCompanyId).equals(rootOf(toCompanyId)) ? INTERNAL : EXTERNAL;
    }

    /**
     * 校验调用方声明的 {@code direction} 与公司树判定一致。
     *
     * <p>「方向」是用户选的字典值，不是由树反推出来的 —— 二者不一致说明选择与事实矛盾
     * （例如声明内部流转却填了一家集团外的公司），必须拒绝而不是以树覆盖用户选择：
     * 静默纠正会让界面上显示的方向与落库的方向不一致。
     */
    public void assertDirectionMatches(String direction, Long fromCompanyId, Long toCompanyId) {
        String actual = resolve(fromCompanyId, toCompanyId);
        if (!actual.equals(direction)) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    EXTERNAL.equals(actual)
                            ? "所选新公司不在本集团内，请改选「外部流转」"
                            : "所选新公司属于本集团，请改选「内部流转」");
        }
    }

    /** 上溯到根（{@link CompanyTreeService#ancestorIds} 返回 root → 自身，故取首元素）。 */
    private Long rootOf(Long companyId) {
        List<Long> path = companyTreeService.ancestorIds(companyId);
        return path.isEmpty() ? companyId : path.get(0);
    }

    private void requireActiveCompany(Long companyId, String label) {
        if (!companyTreeService.isActiveCompany(companyId)) {
            throw new AppException(ErrorCode.BAD_REQUEST, label + "不存在或已停用：" + companyId);
        }
    }

    /**
     * 供服务层复用：把公司名一次性查全（列表页每行都要显示「A → B」，逐行查会变成 N+1）。
     */
    public Map<Long, String> namesById() {
        return companyTreeService.listCompanies().stream()
                .collect(Collectors.toMap(Company::getId, Company::getName, (a, b) -> a));
    }

    /** 供测试与调用方取公司实体（避免直接依赖 CompanyTreeService 的返回顺序）。 */
    public Function<Long, Company> lookup() {
        Map<Long, Company> byId = companyTreeService.listCompanies().stream()
                .collect(Collectors.toMap(Company::getId, Function.identity(), (a, b) -> a));
        return byId::get;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run:

```bash
cd backend && mvn -q test -Dtest=TransferDirectionResolverTest -DfailIfNoTests=false
```

Expected: PASS（6 个用例）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ams/modules/ownership/service/TransferDirectionResolver.java \
        backend/src/test/java/com/ams/modules/ownership/TransferDirectionResolverTest.java
git commit -m "feat(ownership): 内/外方向判定与企业树一致性校验"
```

---

## Task 8: `OwnershipTransferService` 草稿 CRUD + 校验

**Files:**

- Create: `backend/src/main/java/com/ams/modules/ownership/service/OwnershipTransferService.java`
- Create: `backend/src/test/java/com/ams/modules/ownership/service/OwnershipTransferServiceTest.java`

**Interfaces:**

- Consumes:
  - `OwnershipTransferResolver` → `resolve/assertDirectionMatches/namesById`（Task 7）
  - `OwnershipTransferMapper` / `OwnershipTransferAssetMapper`（Task 6）
  - `OwnershipTransferInput` / `OwnershipTransferView` / `OwnershipTransferAssetView` / `TransferAssetOption`（Task 6）
  - `RecordSheetService.syncAttachments/toAttachmentRefs`（Task 3）
  - `AssetHandoverBuilder`（Task 4）、`CertificateService.assertNotMortgaged`（既有）
- Produces（Task 9 / 10 依赖，**签名逐字一致**）：
  - `public PageResult<OwnershipTransferView> page(long page, long pageSize, String status, String direction, Long fromCompanyId, String keyword)`
  - `public OwnershipTransferView get(Long id)`
  - `public PageResult<TransferAssetOption> assetOptions(Long companyId, String transferScope, String keyword, long page, long pageSize)`
  - `public OwnershipTransferView create(OwnershipTransferInput input)`
  - `public OwnershipTransferView update(Long id, OwnershipTransferInput input)`
  - `public void delete(Long id)`
  - `public OwnershipTransferView effect(Long id)`（Task 9 实现；本 task 先留一个抛 `UnsupportedOperationException` 的占位会被 Task 9 替换 —— **不要**把这个占位提交给下游，Task 9 紧跟其后）

---

- [ ] **Step 1: 写失败的测试**

Create `backend/src/test/java/com/ams/modules/ownership/service/OwnershipTransferServiceTest.java`：

```java
package com.ams.modules.ownership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.service.AssetHandoverBuilder;
import com.ams.modules.asset.service.CertificateService;
import com.ams.modules.org.service.CompanyTreeService;
import com.ams.modules.ownership.dto.OwnershipTransferInput;
import com.ams.modules.ownership.entity.OwnershipTransfer;
import com.ams.modules.ownership.mapper.OwnershipTransferAssetMapper;
import com.ams.modules.ownership.mapper.OwnershipTransferMapper;
import com.ams.modules.record.service.RecordSheetService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 权属流转草稿的校验闭环（设计 §5.3）。
 *
 * <p>本用例只覆盖**草稿**阶段：方向一致性、按权属类型的归属校验、在押拦截、非草稿不可改。
 * 生效阶段的落库断言在 Task 9 的用例里（那两个关注点分开测，改一个不会让另一个的报错指向错的地方）。
 */
class OwnershipTransferServiceTest {

    private static final long FROM = 2L;
    private static final long TO = 3L;

    private OwnershipTransferMapper transferMapper;
    private OwnershipTransferAssetMapper assetMapper;
    private AssetMapper assetEntityMapper;
    private CertificateService certificateService;

    @BeforeEach
    void setUp() {
        transferMapper = mock(OwnershipTransferMapper.class);
        assetMapper = mock(OwnershipTransferAssetMapper.class);
        assetEntityMapper = mock(AssetMapper.class);
        certificateService = mock(CertificateService.class);
        // 默认放行在押校验；需要拦的用例单独 when(...).thenThrow(...)
    }

    private OwnershipTransferService newService() {
        CompanyTreeService tree = TransferDirectionResolverTestSupport.tree();
        return new OwnershipTransferService(
                transferMapper,
                assetMapper,
                assetEntityMapper,
                new TransferDirectionResolver(tree),
                certificateService,
                mock(AssetHandoverBuilder.class),
                mock(RecordSheetService.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static Asset asset(long id, Long propertyCompanyId, Long operatingCompanyId) {
        Asset a = new Asset();
        a.setId(id);
        a.setAssetNo("A-" + id);
        a.setName("资产" + id);
        a.setPropertyCompanyId(propertyCompanyId);
        a.setOperatingCompanyId(operatingCompanyId);
        a.setLifecycleStatus("in_book");
        return a;
    }

    private static OwnershipTransferInput input(String scope, Long... assetIds) {
        OwnershipTransferInput in = new OwnershipTransferInput();
        in.setDirection("internal");
        in.setTransferScope(scope);
        in.setFromCompanyId(FROM);
        in.setToCompanyId(TO);
        in.setTransferMode("allocate");
        in.setApplicantName("张三");
        in.setAssetIds(List.of(assetIds));
        return in;
    }

    @Test
    @DisplayName("权属类型=产权：只校验 property_company_id 归属")
    void propertyScopeChecksPropertyCompany() {
        when(assetEntityMapper.selectById(anyLong()))
                .thenReturn(asset(11L, FROM, 999L));

        newService().create(input("property", 11L));

        verify(transferMapper).insert(any());
    }

    @Test
    @DisplayName("权属类型=产权 但资产产权公司不是所选原公司 -> 400，且不落库")
    void propertyScopeRejectsWrongCompany() {
        when(assetEntityMapper.selectById(anyLong())).thenReturn(asset(11L, 999L, FROM));

        assertThatThrownBy(() -> newService().create(input("property", 11L)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不属于所选原公司");
        verify(transferMapper, never()).insert(any());
    }

    @Test
    @DisplayName("权属类型=经营权：校验 operating_company_id 归属（产权公司不同也放行）")
    void operatingScopeChecksOperatingCompany() {
        when(assetEntityMapper.selectById(anyLong())).thenReturn(asset(11L, 999L, FROM));

        newService().create(input("operating", 11L));

        verify(transferMapper).insert(any());
    }

    @Test
    @DisplayName("权属类型=经营权且产权：两个字段都必须属于原公司")
    void bothScopeChecksBothCompanies() {
        when(assetEntityMapper.selectById(anyLong())).thenReturn(asset(11L, FROM, 999L));

        assertThatThrownBy(() -> newService().create(input("both", 11L)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不属于所选原公司");
    }

    @Test
    @DisplayName("在押资产一律拒绝流转（与处置/调拨同口径）")
    void rejectsMortgagedAsset() {
        when(assetEntityMapper.selectById(anyLong())).thenReturn(asset(11L, FROM, FROM));
        when(certificateService.assertNotMortgaged(anyLong()))
                .thenThrow(new AppException(com.ams.common.exception.ErrorCode.BUSINESS_ERROR,
                        "资产处于在押状态，须先解押"));

        assertThatThrownBy(() -> newService().create(input("property", 11L)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("在押");
    }

    @Test
    @DisplayName("资产列表去重：同一资产传两次只落一条明细")
    void deduplicatesAssetIds() {
        when(assetEntityMapper.selectById(anyLong())).thenReturn(asset(11L, FROM, FROM));

        newService().create(input("property", 11L, 11L));

        verify(assetMapper).insert(any());
    }

    @Test
    @DisplayName("资产列表为空 -> 400")
    void rejectsEmptyAssetList() {
        assertThatThrownBy(() -> newService().create(input("property")))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("至少");
    }

    @Test
    @DisplayName("新老公司相同 -> 400（无变化的流转必须挡在落库前）")
    void rejectsSameCompany() {
        OwnershipTransferInput in = input("property", 11L);
        in.setToCompanyId(FROM);

        assertThatThrownBy(() -> newService().create(in))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不能相同");
    }

    @Test
    @DisplayName("金额超过 2 位小数 -> 400（不依赖 PG 静默四舍五入）")
    void rejectsTooManyDecimals() {
        OwnershipTransferInput in = input("property", 11L);
        in.setAmountWan(new java.math.BigDecimal("12.345"));

        assertThatThrownBy(() -> newService().create(in))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("万元");
    }

    @Test
    @DisplayName("非草稿不可改：completed 的单子拒绝 update")
    void rejectsUpdateOnCompleted() {
        OwnershipTransfer existing = new OwnershipTransfer();
        existing.setId(5L);
        existing.setStatus("completed");
        when(transferMapper.selectById(5L)).thenReturn(existing);

        assertThatThrownBy(() -> newService().update(5L, input("property", 11L)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("草稿");
    }

    @Test
    @DisplayName("非草稿不可删：completed 的单子拒绝 delete")
    void rejectsDeleteOnCompleted() {
        OwnershipTransfer existing = new OwnershipTransfer();
        existing.setId(5L);
        existing.setStatus("completed");
        when(transferMapper.selectById(5L)).thenReturn(existing);

        assertThatThrownBy(() -> newService().delete(5L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("草稿");
    }

    @Test
    @DisplayName("已软删的单子查不到 -> 404（列表与详情都不能冒出幽灵单）")
    void hiddenWhenSoftDeleted() {
        when(transferMapper.selectById(5L)).thenReturn(null);

        assertThatThrownBy(() -> newService().get(5L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("方向与公司树不一致 -> 400")
    void rejectsDirectionMismatch() {
        when(assetEntityMapper.selectById(anyLong())).thenReturn(asset(11L, FROM, FROM));
        OwnershipTransferInput in = input("property", 11L);
        in.setDirection("external");

        assertThatThrownBy(() -> newService().create(in))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("属于本集团");
    }

    @Test
    @DisplayName("列表按状态筛选：状态透传给查询条件")
    void pagePassesStatusFilter() {
        when(transferMapper.selectPage(any(), any()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        newService().page(1, 10, "draft", null, null, null);

        verify(transferMapper).selectPage(any(), any());
    }
}
```

同时 Create `backend/src/test/java/com/ams/modules/ownership/TransferDirectionResolverTestSupport.java`：把 Task 7 的 `TransferDirectionResolverTest` 里那套公司树 mock 抽成共享夹具（**测试支撑类，不是生产代码**）：

```java
package com.ams.modules.ownership;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ams.modules.org.entity.Company;
import com.ams.modules.org.service.CompanyTreeService;
import java.util.ArrayList;
import java.util.List;

/**
 * 公司树测试夹具：1 集团（根）→ 2 城投 → 3 城投商运；1 → 4 文旅；9 外部私企（自成一根）。
 *
 * <p>抽出来是为了让「方向判定」与「草稿校验」两个测试类用**同一棵**树 —— 各自搭一棵
 * 会让「同根 / 跨根」的语义在两个文件里各说一遍，改一处忘一处。
 */
public final class TransferDirectionResolverTestSupport {

    private TransferDirectionResolverTestSupport() {
    }

    public static CompanyTreeService tree() {
        CompanyTreeService service = mock(CompanyTreeService.class);
        when(service.listCompanies()).thenReturn(List.of(
                company(1L, null, "集团"),
                company(2L, 1L, "城投"),
                company(3L, 2L, "城投商运"),
                company(4L, 1L, "文旅"),
                company(9L, null, "外部私企")));
        when(service.ancestorIds(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            List<Long> path = new ArrayList<>();
            Long cursor = id;
            int guard = 0;
            while (cursor != null && guard++ < 10) {
                path.add(0, cursor);
                cursor = parentOf(cursor);
            }
            return path;
        });
        when(service.isActiveCompany(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return List.of(1L, 2L, 3L, 4L, 9L).contains(id);
        });
        return service;
    }

    public static Long parentOf(Long id) {
        return switch (id.intValue()) {
            case 2, 4 -> 1L;
            case 3 -> 2L;
            default -> null;
        };
    }

    public static Company company(Long id, Long parentId, String name) {
        Company c = new Company();
        c.setId(id);
        c.setParentId(parentId);
        c.setName(name);
        c.setStatus(1);
        return c;
    }
}
```

并把 Task 7 的 `TransferDirectionResolverTest` 改成用这个夹具（删掉它自己的 `company` / `parentOf` / mock 装配，`setUp` 里改为 `service = TransferDirectionResolverTestSupport.tree();`）。

- [ ] **Step 2: 跑测试确认它失败**

Run:

```bash
cd backend && mvn -q test -Dtest=OwnershipTransferServiceTest -DfailIfNoTests=false
```

Expected: 编译失败 —— `cannot find symbol: class OwnershipTransferService`。

- [ ] **Step 3: 实现草稿部分**

Create `backend/src/main/java/com/ams/modules/ownership/service/OwnershipTransferService.java`：

```java
package com.ams.modules.ownership.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.service.AssetHandoverBuilder;
import com.ams.modules.asset.service.CertificateService;
import com.ams.modules.org.entity.Company;
import com.ams.modules.ownership.dto.OwnershipTransferAssetView;
import com.ams.modules.ownership.dto.OwnershipTransferInput;
import com.ams.modules.ownership.dto.OwnershipTransferView;
import com.ams.modules.ownership.dto.TransferAssetOption;
import com.ams.modules.ownership.entity.OwnershipTransfer;
import com.ams.modules.ownership.entity.OwnershipTransferAsset;
import com.ams.modules.ownership.mapper.OwnershipTransferAssetMapper;
import com.ams.modules.ownership.mapper.OwnershipTransferMapper;
import com.ams.modules.record.dto.AttachmentRef;
import com.ams.modules.record.service.RecordSheetService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 权属流转（设计 §5）。一张单选多个资产，按权属类型改写
 * {@code property_company_id} / {@code operating_company_id}。
 *
 * <p>状态机只有 {@code draft → completed}：本期不接审批引擎（设计 §3.2），
 * 「生效」= 唯一的落库动作，且是**不可逆**的。
 *
 * <p><b>草稿不写任何预留行</b>：两个人可以同时对同一资产起草，谁先生效谁赢。
 * 这是刻意的 —— 本仓已为「预留永不收口」付过代价（见 {@code OccupationService.withdraw}
 * 的注释与改造清单 P0-5）。跨草稿的冲突改由「生效时按原公司重新校验」自然消解：
 * 第一张生效后，该资产的产权公司已不是第二张单的原公司，第二张生效会失败。
 */
@Service
public class OwnershipTransferService {

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_COMPLETED = "completed";

    private static final String SCOPE_PROPERTY = "property";
    private static final String SCOPE_OPERATING = "operating";
    private static final String SCOPE_BOTH = "both";

    private static final Set<String> DIRECTIONS = Set.of("internal", "external");
    private static final Set<String> SCOPES = Set.of(SCOPE_PROPERTY, SCOPE_OPERATING, SCOPE_BOTH);
    private static final Set<String> MODES = Set.of("allocate", "purchase", "auction");

    /** 资产下拉每页上限：前端每页 50，服务端夹一道防止 `pageSize=99999` 拖垮库。 */
    private static final long MAX_ASSET_OPTIONS_PAGE_SIZE = 200;

    private final OwnershipTransferMapper transferMapper;
    private final OwnershipTransferAssetMapper transferAssetMapper;
    private final AssetMapper assetMapper;
    private final TransferDirectionResolver directionResolver;
    private final CertificateService certificateService;
    private final AssetHandoverBuilder handoverBuilder;
    private final RecordSheetService recordSheetService;
    private final ObjectMapper objectMapper;

    public OwnershipTransferService(
            OwnershipTransferMapper transferMapper,
            OwnershipTransferAssetMapper transferAssetMapper,
            AssetMapper assetMapper,
            TransferDirectionResolver directionResolver,
            CertificateService certificateService,
            AssetHandoverBuilder handoverBuilder,
            RecordSheetService recordSheetService,
            ObjectMapper objectMapper) {
        this.transferMapper = transferMapper;
        this.transferAssetMapper = transferAssetMapper;
        this.assetMapper = assetMapper;
        this.directionResolver = directionResolver;
        this.certificateService = certificateService;
        this.handoverBuilder = handoverBuilder;
        this.recordSheetService = recordSheetService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    public PageResult<OwnershipTransferView> page(long page, long pageSize, String status,
            String direction, Long fromCompanyId, String keyword) {
        Page<OwnershipTransfer> result = transferMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<OwnershipTransfer>()
                        .isNull(OwnershipTransfer::getDeletedAt)
                        .eq(status != null, OwnershipTransfer::getStatus, status)
                        .eq(direction != null, OwnershipTransfer::getDirection, direction)
                        .eq(fromCompanyId != null, OwnershipTransfer::getFromCompanyId, fromCompanyId)
                        .and(keyword != null && !keyword.isBlank(),
                                w -> w.like(OwnershipTransfer::getReason, keyword)
                                        .or()
                                        .like(OwnershipTransfer::getApplicantName, keyword))
                        .orderByDesc(OwnershipTransfer::getId));
        Map<Long, String> companyNames = directionResolver.namesById();
        Map<Long, Integer> assetCounts = assetCountsByTransfer(
                result.getRecords().stream().map(OwnershipTransfer::getId).toList());
        List<OwnershipTransferView> views = new ArrayList<>();
        for (OwnershipTransfer row : result.getRecords()) {
            views.add(toView(row, companyNames, assetCounts.getOrDefault(row.getId(), 0), false));
        }
        return PageResult.of(views, result.getTotal(), page, pageSize);
    }

    public OwnershipTransferView get(Long id) {
        OwnershipTransfer row = require(id);
        Map<Long, String> companyNames = directionResolver.namesById();
        Map<Long, Integer> assetCounts = assetCountsByTransfer(List.of(id));
        return toView(row, companyNames, assetCounts.getOrDefault(id, 0), true);
    }

    /**
     * 资产下拉：按「原公司」联动过滤（设计 §5.1 / D5）。
     *
     * <p><b>为什么不用既有的 {@code GET /assets}</b>：两个原因。其一是它的 {@code companyId}
     * 过滤的是 {@code operating_company_id}，而这里要按**权属类型**选过滤字段；其二是它要求
     * {@code asset.ledger:view}，而权属流转岗未必持有资产台账权限 —— 让他们因为缺台账权限
     * 就选不到资产，功能等于不存在。
     */
    public PageResult<TransferAssetOption> assetOptions(Long companyId, String transferScope,
            String keyword, long page, long pageSize) {
        if (companyId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请先选择原产权公司");
        }
        String scope = normalizeScope(transferScope);
        long size = Math.min(Math.max(pageSize, 1), MAX_ASSET_OPTIONS_PAGE_SIZE);
        // 产权口径（property / both）过滤 property_company_id；经营权口径过滤 operating_company_id
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .isNull(Asset::getDeletedAt)
                .ne(Asset::getLifecycleStatus, "exited");
        if (SCOPE_OPERATING.equals(scope)) {
            wrapper.eq(Asset::getOperatingCompanyId, companyId);
        } else {
            wrapper.eq(Asset::getPropertyCompanyId, companyId);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(Asset::getName, keyword)
                    .or()
                    .like(Asset::getAssetNo, keyword));
        }
        wrapper.orderByAsc(Asset::getId);
        Page<Asset> result = assetMapper.selectPage(new Page<>(page, size), wrapper);
        List<TransferAssetOption> options = result.getRecords().stream().map(a -> {
            TransferAssetOption option = new TransferAssetOption();
            option.setAssetId(a.getId());
            option.setAssetNo(a.getAssetNo());
            option.setName(a.getName());
            option.setProjectName(a.getProjectName());
            option.setZoneName(a.getZoneName());
            option.setFloorNo(a.getFloorNo());
            return option;
        }).toList();
        return PageResult.of(options, result.getTotal(), page, size);
    }

    // ------------------------------------------------------------------
    // 写：草稿
    // ------------------------------------------------------------------

    @Transactional
    public OwnershipTransferView create(OwnershipTransferInput input) {
        OwnershipTransfer entity = new OwnershipTransfer();
        entity.setStatus(STATUS_DRAFT);
        List<Long> assetIds = validateDraft(entity, input);
        transferMapper.insert(entity);
        replaceAssets(entity.getId(), assetIds, Map.of());
        recordSheetService.syncAttachments(
                com.ams.modules.record.AttachmentOwner.OWNERSHIP_TRANSFER,
                entity.getId(),
                input.getAttachments());
        return get(entity.getId());
    }

    @Transactional
    public OwnershipTransferView update(Long id, OwnershipTransferInput input) {
        OwnershipTransfer entity = requireDraft(id);
        List<Long> assetIds = validateDraft(entity, input);
        transferMapper.updateById(entity);
        replaceAssets(entity.getId(), assetIds, existingSnapshot(entity.getId()));
        recordSheetService.syncAttachments(
                com.ams.modules.record.AttachmentOwner.OWNERSHIP_TRANSFER,
                entity.getId(),
                input.getAttachments());
        return get(entity.getId());
    }

    @Transactional
    public void delete(Long id) {
        OwnershipTransfer entity = requireDraft(id);
        // 软删：已完成单不可查到，草稿删掉后附件随宿主一起失效（附件行不清理，与处置单同口径）
        entity.setDeletedAt(java.time.LocalDateTime.now());
        transferMapper.updateById(entity);
    }

    /**
     * 生效（Task 9 实现）。
     *
     * <p>本 task 先留桩，Task 9 会替换掉它 —— 桩的存在只为了让本 task 的测试能编译。
     */
    @Transactional
    public OwnershipTransferView effect(Long id) {
        throw new UnsupportedOperationException("Task 9 实现");
    }

    // ------------------------------------------------------------------
    // 校验：唯一的一份实现，create / update / effect 三处共用（设计 §5.5）
    // ------------------------------------------------------------------

    /**
     * 校验草稿并把请求体写进 {@code entity}，返回去重后的资产 id 列表。
     *
     * <p><b>只有这一份校验</b>：`create`、`update`、`effect` 三处必须调它。仓内已为
     * 「同一语义两处判定漂移」付过代价（`replaceZones` vs `deleteProjectZone` 的
     * `assertZoneRemovable` 收敛过程），本模块从一开始就不留第二份。
     */
    private List<Long> validateDraft(OwnershipTransfer entity, OwnershipTransferInput input) {
        if (input.getFromCompanyId() == null || input.getToCompanyId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "原公司与新公司均必填");
        }
        if (Objects.equals(input.getFromCompanyId(), input.getToCompanyId())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "新公司与原公司不能相同");
        }
        String direction = requireIn(input.getDirection(), DIRECTIONS, "流转方向");
        String scope = normalizeScope(input.getTransferScope());
        String mode = requireIn(input.getTransferMode(), MODES, "流转类型");

        // 方向必须与公司树一致（不存在的公司在这里被挡掉 —— 否则 ancestorIds 会把它当自己的根）
        directionResolver.assertDirectionMatches(direction, input.getFromCompanyId(), input.getToCompanyId());

        assertAmountScale(input.getAmountWan());

        // 申请人：内员时用 sys_user.name 覆盖快照，外部人员要求手填非空（设计 §5.3 规则 5）
        String applicantName = applicantName(input);

        List<Long> assetIds = dedupeAssetIds(input.getAssetIds());
        List<Asset> assets = loadAndValidateAssets(assetIds, scope, input.getFromCompanyId());

        entity.setDirection(direction);
        entity.setTransferScope(scope);
        entity.setFromCompanyId(input.getFromCompanyId());
        entity.setToCompanyId(input.getToCompanyId());
        entity.setTransferMode(mode);
        entity.setApplicantUserId(input.getApplicantUserId());
        entity.setApplicantName(applicantName);
        entity.setApprovalDeadline(input.getApprovalDeadline());
        entity.setAmountWan(input.getAmountWan());
        entity.setReason(input.getReason());
        // 快照留给 effect 用：与资产列表一一对应
        entity.setHandoverJson(null);
        pendingAssets = assets;
        return assetIds;
    }

    /** `validateDraft` 的产物：校验通过的资产实体，供 `replaceAssets` 写快照。 */
    private List<Asset> pendingAssets = List.of();

    private List<Long> dedupeAssetIds(List<Long> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "资产列表至少选择 1 个资产");
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : raw) {
            if (id != null) {
                unique.add(id);
            }
        }
        if (unique.isEmpty()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "资产列表至少选择 1 个资产");
        }
        return new ArrayList<>(unique);
    }

    private List<Asset> loadAndValidateAssets(List<Long> assetIds, String scope, Long fromCompanyId) {
        List<Asset> assets = new ArrayList<>(assetIds.size());
        for (Long assetId : assetIds) {
            Asset asset = assetMapper.selectById(assetId);
            if (asset == null) {
                throw new AppException(ErrorCode.NOT_FOUND, "资产不存在：" + assetId);
            }
            if (asset.getDeletedAt() != null) {
                throw new AppException(ErrorCode.BAD_REQUEST, "资产已删除：" + assetId);
            }
            if ("exited".equals(asset.getLifecycleStatus())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "资产已退出，不可流转：" + assetId);
            }
            // 按权属类型校验归属：property/both 看产权公司，operating 看经营公司
            if (SCOPE_OPERATING.equals(scope)) {
                assertSameCompany(asset.getOperatingCompanyId(), fromCompanyId, assetId, "经营公司");
            } else {
                assertSameCompany(asset.getPropertyCompanyId(), fromCompanyId, assetId, "产权公司");
            }
            certificateService.assertNotMortgaged(assetId);
            assets.add(asset);
        }
        return assets;
    }

    private void assertSameCompany(Long actual, Long expected, Long assetId, String label) {
        if (!Objects.equals(actual, expected)) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "资产 #" + assetId + " 的" + label + "不属于所选原公司");
        }
    }

    private String applicantName(OwnershipTransferInput input) {
        if (input.getApplicantUserId() != null) {
            // 内员：姓名以数据库为准，不用前端传来的字符串（前端可改，姓名是快照）
            return userMapper.selectById(input.getApplicantUserId()) == null
                    ? throwNoSuchUser(input.getApplicantUserId())
                    : currentName(input.getApplicantUserId());
        }
        if (input.getApplicantName() == null || input.getApplicantName().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "变更申请人必填");
        }
        return input.getApplicantName().trim();
    }

    private void assertAmountScale(BigDecimal amountWan) {
        if (amountWan != null && amountWan.scale() > 2) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "金额(万元)最多 2 位小数，当前传入 " + amountWan.toPlainString());
        }
    }

    private String normalizeScope(String raw) {
        String scope = requireIn(raw, SCOPES, "权属类型");
        return scope;
    }

    private String requireIn(String raw, Set<String> allowed, String label) {
        if (raw == null || !allowed.contains(raw)) {
            throw new AppException(ErrorCode.BAD_REQUEST, label + "取值非法：" + raw);
        }
        return raw;
    }

    // ------------------------------------------------------------------
    // 明细与视图
    // ------------------------------------------------------------------

    /** 全量替换明细（草稿阶段）：先删后插，与 `DisposalService.syncForAsset` 同一套做法。 */
    private void replaceAssets(Long transferId, List<Long> assetIds, Map<Long, OwnershipTransferAsset> previous) {
        transferAssetMapper.delete(new LambdaQueryWrapper<OwnershipTransferAsset>()
                .eq(OwnershipTransferAsset::getTransferId, transferId));
        for (Long assetId : assetIds) {
            OwnershipTransferAsset row = new OwnershipTransferAsset();
            row.setTransferId(transferId);
            row.setAssetId(assetId);
            OwnershipTransferAsset old = previous.get(assetId);
            if (old != null) {
                // 编辑草稿时保留已写下的原值快照，不要用当前值覆盖
                row.setFromPropertyCompanyId(old.getFromPropertyCompanyId());
                row.setFromOperatingCompanyId(old.getFromOperatingCompanyId());
            }
            transferAssetMapper.insert(row);
        }
    }

    private Map<Long, OwnershipTransferAsset> existingSnapshot(Long transferId) {
        return transferAssetMapper.selectList(new LambdaQueryWrapper<OwnershipTransferAsset>()
                        .eq(OwnershipTransferAsset::getTransferId, transferId))
                .stream()
                .collect(Collectors.toMap(OwnershipTransferAsset::getAssetId, a -> a, (a, b) -> a, LinkedHashMap::new));
    }

    private Map<Long, Integer> assetCountsByTransfer(List<Long> transferIds) {
        if (transferIds.isEmpty()) {
            return Map.of();
        }
        List<OwnershipTransferAsset> rows = transferAssetMapper.selectList(
                new LambdaQueryWrapper<OwnershipTransferAsset>()
                        .in(OwnershipTransferAsset::getTransferId, transferIds));
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (OwnershipTransferAsset row : rows) {
            counts.merge(row.getTransferId(), 1, Integer::sum);
        }
        return counts;
    }

    private OwnershipTransfer require(Long id) {
        OwnershipTransfer row = transferMapper.selectById(id);
        if (row == null || row.getDeletedAt() != null) {
            throw new AppException(ErrorCode.NOT_FOUND, "权属流转单不存在：" + id);
        }
        return row;
    }

    private OwnershipTransfer requireDraft(Long id) {
        OwnershipTransfer row = require(id);
        if (!STATUS_DRAFT.equals(row.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "只有草稿可以修改或删除");
        }
        return row;
    }

    private OwnershipTransferView toView(OwnershipTransfer row, Map<Long, String> companyNames,
            int assetCount, boolean withAssets) {
        OwnershipTransferView view = new OwnershipTransferView();
        view.setId(row.getId());
        view.setDirection(row.getDirection());
        view.setTransferScope(row.getTransferScope());
        view.setFromCompanyId(row.getFromCompanyId());
        view.setFromCompanyName(companyNames.get(row.getFromCompanyId()));
        view.setToCompanyId(row.getToCompanyId());
        view.setToCompanyName(companyNames.get(row.getToCompanyId()));
        view.setTransferMode(row.getTransferMode());
        view.setApplicantUserId(row.getApplicantUserId());
        view.setApplicantName(row.getApplicantName());
        view.setApprovalDeadline(row.getApprovalDeadline());
        view.setAmountWan(row.getAmountWan());
        view.setReason(row.getReason());
        view.setStatus(row.getStatus());
        view.setEffectedAt(row.getEffectedAt());
        view.setCreatedAt(row.getCreatedAt());
        view.setAssetCount(assetCount);
        view.setAssets(withAssets ? assetViews(row.getId()) : null);
        view.setAttachments(recordSheetService.toAttachmentRefs(
                com.ams.modules.record.AttachmentOwner.OWNERSHIP_TRANSFER, row.getId()));
        return view;
    }

    private List<OwnershipTransferAssetView> assetViews(Long transferId) {
        List<OwnershipTransferAsset> rows = transferAssetMapper.selectList(
                new LambdaQueryWrapper<OwnershipTransferAsset>()
                        .eq(OwnershipTransferAsset::getTransferId, transferId)
                        .orderByAsc(OwnershipTransferAsset::getId));
        List<OwnershipTransferAssetView> views = new ArrayList<>(rows.size());
        for (OwnershipTransferAsset row : rows) {
            Asset asset = assetMapper.selectById(row.getAssetId());
            OwnershipTransferAssetView view = new OwnershipTransferAssetView();
            view.setAssetId(row.getAssetId());
            view.setFromPropertyCompanyId(row.getFromPropertyCompanyId());
            view.setFromOperatingCompanyId(row.getFromOperatingCompanyId());
            if (asset != null) {
                view.setAssetNo(asset.getAssetNo());
                view.setAssetName(asset.getName());
                view.setProjectName(asset.getProjectName());
                view.setZoneName(asset.getZoneName());
                view.setFloorNo(asset.getFloorNo());
            }
            views.add(view);
        }
        return views;
    }

    private static String currentName(Long userId) {
        throw new UnsupportedOperationException("Task 8 实现");
    }

    private static String throwNoSuchUser(Long userId) {
        throw new AppException(ErrorCode.BAD_REQUEST, "申请人不存在：" + userId);
    }
}
```

> **注意两处待补全**，都在 Step 4 用真实依赖替换：
> 1. `currentName(Long)` 需要 `UserMapper`（本类还没注入）；
> 2. `pendingAssets` 这个字段逃逸是**刻意的临时写法**吗？不是 —— 它是 Step 4 要消掉的：把 `validateDraft` 的返回改成一个小 record，同时带回 `assetIds` 与校验通过的资产。Step 4 会给出最终形态。

- [ ] **Step 4: 消掉临时写法，跑通测试**

按上面两处提示改：

1. 构造器注入 `com.ams.modules.system.mapper.UserMapper userMapper`（与 `sys_user` 对应；**先确认类名与包路径**，用 `rg -n "class UserMapper" backend/src/main` 查）。把 `currentName` 换成：

```java
    private String applicantName(OwnershipTransferInput input) {
        if (input.getApplicantUserId() != null) {
            User user = userMapper.selectById(input.getApplicantUserId());
            if (user == null) {
                throw new AppException(ErrorCode.BAD_REQUEST, "申请人不存在：" + input.getApplicantUserId());
            }
            return user.getName();
        }
        if (input.getApplicantName() == null || input.getApplicantName().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "变更申请人必填");
        }
        return input.getApplicantName().trim();
    }
```

删掉 `currentName` 与 `throwNoSuchUser` 两个方法。

2. 把「`validateDraft` 顺带吐资产实体」的写法换成显式 record（去掉 `pendingAssets` 可变字段 —— 它会让 `create` 与 `update` 之间产生隐藏耦合）：

```java
    /** `validateDraft` 的产物：去重后的资产 id + 校验通过的资产实体。 */
    private record DraftValidation(List<Long> assetIds, List<Asset> assets) {
    }

    private DraftValidation validateDraft(OwnershipTransfer entity, OwnershipTransferInput input) {
        // ...（前半段不变，到 loadAndValidateAssets 为止）
        return new DraftValidation(assetIds, assets);
    }
```

`create` / `update` 相应改为：

```java
        DraftValidation validated = validateDraft(entity, input);
        transferMapper.insert(entity);
        replaceAssets(entity.getId(), validated.assetIds(), validated.assets(), Map.of());
```

`replaceAssets` 增加 `List<Asset> assets` 形参，并在写快照时按本次校验到的资产填（新建 = 当前值即原值）：

```java
    private void replaceAssets(Long transferId, List<Long> assetIds, List<Asset> assets,
            Map<Long, OwnershipTransferAsset> previous) {
        Map<Long, Asset> byId = assets.stream()
                .collect(Collectors.toMap(Asset::getId, a -> a, (a, b) -> a));
        transferAssetMapper.delete(new LambdaQueryWrapper<OwnershipTransferAsset>()
                .eq(OwnershipTransferAsset::getTransferId, transferId));
        for (Long assetId : assetIds) {
            OwnershipTransferAsset row = new OwnershipTransferAsset();
            row.setTransferId(transferId);
            row.setAssetId(assetId);
            OwnershipTransferAsset old = previous.get(assetId);
            if (old != null) {
                // 编辑草稿时保留已写下的原值快照，不要用当前值覆盖
                row.setFromPropertyCompanyId(old.getFromPropertyCompanyId());
                row.setFromOperatingCompanyId(old.getFromOperatingCompanyId());
            } else {
                Asset asset = byId.get(assetId);
                row.setFromPropertyCompanyId(asset == null ? null : asset.getPropertyCompanyId());
                row.setFromOperatingCompanyId(asset == null ? null : asset.getOperatingCompanyId());
            }
            transferAssetMapper.insert(row);
        }
    }
```

删掉 `entity.setHandoverJson(null);` 那一行（`handoverJson` 只在 `effect` 里写）。

3. 跑：

```bash
cd backend && mvn -q test -Dtest=OwnershipTransferServiceTest -DfailIfNoTests=false
```

Expected: 14 个用例全绿。

- [ ] **Step 5: 跑全量后端测试确认没有连带破坏**

Run:

```bash
cd backend && mvn -q test
```

Expected: BUILD SUCCESS（`MigrationChainPostgresTest` 本机无 Docker 时为 skipped）。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/ams/modules/ownership/service/OwnershipTransferService.java \
        backend/src/test/java/com/ams/modules/ownership/
git commit -m "feat(ownership): 权属流转草稿 CRUD 与统一校验"
```

---

## Task 9: `effect`（生效落库）

**Files:**

- Modify: `backend/src/main/java/com/ams/modules/ownership/service/OwnershipTransferService.java`（替换 `effect` 桩）
- Modify: `backend/src/test/java/com/ams/modules/ownership/service/OwnershipTransferServiceTest.java`（加生效用例）

**Interfaces:**

- Consumes: `AssetHandoverBuilder.build(Asset, Long, Long, Map)`（Task 4）、`OwnershipTransferredEvent`（Task 5）、`DomainEventPublisher.publishAfterCommit(DomainEvent)`（既有）
- Produces: `effect` 的最终行为（Task 10 的控制器直接转调；Task 11 的档案靠 `ownership_transfer_asset` 反查，不依赖本 task 的额外产出）

**生效时对资产的四类写入**（设计 §5.3）：

| `transferScope` | 写入 |
|---|---|
| `property` | `property_company_id = to_company_id` |
| `operating` | `operating_company_id = to_company_id` |
| `both` | 两个都写 |
| `direction = external` | 额外 `ownership_status = 'transferred_out'`、`lifecycle_status = 'exited'` |

**租控状态一律不动**（设计 §5.4）：`LeaseControlStatus.canTransition` 只允许 `DISPOSING → EXITED`，直接置「已退出」会被状态机以 `CONFLICT` 拒绝；且 `ADR-0019` 已把「已退出」归还给生命周期字段。

---

- [ ] **Step 1: 写失败的测试**

Modify `backend/src/test/java/com/ams/modules/ownership/service/OwnershipTransferServiceTest.java`，追加生效用例。需要先把 `newService()` 里的 `DomainEventPublisher` 与 `AssetHandoverBuilder` 换成可断言的 mock（在 `setUp` 里建字段）：

```java
    private com.ams.platform.event.DomainEventPublisher eventPublisher;
    private AssetHandoverBuilder handoverBuilder;
```

`setUp` 里追加：

```java
        eventPublisher = mock(com.ams.platform.event.DomainEventPublisher.class);
        handoverBuilder = mock(AssetHandoverBuilder.class);
```

`newService()` 改为传 `handoverBuilder` 与 `eventPublisher`。

追加的用例：

```java
    private static OwnershipTransfer draft(long id, String scope, String direction) {
        OwnershipTransfer t = new OwnershipTransfer();
        t.setId(id);
        t.setStatus("draft");
        t.setDirection(direction);
        t.setTransferScope(scope);
        t.setFromCompanyId(FROM);
        t.setToCompanyId(TO);
        t.setTransferMode("allocate");
        t.setApplicantName("张三");
        return t;
    }

    private void givenDraftAssets(long transferId, long... assetIds) {
        when(transferMapper.selectById(transferId)).thenReturn(draft(transferId, "property", "internal"));
        when(assetMapper.selectList(any())).thenReturn(java.util.Arrays.stream(assetIds)
                .mapToObj(id -> {
                    OwnershipTransferAsset row = new OwnershipTransferAsset();
                    row.setId(id);
                    row.setTransferId(transferId);
                    row.setAssetId(id);
                    return row;
                })
                .toList());
    }

    @Test
    @DisplayName("生效（产权）：只改 property_company_id，经营公司与权属状态都不动")
    void effectWritesPropertyCompanyOnly() {
        givenDraftAssets(7L, 11L);
        Asset asset = asset(11L, FROM, 999L);
        when(assetEntityMapper.selectById(11L)).thenReturn(asset);
        when(assetEntityMapper.updateById(any())).thenReturn(1);

        newService().effect(7L);

        assertThat(asset.getPropertyCompanyId()).isEqualTo(TO);
        assertThat(asset.getOperatingCompanyId()).as("产权口径不应动经营公司").isEqualTo(999L);
        assertThat(asset.getOwnershipStatus()).as("内部流转不打「已对外转出」").isEqualTo("in_group");
        assertThat(asset.getLeaseControlStatus()).as("租控状态一律不动").isNull();
    }

    @Test
    @DisplayName("生效（经营权且产权）：两个公司都改成新公司")
    void effectWritesBothCompanies() {
        when(transferMapper.selectById(7L)).thenReturn(draft(7L, "both", "internal"));
        OwnershipTransferAsset row = new OwnershipTransferAsset();
        row.setTransferId(7L);
        row.setAssetId(11L);
        when(assetMapper.selectList(any())).thenReturn(List.of(row));
        Asset asset = asset(11L, FROM, FROM);
        when(assetEntityMapper.selectById(11L)).thenReturn(asset);
        when(assetEntityMapper.updateById(any())).thenReturn(1);

        newService().effect(7L);

        assertThat(asset.getPropertyCompanyId()).isEqualTo(TO);
        assertThat(asset.getOperatingCompanyId()).isEqualTo(TO);
    }

    @Test
    @DisplayName("生效（外部）：打 transferred_out + lifecycle_status=exited，且不动租控")
    void effectMarksExternalTransfer() {
        when(transferMapper.selectById(7L)).thenReturn(draft(7L, "property", "external"));
        OwnershipTransferAsset row = new OwnershipTransferAsset();
        row.setTransferId(7L);
        row.setAssetId(11L);
        when(assetMapper.selectList(any())).thenReturn(List.of(row));
        Asset asset = asset(11L, FROM, 999L);
        when(assetEntityMapper.selectById(11L)).thenReturn(asset);
        when(assetEntityMapper.updateById(any())).thenReturn(1);

        newService().effect(7L);

        assertThat(asset.getOwnershipStatus()).isEqualTo("transferred_out");
        assertThat(asset.getLifecycleStatus()).isEqualTo("exited");
        assertThat(asset.getLeaseControlStatus())
                .as("ADR-0019：已退出属于生命周期，不是占用状态；租控状态机也只允许 DISPOSING→EXITED")
                .isNull();
    }

    @Test
    @DisplayName("生效幂等：completed 的单子直接返回，不再改一次资产")
    void effectIsIdempotent() {
        OwnershipTransfer done = draft(7L, "property", "internal");
        done.setStatus("completed");
        when(transferMapper.selectById(7L)).thenReturn(done);

        newService().effect(7L);

        verify(assetEntityMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("生效重跑校验：草稿期间资产产权公司被改过 -> 400 且不写任何资产")
    void effectRevalidatesBeforeWriting() {
        givenDraftAssets(7L, 11L);
        // 草稿提交后又有人改了这家公司 —— 生效必须重新校验
        when(assetEntityMapper.selectById(11L)).thenReturn(asset(11L, 888L, 999L));

        assertThatThrownBy(() -> newService().effect(7L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不属于所选原公司");
        verify(assetEntityMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("乐观锁冲突（updateById 返回 0）-> CONFLICT，整单回滚")
    void effectFailsOnOptimisticLock() {
        givenDraftAssets(7L, 11L);
        when(assetEntityMapper.selectById(11L)).thenReturn(asset(11L, FROM, 999L));
        when(assetEntityMapper.updateById(any())).thenReturn(0);

        assertThatThrownBy(() -> newService().effect(7L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("并发");
    }

    @Test
    @DisplayName("生效发布逐资产事件，且交接清单写进主单")
    void effectPublishesEventsAndStoresHandover() {
        givenDraftAssets(7L, 11L, 12L);
        when(assetEntityMapper.selectById(11L)).thenReturn(asset(11L, FROM, 999L));
        when(assetEntityMapper.selectById(12L)).thenReturn(asset(12L, FROM, 999L));
        when(assetEntityMapper.updateById(any())).thenReturn(1);
        when(handoverBuilder.build(any(), any(), any(), any())).thenReturn(Map.of("assetId", 11L));

        newService().effect(7L);

        verify(eventPublisher, org.mockito.Mockito.times(2))
                .publishAfterCommit(any(com.ams.platform.event.OwnershipTransferredEvent.class));
        verify(handoverBuilder, org.mockito.Mockito.times(2)).build(any(), any(), any(), any());
    }
```

- [ ] **Step 2: 跑测试确认它失败**

Run:

```bash
cd backend && mvn -q test -Dtest=OwnershipTransferServiceTest -DfailIfNoTests=false
```

Expected: 新的 7 个用例失败，报 `UnsupportedOperationException: Task 9 实现`。

- [ ] **Step 3: 实现 `effect`**

Modify `OwnershipTransferService`：

1. 加字段与构造参数 `private final DomainEventPublisher eventPublisher;`（`com.ams.platform.event.DomainEventPublisher`），**放在构造参数列表的最后**，对应 Task 8 Step 4 之后的 10 个参数：
   `(transferMapper, transferAssetMapper, assetMapper, directionResolver, certificateService, handoverBuilder, recordSheetService, objectMapper, userMapper, eventPublisher)`；
2. 替换 `effect` 桩；3. 补三个私有方法。

```java
    /**
     * 生效：把每个资产的对应公司字段改成新公司（设计 §5.3）。
     *
     * <p><b>重跑全部校验</b>：草稿可能已经躺了很久 —— 公司树、抵押状态、资产的当前公司都可能
     * 变过。这一步同时是「跨草稿防重」机制：第一张单生效后，该资产的公司已不是第二张单的
     * 原公司，第二张生效会在这里失败。
     *
     * <p><b>为什么逐资产用乐观锁而不是一条 UPDATE</b>：`asset.version` 是既有的并发保护；
     * 返回 0 说明有人在这期间改了资产，此时**整单回滚**比「改一半」安全 ——
     * 部分资产换了公司而单据没生效，是最难排查的状态。
     */
    @Transactional
    public OwnershipTransferView effect(Long id) {
        OwnershipTransfer entity = require(id);
        if (STATUS_COMPLETED.equals(entity.getStatus())) {
            return get(id); // 幂等：已完成不重复改资产
        }
        if (!STATUS_DRAFT.equals(entity.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "只有草稿可以生效，当前状态：" + entity.getStatus());
        }

        OwnershipTransferInput input = toInput(entity);
        DraftValidation validated = validateDraft(entity, input);
        Map<Long, OwnershipTransferAsset> previous = existingSnapshot(id);
        boolean external = TransferDirectionResolver.EXTERNAL.equals(entity.getDirection());

        for (Asset asset : validated.assets()) {
            if (external) {
                // 不写租控状态：ADR-0019 已把「已退出」归还给生命周期字段，
                // 且 LeaseControlStatus.canTransition 只允许 DISPOSING→EXITED（设计 §5.4）
                asset.setOwnershipStatus("transferred_out");
                asset.setLifecycleStatus("exited");
            }
            if (SCOPE_OPERATING.equals(entity.getTransferScope())) {
                asset.setOperatingCompanyId(entity.getToCompanyId());
            } else if (SCOPE_PROPERTY.equals(entity.getTransferScope())) {
                asset.setPropertyCompanyId(entity.getToCompanyId());
            } else {
                asset.setPropertyCompanyId(entity.getToCompanyId());
                asset.setOperatingCompanyId(entity.getToCompanyId());
            }
            if (assetMapper.updateById(asset) == 0) {
                throw new AppException(ErrorCode.CONFLICT,
                        "资产已被并发修改，请刷新重试：" + asset.getId());
            }
        }

        // 明细写原值快照 + 交接清单：一件资产一份（欠费/保证金/预收/在租合同各不相同）
        replaceAssets(id, validated.assetIds(), validated.assets(), previous);
        writeHandover(id, entity, validated.assets());

        entity.setStatus(STATUS_COMPLETED);
        entity.setEffectedAt(java.time.LocalDateTime.now());
        transferMapper.updateById(entity);

        // 逐资产发事件（不是一张单一条）：通知与下游投影都按资产粒度消费
        for (Asset asset : validated.assets()) {
            eventPublisher.publishAfterCommit(new OwnershipTransferredEvent(
                    id, asset.getId(), entity.getFromCompanyId(), entity.getToCompanyId(),
                    entity.getDirection()));
        }
        return get(id);
    }
```

同时补三个私有方法：

```java
    /** 把已落库的主单还原成 `validateDraft` 能吃的入参（生效要重跑同一份校验）。 */
    private OwnershipTransferInput toInput(OwnershipTransfer entity) {
        OwnershipTransferInput input = new OwnershipTransferInput();
        input.setDirection(entity.getDirection());
        input.setTransferScope(entity.getTransferScope());
        input.setFromCompanyId(entity.getFromCompanyId());
        input.setToCompanyId(entity.getToCompanyId());
        input.setTransferMode(entity.getTransferMode());
        input.setApplicantUserId(entity.getApplicantUserId());
        input.setApplicantName(entity.getApplicantName());
        input.setApprovalDeadline(entity.getApprovalDeadline());
        input.setAmountWan(entity.getAmountWan());
        input.setReason(entity.getReason());
        input.setAssetIds(transferAssetMapper.selectList(
                        new LambdaQueryWrapper<OwnershipTransferAsset>()
                                .eq(OwnershipTransferAsset::getTransferId, entity.getId())
                                .orderByAsc(OwnershipTransferAsset::getId))
                .stream()
                .map(OwnershipTransferAsset::getAssetId)
                .toList());
        return input;
    }

    /**
     * 交接清单快照：**每张单一个**（不是每资产一个），以「assetId → 快照」的 Map 存 JSON。
     *
     * <p>一张单可以有几十个资产，逐个存一列会需要第二张表；而快照只用于双方对账查看，
     * 一次读全更实用。键是字符串（JSON 对象的键必须是字符串），前端读时注意。
     */
    private void writeHandover(Long id, OwnershipTransfer entity, List<Asset> assets) {
        Map<String, Object> all = new LinkedHashMap<>();
        for (Asset asset : assets) {
            all.put(String.valueOf(asset.getId()),
                    handoverBuilder.build(asset, entity.getFromCompanyId(), entity.getToCompanyId(),
                            Map.of("transferScope", entity.getTransferScope(),
                                    "transferMode", entity.getTransferMode(),
                                    "direction", entity.getDirection())));
        }
        try {
            entity.setHandoverJson(objectMapper.writeValueAsString(all));
        } catch (Exception ex) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "生成交接清单失败");
        }
    }
```

`replaceAssets` 里要写「生效时的原值快照」。生效路径下 `previous` 已有旧快照（草稿创建时写的当前值），但那些值是**草稿创建时**的，可能与生效时不同 —— 生效必须以**生效那一刻**的值写快照。因此在 `effect` 里改为在**改写资产之前**捕获快照，方法如下（替换 `effect` 中 `replaceAssets` 那一行）：

```java
        snapshotBeforeWrite(id, validated.assets(), previous);
```

并新增：

```java
    /**
     * 生效前把「改之前」的公司值写进明细 —— 快照必须是**生效那一刻**的值，
     * 而不是草稿创建时的值（草稿可能躺了几个月，中间公司已经变过）。
     */
    private void snapshotBeforeWrite(Long transferId, List<Asset> assets,
            Map<Long, OwnershipTransferAsset> previous) {
        for (Asset asset : assets) {
            OwnershipTransferAsset row = previous.get(asset.getId());
            if (row == null) {
                row = new OwnershipTransferAsset();
                row.setTransferId(transferId);
                row.setAssetId(asset.getId());
            }
            row.setFromPropertyCompanyId(asset.getPropertyCompanyId());
            row.setFromOperatingCompanyId(asset.getOperatingCompanyId());
            if (row.getId() == null) {
                transferAssetMapper.insert(row);
            } else {
                transferAssetMapper.updateById(row);
            }
        }
    }
```

> 注意执行顺序：`snapshotBeforeWrite` 必须在改写资产的循环**之前**调用，否则快照会记成新值。Step 3 的 `effect` 代码块里这一行的位置要相应上移到 `for (Asset asset : validated.assets())` 之前。

- [ ] **Step 4: 跑测试确认通过**

Run:

```bash
cd backend && mvn -q test -Dtest=OwnershipTransferServiceTest -DfailIfNoTests=false
```

Expected: 21 个用例全绿。

- [ ] **Step 5: 跑全量后端测试**

Run:

```bash
cd backend && mvn -q test
```

Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/ams/modules/ownership/service/OwnershipTransferService.java \
        backend/src/test/java/com/ams/modules/ownership/service/OwnershipTransferServiceTest.java
git commit -m "feat(ownership): 生效落库（按权属类型改公司字段 + 外部流转标记 + 交接清单 + 事件）"
```

---

## Task 10: 控制器 + 权限测试 + `RbacFixtures`

**Files:**

- Create: `backend/src/main/java/com/ams/modules/ownership/controller/OwnershipTransferController.java`
- Create: `backend/src/test/java/com/ams/modules/ownership/OwnershipTransferPermissionTest.java`
- Modify: `backend/src/test/java/com/ams/support/RbacFixtures.java`

**Interfaces:**

- Consumes: `OwnershipTransferService` 的 6 个公开方法（Task 8 / 9）
- Produces: 7 个 HTTP 端点（前端 Task 12 的 `ownershipTransfer.ts` 按此封装）

---

- [ ] **Step 1: 写失败的权限测试**

Create `backend/src/test/java/com/ams/modules/ownership/OwnershipTransferPermissionTest.java`。**逐字照搬 `DisposalPermissionTest` 的结构**（mock 服务层 + 真实拦释器 + 真实 `RbacService`），只换控制器与断言：

```java
package com.ams.modules.ownership;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ams.common.exception.GlobalExceptionHandler;
import com.ams.modules.ownership.controller.OwnershipTransferController;
import com.ams.modules.ownership.dto.OwnershipTransferView;
import com.ams.modules.ownership.service.OwnershipTransferService;
import com.ams.platform.observability.service.AppLogRecorder;
import com.ams.platform.security.LoginUser;
import com.ams.platform.security.PermissionInterceptor;
import com.ams.platform.security.RbacService;
import com.ams.support.RbacFixtures;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 权属流转的权限闭环（设计 §8）。
 *
 * <p>关键的三条：
 * <ol>
 *   <li><b>生效不看写权限的「轻量档」</b>：只有 {@code :create} 的账号可以建草稿但**不能生效** ——
 *       「能起草」与「能改产权」必须是两个权限（生效是不可逆的跨法人变更）；</li>
 *   <li><b>只有 {@code :update} 不能新增</b>：起草权与改稿权也不该互相顶替；</li>
 *   <li>每个 403 用例都断言<strong>服务未被调用</strong> —— 403 若发生在副作用之后，
 *       拦截就没有意义。</li>
 * </ol>
 */
class OwnershipTransferPermissionTest {

    private static final long TRANSFER_ID = 88L;

    private RbacService rbacService;
    private OwnershipTransferService service;
    private OwnershipTransferController controller;

    @BeforeEach
    void setUp() {
        rbacService = RbacFixtures.standard().newService(1L);
        service = mock(OwnershipTransferService.class);
        when(service.get(any())).thenReturn(new OwnershipTransferView());
        when(service.create(any())).thenReturn(new OwnershipTransferView());
        when(service.update(any(), any())).thenReturn(new OwnershipTransferView());
        when(service.effect(any())).thenReturn(new OwnershipTransferView());
        controller = new OwnershipTransferController(service);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("起草：只有 :update 必须被拒 —— 改稿权不等于起草权")
    void createNeedsCreatePermission() throws Exception {
        login(Set.of("deed.ownershipTransfer:view", "deed.ownershipTransfer:update"));

        mvc().perform(post("/api/v1/ownership-transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCompanyId\":2,\"toCompanyId\":3}"))
                .andExpect(status().isForbidden());

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("起草：授予 :create -> 放行")
    void createWithPermissionSucceeds() throws Exception {
        login(Set.of("deed.ownershipTransfer:create"));

        mvc().perform(post("/api/v1/ownership-transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCompanyId\":2,\"toCompanyId\":3}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("生效：只有 :create（能起草）必须被拒 —— 生效是不可逆的跨法人变更")
    void effectNeedsUpdateNotCreate() throws Exception {
        login(Set.of("deed.ownershipTransfer:create"));

        mvc().perform(post("/api/v1/ownership-transfers/{id}/effect", TRANSFER_ID))
                .andExpect(status().isForbidden());

        verify(service, never()).effect(any());
    }

    @Test
    @DisplayName("生效：授予 :update -> 放行")
    void effectWithUpdatePermissionSucceeds() throws Exception {
        login(Set.of("deed.ownershipTransfer:update"));

        mvc().perform(post("/api/v1/ownership-transfers/{id}/effect", TRANSFER_ID))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("删除：需 :delete，只有 :update 被拒")
    void deleteNeedsDeletePermission() throws Exception {
        login(Set.of("deed.ownershipTransfer:update"));

        mvc().perform(delete("/api/v1/ownership-transfers/{id}", TRANSFER_ID))
                .andExpect(status().isForbidden());

        verify(service, never()).delete(any());
    }

    @Test
    @DisplayName("列表与资产下拉：需 :view")
    void readsNeedViewPermission() throws Exception {
        login(Set.of());

        mvc().perform(get("/api/v1/ownership-transfers")).andExpect(status().isForbidden());
        mvc().perform(get("/api/v1/ownership-transfers/asset-options")
                        .param("companyId", "2")
                        .param("transferScope", "property"))
                .andExpect(status().isForbidden());

        login(Set.of("deed.ownershipTransfer:view"));
        mvc().perform(get("/api/v1/ownership-transfers")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("编辑草稿：需 :update")
    void updateNeedsUpdatePermission() throws Exception {
        login(Set.of("deed.ownershipTransfer:view"));

        mvc().perform(put("/api/v1/ownership-transfers/{id}", TRANSFER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        verify(service, never()).update(any(), any());
    }

    private void login(Set<String> permissions) {
        LoginUser user = LoginUser.builder()
                .userId(9102L)
                .username("ownership-probe")
                .name("权属流转权限探针")
                .companyId(2L)
                .homeCompanyId(2L)
                .departmentId(1L)
                .clientType("admin")
                .roles(Set.of("asset_mgr"))
                .permissions(permissions)
                .dataScope("all")
                .companyScoped(false)
                .excludedCompanyIds(Set.of())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(rbacService, false))
                .setControllerAdvice(new GlobalExceptionHandler(mock(AppLogRecorder.class)))
                .build();
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run:

```bash
cd backend && mvn -q test -Dtest=OwnershipTransferPermissionTest -DfailIfNoTests=false
```

Expected: 编译失败 —— `cannot find symbol: class OwnershipTransferController`。

- [ ] **Step 3: 写控制器**

Create `backend/src/main/java/com/ams/modules/ownership/controller/OwnershipTransferController.java`：

```java
package com.ams.modules.ownership.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.ownership.dto.OwnershipTransferInput;
import com.ams.modules.ownership.dto.OwnershipTransferView;
import com.ams.modules.ownership.dto.TransferAssetOption;
import com.ams.modules.ownership.service.OwnershipTransferService;
import com.ams.platform.security.Audited;
import com.ams.platform.security.RequiresPerm;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权属流转接口（设计 §5.1）。
 *
 * <p>权限码固定 4 个：{@code deed.ownershipTransfer:view|create|update|delete}。
 * 「生效」用 {@code :update} 而不是 {@code :approve}：本期没有审批环节，而仓内
 * {@code :approve} 特指「审批别人的单」（处置/占用/自用）；仓内推进状态机一律用
 * {@code :update}（{@code operation.disposal} 的 execute / complete 即是）。
 */
@RestController
@RequestMapping("/api/v1/ownership-transfers")
public class OwnershipTransferController {

    private final OwnershipTransferService service;

    public OwnershipTransferController(OwnershipTransferService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPerm("deed.ownershipTransfer:view")
    public ApiResponse<PageResult<OwnershipTransferView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) Long fromCompanyId,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(
                service.page(page, pageSize, status, direction, fromCompanyId, keyword),
                TraceIdUtil.get());
    }

    /**
     * 资产下拉（按原公司 + 权属类型联动过滤）。
     *
     * <p><b>必须声明在 {@code /{id}} 之前</b>：否则「asset-options」会被 {@code @PathVariable}
     * 抢匹配，然后因无法转成 {@code Long} 直接 500。
     */
    @GetMapping("/asset-options")
    @RequiresPerm("deed.ownershipTransfer:view")
    public ApiResponse<PageResult<TransferAssetOption>> assetOptions(
            @RequestParam Long companyId,
            @RequestParam(required = false) String transferScope,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long pageSize) {
        return ApiResponse.ok(
                service.assetOptions(companyId, transferScope, keyword, page, pageSize),
                TraceIdUtil.get());
    }

    @GetMapping("/{id}")
    @RequiresPerm("deed.ownershipTransfer:view")
    public ApiResponse<OwnershipTransferView> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id), TraceIdUtil.get());
    }

    @PostMapping
    @RequiresPerm("deed.ownershipTransfer:create")
    @Audited(module = "ownership_transfer", action = "create")
    public ApiResponse<OwnershipTransferView> create(@RequestBody OwnershipTransferInput input) {
        return ApiResponse.ok(service.create(input), TraceIdUtil.get());
    }

    @PutMapping("/{id}")
    @RequiresPerm("deed.ownershipTransfer:update")
    @Audited(module = "ownership_transfer", action = "update")
    public ApiResponse<OwnershipTransferView> update(
            @PathVariable Long id, @RequestBody OwnershipTransferInput input) {
        return ApiResponse.ok(service.update(id, input), TraceIdUtil.get());
    }

    @DeleteMapping("/{id}")
    @RequiresPerm("deed.ownershipTransfer:delete")
    @Audited(module = "ownership_transfer", action = "delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    /** 生效：把每个资产的对应公司字段改成新公司。**不可逆**，前端需二次确认。 */
    @PostMapping("/{id}/effect")
    @RequiresPerm("deed.ownershipTransfer:update")
    @Audited(module = "ownership_transfer", action = "effect")
    public ApiResponse<OwnershipTransferView> effect(@PathVariable Long id) {
        return ApiResponse.ok(service.effect(id), TraceIdUtil.get());
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run:

```bash
cd backend && mvn -q test -Dtest=OwnershipTransferPermissionTest -DfailIfNoTests=false
```

Expected: 7 个用例全绿。

- [ ] **Step 5: 补 `RbacFixtures`**

Modify `backend/src/test/java/com/ams/support/RbacFixtures.java`：

1. 在 `f.grant(ROLE_ASSET_MGR, ...)` 的权限码列表末尾追加：

```java
                "operation.disposal:create", "operation.disposal:update", "operation.disposal:approve",
                "deed.ownershipTransfer:view", "deed.ownershipTransfer:create",
                "deed.ownershipTransfer:update", "deed.ownershipTransfer:delete");
```

2. 在 `menu("operation.disposal", "资产处置", "menu");` 之后追加：

```java
        menu("deed.ownershipTransfer", "权属流转", "menu");
```

- [ ] **Step 6: 跑权限夹具守卫 + 全量后端测试**

Run:

```bash
cd frontend && pnpm check:perm
cd ../backend && mvn -q test
```

Expected:
- `check:perm` 打印的后端 `@RequiresPerm` 个数从 63 → 67（4 个新码），且**失败项为 0**。若这一步报「前端声明的 X 在后端不存在」，说明前端 Task 12 还没做，此时 `declared` 集合里还没有新码，不会报 —— 前端改动在 Task 12。
- 后端全量测试 BUILD SUCCESS。

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/ams/modules/ownership/controller/OwnershipTransferController.java \
        backend/src/test/java/com/ams/modules/ownership/OwnershipTransferPermissionTest.java \
        backend/src/test/java/com/ams/support/RbacFixtures.java
git commit -m "feat(ownership): 权属流转 7 个端点 + 权限闭环"
```

---

## Task 11: 资产档案联动

**Files:**

- Modify: `backend/src/main/java/com/ams/modules/asset/service/AssetDossierService.java`
- Modify: `backend/src/main/java/com/ams/modules/asset/dto/AssetDossierView.java`（若 `AssetDossier` 段落在别的类，按实际类名改）
- Modify: `backend/src/test/java/com/ams/modules/ownership/service/OwnershipTransferServiceTest.java`（加一条「档案能反查到本单」的断言，或新建独立用例）

**Interfaces:**

- Consumes: `OwnershipTransferAssetMapper`（Task 6）、`OwnershipTransferMapper`、`OwnershipTransfer` / `OwnershipTransferAsset` 实体
- Produces: 档案新增 `ownershipTransfers` 字段；时间线新增一类 `ownership_transfer`，标题 `权属流转 #<id>`

---

- [ ] **Step 1: 先确认档案段的实际形态**

Run:

```bash
cd /Users/lixun/Desktop/projects/asset-management-system && rg -n "private List<AssetTransfer> transfers|setTransfers|class AssetDossier" backend/src/main/java/com/ams/modules/asset/
```

把实际字段名与所在类记下来 —— 下一步按它照做（不同段落可能落在 `AssetDossier` 实体或 `AssetDossierView` 上）。

- [ ] **Step 2: 加档案段**

按 Step 1 查到的位置，在 `transfers` 段旁边加一段。以 `AssetDossierService` 的现有写法为准（该类的段落形如 `dossier.setXxx(mapper.selectList(...))`）：

```java
    /**
     * 资产被哪些权属流转单改过（设计 §5.7）。
     *
     * <p>查的是 {@code ownership_transfer_asset}（`asset_id` 上有索引），join 主单取状态 ——
     * 走「按资产反查明细」而不是「扫主单」，因为一次流转可以挂几十个资产。
     *
     * <p>软删的单据不显示：草稿删掉后不该在档案里留下痕迹。
     */
    private List<OwnershipTransfer> ownershipTransfers(Long assetId) {
        List<Long> transferIds = transferAssetMapper.selectList(
                        new LambdaQueryWrapper<OwnershipTransferAsset>()
                                .eq(OwnershipTransferAsset::getAssetId, assetId))
                .stream()
                .map(OwnershipTransferAsset::getTransferId)
                .toList();
        if (transferIds.isEmpty()) {
            return List.of();
        }
        return transferMapper.selectList(new LambdaQueryWrapper<OwnershipTransfer>()
                .in(OwnershipTransfer::getId, transferIds)
                .isNull(OwnershipTransfer::getDeletedAt)
                .orderByDesc(OwnershipTransfer::getId));
    }
```

并在装配段落里调用它、在档案视图的字段上接住（字段命名与 `transfers` 保持一致的前缀风格）。

- [ ] **Step 3: 加时间线类型**

在 `AssetDossierService` 的时间线拼装里，紧跟既有 `transfer` 那一段之后加：

```java
        for (OwnershipTransfer t : dossier.getOwnershipTransfers()) {
            items.add(item("ownership_transfer", "权属流转 #" + t.getId(),
                    t.getStatus(), t.getReason(), t.getId(), t.getCreatedAt()));
        }
```

> **不要**把它并进既有的 `transfer` 分支：调拨与权属流转的业务口径不同（D11），
> 合成一类会让档案里无法区分「调拨过」和「产权转出过」。

- [ ] **Step 4: 跑测试**

Run:

```bash
cd backend && mvn -q test -Dtest='com.ams.modules.asset.*Test,com.ams.modules.ownership.*Test' -DfailIfNoTests=false
```

Expected: PASS。若 `AssetDossierService` 有构造器注入，按编译错误补 `OwnershipTransferMapper` / `OwnershipTransferAssetMapper` 两个依赖；若有既有的 `AssetDossierServiceTest` 构造了它，同步补 mock。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ams/modules/asset/service/AssetDossierService.java \
        backend/src/main/java/com/ams/modules/asset/dto/
git commit -m "feat(asset): 一物一档时间线接入权属流转"
```

---

<!-- ==================================================================== -->
<!--                           前端部分                                    -->
<!-- ==================================================================== -->

## Task 12: 前端 lib 层与路由/菜单注册

**Files:**

- Create: `frontend/admin-web/src/lib/ownershipTransfer.ts`
- Modify: `frontend/admin-web/src/lib/labels.ts`（末尾追加 4 张表）
- Modify: `frontend/admin-web/src/lib/routeRegistry.ts`（`STANDALONE_ROUTES` 加 1 条）
- Modify: `frontend/admin-web/src/lib/pathToCode.ts`（镜像个加 1 条）
- Modify: `frontend/admin-web/src/pages/modules.tsx`（静态 `MENU` 兜底加 1 条）
- Modify: `frontend/admin-web/src/lib/menuIcons.tsx`（`PATH_ICONS` 加 1 条）
- Modify: `frontend/admin-web/src/App.tsx`（3 条 `<Route>`）

**Interfaces:**

- Consumes: 后端 7 个端点（Task 10）
- Produces:
  - `OwnershipTransferView` / `OwnershipTransferAssetView` / `TransferAssetOption` / `OwnershipTransferInput` 类型
  - `ownershipTransferApi.{list, detail, assetOptions, create, update, remove, effect}`
  - `assetOptionLabel(o: TransferAssetOption): string` → `项目 · 分区 · 楼层 · 资产名称`
  - `TRANSFER_DIRECTION` / `TRANSFER_SCOPE` / `TRANSFER_MODE` / `TRANSFER_STATUS` 标签表
  - `suggestDirection(companies, fromCompanyId, toCompanyId): 'internal' | 'external' | undefined`

**为什么 lib 与注册必须同一个 task**：`pathToCode.ts` 的镜像与 `STANDALONE_ROUTES` 由 `scripts/check-perm-invariants.mjs` 交叉校验（第 3 / 4 条）。只改一半的中间状态会硬失败 —— 这不是可以拆开的两个提交。

---

- [ ] **Step 1: 写 API 与类型层**

Create `frontend/admin-web/src/lib/ownershipTransfer.ts`：

```ts
import { api } from '@/lib/api';
import { normalizeList } from '@/lib/org';
import type { CompanyOption } from '@/lib/org';

/**
 * 权属流转（与后端 `com.ams.modules.ownership` 逐字段对应）。
 *
 * <p>把类型与请求都收在 lib 里、页面只消费：列表页与表单页都要用同一套字段名，
 * 各自抄一份会在后端改字段名时只改一半（前端不会因此变红，只在运行时显示空白）。
 */

/** 与后端 `OwnershipTransferAssetView` 对应 */
export interface OwnershipTransferAssetView {
  assetId: number;
  assetNo?: string | null;
  assetName?: string | null;
  projectName?: string | null;
  zoneName?: string | null;
  floorNo?: number | null;
  fromPropertyCompanyId?: number | null;
  fromOperatingCompanyId?: number | null;
}

/** 与后端 `OwnershipTransferView` 对应 */
export interface OwnershipTransferView {
  id: number;
  direction: string;
  transferScope: string;
  fromCompanyId: number;
  fromCompanyName?: string | null;
  toCompanyId: number;
  toCompanyName?: string | null;
  transferMode: string;
  applicantUserId?: number | null;
  applicantName: string;
  approvalDeadline?: string | null;
  amountWan?: number | null;
  reason?: string | null;
  status: string;
  effectedAt?: string | null;
  createdAt?: string | null;
  assetCount: number;
  /** 详情才有值（列表页为 null） */
  assets?: OwnershipTransferAssetView[] | null;
  attachments?: { fileId: number; url: string; name: string }[] | null;
}

/** 与后端 `TransferAssetOption` 对应 */
export interface TransferAssetOption {
  assetId: number;
  assetNo?: string | null;
  name?: string | null;
  projectName?: string | null;
  zoneName?: string | null;
  floorNo?: number | null;
}

/** 表单提交体（与后端 `OwnershipTransferInput` 对应） */
export interface OwnershipTransferInput {
  direction: string;
  transferScope: string;
  fromCompanyId: number;
  toCompanyId: number;
  transferMode: string;
  applicantUserId?: number | null;
  applicantName: string;
  approvalDeadline?: string | null;
  amountWan?: number | null;
  reason?: string | null;
  assetIds: number[];
  attachments: { fileId: number; url: string; name: string }[];
}

export interface OwnershipTransferListParams {
  page: number;
  pageSize: number;
  status?: string;
  direction?: string;
  fromCompanyId?: number;
  keyword?: string;
}

export interface AssetOptionParams {
  companyId: number;
  transferScope?: string;
  keyword?: string;
  page?: number;
  pageSize?: number;
}

/**
 * 兼容「直接数组」与「PageResult」两种响应形态。
 *
 * <p>与 `ResourcePage` / `lib/org.ts` 同口径的兜底：后端某天改成返回数组（或中间网关
 * 包装了一层）时，页面会显示「0 条」而不是抛错 —— 后者会让整页白屏，前者至少能自查。
 */
const readPage = <T>(raw: unknown): { list: T[]; total: number } => {
  const list = normalizeList<T>(raw);
  const total =
    raw && typeof raw === 'object' && typeof (raw as { total?: number }).total === 'number'
      ? (raw as { total: number }).total
      : list.length;
  return { list, total };
};

const toQuery = (params: Record<string, unknown>): string => {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  return search.toString();
};

export const ownershipTransferApi = {
  list: async (params: OwnershipTransferListParams) => {
    const raw = await api.get<unknown>(`/ownership-transfers?${toQuery(params)}`);
    return readPage<OwnershipTransferView>(raw);
  },

  detail: (id: number) => api.get<OwnershipTransferView>(`/ownership-transfers/${id}`),

  assetOptions: async (params: AssetOptionParams) => {
    const raw = await api.get<unknown>(`/ownership-transfers/asset-options?${toQuery(params)}`);
    return readPage<TransferAssetOption>(raw);
  },

  create: (input: OwnershipTransferInput) =>
    api.post<OwnershipTransferView>('/ownership-transfers', input),

  update: (id: number, input: OwnershipTransferInput) =>
    api.put<OwnershipTransferView>(`/ownership-transfers/${id}`, input),

  /** 注意是 `api.del`：`api` 上没有 `delete` 方法（`delete` 是 JS 关键字，仓内统一用 `del`）。 */
  remove: (id: number) => api.del<void>(`/ownership-transfers/${id}`),

  /** 生效：不可逆，调用前必须二次确认。 */
  effect: (id: number) => api.post<OwnershipTransferView>(`/ownership-transfers/${id}/effect`),
};

/**
 * 资产显示的公共入参：**下拉项与详情资产行的公共子集**。
 *
 * <p>为什么要有这个接口：下拉项（`TransferAssetOption`）的名字字段是 `name`，
 * 而详情资产行（`OwnershipTransferAssetView`）是 `assetName` —— 两者其余字段一致。
 * 若把 `assetOptionLabel` 写成只吃其中一种，另一处就必须自己再拼一份 label，
 * 而「两处拼接必然漂移」（同一次流转在两个页面显示成不同的资产名）。
 */
export interface AssetLabelSource {
  assetId: number;
  assetNo?: string | null;
  /** 下拉项的字段名 */
  name?: string | null;
  /** 详情资产行的字段名 */
  assetName?: string | null;
  projectName?: string | null;
  zoneName?: string | null;
  floorNo?: number | null;
}

/**
 * 资产下拉的显示文案：`项目 · 分区 · 楼层 · 资产名称`（需求指定的四段）。
 *
 * <p>空段跳过而不是留空占位：`- · - · 5层 · 厂房A` 比 `厂房A` 更难读。
 * 名称缺失时回落到资产编号 —— 至少让用户能选中一个可辨认的东西。
 */
export const assetOptionLabel = (option: AssetLabelSource): string => {
  const title = option.name ?? option.assetName ?? null;
  const floor = option.floorNo === null || option.floorNo === undefined ? null : `${option.floorNo}层`;
  const segments = [option.projectName, option.zoneName, floor, title].filter(
    (segment): segment is string => Boolean(segment && String(segment).trim()),
  );
  return segments.length > 0 ? segments.join(' · ') : (option.assetNo ?? `资产 #${option.assetId}`);
};

/** 公司名的兜底（公司被删或未加载时不该显示 `undefined`）。 */
export const companyLabel = (name?: string | null, id?: number | null): string =>
  name ?? (id === null || id === undefined ? '-' : `#${id}`);

/**
 * 按公司树推内部 / 外部（与后端 `TransferDirectionResolver` 同一规则：上溯到根，同根为内部）。
 *
 * <p>**只用于给表单预填与提示**，不是判定权：后端会再判一次并不一致时 400。
 * 前端算一遍的价值在于「选完两个公司就知道该选哪个方向」，而不是等提交才报错。
 */
export const suggestDirection = (
  companies: CompanyOption[],
  fromCompanyId?: number | null,
  toCompanyId?: number | null,
): 'internal' | 'external' | undefined => {
  if (!fromCompanyId || !toCompanyId) return undefined;
  const parentById = new Map(companies.map((company) => [company.id, company.parentId ?? null]));
  const rootOf = (id: number): number => {
    let cursor: number = id;
    // 环保护：脏数据（A.parent = B、B.parent = A）不该让页面挂死
    const seen = new Set<number>();
    while (!seen.has(cursor)) {
      seen.add(cursor);
      const parent = parentById.get(cursor);
      if (parent === null || parent === undefined) return cursor;
      cursor = parent;
    }
    return cursor;
  };
  return rootOf(fromCompanyId) === rootOf(toCompanyId) ? 'internal' : 'external';
};
```

- [ ] **Step 2: 加标签表**

在 `frontend/admin-web/src/lib/labels.ts` **末尾**追加（与既有 `LEASE_CONTROL_STATUS` 同一风格 —— 静态表，不查字典接口；枚举值是闭集）：

```ts
/** 权属流转方向（sys_dict_type.code = transfer_direction） */
export const TRANSFER_DIRECTION: Record<string, string> = {
  internal: '内部流转',
  external: '外部流转',
};

/** 权属类型（sys_dict_type.code = transfer_scope）——决定改资产的哪个公司字段 */
export const TRANSFER_SCOPE: Record<string, string> = {
  both: '经营权且产权',
  property: '产权',
  operating: '经营权',
};

/** 流转类型（sys_dict_type.code = transfer_mode） */
export const TRANSFER_MODE: Record<string, string> = {
  allocate: '直接划拨',
  purchase: '购买流转',
  auction: '拍卖流转',
};

/** 权属流转单据状态（本期只有两态，无审批中） */
export const TRANSFER_STATUS: Record<string, string> = {
  draft: '草稿',
  completed: '已完成',
};
```

- [ ] **Step 3: 注册路由与菜单（五处，必须一次改完）**

1. `frontend/admin-web/src/lib/pathToCode.ts`：在 `// ---- 资债权证（deed / icon=certificate） ----` 段的 `/evaluations` 之后加一行：

```ts
  '/evaluations': 'deed.evaluation',
  '/ownership-transfers': 'deed.ownershipTransfer',
```

2. `frontend/admin-web/src/lib/routeRegistry.ts`：在 `STANDALONE_ROUTES` 的 `'/org/structure',` 之后加：

```ts
  '/org/structure',
  // 权属流转：多资产远程选择 + 附件，字段类型超出 ResourcePage 的表达能力，走独立页
  '/ownership-transfers',
```

3. `frontend/admin-web/src/pages/modules.tsx`：`资债权证记录` 分组的 `items` 末尾加：

```ts
      { path: '/evaluations', title: '评估申请' },
      { path: '/ownership-transfers', title: '权属流转' },
```

4. `frontend/admin-web/src/lib/menuIcons.tsx`：`PATH_ICONS` 里 `/evaluations` 之后加：

```tsx
  '/evaluations': <FormOutlined />,
  // V54 菜单的 icon 列为 NULL，侧栏会回退到这里
  '/ownership-transfers': <SwapOutlined />,
```

> `SwapOutlined` 已在文件顶部 import（`/asset-transfers` 在用），**不需要**新增 import。

5. `frontend/admin-web/src/App.tsx`：

   a. 在 `import { OperationLogPage } from '@/pages/OperationLogPage';` 之后加：

```tsx
import { OwnershipTransfersPage } from '@/pages/OwnershipTransfersPage';
import { OwnershipTransferFormPage } from '@/pages/OwnershipTransferFormPage';
```

   b. 在 `<Route path="system/operation-logs" element={<OperationLogPage />} />` 这一行之后加：

```tsx
              {/* 权属流转：多资产改产权公司。列表 + 独立表单页（新建 / 编辑草稿同页） */}
              <Route path="ownership-transfers" element={<OwnershipTransfersPage />} />
              <Route path="ownership-transfers/new" element={<OwnershipTransferFormPage />} />
              <Route path="ownership-transfers/:id/edit" element={<OwnershipTransferFormPage />} />
```

> `/ownership-transfers/new` 与 `/:id/edit` **不登记** `STANDALONE_ROUTES`：它们是列表页钻取进入的页面，不是菜单项（同 `/assets/:assetId/dossier` 的处理）。登记它们会让注册表校验把「未注册菜单」的告警吞掉。

- [ ] **Step 4: 跑守卫（此时会因页面文件还没建而失败，这是预期的）**

Run:

```bash
cd frontend && pnpm check:perm
```

Expected: `check:perm` 的第 3 条（`STANDALONE_ROUTES` 与 `App.tsx` 的 `<Route>` 互相覆盖）应该仍然通过（`<Route>` 已加），但 `tsc` 层面不通 —— 下一页测试会报找不到 `OwnershipTransfersPage`。

- [ ] **Step 5: Commit（连同 Task 13 / 14 的页面一起提交）**

见 Task 14 的 Step 5。**本 task 不单独提交**：`check:perm` 的第 3 条要求 `STANDALONE_ROUTES` 的每个 path 都有 `<Route>`，而 `<Route>` 指向的组件必须已存在才能编译 —— 这个中间状态既过不了 `check:perm` 也过不了 `tsc`。

---

## Task 13: 列表页

**Files:**

- Create: `frontend/admin-web/src/pages/OwnershipTransfersPage.tsx`

**Interfaces:**

- Consumes: `ownershipTransferApi` / `assetOptionLabel` / 4 张标签表 / `TRANSFER_DIRECTION` 等（Task 12）；`useListQuery`、`usePerm`、`loadCompanies`（既有）
- Produces: `/ownership-transfers` 页面组件 `OwnershipTransfersPage`

---

- [ ] **Step 1: 写列表页**

Create `frontend/admin-web/src/pages/OwnershipTransfersPage.tsx`：

```tsx
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button,
  Descriptions,
  Drawer,
  Empty,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  SendOutlined,
  SwapOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useListQuery } from '@/lib/listQuery';
import { usePerm } from '@/lib/perm';
import { loadCompanies, type CompanyOption } from '@/lib/org';
import {
  TRANSFER_DIRECTION,
  TRANSFER_MODE,
  TRANSFER_SCOPE,
  TRANSFER_STATUS,
} from '@/lib/labels';
import {
  assetOptionLabel,
  companyLabel,
  ownershipTransferApi,
  type OwnershipTransferView,
} from '@/lib/ownershipTransfer';

const CODE = 'deed.ownershipTransfer';

const STATUS_COLOR: Record<string, string> = { draft: 'default', completed: 'green' };

const formatTime = (value?: string | null): string =>
  value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-';

/**
 * 权属流转列表页。
 *
 * <p>筛选项刻意只有四个（状态 / 方向 / 原公司 / 关键字）：一张流转单本身没有那么多维度，
 * 堆满筛选框会让「先按状态看一眼草稿」这个最常用的动作变慢。
 *
 * <p>单据没有单号列（与处置单/调拨一致），界面用 `#id` 指代 —— 加一个业务单号需要
 * 编号规则、年度重置与重号检测，而它并不解决任何现有问题。
 */
export function OwnershipTransfersPage() {
  const navigate = useNavigate();
  const can = usePerm();
  const canCreate = can(CODE, 'create');
  const canUpdate = can(CODE, 'update');
  const canDelete = can(CODE, 'delete');

  const list = useListQuery({
    filterKeys: ['status', 'direction', 'fromCompanyId'],
    defaultPageSize: 10,
  });
  const { page, pageSize, keyword, filters, setPage, setPageSize, setKeyword, setFilter } = list;

  const status = filters.status || undefined;
  const direction = filters.direction || undefined;
  const fromCompanyId = filters.fromCompanyId ? Number(filters.fromCompanyId) : undefined;

  const [companies, setCompanies] = useState<CompanyOption[]>([]);
  const [rows, setRows] = useState<OwnershipTransferView[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<OwnershipTransferView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [working, setWorking] = useState(false);

  useEffect(() => {
    void loadCompanies().then(setCompanies);
  }, []);

  const companyOptions = useMemo(
    () => companies.map((company) => ({ value: company.id, label: company.name })),
    [companies],
  );

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await ownershipTransferApi.list({
        page,
        pageSize,
        status,
        direction,
        fromCompanyId,
        keyword,
      });
      setRows(data.list);
      setTotal(data.total);
    } catch (e) {
      // 失败时清空而不是留着上一次的数据：留着会让人以为「筛选没生效」
      setRows([]);
      setTotal(0);
      message.error(e instanceof Error ? e.message : '加载权属流转单失败');
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, status, direction, fromCompanyId, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleOpenDetail = useCallback(async (id: number) => {
    setDetailLoading(true);
    setDetail(null);
    try {
      const data = await ownershipTransferApi.detail(id);
      setDetail(data);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载详情失败');
    } finally {
      setDetailLoading(false);
    }
  }, []);

  /**
   * 生效：不可逆的跨法人变更，必须二次确认。
   *
   * <p>确认文案里写明「会改写 N 个资产的产权 / 经营公司」而不是笼统的「确认生效？」——
   * 用户需要在这一刻知道影响面，而不是事后再去对账。
   */
  const handleEffect = (row: OwnershipTransferView) => {
    Modal.confirm({
      title: '确认生效？',
      content: (
        <div className="text-sm">
          <div>
            将把 <b>{row.assetCount}</b> 个资产的
            <b>{TRANSFER_SCOPE[row.transferScope] ?? row.transferScope}</b>
            改写为「{companyLabel(row.toCompanyName, row.toCompanyId)}」。
          </div>
          <div className="text-amber-600 mt-1">
            {row.direction === 'external'
              ? '外部流转：资产会被标记为「已对外转出」并置为已退出，且不可撤销。'
              : '生效后不可撤销，需要再改回来只能新建一张反向单。'}
          </div>
        </div>
      ),
      okText: '确认生效',
      cancelText: '取消',
      onOk: async () => {
        setWorking(true);
        try {
          await ownershipTransferApi.effect(row.id);
          message.success('已生效');
          await load();
        } catch (e) {
          message.error(e instanceof Error ? e.message : '生效失败');
          throw e;
        } finally {
          setWorking(false);
        }
      },
    });
  };

  const handleDelete = (row: OwnershipTransferView) => {
    Modal.confirm({
      title: '确认删除该草稿？',
      content: '删除后单据不再出现在列表与资产档案中；已完成的单据不能删除。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        setWorking(true);
        try {
          await ownershipTransferApi.remove(row.id);
          message.success('已删除');
          await load();
        } catch (e) {
          message.error(e instanceof Error ? e.message : '删除失败');
          throw e;
        } finally {
          setWorking(false);
        }
      },
    });
  };

  const columns: ColumnsType<OwnershipTransferView> = useMemo(
    () => [
      {
        title: '单据',
        dataIndex: 'id',
        key: 'id',
        width: 90,
        render: (_, row) => <span className="tabular-nums">#{row.id}</span>,
      },
      {
        title: '流转方向',
        dataIndex: 'direction',
        key: 'direction',
        width: 100,
        render: (_, row) => (
          <Tag color={row.direction === 'external' ? 'orange' : 'blue'} className="m-0">
            {TRANSFER_DIRECTION[row.direction] ?? row.direction}
          </Tag>
        ),
      },
      {
        title: '权属类型',
        dataIndex: 'transferScope',
        key: 'transferScope',
        width: 120,
        render: (_, row) => TRANSFER_SCOPE[row.transferScope] ?? row.transferScope,
      },
      {
        title: '原公司 → 新公司',
        key: 'companies',
        width: 260,
        render: (_, row) => (
          <span className="text-gray-800">
            {companyLabel(row.fromCompanyName, row.fromCompanyId)}
            <span className="text-gray-400 mx-1">→</span>
            <b>{companyLabel(row.toCompanyName, row.toCompanyId)}</b>
          </span>
        ),
      },
      {
        title: '流转类型',
        dataIndex: 'transferMode',
        key: 'transferMode',
        width: 110,
        render: (_, row) => TRANSFER_MODE[row.transferMode] ?? row.transferMode,
      },
      {
        title: '变更申请人',
        dataIndex: 'applicantName',
        key: 'applicantName',
        width: 120,
      },
      {
        title: '金额(万元)',
        dataIndex: 'amountWan',
        key: 'amountWan',
        width: 110,
        align: 'right',
        render: (_, row) =>
          row.amountWan === null || row.amountWan === undefined ? (
            <span className="text-gray-300">-</span>
          ) : (
            <span className="tabular-nums">{row.amountWan}</span>
          ),
      },
      {
        title: '资产数',
        dataIndex: 'assetCount',
        key: 'assetCount',
        width: 90,
        align: 'right',
        render: (_, row) => <span className="tabular-nums">{row.assetCount}</span>,
      },
      {
        title: '状态',
        dataIndex: 'status',
        key: 'status',
        width: 90,
        render: (_, row) => (
          <Tag color={STATUS_COLOR[row.status] ?? 'default'} className="m-0">
            {TRANSFER_STATUS[row.status] ?? row.status}
          </Tag>
        ),
      },
      {
        title: '创建时间',
        dataIndex: 'createdAt',
        key: 'createdAt',
        width: 150,
        render: (_, row) => (
          <span className="tabular-nums text-gray-600">{formatTime(row.createdAt)}</span>
        ),
      },
      {
        title: '操作',
        key: 'actions',
        width: 170,
        fixed: 'right',
        render: (_, row) => (
          <Space size={4}>
            <Button type="link" size="small" className="p-0" onClick={() => void handleOpenDetail(row.id)}>
              详情
            </Button>
            {/* 草稿才可编辑 / 生效 / 删除 —— 与后端状态机一致，避免点了才报 409 */}
            {row.status === 'draft' && canUpdate && (
              <>
                <Button
                  type="link"
                  size="small"
                  className="p-0"
                  icon={<EditOutlined />}
                  onClick={() => navigate(`/ownership-transfers/${row.id}/edit`)}
                >
                  编辑
                </Button>
                <Button
                  type="link"
                  size="small"
                  className="p-0"
                  icon={<SendOutlined />}
                  disabled={working}
                  onClick={() => handleEffect(row)}
                >
                  生效
                </Button>
              </>
            )}
            {row.status === 'draft' && canDelete && (
              <Button
                type="link"
                size="small"
                danger
                className="p-0"
                icon={<DeleteOutlined />}
                disabled={working}
                onClick={() => handleDelete(row)}
              />
            )}
          </Space>
        ),
      },
    ],
    // handleOpenDetail / handleEffect / handleDelete 都闭包了 loading 与 list 的引用；
    // 依赖里只放会真正改变渲染的项，避免每次渲染都重建列定义导致表格整表重绘
    [canUpdate, canDelete, working, navigate, handleOpenDetail],
  );

  return (
    <div className="space-y-4 min-w-0 max-w-full overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold text-gray-900 flex items-center gap-2 m-0">
            <SwapOutlined className="text-[var(--ams-primary)]" />
            权属流转
          </h1>
          <p className="text-sm text-gray-500 mt-1 mb-0">
            一次为多个资产变更产权公司 / 经营公司；草稿可反复修改，生效后不可撤销
          </p>
        </div>
        <Space wrap size={[8, 8]}>
          <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
            刷新
          </Button>
          {canCreate && (
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => navigate('/ownership-transfers/new')}
            >
              新建流转单
            </Button>
          )}
        </Space>
      </div>

      <div className="bg-white rounded-xl border border-[var(--ams-border)] p-3 sm:p-4">
        <div className="flex flex-wrap items-center gap-2 mb-3">
          <Select
            className="!w-[130px]"
            allowClear
            placeholder="全部状态"
            value={status}
            options={[
              { value: 'draft', label: TRANSFER_STATUS.draft },
              { value: 'completed', label: TRANSFER_STATUS.completed },
            ]}
            onChange={(value?: string) => setFilter('status', value ?? '')}
          />
          <Select
            className="!w-[140px]"
            allowClear
            placeholder="全部方向"
            value={direction}
            options={Object.entries(TRANSFER_DIRECTION).map(([value, label]) => ({ value, label }))}
            onChange={(value?: string) => setFilter('direction', value ?? '')}
          />
          <Select
            className="!w-[200px]"
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder="全部原公司"
            value={fromCompanyId}
            options={companyOptions}
            onChange={(value?: number) => setFilter('fromCompanyId', value ? String(value) : '')}
          />
          <Input
            className="!w-[260px]"
            allowClear
            placeholder="搜索流转原因 / 申请人（回车）"
            defaultValue={keyword}
            prefix={<SearchOutlined className="text-gray-300" />}
            onPressEnter={(e) => setKeyword(e.currentTarget.value.trim())}
          />
        </div>

        <div className="ams-table-wrap">
          <Table
            rowKey="id"
            size="small"
            loading={loading}
            columns={columns}
            dataSource={rows}
            scroll={{ x: 1500 }}
            locale={{
              emptyText: (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无权属流转单" />
              ),
            }}
            pagination={{
              current: page,
              pageSize,
              total,
              showSizeChanger: true,
              pageSizeOptions: ['10', '20', '50'],
              showTotal: (value) => `共 ${value} 条`,
              onChange: (nextPage, nextPageSize) => {
                // 两个值必须一起交给 useListQuery：分开调两次 setter 时后一次会用同一份
                // 旧 searchParams 覆盖前一次（lib/listQuery.ts 顶部已说明）
                if (nextPageSize !== pageSize) setPageSize(nextPageSize);
                else setPage(nextPage);
              },
            }}
          />
        </div>
      </div>

      <Drawer
        title={detail ? `权属流转单 #${detail.id}` : '权属流转单'}
        open={!!detail || detailLoading}
        onClose={() => setDetail(null)}
        width={Math.min(760, typeof window !== 'undefined' ? window.innerWidth - 32 : 760)}
        destroyOnHidden
      >
        {detail && (
          <div className="space-y-4">
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="流转方向">
                {TRANSFER_DIRECTION[detail.direction] ?? detail.direction}
              </Descriptions.Item>
              <Descriptions.Item label="权属类型">
                {TRANSFER_SCOPE[detail.transferScope] ?? detail.transferScope}
              </Descriptions.Item>
              <Descriptions.Item label="原公司">
                {companyLabel(detail.fromCompanyName, detail.fromCompanyId)}
              </Descriptions.Item>
              <Descriptions.Item label="新公司">
                {companyLabel(detail.toCompanyName, detail.toCompanyId)}
              </Descriptions.Item>
              <Descriptions.Item label="流转类型">
                {TRANSFER_MODE[detail.transferMode] ?? detail.transferMode}
              </Descriptions.Item>
              <Descriptions.Item label="变更申请人">{detail.applicantName}</Descriptions.Item>
              <Descriptions.Item label="审批截止时间">
                {formatTime(detail.approvalDeadline)}
              </Descriptions.Item>
              <Descriptions.Item label="金额(万元)">
                {detail.amountWan ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="流转原因">
                <span className="break-all">{detail.reason || '-'}</span>
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={STATUS_COLOR[detail.status] ?? 'default'} className="m-0">
                  {TRANSFER_STATUS[detail.status] ?? detail.status}
                </Tag>
                {detail.effectedAt && (
                  <span className="text-gray-500 ml-2">生效于 {formatTime(detail.effectedAt)}</span>
                )}
              </Descriptions.Item>
            </Descriptions>

            <div>
              <div className="text-sm font-medium text-gray-700 mb-1">
                资产（{detail.assets?.length ?? 0}）
              </div>
              <Table
                rowKey="assetId"
                size="small"
                pagination={false}
                dataSource={detail.assets ?? []}
                scroll={{ y: 240 }}
                columns={[
                  {
                    title: '资产',
                    key: 'asset',
                    render: (_, row) => (
                      <Tooltip title={row.assetNo ?? undefined} placement="topLeft">
                        <span>{assetOptionLabel(row)}</span>
                      </Tooltip>
                    ),
                  },
                ]}
                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无" /> }}
              />
            </div>

            <div>
              <div className="text-sm font-medium text-gray-700 mb-1">
                附件（{detail.attachments?.length ?? 0}）
              </div>
              {(detail.attachments?.length ?? 0) === 0 ? (
                <div className="text-xs text-gray-400">无附件</div>
              ) : (
                <ul className="m-0 pl-4">
                  {detail.attachments?.map((file) => (
                    <li key={file.fileId}>
                      <a href={file.url} target="_blank" rel="noreferrer">
                        {file.name}
                      </a>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        )}
      </Drawer>
    </div>
  );
}
```

- [ ] **Step 2: 本 task 不单独验证（与 Task 14 共用一个提交）**

`App.tsx` 在 Task 12 已 import 了 `OwnershipTransferFormPage`，因此**在 Task 14 建出表单页之前，`tsc` 必然报「找不到模块」**。这不是本 task 的缺陷。

Task 13 与 Task 14 是一次不可分割的变更（`check:perm` 第 3 条要求 `STANDALONE_ROUTES` 的每个 path 都有 `<Route>`、而 `<Route>` 的组件必须可编译），验证与提交统一在 **Task 14 Step 2–5** 完成。

- [ ] **Step 3: 直接进入 Task 14**

不要在中间跑 `tsc` / `pnpm lint` / `check:perm` —— 它们在页面文件凑齐之前必然失败，只会制造噪声。

---

## Task 14: 表单页（含资产远程联动选择）

**Files:**

- Create: `frontend/admin-web/src/pages/OwnershipTransferFormPage.tsx`
- Modify: `frontend/admin-web/src/lib/ownershipTransfer.ts`（按 Task 13 Step 2 的提示放宽 `assetOptionLabel` 形参）

**Interfaces:**

- Consumes: `ownershipTransferApi`、`suggestDirection`、`assetOptionLabel`、`AttachmentField`、`ActorField`、`loadCompanies`
- Produces: `/ownership-transfers/new` 与 `/ownership-transfers/:id/edit` 的页面组件 `OwnershipTransferFormPage`

---

- [ ] **Step 1: 写表单页**

Create `frontend/admin-web/src/pages/OwnershipTransferFormPage.tsx`：

```tsx
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Button,
  Card,
  DatePicker,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Spin,
  Tag,
  message,
} from 'antd';
import { ArrowLeftOutlined, SaveOutlined, SwapOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { ActorField, type ActorValue } from '@/components/ActorField';
import { AttachmentField, type AttachmentValue } from '@/components/AttachmentField';
import { usePerm } from '@/lib/perm';
import { loadCompanies, type CompanyOption } from '@/lib/org';
import { TRANSFER_DIRECTION, TRANSFER_MODE, TRANSFER_SCOPE } from '@/lib/labels';
import {
  assetOptionLabel,
  companyLabel,
  ownershipTransferApi,
  suggestDirection,
  type OwnershipTransferInput,
  type TransferAssetOption,
} from '@/lib/ownershipTransfer';

const CODE = 'deed.ownershipTransfer';

/** 资产远程搜索的防抖窗口：300ms 是「打字停顿」与「每键一次全表 LIKE」之间的折中。 */
const SEARCH_DEBOUNCE_MS = 300;
const ASSET_PAGE_SIZE = 50;

/** 表单值（与后端入参的差异都在 `toPayload` 里收口） */
interface FormValues {
  direction: string;
  transferScope: string;
  fromCompanyId: number;
  toCompanyId: number;
  transferMode: string;
  applicant?: ActorValue | null;
  approvalDeadline?: Dayjs | null;
  amountWan?: number | null;
  reason?: string | null;
  assetIds?: number[];
  attachments?: AttachmentValue[];
}

/**
 * 权属流转表单页（新建 / 编辑草稿共用）。
 *
 * <p>三处刻意的取舍：
 *
 * <ol>
 *   <li><b>「审批截止时间」带说明文字</b>：本期没有审批环节，这一栏只是业务留痕。
 *       不说清楚会让人以为「填了就会有人来审批」，然后一直等；</li>
 *   <li><b>换原公司 / 换权属类型时先确认再清空已选资产</b>：候选集变了，之前选的资产
 *       在新条件下可能已经不属于这家公司 —— 留着不放会在提交时才报 400，
 *       而静默清空会让人以为「白选了」；</li>
 *   <li><b>方向自动预填但可改</b>：与后端同一套公司树判定（同根为内部）；不一致时给出
 *       提示而不是禁止提交 —— 前端算错时不该把功能锁死，后端仍会给出明确报错。</li>
 * </ol>
 */
export function OwnershipTransferFormPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const can = usePerm();
  const isEdit = Boolean(id);
  const transferId = isEdit ? Number(id) : null;

  const [form] = Form.useForm<FormValues>();
  const [companies, setCompanies] = useState<CompanyOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [assetOptions, setAssetOptions] = useState<TransferAssetOption[]>([]);
  const [assetLoading, setAssetLoading] = useState(false);

  const fromCompanyId = Form.useWatch('fromCompanyId', form);
  const toCompanyId = Form.useWatch('toCompanyId', form);
  const transferScope = Form.useWatch('transferScope', form);
  const direction = Form.useWatch('direction', form);
  const assetIds = Form.useWatch('assetIds', form);

  const requiredAction = isEdit ? 'update' : 'create';
  const allowed = can(CODE, requiredAction);

  useEffect(() => {
    void loadCompanies().then(setCompanies);
  }, []);

  const companyOptions = useMemo(
    () => companies.map((company) => ({ value: company.id, label: company.name })),
    [companies],
  );

  const suggested = suggestDirection(companies, fromCompanyId, toCompanyId);

  // ---------------------------------------------------------------
  // 资产远程搜索
  // ---------------------------------------------------------------

  /**
   * 拉候选资产。`companyId` 必填 —— 后端在没有公司时会 400，前端提前拦住并给出提示。
   *
   * <p>`transferScope` 决定按产权公司还是经营公司过滤（与后端同一规则）：
   * 选「经营权」时列出的应该是「经营公司 = 原公司」的资产，而不是产权公司。
   */
  const fetchAssets = useCallback(
    async (keywordText: string) => {
      if (!fromCompanyId) {
        setAssetOptions([]);
        return;
      }
      setAssetLoading(true);
      try {
        const data = await ownershipTransferApi.assetOptions({
          companyId: fromCompanyId,
          transferScope,
          keyword: keywordText || undefined,
          page: 1,
          pageSize: ASSET_PAGE_SIZE,
        });
        setAssetOptions(data.list);
      } catch (e) {
        setAssetOptions([]);
        message.error(e instanceof Error ? e.message : '加载资产失败');
      } finally {
        setAssetLoading(false);
      }
    },
    [fromCompanyId, transferScope],
  );

  // 公司 / 权属类型变化：候选集整体变了，必须重新拉一次（只靠搜索框是拉不到的）
  useEffect(() => {
    void fetchAssets('');
  }, [fetchAssets]);

  const debounceRef = useRef<number | undefined>(undefined);
  const handleAssetSearch = (keywordText: string) => {
    window.clearTimeout(debounceRef.current);
    debounceRef.current = window.setTimeout(() => {
      void fetchAssets(keywordText.trim());
    }, SEARCH_DEBOUNCE_MS);
  };

  useEffect(
    () => () => {
      window.clearTimeout(debounceRef.current);
    },
    [],
  );

  /**
   * 换原公司 / 换权属类型：候选集变了，已选资产可能已不再合法。
   *
   * <p>先确认再清空，而不是静默清空或静默保留：静默清空会让人以为白选了，
   * 静默保留则会在提交时以一句「资产不属于所选原公司」告终。
   */
  const handleScopeKeyChange = (next: { fromCompanyId?: number; transferScope?: string }) => {
    const currentFrom = form.getFieldValue('fromCompanyId');
    const currentScope = form.getFieldValue('transferScope');
    const changed =
      (next.fromCompanyId !== undefined && next.fromCompanyId !== currentFrom) ||
      (next.transferScope !== undefined && next.transferScope !== currentScope);
    const selected = form.getFieldValue('assetIds') as number[] | undefined;
    if (!changed || !selected || selected.length === 0) return;

    Modal.confirm({
      title: '需要重新选择资产',
      content: `原公司或权属类型已改变，已选的 ${selected.length} 个资产可能不再属于该范围，将继续保留请先自行核对。`,
      okText: '清空并重选',
      cancelText: '保留已选',
      onOk: () => form.setFieldValue('assetIds', []),
    });
  };

  // ---------------------------------------------------------------
  // 回显（编辑）
  // ---------------------------------------------------------------

  useEffect(() => {
    if (!transferId) return;
    setLoading(true);
    void ownershipTransferApi
      .detail(transferId)
      .then((data) => {
        form.setFieldsValue({
          direction: data.direction,
          transferScope: data.transferScope,
          fromCompanyId: data.fromCompanyId,
          toCompanyId: data.toCompanyId,
          transferMode: data.transferMode,
          applicant: data.applicantUserId
            ? { userId: data.applicantUserId, name: data.applicantName }
            : { name: data.applicantName },
          approvalDeadline: data.approvalDeadline ? dayjs(data.approvalDeadline) : null,
          amountWan: data.amountWan === null || data.amountWan === undefined ? null : Number(data.amountWan),
          reason: data.reason ?? '',
          assetIds: data.assets?.map((asset) => asset.assetId) ?? [],
          attachments: data.attachments ?? [],
        });
        // 已选资产在详情里有名字（项目 / 分区 / 楼层），先并进候选集，
        // 否则 Select 会因为 options 里找不到 id 而只显示一个裸数字
        setAssetOptions(
          (data.assets ?? []).map((asset) => ({
            assetId: asset.assetId,
            assetNo: asset.assetNo,
            name: asset.assetName,
            projectName: asset.projectName,
            zoneName: asset.zoneName,
            floorNo: asset.floorNo,
          })),
        );
      })
      .catch((e) => {
        message.error(e instanceof Error ? e.message : '加载权属流转单失败');
        navigate('/ownership-transfers');
      })
      .finally(() => setLoading(false));
  }, [transferId, form, navigate]);

  // ---------------------------------------------------------------
  // 提交
  // ---------------------------------------------------------------

  const toPayload = (values: FormValues): OwnershipTransferInput => {
    const applicant = values.applicant ?? null;
    return {
      direction: values.direction,
      transferScope: values.transferScope,
      fromCompanyId: values.fromCompanyId,
      toCompanyId: values.toCompanyId,
      transferMode: values.transferMode,
      // 内员带 userId（后端用它回查姓名覆盖快照）；外部人员只带姓名
      applicantUserId: applicant?.userId ?? null,
      applicantName: applicant?.name?.trim() ?? '',
      // 只传本地时间，不带时区后缀：后端参数是 LocalDateTime
      approvalDeadline: values.approvalDeadline
        ? values.approvalDeadline.format('YYYY-MM-DDTHH:mm:ss')
        : null,
      amountWan: values.amountWan ?? null,
      reason: values.reason ?? null,
      assetIds: values.assetIds ?? [],
      attachments: values.attachments ?? [],
    };
  };

  const handleSubmit = async (goEffect: boolean) => {
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return; // 校验失败时 antd 已把错误标在字段上
    }
    setSaving(true);
    try {
      const payload = toPayload(values);
      const saved = isEdit && transferId
        ? await ownershipTransferApi.update(transferId, payload)
        : await ownershipTransferApi.create(payload);
      message.success(isEdit ? '已保存' : '草稿已创建');
      if (goEffect) {
        // 新建后立刻生效：先二次确认，再调生效接口
        Modal.confirm({
          title: '确认立即生效？',
          content: `将把 ${payload.assetIds.length} 个资产的「${
            TRANSFER_SCOPE[payload.transferScope] ?? payload.transferScope
          }」改写为「${companyLabel(
            companyOptions.find((company) => company.value === payload.toCompanyId)?.label,
            payload.toCompanyId,
          )}」。生效后不可撤销。`,
          okText: '确认生效',
          cancelText: '稍后再说',
          onOk: async () => {
            await ownershipTransferApi.effect(saved.id);
            message.success('已生效');
            navigate('/ownership-transfers');
          },
        });
        return;
      }
      navigate('/ownership-transfers');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  if (!allowed) {
    return (
      <div className="p-6">
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={isEdit ? '你没有编辑权属流转单的权限' : '你没有新建权属流转单的权限'}
        />
      </div>
    );
  }

  return (
    <div className="space-y-4 min-w-0 max-w-full">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold text-gray-900 flex items-center gap-2 m-0">
            <SwapOutlined className="text-[var(--ams-primary)]" />
            {isEdit ? `编辑权属流转单 #${transferId}` : '新建权属流转单'}
          </h1>
          <p className="text-sm text-gray-500 mt-1 mb-0">
            保存为草稿后可在列表中反复修改；生效会立即改写所选资产的产权 / 经营公司
          </p>
        </div>
        <Space wrap size={[8, 8]}>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/ownership-transfers')}>
            返回列表
          </Button>
          <Button icon={<SaveOutlined />} loading={saving} onClick={() => void handleSubmit(false)}>
            保存草稿
          </Button>
          <Button type="primary" loading={saving} onClick={() => void handleSubmit(true)}>
            保存并生效
          </Button>
        </Space>
      </div>

      <Spin spinning={loading}>
        <Card className="rounded-xl" styles={{ body: { padding: 20 } }}>
          <Form
            form={form}
            layout="vertical"
            initialValues={{ transferScope: 'property', direction: 'internal' }}
            onValuesChange={(changed) => {
              // 换原公司 / 换权属类型 -> 提示清空已选资产
              if ('fromCompanyId' in changed || 'transferScope' in changed) {
                handleScopeKeyChange({
                  fromCompanyId: changed.fromCompanyId,
                  transferScope: changed.transferScope,
                });
              }
            }}
          >
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-x-4">
              <Form.Item
                name="fromCompanyId"
                label="原产权公司"
                rules={[{ required: true, message: '请选择原产权公司' }]}
              >
                <Select
                  showSearch
                  optionFilterProp="label"
                  placeholder="请选择"
                  options={companyOptions}
                />
              </Form.Item>

              <Form.Item
                name="toCompanyId"
                label="新公司"
                rules={[{ required: true, message: '请选择新公司' }]}
              >
                <Select
                  showSearch
                  optionFilterProp="label"
                  placeholder="请选择"
                  options={companyOptions}
                />
              </Form.Item>

              <Form.Item
                name="direction"
                label="流转方向"
                rules={[{ required: true, message: '请选择流转方向' }]}
                extra={
                  suggested && direction && suggested !== direction ? (
                    <span className="text-amber-600">
                      按公司树应为「{TRANSFER_DIRECTION[suggested]}」，请核对
                    </span>
                  ) : (
                    '内部 = 原公司与新公司属同一集团'
                  )
                }
              >
                <Select
                  options={Object.entries(TRANSFER_DIRECTION).map(([value, label]) => ({
                    value,
                    label,
                  }))}
                />
              </Form.Item>

              <Form.Item
                name="transferScope"
                label="权属类型"
                rules={[{ required: true, message: '请选择权属类型' }]}
                extra="决定改写资产的哪个公司字段：产权公司 / 经营公司 / 两者"
              >
                <Select
                  options={Object.entries(TRANSFER_SCOPE).map(([value, label]) => ({ value, label }))}
                />
              </Form.Item>

              <Form.Item
                name="transferMode"
                label="流转类型"
                rules={[{ required: true, message: '请选择流转类型' }]}
              >
                <Select
                  options={Object.entries(TRANSFER_MODE).map(([value, label]) => ({ value, label }))}
                />
              </Form.Item>

              <Form.Item
                name="applicant"
                label="变更申请人"
                rules={[
                  {
                    validator: (_, value: ActorValue | null | undefined) =>
                      value && value.name && value.name.trim()
                        ? Promise.resolve()
                        : Promise.reject(new Error('请选择内部员工或填写外部人员姓名')),
                  },
                ]}
              >
                <ActorField companyId={fromCompanyId ?? null} placeholder="选择内部员工或输入外部人员姓名" />
              </Form.Item>

              <Form.Item
                name="approvalDeadline"
                label="审批截止时间"
                extra="本期不做审批流转，此栏仅作业务留痕"
              >
                <DatePicker showTime format="YYYY-MM-DD HH:mm" className="w-full" />
              </Form.Item>

              <Form.Item name="amountWan" label="金额(万元)">
                <InputNumber
                  className="w-full"
                  min={0}
                  precision={2}
                  // 不让输入框接受 3 位小数：后端会 400，在前端就不能打出来
                  stringMode
                  placeholder="最多 2 位小数"
                />
              </Form.Item>
            </div>

            <Form.Item
              name="assetIds"
              label="资产列表"
              rules={[{ required: true, message: '请至少选择 1 个资产' }]}
              extra={
                fromCompanyId
                  ? `候选资产按「${
                      transferScope === 'operating' ? '经营公司' : '产权公司'
                    } = ${companyLabel(
                      companyOptions.find((company) => company.value === fromCompanyId)?.label,
                      fromCompanyId,
                    )}」过滤，支持按名称 / 编号搜索`
                  : '请先选择原产权公司，再选择资产'
              }
            >
              <Select
                mode="multiple"
                allowClear
                showSearch
                // 必须 false：否则 antd 会在本地对 options 做过滤，与远程搜索结果叠加后
                // 表现为「搜到的项搜不到、没搜到的项一直在」
                filterOption={false}
                disabled={!fromCompanyId}
                placeholder={fromCompanyId ? '搜索并选择资产' : '请先选择原产权公司'}
                loading={assetLoading}
                onSearch={handleAssetSearch}
                options={assetOptions.map((option) => ({
                  value: option.assetId,
                  label: assetOptionLabel(option),
                }))}
                // 已选项加角标提示数量：选项多时（几十个）需要一个概览
                maxTagCount="responsive"
                aria-label="资产列表"
                notFoundContent={
                  assetLoading ? <Spin size="small" /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无可选资产" />
                }
              />
            </Form.Item>

            <Form.Item name="reason" label="流转原因">
              <Input.TextArea rows={3} maxLength={1000} showCount placeholder="请说明本次流转的原因" />
            </Form.Item>

            <Form.Item
              name="attachments"
              label="附件"
              extra="支持 pdf / 图片 / Office 文档，单个不超过 20MB"
            >
              <AttachmentField bizType="ownership_transfer" maxMB={20} maxCount={10} />
            </Form.Item>
          </Form>
        </Card>
      </Spin>

      {/* 已选资产数的实时回显：表单很长，滚动到按钮时看不到上面选了什么 */}
      <div className="text-xs text-gray-500">
        已选资产 <Tag className="m-0">{(assetIds ?? []).length}</Tag>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: 类型检查 + 构建**

Run:

```bash
cd frontend && pnpm --filter admin-web exec tsc --noEmit -p tsconfig.json
cd frontend && pnpm build
```

Expected: 0 error，构建产物生成。

- [ ] **Step 3: lint（基线是 4 warning / 0 error）**

Run:

```bash
cd frontend && pnpm lint
```

Expected: **0 error**，warning 数 **不超过 4**。常见的新增提示与处理：
- `react-hooks/exhaustive-deps` 抱怨 `columns` 的依赖数组不全 → 把缺失的 `handleEffect` / `handleDelete` 用 `useCallback` 包起来再加进依赖，**不要**用 `// eslint-disable`；
- `@typescript-eslint/no-unused-vars` → 删掉没用到的 import（例如列表页没用到 `Tag` 时）。

- [ ] **Step 4: 跑全部前端守卫**

Run:

```bash
cd frontend && pnpm check:perm && pnpm check:record && pnpm check:backend && pnpm check:audit
```

Expected: 四条全 `检查通过`。**`check:perm` 是这一步的重点** —— 它会同时校验：
- 前端声明的 4 个 `deed.ownershipTransfer:*` 都在后端 `@RequiresPerm` 里；
- `STANDALONE_ROUTES` 的每个 path 都有 `<Route>`；
- `STANDALONE_ROUTES` 的每个 path 都在 `PATH_TO_CODE` 镜像里。

- [ ] **Step 5: Commit**

```bash
git add frontend/admin-web/src/lib/ownershipTransfer.ts \
        frontend/admin-web/src/lib/labels.ts \
        frontend/admin-web/src/lib/routeRegistry.ts \
        frontend/admin-web/src/lib/pathToCode.ts \
        frontend/admin-web/src/lib/menuIcons.tsx \
        frontend/admin-web/src/pages/modules.tsx \
        frontend/admin-web/src/App.tsx \
        frontend/admin-web/src/pages/OwnershipTransfersPage.tsx \
        frontend/admin-web/src/pages/OwnershipTransferFormPage.tsx
git commit -m "feat(ownership): 权属流转列表页与表单页（多资产远程联动选择 + 附件）"
```

---

## Task 15: 全量门禁与交付

**Files:**

- 无新增；可能 Modify: `docs/api/openapi.yaml`

**Interfaces:**

- Consumes: Task 1–14 的全部产物
- Produces: 可交付状态

---

- [ ] **Step 1: 确认菜单改名迁移已提交**

Run:

```bash
git log --oneline -1 -- backend/src/main/resources/db/migration/V53__rename_deed_menu.sql
git status --porcelain backend/src/main/resources/db/migration/
```

Expected: `V53` 有提交记录；`V53` / `V54` 都不在未跟踪列表里。**若 `V53` 还没提交，先单独提交它再继续** —— 它改的是已存在的菜单目录名，与本模块无依赖，混在一起提交会让 review 难以分辨。

- [ ] **Step 2: 全量后端测试**

Run:

```bash
cd backend && mvn -q test
```

Expected: BUILD SUCCESS。`MigrationChainPostgresTest` 在无 Docker 时显示 skipped（不是 failed）—— 若显示 failed，先看 `flyway.migrate()` 的原始报错。

- [ ] **Step 3: 全量前端门禁**

Run:

```bash
cd frontend && pnpm lint && pnpm check:perm && pnpm check:record && pnpm check:backend && pnpm check:audit && pnpm build
```

Expected: lint 0 error；四条守卫全通过；构建成功。

- [ ] **Step 4: 确认 openapi 文档是否需要同步**

Run:

```bash
cd /Users/lixun/Desktop/projects/asset-management-system && rg -n "asset-transfers|/disposals" docs/api/openapi.yaml | head -20
```

Expected: 两种结果都要按情况处理：
- **若有**（既有同类模块的端点都写在里面）→ 按同样的结构补 7 个端点（`/ownership-transfers` 的 GET / POST、`/{id}` 的 GET / PUT / DELETE、`/{id}/effect` 的 POST、`/asset-options` 的 GET），然后跑 `pnpm api:lint` 确认 0 error；
- **若无**（既有模块的端点也不在里面）→ 不动，在 PR 描述里说明本模块同样未登记。

- [ ] **Step 5: 逐条对照设计文档的验收点**

打开 `docs/superpowers/specs/2026-09-13-ownership-transfer-design.md`，逐条确认：

| 设计条款 | 验证方式 |
|---|---|
| D1 内 / 外方向由公司树同根判定 | `TransferDirectionResolverTest` 6 例 |
| D2 目标公司必须选（内 / 外都要） | `OwnershipTransferServiceTest` 的「必填」用例 |
| D3 外部流转标记 `transferred_out` + `exited` | `effectMarksExternalTransfer` |
| D4 权属类型决定改哪个字段 | `propertyScope*` / `operatingScope*` / `bothScope*` 三个用例 |
| D5 资产下拉按原公司 + 权属类型联动 | 表单页 `fetchAssets` + 权限测试的 `asset-options` 用例 |
| D6 下拉展示「项目 · 分区 · 楼层 · 资产名称」 | 表单页 `assetOptionLabel` |
| D7 变更申请人支持内员 / 外部两种 | 表单页 `ActorField` + 服务端 `applicantName` 分支 |
| D8 附件多类型 | `AttachmentField` + `AttachmentOwner.OWNERSHIP_TRANSFER` |
| D9 金额 2 位小数 | `rejectsTooManyDecimals` |
| D10 审批截止时间纯记录 | 表单页 extra 文案 + 服务端不做任何校验/提醒 |
| D11 与调拨并存 | 两个模块互不引用；`AssetHandoverBuilder` 是唯一的共享代码 |
| 草稿 → 生效两步 | `effectIsIdempotent` + `rejectsUpdateOnCompleted` |
| 生效不可逆、不可重复 | `effectIsIdempotent` |
| 生效重跑校验（跨草稿防重） | `effectRevalidatesBeforeWriting` |
| 在押拦截 | `rejectsMortgagedAsset` |
| 权限 4 码闭环 | `OwnershipTransferPermissionTest` 7 例 |
| 一物一档可追溯 | Task 11 的档案段 + 时间线 |
| 生效发事件 + 通知 | `effectPublishesEventsAndStoresHandover` + `NotificationEventListener` |

- [ ] **Step 6: 人工点一遍（只有真跑起来才能发现的部分）**

Run:

```bash
cd frontend && pnpm dev
```

检查清单：
1. 侧栏「资债权证记录 → 权属流转」出现且点进去不落回首页；
2. 无 `deed.ownershipTransfer:view` 的账号看不到该菜单；
3. 新建：选原公司 → 资产下拉能搜到东西，每项显示四段；改公司时弹出「需要重新选择资产」；
4. 切到「外部流转」时方向提示是否与实际一致；
5. 保存草稿 → 列表出现 `#id` 且状态为「草稿」；
6. 生效 → 到资产详情页确认产权公司已变、档案时间线出现「权属流转 #id」；
7. 外部流转生效后，资产上显示已退出（且不是「已处置」）；
8. 已完成的单在列表里没有「编辑 / 生效 / 删除」按钮。

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore(ownership): 交付前门禁（mvn test / pnpm lint / 四条守卫 / 构建）"
```

---

## Self-Review

**1. Spec 覆盖**

| 设计文档条款 | 落在哪个 task |
|---|---|
| §4.1 主单表 | Task 1 |
| §4.2 明细表 + 原值快照 | Task 1 / Step 9 |
| §4.3 `asset.ownership_status` | Task 1 |
| §4.4 附件复用 `biz_attachment` | Task 2 + Task 3 |
| §4.5 三个字典 | Task 1 |
| §5.1 7 个端点 | Task 10 + Task 12 |
| §5.2 方向判定 | Task 7 |
| §5.3 校验与生效规则 | Task 8 + Task 9 |
| §5.4 租控不动 | Task 9（测试显式断言 `leaseControlStatus` 为 null） |
| §5.6 通知 | Task 5 |
| §5.7 一物一档 | Task 11 |
| §8 权限 | Task 10 |
| §9 前端 | Task 12 / 13 / 14 |

无遗漏。设计文档里明确**不做**的项（审批引擎、数据范围迁移、租控状态流转）在本计划里也没有任何 task 触碰。

**2. 类型一致性抽查**

- `OwnershipTransferService.assetOptions(Long, String, String, long, long)` ← Task 10 控制器按 5 个位置参数调用，一致。
- `AssetHandoverBuilder.build(Asset, Long, Long, Map)` ← Task 9 的 `writeHandover` 与 Task 4 的 `TransferService` 都是 4 参，一致。
- `AttachmentOwner.OWNERSHIP_TRANSFER` ← Task 3 的测试、Task 8 的 `syncAttachments`、Task 12 的 `bizType="ownership_transfer"` 字符串三处口径一致（枚举 `code()` 也是 `ownership_transfer`，但 `bizType` 参数是传给上传接口的来源标记，与宿主无关）。
- 前端 `ownership-transfer` 相关的 path 三处一致：`/ownership-transfers`（列表）、`/ownership-transfers/new`、`/ownership-transfers/:id/edit`。
- 权限码一处定义（`OwnershipTransferController`），前端两处引用（列表页 `CODE` 常量、表单页 `CODE` 常量），值都是 `deed.ownershipTransfer`。

**3. 已知的取舍点（不属于缺陷，写在这里避免被当成疏漏）**

- 草稿不写预留行 → 两个人可同时对同一资产起草，后者生效时会被「重新校验」挡住（Task 9 Step 3 的注释已说明）。这是刻意的，本仓已为「预留永不收口」付过代价。
- `effect` 逐资产发事件 → 一张 50 个资产的单会产生 50 条事件与 50 条通知。通知量大时会话，但先保正确性：按单发一条会让下游每条都要自己拆。
- 「保存并生效」在前端是「先保存再确认生效」，因此生效失败时草稿已存在。这是有意的 —— 草稿可改，比「白填一遍」友好。

