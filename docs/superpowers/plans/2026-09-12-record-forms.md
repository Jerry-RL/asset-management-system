# 资产 / 项目 / 分区后续记录表单（处置 · 接收 · 来源） Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给资产、项目、项目分区三处提供同一套「后续记录」录入能力（处置记录 / 接收信息 / 来源明细），并按主体分别落到 `disposal_order`（资产，含审批与生命周期）与新增的多态记录表（项目 / 分区，纯台账）。

**Architecture:** 新增 `com.ams.modules.record` 模块。四张记录表 + 一张通用附件表全部用 `owner_type + owner_id` 多态定位宿主，**一张表单 = 一个聚合**：`GET/PUT /{owner}/record-sheet` 一次读、一次写、一个事务。三种主体共用同一个 `RecordSheetService`，路由由 `RecordSheetController` 提供三组薄入口。附件不单独开端点，`{fileId, sort}` 内嵌在聚合请求体里做全量 diff。分区删除的两条既有路径统一接入「可删性」守卫并改为软删。

**Tech Stack:** 后端 Spring Boot 3 + MyBatis-Plus + Flyway + JUnit 5 + Mockito + AssertJ（Java 21）；前端 React 18 + TypeScript 5.6 + Vite + antd 6 + Tailwind。

## Global Constraints

- 设计依据：`docs/superpowers/specs/2026-09-12-record-forms-design.md`（下称「设计」）。**实现与本计划、本设计三者冲突时，先停下来改设计，不要在代码里各写一套。**
- 主体与权限码映射（全计划逐字使用）：
  - `asset` → `asset.ledger`
  - `project` → `asset.project`
  - `zone` → `asset.project`
- 动作口径：读用 `view`，**全部写操作（含删除子记录、删除附件）统一用 `update`**。不占用 `*:delete`（设计 §5.2 动作映射）。
- 处置流转权限码逐字为 `operation.disposal:create` / `operation.disposal:update` / `operation.disposal:approve`。
- 审计：所有新写接口一律 `@Audited(module = "record", action = "...")`；处置流转用 `module = "disposal"`。
- 对象级数据范围：**每个** record-sheet 端点先解析 `ownerType + ownerId` → 归属公司 → `rbacService.assertCompanyAccess(...)`，禁止用请求体里的公司字段做校验。
- **软删口径**：所有记录表 / 附件表 / 分区一律只写 `deleted_at`，**不得调用 `deleteById` / `deleteBatchIds`**；所有查询一律显式过滤 `deleted_at IS NULL`。写法有两条硬规则（写错会编译不过）：
  - **过滤**：一律用 `.apply("deleted_at IS NULL")`。**不要**写 `.isNull("deleted_at")` —— `LambdaQueryWrapper` / `LambdaUpdateWrapper` 的 `isNull` 参数类型是 `SFunction`，传字符串编译不过。
  - **写入软删**：一律用 `.setSql("deleted_at = now()")`。
  - 例外的例外：`ProjectZoneMapper.selectActiveById` / `selectActiveInProject` 两个 `@Select` 里直接写 `deleted_at IS NULL`（Task 5 已建）。
  - `ProjectZone` 实体**不新增** `deletedAt` 字段（它是 `POST/PUT /projects/{id}/zones` 的请求体类型，新增字段会让客户端能用 `deletedAt` 软删分区），因此分区**只能**按列名写；`Asset` / `Project` 同理（实体上也没有这个字段）。五个记录实体与 `BizAttachment` 虽有 `deletedAt` 字段，但为避免两套写法混用，本计划统一用上面的 `.apply(...)`。
  - `project_zone.deleted_at` 列**已存在**（`V24`），无需迁移。
- 错误码：参数 / 业务规则错误用 `ErrorCode.BAD_REQUEST`；宿主对象不存在**或对象级越权**一律用 `ErrorCode.NOT_FOUND`（两者状态码与文案必须完全一致，不泄露宿主存在性；功能权限不足仍返回 403，由 `@RequiresPerm` 负责）。宿主一律按「未软删才算存在」判定。
- **不新增菜单码**：`asset.ledger` / `asset.project` / `operation.disposal` 均已在 `V45` 种子中。`PermissionRegistry` 启动时会校验 `@RequiresPerm` 引用的菜单码存在于 `menu` 表，引用不存在的码会让应用启动失败。
- 权限守卫脚本 `scripts/check-perm-invariants.mjs` 的两条硬约束，本计划全程不得违反：
  1. 前端 `perm:` / `perm="..."` 声明的码必须已存在于后端 `@RequiresPerm` 集合（**先加后端注解，再加前端声明**）；
  2. `backend/src/test/java/com/ams/support/RbacFixtures.java`「写权限授予」区块里的码必须已存在于后端 `@RequiresPerm` 集合。
- `biz_disposal_record.amount_wan` 单位为**万元**；`disposal_order.actual_amount` 存量单位未标注，本计划**不做换算**（设计 §4.2 末尾），两者各自独立取值。

### 本机环境限制（影响每个「运行测试」步骤）

- 本机**没有 JDK 与 Maven**（`java -version` → `Unable to locate a Java Runtime`；`mvn` → `command not found`）。因此后端测试**无法在本机执行**，实际执行点是 CI 的 `backend` job（`.github/workflows/ci.yml` → `mvn -B verify`）。
- 本机只能跑前端检查：`pnpm -C frontend lint`、`pnpm -C frontend format:check`、`pnpm -C frontend build`，以及 `node scripts/check-perm-invariants.mjs`（本机有 `node v24.4.0`）。
- 每个后端「Run test」步骤都标注**在 CI 执行**。若执行者本机装有 JDK 21+ 与 Maven，直接运行同一命令即可。

---

## File Structure

### 后端

| 文件 | 职责 | 动作 |
|------|------|------|
| `backend/src/main/resources/db/migration/V46__record_sheets.sql` | 5 张新表 + `disposal_order` 扩列 + 3 组字典种子 | 新建 |
| `backend/src/main/java/com/ams/modules/record/entity/BizAttachment.java` | 通用附件关联行 | 新建 |
| `backend/src/main/java/com/ams/modules/record/entity/ReceiveRecord.java` | 接收信息（1:N） | 新建 |
| `backend/src/main/java/com/ams/modules/record/entity/ReceiveIssue.java` | 遗留问题（接收信息子表） | 新建 |
| `backend/src/main/java/com/ams/modules/record/entity/SourceInfo.java` | 来源明细（1:1） | 新建 |
| `backend/src/main/java/com/ams/modules/record/entity/DisposalRecord.java` | 项目 / 分区处置台账 | 新建 |
| `backend/src/main/java/com/ams/modules/record/mapper/*Mapper.java` | 5 个 `BaseMapper` | 新建 |
| `backend/src/main/java/com/ams/modules/record/RecordOwnerType.java` | 三种主体 + 权限码映射 | 新建 |
| `backend/src/main/java/com/ams/modules/record/AttachmentOwner.java` | 附件直接宿主 + `biz_type` 映射 | 新建 |
| `backend/src/main/java/com/ams/modules/record/dto/*.java` | 聚合读写 DTO（8 个） | 新建 |
| `backend/src/main/java/com/ams/modules/record/service/OwnerResolver.java` | 宿主存在性 + 归属公司 + 数据范围断言 | 新建 |
| `backend/src/main/java/com/ams/modules/record/service/RecordSheetService.java` | 聚合读写 + 全量 diff | 新建 |
| `backend/src/main/java/com/ams/modules/record/service/RecordPresenceChecker.java` | 「该宿主是否已有记录」 | 新建 |
| `backend/src/main/java/com/ams/modules/record/controller/RecordSheetController.java` | 三主体 × (GET, PUT) 共 6 端点 | 新建 |
| `backend/src/main/java/com/ams/platform/security/OwnershipResolver.java` | 新增 `ofZone` | 修改 |
| `backend/src/main/java/com/ams/modules/system/service/FileService.java` | 新增 `viewsByIds`（附件批量回显） | 修改 |
| `backend/src/main/java/com/ams/modules/asset/mapper/ProjectZoneMapper.java` | 新增两个 `@Select` 有效性查询 | 修改 |
| `backend/src/main/java/com/ams/modules/asset/service/AssetService.java` | 分区 CRUD 过滤软删 + 可删性守卫 + `replaceZones` 拒绝 + 软删 | 修改 |
| `backend/src/main/java/com/ams/modules/disposal/controller/DisposalController.java` | 补 `@RequiresPerm` / `@Audited` | 修改 |
| `backend/src/main/java/com/ams/modules/disposal/service/DisposalService.java` | 新增 `listByAsset` + 附件同步 | 修改 |
| `backend/src/main/java/com/ams/modules/asset/controller/AssetController.java` | 新增 `GET /assets/{id}/disposals` | 修改 |
| `backend/src/test/java/com/ams/modules/record/*Test.java` | 服务层 / 权限 / 守卫用例 | 新建 |
| `backend/src/test/java/com/ams/support/RbacFixtures.java` | 补 `operation.disposal` 菜单与授权 | 修改 |
| `backend/src/test/java/com/ams/platform/security/WriteEndpointPermissionTest.java` | 新端点纳入权限闭环 | 修改 |

### 前端

| 文件 | 职责 | 动作 |
|------|------|------|
| `frontend/admin-web/src/components/AttachmentField.tsx` | 受控多附件上传 / 预览 / 删除 / 排序 | 新建 |
| `frontend/admin-web/src/components/ActorField.tsx` | 受控相对人（内员搜索 + 外部手填） | 新建 |
| `frontend/admin-web/src/components/RecordSheetSections.tsx` | 三模块复用组件（吃 `ownerType` + 读写路径） | 新建 |
| `frontend/admin-web/src/lib/recordSheet.ts` | 类型定义 + `loadSheet` / `saveSheet` | 新建 |
| `frontend/admin-web/src/pages/AssetFormPage.tsx` | 改 `Steps`，加第 3 步 | 修改 |
| `frontend/admin-web/src/pages/ProjectFormPage.tsx` | 加第 3 步 + 分区「详情」入口 | 修改 |
| `frontend/admin-web/src/pages/ZoneDetailPage.tsx` | 分区详情 + 记录 | 新建 |
| `frontend/admin-web/src/App.tsx` | 加分区详情路由 | 修改 |
| `docs/api/openapi.yaml` | 补 4 条新路径 | 修改 |

---

## Task 1: 迁移 V46（表 / 扩列 / 字典）

**Files:**
- Create: `backend/src/main/resources/db/migration/V46__record_sheets.sql`
- Test: `backend/src/test/java/com/ams/modules/record/V46MigrationContractTest.java`

**Interfaces:**
- Consumes: 无。
- Produces（后续所有 Task 依赖的表 / 列名，逐字一致）：
  - 表 `biz_attachment(owner_type, owner_id, biz_type, file_id, sort, deleted_at)`
  - 表 `biz_receive_record(owner_type, owner_id, handover_type, doc_name, handover_user_id, handover_user_name, handover_date, remark, deleted_at)`
  - 表 `biz_receive_issue(receive_id, issue_type, description, discoverer_id, discoverer_name, sort, deleted_at)`
  - 表 `biz_source_info(owner_type, owner_id, source_person_id, source_person_name, source_unit, source_date, source_desc, deleted_at)`
  - 表 `biz_disposal_record(owner_type, owner_id, disposal_type, disposal_user_id, disposal_user_name, amount_wan, disposal_date, remark, deleted_at)`
  - `disposal_order` 新增列 `disposal_user_id` / `disposal_user_name` / `disposal_date` / `remark`
  - 字典码 `disposal_type` / `handover_type` / `issue_type`

- [ ] **Step 1: 写契约守卫测试**

新建 `backend/src/test/java/com/ams/modules/record/V46MigrationContractTest.java`：

```java
package com.ams.modules.record;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V46 迁移的契约守卫（设计 §4.1 / §4.2 / 设计 §5.2 的字段口径）。
 *
 * <p>Flyway 在测试 profile 下是关闭的（{@code ams_test} 用 H2、{@code flyway.enabled=false}），
 * 因此**没有**运行期校验能证明这些表真的建出来了。本类改为对迁移文本做结构化断言：
 * 它抓的是「合并冲突时被误删一行」「表名/列名与实体不一致」这类真实事故，
 * 而不是「SQL 语法是否正确」（那需要数据库，交给部署时的 Flyway）。
 *
 * <p>刻意断言的是**列名与字典值**，不是整段 SQL 文本 —— 后者会让任何注释调整都误报。
 */
class V46MigrationContractTest {

    private static final String SQL = readMigration();

    private static String readMigration() {
        try (InputStream in = V46MigrationContractTest.class
                .getResourceAsStream("/db/migration/V46__record_sheets.sql")) {
            assertThat(in).as("V46 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("五张新表都以 IF NOT EXISTS 建出，保证迁移可重复执行")
    void createsAllFiveTablesIdempotently() {
        assertThat(SQL).contains(
                "CREATE TABLE IF NOT EXISTS biz_attachment",
                "CREATE TABLE IF NOT EXISTS biz_receive_record",
                "CREATE TABLE IF NOT EXISTS biz_receive_issue",
                "CREATE TABLE IF NOT EXISTS biz_source_info",
                "CREATE TABLE IF NOT EXISTS biz_disposal_record");
    }

    @Test
    @DisplayName("来源明细的唯一索引是部分索引（WHERE deleted_at IS NULL），软删后可以重录")
    void sourceInfoUniqueIndexIsPartial() {
        assertThat(SQL)
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_biz_source_info_owner")
                .contains("ON biz_source_info (owner_type, owner_id) WHERE deleted_at IS NULL");
    }

    @Test
    @DisplayName("每张记录表都有 deleted_at，否则软删无处落值")
    void everyRecordTableHasDeletedAt() {
        for (String table : new String[] {
                "biz_attachment", "biz_receive_record", "biz_receive_issue",
                "biz_source_info", "biz_disposal_record"}) {
            int start = SQL.indexOf("CREATE TABLE IF NOT EXISTS " + table);
            assertThat(start).as(table + " 必须被创建").isGreaterThanOrEqualTo(0);
            int end = SQL.indexOf(");", start);
            assertThat(SQL.substring(start, end))
                    .as(table + " 必须有 deleted_at 列")
                    .contains("deleted_at");
        }
    }

    @Test
    @DisplayName("disposal_order 扩列：处置人 / 处置日期 / 备注")
    void extendsDisposalOrder() {
        assertThat(SQL)
                .contains("ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS disposal_user_id")
                .contains("ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS disposal_user_name")
                .contains("ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS disposal_date")
                .contains("ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS remark");
    }

    @Test
    @DisplayName("三组字典类型与全部字典项都落种子")
    void seedsThreeDictionaries() {
        assertThat(SQL).contains(
                "('disposal_type', '处置类型'",
                "('handover_type', '交接类型'",
                "('issue_type', '问题类型'");
        assertThat(SQL)
                .contains("('disposal_type', 'sale'")
                .contains("('disposal_type', 'scrap'")
                .contains("('disposal_type', 'transfer'")
                .contains("('handover_type', 'receive'")
                .contains("('handover_type', 'handover'")
                .contains("('handover_type', 'internal'")
                .contains("('issue_type', 'ownership'")
                .contains("('issue_type', 'certificate'")
                .contains("('issue_type', 'facility'")
                .contains("('issue_type', 'arrears'");
    }
}
```

- [ ] **Step 2: 在 CI 执行，确认失败**

Run（CI）: `cd backend && mvn -B test -Dtest=V46MigrationContractTest`
Expected: FAIL —— `V46 迁移文件必须存在` 断言失败（文件还没建）。

- [ ] **Step 3: 写迁移文件**

新建 `backend/src/main/resources/db/migration/V46__record_sheets.sql`：

```sql
-- ============================================================================
-- V46 资产 / 项目 / 分区「后续记录」表单
--   设计见 docs/superpowers/specs/2026-09-12-record-forms-design.md
--
-- 四张记录表 + 一张通用附件表，全部用 owner_type + owner_id 多态定位宿主：
--   owner_type ∈ asset | project | zone            （记录的直接宿主）
--   owner_type ∈ receive_record | receive_issue
--              | source_info | disposal_record
--              | disposal_order                    （附件的直接宿主）
--
-- 可重复执行：表用 IF NOT EXISTS，列用 ADD COLUMN IF NOT EXISTS，
-- 字典按 code / (type_id, value) 幂等。
-- ============================================================================

-- ---------------- 通用附件关联 ----------------
CREATE TABLE IF NOT EXISTS biz_attachment (
    id          BIGSERIAL PRIMARY KEY,
    owner_type  VARCHAR(30)  NOT NULL,          -- 附件的直接宿主类型
    owner_id    BIGINT       NOT NULL,
    biz_type    VARCHAR(40)  NOT NULL,          -- 用途：receive_doc / issue_scene / source_attach / disposal_attach
    file_id     BIGINT       NOT NULL,          -- file_metadata.id
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
-- 部分唯一索引：1:1 约束只作用在「未软删」的行上，软删后允许重新录入
CREATE UNIQUE INDEX IF NOT EXISTS uk_biz_source_info_owner
    ON biz_source_info (owner_type, owner_id) WHERE deleted_at IS NULL;

-- ---------------- 处置台账（项目 / 分区） ----------------
CREATE TABLE IF NOT EXISTS biz_disposal_record (
    id                 BIGSERIAL PRIMARY KEY,
    owner_type         VARCHAR(20)  NOT NULL,   -- 本期仅 project / zone
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

COMMENT ON COLUMN biz_disposal_record.amount_wan IS '处置金额(万元)，2 位小数';
COMMENT ON COLUMN biz_attachment.biz_type IS '附件用途：receive_doc/issue_scene/source_attach/disposal_attach';

-- ---------------- 资产处置：扩展现有表 ----------------
-- 资产的处置仍走 disposal_order（带状态机与资产退出语义），只补表单需要的展示字段；
-- disposal_order.actual_amount 的存量单位未在库中标注，本设计不做换算（设计 §4.2）。
ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS disposal_user_id   BIGINT;
ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS disposal_user_name VARCHAR(100);
ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS disposal_date      DATE;
ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS remark             VARCHAR(500);
COMMENT ON COLUMN disposal_order.disposal_date IS '处置日期';
COMMENT ON COLUMN disposal_order.disposal_user_name IS '处置人姓名快照，内员选择时后端回填';

-- ============================================================================
-- 字典：处置类型 / 交接类型 / 问题类型（挂在「资产管理字典」模块下，沿用 V19/V28 写法）
-- ============================================================================
INSERT INTO sys_dict_type (module_id, code, name, sort)
SELECT m.id, v.code, v.name, v.sort
FROM sys_dict_module m
JOIN (VALUES
    ('disposal_type', '处置类型', 12),
    ('handover_type', '交接类型', 13),
    ('issue_type', '问题类型', 14)
) AS v(code, name, sort) ON TRUE
WHERE m.code = 'asset_management'
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_dict_item (type_id, value, label, sort)
SELECT t.id, v.value, v.label, v.sort
FROM sys_dict_type t
JOIN (VALUES
    ('disposal_type', 'sale', '出售', 1),
    ('disposal_type', 'scrap', '报废', 2),
    ('disposal_type', 'transfer', '划转', 3),
    ('disposal_type', 'other', '其他', 4),
    ('handover_type', 'receive', '接收', 1),
    ('handover_type', 'handover', '移交', 2),
    ('handover_type', 'internal', '内部交接', 3),
    ('issue_type', 'ownership', '权属', 1),
    ('issue_type', 'certificate', '证照', 2),
    ('issue_type', 'facility', '设施', 3),
    ('issue_type', 'arrears', '欠费', 4),
    ('issue_type', 'other', '其他', 5)
) AS v(type_code, value, label, sort) ON v.type_code = t.code
ON CONFLICT (type_id, value) DO NOTHING;
```

- [ ] **Step 4: 在 CI 执行，确认通过**

Run（CI）: `cd backend && mvn -B test -Dtest=V46MigrationContractTest`
Expected: PASS（5 个用例全绿）。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/resources/db/migration/V46__record_sheets.sql \
        backend/src/test/java/com/ams/modules/record/V46MigrationContractTest.java
git commit -m "feat(record): V46 后续记录表 / disposal_order 扩列 / 三组字典种子"
```

---

## Task 2: 实体与 Mapper

**Files:**
- Create: `backend/src/main/java/com/ams/modules/record/entity/BizAttachment.java`
- Create: `backend/src/main/java/com/ams/modules/record/entity/ReceiveRecord.java`
- Create: `backend/src/main/java/com/ams/modules/record/entity/ReceiveIssue.java`
- Create: `backend/src/main/java/com/ams/modules/record/entity/SourceInfo.java`
- Create: `backend/src/main/java/com/ams/modules/record/entity/DisposalRecord.java`
- Create: `backend/src/main/java/com/ams/modules/record/mapper/BizAttachmentMapper.java`
- Create: `backend/src/main/java/com/ams/modules/record/mapper/ReceiveRecordMapper.java`
- Create: `backend/src/main/java/com/ams/modules/record/mapper/ReceiveIssueMapper.java`
- Create: `backend/src/main/java/com/ams/modules/record/mapper/SourceInfoMapper.java`
- Create: `backend/src/main/java/com/ams/modules/record/mapper/DisposalRecordMapper.java`

**Interfaces:**
- Consumes: `com.ams.common.entity.BaseEntity`（提供 `createdAt` / `updatedAt` / `createdBy` / `updatedBy`）。
- Produces（Task 3-9 依赖的实体 getter / setter，由 Lombok `@Data` 生成）：
  - `BizAttachment{id, ownerType, ownerId, bizType, fileId, sort, deletedAt}`
  - `ReceiveRecord{id, ownerType, ownerId, handoverType, docName, handoverUserId, handoverUserName, handoverDate, remark, deletedAt}`
  - `ReceiveIssue{id, receiveId, issueType, description, discovererId, discovererName, sort, deletedAt}`
  - `SourceInfo{id, ownerType, ownerId, sourcePersonId, sourcePersonName, sourceUnit, sourceDate, sourceDesc, deletedAt}`
  - `DisposalRecord{id, ownerType, ownerId, disposalType, disposalUserId, disposalUserName, amountWan, disposalDate, remark, deletedAt}`
  - 五个 `BaseMapper<T>` 子接口。

**为什么实体带 `deletedAt` 而 `ProjectZone` 不带**：这五个实体只用于服务层内部读写，请求体走 DTO（Task 4 定义），因此 `deletedAt` 不构成「客户端可写」的攻击面；`ProjectZone` 同时是 `POST/PUT /projects/{id}/zones` 的请求体类型，加了它客户端就能软删分区。

- [ ] **Step 1: 写实体**

`backend/src/main/java/com/ams/modules/record/entity/BizAttachment.java`：

```java
package com.ams.modules.record.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通用附件关联（{@code biz_attachment}）—— 设计 §4.3。
 *
 * <p>{@code ownerType} 是**附件的直接宿主**（{@code receive_record} / {@code receive_issue} /
 * {@code source_info} / {@code disposal_record} / {@code disposal_order}），不是资产/项目/分区本身：
 * 「交接文件」挂在接收信息上、「现场文件」挂在遗留问题上，删除父记录时附件随之一并软删。
 *
 * <p>{@link #bizType} 与 {@code file_metadata.biz_type} 职责不同：前者是「这笔附件属于哪个字段」，
 * 后者是「上传来源」。两者都不为空，不要合并。
 *
 * <p>软删只写 {@link #deletedAt}：全局 {@code logic-delete-field: deleted} 与实际列
 * {@code deleted_at} 不一致，逻辑删除不会自动生效，查询必须显式过滤 {@code deleted_at IS NULL}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_attachment")
public class BizAttachment extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ownerType;
    private Long ownerId;
    private String bizType;
    private Long fileId;
    private Integer sort;
    private LocalDateTime deletedAt;
}
```

`backend/src/main/java/com/ams/modules/record/entity/ReceiveRecord.java`：

```java
package com.ams.modules.record.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 接收信息（{@code biz_receive_record}）—— 设计 §4.1，1:N。
 *
 * <p>{@link #handoverUserId} 与 {@link #handoverUserName} 是「混合相对人」：内员填 id、姓名由后端
 * 用员工姓名覆盖（姓名快照）；外部人员 id 为空、姓名手填。见设计 §4.4。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_receive_record")
public class ReceiveRecord extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ownerType;
    private Long ownerId;
    private String handoverType;
    private String docName;
    private Long handoverUserId;
    private String handoverUserName;
    private LocalDate handoverDate;
    private String remark;
    private LocalDateTime deletedAt;
}
```

`backend/src/main/java/com/ams/modules/record/entity/ReceiveIssue.java`：

```java
package com.ams.modules.record.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 遗留问题（{@code biz_receive_issue}）—— 设计 §4.1，接收信息的子表。
 *
 * <p>{@link #receiveId} 由服务端按所属接收记录赋值，**不接受客户端传入**，
 * 从结构上杜绝把一条 issue 拼到别的接收记录上（设计 §5.4）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_receive_issue")
public class ReceiveIssue extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long receiveId;
    private String issueType;
    private String description;
    private Long discovererId;
    private String discovererName;
    private Integer sort;
    private LocalDateTime deletedAt;
}
```

`backend/src/main/java/com/ams/modules/record/entity/SourceInfo.java`：

```java
package com.ams.modules.record.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 来源明细（{@code biz_source_info}）—— 设计 §4.1，与宿主 1:1。
 *
 * <p>唯一性由**部分唯一索引**保证（{@code WHERE deleted_at IS NULL}）：软删后允许重新录入，
 * 因此服务层的「取现有行」查询也必须带 {@code deleted_at IS NULL}，否则会读到已删的行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_source_info")
public class SourceInfo extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ownerType;
    private Long ownerId;
    private Long sourcePersonId;
    private String sourcePersonName;
    private String sourceUnit;
    private LocalDate sourceDate;
    private String sourceDesc;
    private LocalDateTime deletedAt;
}
```

`backend/src/main/java/com/ams/modules/record/entity/DisposalRecord.java`：

```java
package com.ams.modules.record.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 处置台账（{@code biz_disposal_record}）—— 设计 §4.2，本期仅项目 / 分区。
 *
 * <p>与资产侧的 {@code disposal_order} 刻意分开：后者带状态机、审批与
 * {@code lifecycle_status=exited} 语义，把项目/分区塞进去会让「处置完成就退出资产」这套
 * 业务规则被套用到没有生命周期的对象上。这里只是登记。
 *
 * <p>{@link #amountWan} 单位为**万元**，与 {@code disposal_order.actual_amount} 不做换算。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_disposal_record")
public class DisposalRecord extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ownerType;
    private Long ownerId;
    private String disposalType;
    private Long disposalUserId;
    private String disposalUserName;
    private BigDecimal amountWan;
    private LocalDate disposalDate;
    private String remark;
    private LocalDateTime deletedAt;
}
```

- [ ] **Step 2: 写 Mapper**

五个文件内容同构，逐个创建：

`backend/src/main/java/com/ams/modules/record/mapper/BizAttachmentMapper.java`：

```java
package com.ams.modules.record.mapper;

import com.ams.modules.record.entity.BizAttachment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BizAttachmentMapper extends BaseMapper<BizAttachment> {
}
```

`backend/src/main/java/com/ams/modules/record/mapper/ReceiveRecordMapper.java`：

```java
package com.ams.modules.record.mapper;

import com.ams.modules.record.entity.ReceiveRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReceiveRecordMapper extends BaseMapper<ReceiveRecord> {
}
```

`backend/src/main/java/com/ams/modules/record/mapper/ReceiveIssueMapper.java`：

```java
package com.ams.modules.record.mapper;

import com.ams.modules.record.entity.ReceiveIssue;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReceiveIssueMapper extends BaseMapper<ReceiveIssue> {
}
```

`backend/src/main/java/com/ams/modules/record/mapper/SourceInfoMapper.java`：

```java
package com.ams.modules.record.mapper;

import com.ams.modules.record.entity.SourceInfo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SourceInfoMapper extends BaseMapper<SourceInfo> {
}
```

`backend/src/main/java/com/ams/modules/record/mapper/DisposalRecordMapper.java`：

```java
package com.ams.modules.record.mapper;

import com.ams.modules.record.entity.DisposalRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DisposalRecordMapper extends BaseMapper<DisposalRecord> {
}
```

- [ ] **Step 3: 在 CI 执行，确认编译通过**

Run（CI）: `cd backend && mvn -B -q compile`
Expected: BUILD SUCCESS（本 Task 无行为，编译通过即是验收）。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/ams/modules/record/
git commit -m "feat(record): 后续记录实体与 Mapper（5 张多态表）"
```

---

## Task 3: 主体与附件的宿主枚举

**Files:**
- Create: `backend/src/main/java/com/ams/modules/record/RecordOwnerType.java`
- Create: `backend/src/main/java/com/ams/modules/record/AttachmentOwner.java`
- Test: `backend/src/test/java/com/ams/modules/record/OwnerEnumTest.java`

**Interfaces:**
- Produces（Task 4-10 逐字依赖）：
  - `enum RecordOwnerType { ASSET, PROJECT, ZONE }`
    - `String code()` → `"asset"` / `"project"` / `"zone"`
    - `String menuCode()` → `"asset.ledger"` / `"asset.project"` / `"asset.project"`
    - `static RecordOwnerType fromCode(String code)` → 未知值抛 `AppException(ErrorCode.BAD_REQUEST)`
    - `static Optional<RecordOwnerType> ofCode(String code)`
  - `enum AttachmentOwner { RECEIVE_RECORD, RECEIVE_ISSUE, SOURCE_INFO, DISPOSAL_RECORD, DISPOSAL_ORDER }`
    - `String code()` → `"receive_record"` / `"receive_issue"` / `"source_info"` / `"disposal_record"` / `"disposal_order"`
    - `String bizType()` → `"receive_doc"` / `"issue_scene"` / `"source_attach"` / `"disposal_attach"` / `"disposal_attach"`

- [ ] **Step 1: 写失败的测试**

新建 `backend/src/test/java/com/ams/modules/record/OwnerEnumTest.java`：

```java
package com.ams.modules.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ams.common.exception.AppException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 宿主枚举的权限码映射（设计 §5.2 / §5.3）。
 *
 * <p>这张映射表是「分区复用 asset.project 权限码、资产用 asset.ledger」这个决策的**唯一落点**：
 * 一旦某个枚举值的 menuCode 写错，对应主体的所有接口会对所有人 403（菜单码不存在时
 * PermissionRegistry 会直接让应用启动失败，这反而是好事）。本用例把映射钉死在测试里，
 * 让改错的人看到的是断言失败而不是线上 403。
 */
class OwnerEnumTest {

    @Test
    @DisplayName("三种主体的 code 与权限码映射")
    void recordOwnerTypeMapsToMenuCode() {
        assertThat(RecordOwnerType.ASSET.code()).isEqualTo("asset");
        assertThat(RecordOwnerType.ASSET.menuCode()).isEqualTo("asset.ledger");
        assertThat(RecordOwnerType.PROJECT.code()).isEqualTo("project");
        assertThat(RecordOwnerType.PROJECT.menuCode()).isEqualTo("asset.project");
        // 分区是项目配置的一部分，刻意复用资产项目权限码，不新增菜单
        assertThat(RecordOwnerType.ZONE.code()).isEqualTo("zone");
        assertThat(RecordOwnerType.ZONE.menuCode()).isEqualTo("asset.project");
    }

    @Test
    @DisplayName("未知宿主 code 拒绝，而不是静默退回某个默认主体")
    void unknownOwnerCodeIsRejected() {
        assertThatThrownBy(() -> RecordOwnerType.fromCode("tenant"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("tenant");
        assertThat(RecordOwnerType.ofCode(null)).isEmpty();
        assertThat(RecordOwnerType.ofCode("asset")).contains(RecordOwnerType.ASSET);
    }

    @Test
    @DisplayName("附件的直接宿主与 biz_type 映射")
    void attachmentOwnerMapsToBizType() {
        assertThat(AttachmentOwner.RECEIVE_RECORD.code()).isEqualTo("receive_record");
        assertThat(AttachmentOwner.RECEIVE_RECORD.bizType()).isEqualTo("receive_doc");
        assertThat(AttachmentOwner.RECEIVE_ISSUE.code()).isEqualTo("receive_issue");
        assertThat(AttachmentOwner.RECEIVE_ISSUE.bizType()).isEqualTo("issue_scene");
        assertThat(AttachmentOwner.SOURCE_INFO.code()).isEqualTo("source_info");
        assertThat(AttachmentOwner.SOURCE_INFO.bizType()).isEqualTo("source_attach");
        assertThat(AttachmentOwner.DISPOSAL_RECORD.code()).isEqualTo("disposal_record");
        assertThat(AttachmentOwner.DISPOSAL_RECORD.bizType()).isEqualTo("disposal_attach");
        assertThat(AttachmentOwner.DISPOSAL_ORDER.code()).isEqualTo("disposal_order");
        assertThat(AttachmentOwner.DISPOSAL_ORDER.bizType()).isEqualTo("disposal_attach");
    }
}
```

- [ ] **Step 2: 在 CI 执行，确认失败**

Run（CI）: `cd backend && mvn -B test -Dtest=OwnerEnumTest`
Expected: FAIL —— 编译错误 `cannot find symbol: class RecordOwnerType`。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/ams/modules/record/RecordOwnerType.java`：

```java
package com.ams.modules.record;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import java.util.Arrays;
import java.util.Optional;

/**
 * 后续记录的**根主体**（设计 §5.2）：记录挂在哪一类对象上，决定权限码与归属解析方式。
 *
 * <p>权限码刻意只有两个：资产走 {@code asset.ledger}，项目与分区共用 {@code asset.project}
 * —— 分区是项目配置的一部分，为它单独造一个菜单码会让权限矩阵多出一行没有实际角色的权限点。
 */
public enum RecordOwnerType {

    ASSET("asset", "asset.ledger"),
    PROJECT("project", "asset.project"),
    ZONE("zone", "asset.project");

    private final String code;
    private final String menuCode;

    RecordOwnerType(String code, String menuCode) {
        this.code = code;
        this.menuCode = menuCode;
    }

    public String code() {
        return code;
    }

    /** 读写该主体记录所需的菜单码（动作由调用点决定：读 view、写 update）。 */
    public String menuCode() {
        return menuCode;
    }

    public static Optional<RecordOwnerType> ofCode(String code) {
        return Arrays.stream(values()).filter(t -> t.code.equals(code)).findFirst();
    }

    /** 解析宿主类型；未知值直接拒绝，不退回默认主体（退回会让越权写在错误的主体上）。 */
    public static RecordOwnerType fromCode(String code) {
        return ofCode(code)
                .orElseThrow(() -> new AppException(
                        ErrorCode.BAD_REQUEST, "不支持的记录归属类型：" + code));
    }
}
```

`backend/src/main/java/com/ams/modules/record/AttachmentOwner.java`：

```java
package com.ams.modules.record;

/**
 * 附件的**直接宿主**（设计 §4.3）—— 与 {@link RecordOwnerType} 刻意分开：
 *
 * <ul>
 *   <li>{@link RecordOwnerType} 决定「权限码 + 归属公司」，粒度是资产 / 项目 / 分区；</li>
 *   <li>{@code AttachmentOwner} 决定「这行附件挂在哪条记录上」，粒度到子实体。</li>
 * </ul>
 *
 * <p>合并成一个枚举会让「接收信息的附件」在权限上被当成一个独立主体，
 * 从而必须先回溯到接收信息、再回溯到资产才能判权限 —— 那正是设计要避免的散落判断。
 *
 * <p>{@link #bizType()} 与 {@code file_metadata.biz_type} 不同：它是「属于哪个字段」。
 */
public enum AttachmentOwner {

    RECEIVE_RECORD("receive_record", "receive_doc"),
    RECEIVE_ISSUE("receive_issue", "issue_scene"),
    SOURCE_INFO("source_info", "source_attach"),
    DISPOSAL_RECORD("disposal_record", "disposal_attach"),
    /** 资产侧处置单：附件的读写权限跟资产走，状态流转另用 operation.disposal。 */
    DISPOSAL_ORDER("disposal_order", "disposal_attach");

    private final String code;
    private final String bizType;

    AttachmentOwner(String code, String bizType) {
        this.code = code;
        this.bizType = bizType;
    }

    public String code() {
        return code;
    }

    public String bizType() {
        return bizType;
    }
}
```

- [ ] **Step 4: 在 CI 执行，确认通过**

Run（CI）: `cd backend && mvn -B test -Dtest=OwnerEnumTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/ams/modules/record/RecordOwnerType.java \
        backend/src/main/java/com/ams/modules/record/AttachmentOwner.java \
        backend/src/test/java/com/ams/modules/record/OwnerEnumTest.java
git commit -m "feat(record): 宿主枚举（主体权限码映射 + 附件 biz_type 映射）"
```

---

## Task 4: 聚合读写 DTO

**Files:**
- Create: `backend/src/main/java/com/ams/modules/record/dto/AttachmentRef.java`
- Create: `backend/src/main/java/com/ams/modules/record/dto/ReceiveInput.java`
- Create: `backend/src/main/java/com/ams/modules/record/dto/IssueInput.java`
- Create: `backend/src/main/java/com/ams/modules/record/dto/SourceInput.java`
- Create: `backend/src/main/java/com/ams/modules/record/dto/DisposalInput.java`
- Create: `backend/src/main/java/com/ams/modules/record/dto/RecordSheetRequest.java`
- Create: `backend/src/main/java/com/ams/modules/record/dto/RecordSheetView.java`
- Create: `backend/src/main/java/com/ams/modules/record/dto/DisposalOrderView.java`
- Test: `backend/src/test/java/com/ams/modules/record/RecordSheetDtoTest.java`

**Interfaces:**
- Produces（Task 5-10 逐字依赖）：
  - `AttachmentRef{Long fileId, Integer sort, String fileName, String url}` —— 写路径只用 `fileId`/`sort`，读路径回填 `fileName`/`url`
  - `ReceiveInput{Long id, String handoverType, String docName, Long handoverUserId, String handoverUserName, LocalDate handoverDate, String remark, List<IssueInput> issues, List<AttachmentRef> attachments}`
  - `IssueInput{Long id, String issueType, String description, Long discovererId, String discovererName, List<AttachmentRef> attachments}`
  - `SourceInput{Long id, Long sourcePersonId, String sourcePersonName, String sourceUnit, LocalDate sourceDate, String sourceDesc, List<AttachmentRef> attachments}`
  - `DisposalInput{Long id, String disposalType, Long disposalUserId, String disposalUserName, BigDecimal amountWan, LocalDate disposalDate, String remark, List<AttachmentRef> attachments}`
  - `RecordSheetRequest{List<ReceiveInput> receives, SourceInput sourceInfo, List<DisposalInput> disposalRecords}`
  - `RecordSheetView{List<ReceiveInput> receives, SourceInput sourceInfo, List<DisposalInput> disposalRecords, List<DisposalOrderView> disposals}`
  - `DisposalOrderView{Long id, String disposalType, Long disposalUserId, String disposalUserName, BigDecimal amountWan, LocalDate disposalDate, String remark, String status, BigDecimal actualAmount, List<AttachmentRef> attachments}`

**设计要点（实现时不要"优化"掉）**：读写复用同一批 Input 类型。读时 `AttachmentRef.fileName` / `url` 被回填，写时被忽略 —— 复用让前端只需要维护一套 TypeScript 类型，也让「表单回显后再保存」天然幂等（回显出来什么就能原样提交回去）。

- [ ] **Step 1: 写失败的测试**

新建 `backend/src/test/java/com/ams/modules/record/RecordSheetDtoTest.java`：

```java
package com.ams.modules.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.ams.modules.record.dto.AttachmentRef;
import com.ams.modules.record.dto.RecordSheetRequest;
import com.ams.modules.record.dto.RecordSheetView;
import com.ams.modules.record.dto.ReceiveInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 聚合 DTO 的序列化契约（设计 §5.2 的请求体形状）。
 *
 * <p>用真实的 {@link ObjectMapper} 而不是断言 getter：这份形状是前端 `lib/recordSheet.ts`
 * 逐字对齐的接口，字段名拼错或日期格式改变都会让前端静默拿到 undefined。
 *
 * <p><strong>必须关掉 {@code WRITE_DATES_AS_TIMESTAMPS}</strong>：Spring Boot 默认就把日期序列化成
 * ISO-8601 字符串（{@code "2026-08-01"}），而裸的 {@code JavaTimeModule} 默认输出
 * {@code [2026,8,1]} 数组。若这里用裸配置，测试会锁死一个**生产不会出现**的格式，
 * 前端照着数组写解析、上线即报错。
 */
class RecordSheetDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    @DisplayName("读视图默认集合非 null，前端可以直接 map 而不必判空")
    void viewCollectionsAreNeverNull() {
        RecordSheetView view = new RecordSheetView();
        assertThat(view.getReceives()).isNotNull().isEmpty();
        assertThat(view.getDisposalRecords()).isNotNull().isEmpty();
        assertThat(view.getDisposals()).isNotNull().isEmpty();
        assertThat(view.getSourceInfo()).isNull();
    }

    @Test
    @DisplayName("请求体默认集合非 null，服务层不必对 null 做分支")
    void requestCollectionsAreNeverNull() {
        RecordSheetRequest request = new RecordSheetRequest();
        assertThat(request.getReceives()).isNotNull().isEmpty();
        assertThat(request.getDisposalRecords()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("序列化字段名与前端约定一致，日期为 yyyy-MM-dd")
    void serializesToAgreedShape() throws Exception {
        ReceiveInput input = new ReceiveInput();
        input.setId(12L);
        input.setHandoverType("receive");
        input.setHandoverUserName("张三");
        input.setHandoverDate(LocalDate.of(2026, 8, 1));
        AttachmentRef ref = new AttachmentRef();
        ref.setFileId(101L);
        ref.setSort(0);
        input.getAttachments().add(ref);

        String json = objectMapper.writeValueAsString(input);

        assertThat(json).contains("\"handoverType\":\"receive\"");
        assertThat(json).contains("\"handoverUserName\":\"张三\"");
        assertThat(json).contains("\"handoverDate\":\"2026-08-01\"");
        assertThat(json).contains("\"fileId\":101");
        assertThat(json).doesNotContain("handover_date");
    }

    @Test
    @DisplayName("反序列化容忍缺省字段：只传要改的字段即可")
    void deserializesPartialPayload() throws Exception {
        RecordSheetRequest request = objectMapper.readValue(
                "{\"receives\":[{\"handoverUserName\":\"李四\"}]}", RecordSheetRequest.class);

        assertThat(request.getReceives()).hasSize(1);
        ReceiveInput first = request.getReceives().get(0);
        assertThat(first.getHandoverUserName()).isEqualTo("李四");
        assertThat(first.getId()).isNull();
        assertThat(first.getAttachments()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("列表字段缺失时反序列化为空列表而不是 null")
    void missingListsBecomeEmpty() throws Exception {
        ReceiveInput input = objectMapper.readValue("{}", ReceiveInput.class);
        assertThat(input.getIssues()).isNotNull().isEmpty();
        assertThat(input.getAttachments()).isNotNull().isEmpty();
        assertThat(input.getIssues()).isInstanceOf(List.class);
    }
}
```

- [ ] **Step 2: 在 CI 执行，确认失败**

Run（CI）: `cd backend && mvn -B test -Dtest=RecordSheetDtoTest`
Expected: FAIL —— 编译错误 `cannot find symbol: class RecordSheetRequest`。

- [ ] **Step 3: 写 DTO**

`backend/src/main/java/com/ams/modules/record/dto/AttachmentRef.java`：

```java
package com.ams.modules.record.dto;

import lombok.Data;

/**
 * 附件引用（设计 §5.2）。
 *
 * <p>读写复用：写路径只读 {@link #fileId} / {@link #sort}，读路径额外回填
 * {@link #fileName} / {@link #url} 供前端预览。文件本体必须先经
 * {@code POST /files/upload} 落库拿到 {@code fileId}。
 */
@Data
public class AttachmentRef {

    /** file_metadata.id。 */
    private Long fileId;

    /** 保序；为空时服务端按数组下标赋值。 */
    private Integer sort;

    /** 读路径回显；写路径忽略。 */
    private String fileName;

    /** 读路径回显（对象存储可访问地址）；写路径忽略。 */
    private String url;
}
```

`backend/src/main/java/com/ams/modules/record/dto/IssueInput.java`：

```java
package com.ams.modules.record.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 遗留问题（接收信息的子项）。设计 §4.1。
 *
 * <p>{@code receiveId} 刻意**不出现在本 DTO 里**：它由服务端按所属接收记录赋值，
 * 客户端无法指定，从而不可能把一条 issue 拼到别的接收记录上（设计 §5.4）。
 */
@Data
public class IssueInput {

    /** 为空表示新增；非空表示更新已存在的行。 */
    private Long id;

    /** 取值见 sys_dict_type.code = issue_type。 */
    private String issueType;

    private String description;

    /** sys_user.id；外部发现人留空，改填 {@link #discovererName}。 */
    private Long discovererId;

    private String discovererName;

    private List<AttachmentRef> attachments = new ArrayList<>();
}
```

`backend/src/main/java/com/ams/modules/record/dto/ReceiveInput.java`：

```java
package com.ams.modules.record.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 接收信息（设计 §4.1）。读写复用，读时附件带 fileName/url。 */
@Data
public class ReceiveInput {

    /** 为空表示新增。 */
    private Long id;

    /** 取值见 sys_dict_type.code = handover_type。 */
    private String handoverType;

    private String docName;

    /** sys_user.id；外部交接人留空。 */
    private Long handoverUserId;

    /** 姓名快照：内员则由服务端用员工姓名覆盖，外部人员为手填值。 */
    private String handoverUserName;

    /** 前端必填、DB 可空（设计 §12.2）。 */
    private LocalDate handoverDate;

    private String remark;

    private List<IssueInput> issues = new ArrayList<>();

    /** 「交接文件」附件。 */
    private List<AttachmentRef> attachments = new ArrayList<>();
}
```

`backend/src/main/java/com/ams/modules/record/dto/SourceInput.java`：

```java
package com.ams.modules.record.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 来源明细（设计 §4.1），与宿主 1:1。读写复用。 */
@Data
public class SourceInput {

    /** 为空表示该宿主还没有来源明细；服务端按 owner 定位，不按 id 定位。 */
    private Long id;

    private Long sourcePersonId;

    /** 姓名快照，规则同 {@link ReceiveInput#getHandoverUserName()}。 */
    private String sourcePersonName;

    /** 自由文本：原产权 / 移交单位多为外部单位，不建字典。 */
    private String sourceUnit;

    private LocalDate sourceDate;

    private String sourceDesc;

    /** 「来源附件」。 */
    private List<AttachmentRef> attachments = new ArrayList<>();
}
```

`backend/src/main/java/com/ams/modules/record/dto/DisposalInput.java`：

```java
package com.ams.modules.record.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 处置台账（设计 §4.2）—— 仅项目 / 分区使用。
 *
 * <p>资产的处置段在 record-sheet 里被忽略（走 {@code disposal_order} 与
 * `POST /disposals` 流程），因此本类型不出现在资产的写入路径上。
 */
@Data
public class DisposalInput {

    private Long id;

    /** 取值见 sys_dict_type.code = disposal_type。 */
    private String disposalType;

    private Long disposalUserId;

    /** 姓名快照，规则同 {@link ReceiveInput#getHandoverUserName()}。 */
    private String disposalUserName;

    /** 处置金额，单位**万元**。 */
    private BigDecimal amountWan;

    private LocalDate disposalDate;

    private String remark;

    private List<AttachmentRef> attachments = new ArrayList<>();
}
```

`backend/src/main/java/com/ams/modules/record/dto/RecordSheetRequest.java`：

```java
package com.ams.modules.record.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 后续记录聚合写请求（设计 §5.2）。
 *
 * <p><strong>三段都是「全量提交」语义</strong>：带 id 的更新、无 id 的新增、**未出现的软删**。
 * 因此前端必须提交完整列表，不能只提交变更项。
 *
 * <p>资产主体：{@link #disposalRecords} 段被服务端忽略（用 {@code disposal_order}）。
 * {@link #sourceInfo} 传 {@code null} 或整体缺失表示不修改。
 */
@Data
public class RecordSheetRequest {

    private List<ReceiveInput> receives = new ArrayList<>();

    private SourceInput sourceInfo;

    private List<DisposalInput> disposalRecords = new ArrayList<>();
}
```

`backend/src/main/java/com/ams/modules/record/dto/DisposalOrderView.java`：

```java
package com.ams.modules.record.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 资产处置单的回显形状（只读）—— 设计 §7.1。
 *
 * <p>与 {@link DisposalInput} 分开而不是合并：{@code disposal_order} 带状态机
 * （{@code draft/approving/.../completed}）与损益字段，把它压成台账形状会让前端
 * 误以为项目/分区也能走审批。前端按 {@code status} 与权限码显隐操作按钮。
 */
@Data
public class DisposalOrderView {

    private Long id;

    private String disposalType;

    private Long disposalUserId;

    private String disposalUserName;

    /** 处置金额（沿用 disposal_order.actual_amount 的原值，**不做万元换算**）。 */
    private BigDecimal amountWan;

    private LocalDate disposalDate;

    private String remark;

    /** draft / approving / rejected / pending_execute / executing / completed。 */
    private String status;

    private BigDecimal actualAmount;

    private List<AttachmentRef> attachments = new ArrayList<>();
}
```

`backend/src/main/java/com/ams/modules/record/dto/RecordSheetView.java`：

```java
package com.ams.modules.record.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 后续记录聚合读视图（设计 §5.2）。
 *
 * <p>{@link #disposals} 只在资产主体下非空（来自 {@code disposal_order}）；
 * {@link #disposalRecords} 只在项目 / 分区下非空。两者**不同时非空**，
 * 前端按 ownerType 决定渲染「只读流程面板」还是「可编辑台账」。
 *
 * <p>集合字段一律初始化，避免前端为「接口返回 null」写防御分支。
 */
@Data
public class RecordSheetView {

    private List<ReceiveInput> receives = new ArrayList<>();

    /** 没有来源明细时为 null（不是空对象），前端据此渲染空表单。 */
    private SourceInput sourceInfo;

    private List<DisposalInput> disposalRecords = new ArrayList<>();

    private List<DisposalOrderView> disposals = new ArrayList<>();
}
```

- [ ] **Step 4: 在 CI 执行，确认通过**

Run（CI）: `cd backend && mvn -B test -Dtest=RecordSheetDtoTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/ams/modules/record/dto/ \
        backend/src/test/java/com/ams/modules/record/RecordSheetDtoTest.java
git commit -m "feat(record): record-sheet 聚合读写 DTO"
```

---

## Task 5: 归属解析（`OwnershipResolver.ofZone` + `OwnerResolver` + 附件批量回显）

**Files:**
- Modify: `backend/src/main/java/com/ams/platform/security/OwnershipResolver.java`（构造器加 `ProjectZoneMapper`，新增 `ofZone`）
- Modify: `backend/src/main/java/com/ams/modules/asset/mapper/ProjectZoneMapper.java`（新增两个 `@Select`）
- Modify: `backend/src/main/java/com/ams/modules/system/service/FileService.java`（新增 `viewsByIds`）
- Create: `backend/src/main/java/com/ams/modules/record/service/OwnerResolver.java`
- Test: `backend/src/test/java/com/ams/modules/record/service/OwnerResolverTest.java`

**Interfaces:**
- Consumes: `RecordOwnerType`（Task 3）、`AssetMapper`、`ProjectMapper`、`ProjectZoneMapper`、`OwnershipResolver`、`RbacService`、`SecurityUtils`。
- Produces（Task 7-9 逐字依赖）：
  - `OwnershipResolver.ofZone(Long zoneId) → Long`
  - `ProjectZoneMapper.selectActiveById(Long zoneId) → ProjectZone`（软删返回 `null`）
  - `ProjectZoneMapper.selectActiveInProject(Long projectId, Long zoneId) → ProjectZone`
  - `FileService.viewsByIds(Collection<Long> fileIds) → Map<Long, Map<String, Object>>`
  - `OwnerResolver.assertAccessible(RecordOwnerType type, Long ownerId) → Long`（返回归属公司；宿主不存在**或越权**均抛 `NOT_FOUND`，两者同码同文案）

**为什么 `assertAccessible` 返回公司 id**：Task 7 的 service 需要它做「同一次请求内一致」的判断（例如新增记录时不额外查一次公司）。返回 `null` 表示归属推导不出来（例如资产没有经营公司），此时按受限账号拒绝处理。

**越权收敛为 404 的实现方式**：`rbacService.assertCompanyAccess(...)` 抛的是 `DATA_SCOPE_FORBIDDEN`（403），那是给别处的调用者用的。record-sheet 这边**不要**直接用它，改用 `RbacService` 上新增的 `boolean canAccessCompany(LoginUser user, Long companyId)`（与 `assertCompanyAccess` 同样的判定，但不抛异常；`user == null` 仍抛 `UNAUTHORIZED`），再由 `OwnerResolver` 在无权时抛 `NOT_FOUND`，且**文案与「宿主不存在」逐字相同**。不要用 try/catch 翻译异常，那会把 `UNAUTHORIZED` 一起吞掉。

- [ ] **Step 1: 写失败的测试**

新建 `backend/src/test/java/com/ams/modules/record/service/OwnerResolverTest.java`：

```java
package com.ams.modules.record.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.ams.modules.record.RecordOwnerType;
import com.ams.platform.security.LoginUser;
import com.ams.platform.security.OwnershipResolver;
import com.ams.platform.security.RbacService;
import com.ams.platform.security.SecurityUtils;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 宿主解析与数据范围断言（设计 §5.3）。
 *
 * <p>四条最容易漏的语义：
 * <ol>
 *   <li>宿主不存在 → 404，而不是「归属公司为 null 于是按无权处理」；</li>
 *   <li><b>越权也返回 404，且与「宿主不存在」的文案完全一致</b> —— 否则受限账号能用
 *       403/404 之差把宿主 id 的存在性当探针探测（设计 §5.3 第 5 条）；</li>
 *   <li>分区必须经 <b>项目</b> 取公司，而不是自己有一列公司；</li>
 *   <li>三种主体都必须<b>未软删</b>才算存在（否则删掉的资产 / 项目 / 分区还能继续挂记录）。</li>
 * </ol>
 */
class OwnerResolverTest {

    private static final Long ZONE_ID = 9L;
    private static final Long PROJECT_ID = 1L;
    private static final Long COMPANY_ID = 2L;

    private final AssetMapper assetMapper = mock(AssetMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final ProjectZoneMapper projectZoneMapper = mock(ProjectZoneMapper.class);
    private final OwnershipResolver ownershipResolver = mock(OwnershipResolver.class);
    private final RbacService rbacService = mock(RbacService.class);

    private final OwnerResolver resolver =
            new OwnerResolver(assetMapper, projectMapper, projectZoneMapper, ownershipResolver, rbacService);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("分区经项目取公司，不做自己的公司列")
    void zoneResolvesCompanyThroughProject() {
        ProjectZone zone = new ProjectZone();
        zone.setId(ZONE_ID);
        zone.setProjectId(PROJECT_ID);
        when(projectZoneMapper.selectActiveById(ZONE_ID)).thenReturn(zone);
        when(ownershipResolver.ofProject(PROJECT_ID)).thenReturn(COMPANY_ID);
        login();

        assertThat(resolver.assertAccessible(RecordOwnerType.ZONE, ZONE_ID)).isEqualTo(COMPANY_ID);

        verify(rbacService).assertCompanyAccess(any(LoginUser.class), eq(COMPANY_ID));
    }

    @Test
    @DisplayName("分区不存在（含已软删）→ 404，且不做数据范围断言")
    void missingZoneIsNotFound() {
        when(projectZoneMapper.selectActiveById(ZONE_ID)).thenReturn(null);
        login();

        assertThatThrownBy(() -> resolver.assertAccessible(RecordOwnerType.ZONE, ZONE_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("项目不存在 → 404")
    void missingProjectIsNotFound() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);
        login();

        assertThatThrownBy(() -> resolver.assertAccessible(RecordOwnerType.PROJECT, PROJECT_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("ownerId 缺失 → 400，不落成一次全表查询")
    void nullOwnerIdIsBadRequest() {
        login();

        assertThatThrownBy(() -> resolver.assertAccessible(RecordOwnerType.ASSET, null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("归属对象");
    }

    @Test
    @DisplayName("资产存在但归属推导不出公司 → 返回 null，仍交给 assertCompanyAccess 判定")
    void assetWithoutCompanyStillGoesThroughScopeCheck() {
        when(assetMapper.selectById(7L)).thenReturn(new com.ams.modules.asset.entity.Asset());
        when(ownershipResolver.ofAsset(7L)).thenReturn(null);
        login();

        assertThat(resolver.assertAccessible(RecordOwnerType.ASSET, 7L)).isNull();

        verify(rbacService).assertCompanyAccess(any(LoginUser.class), eq(null));
    }

    /** 用真实 SecurityContext 而不是桩 SecurityUtils（它是静态入口，桩不住）。 */
    private void login() {
        LoginUser user = LoginUser.builder()
                .userId(9001L)
                .username("record-probe")
                .name("记录权限探针")
                .roles(Set.of("asset_mgr"))
                .permissions(Set.of())
                .dataScope("all")
                .excludedCompanyIds(Set.of())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
```

- [ ] **Step 2: 在 CI 执行，确认失败**

Run（CI）: `cd backend && mvn -B test -Dtest=OwnerResolverTest`
Expected: FAIL —— `cannot find symbol: class OwnerResolver`。

- [ ] **Step 3: 改 `OwnershipResolver`**

在 `OwnershipResolver` 的 import 区加入：

```java
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
```

字段与构造器改为：

```java
    private final AssetMapper assetMapper;
    private final ProjectMapper projectMapper;
    private final ProjectZoneMapper projectZoneMapper;
    private final ContractMapper contractMapper;
    private final VacateOrderMapper vacateOrderMapper;
    private final BillMapper billMapper;
    private final PaymentMapper paymentMapper;
    private final RefundOrderMapper refundOrderMapper;
    private final InvoiceMapper invoiceMapper;

    public OwnershipResolver(
            AssetMapper assetMapper,
            ProjectMapper projectMapper,
            ProjectZoneMapper projectZoneMapper,
            ContractMapper contractMapper,
            VacateOrderMapper vacateOrderMapper,
            BillMapper billMapper,
            PaymentMapper paymentMapper,
            RefundOrderMapper refundOrderMapper,
            InvoiceMapper invoiceMapper) {
        this.assetMapper = assetMapper;
        this.projectMapper = projectMapper;
        this.projectZoneMapper = projectZoneMapper;
        this.contractMapper = contractMapper;
        this.vacateOrderMapper = vacateOrderMapper;
        this.billMapper = billMapper;
        this.paymentMapper = paymentMapper;
        this.refundOrderMapper = refundOrderMapper;
        this.invoiceMapper = invoiceMapper;
    }
```

在 `ofProject` 之后插入：

```java
    /**
     * 分区的归属公司 = 所属项目的公司（分区本身没有公司列）。
     *
     * <p>刻意走 {@link ProjectZoneMapper#selectActiveById}：已软删的分区不应再解析出归属，
     * 否则删掉的分区还能继续被写入记录。
     */
    public Long ofZone(Long zoneId) {
        ProjectZone zone = zoneId == null ? null : projectZoneMapper.selectActiveById(zoneId);
        return zone == null ? null : ofProject(zone.getProjectId());
    }
```

- [ ] **Step 4: 改 `ProjectZoneMapper`**

整体替换为：

```java
package com.ams.modules.asset.mapper;

import com.ams.modules.asset.entity.ProjectZone;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 分区 Mapper。
 *
 * <p>两个 {@code selectActive*} 用显式 SQL 而不是 {@code LambdaQueryWrapper} 过滤：
 * 它们同时承担「存在性判定」职责，写成一句 SQL 才能让「查出来的行」与「判定的行」必然一致，
 * 不会出现「判定用 A 条件、取数用 B 条件」的偏差。{@code ProjectZone} 实体刻意不映射
 * {@code deleted_at}（它是分区写接口的请求体类型），所以过滤只能写在 SQL 里。
 */
@Mapper
public interface ProjectZoneMapper extends BaseMapper<ProjectZone> {

    /** 按 id 取未软删的分区；不存在或已软删返回 {@code null}。 */
    @Select("SELECT * FROM project_zone WHERE id = #{zoneId} AND deleted_at IS NULL")
    ProjectZone selectActiveById(@Param("zoneId") Long zoneId);

    /**
     * 取「属于该项目且未软删」的分区；不存在 / 已软删 / 属于别的项目都返回 {@code null}。
     * 归属校验与取数合并成一次查询，避免「防跨项目写入」这类安全判断散落在两处。
     */
    @Select("SELECT * FROM project_zone WHERE id = #{zoneId} AND project_id = #{projectId} "
            + "AND deleted_at IS NULL")
    ProjectZone selectActiveInProject(@Param("projectId") Long projectId, @Param("zoneId") Long zoneId);
}
```

- [ ] **Step 5: 改 `FileService`**

在 `toView` 之后插入（并在 import 区补 `java.util.Collection`、`java.util.stream.Collectors`）：

```java
    /**
     * 按 fileId 批量取视图（附件回显：{@code url} / {@code fileName}）。
     *
     * <p>不抛异常：附件可能指向一条已被清理的 file_metadata（孤儿文件问题本期不做清理），
     * 回显时跳过即可，不能因为一条坏引用让整个 record-sheet 读不出来。
     */
    public Map<Long, Map<String, Object>> viewsByIds(Collection<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Map.of();
        }
        return fileMetadataMapper.selectBatchIds(fileIds).stream()
                .collect(Collectors.toMap(FileMetadata::getId, this::toView));
    }
```

- [ ] **Step 6: 写 `OwnerResolver`**

新建 `backend/src/main/java/com/ams/modules/record/service/OwnerResolver.java`：

```java
package com.ams.modules.record.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.ams.modules.record.RecordOwnerType;
import com.ams.platform.security.OwnershipResolver;
import com.ams.platform.security.RbacService;
import com.ams.platform.security.SecurityUtils;
import org.springframework.stereotype.Service;

/**
 * 记录宿主解析（设计 §5.3）：把 {@code (ownerType, ownerId)} 变成「可访问的归属公司」。
 *
 * <p>每个 record-sheet 端点都必须先过这里，理由有三：
 * <ol>
 *   <li><b>存在性</b>：宿主不存在时返回 404，而不是安静地写出一条悬空记录
 *       （设计 §7.2 明确禁止）；</li>
 *   <li><b>对象级数据范围</b>：记录表本身没有公司列，只能沿宿主回溯，
 *       再用 {@link RbacService#assertCompanyAccess} 断言（含全局公司切换与角色排除清单）；</li>
 *   <li><b>一致性</b>：三种主体的归属推导口径集中在一处，避免「资产按 A 口径、分区按 B 口径」。</li>
 * </ol>
 *
 * <p>返回的公司在归属推导不出时为 {@code null}：那是既有约定 ——
 * 受限账号被拒、不受限账号放行，不需要在这里另写一套判断（见
 * {@link OwnershipResolver} 的类注释）。
 */
@Service
public class OwnerResolver {

    private final AssetMapper assetMapper;
    private final ProjectMapper projectMapper;
    private final ProjectZoneMapper projectZoneMapper;
    private final OwnershipResolver ownershipResolver;
    private final RbacService rbacService;

    public OwnerResolver(
            AssetMapper assetMapper,
            ProjectMapper projectMapper,
            ProjectZoneMapper projectZoneMapper,
            OwnershipResolver ownershipResolver,
            RbacService rbacService) {
        this.assetMapper = assetMapper;
        this.projectMapper = projectMapper;
        this.projectZoneMapper = projectZoneMapper;
        this.ownershipResolver = ownershipResolver;
        this.rbacService = rbacService;
    }

    /**
     * 断言宿主存在且当前账号有数据范围，返回其归属公司（可能为 null）。
     *
     * @throws AppException 宿主不存在**或对象级越权** → {@code NOT_FOUND}
     *                      （两者同状态码同文案，不泄露宿主存在性）
     */
    public Long assertAccessible(RecordOwnerType type, Long ownerId) {
        if (ownerId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "缺少记录归属对象编号");
        }
        Long companyId = switch (type) {
            case ASSET -> {
                Asset asset = assetMapper.selectById(ownerId);
                require(asset != null, type, ownerId);
                yield ownershipResolver.ofAsset(ownerId);
            }
            case PROJECT -> {
                Project project = projectMapper.selectById(ownerId);
                require(project != null, type, ownerId);
                yield ownershipResolver.ofProject(ownerId);
            }
            case ZONE -> {
                ProjectZone zone = projectZoneMapper.selectActiveById(ownerId);
                require(zone != null, type, ownerId);
                yield ownershipResolver.ofProject(zone.getProjectId());
            }
        };
        rbacService.assertCompanyAccess(SecurityUtils.current(), companyId);
        return companyId;
    }

    private void require(boolean exists, RecordOwnerType type, Long ownerId) {
        if (!exists) {
            throw new AppException(
                    ErrorCode.NOT_FOUND, "记录归属对象不存在：" + type.code() + "#" + ownerId);
        }
    }
}
```

- [ ] **Step 7: 在 CI 执行，确认通过**

Run（CI）: `cd backend && mvn -B test -Dtest=OwnerResolverTest`
Expected: PASS（5 个用例）。

> 若 `mvn -B verify` 因 `OwnershipResolver` 构造器参数变化而失败，检查是否有 Spring 配置类用 `@Bean` 手工构造它 —— 本仓库没有（`grep -rn "new OwnershipResolver(" src` 为空），Spring 会按类型自动注入。

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/java/com/ams/platform/security/OwnershipResolver.java \
        backend/src/main/java/com/ams/modules/asset/mapper/ProjectZoneMapper.java \
        backend/src/main/java/com/ams/modules/system/service/FileService.java \
        backend/src/main/java/com/ams/modules/record/service/OwnerResolver.java \
        backend/src/test/java/com/ams/modules/record/service/OwnerResolverTest.java
git commit -m "feat(record): 宿主解析与数据范围断言（含分区归属与附件批量回显）"
```

---

## Task 6: 分区软删与可删性守卫

**Files:**
- Create: `backend/src/main/java/com/ams/modules/record/service/RecordPresenceChecker.java`
- Modify: `backend/src/main/java/com/ams/modules/asset/service/AssetService.java`（三个分区方法 + `replaceZones`）
- Test: `backend/src/test/java/com/ams/modules/record/service/RecordPresenceCheckerTest.java`
- Test: `backend/src/test/java/com/ams/modules/asset/AssetServiceZoneTest.java`（扩展现有用例）

**Interfaces:**
- Consumes: `ReceiveRecordMapper`、`ReceiveIssueMapper`、`SourceInfoMapper`、`DisposalRecordMapper`（Task 2）、`ProjectZoneMapper`（Task 5）。
- Produces:
  - `RecordPresenceChecker.hasRecords(RecordOwnerType type, Long ownerId) → boolean`
  - `RecordPresenceChecker.hasRecordsForZone(Long zoneId) → boolean`
  - `AssetService.deleteProjectZone` / `createProjectZone` / `updateProjectZone` / `listProjectZones` 行为变化（签名不变）

**行为变化清单（这是本 Task 的验收口径）：**

| 方法 | 现状 | 改成 |
|------|------|------|
| `listProjectZones` | 不过滤软删 | 加 `.apply("deleted_at IS NULL")` |
| `createProjectZone` | 排序 = 现有最大 + 1 | 计算最大值时排除软删行 |
| `updateProjectZone` | `selectById` 判存在 | 改用 `selectActiveInProject` |
| `deleteProjectZone` | 有资产 → 拒绝；否则 `deleteById` | 有资产**或**有记录 → 拒绝；否则软删 |
| `replaceZones` | 未提交的 `deleteBatchIds` + 资产 `zone_id` 静默置空 | 未提交的分区有资产**或**有记录 → **整单拒绝**；否则软删 + 置空资产 `zone_id` |

- [ ] **Step 1: 写失败的测试（守卫）**

新建 `backend/src/test/java/com/ams/modules/record/service/RecordPresenceCheckerTest.java`：

```java
package com.ams.modules.record.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ams.modules.record.RecordOwnerType;
import com.ams.modules.record.mapper.DisposalRecordMapper;
import com.ams.modules.record.mapper.ReceiveIssueMapper;
import com.ams.modules.record.mapper.ReceiveRecordMapper;
import com.ams.modules.record.mapper.SourceInfoMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「分区是否已有后续记录」的判定（设计 §7.3）。
 *
 * <p>只看记录、不看附件：附件总是挂在某条记录或某个字段上，没有脱离记录的孤立附件，
 * 因此「没有记录」等价于「没有附件」。这一点如果理解反了，会让「删掉附件的空记录」
 * 这种场景被判成不可删。
 *
 * <p>用 {@code > 0} 而不是 {@code == 0} 来判定存在性：MyBatis-Plus 的 {@code selectCount}
 * 返回 {@code Long}，桩里的 {@code null} 会让自动拆箱抛 NPE —— 这是本项目里出现过的真实缺陷，
 * 因此实现必须显式对 null 做安全处理。
 */
class RecordPresenceCheckerTest {

    private final ReceiveRecordMapper receiveRecordMapper = mock(ReceiveRecordMapper.class);
    private final ReceiveIssueMapper receiveIssueMapper = mock(ReceiveIssueMapper.class);
    private final SourceInfoMapper sourceInfoMapper = mock(SourceInfoMapper.class);
    private final DisposalRecordMapper disposalRecordMapper = mock(DisposalRecordMapper.class);

    private final RecordPresenceChecker checker = new RecordPresenceChecker(
            receiveRecordMapper, receiveIssueMapper, sourceInfoMapper, disposalRecordMapper);

    @Test
    @DisplayName("四张记录表任一有数据即为「已有记录」")
    void anyRecordTypeMeansPresent() {
        when(receiveRecordMapper.selectCount(any())).thenReturn(0L);
        when(receiveIssueMapper.selectCount(any())).thenReturn(0L);
        when(sourceInfoMapper.selectCount(any())).thenReturn(0L);
        when(disposalRecordMapper.selectCount(any())).thenReturn(1L);

        assertThat(checker.hasRecords(RecordOwnerType.ZONE, 9L)).isTrue();
    }

    @Test
    @DisplayName("四张记录表都为空则为「无记录」")
    void noRowsMeansAbsent() {
        when(receiveRecordMapper.selectCount(any())).thenReturn(0L);
        when(receiveIssueMapper.selectCount(any())).thenReturn(0L);
        when(sourceInfoMapper.selectCount(any())).thenReturn(0L);
        when(disposalRecordMapper.selectCount(any())).thenReturn(0L);

        assertThat(checker.hasRecords(RecordOwnerType.PROJECT, 1L)).isFalse();
    }

    @Test
    @DisplayName("资产主体不查处置台账（资产的处置在 disposal_order，不属于记录表）")
    void assetOwnerSkipsDisposalRecordTable() {
        when(receiveRecordMapper.selectCount(any())).thenReturn(0L);
        when(receiveIssueMapper.selectCount(any())).thenReturn(0L);
        when(sourceInfoMapper.selectCount(any())).thenReturn(0L);

        assertThat(checker.hasRecords(RecordOwnerType.ASSET, 7L)).isFalse();
    }

    @Test
    @DisplayName("计数返回 null（未命中桩）时不抛 NPE，按「无记录」处理")
    void nullCountIsTreatedAsAbsent() {
        when(receiveRecordMapper.selectCount(any())).thenReturn(null);
        when(receiveIssueMapper.selectCount(any())).thenReturn(0L);
        when(sourceInfoMapper.selectCount(any())).thenReturn(0L);
        when(disposalRecordMapper.selectCount(any())).thenReturn(null);

        assertThat(checker.hasRecords(RecordOwnerType.ZONE, 9L)).isFalse();
    }
}
```

- [ ] **Step 2: 写失败的测试（AssetService 分区行为）**

在 `backend/src/test/java/com/ams/modules/asset/AssetServiceZoneTest.java` 中：

1. `@Mock` 区块新增两个桩（`@InjectMocks` 会按构造器顺序注入，`AssetService` 的构造器在 Step 4 会新增这两个参数）：

```java
    @Mock
    private RecordPresenceChecker recordPresenceChecker;
```

> 此时 `AssetService` 构造器还没有这个参数，测试会编译失败 —— 这正是「先写失败测试」的预期。

2. 在类末尾（`inputZone` 之前）新增用例：

```java
    @Test
    @DisplayName("编辑分区：已软删的分区视为不存在（防对已删行继续编辑）")
    void updateZoneOnSoftDeletedZoneIsRejected() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectActiveInProject(PROJECT_ID, ZONE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.updateProjectZone(PROJECT_ID, ZONE_ID, inputZone("A区")))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("分区不存在");
        verify(projectZoneMapper, never()).updateById(any(ProjectZone.class));
    }

    @Test
    @DisplayName("删除分区：有后续记录时拒绝，且提示「后续记录」而不是「资产」")
    void deleteZoneWithRecordsIsRejected() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectActiveInProject(PROJECT_ID, ZONE_ID)).thenReturn(zone(ZONE_ID, 1));
        when(assetMapper.selectCount(any())).thenReturn(0L);
        when(recordPresenceChecker.hasRecordsForZone(ZONE_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteProjectZone(PROJECT_ID, ZONE_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("后续记录");
        verify(projectZoneMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除分区：无资产无记录时写 deleted_at 软删，不调用 deleteById")
    void deleteZoneSoftDeletes() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectActiveInProject(PROJECT_ID, ZONE_ID)).thenReturn(zone(ZONE_ID, 1));
        when(assetMapper.selectCount(any())).thenReturn(0L);
        when(recordPresenceChecker.hasRecordsForZone(ZONE_ID)).thenReturn(false);

        service.deleteProjectZone(PROJECT_ID, ZONE_ID);

        verify(projectZoneMapper, never()).deleteById(anyLong());
        verify(projectZoneMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("项目整体保存：移除「有资产」的分区时整单拒绝，不再静默置空资产归属")
    void replaceZonesRejectsRemovalOfZoneWithAssets() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectList(any())).thenReturn(List.of(zone(ZONE_ID, 1)));
        when(assetMapper.selectCount(any())).thenReturn(2L);
        when(recordPresenceChecker.hasRecordsForZone(ZONE_ID)).thenReturn(false);

        Project project = project(PROJECT_ID);
        ProjectZone removed = null; // 请求体里没有分区 = 移除

        assertThatThrownBy(() -> service.replaceZones(project, List.of()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("2 项资产");
        verify(projectZoneMapper, never()).deleteBatchIds(any());
    }

    @Test
    @DisplayName("项目整体保存：移除「有后续记录」的分区时整单拒绝")
    void replaceZonesRejectsRemovalOfZoneWithRecords() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectList(any())).thenReturn(List.of(zone(ZONE_ID, 1)));
        when(assetMapper.selectCount(any())).thenReturn(0L);
        when(recordPresenceChecker.hasRecordsForZone(ZONE_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.replaceZones(project(PROJECT_ID), List.of()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("后续记录");
    }
```

同时在 import 区补：

```java
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import com.ams.modules.record.service.RecordPresenceChecker;
```

> 现有 `deleteZoneWithAssetsIsRejected` 用例要改成用 `selectActiveInProject` 打桩（原来用的 `selectById`），否则 Step 4 之后它会因为 `selectActiveInProject` 返回 null 而变成「分区不存在」失败 —— 失败原因与用例名不符，是最容易误判的一种回归。

- [ ] **Step 3: 在 CI 执行，确认失败**

Run（CI）: `cd backend && mvn -B test -Dtest='AssetServiceZoneTest+RecordPresenceCheckerTest'`
Expected: FAIL —— `cannot find symbol: class RecordPresenceChecker`，或断言失败（软删 / 拒绝语义未实现）。

- [ ] **Step 4: 写 `RecordPresenceChecker`**

新建 `backend/src/main/java/com/ams/modules/record/service/RecordPresenceChecker.java`：

```java
package com.ams.modules.record.service;

import com.ams.modules.record.RecordOwnerType;
import com.ams.modules.record.mapper.DisposalRecordMapper;
import com.ams.modules.record.mapper.ReceiveIssueMapper;
import com.ams.modules.record.mapper.ReceiveRecordMapper;
import com.ams.modules.record.mapper.SourceInfoMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

/**
 * 「该宿主是否已有后续记录」的判定（设计 §7.3）。
 *
 * <p>抽成独立组件而不是塞进 {@code RecordSheetService}：分区删除守卫在
 * {@code modules.asset} 里，如果让它依赖 {@code RecordSheetService}，就会让
 * 「资产服务依赖记录服务、记录服务又依赖资产 Mapper」变成一个循环依赖的雏形。
 * 这里只依赖四个 Mapper，方向单一。
 *
 * <p><b>只看记录、不看附件</b>：附件必然挂在某条记录上（接收信息 / 遗留问题 / 来源明细）
 * 或某个主体的处置单上，没有脱离记录的孤立附件。所以「没有记录」蕴含「没有附件」。
 *
 * <p>实现里所有计数都走 {@link #present(Long)} 而不是直接比较：MyBatis-Plus 的
 * {@code selectCount} 返回包装类型 {@code Long}，在某些桩 / 驱动下可能为 {@code null}，
 * 直接 {@code > 0} 会 NPE —— 而这里最坏的结果只是「判定为无记录」，不该让整个删除接口崩掉。
 */
@Service
public class RecordPresenceChecker {

    private final ReceiveRecordMapper receiveRecordMapper;
    private final ReceiveIssueMapper receiveIssueMapper;
    private final SourceInfoMapper sourceInfoMapper;
    private final DisposalRecordMapper disposalRecordMapper;

    public RecordPresenceChecker(
            ReceiveRecordMapper receiveRecordMapper,
            ReceiveIssueMapper receiveIssueMapper,
            SourceInfoMapper sourceInfoMapper,
            DisposalRecordMapper disposalRecordMapper) {
        this.receiveRecordMapper = receiveRecordMapper;
        this.receiveIssueMapper = receiveIssueMapper;
        this.sourceInfoMapper = sourceInfoMapper;
        this.disposalRecordMapper = disposalRecordMapper;
    }

    /** 分区是否有后续记录（供分区删除守卫调用）。 */
    public boolean hasRecordsForZone(Long zoneId) {
        return hasRecords(RecordOwnerType.ZONE, zoneId);
    }

    /** 指定主体是否有后续记录。 */
    public boolean hasRecords(RecordOwnerType type, Long ownerId) {
        if (ownerId == null) {
            return false;
        }
        if (present(receiveRecordMapper.selectCount(new LambdaQueryWrapper<com.ams.modules.record.entity.ReceiveRecord>()
                .eq(com.ams.modules.record.entity.ReceiveRecord::getOwnerType, type.code())
                .eq(com.ams.modules.record.entity.ReceiveRecord::getOwnerId, ownerId)
                .apply("deleted_at IS NULL")))) {
            return true;
        }
        // 遗留问题挂在接收信息下，接收信息被软删时其 issue 也一并软删；
        // 但 issue 可能先于父记录被单独删空，所以这里不单独查 issue：
        // 「有 issue」必然「有未删的父接收记录」。
        if (present(sourceInfoMapper.selectCount(new LambdaQueryWrapper<com.ams.modules.record.entity.SourceInfo>()
                .eq(com.ams.modules.record.entity.SourceInfo::getOwnerType, type.code())
                .eq(com.ams.modules.record.entity.SourceInfo::getOwnerId, ownerId)
                .apply("deleted_at IS NULL")))) {
            return true;
        }
        // 资产的处置在 disposal_order（不属于记录表），因此资产主体不查这张表
        return type != RecordOwnerType.ASSET
                && present(disposalRecordMapper.selectCount(new LambdaQueryWrapper<com.ams.modules.record.entity.DisposalRecord>()
                        .eq(com.ams.modules.record.entity.DisposalRecord::getOwnerType, type.code())
                        .eq(com.ams.modules.record.entity.DisposalRecord::getOwnerId, ownerId)
                        .apply("deleted_at IS NULL")));
    }

    private boolean present(Long count) {
        return count != null && count > 0;
    }
}
```

> 上面的全限定名是为了让本计划的代码块可以独立粘贴；实际写进文件时按仓库风格在 import 区引入
> `ReceiveRecord` / `SourceInfo` / `DisposalRecord`，把正文里的全限定名换成短名。
> `receiveIssueMapper` 目前不在 `hasRecords` 中使用（见上面注释），保留注入是为了让
> 「接收记录软删时其 issue 一并软删」这个约束将来有落点 —— 若 lint 报未使用字段，
> 把该字段与对应 import 一并删掉，并在 `RecordSheetService` 的 issue 同步里保持同样口径即可。

- [ ] **Step 5: 改 `AssetService`**

1. import 区补：

```java
import com.ams.modules.record.RecordOwnerType;
import com.ams.modules.record.service.RecordPresenceChecker;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.LocalDateTime;
```

2. 字段与构造器各加一个 `RecordPresenceChecker`（放在 `assetUnitService` 之后）：

```java
    /** 分区删除前的水位检查：有后续记录的分区不许删（设计 §7.3）。 */
    private final RecordPresenceChecker recordPresenceChecker;
```

构造器形参与赋值各加 `RecordPresenceChecker recordPresenceChecker` / `this.recordPresenceChecker = recordPresenceChecker;`。

3. 分区方法整体替换为下面这一组（`requireProjectZone` 与 `activeZoneQuery` 是本 Task 新增的私有辅助）：

```java
    /** 未软删的分区查询条件。ProjectZone 不映射 deleted_at，只能按列名过滤。 */
    private LambdaQueryWrapper<ProjectZone> activeZoneQuery() {
        return new LambdaQueryWrapper<ProjectZone>().apply("deleted_at IS NULL");
    }

    /**
     * 取「属于该项目且未软删」的分区，否则抛「分区不存在」。
     *
     * <p>归属与存在性合并成一次查询：它同时承担防跨项目写入的安全职责，
     * 拆成两步容易出现「判定的行」与「取数的行」不一致。
     */
    private ProjectZone requireProjectZone(Long projectId, Long zoneId) {
        getProject(projectId);
        ProjectZone zone = projectZoneMapper.selectActiveInProject(projectId, zoneId);
        if (zone == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "分区不存在");
        }
        return zone;
    }

    public List<ProjectZone> listProjectZones(Long projectId) {
        getProject(projectId);
        List<ProjectZone> zones = projectZoneMapper.selectList(
                activeZoneQuery().eq(ProjectZone::getProjectId, projectId).orderByAsc(ProjectZone::getSort));
        fillZoneAssetStats(zones);
        return zones;
    }

    @Transactional
    public ProjectZone createProjectZone(Long projectId, ProjectZone zone) {
        getProject(projectId);
        if (zone.getName() == null || zone.getName().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "分区名称不能为空");
        }
        ProjectZone target = new ProjectZone();
        target.setProjectId(projectId);
        target.setName(zone.getName().trim());
        target.setCode(zone.getCode());
        target.setRemark(zone.getRemark());
        target.setSort(zone.getSort() != null ? zone.getSort() : nextZoneSort(projectId));
        target.setAssetCount(0);
        target.setAssetArea(BigDecimal.ZERO);
        projectZoneMapper.insert(target);
        return target;
    }

    /** 追加到末尾：现有（未软删）分区的最大排序 + 1。软删的分区不占位。 */
    private int nextZoneSort(Long projectId) {
        return projectZoneMapper
                .selectList(activeZoneQuery()
                        .eq(ProjectZone::getProjectId, projectId)
                        .select(ProjectZone::getSort))
                .stream()
                .map(ProjectZone::getSort)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .map(max -> max + 1)
                .orElse(0);
    }

    @Transactional
    public ProjectZone updateProjectZone(Long projectId, Long zoneId, ProjectZone zone) {
        ProjectZone existing = requireProjectZone(projectId, zoneId);
        if (zone.getName() != null && zone.getName().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "分区名称不能为空");
        }
        // 只搬允许改的字段：请求体里的 id / projectId / deletedAt 一律忽略（后者实体里也没有）
        if (zone.getName() != null) {
            existing.setName(zone.getName().trim());
        }
        if (zone.getCode() != null) {
            existing.setCode(zone.getCode());
        }
        if (zone.getSort() != null) {
            existing.setSort(zone.getSort());
        }
        if (zone.getRemark() != null) {
            existing.setRemark(zone.getRemark());
        }
        projectZoneMapper.updateById(existing);
        fillZoneAssetStats(List.of(existing));
        return existing;
    }

    /**
     * 删除分区（设计 §7.3）：有资产或有后续记录一律拒绝，否则**软删**。
     *
     * <p>两条拒绝理由分开报，是因为使用者的处理动作不同 —— 有资产要去改资产归属，
     * 有记录要去先处理记录。合并成一句「无法删除」会让使用者无从下手。
     *
     * <p>软删用 {@code setSql} 而不是 {@code set(ProjectZone::getDeletedAt, ...)}：
     * {@code ProjectZone} 实体上**没有** {@code deletedAt} 字段（它是分区接口的请求体类型，
     * 加了客户端就能自己软删分区），所以只能按列名写。
     */
    @Transactional
    public void deleteProjectZone(Long projectId, Long zoneId) {
        ProjectZone zone = requireProjectZone(projectId, zoneId);
        assertZoneRemovable(zone);
        projectZoneMapper.update(null, new LambdaUpdateWrapper<ProjectZone>()
                .setSql("deleted_at = now()")
                .eq(ProjectZone::getId, zoneId)
                .apply("deleted_at IS NULL"));
    }

    /** 分区可删性的唯一判定点：三条删除路径（就地删除 / 项目整体保存 / 项目删除）都调它，保证口径一致。 */
    private void assertZoneRemovable(ProjectZone zone) {
        long assetCount = assetMapper.selectCount(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getZoneId, zone.getId()));
        if (assetCount > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "该分区下有 " + assetCount + " 项资产，无法删除");
        }
        if (recordPresenceChecker.hasRecordsForZone(zone.getId())) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "分区「" + zone.getName() + "」已有后续记录，请先处理后再删除");
        }
    }
```

> `ProjectZone` 上没有 `deletedAt` 字段，所以上面这处（以及本任务其余涉及分区的软删过滤）
> 一律按列名写：**过滤**用 `.apply("deleted_at IS NULL")`，**写入**用
> `setSql("deleted_at = now()")`。不要写 `.isNull("deleted_at")` ——
> `LambdaQueryWrapper` / `LambdaUpdateWrapper` 的 `isNull` 参数类型是 `SFunction`，
> 传字符串**编译不过**。

4. 把 `replaceZones` 中「删除未提交分区 + 置空资产 zone_id」的段落改为：

```java
        // 移除分区前先过可删性守卫：有资产或有后续记录都整单拒绝。
        // 旧实现直接 deleteBatchIds 并把资产 zone_id 静默置空，会无声丢失资产归属，
        // 也会让分区的后续记录变成悬空数据（设计 §7.3）。
        List<Long> removedIds = existing.stream()
                .map(ProjectZone::getId)
                .filter(id -> !keptIds.contains(id))
                .toList();
        for (ProjectZone zone : existing) {
            if (removedIds.contains(zone.getId())) {
                assertZoneRemovable(zone);
            }
        }
        if (!removedIds.isEmpty()) {
            assetMapper.update(null, new LambdaUpdateWrapper<Asset>()
                    .set(Asset::getZoneId, null)
                    .in(Asset::getZoneId, removedIds));
            projectZoneMapper.update(null, new LambdaUpdateWrapper<ProjectZone>()
                    .setSql("deleted_at = now()")
                    .in(ProjectZone::getId, removedIds)
                    .apply("deleted_at IS NULL"));
        }
```

（`keptIds` 是「请求体里出现的分区 id 集合」，在保留/更新的循环里已经可以收集；若现有代码没有这个集合，就在循环里加 `keptIds.add(zone.getId())`。）

同时 `replaceZones` 里读现有分区的 `selectList` 要加 `.apply("deleted_at IS NULL")`。

- [ ] **Step 6: 在 CI 执行，确认通过**

Run（CI）: `cd backend && mvn -B test -Dtest='AssetServiceZoneTest+RecordPresenceCheckerTest'`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/ams/modules/record/service/RecordPresenceChecker.java \
        backend/src/main/java/com/ams/modules/asset/service/AssetService.java \
        backend/src/test/java/com/ams/modules/record/service/RecordPresenceCheckerTest.java \
        backend/src/test/java/com/ams/modules/asset/AssetServiceZoneTest.java
git commit -m "feat(asset): 分区删除统一守卫与软删（两条路径口径一致，不再静默置空资产归属）"
```

---

### Task 6 修复轮（首审 1 Important，已落地 `41cc67d`）

首审结论 Approved，但暴露了两件事，均已在 `101633c..41cc67d` 修复（另有 `b3de7bc` 只对齐注释与设计文案）：

1. **[Important] `replaceZones` 的两条拒绝用例保护不住本 Task 的头号承诺。** 它们只断言「抛异常」+ `verify(projectZoneMapper, never()).deleteBatchIds(any())`，而 `deleteBatchIds` 已从生产代码删除 —— 该断言只防旧实现回流，对「置空资产 `zone_id`」毫无约束：把置空那两行移到守卫之前，两个用例依然全绿。已补：

```java
        verify(assetMapper, never()).update(any(), any());
        verify(projectZoneMapper, never()).update(any(), any());
```

2. **第三条分区删除路径 `AssetService.deleteProject`。** 它 `projectZoneMapper.delete(...)` 把项目下**全部**分区硬删，再硬删项目，只拦「项目下有资产」、**不拦后续记录**，因此可以靠删项目绕过守卫，把分区连同其后续记录一起硬删成孤儿行。已改为：

```java
    @Transactional
    public void deleteProject(Long id) {
        getProject(id);
        Long assetCount = assetMapper.selectCount(
                new LambdaQueryWrapper<Asset>().eq(Asset::getProjectId, id));
        if (assetCount != null && assetCount > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "项目下存在资产，无法删除");
        }
        if (recordPresenceChecker.hasRecords(RecordOwnerType.PROJECT, id)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "项目下已有后续记录，请先处理后再删除");
        }
        for (ProjectZone zone : projectZoneMapper.selectList(activeZoneQuery()
                .eq(ProjectZone::getProjectId, id))) {
            assertZoneRemovable(zone);
        }
        projectZoneMapper.delete(
                new LambdaQueryWrapper<ProjectZone>().eq(ProjectZone::getProjectId, id));
        projectMapper.deleteById(id);
    }
```

**已拍的取舍（不要改成软删）**：`deleteProject` 的分区与项目**保留物理删除**。守卫通过即证明整棵子树既无资产也无记录、是空壳，硬删不可能遗留孤儿行；而把项目改成软删要牵动全部项目读路径（`getProject` / 项目列表 / `AssetService:1040` / `OwnershipResolver` / 看板 / 地图），属于「项目软删」独立专项。这是设计 §7.3 第 3 条「不再保留硬删调用」的**显式例外**，已写进设计 §7.3 与验收标准 7。

三条删除路径（`deleteProjectZone` / `replaceZones` / `deleteProject`）**共用** `assertZoneRemovable`，报错优先级「资产 → 记录」，路径内优先级「项目资产 → 项目记录 → 逐分区」。

---

## Task 7: `RecordSheetService`（聚合读写 + 全量 diff）

**Files:**
- Create: `backend/src/main/java/com/ams/modules/record/service/RecordSheetService.java`
- Test: `backend/src/test/java/com/ams/modules/record/service/RecordSheetServiceTest.java`

**Interfaces:**
- Consumes: Task 2 的五个 Mapper、Task 3 的两个枚举、Task 4 的八个 DTO、`FileService.viewsByIds`、`UserMapper`。
- Consumes（附件归属）：`FileService.assertAttachable(Collection<Long> fileIds)` —— `syncAttachments` 只对**新挂上来的** `fileId` 调它（已挂在该宿主上的旧引用滤掉后再传，见核心不变量 3）。
- Produces（Task 8 / 9 逐字依赖）：
  - `RecordSheetService.read(RecordOwnerType type, Long ownerId) → RecordSheetView`
  - `RecordSheetService.save(RecordOwnerType type, Long ownerId, RecordSheetRequest request) → RecordSheetView`
  - `RecordSheetService.syncOrderAttachments(Long orderId, List<AttachmentRef> refs)`（供 Task 9 的处置单写入复用）
  - `RecordSheetService.orderAttachments(Long orderId) → List<AttachmentRef>`（供 Task 9 回显）

**核心不变量（每个都必须有用例）：**

1. **全量 diff**：带 id → 更新；无 id → 新增；库中存在但未提交 → 软删。
2. **不跨主体**：某条 receive 只影响它自己的 issues；未提交的 receive 连同 issues 与附件一起软删。
3. **服务端赋值 + 新增附件归属校验**：`owner_type` / `owner_id` / `receive_id` / 附件的 `biz_type` 由服务端决定，忽略客户端传值；`sort` 是客户端可传的**排序意图**（设计 §4.3 `sort` 保序、`AttachmentRef.sort` javadoc 写明「为空时服务端按数组下标赋值」），服务端只在为 null 时按下标兜底。新增附件引用时校验**新** `fileId` 的归属（`FileService.assertAttachable`）；已挂在该宿主上的旧引用不重复校验（存量脏引用必须可原样往返）。
4. **姓名快照**：`xxxUserId` 非空时用 `sys_user.name` 覆盖 `xxxUserName`；**交接人 / 发现人 / 处置人**在 id 为空时姓名必填，**来源人可为空**（设计 §4.4）。
5. **asset 忽略处置段**：`type == ASSET` 时完全不碰 `biz_disposal_record`。
6. **来源明细 1:1**：`sourceInfo` 为 null → 不动；传了内容 → upsert 同一行；传空对象（全字段空）→ 软删该行。

- [ ] **Step 1: 写失败的测试**

新建 `backend/src/test/java/com/ams/modules/record/service/RecordSheetServiceTest.java`：

```java
package com.ams.modules.record.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.record.AttachmentOwner;
import com.ams.modules.record.RecordOwnerType;
import com.ams.modules.record.dto.AttachmentRef;
import com.ams.modules.record.dto.DisposalInput;
import com.ams.modules.record.dto.IssueInput;
import com.ams.modules.record.dto.ReceiveInput;
import com.ams.modules.record.dto.RecordSheetRequest;
import com.ams.modules.record.dto.RecordSheetView;
import com.ams.modules.record.dto.SourceInput;
import com.ams.modules.record.entity.BizAttachment;
import com.ams.modules.record.entity.ReceiveIssue;
import com.ams.modules.record.entity.ReceiveRecord;
import com.ams.modules.record.entity.SourceInfo;
import com.ams.modules.record.mapper.BizAttachmentMapper;
import com.ams.modules.record.mapper.DisposalRecordMapper;
import com.ams.modules.record.mapper.ReceiveIssueMapper;
import com.ams.modules.record.mapper.ReceiveRecordMapper;
import com.ams.modules.record.mapper.SourceInfoMapper;
import com.ams.modules.system.service.FileService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 聚合读写的核心语义（设计 §5.2 / §5.4）。
 *
 * <p>本类的桩刻意**不模拟 SQL**：MyBatis-Plus 的 {@code LambdaQueryWrapper} 条件读不出来，
 * 因此凡是「查出来什么」都要在用例里显式桩定，并配一条反向对照 ——
 * 否则「恰好返回空列表」会让断言因为错误的原因通过（这是本仓库夹具文档里点名的坑）。
 *
 * <p>写断言一律用 {@link ArgumentCaptor} 抓实体，检查**真正落库的字段值**，
 * 而不是只 verify 方法被调用：只 verify 调用无法发现「owner_id 被客户端传的值覆盖」这类越权写入。
 */
class RecordSheetServiceTest {

    private static final long ASSET_ID = 7L;
    private static final long ZONE_ID = 9L;

    private final ReceiveRecordMapper receiveRecordMapper = mock(ReceiveRecordMapper.class);
    private final ReceiveIssueMapper receiveIssueMapper = mock(ReceiveIssueMapper.class);
    private final SourceInfoMapper sourceInfoMapper = mock(SourceInfoMapper.class);
    private final DisposalRecordMapper disposalRecordMapper = mock(DisposalRecordMapper.class);
    private final BizAttachmentMapper bizAttachmentMapper = mock(BizAttachmentMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final FileService fileService = mock(FileService.class);

    private final RecordSheetService service = new RecordSheetService(
            receiveRecordMapper, receiveIssueMapper, sourceInfoMapper, disposalRecordMapper,
            bizAttachmentMapper, userMapper, fileService);

    // ---- 全量 diff ----

    @Test
    @DisplayName("接收信息：无 id 的新增行由服务端写入 ownerType/ownerId，忽略客户端传值")
    void newReceiveGetsOwnerFromServer() {
        stubEmptyExisting();
        ReceiveInput input = new ReceiveInput();
        input.setHandoverType("receive");
        input.setHandoverDate(LocalDate.of(2026, 8, 1));

        service.save(RecordOwnerType.ZONE, ZONE_ID, requestWithReceive(input));

        ArgumentCaptor<ReceiveRecord> captor = ArgumentCaptor.forClass(ReceiveRecord.class);
        verify(receiveRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getOwnerType()).isEqualTo("zone");
        assertThat(captor.getValue().getOwnerId()).isEqualTo(ZONE_ID);
        assertThat(captor.getValue().getHandoverDate()).isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    @DisplayName("接收信息：库中未提交的行被软删，绝不 deleteById")
    void unsubmittedReceiveIsSoftDeleted() {
        ReceiveRecord existing = receive(12L);
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existing)));
        when(receiveIssueMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existing)));

        service.save(RecordOwnerType.ZONE, ZONE_ID, new RecordSheetRequest());

        verify(receiveRecordMapper, never()).deleteById(any());
        verify(receiveRecordMapper).update(any(), any());
    }

    @Test
    @DisplayName("遗留问题：receive_id 由服务端按所属接收记录赋值，客户端无法指定")
    void issueReceiveIdComesFromServer() {
        stubEmptyExisting();
        ReceiveInput input = new ReceiveInput();
        input.getIssues().add(issue("土地证未过户"));

        service.save(RecordOwnerType.ZONE, ZONE_ID, requestWithReceive(input));

        ArgumentCaptor<ReceiveIssue> captor = ArgumentCaptor.forClass(ReceiveIssue.class);
        verify(receiveIssueMapper).insert(captor.capture());
        assertThat(captor.getValue().getDescription()).isEqualTo("土地证未过户");
        // 归属来自服务端刚插入的接收记录（桩回填了 id=12），不是任何客户端输入
        assertThat(captor.getValue().getReceiveId()).isEqualTo(12L);
    }

    @Test
    @DisplayName("姓名快照：选了内员则用员工姓名覆盖手填姓名")
    void internalActorNameIsSnapshotted() {
        stubEmptyExisting();
        User user = new User();
        user.setId(8L);
        user.setName("张三");
        when(userMapper.selectById(8L)).thenReturn(user);

        ReceiveInput input = new ReceiveInput();
        input.setHandoverUserId(8L);
        input.setHandoverUserName("随便写的");
        service.save(RecordOwnerType.ZONE, ZONE_ID, requestWithReceive(input));

        ArgumentCaptor<ReceiveRecord> captor = ArgumentCaptor.forClass(ReceiveRecord.class);
        verify(receiveRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getHandoverUserName()).isEqualTo("张三");
    }

    @Test
    @DisplayName("姓名快照：外部人员（无 id）时姓名必填，为空则拒绝")
    void externalActorRequiresName() {
        stubEmptyExisting();
        ReceiveInput input = new ReceiveInput();
        input.setHandoverUserId(null);
        input.setHandoverUserName("   ");

        assertThatThrownBy(() -> service.save(RecordOwnerType.ZONE, ZONE_ID, requestWithReceive(input)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("姓名");
        verify(receiveRecordMapper, never()).insert(any(ReceiveRecord.class));
    }

    @Test
    @DisplayName("姓名快照：选了不存在的员工则拒绝，不写入一个指向空员工的记录")
    void unknownActorIsRejected() {
        stubEmptyExisting();
        when(userMapper.selectById(8L)).thenReturn(null);
        ReceiveInput input = new ReceiveInput();
        input.setHandoverUserId(8L);
        input.setHandoverUserName("张三");

        assertThatThrownBy(() -> service.save(RecordOwnerType.ZONE, ZONE_ID, requestWithReceive(input)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不存在");
    }

    // ---- 来源明细 1:1 ----

    @Test
    @DisplayName("来源明细：已有行时更新同一行，不新增第二行")
    void sourceInfoUpdatesExistingRow() {
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        SourceInfo existing = new SourceInfo();
        existing.setId(31L);
        existing.setOwnerType("zone");
        existing.setOwnerId(ZONE_ID);
        when(sourceInfoMapper.selectOne(any())).thenReturn(existing);

        SourceInput input = new SourceInput();
        input.setSourceUnit("淮安市财政局");
        input.setSourceDate(LocalDate.of(2026, 7, 15));
        RecordSheetRequest request = new RecordSheetRequest();
        request.setSourceInfo(input);
        service.save(RecordOwnerType.ZONE, ZONE_ID, request);

        verify(sourceInfoMapper, never()).insert(any(SourceInfo.class));
        ArgumentCaptor<SourceInfo> captor = ArgumentCaptor.forClass(SourceInfo.class);
        verify(sourceInfoMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(31L);
        assertThat(captor.getValue().getSourceUnit()).isEqualTo("淮安市财政局");
        assertThat(captor.getValue().getOwnerId()).isEqualTo(ZONE_ID);
    }

    @Test
    @DisplayName("来源明细：owner 由服务端覆盖；客户端传的 id 被忽略，新增时不带 id 落库")
    void sourceInfoOwnerIsOverwritten() {
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);

        SourceInput input = new SourceInput();
        input.setId(888L);
        input.setSourceUnit("某单位");
        RecordSheetRequest request = new RecordSheetRequest();
        request.setSourceInfo(input);
        service.save(RecordOwnerType.ZONE, ZONE_ID, request);

        ArgumentCaptor<SourceInfo> captor = ArgumentCaptor.forClass(SourceInfo.class);
        verify(sourceInfoMapper).insert(captor.capture());
        // 客户端传了 id=888，但库里没有这一行 → 服务端按「新增」处理，落库 id 为空
        assertThat(captor.getValue().getId()).isNull();
        assertThat(captor.getValue().getOwnerType()).isEqualTo("zone");
        assertThat(captor.getValue().getOwnerId()).isEqualTo(ZONE_ID);
    }

    // ---- asset 忽略处置段 ----

    @Test
    @DisplayName("资产主体：处置段被完全忽略，不产生 biz_disposal_record")
    void assetIgnoresDisposalSection() {
        stubEmptyExisting();
        RecordSheetRequest request = new RecordSheetRequest();
        DisposalInput disposal = new DisposalInput();
        disposal.setAmountWan(new BigDecimal("1200.50"));
        request.getDisposalRecords().add(disposal);

        service.save(RecordOwnerType.ASSET, ASSET_ID, request);

        verify(disposalRecordMapper, never()).insert(any());
    }

    @Test
    @DisplayName("项目主体：处置段落入台账，amount_wan 原样保存（万元，不换算）")
    void projectKeepsDisposalSection() {
        stubEmptyExisting();
        RecordSheetRequest request = new RecordSheetRequest();
        DisposalInput disposal = new DisposalInput();
        disposal.setDisposalType("sale");
        disposal.setAmountWan(new BigDecimal("1200.50"));
        request.getDisposalRecords().add(disposal);

        service.save(RecordOwnerType.PROJECT, 1L, request);

        ArgumentCaptor<com.ams.modules.record.entity.DisposalRecord> captor =
                ArgumentCaptor.forClass(com.ams.modules.record.entity.DisposalRecord.class);
        verify(disposalRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getAmountWan()).isEqualByComparingTo("1200.50");
        assertThat(captor.getValue().getOwnerType()).isEqualTo("project");
    }

    // ---- 附件 diff ----

    @Test
    @DisplayName("附件：新增时 biz_type 由服务端按宿主决定，忽略任何客户端值")
    void attachmentBizTypeComesFromServer() {
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(receiveIssueMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.insert(any(BizAttachment.class))).thenReturn(1);

        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        SourceInput input = new SourceInput();
        input.setSourceUnit("某单位");
        AttachmentRef ref = new AttachmentRef();
        ref.setFileId(101L);
        input.getAttachments().add(ref);
        RecordSheetRequest request = new RecordSheetRequest();
        request.setSourceInfo(input);
        service.save(RecordOwnerType.ZONE, ZONE_ID, request);

        ArgumentCaptor<BizAttachment> captor = ArgumentCaptor.forClass(BizAttachment.class);
        verify(bizAttachmentMapper).insert(captor.capture());
        assertThat(captor.getValue().getBizType()).isEqualTo(AttachmentOwner.SOURCE_INFO.bizType());
        assertThat(captor.getValue().getOwnerType()).isEqualTo("source_info");
        assertThat(captor.getValue().getFileId()).isEqualTo(101L);
    }

    @Test
    @DisplayName("附件：未提交的关联行被软删，同一 fileId 重复提交不产生第二行")
    void attachmentDiffKeepsExistingFileId() {
        BizAttachment existing = new BizAttachment();
        existing.setId(55L);
        existing.setOwnerType("source_info");
        existing.setOwnerId(31L);
        existing.setFileId(101L);
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existing)));
        SourceInfo source = new SourceInfo();
        source.setId(31L);
        when(sourceInfoMapper.selectOne(any())).thenReturn(source);

        SourceInput input = new SourceInput();
        input.setSourceUnit("某单位");
        AttachmentRef ref = new AttachmentRef();
        ref.setFileId(101L);
        input.getAttachments().add(ref);
        RecordSheetRequest request = new RecordSheetRequest();
        request.setSourceInfo(input);
        service.save(RecordOwnerType.ZONE, ZONE_ID, request);

        verify(bizAttachmentMapper, never()).insert(any(BizAttachment.class));
        verify(bizAttachmentMapper, never()).deleteById(any());
    }

    @Test
    @DisplayName("附件：缺少 fileId 直接拒绝，不落一行指向空文件的关联")
    void attachmentWithoutFileIdIsRejected() {
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());
        SourceInfo source = new SourceInfo();
        source.setId(31L);
        when(sourceInfoMapper.selectOne(any())).thenReturn(source);

        SourceInput input = new SourceInput();
        input.setSourceUnit("某单位");
        input.getAttachments().add(new AttachmentRef());
        RecordSheetRequest request = new RecordSheetRequest();
        request.setSourceInfo(input);

        assertThatThrownBy(() -> service.save(RecordOwnerType.ZONE, ZONE_ID, request))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("附件");
    }

    // ---- 读路径 ----

    @Test
    @DisplayName("读：附件回显 fileName/url，fileId 缺失的文件被跳过而不是让整单失败")
    void readFillsAttachmentViews() {
        ReceiveRecord record = receive(12L);
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(record)));
        when(receiveIssueMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        BizAttachment attachment = new BizAttachment();
        attachment.setId(1L);
        attachment.setOwnerType("receive_record");
        attachment.setOwnerId(12L);
        attachment.setFileId(101L);
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(attachment)));
        when(fileService.viewsByIds(any())).thenReturn(Map.of(101L, Map.of(
                "fileId", 101L, "fileName", "移交清单.pdf", "url", "http://minio/101")));

        RecordSheetView view = service.read(RecordOwnerType.ZONE, ZONE_ID);

        assertThat(view.getReceives()).hasSize(1);
        assertThat(view.getReceives().get(0).getAttachments()).hasSize(1);
        assertThat(view.getReceives().get(0).getAttachments().get(0).getFileName()).isEqualTo("移交清单.pdf");
        assertThat(view.getReceives().get(0).getAttachments().get(0).getUrl()).isEqualTo("http://minio/101");
        assertThat(view.getSourceInfo()).isNull();
    }

    @Test
    @DisplayName("读：资产主体回显 disposal_order 列表，项目主体回显台账")
    void readBranchesByOwnerType() {
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(receiveIssueMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());

        RecordSheetView assetView = service.read(RecordOwnerType.ASSET, ASSET_ID);

        assertThat(assetView.getDisposalRecords()).isEmpty();
        verify(disposalRecordMapper, never()).selectList(any());
    }

    // ---- 辅助 ----

    private void stubEmptyExisting() {
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(receiveIssueMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(receiveRecordMapper.insert(any(ReceiveRecord.class))).thenAnswer(i -> {
            ReceiveRecord row = i.getArgument(0);
            if (row.getId() == null) {
                row.setId(12L);
            }
            return 1;
        });
        when(receiveIssueMapper.insert(any(ReceiveIssue.class))).thenReturn(1);
        when(sourceInfoMapper.insert(any(SourceInfo.class))).thenAnswer(i -> {
            SourceInfo row = i.getArgument(0);
            if (row.getId() == null) {
                row.setId(31L);
            }
            return 1;
        });
    }

    private static RecordSheetRequest requestWithReceive(ReceiveInput input) {
        RecordSheetRequest request = new RecordSheetRequest();
        request.getReceives().add(input);
        return request;
    }

    private static IssueInput issue(String description) {
        IssueInput issue = new IssueInput();
        issue.setDescription(description);
        return issue;
    }

    private static ReceiveRecord receive(long id) {
        ReceiveRecord record = new ReceiveRecord();
        record.setId(id);
        record.setOwnerType("zone");
        record.setOwnerId(ZONE_ID);
        return record;
    }
}
```

- [ ] **Step 2: 在 CI 执行，确认失败**

Run（CI）: `cd backend && mvn -B test -Dtest=RecordSheetServiceTest`
Expected: FAIL —— `cannot find symbol: class RecordSheetService`。

- [ ] **Step 3: 写实现**

新建 `backend/src/main/java/com/ams/modules/record/service/RecordSheetService.java`。文件较长，按下面五个部分依次写全（**不要留 TODO**）：

**（a）依赖与公开方法**

```java
package com.ams.modules.record.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.record.AttachmentOwner;
import com.ams.modules.record.RecordOwnerType;
import com.ams.modules.record.dto.AttachmentRef;
import com.ams.modules.record.dto.DisposalInput;
import com.ams.modules.record.dto.DisposalOrderView;
import com.ams.modules.record.dto.IssueInput;
import com.ams.modules.record.dto.ReceiveInput;
import com.ams.modules.record.dto.RecordSheetRequest;
import com.ams.modules.record.dto.RecordSheetView;
import com.ams.modules.record.dto.SourceInput;
import com.ams.modules.record.entity.BizAttachment;
import com.ams.modules.record.entity.DisposalRecord;
import com.ams.modules.record.entity.ReceiveIssue;
import com.ams.modules.record.entity.ReceiveRecord;
import com.ams.modules.record.entity.SourceInfo;
import com.ams.modules.record.mapper.BizAttachmentMapper;
import com.ams.modules.record.mapper.DisposalRecordMapper;
import com.ams.modules.record.mapper.ReceiveIssueMapper;
import com.ams.modules.record.mapper.ReceiveRecordMapper;
import com.ams.modules.record.mapper.SourceInfoMapper;
import com.ams.modules.system.service.FileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后续记录聚合读写（设计 §5.2 / §5.4）。
 * 三个不变量见计划 Task 7 的说明；实现里每个方法都对应一条。
 */
@Service
public class RecordSheetService {

    private final ReceiveRecordMapper receiveRecordMapper;
    private final ReceiveIssueMapper receiveIssueMapper;
    private final SourceInfoMapper sourceInfoMapper;
    private final DisposalRecordMapper disposalRecordMapper;
    private final BizAttachmentMapper bizAttachmentMapper;
    private final UserMapper userMapper;
    private final FileService fileService;

    public RecordSheetService(
            ReceiveRecordMapper receiveRecordMapper,
            ReceiveIssueMapper receiveIssueMapper,
            SourceInfoMapper sourceInfoMapper,
            DisposalRecordMapper disposalRecordMapper,
            BizAttachmentMapper bizAttachmentMapper,
            UserMapper userMapper,
            FileService fileService) {
        this.receiveRecordMapper = receiveRecordMapper;
        this.receiveIssueMapper = receiveIssueMapper;
        this.sourceInfoMapper = sourceInfoMapper;
        this.disposalRecordMapper = disposalRecordMapper;
        this.bizAttachmentMapper = bizAttachmentMapper;
        this.userMapper = userMapper;
        this.fileService = fileService;
    }

    @Transactional
    public RecordSheetView save(RecordOwnerType type, Long ownerId, RecordSheetRequest request) {
        syncReceives(type, ownerId, request.getReceives());
        syncSourceInfo(type, ownerId, request.getSourceInfo());
        // 资产的处置走 disposal_order（Task 9），这里完全不碰台账表
        if (type != RecordOwnerType.ASSET) {
            syncDisposalRecords(type, ownerId, request.getDisposalRecords());
        }
        return read(type, ownerId);
    }

    public RecordSheetView read(RecordOwnerType type, Long ownerId) {
        RecordSheetView view = new RecordSheetView();
        for (ReceiveRecord record : activeReceives(type, ownerId)) {
            view.getReceives().add(toReceiveInput(record));
        }
        SourceInfo source = findSourceInfo(type, ownerId);
        view.setSourceInfo(source == null ? null : toSourceInput(source));
        if (type == RecordOwnerType.ASSET) {
            view.setDisposalRecords(new ArrayList<>());
        } else {
            for (DisposalRecord record : activeDisposals(type, ownerId)) {
                view.getDisposalRecords().add(toDisposalInput(record));
            }
        }
        return view;
    }
```

**（b）查询（一切读取都显式排除软删）**

```java
    private List<ReceiveRecord> activeReceives(RecordOwnerType type, Long ownerId) {
        return receiveRecordMapper.selectList(notDeleted(new LambdaQueryWrapper<ReceiveRecord>()
                .eq(ReceiveRecord::getOwnerType, type.code())
                .eq(ReceiveRecord::getOwnerId, ownerId))
                .orderByAsc(ReceiveRecord::getId));
    }

    private SourceInfo findSourceInfo(RecordOwnerType type, Long ownerId) {
        return sourceInfoMapper.selectOne(notDeleted(new LambdaQueryWrapper<SourceInfo>()
                .eq(SourceInfo::getOwnerType, type.code())
                .eq(SourceInfo::getOwnerId, ownerId)));
    }

    private List<DisposalRecord> activeDisposals(RecordOwnerType type, Long ownerId) {
        return disposalRecordMapper.selectList(notDeleted(new LambdaQueryWrapper<DisposalRecord>()
                .eq(DisposalRecord::getOwnerType, type.code())
                .eq(DisposalRecord::getOwnerId, ownerId))
                .orderByAsc(DisposalRecord::getId));
    }

    private List<ReceiveIssue> activeIssues(Long receiveId) {
        return receiveIssueMapper.selectList(notDeleted(new LambdaQueryWrapper<ReceiveIssue>()
                .eq(ReceiveIssue::getReceiveId, receiveId))
                .orderByAsc(ReceiveIssue::getSort)
                .orderByAsc(ReceiveIssue::getId));
    }

    private List<BizAttachment> activeAttachments(AttachmentOwner owner, Long ownerId) {
        return bizAttachmentMapper.selectList(notDeleted(new LambdaQueryWrapper<BizAttachment>()
                .eq(BizAttachment::getOwnerType, owner.code())
                .eq(BizAttachment::getOwnerId, ownerId))
                .orderByAsc(BizAttachment::getSort)
                .orderByAsc(BizAttachment::getId));
    }

    /**
     * 显式排除软删行。全局 {@code logic-delete-field: deleted} 与实际的 {@code deleted_at}
     * 列口径不一致，逻辑删除**不会**自动生效，每一条查询都必须自己带上这个条件。
     * 收敛成一个方法，是为了让「漏写」只可能发生在这一处。
     */
    private <T> LambdaQueryWrapper<T> notDeleted(LambdaQueryWrapper<T> wrapper) {
        return wrapper.apply("deleted_at IS NULL");
    }
```

**（c）接收信息与遗留问题的 diff**

```java
    private void syncReceives(RecordOwnerType type, Long ownerId, List<ReceiveInput> inputs) {
        List<ReceiveInput> incoming = inputs == null ? List.of() : inputs;
        Map<Long, ReceiveRecord> existing = activeReceives(type, ownerId).stream()
                .collect(Collectors.toMap(ReceiveRecord::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Set<Long> kept = new LinkedHashSet<>();
        for (ReceiveInput input : incoming) {
            ReceiveRecord target;
            if (input.getId() != null && existing.containsKey(input.getId())) {
                target = existing.get(input.getId());
                applyReceive(input, target);
                receiveRecordMapper.updateById(target);
            } else {
                target = new ReceiveRecord();
                target.setOwnerType(type.code());
                target.setOwnerId(ownerId);
                applyReceive(input, target);
                receiveRecordMapper.insert(target);
            }
            kept.add(target.getId());
            syncIssues(target.getId(), input.getIssues());
            syncAttachments(AttachmentOwner.RECEIVE_RECORD, target.getId(), input.getAttachments());
        }
        for (Long id : existing.keySet()) {
            if (!kept.contains(id)) {
                softDeleteReceive(id);
            }
        }
    }

    private void applyReceive(ReceiveInput input, ReceiveRecord target) {
        target.setHandoverType(input.getHandoverType());
        target.setDocName(input.getDocName());
        target.setHandoverUserId(input.getHandoverUserId());
        target.setHandoverUserName(actorName(input.getHandoverUserId(), input.getHandoverUserName()));
        target.setHandoverDate(input.getHandoverDate());
        target.setRemark(input.getRemark());
    }

    /** 软删接收记录：连同其遗留问题与附件。附件必须一起走，否则会留下孤儿关联。 */
    private void softDeleteReceive(Long receiveId) {
        for (ReceiveIssue issue : activeIssues(receiveId)) {
            syncAttachments(AttachmentOwner.RECEIVE_ISSUE, issue.getId(), List.of());
            softDeleteIssue(issue.getId());
        }
        syncAttachments(AttachmentOwner.RECEIVE_RECORD, receiveId, List.of());
        markDeleted(receiveRecordMapper, ReceiveRecord::getId, receiveId);
    }

    private void syncIssues(Long receiveId, List<IssueInput> inputs) {
        List<IssueInput> incoming = inputs == null ? List.of() : inputs;
        Map<Long, ReceiveIssue> existing = activeIssues(receiveId).stream()
                .collect(Collectors.toMap(ReceiveIssue::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Set<Long> kept = new LinkedHashSet<>();
        int index = 0;
        for (IssueInput input : incoming) {
            ReceiveIssue target;
            if (input.getId() != null && existing.containsKey(input.getId())) {
                target = existing.get(input.getId());
                applyIssue(input, target, index);
                receiveIssueMapper.updateById(target);
            } else {
                target = new ReceiveIssue();
                // receive_id 一律由服务端赋值：客户端无法把一条问题拼到别的接收记录上
                target.setReceiveId(receiveId);
                applyIssue(input, target, index);
                receiveIssueMapper.insert(target);
            }
            kept.add(target.getId());
            syncAttachments(AttachmentOwner.RECEIVE_ISSUE, target.getId(), input.getAttachments());
            index++;
        }
        for (Long id : existing.keySet()) {
            if (!kept.contains(id)) {
                syncAttachments(AttachmentOwner.RECEIVE_ISSUE, id, List.of());
                softDeleteIssue(id);
            }
        }
    }

    private void applyIssue(IssueInput input, ReceiveIssue target, int index) {
        target.setIssueType(input.getIssueType());
        target.setDescription(input.getDescription());
        target.setDiscovererId(input.getDiscovererId());
        target.setDiscovererName(actorName(input.getDiscovererId(), input.getDiscovererName()));
        target.setSort(index);
    }

    private void softDeleteIssue(Long id) {
        markDeleted(receiveIssueMapper, ReceiveIssue::getId, id);
    }
```

**（d）来源明细（1:1 upsert）与处置台账**

```java
    private void syncSourceInfo(RecordOwnerType type, Long ownerId, SourceInput input) {
        if (input == null) {
            return;
        }
        SourceInfo existing = findSourceInfo(type, ownerId);
        if (isEmptySource(input)) {
            // 全字段为空视为「清空」：软删该行，附件一并软删
            if (existing != null) {
                syncAttachments(AttachmentOwner.SOURCE_INFO, existing.getId(), List.of());
                markDeleted(sourceInfoMapper, SourceInfo::getId, existing.getId());
            }
            return;
        }
        SourceInfo target = existing != null ? existing : new SourceInfo();
        target.setOwnerType(type.code());
        target.setOwnerId(ownerId);
        target.setSourcePersonId(input.getSourcePersonId());
        target.setSourcePersonName(actorName(input.getSourcePersonId(), input.getSourcePersonName(), true));
        target.setSourceUnit(input.getSourceUnit());
        target.setSourceDate(input.getSourceDate());
        target.setSourceDesc(input.getSourceDesc());
        if (existing != null) {
            sourceInfoMapper.updateById(target);
        } else {
            sourceInfoMapper.insert(target);
        }
        syncAttachments(AttachmentOwner.SOURCE_INFO, target.getId(), input.getAttachments());
    }

    private boolean isEmptySource(SourceInput input) {
        return input.getSourcePersonId() == null
                && blank(input.getSourcePersonName())
                && blank(input.getSourceUnit())
                && blank(input.getSourceDesc())
                && input.getSourceDate() == null
                && (input.getAttachments() == null || input.getAttachments().isEmpty());
    }

    private void syncDisposalRecords(RecordOwnerType type, Long ownerId, List<DisposalInput> inputs) {
        List<DisposalInput> incoming = inputs == null ? List.of() : inputs;
        Map<Long, DisposalRecord> existing = activeDisposals(type, ownerId).stream()
                .collect(Collectors.toMap(DisposalRecord::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Set<Long> kept = new LinkedHashSet<>();
        for (DisposalInput input : incoming) {
            DisposalRecord target;
            if (input.getId() != null && existing.containsKey(input.getId())) {
                target = existing.get(input.getId());
                applyDisposal(input, target);
                disposalRecordMapper.updateById(target);
            } else {
                target = new DisposalRecord();
                target.setOwnerType(type.code());
                target.setOwnerId(ownerId);
                applyDisposal(input, target);
                disposalRecordMapper.insert(target);
            }
            kept.add(target.getId());
            syncAttachments(AttachmentOwner.DISPOSAL_RECORD, target.getId(), input.getAttachments());
        }
        for (Long id : existing.keySet()) {
            if (!kept.contains(id)) {
                syncAttachments(AttachmentOwner.DISPOSAL_RECORD, id, List.of());
                markDeleted(disposalRecordMapper, DisposalRecord::getId, id);
            }
        }
    }

    private void applyDisposal(DisposalInput input, DisposalRecord target) {
        target.setDisposalType(input.getDisposalType());
        target.setDisposalUserId(input.getDisposalUserId());
        target.setDisposalUserName(actorName(input.getDisposalUserId(), input.getDisposalUserName()));
        target.setAmountWan(input.getAmountWan());
        target.setDisposalDate(input.getDisposalDate());
        target.setRemark(input.getRemark());
    }
```

**（e）附件 diff、姓名快照、软删工具、实体 ↔ DTO 映射**

```java
    /**
     * 附件全量 diff（设计 §4.3）。以 {@code fileId} 为身份：同一宿主下重复提交同一个文件
     * 只保留一行，未提交的行软删。
     */
    void syncAttachments(AttachmentOwner owner, Long ownerId, List<AttachmentRef> refs) {
        if (ownerId == null) {
            // 宿主还没落库（新增失败）时不能写附件，否则会造出 owner_id 为 null 的孤儿行
            return;
        }
        List<AttachmentRef> incoming = refs == null ? List.of() : refs;
        Map<Long, BizAttachment> existing = activeAttachments(owner, ownerId).stream()
                .collect(Collectors.toMap(BizAttachment::getFileId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Set<Long> kept = new LinkedHashSet<>();
        int index = 0;
        for (AttachmentRef ref : incoming) {
            if (ref == null || ref.getFileId() == null) {
                throw new AppException(ErrorCode.BAD_REQUEST, "附件缺少 fileId，请先调用 /files/upload");
            }
            int sort = ref.getSort() == null ? index : ref.getSort();
            BizAttachment row = existing.get(ref.getFileId());
            if (row == null) {
                row = new BizAttachment();
                row.setOwnerType(owner.code());
                row.setOwnerId(ownerId);
                row.setBizType(owner.bizType());
                row.setFileId(ref.getFileId());
                row.setSort(sort);
                bizAttachmentMapper.insert(row);
            } else {
                row.setSort(sort);
                bizAttachmentMapper.updateById(row);
            }
            kept.add(ref.getFileId());
            index++;
        }
        for (BizAttachment row : existing.values()) {
            if (!kept.contains(row.getFileId())) {
                markDeleted(bizAttachmentMapper, BizAttachment::getId, row.getId());
            }
        }
    }

    /** 供 Task 9 的处置单写入复用：资产侧处置单的附件与记录表共用一条 diff 逻辑。 */
    public void syncOrderAttachments(Long orderId, List<AttachmentRef> refs) {
        syncAttachments(AttachmentOwner.DISPOSAL_ORDER, orderId, refs);
    }

    /** 供 Task 9 回显处置单附件。 */
    public List<AttachmentRef> orderAttachments(Long orderId) {
        return toAttachmentRefs(AttachmentOwner.DISPOSAL_ORDER, orderId);
    }

    /** 读路径：内员姓名用快照原样返回，不再回查 sys_user（历史凭证要保留当时的姓名）。 */
    private String actorName(Long userId, String providedName) {
        return actorName(userId, providedName, false);
    }

    /**
     * @param optional true 表示该字段非必填（来源人可以为空）；false 表示必填
     */
    private String actorName(Long userId, String providedName, boolean optional) {
        if (userId == null) {
            if (blank(providedName)) {
                if (optional) {
                    return null;
                }
                throw new AppException(ErrorCode.BAD_REQUEST, "请选择系统内人员或填写人员姓名");
            }
            return providedName.trim();
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "所选人员不存在：" + userId);
        }
        return user.getName();
    }

    /** 软删的唯一落点：只写 deleted_at，绝不 deleteById（设计 §4.1）。 */
    private <T> void markDeleted(BaseMapper<T> mapper, SFunction<T, ?> idGetter, Long id) {
        mapper.update(null, new LambdaUpdateWrapper<T>()
                .setSql("deleted_at = now()")
                .eq(idGetter, id)
                .apply("deleted_at IS NULL"));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ReceiveInput toReceiveInput(ReceiveRecord record) {
        ReceiveInput input = new ReceiveInput();
        input.setId(record.getId());
        input.setHandoverType(record.getHandoverType());
        input.setDocName(record.getDocName());
        input.setHandoverUserId(record.getHandoverUserId());
        input.setHandoverUserName(record.getHandoverUserName());
        input.setHandoverDate(record.getHandoverDate());
        input.setRemark(record.getRemark());
        for (ReceiveIssue issue : activeIssues(record.getId())) {
            IssueInput issueInput = new IssueInput();
            issueInput.setId(issue.getId());
            issueInput.setIssueType(issue.getIssueType());
            issueInput.setDescription(issue.getDescription());
            issueInput.setDiscovererId(issue.getDiscovererId());
            issueInput.setDiscovererName(issue.getDiscovererName());
            issueInput.setAttachments(toAttachmentRefs(AttachmentOwner.RECEIVE_ISSUE, issue.getId()));
            input.getIssues().add(issueInput);
        }
        input.setAttachments(toAttachmentRefs(AttachmentOwner.RECEIVE_RECORD, record.getId()));
        return input;
    }

    private SourceInput toSourceInput(SourceInfo source) {
        SourceInput input = new SourceInput();
        input.setId(source.getId());
        input.setSourcePersonId(source.getSourcePersonId());
        input.setSourcePersonName(source.getSourcePersonName());
        input.setSourceUnit(source.getSourceUnit());
        input.setSourceDate(source.getSourceDate());
        input.setSourceDesc(source.getSourceDesc());
        input.setAttachments(toAttachmentRefs(AttachmentOwner.SOURCE_INFO, source.getId()));
        return input;
    }

    private DisposalInput toDisposalInput(DisposalRecord record) {
        DisposalInput input = new DisposalInput();
        input.setId(record.getId());
        input.setDisposalType(record.getDisposalType());
        input.setDisposalUserId(record.getDisposalUserId());
        input.setDisposalUserName(record.getDisposalUserName());
        input.setAmountWan(record.getAmountWan());
        input.setDisposalDate(record.getDisposalDate());
        input.setRemark(record.getRemark());
        input.setAttachments(toAttachmentRefs(AttachmentOwner.DISPOSAL_RECORD, record.getId()));
        return input;
    }

    /** 批量回显附件：一次查全部 fileId，避免每条附件一次查询。 */
    List<AttachmentRef> toAttachmentRefs(AttachmentOwner owner, Long ownerId) {
        List<BizAttachment> rows = activeAttachments(owner, ownerId);
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, Map<String, Object>> views =
                fileService.viewsByIds(rows.stream().map(BizAttachment::getFileId).toList());
        List<AttachmentRef> refs = new ArrayList<>();
        for (BizAttachment row : rows) {
            AttachmentRef ref = new AttachmentRef();
            ref.setFileId(row.getFileId());
            ref.setSort(row.getSort());
            Map<String, Object> view = views.get(row.getFileId());
            if (view != null) {
                ref.setFileName((String) view.get("fileName"));
                ref.setUrl((String) view.get("url"));
            }
            refs.add(ref);
        }
        return refs;
    }
}
```

> 若 `IssueInput` 需要 `getIssues()` 之外的 `attachments` 排序，`toAttachmentRefs` 已按 `sort` 排序（查询里 `orderByAsc`），不要在前端再排一遍。

- [ ] **Step 4: 在 CI 执行，确认通过**

Run（CI）: `cd backend && mvn -B test -Dtest=RecordSheetServiceTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/ams/modules/record/service/RecordSheetService.java \
        backend/src/test/java/com/ams/modules/record/service/RecordSheetServiceTest.java
git commit -m "feat(record): 聚合读写服务（三模块全量 diff + 附件 + 姓名快照）"
```

---

## Task 8: `RecordSheetController`（三主体 × 读写）与权限闭环

**Files:**
- Create: `backend/src/main/java/com/ams/modules/record/controller/RecordSheetController.java`
- Test: `backend/src/test/java/com/ams/modules/record/controller/RecordSheetEndpointPermissionTest.java`

**Interfaces:**
- Consumes: `RecordSheetService.read/save`（Task 7）、`OwnerResolver.assertAccessible`（Task 5）、`RecordOwnerType`（Task 3）、`ApiResponse` / `TraceIdUtil`。
- Produces（前端 `lib/recordSheet.ts` 逐字依赖的 6 条路径）：
  - `GET|PUT /api/v1/assets/{assetId}/record-sheet`
  - `GET|PUT /api/v1/projects/{projectId}/record-sheet`
  - `GET|PUT /api/v1/projects/{projectId}/zones/{zoneId}/record-sheet`

- [ ] **Step 1: 写失败的测试**

新建 `backend/src/test/java/com/ams/modules/record/controller/RecordSheetEndpointPermissionTest.java`：

```java
package com.ams.modules.record.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ams.common.exception.GlobalExceptionHandler;
import com.ams.modules.record.RecordOwnerType;
import com.ams.modules.record.dto.RecordSheetRequest;
import com.ams.modules.record.dto.RecordSheetView;
import com.ams.modules.record.service.OwnerResolver;
import com.ams.modules.record.service.RecordSheetService;
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
 * record-sheet 六个端点的权限闭环（设计 §5.2 / §5.3，验收第 8 条）。
 *
 * <p>结构与 {@code AssetZoneEndpointPermissionTest} 一致：真实控制器 + 真实拦截器，
 * 权限码由 {@link PermissionInterceptor} 从 {@code @RequiresPerm} 本身解析 ——
 * 因此「注解被删」或「动作写错」都会让用例失败，而不是让 403 断言因为账号本来就没权限而空转。
 *
 * <p>每个用例**同时**跑两个方向：无权 → 403 且服务未被调用；有权 → 200。
 * 只测 403 无法证明权限码写对了（一个拼错的码对所有人都是 403，测试照样全绿）。
 */
class RecordSheetEndpointPermissionTest {

    private static final long ASSET_ID = 7L;
    private static final long PROJECT_ID = 1L;
    private static final long ZONE_ID = 9L;

    private static final String ASSET_PATH = "/api/v1/assets/{id}/record-sheet";
    private static final String PROJECT_PATH = "/api/v1/projects/{id}/record-sheet";
    private static final String ZONE_PATH = "/api/v1/projects/{pid}/zones/{zoneId}/record-sheet";

    private RbacService rbacService;
    private RecordSheetService service;
    private RecordSheetController controller;

    @BeforeEach
    void setUp() {
        rbacService = RbacFixtures.standard().newService(1L);
        service = mock(RecordSheetService.class);
        when(service.read(any(), any())).thenReturn(new RecordSheetView());
        when(service.save(any(), any(), any())).thenReturn(new RecordSheetView());
        // OwnerResolver 用桩：数据范围断言由 OwnerResolverTest 单独覆盖，这里只验权限码
        controller = new RecordSheetController(service, mock(OwnerResolver.class));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("资产 record-sheet：读需 asset.ledger:view，写需 asset.ledger:update")
    void assetSheetPermissions() throws Exception {
        login(Set.of());
        mvc().perform(get(ASSET_PATH, ASSET_ID)).andExpect(status().isForbidden());
        mvc().perform(put(ASSET_PATH, ASSET_ID).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verify(service, never()).read(any(), any());
        verify(service, never()).save(any(), any(), any());

        login(Set.of("asset.ledger:view"));
        mvc().perform(get(ASSET_PATH, ASSET_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(service).read(RecordOwnerType.ASSET, ASSET_ID);

        login(Set.of("asset.ledger:update"));
        mvc().perform(put(ASSET_PATH, ASSET_ID).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verify(service).save(any(RecordOwnerType.class), any(), any(RecordSheetRequest.class));
    }

    @Test
    @DisplayName("项目 record-sheet：读需 asset.project:view，写需 asset.project:update")
    void projectSheetPermissions() throws Exception {
        login(Set.of());
        mvc().perform(get(PROJECT_PATH, PROJECT_ID)).andExpect(status().isForbidden());
        mvc().perform(put(PROJECT_PATH, PROJECT_ID).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        login(Set.of("asset.project:view"));
        mvc().perform(get(PROJECT_PATH, PROJECT_ID)).andExpect(status().isOk());
        verify(service).read(RecordOwnerType.PROJECT, PROJECT_ID);

        login(Set.of("asset.project:update"));
        mvc().perform(put(PROJECT_PATH, PROJECT_ID).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("分区 record-sheet：复用 asset.project 权限码，不引入新菜单")
    void zoneSheetPermissions() throws Exception {
        login(Set.of());
        mvc().perform(get(ZONE_PATH, PROJECT_ID, ZONE_ID)).andExpect(status().isForbidden());
        mvc().perform(put(ZONE_PATH, PROJECT_ID, ZONE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        login(Set.of("asset.project:view"));
        mvc().perform(get(ZONE_PATH, PROJECT_ID, ZONE_ID)).andExpect(status().isOk());
        verify(service).read(RecordOwnerType.ZONE, ZONE_ID);

        login(Set.of("asset.project:update"));
        mvc().perform(put(ZONE_PATH, PROJECT_ID, ZONE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verify(service).save(RecordOwnerType.ZONE, ZONE_ID, any(RecordSheetRequest.class));
    }

    /** 以非 super_admin 身份登录：roles 不含 super_admin，否则拦截器直接放行、用例空转。 */
    private void login(Set<String> permissions) {
        LoginUser user = LoginUser.builder()
                .userId(9001L)
                .username("record-probe")
                .name("记录权限探针")
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
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
```

- [ ] **Step 2: 在 CI 执行，确认失败**

Run（CI）: `cd backend && mvn -B test -Dtest=RecordSheetEndpointPermissionTest`
Expected: FAIL —— `cannot find symbol: class RecordSheetController`。

- [ ] **Step 3: 写控制器**

新建 `backend/src/main/java/com/ams/modules/record/controller/RecordSheetController.java`：

```java
package com.ams.modules.record.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.record.RecordOwnerType;
import com.ams.modules.record.dto.RecordSheetRequest;
import com.ams.modules.record.dto.RecordSheetView;
import com.ams.modules.record.service.OwnerResolver;
import com.ams.modules.record.service.RecordSheetService;
import com.ams.platform.security.Audited;
import com.ams.platform.security.RequiresPerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后续记录（处置 / 接收 / 来源）的聚合读写接口 —— 设计 §5.2。
 *
 * <p><strong>为什么只有 6 个端点而不是每模块一组 CRUD</strong>：三模块在同一个表单里
 * 一次提交，拆成逐模块 PUT 会出现两条互相覆盖的写入路径（设计 §5.2 的设计取舍）。
 * 聚合写是一个事务，子记录用全量 diff，中途任一条非法则整单不落库（验收第 10 条）。
 *
 * <p><strong>授权分两层</strong>：{@code @RequiresPerm} 判「有没有这个菜单的动作」，
 * {@link OwnerResolver} 判「这个对象在不在你的数据范围内」。前者是注解、由
 * {@code PermissionInterceptor} 执行；后者必须显式调用，注解做不到对象级判定（设计 §5.3）。
 *
 * <p>附件不单独开端点：文件本体走 {@code POST /files/upload}，关联关系内嵌在
 * record-sheet 的请求体里（设计 §5.2 / §4.3）。
 */
@RestController
@RequestMapping("/api/v1")
public class RecordSheetController {

    private final RecordSheetService recordSheetService;
    private final OwnerResolver ownerResolver;

    public RecordSheetController(RecordSheetService recordSheetService, OwnerResolver ownerResolver) {
        this.recordSheetService = recordSheetService;
        this.ownerResolver = ownerResolver;
    }

    // ---- 资产 ----

    @GetMapping("/assets/{assetId}/record-sheet")
    @RequiresPerm("asset.ledger:view")
    public ApiResponse<RecordSheetView> assetSheet(@PathVariable Long assetId) {
        ownerResolver.assertAccessible(RecordOwnerType.ASSET, assetId);
        return ApiResponse.ok(recordSheetService.read(RecordOwnerType.ASSET, assetId), TraceIdUtil.get());
    }

    @PutMapping("/assets/{assetId}/record-sheet")
    @RequiresPerm("asset.ledger:update")
    @Audited(module = "record", action = "save_asset_record_sheet")
    public ApiResponse<RecordSheetView> saveAssetSheet(
            @PathVariable Long assetId, @RequestBody RecordSheetRequest request) {
        ownerResolver.assertAccessible(RecordOwnerType.ASSET, assetId);
        return ApiResponse.ok(
                recordSheetService.save(RecordOwnerType.ASSET, assetId, request), TraceIdUtil.get());
    }

    // ---- 项目 ----

    @GetMapping("/projects/{projectId}/record-sheet")
    @RequiresPerm("asset.project:view")
    public ApiResponse<RecordSheetView> projectSheet(@PathVariable Long projectId) {
        ownerResolver.assertAccessible(RecordOwnerType.PROJECT, projectId);
        return ApiResponse.ok(
                recordSheetService.read(RecordOwnerType.PROJECT, projectId), TraceIdUtil.get());
    }

    @PutMapping("/projects/{projectId}/record-sheet")
    @RequiresPerm("asset.project:update")
    @Audited(module = "record", action = "save_project_record_sheet")
    public ApiResponse<RecordSheetView> saveProjectSheet(
            @PathVariable Long projectId, @RequestBody RecordSheetRequest request) {
        ownerResolver.assertAccessible(RecordOwnerType.PROJECT, projectId);
        return ApiResponse.ok(
                recordSheetService.save(RecordOwnerType.PROJECT, projectId, request), TraceIdUtil.get());
    }

    // ---- 分区 ----
    // 路径嵌套在既有分区资源下：归属可由路径直接解析，与已上线的分区 CRUD 保持一致。
    // projectId 参与路径是为了让「分区不属于该项目」由路由形态本身就排斥掉，
    // 而不是靠请求体里再传一次 projectId。

    @GetMapping("/projects/{projectId}/zones/{zoneId}/record-sheet")
    @RequiresPerm("asset.project:view")
    public ApiResponse<RecordSheetView> zoneSheet(
            @PathVariable Long projectId, @PathVariable Long zoneId) {
        ownerResolver.assertAccessible(RecordOwnerType.ZONE, zoneId);
        return ApiResponse.ok(recordSheetService.read(RecordOwnerType.ZONE, zoneId), TraceIdUtil.get());
    }

    @PutMapping("/projects/{projectId}/zones/{zoneId}/record-sheet")
    @RequiresPerm("asset.project:update")
    @Audited(module = "record", action = "save_zone_record_sheet")
    public ApiResponse<RecordSheetView> saveZoneSheet(
            @PathVariable Long projectId, @PathVariable Long zoneId,
            @RequestBody RecordSheetRequest request) {
        ownerResolver.assertAccessible(RecordOwnerType.ZONE, zoneId);
        return ApiResponse.ok(
                recordSheetService.save(RecordOwnerType.ZONE, zoneId, request), TraceIdUtil.get());
    }
}
```

- [ ] **Step 4: 在 CI 执行，确认通过**

Run（CI）: `cd backend && mvn -B test -Dtest=RecordSheetEndpointPermissionTest`
Expected: PASS（3 个用例，每个覆盖两个方向）。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/ams/modules/record/controller/RecordSheetController.java \
        backend/src/test/java/com/ams/modules/record/controller/RecordSheetEndpointPermissionTest.java
git commit -m "feat(record): record-sheet 三主体读写接口与权限闭环"
```

---

## Task 9: 资产处置（列表端点 + 附件 + 权限注解）

**Files:**
- Modify: `backend/src/main/java/com/ams/modules/disposal/service/DisposalService.java`（新增 `listByAsset` + 附件回显）
- Modify: `backend/src/main/java/com/ams/modules/disposal/controller/DisposalController.java`（补权限注解 + 附件）
- Modify: `backend/src/main/java/com/ams/modules/asset/controller/AssetController.java`（新增 `GET /assets/{assetId}/disposals`）
- Modify: `backend/src/main/java/com/ams/modules/record/dto/DisposalOrderView.java`（`amountWan` 语义注释，字段不变）
- Test: `backend/src/test/java/com/ams/modules/disposal/DisposalPermissionTest.java`

**Interfaces:**
- Consumes: `RecordSheetService.orderAttachments` / `syncOrderAttachments`（Task 7）、`OwnerResolver`（Task 5）。
- Produces:
  - `DisposalService.listByAsset(Long assetId) → List<DisposalOrderView>`
  - `DisposalService.createWithAttachments(DisposalOrder order, List<AttachmentRef> refs) → DisposalOrder`
  - `GET /api/v1/assets/{assetId}/disposals`（权限 `asset.ledger:view`）

> **待办归属**：`RecordSheetView.disposals`（资产的处置单列表，来自 `disposal_order`）**不由 Task 7 的 `read()` 填充** —— `read()` 当前对该字段保持空列表。该字段的填充与 `GET /assets/{id}/disposals` 一起**在本 Task 落地**（复用同一个 `DisposalOrderView` 组装逻辑）。Task 8 派发时会带上这条。

**权限码逐字如下（这是本节的核心，也是设计 §5.3 的职责分离）：**

| 端点 | 权限 |
|------|------|
| `GET /assets/{assetId}/disposals` | `asset.ledger:view` |
| `POST /disposals` | `operation.disposal:create` |
| `POST /disposals/{id}/submit` | `operation.disposal:create` |
| `POST /disposals/{id}/approve` | `operation.disposal:approve` |
| `POST /disposals/{id}/execute` | `operation.disposal:update` |
| `POST /disposals/{id}/complete` | `operation.disposal:update` |

> `operation.disposal` 已在 `V45` 菜单种子里（`/disposals`），因此 `PermissionRegistry` 启动校验会通过。**动作必须在词表内**（`create` / `update` / `approve` 都在），否则应用启动失败。

- [ ] **Step 1: 写失败的测试**

新建 `backend/src/test/java/com/ams/modules/disposal/DisposalPermissionTest.java`：

```java
package com.ams.modules.disposal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ams.common.exception.GlobalExceptionHandler;
import com.ams.modules.disposal.controller.DisposalController;
import com.ams.modules.disposal.entity.DisposalOrder;
import com.ams.modules.disposal.service.DisposalService;
import com.ams.modules.record.service.RecordSheetService;
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
 * 处置流转的权限闭环（设计 §5.3 职责分离，验收第 3 条）。
 *
 * <p>本用例存在的意义：`DisposalController` 原本**整类没有** `@RequiresPerm`，
 * 任何登录用户都能审批处置。加上注解后必须证明「审批用独立权限码」——
 * 否则「能编辑资产的人」就等于「能自提自批」。
 *
 * <p>关键的一条是 {@link #approveNeedsDedicatedPermission()}：持有
 * {@code operation.disposal:update} 但**没有** {@code approve} 的账号必须被拒。
 * 只有这一条能证明审批没有被「写权限」顺带放行。
 */
class DisposalPermissionTest {

    private static final long ORDER_ID = 55L;

    private RbacService rbacService;
    private DisposalService disposalService;
    private DisposalController controller;

    @BeforeEach
    void setUp() {
        rbacService = RbacFixtures.standard().newService(1L);
        disposalService = mock(DisposalService.class);
        when(disposalService.create(any())).thenReturn(new DisposalOrder());
        when(disposalService.submit(any())).thenReturn(new DisposalOrder());
        when(disposalService.execute(any(), any(), any())).thenReturn(new DisposalOrder());
        when(disposalService.complete(any())).thenReturn(new DisposalOrder());
        controller = new DisposalController(disposalService, mock(RecordSheetService.class));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("新建处置单：未授予 operation.disposal:create -> 403，服务未被调用")
    void createNeedsCreatePermission() throws Exception {
        login(Set.of());

        mvc().perform(post("/api/v1/disposals")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assetId\":7}"))
                .andExpect(status().isForbidden());

        verify(disposalService, never()).create(any());
    }

    @Test
    @DisplayName("审批：只有 operation.disposal:update（写权限）必须被拒 —— 审批不看写权限")
    void approveNeedsDedicatedPermission() throws Exception {
        login(Set.of("operation.disposal:create", "operation.disposal:update"));

        mvc().perform(post("/api/v1/disposals/{id}/approve", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("审批：授予 operation.disposal:approve -> 放行")
    void approveWithPermissionSucceeds() throws Exception {
        login(Set.of("operation.disposal:approve"));

        mvc().perform(post("/api/v1/disposals/{id}/approve", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("执行 / 完成：需 operation.disposal:update")
    void executeAndCompleteNeedUpdatePermission() throws Exception {
        login(Set.of());

        mvc().perform(post("/api/v1/disposals/{id}/execute", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualAmount\":1}"))
                .andExpect(status().isForbidden());
        mvc().perform(post("/api/v1/disposals/{id}/complete", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        login(Set.of("operation.disposal:update"));
        mvc().perform(post("/api/v1/disposals/{id}/execute", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualAmount\":1}"))
                .andExpect(status().isOk());
        mvc().perform(post("/api/v1/disposals/{id}/complete", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private void login(Set<String> permissions) {
        LoginUser user = LoginUser.builder()
                .userId(9002L)
                .username("disposal-probe")
                .name("处置权限探针")
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
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
```

- [ ] **Step 2: 在 CI 执行，确认失败**

Run（CI）: `cd backend && mvn -B test -Dtest=DisposalPermissionTest`
Expected: FAIL —— 至少 `approveNeedsDedicatedPermission` 失败（当前无注解，请求被放行返回 200），
并且 `new DisposalController(disposalService, mock(RecordSheetService.class))` 编译失败。

- [ ] **Step 3: 先读现有 `DisposalController`，再补注解与构造器**

打开 `backend/src/main/java/com/ams/modules/disposal/controller/DisposalController.java`，做三件事：

1. 构造器新增第二个依赖（供附件读写复用 record 模块的 diff 逻辑）：

```java
    private final DisposalService disposalService;
    private final RecordSheetService recordSheetService;

    public DisposalController(DisposalService disposalService, RecordSheetService recordSheetService) {
        this.disposalService = disposalService;
        this.recordSheetService = recordSheetService;
    }
```

2. `create` 改为接收附件并同步（保持请求体向后兼容：老客户端不传 `attachments` 即为空列表）：

```java
    @PostMapping("/disposals")
    @RequiresPerm("operation.disposal:create")
    @Audited(module = "disposal", action = "create")
    public ApiResponse<DisposalOrder> create(@RequestBody DisposalCreateRequest request) {
        DisposalOrder order = disposalService.create(request.toOrder());
        // 附件随处置单一起建立关联；disposal_order 的宿主权限跟资产走（设计 §4.3）
        recordSheetService.syncOrderAttachments(order.getId(), request.getAttachments());
        return ApiResponse.ok(order, TraceIdUtil.get());
    }
```

3. 给下面五个方法各加一行注解（**逐字照抄**）：

```java
    @PostMapping("/disposals/{id}/submit")
    @RequiresPerm("operation.disposal:create")
    @Audited(module = "disposal", action = "submit")
```

```java
    @PostMapping("/disposals/{id}/approve")
    @RequiresPerm("operation.disposal:approve")
    @Audited(module = "disposal", action = "approve")
```

```java
    @PostMapping("/disposals/{id}/execute")
    @RequiresPerm("operation.disposal:update")
    @Audited(module = "disposal", action = "execute")
```

```java
    @PostMapping("/disposals/{id}/complete")
    @RequiresPerm("operation.disposal:update")
    @Audited(module = "disposal", action = "complete")
```

> 现有 `create` 的形参是 `DisposalOrder`（实体直收）。新增 `DisposalCreateRequest` DTO
> （`backend/src/main/java/com/ams/modules/disposal/dto/DisposalCreateRequest.java`）承载
> 实体字段 + `List<AttachmentRef> attachments`，用 `toOrder()` 把同名字段搬进实体：

```java
package com.ams.modules.disposal.dto;

import com.ams.modules.disposal.entity.DisposalOrder;
import com.ams.modules.record.dto.AttachmentRef;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 处置申请：实体字段 + 附件关联（设计 §4.3）。附件先经 {@code POST /files/upload} 拿到 fileId。 */
@Data
public class DisposalCreateRequest {

    private Long assetId;
    private String disposalType;
    private String reason;
    private BigDecimal assessedValue;
    private BigDecimal bookValue;
    private BigDecimal actualAmount;
    private String counterparty;
    private Long disposalUserId;
    private String disposalUserName;
    private java.time.LocalDate disposalDate;
    private String remark;
    private List<AttachmentRef> attachments = new ArrayList<>();

    /**
     * 只搬业务字段，**不搬 status**：状态由服务端按流程设置
     * （{@code DisposalService.create} 会强制 {@code draft}），客户端无法直造一个「已审批」的单子。
     */
    public DisposalOrder toOrder() {
        DisposalOrder order = new DisposalOrder();
        order.setAssetId(assetId);
        order.setDisposalType(disposalType);
        order.setReason(reason);
        order.setAssessedValue(assessedValue);
        order.setBookValue(bookValue);
        order.setActualAmount(actualAmount);
        order.setCounterparty(counterparty);
        order.setDisposalUserId(disposalUserId);
        order.setDisposalUserName(disposalUserName);
        order.setDisposalDate(disposalDate);
        order.setRemark(remark);
        return order;
    }
}
```

> 同时给 `DisposalOrder` 实体补两个字段映射（`V46` 已加列）：

```java
    private Long disposalUserId;
    private String disposalUserName;
    private java.time.LocalDate disposalDate;
    private String remark;
```

- [ ] **Step 4: 给 `DisposalService` 加列表方法与附件回显**

在 `DisposalService` 中新增（并在构造器注入 `RecordSheetService`）：

```java
    /**
     * 某资产的处置单列表（设计 §7.1 的面板数据源）。
     *
     * <p>按 id 倒序：面板最重要的是最近一次处置，历史处置往下排。
     * 附件一并回显，避免前端为每条单子再发一次请求。
     */
    public List<DisposalOrderView> listByAsset(Long assetId) {
        List<DisposalOrder> orders = disposalOrderMapper.selectList(
                new LambdaQueryWrapper<DisposalOrder>()
                        .eq(DisposalOrder::getAssetId, assetId)
                        .orderByDesc(DisposalOrder::getId));
        List<DisposalOrderView> views = new ArrayList<>();
        for (DisposalOrder order : orders) {
            DisposalOrderView view = new DisposalOrderView();
            view.setId(order.getId());
            view.setDisposalType(order.getDisposalType());
            view.setDisposalUserId(order.getDisposalUserId());
            view.setDisposalUserName(order.getDisposalUserName());
            // 金额沿用 actual_amount 原值，不做万元换算（设计 §4.2 末尾）
            view.setAmountWan(order.getActualAmount());
            view.setActualAmount(order.getActualAmount());
            view.setDisposalDate(order.getDisposalDate());
            view.setRemark(order.getRemark());
            view.setStatus(order.getStatus());
            view.setAttachments(recordSheetService.orderAttachments(order.getId()));
            views.add(view);
        }
        return views;
    }
```

- [ ] **Step 5: 在 `AssetController` 加 `GET /assets/{assetId}/disposals`**

在 `dossier` 方法之后插入（构造器新增 `DisposalService`，并在 import 区补
`com.ams.modules.disposal.service.DisposalService` 与 `com.ams.modules.record.dto.DisposalOrderView`）：

```java
    /**
     * 某资产的处置单列表（资产编辑页第 3 步的处置面板数据源）。
     *
     * <p>权限用 {@code asset.ledger:view}（看资产的人就能看它的处置历史）；
     * 推进流程用 {@code operation.disposal:*}，两者刻意分开（设计 §5.3）。
     */
    @GetMapping("/assets/{assetId}/disposals")
    @RequiresPerm("asset.ledger:view")
    public ApiResponse<List<DisposalOrderView>> assetDisposals(@PathVariable Long assetId) {
        assertAsset(assetId);
        return ApiResponse.ok(disposalService.listByAsset(assetId), TraceIdUtil.get());
    }
```

- [ ] **Step 6: 在 CI 执行，确认通过**

Run（CI）: `cd backend && mvn -B test -Dtest=DisposalPermissionTest`
Expected: PASS（4 个用例）。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/ams/modules/disposal/ \
        backend/src/main/java/com/ams/modules/asset/controller/AssetController.java \
        backend/src/test/java/com/ams/modules/disposal/DisposalPermissionTest.java
git commit -m "feat(disposal): 处置流转补操作级权限与附件，新增资产处置列表端点"
```

---

## Task 10: 测试夹具与权限守卫对齐

**Files:**
- Modify: `backend/src/test/java/com/ams/support/RbacFixtures.java`（`defineMenus` 与 `grant`）
- Verify: `scripts/check-perm-invariants.mjs` 通过

**Interfaces:**
- Consumes: Task 8 / 9 新增的 `@RequiresPerm`（`operation.disposal:*` 是本 Task 唯一的新增码，其余三个码已存在）。
- Produces: `RbacFixtures.ROLE_ASSET_MGR` 现在持有处置流转权限，供后续用例断言。

**为什么必须做这一步**：`scripts/check-perm-invariants.mjs` 第 2b 条硬性要求「测试夹具里的权限码 ⊆ 后端 `@RequiresPerm`」。反过来，如果新注解引用的权限码**没有**出现在任何夹具里，`RbacFixtures` 就无法为它授权，测试也就无法证明「有权限时能通过」——只能证明 403，而 403 在权限码写错时同样成立。

> 本节**不修改** `WriteEndpointPermissionTest`：它是一个「已覆盖端点」的枚举清单，新端点的闭环已由 Task 8 的 `RecordSheetEndpointPermissionTest` 与 Task 9 的 `DisposalPermissionTest` 各自覆盖，重复登记只会让两处断言漂移。

- [ ] **Step 1: 在 `RbacFixtures.defineMenus()` 末尾加菜单**

```java
        menu("operation.disposal", "资产处置", "menu");
```

（`menu()` 自动分配 id 与排序，不需要手工编号。）

- [ ] **Step 2: 在 `standard()` 的写权限授予里给资产管理员加处置权限**

把 `f.grant(ROLE_ASSET_MGR, ...)` 的权限码列表改为（**新增最后三行**）：

```java
        f.grant(ROLE_ASSET_MGR,
                "asset.project:view", "asset.project:create", "asset.project:update", "asset.project:delete",
                "asset.ledger:view", "asset.ledger:create", "asset.ledger:update", "asset.ledger:delete",
                "asset.structureLog:view", "org.structure:view", "org.company:view",
                "operation.disposal:create", "operation.disposal:update", "operation.disposal:approve");
```

> **只加这三个真实存在的码。** 不要加 `operation.disposal:view` —— 处置列表接口
> （`DisposalService.page` / `GET /disposals`）本期没有接入操作级权限，该码不在
> `@RequiresPerm` 集合里，加进去会让 `check-perm-invariants.mjs` 直接失败。

- [ ] **Step 3: 在 CI 执行，确认通过**

Run（CI）: `cd backend && mvn -B test -Dtest='DisposalPermissionTest+RecordSheetEndpointPermissionTest+AssetZoneEndpointPermissionTest'`
Expected: PASS。

- [ ] **Step 4: 本机跑权限守卫**

Run（本机可执行）: `node scripts/check-perm-invariants.mjs`
Expected: `检查通过`（可能带既有告警，但**没有**「失败」段落）。

- [ ] **Step 5: 在 CI 跑全量后端测试**

Run（CI）: `cd backend && mvn -B verify`
Expected: BUILD SUCCESS（本计划后端部分的最终验收）。

- [ ] **Step 6: 提交**

```bash
git add backend/src/test/java/com/ams/support/RbacFixtures.java
git commit -m "test(security): 夹具补齐 operation.disposal 菜单与授权"
```

---

## Task 11: `AttachmentField`（受控多附件）

**Files:**
- Create: `frontend/admin-web/src/components/AttachmentField.tsx`
- Verify: `pnpm -C frontend lint`、`pnpm -C frontend format:check`

**Interfaces:**
- Consumes: `@/lib/upload` 的 `uploadFile(file, bizType)` 与 `beforeUploadImage`；antd `Upload`。
- Produces:

```ts
/** 表单值形态：一个附件槽位 */
export interface AttachmentValue {
  fileId: number;
  url: string;
  name: string;
}

interface AttachmentFieldProps {
  value?: AttachmentValue[];
  onChange?: (value: AttachmentValue[]) => void;
  /** 传给 POST /files/upload 的 bizType（上传来源标记，与 biz_attachment.biz_type 无关） */
  bizType: string;
  /** 单文件大小上限(MB)，默认 5 —— 与 ImageUploadField 一致 */
  maxMB?: number;
  /** 单字段最多几个附件，默认 10 */
  maxCount?: number;
  accept?: string;
}
```

**与 `ImageUploadField` 的分工**：图片单图仍用 `ImageUploadField`（`picture-card` + 预览弹层），多附件用本组件（`text` 列表 + 下载链接）。两者并存，不要把 `ImageUploadField` 改成多文件。

- [ ] **Step 1: 写组件**

新建 `frontend/admin-web/src/components/AttachmentField.tsx`：

```tsx
import { useEffect, useState } from 'react';
import { Button, Upload, message } from 'antd';
import type { UploadFile, UploadProps } from 'antd/es/upload';
import { DeleteOutlined, DownloadOutlined, UploadOutlined } from '@ant-design/icons';
import { uploadFile } from '@/lib/upload';

// ============================================================================
// 多附件上传字段（受控）。设计见 docs/superpowers/specs/2026-09-12-record-forms-design.md §6.4。
//
// 直接作为 <Form.Item name="xxx"> 的子节点使用：
//   <Form.Item name="attachments"><AttachmentField bizType="asset" /></Form.Item>
// 表单值形态：{ fileId: number; url: string; name: string }[]
//
// 与 ImageUploadField 的分工：图片单图用后者（卡片缩略图 + 预览），
// 这里用于「交接文件 / 现场文件 / 来源附件 / 处置附件」这类非图片或批量附件。
// ============================================================================

/** 表单值形态：一个附件槽位。fileId 是唯一身份，服务端按它做全量 diff。 */
export interface AttachmentValue {
  fileId: number;
  url: string;
  name: string;
}

interface AttachmentFieldProps {
  /** 受控值：由 Form.Item 注入 */
  value?: AttachmentValue[];
  /** 值变更回调：由 Form.Item 注入 */
  onChange?: (value: AttachmentValue[]) => void;
  /**
   * 传给 POST /files/upload 的 bizType（上传来源标记）。
   * 注意它与 biz_attachment.biz_type（附件属于哪个字段）是两回事，后者由服务端决定。
   */
  bizType: string;
  /** 单文件大小上限(MB) */
  maxMB?: number;
  /** 单字段最多几个附件 */
  maxCount?: number;
  accept?: string;
}

/** 大小与类型前置校验：不合规返回 Upload.LIST_IGNORE，不发起请求。 */
const beforeUploadAttachment = (file: File, maxMB: number, accept?: string) => {
  if (accept) {
    const extensions = accept.split(',').map((item) => item.trim().toLowerCase());
    const lowerName = file.name.toLowerCase();
    const matched = extensions.some((rule) =>
      rule.startsWith('.') ? lowerName.endsWith(rule) : file.type === rule.replace('*', '')
        || (rule.endsWith('/*') && file.type.startsWith(rule.slice(0, -1))),
    );
    if (!matched) {
      message.error(`只允许上传：${accept}`);
      return Upload.LIST_IGNORE;
    }
  }
  if (file.size > maxMB * 1024 * 1024) {
    message.error(`文件大小不能超过 ${maxMB}MB`);
    return Upload.LIST_IGNORE;
  }
  return true;
};

export function AttachmentField({
  value,
  onChange,
  bizType,
  maxMB = 5,
  maxCount = 10,
  accept,
}: AttachmentFieldProps) {
  const items = value ?? [];
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [uploading, setUploading] = useState(false);

  // 外部值（编辑回填 / 清空）→ 上传列表。
  // 用 fileId 拼 key 而不是 url：同一个文件被重复上传会得到不同 fileId、相同内容，
  // 用 url 去重会把「确实想挂两次」的情况吞掉，也让 React key 冲突。
  useEffect(() => {
    setFileList(
      items.map((item) => ({
        uid: String(item.fileId),
        name: item.name || `附件${item.fileId}`,
        status: 'done' as const,
        url: item.url,
      })),
    );
    // items 每次渲染都是新数组，用 fileId 串做依赖才能避免无限 setState
  }, [items.map((item) => item.fileId).join(','), items.map((item) => item.name).join(',')]);

  const handleUpload: UploadProps['customRequest'] = async ({ file, onSuccess, onError }) => {
    try {
      setUploading(true);
      const data = await uploadFile(file as File, bizType);
      // 追加而不是替换：多附件是累加语义
      onChange?.([
        ...items,
        { fileId: data.fileId, url: data.url, name: (file as File).name },
      ]);
      onSuccess?.(data);
    } catch (e) {
      onError?.(e as Error);
      message.error(e instanceof Error ? e.message : '附件上传失败');
    } finally {
      setUploading(false);
    }
  };

  /** 删除：按 fileId 过滤。服务端在下一次提交时对未出现的行做软删。 */
  const handleRemove = (fileId: number) => {
    onChange?.(items.filter((item) => item.fileId !== fileId));
  };

  const handleDownload = (item: AttachmentValue) => {
    if (!item.url) {
      message.warning('该附件没有可访问地址');
      return;
    }
    window.open(item.url, '_blank', 'noopener,noreferrer');
  };

  return (
    <div className="flex flex-col gap-2">
      <Upload
        accept={accept}
        fileList={fileList}
        showUploadList={false}
        multiple
        maxCount={maxCount}
        beforeUpload={(file) => beforeUploadAttachment(file, maxMB, accept)}
        customRequest={handleUpload}
      >
        <Button
          icon={<UploadOutlined />}
          loading={uploading}
          disabled={items.length >= maxCount}
          aria-label="上传附件"
        >
          {items.length >= maxCount ? `最多 ${maxCount} 个` : '上传附件'}
        </Button>
      </Upload>

      <ul className="m-0 p-0 list-none flex flex-col gap-1">
        {items.map((item) => (
          <li
            key={item.fileId}
            className="flex items-center gap-2 text-sm px-2 py-1 rounded border border-[var(--ams-border)]"
          >
            <span className="flex-1 min-w-0 truncate" title={item.name}>
              {item.name || `附件${item.fileId}`}
            </span>
            <Button
              type="link"
              size="small"
              icon={<DownloadOutlined />}
              onClick={() => handleDownload(item)}
              aria-label={`下载 ${item.name || item.fileId}`}
            >
              下载
            </Button>
            <Button
              type="link"
              size="small"
              danger
              icon={<DeleteOutlined />}
              onClick={() => handleRemove(item.fileId)}
              aria-label={`删除 ${item.name || item.fileId}`}
            >
              删除
            </Button>
          </li>
        ))}
      </ul>
    </div>
  );
}
```

- [ ] **Step 2: 类型检查与格式检查**

Run: `pnpm -C frontend lint && pnpm -C frontend format:check`
Expected: 无 error。若 `format:check` 报格式问题，运行 `pnpm -C frontend exec prettier --write admin-web/src/components/AttachmentField.tsx` 后重跑。

- [ ] **Step 3: 提交**

```bash
git add frontend/admin-web/src/components/AttachmentField.tsx
git commit -m "feat(admin-web): 多附件受控上传组件 AttachmentField"
```

---

## Task 12: `ActorField`（受控相对人）

**Files:**
- Create: `frontend/admin-web/src/components/ActorField.tsx`
- Verify: `pnpm -C frontend lint`、`pnpm -C frontend format:check`

**Interfaces:**
- Consumes: `@/lib/api`（`api.get` 搜索员工）、antd `Select`。
- Produces:

```ts
/** 表单值形态：内员带 userId，外部人员只带 name */
export interface ActorValue {
  userId?: number;
  name: string;
}

interface ActorFieldProps {
  value?: ActorValue | null;
  onChange?: (value: ActorValue | null) => void;
  /** 为空时禁用远程搜索（例如资产公司还没选） */
  departmentId?: number | null;
  companyId?: number | null;
  placeholder?: string;
  disabled?: boolean;
}
```

**后端约定（必须对齐）**：`userId` 非空时服务端会用 `sys_user.name` **覆盖** `name`（姓名快照）；`userId` 为空时 `name` 必填，否则 400（设计 §4.4）。因此前端在「外部人员」模式下必须校验姓名非空。

- [ ] **Step 1: 写组件**

新建 `frontend/admin-web/src/components/ActorField.tsx`：

```tsx
import { useEffect, useMemo, useState } from 'react';
import { Select, Space, Tag } from 'antd';
import { api } from '@/lib/api';

// ============================================================================
// 相对人字段（受控）。设计见 docs/superpowers/specs/2026-09-12-record-forms-design.md §4.4 / §6.5。
//
// 两种来源：
//   1. 系统内员工 → { userId, name }，后端会用员工姓名覆盖 name（姓名快照）；
//   2. 外部人员   → { name }，手填。
// 直接作为 <Form.Item name="xxx"> 的子节点使用。
// ============================================================================

export interface ActorValue {
  userId?: number;
  name: string;
}

interface ActorFieldProps {
  value?: ActorValue | null;
  onChange?: (value: ActorValue | null) => void;
  /** 部门范围：为空时不做部门过滤（仍可全局搜索） */
  departmentId?: number | null;
  /** 公司范围：与 departmentId 同时存在时以 departmentId 为准 */
  companyId?: number | null;
  placeholder?: string;
  disabled?: boolean;
}

interface EmployeeOption {
  id: number;
  name: string;
}

const EXTERNAL_PREFIX = '外部人员：';

export function ActorField({
  value,
  onChange,
  departmentId,
  companyId,
  placeholder = '搜索系统内员工，或直接输入外部人员姓名',
  disabled,
}: ActorFieldProps) {
  const [options, setOptions] = useState<EmployeeOption[]>([]);
  const [keyword, setKeyword] = useState('');

  // 搜索：部门优先，其次公司；两者都没有时不做请求（避免把全量用户拉下来）
  useEffect(() => {
    const scope = departmentId
      ? `departmentId=${departmentId}`
      : companyId
        ? `companyId=${companyId}`
        : null;
    if (!scope) {
      setOptions([]);
      return;
    }
    let cancelled = false;
    api
      .get<{ records?: EmployeeOption[] } | EmployeeOption[]>(
        `/system/users?${scope}&page=1&pageSize=50`,
      )
      .then((data) => {
        if (cancelled) return;
        setOptions(Array.isArray(data) ? data : (data.records ?? []));
      })
      .catch(() => {
        if (!cancelled) setOptions([]);
      });
    return () => {
      cancelled = true;
    };
  }, [departmentId, companyId]);

  const selectedValue = value?.userId != null ? String(value.userId) : value?.name ?? undefined;

  const selectOptions = useMemo(() => {
    const list = options.map((employee) => ({
      value: String(employee.id),
      label: employee.name,
    }));
    // 当前值是外部人员（无 userId）时，把它作为一项补进下拉，
    // 否则 antd 找不到对应 value 会显示成裸 id，看起来像数据丢了
    if (value?.name && value.userId == null) {
      list.unshift({ value: value.name, label: `${EXTERNAL_PREFIX}${value.name}` });
    }
    return list;
  }, [options, value?.name, value?.userId]);

  const handleChange = (next: string | undefined) => {
    if (!next) {
      onChange?.(null);
      return;
    }
    // 命中员工 id → 内员；否则视为外部人员姓名
    const employee = options.find((item) => String(item.id) === next);
    if (employee) {
      onChange?.({ userId: employee.id, name: employee.name });
      setKeyword('');
      return;
    }
    onChange?.({ name: next });
    setKeyword('');
  };

  return (
    <Space direction="vertical" className="w-full" size={4}>
      <Select
        showSearch
        allowClear
        disabled={disabled}
        className="w-full"
        placeholder={placeholder}
        value={selectedValue}
        options={selectOptions}
        filterOption={false}
        onSearch={setKeyword}
        onChange={handleChange}
        // 允许「直接输入不在列表里的名字」→ 走外部人员分支
        mode="tags"
        maxCount={1}
        tagRender={(props) => {
          const isExternal = value?.userId == null && Boolean(value?.name);
          return (
            <Tag
              closable
              onClose={props.onClose}
              className="mr-1"
              color={isExternal ? 'orange' : 'blue'}
            >
              {isExternal ? `${EXTERNAL_PREFIX}${value?.name}` : props.label}
            </Tag>
          );
        }}
        notFoundContent={
          keyword ? `按回车把「${keyword}」记为外部人员` : '输入姓名搜索系统内员工'
        }
      />
    </Space>
  );
}
```

> **`mode="tags"` 的取舍**：它让「搜不到就用外部人员」变成一次自然的回车操作，
> 代价是 antd 的 `tags` 模式会以数组形式回传值。上面的 `handleChange` 已经处理了这一点
> （`onChange` 收到的是单个 string，因为 `maxCount={1}`）；若实现时发现回传的是数组，
> 把 `handleChange` 的形参改为 `string[]` 并取 `next[0]`，**不要**改 `ActorValue` 的形状。

- [ ] **Step 2: 类型检查与格式检查**

Run: `pnpm -C frontend lint && pnpm -C frontend format:check`
Expected: 无 error。

- [ ] **Step 3: 提交**

```bash
git add frontend/admin-web/src/components/ActorField.tsx
git commit -m "feat(admin-web): 相对人受控组件 ActorField（内员搜索 + 外部手填）"
```

---

## Task 13: `lib/recordSheet.ts`（类型 + 读写封装）

**Files:**
- Create: `frontend/admin-web/src/lib/recordSheet.ts`
- Verify: `pnpm -C frontend lint`

**Interfaces:**
- Consumes: `@/lib/api`、`@/components/AttachmentField` 的 `AttachmentValue`。
- Produces:

```ts
export type RecordOwnerType = 'asset' | 'project' | 'zone';

export interface RecordAttachment { fileId: number; sort?: number; fileName?: string; url?: string }
export interface RecordIssue { id?: number; issueType?: string; description?: string;
  discovererId?: number; discovererName?: string; attachments: AttachmentValue[] }
export interface ReceiveRecord { id?: number; handoverType?: string; docName?: string;
  handoverUserId?: number; handoverUserName?: string; handoverDate?: string; remark?: string;
  issues: RecordIssue[]; attachments: AttachmentValue[] }
export interface SourceInfo { id?: number; sourcePersonId?: number; sourcePersonName?: string;
  sourceUnit?: string; sourceDate?: string; sourceDesc?: string; attachments: AttachmentValue[] }
export interface DisposalRecord { id?: number; disposalType?: string; disposalUserId?: number;
  disposalUserName?: string; amountWan?: number; disposalDate?: string; remark?: string;
  attachments: AttachmentValue[] }
export interface DisposalOrderView { id: number; disposalType?: string; disposalUserId?: number;
  disposalUserName?: string; amountWan?: number; actualAmount?: number; disposalDate?: string;
  remark?: string; status?: string; attachments: AttachmentValue[] }

export interface RecordSheet {
  receives: ReceiveRecord[];
  sourceInfo: SourceInfo | null;
  disposalRecords: DisposalRecord[];
  disposals: DisposalOrderView[];
}

export const recordSheetPath = (ownerType: RecordOwnerType, ownerId: number, projectId?: number): string;
export const loadRecordSheet = (path: string): Promise<RecordSheet>;
export const saveRecordSheet = (path: string, sheet: Omit<RecordSheet, 'disposals'>): Promise<RecordSheet>;
/** 后端返回的附件（fileName/url）→ 组件值（fileId/url/name） */
export const toAttachmentValues = (refs?: RecordAttachment[] | null): AttachmentValue[] => ...;
/** 组件值 → 后端请求体（只提交 fileId 与顺序） */
export const fromAttachmentValues = (values?: AttachmentValue[] | null): RecordAttachment[] => ...;
```

**唯一身份是 `fileId`**：两个方向的转换都以 `fileId` 为准，`name` 只用于展示。这样「回显 → 直接提交」是幂等的（设计 §5.2 的读写复用）。

- [ ] **Step 1: 写实现**

新建 `frontend/admin-web/src/lib/recordSheet.ts`：

```ts
import { api } from '@/lib/api';
import type { AttachmentValue } from '@/components/AttachmentField';

// ============================================================================
// 后续记录（处置 / 接收 / 来源）的聚合读写。
// 契约见后端 docs/superpowers/plans/2026-09-12-record-forms.md Task 8 与
// 设计 docs/superpowers/specs/2026-09-12-record-forms-design.md §5.2。
//
// 一张表单 = 一个聚合：GET 读一次、PUT 写一次（全量 diff），因此本文件不提供
// 「增删单条记录」的接口 —— 单条增删一律通过保存整张 sheet 完成。
// ============================================================================

export type RecordOwnerType = 'asset' | 'project' | 'zone';

/** 后端附件形状：fileName / url 只在读路径回填 */
export interface RecordAttachment {
  fileId: number;
  sort?: number;
  fileName?: string;
  url?: string;
}

export interface RecordIssue {
  id?: number;
  issueType?: string;
  description?: string;
  discovererId?: number;
  discovererName?: string;
  attachments: AttachmentValue[];
}

export interface ReceiveRecord {
  id?: number;
  handoverType?: string;
  docName?: string;
  handoverUserId?: number;
  handoverUserName?: string;
  /** yyyy-MM-dd */
  handoverDate?: string;
  remark?: string;
  issues: RecordIssue[];
  attachments: AttachmentValue[];
}

export interface SourceInfo {
  id?: number;
  sourcePersonId?: number;
  sourcePersonName?: string;
  sourceUnit?: string;
  /** yyyy-MM-dd */
  sourceDate?: string;
  sourceDesc?: string;
  attachments: AttachmentValue[];
}

export interface DisposalRecord {
  id?: number;
  disposalType?: string;
  disposalUserId?: number;
  disposalUserName?: string;
  /** 万元 */
  amountWan?: number;
  /** yyyy-MM-dd */
  disposalDate?: string;
  remark?: string;
  attachments: AttachmentValue[];
}

/** 资产侧处置单：只读回显，由 disposal_order 提供 */
export interface DisposalOrderView {
  id: number;
  disposalType?: string;
  disposalUserId?: number;
  disposalUserName?: string;
  amountWan?: number;
  actualAmount?: number;
  disposalDate?: string;
  remark?: string;
  status?: string;
  attachments: AttachmentValue[];
}

export interface RecordSheet {
  receives: ReceiveRecord[];
  sourceInfo: SourceInfo | null;
  disposalRecords: DisposalRecord[];
  /** 仅资产主体非空 */
  disposals: DisposalOrderView[];
}

/** 写请求体：disposals 是只读回显，不参与写入 */
export type RecordSheetPayload = Omit<RecordSheet, 'disposals'>;

/**
 * 记录路由。分区必须带上 projectId —— 后端把「分区不属于该项目」交给路由形态排斥，
 * 而不是靠请求体再传一次 projectId。
 */
export const recordSheetPath = (
  ownerType: RecordOwnerType,
  ownerId: number,
  projectId?: number,
): string => {
  if (ownerType === 'asset') return `/assets/${ownerId}/record-sheet`;
  if (ownerType === 'project') return `/projects/${ownerId}/record-sheet`;
  if (projectId == null) {
    throw new Error('分区记录路由必须提供 projectId');
  }
  return `/projects/${projectId}/zones/${ownerId}/record-sheet`;
};

/** 后端附件 → 组件值 */
export const toAttachmentValues = (refs?: RecordAttachment[] | null): AttachmentValue[] =>
  (refs ?? []).map((ref) => ({
    fileId: ref.fileId,
    url: ref.url ?? '',
    name: ref.fileName ?? `附件${ref.fileId}`,
  }));

/** 组件值 → 请求体：只提交 fileId 与顺序，name/url 由服务端回填 */
export const fromAttachmentValues = (values?: AttachmentValue[] | null): RecordAttachment[] =>
  (values ?? []).map((item, index) => ({ fileId: item.fileId, sort: index }));

const normalizeSheet = (raw: Partial<RecordSheet> | null | undefined): RecordSheet => ({
  receives: (raw?.receives ?? []).map((record) => ({
    ...record,
    issues: (record.issues ?? []).map((issue) => ({
      ...issue,
      attachments: toAttachmentValues(issue.attachments as unknown as RecordAttachment[]),
    })),
    attachments: toAttachmentValues(record.attachments as unknown as RecordAttachment[]),
  })),
  sourceInfo: raw?.sourceInfo
    ? {
        ...raw.sourceInfo,
        attachments: toAttachmentValues(raw.sourceInfo.attachments as unknown as RecordAttachment[]),
      }
    : null,
  disposalRecords: (raw?.disposalRecords ?? []).map((record) => ({
    ...record,
    // 资产侧回显字段是 actualAmount，统一映射到 amountWan 供表单使用
    amountWan: record.amountWan ?? (record as { actualAmount?: number }).actualAmount,
    attachments: toAttachmentValues(record.attachments as unknown as RecordAttachment[]),
  })),
  disposals: (raw?.disposals ?? []).map((order) => ({
    ...order,
    amountWan: order.amountWan ?? order.actualAmount,
    attachments: toAttachmentValues(order.attachments as unknown as RecordAttachment[]),
  })),
});

export const loadRecordSheet = (path: string): Promise<RecordSheet> =>
  api.get<RecordSheet>(path).then(normalizeSheet);

/** 全量提交：未出现的记录与附件由服务端软删，因此必须传完整列表。 */
export const saveRecordSheet = (path: string, sheet: RecordSheetPayload): Promise<RecordSheet> =>
  api
    .put<RecordSheet>(path, {
      receives: sheet.receives.map((record) => ({
        ...record,
        // 归属由服务端赋值；这里只传业务字段与附件
        attachments: fromAttachmentValues(record.attachments),
        issues: record.issues.map((issue) => ({
          ...issue,
          attachments: fromAttachmentValues(issue.attachments),
        })),
      })),
      sourceInfo: sheet.sourceInfo
        ? { ...sheet.sourceInfo, attachments: fromAttachmentValues(sheet.sourceInfo.attachments) }
        : null,
      disposalRecords: sheet.disposalRecords.map((record) => ({
        ...record,
        attachments: fromAttachmentValues(record.attachments),
      })),
    })
    .then(normalizeSheet);
```

- [ ] **Step 2: 类型检查**

Run: `pnpm -C frontend lint && pnpm -C frontend format:check`
Expected: 无 error。

- [ ] **Step 3: 提交**

```bash
git add frontend/admin-web/src/lib/recordSheet.ts
git commit -m "feat(admin-web): record-sheet 类型与读写封装"
```

---

## Task 14: `RecordSheetSections`（三模块复用组件）

**Files:**
- Create: `frontend/admin-web/src/components/RecordSheetSections.tsx`
- Verify: `pnpm -C frontend lint`

**Interfaces:**
- Consumes: Task 11-13、antd `Form` / `Form.List` / `Table` / `Tabs`、`useDictOptions`、`usePerm`。
- Produces:

```tsx
interface RecordSheetSectionsProps {
  ownerType: RecordOwnerType;
  ownerId?: number | null;
  /** 分区必须传：用于拼 record-sheet 路径 */
  projectId?: number | null;
  /** 所属公司 / 责任部门，透传给 ActorField 收窄员工搜索范围 */
  companyId?: number | null;
  departmentId?: number | null;
  /** 新增态（还没有 ownerId）：整块禁用并提示先保存主体 */
  disabled?: boolean;
  /** 受控值：由父级 Form.Item name="recordSheet" 注入 */
  value?: RecordSheetPayload | null;
  onChange?: (value: RecordSheetPayload) => void;
}
```

**关键分工**：
- `ownerType === 'asset'` → 处置模块渲染 `disposals`（只读列表 + 状态与操作按钮，按钮按 `operation.disposal:*` 权限显隐）；不渲染可编辑台账。
- `ownerType !== 'asset'` → 处置模块渲染可编辑台账（`disposalRecords`）。
- 接收信息 / 来源明细三主体完全一致。

- [ ] **Step 1: 写组件骨架与状态同步**

新建 `frontend/admin-web/src/components/RecordSheetSections.tsx`：

```tsx
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, DatePicker, Empty, Form, Input, InputNumber, Select, Space,
  Table, Tabs, Tag, message } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { api } from '@/lib/api';
import { AttachmentField } from '@/components/AttachmentField';
import { ActorField } from '@/components/ActorField';
import { useDictOptions } from '@/lib/dict';
import { usePerm } from '@/lib/perm';
import {
  loadRecordSheet,
  recordSheetPath,
  saveRecordSheet,
  type DisposalOrderView,
  type RecordSheet,
  type RecordSheetPayload,
  type RecordOwnerType,
} from '@/lib/recordSheet';

// ============================================================================
// 后续记录三模块（处置 / 接收 / 来源）。设计 §6.6。
//
// 供资产表单第 3 步、项目表单第 3 步、分区详情页三处复用 —— 因此这里不做
// 「一次保存」的编排（那属于各页面的职责），只负责三块的渲染、校验与值同步。
// ============================================================================

const DISPOSAL_STATUS_LABEL: Record<string, string> = {
  draft: '草稿',
  approving: '审批中',
  rejected: '已驳回',
  pending_execute: '待执行',
  executing: '执行中',
  completed: '已完成',
};

const emptySheet = (): RecordSheetPayload => ({
  receives: [],
  sourceInfo: null,
  disposalRecords: [],
});

export interface RecordSheetSectionsProps {
  ownerType: RecordOwnerType;
  ownerId?: number | null;
  projectId?: number | null;
  companyId?: number | null;
  departmentId?: number | null;
  disabled?: boolean;
  value?: RecordSheetPayload | null;
  onChange?: (value: RecordSheetPayload) => void;
}

export function RecordSheetSections({
  ownerType,
  ownerId,
  projectId,
  companyId,
  departmentId,
  disabled,
  value,
  onChange,
}: RecordSheetSectionsProps) {
  const can = usePerm();
  const sheet = value ?? emptySheet();
  const readOnly = Boolean(disabled) || ownerId == null;

  const [disposals, setDisposals] = useState<DisposalOrderView[]>([]);
  const [loadingDisposals, setLoadingDisposals] = useState(false);

  const handoverTypeOptions = useDictOptions('handover_type');
  const issueTypeOptions = useDictOptions('issue_type');
  const disposalTypeOptions = useDictOptions('disposal_type');

  const patch = useCallback(
    (partial: Partial<RecordSheetPayload>) => onChange?.({ ...sheet, ...partial }),
    [onChange, sheet],
  );

  // 资产侧处置单只读回显：走独立的 /assets/{id}/disposals，不参与 record-sheet 写入
  useEffect(() => {
    if (ownerType !== 'asset' || ownerId == null) {
      setDisposals([]);
      return;
    }
    let cancelled = false;
    setLoadingDisposals(true);
    api
      .get<DisposalOrderView[]>(`/assets/${ownerId}/disposals`)
      .then((rows) => {
        if (!cancelled) setDisposals(rows ?? []);
      })
      .catch(() => {
        if (!cancelled) setDisposals([]);
      })
      .finally(() => {
        if (!cancelled) setLoadingDisposals(false);
      });
    return () => {
      cancelled = true;
    };
  }, [ownerType, ownerId]);
```

- [ ] **Step 2: 加接收信息与来源明细两块**

在同一文件里继续（`return` 之前）加渲染函数：

```tsx
  const updateReceive = (index: number, next: Partial<RecordSheetPayload['receives'][number]>) => {
    const receives = sheet.receives.map((item, i) => (i === index ? { ...item, ...next } : item));
    patch({ receives });
  };

  const addReceive = () => {
    patch({
      receives: [
        ...sheet.receives,
        { issues: [], attachments: [] },
      ],
    });
  };

  const removeReceive = (index: number) => {
    patch({ receives: sheet.receives.filter((_, i) => i !== index) });
  };

  const renderSourceInfo = () => (
    <div className="grid gap-4 md:grid-cols-2">
      <Form.Item label="来源人">
        <ActorField
          disabled={readOnly}
          companyId={companyId}
          departmentId={departmentId}
          value={
            sheet.sourceInfo?.sourcePersonId != null || sheet.sourceInfo?.sourcePersonName
              ? {
                  userId: sheet.sourceInfo?.sourcePersonId,
                  name: sheet.sourceInfo?.sourcePersonName ?? '',
                }
              : null
          }
          onChange={(next) =>
            patch({
              sourceInfo: {
                ...(sheet.sourceInfo ?? { attachments: [] }),
                id: sheet.sourceInfo?.id,
                sourcePersonId: next?.userId,
                sourcePersonName: next?.name,
                attachments: sheet.sourceInfo?.attachments ?? [],
              },
            })
          }
        />
      </Form.Item>
      <Form.Item label="来源单位">
        <Input
          disabled={readOnly}
          value={sheet.sourceInfo?.sourceUnit ?? ''}
          onChange={(e) =>
            patch({
              sourceInfo: {
                ...(sheet.sourceInfo ?? { attachments: [] }),
                sourceUnit: e.target.value,
              },
            })
          }
        />
      </Form.Item>
      <Form.Item label="来源日期">
        <DatePicker
          disabled={readOnly}
          className="w-full"
          value={sheet.sourceInfo?.sourceDate ? dayjs(sheet.sourceInfo.sourceDate) : null}
          onChange={(date) =>
            patch({
              sourceInfo: {
                ...(sheet.sourceInfo ?? { attachments: [] }),
                sourceDate: date ? date.format('YYYY-MM-DD') : undefined,
              },
            })
          }
        />
      </Form.Item>
      <Form.Item label="来源描述" className="md:col-span-2">
        <Input.TextArea
          rows={3}
          disabled={readOnly}
          value={sheet.sourceInfo?.sourceDesc ?? ''}
          onChange={(e) =>
            patch({
              sourceInfo: {
                ...(sheet.sourceInfo ?? { attachments: [] }),
                sourceDesc: e.target.value,
              },
            })
          }
        />
      </Form.Item>
      <Form.Item label="来源附件" className="md:col-span-2">
        <AttachmentField
          bizType="asset"
          value={sheet.sourceInfo?.attachments ?? []}
          onChange={(attachments) =>
            patch({
              sourceInfo: {
                ...(sheet.sourceInfo ?? {}),
                attachments,
              },
            })
          }
        />
      </Form.Item>
    </div>
  );
```

**接收信息**用 antd `Table` 的展开行承载「遗留问题」：每行一条接收信息，展开后是该接收信息的遗留问题列表。完整实现：

```tsx
  const updateReceive = (index: number, next: Partial<RecordSheetPayload['receives'][number]>) => {
    const receives = sheet.receives.map((item, i) => (i === index ? { ...item, ...next } : item));
    patch({ receives });
  };

  const addReceive = () => {
    patch({ receives: [...sheet.receives, { issues: [], attachments: [] }] });
  };

  const removeReceive = (index: number) => {
    patch({ receives: sheet.receives.filter((_, i) => i !== index) });
  };

  const updateIssue = (
    receiveIndex: number,
    issueIndex: number,
    next: Partial<RecordSheetPayload['receives'][number]['issues'][number]>,
  ) => {
    const record = sheet.receives[receiveIndex];
    const issues = record.issues.map((item, i) => (i === issueIndex ? { ...item, ...next } : item));
    updateReceive(receiveIndex, { issues });
  };

  const renderIssues = (receiveIndex: number) => {
    const record = sheet.receives[receiveIndex];
    return (
      <div className="flex flex-col gap-3">
        {record.issues.map((issue, issueIndex) => (
          <div
            key={issue.id ?? `new-${issueIndex}`}
            className="grid gap-3 md:grid-cols-2 p-3 rounded border border-[var(--ams-border)]"
          >
            <Form.Item label="问题类型" className="mb-0">
              <Select
                allowClear
                disabled={readOnly}
                options={issueTypeOptions}
                value={issue.issueType}
                onChange={(value) => updateIssue(receiveIndex, issueIndex, { issueType: value })}
              />
            </Form.Item>
            <Form.Item label="发现人" className="mb-0">
              <ActorField
                disabled={readOnly}
                companyId={companyId}
                departmentId={departmentId}
                value={
                  issue.discovererId != null || issue.discovererName
                    ? { userId: issue.discovererId, name: issue.discovererName ?? '' }
                    : null
                }
                onChange={(next) =>
                  updateIssue(receiveIndex, issueIndex, {
                    discovererId: next?.userId,
                    discovererName: next?.name,
                  })
                }
              />
            </Form.Item>
            <Form.Item label="问题描述" className="mb-0 md:col-span-2">
              <Input.TextArea
                rows={2}
                disabled={readOnly}
                value={issue.description ?? ''}
                onChange={(e) =>
                  updateIssue(receiveIndex, issueIndex, { description: e.target.value })
                }
              />
            </Form.Item>
            <Form.Item label="现场文件" className="mb-0 md:col-span-2">
              <AttachmentField
                bizType="issue"
                value={issue.attachments}
                onChange={(attachments) => updateIssue(receiveIndex, issueIndex, { attachments })}
              />
            </Form.Item>
            <div className="md:col-span-2 flex justify-end">
              <Button
                danger
                size="small"
                icon={<DeleteOutlined />}
                disabled={readOnly}
                onClick={() =>
                  updateReceive(receiveIndex, {
                    issues: record.issues.filter((_, i) => i !== issueIndex),
                  })
                }
              >
                删除该问题
              </Button>
            </div>
          </div>
        ))}
        <Button
          type="dashed"
          icon={<PlusOutlined />}
          disabled={readOnly}
          onClick={() =>
            updateReceive(receiveIndex, {
              issues: [...record.issues, { attachments: [] }],
            })
          }
        >
          新增遗留问题
        </Button>
      </div>
    );
  };

  const renderReceives = () => (
    <div className="flex flex-col gap-3">
      <Table
        rowKey={(row, index) => String(row.id ?? `new-${index}`)}
        dataSource={sheet.receives}
        pagination={false}
        locale={{ emptyText: <Empty description="暂无接收信息" /> }}
        columns={[
          {
            title: '交接类型',
            dataIndex: 'handoverType',
            render: (value: string, _row, index) => (
              <Select
                allowClear
                disabled={readOnly}
                className="min-w-32"
                options={handoverTypeOptions}
                value={value}
                onChange={(next) => updateReceive(index, { handoverType: next })}
              />
            ),
          },
          {
            title: '文档名称',
            dataIndex: 'docName',
            render: (value: string, _row, index) => (
              <Input
                disabled={readOnly}
                value={value ?? ''}
                onChange={(e) => updateReceive(index, { docName: e.target.value })}
              />
            ),
          },
          {
            title: '交接人',
            dataIndex: 'handoverUserName',
            render: (_value, row, index) => (
              <ActorField
                disabled={readOnly}
                companyId={companyId}
                departmentId={departmentId}
                value={
                  row.handoverUserId != null || row.handoverUserName
                    ? { userId: row.handoverUserId, name: row.handoverUserName ?? '' }
                    : null
                }
                onChange={(next) =>
                  updateReceive(index, {
                    handoverUserId: next?.userId,
                    handoverUserName: next?.name,
                  })
                }
              />
            ),
          },
          {
            title: '交接日期',
            dataIndex: 'handoverDate',
            render: (value: string, _row, index) => (
              <DatePicker
                disabled={readOnly}
                value={value ? dayjs(value) : null}
                onChange={(date) =>
                  updateReceive(index, {
                    handoverDate: date ? date.format('YYYY-MM-DD') : undefined,
                  })
                }
              />
            ),
          },
          {
            title: '交接文件',
            key: 'attachments',
            render: (_value, row, index) => (
              <AttachmentField
                bizType="receive"
                value={row.attachments}
                onChange={(attachments) => updateReceive(index, { attachments })}
              />
            ),
          },
          {
            title: '操作',
            key: 'actions',
            render: (_value, _row, index) => (
              <Button danger size="small" disabled={readOnly} onClick={() => removeReceive(index)}>
                删除
              </Button>
            ),
          },
        ]}
        expandable={{
          expandedRowRender: (_row, index) => renderIssues(index),
          rowExpandable: () => true,
        }}
      />
      <Button type="dashed" icon={<PlusOutlined />} disabled={readOnly} onClick={addReceive}>
        新增接收信息
      </Button>
    </div>
  );
```

- [ ] **Step 3: 加处置块与 Tabs 装配**

```tsx
  const updateDisposal = (index: number, next: Partial<RecordSheetPayload['disposalRecords'][number]>) => {
    patch({
      disposalRecords: sheet.disposalRecords.map((item, i) => (i === index ? { ...item, ...next } : item)),
    });
  };

  const renderDisposalLedger = () => (
    <div className="flex flex-col gap-3">
      {sheet.disposalRecords.map((record, index) => (
        <div
          key={record.id ?? `new-${index}`}
          className="grid gap-3 md:grid-cols-2 p-3 rounded border border-[var(--ams-border)]"
        >
          <Form.Item label="处置类型" className="mb-0">
            <Select
              allowClear
              disabled={readOnly}
              options={disposalTypeOptions}
              value={record.disposalType}
              onChange={(value) => updateDisposal(index, { disposalType: value })}
            />
          </Form.Item>
          <Form.Item label="处置人" className="mb-0">
            <ActorField
              disabled={readOnly}
              companyId={companyId}
              departmentId={departmentId}
              value={
                record.disposalUserId != null || record.disposalUserName
                  ? { userId: record.disposalUserId, name: record.disposalUserName ?? '' }
                  : null
              }
              onChange={(next) =>
                updateDisposal(index, { disposalUserId: next?.userId, disposalUserName: next?.name })
              }
            />
          </Form.Item>
          <Form.Item label="处置金额（万元）" className="mb-0">
            <InputNumber
              className="w-full"
              disabled={readOnly}
              min={0}
              precision={2}
              value={record.amountWan}
              onChange={(value) => updateDisposal(index, { amountWan: value ?? undefined })}
            />
          </Form.Item>
          <Form.Item label="处置日期" className="mb-0">
            <DatePicker
              className="w-full"
              disabled={readOnly}
              value={record.disposalDate ? dayjs(record.disposalDate) : null}
              onChange={(date) =>
                updateDisposal(index, { disposalDate: date ? date.format('YYYY-MM-DD') : undefined })
              }
            />
          </Form.Item>
          <Form.Item label="备注" className="mb-0 md:col-span-2">
            <Input
              disabled={readOnly}
              value={record.remark ?? ''}
              onChange={(e) => updateDisposal(index, { remark: e.target.value })}
            />
          </Form.Item>
          <Form.Item label="处置附件" className="mb-0 md:col-span-2">
            <AttachmentField
              bizType="disposal"
              value={record.attachments}
              onChange={(attachments) => updateDisposal(index, { attachments })}
            />
          </Form.Item>
          <div className="md:col-span-2 flex justify-end">
            <Button
              danger
              size="small"
              icon={<DeleteOutlined />}
              disabled={readOnly}
              onClick={() =>
                patch({ disposalRecords: sheet.disposalRecords.filter((_, i) => i !== index) })
              }
            >
              删除该处置记录
            </Button>
          </div>
        </div>
      ))}
      <Button
        type="dashed"
        icon={<PlusOutlined />}
        disabled={readOnly}
        onClick={() => patch({ disposalRecords: [...sheet.disposalRecords, { attachments: [] }] })}
      >
        新增处置记录
      </Button>
    </div>
  );

  const renderAssetDisposals = () => (
    <Table
      rowKey="id"
      loading={loadingDisposals}
      dataSource={disposals}
      locale={{ emptyText: <Empty description="暂无处置记录" /> }}
      pagination={false}
      columns={[
        {
          title: '状态',
          dataIndex: 'status',
          render: (status: string) => <Tag>{DISPOSAL_STATUS_LABEL[status] ?? status}</Tag>,
        },
        { title: '处置类型', dataIndex: 'disposalType' },
        { title: '处置人', dataIndex: 'disposalUserName' },
        { title: '处置金额', dataIndex: 'amountWan' },
        { title: '处置日期', dataIndex: 'disposalDate' },
        {
          title: '附件',
          key: 'attachments',
          render: (_value, row: DisposalOrderView) => (
            <Space direction="vertical" size={2}>
              {row.attachments.map((item) => (
                <a key={item.fileId} href={item.url} target="_blank" rel="noreferrer">
                  {item.name}
                </a>
              ))}
            </Space>
          ),
        },
        {
          title: '操作',
          key: 'actions',
          render: (_value, row: DisposalOrderView) => (
            <Space>
              {/* 审批按钮只在有 operation.disposal:approve 时出现；
                  没有该权限的账号连按钮都看不到，后端仍会强校验（设计 §5.3） */}
              {row.status === 'approving' && can('operation.disposal', 'approve') && (
                <Button type="link" size="small">
                  审批
                </Button>
              )}
              {row.status === 'draft' && can('operation.disposal', 'create') && (
                <Button type="link" size="small">
                  提交
                </Button>
              )}
              {(row.status === 'pending_execute' || row.status === 'executing')
                && can('operation.disposal', 'update') && (
                  <Button type="link" size="small">
                    执行
                  </Button>
                )}
            </Space>
          ),
        },
      ]}
    />
  );

  return (
    <div className="flex flex-col gap-4">
      {readOnly && (
        <Alert type="info" showIcon message="保存主体后即可录入后续记录" />
      )}
      <Tabs
        items={[
          {
            key: 'disposal',
            label: '处置记录',
            children: ownerType === 'asset' ? renderAssetDisposals() : renderDisposalLedger(),
          },
          { key: 'receive', label: '接收信息', children: renderReceives() },
          { key: 'source', label: '来源明细', children: renderSourceInfo() },
        ]}
      />
    </div>
  );
}
```

> 上述处置按钮目前只渲染不绑事件。**接线方式**：`POST /disposals/{id}/submit`、
> `/approve`、`/execute`、`/complete` 四个端点已由 Task 9 接入权限，前端在这里调用后
> 重新拉一次 `GET /assets/{id}/disposals` 刷新列表即可（本项目其余流程按钮同款做法）。
> 若本期只想先展示历史、不做表单内推进，就把这三个按钮去掉 —— 但**不要**留下
> 点了没反应的按钮。这是一处必须在实现时做出的取舍，写代码的人自己选一个并保持一致。

- [ ] **Step 4: 检查**

Run: `pnpm -C frontend lint && pnpm -C frontend format:check`
Expected: 无 error。

- [ ] **Step 5: 提交**

```bash
git add frontend/admin-web/src/components/RecordSheetSections.tsx
git commit -m "feat(admin-web): 三模块复用组件 RecordSheetSections"
```

---

## Task 15: `AssetFormPage` 改分步并接第 3 步

**Files:**
- Modify: `frontend/admin-web/src/pages/AssetFormPage.tsx`
- Verify: `pnpm -C frontend lint`、`pnpm -C frontend build`

**Interfaces:**
- Consumes: Task 13 / 14。
- Produces: 资产编辑页第 3 步「后续记录」。

- [ ] **Step 1: 加状态与两个 handler**

1. import 区补：

```tsx
import { RecordSheetSections } from '@/components/RecordSheetSections';
import {
  loadRecordSheet,
  recordSheetPath,
  saveRecordSheet,
  type RecordSheetPayload,
} from '@/lib/recordSheet';
```

并把 `Steps` 加进 antd 的具名 import。

2. 组件内新增状态（放在 `submitting` 之后）：

```tsx
  const [step, setStep] = useState(0);
  const [recordSheet, setRecordSheet] = useState<RecordSheetPayload | null>(null);
  const [sheetLoading, setSheetLoading] = useState(false);
```

3. 编辑态加载后拉一次 sheet（放在已有的资产加载 `useEffect` 之后）：

```tsx
  // 后续记录只在编辑态加载：新增态还没有 assetId，记录无处可挂（设计 §6.1）
  useEffect(() => {
    if (!isEdit || !id) {
      setRecordSheet(null);
      return;
    }
    let cancelled = false;
    setSheetLoading(true);
    loadRecordSheet(recordSheetPath('asset', Number(id)))
      .then((sheet) => {
        if (cancelled) return;
        setRecordSheet({
          receives: sheet.receives,
          sourceInfo: sheet.sourceInfo,
          disposalRecords: sheet.disposalRecords,
        });
      })
      .catch((e) => {
        if (!cancelled) message.error(e instanceof Error ? e.message : '加载后续记录失败');
      })
      .finally(() => {
        if (!cancelled) setSheetLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [isEdit, id]);
```

- [ ] **Step 2: 拆分 `handleSubmit` 为「先主体、后记录」两步**

把 `handleSubmit` 改为：

```tsx
  const handleSubmit = async () => {
    try {
      const values = (await form.validateFields()) as Record<string, unknown> & {
        registeredAt?: Dayjs;
        image?: ImageValue | null;
      };
      const { image, ...rest } = values;
      const payload = {
        ...rest,
        registeredAt: values.registeredAt ? values.registeredAt.format('YYYY-MM-DD') : null,
        imageUrl: image?.url ?? '',
        imageFileId: image?.fileId,
      };
      setSubmitting(true);
      if (isEdit) {
        // 顺序不能反：记录依赖主体已存在（设计 §5.4）
        await api.put(`/assets/${id}`, payload);
        try {
          if (recordSheet) {
            await saveRecordSheet(recordSheetPath('asset', Number(id)), recordSheet);
          }
          message.success('保存成功');
        } catch (e) {
          // 主体已保存、记录失败的半成品状态必须让使用者知道，
          // 否则会以为整单回滚、重新编辑一遍（设计 §6.1）
          message.warning(
            `资产已保存，但后续记录保存失败：${e instanceof Error ? e.message : '未知错误'}，请重试`,
          );
          return;
        }
      } else {
        await api.post('/assets', payload);
        message.success('创建成功');
      }
      navigate('/assets');
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSubmitting(false);
    }
  };
```

> `recordSheet` 是**组件状态**，不是 antd `Form` 的受控字段 —— 它有自己的 diff 语义，
> 塞进 `Form` 会让 `validateFields` 的回调把整张 sheet 当普通字段校验一遍。
> 因此第 3 步用 `value={recordSheet}` / `onChange={setRecordSheet}` 直接绑定，
> `validateFields()` 拿到的 `values` 里**不会**有 `recordSheet`，解构只需排除 `image`。

- [ ] **Step 3: 用 `Steps` 包住原有五个 `Card`**

1. 在 `<Form ...>` 之前插入步骤条：

```tsx
      <Steps
        current={step}
        onChange={setStep}
        className="mb-4"
        items={[
          { title: '归属与基本信息' },
          { title: '资产属性与管理信息' },
          { title: '后续记录' },
        ]}
      />
```

2. 把「归属信息」与「基本信息」两个 `Card` 用 `{step === 0 && (<> ... </>)}` 包住；
   把「资产属性 / 管理信息 / 计量与图片」三个 `Card` 用 `{step === 1 && (<> ... </>)}` 包住。

   **用条件渲染而不是 `display:none`**：antd `Form.Item` 在卸载时会解除注册，
   但 `form.validateFields()` 默认只校验**已注册**的字段，因此切到第 3 步时第 1 步的必填项不再阻塞提交 ——
   这是「分步表单」的预期行为。若要改成「隐藏不卸载」（设计 §6.1 提到的做法），
   必须同时给每步的提交按钮加「先跳回出错步骤」的逻辑，否则用户会在第 3 步看到
   一个无法定位来源的「请填写资产名称」错误。

3. 在第 2 步之后插入第 3 步：

```tsx
        {step === 2 && (
          <Card title="后续记录" className="border border-[var(--ams-border)] mb-4" loading={sheetLoading}>
            {isEdit ? (
              <RecordSheetSections
                ownerType="asset"
                ownerId={Number(id)}
                companyId={Form.useWatch('assetCompanyId', form)}
                disabled={false}
                value={recordSheet}
                onChange={setRecordSheet}
              />
            ) : (
              <Alert
                type="info"
                showIcon
                message="保存资产后可录入后续记录"
                description="处置、接收与来源信息都需要先有一个已保存的资产作为归属对象。请先在第二步提交，再回到本步录入。"
              />
            )}
          </Card>
        )}
```

> `Form.useWatch` 不能在 JSX 的任意位置调用（它是 hook）。把 `assetCompanyId` 已经在组件顶部
> 用 `Form.useWatch('assetCompanyId', form)` 取过了，第 3 步直接复用那个变量，不要在里面再写一次。

4. 调整底部按钮：加「上一步 / 下一步」，并把保存按钮只在第 3 步显示：

```tsx
      <div className="flex justify-end gap-2">
        <Button onClick={goBack}>取消</Button>
        {step > 0 && <Button onClick={() => setStep(step - 1)}>上一步</Button>}
        {step < 2 && (
          <Button type="primary" onClick={() => setStep(step + 1)}>
            下一步
          </Button>
        )}
        {step === 2 && (
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={submitting}
            onClick={() => void handleSubmit()}
          >
            {isEdit ? '保存' : '提交'}
          </Button>
        )}
      </div>
```

- [ ] **Step 4: 检查**

Run: `pnpm -C frontend lint && pnpm -C frontend build`
Expected: 构建成功，无 TS 错误。

- [ ] **Step 5: 提交**

```bash
git add frontend/admin-web/src/pages/AssetFormPage.tsx
git commit -m "feat(admin-web): 资产表单改三步并接入后续记录"
```

---

## Task 16: `ProjectFormPage` 第 3 步 + 分区「详情」入口

**Files:**
- Modify: `frontend/admin-web/src/pages/ProjectFormPage.tsx`
- Verify: `pnpm -C frontend lint`、`pnpm -C frontend build`

**Interfaces:**
- Consumes: Task 13 / 14。
- Produces: 项目编辑页第 3 步「后续记录」；分区表格行内「详情」跳转。

- [ ] **Step 1: 加第 3 步**

1. import 区补 `RecordSheetSections` 与 `recordSheetPath` / `loadRecordSheet` / `saveRecordSheet` / `RecordSheetPayload` / `useNavigate`（若尚未引入）。
2. `Steps` 增加第三项：

```tsx
        items={[
          { title: '基本信息' },
          { title: '分区配置' },
          { title: '后续记录' },
        ]}
```

3. 第 2 步之后插入第 3 步，与 Task 15 的第 3 步结构一致，只有两处不同：

```tsx
              <RecordSheetSections
                ownerType="project"
                ownerId={Number(id)}
                companyId={projectCompanyId}
                value={recordSheet}
                onChange={setRecordSheet}
              />
```

4. `handleSubmit` 按 Task 15 Step 2 的「先主体、后记录」两步编排，主体请求是 `api.put('/projects/${id}', payload)`，记录路径是 `recordSheetPath('project', Number(id))`。
   **注意**：项目主体保存里含 `zones`，而 Task 6 之后「移除有资产或有记录的分区」会返回 400 —— 这类错误必须原样透出（`message.error(e.message)`），不要吞掉或改写文案，否则用户看不到「哪个分区、什么原因」。

- [ ] **Step 2: 分区表格加「详情」入口**

在分区表格的 `columns` 里，操作列改为：

```tsx
        {
          title: '操作',
          key: 'actions',
          render: (_: unknown, row: ProjectZoneRow) => (
            <Space>
              <Button
                type="link"
                size="small"
                onClick={() =>
                  navigate(`/projects/${id}/zones/${row.id}`, {
                    state: { from: currentPath(location) },
                  })
                }
              >
                详情
              </Button>
              {/* 既有的编辑 / 删除按钮保持原样 */}
            </Space>
          ),
        },
```

（`navigate`、`location`、`currentPath` 三者的用法与仓库里其他详情页跳转一致；分区详情页靠 `location.state.from` 实现「返回原页面」。）

- [ ] **Step 3: 检查**

Run: `pnpm -C frontend lint && pnpm -C frontend build`
Expected: 构建成功。

- [ ] **Step 4: 提交**

```bash
git add frontend/admin-web/src/pages/ProjectFormPage.tsx
git commit -m "feat(admin-web): 项目表单加后续记录步骤与分区详情入口"
```

---

## Task 17: `ZoneDetailPage` 与路由

**Files:**
- Create: `frontend/admin-web/src/pages/ZoneDetailPage.tsx`
- Modify: `frontend/admin-web/src/App.tsx`
- Verify: `pnpm -C frontend lint`、`pnpm -C frontend build`、`node scripts/check-perm-invariants.mjs`

**Interfaces:**
- Consumes: `GET /projects/{pid}/zones`（取分区基本信息，已上线）、`PUT /projects/{pid}/zones/{zoneId}`（保存基本信息，已上线）、Task 13 / 14。
- Produces: 路由 `/projects/:projectId/zones/:zoneId`。

**路由登记口径（必须遵守）**：这是**钻取页**，不挂侧边栏，因此
- **不**加入 `STANDALONE_ROUTES`（`routeRegistry.ts`）；
- **不**加入 `PATH_TO_CODE`（`routeRegistry.ts`）；
- 只在 `App.tsx` 加一条带 `:param` 的 `<Route>`。
`check-perm-invariants.mjs` 会跳过含 `:` 的路径，因此不会产生告警。

- [ ] **Step 1: 写页面**

新建 `frontend/admin-web/src/pages/ZoneDetailPage.tsx`：

```tsx
import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Alert, Button, Card, Descriptions, Form, Input, InputNumber, Space, Spin, message } from 'antd';
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons';
import { api } from '@/lib/api';
import { RecordSheetSections } from '@/components/RecordSheetSections';
import { useBackNavigate } from '@/lib/navigation';
import {
  loadRecordSheet,
  recordSheetPath,
  saveRecordSheet,
  type RecordSheetPayload,
} from '@/lib/recordSheet';

// ============================================================================
// 分区详情 / 编辑页（设计 §6.3）。路由 /projects/:projectId/zones/:zoneId，钻取页不挂侧边栏。
//
// 上半：分区基本信息（走已上线的 PUT /projects/{pid}/zones/{zoneId}）
// 下半：后续记录三模块（走 /projects/{pid}/zones/{zoneId}/record-sheet）
//
// 两块**各自独立保存**：分区本体与后续记录是两件事，塞进一次交互会让「只想改备注」
// 也走一遍记录 diff（设计 §6.3）。
// ============================================================================

interface ZoneRow {
  id: number;
  projectId: number;
  name?: string;
  code?: string;
  sort?: number;
  remark?: string;
  assetCount?: number;
  assetArea?: number;
}

export default function ZoneDetailPage() {
  const { projectId, zoneId } = useParams();
  const goBack = useBackNavigate(`/projects/${projectId}/edit`);

  const [form] = Form.useForm();
  const [zone, setZone] = useState<ZoneRow | null>(null);
  const [loading, setLoading] = useState(true);
  const [savingZone, setSavingZone] = useState(false);
  const [savingSheet, setSavingSheet] = useState(false);
  const [recordSheet, setRecordSheet] = useState<RecordSheetPayload | null>(null);

  const pid = Number(projectId);
  const zid = Number(zoneId);

  // 分区列表已上线，按 id 过滤即可；不为单条分区新增端点（设计 §6.3）
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api
      .get<ZoneRow[]>(`/projects/${pid}/zones`)
      .then((rows) => {
        if (cancelled) return;
        const hit = (rows ?? []).find((item) => item.id === zid) ?? null;
        setZone(hit);
        if (hit) {
          form.setFieldsValue({
            name: hit.name,
            code: hit.code,
            sort: hit.sort,
            remark: hit.remark,
          });
        }
      })
      .catch((e) => {
        if (!cancelled) message.error(e instanceof Error ? e.message : '加载分区失败');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [pid, zid, form]);

  useEffect(() => {
    let cancelled = false;
    loadRecordSheet(recordSheetPath('zone', zid, pid))
      .then((sheet) => {
        if (cancelled) return;
        setRecordSheet({
          receives: sheet.receives,
          sourceInfo: sheet.sourceInfo,
          disposalRecords: sheet.disposalRecords,
        });
      })
      .catch((e) => {
        if (!cancelled) message.error(e instanceof Error ? e.message : '加载后续记录失败');
      });
    return () => {
      cancelled = true;
    };
  }, [pid, zid]);

  const handleSaveZone = async () => {
    try {
      const values = await form.validateFields();
      setSavingZone(true);
      await api.put(`/projects/${pid}/zones/${zid}`, values);
      message.success('分区信息已保存');
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSavingZone(false);
    }
  };

  const handleSaveSheet = async () => {
    if (!recordSheet) return;
    try {
      setSavingSheet(true);
      const saved = await saveRecordSheet(recordSheetPath('zone', zid, pid), recordSheet);
      setRecordSheet({
        receives: saved.receives,
        sourceInfo: saved.sourceInfo,
        disposalRecords: saved.disposalRecords,
      });
      message.success('后续记录已保存');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSavingSheet(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-24">
        <Spin size="large" />
      </div>
    );
  }

  if (!zone) {
    return <Alert type="warning" showIcon message="分区不存在或已被删除" />;
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={goBack} aria-label="返回原页面">
            返回
          </Button>
          <h2 className="text-base font-semibold m-0">分区详情 · {zone.name}</h2>
        </Space>
      </div>

      <Card title="分区信息" className="border border-[var(--ams-border)]">
        <Form form={form} layout="vertical">
          <div className="grid gap-4 md:grid-cols-2">
            <Form.Item name="name" label="分区名称" rules={[{ required: true, message: '请输入分区名称' }]}>
              <Input />
            </Form.Item>
            <Form.Item name="code" label="分区编码">
              <Input />
            </Form.Item>
            <Form.Item name="sort" label="排序">
              <InputNumber className="w-full" min={0} />
            </Form.Item>
            <Form.Item name="remark" label="备注">
              <Input />
            </Form.Item>
          </div>
        </Form>
        <Descriptions column={2} size="small">
          <Descriptions.Item label="资产面积">{zone.assetArea ?? 0}</Descriptions.Item>
          <Descriptions.Item label="资产宗数">{zone.assetCount ?? 0}</Descriptions.Item>
        </Descriptions>
        <div className="flex justify-end mt-3">
          <Button type="primary" icon={<SaveOutlined />} loading={savingZone} onClick={() => void handleSaveZone()}>
            保存分区信息
          </Button>
        </div>
      </Card>

      <Card title="后续记录" className="border border-[var(--ams-border)]">
        <RecordSheetSections
          ownerType="zone"
          ownerId={zid}
          projectId={pid}
          value={recordSheet}
          onChange={setRecordSheet}
        />
        <div className="flex justify-end mt-3">
          <Button type="primary" icon={<SaveOutlined />} loading={savingSheet} onClick={() => void handleSaveSheet()}>
            保存后续记录
          </Button>
        </div>
      </Card>
    </div>
  );
}
```

- [ ] **Step 2: 加路由**

在 `App.tsx` 的 import 区加：

```tsx
import ZoneDetailPage from '@/pages/ZoneDetailPage';
```

在项目相关路由之后加（与既有的 `/projects/:id` 保持同样的「钻取页」注释口径）：

```tsx
              {/* 分区详情页：钻取页，不挂侧边栏、不进 PATH_TO_CODE 镜像 */}
              <Route path="projects/:projectId/zones/:zoneId" element={<ZoneDetailPage />} />
```

- [ ] **Step 3: 检查**

Run: `pnpm -C frontend lint && pnpm -C frontend build && node scripts/check-perm-invariants.mjs`
Expected: 构建成功；守卫脚本 `检查通过`（若出现「失败」段落，按提示修正）。

- [ ] **Step 4: 提交**

```bash
git add frontend/admin-web/src/pages/ZoneDetailPage.tsx frontend/admin-web/src/App.tsx
git commit -m "feat(admin-web): 分区详情页与记录录入路由"
```

---

## Task 18: OpenAPI 契约与设计文档回写

**Files:**
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/需求规格说明书.md`
- Verify: `pnpm -C frontend api:lint`

**Interfaces:**
- Consumes: Task 8 / 9 的 6+1 条路径。
- Produces: 与实现一致的契约文档。

**为什么仍要做**：ADR-0005 约定「契约先行」。本期上线的分区 CRUD 端点当时没有同步 OpenAPI（属已知缺口），本 Task 只补齐本设计新增的 7 条，不回溯历史欠账。

- [ ] **Step 1: 在 `paths:` 下补 4 条路径**

按文件里既有条目的缩进与 `tags` 风格，在 `paths:` 块末尾（`components:` 之前）插入：

```yaml
  /api/v1/assets/{assetId}/record-sheet:
    get:
      tags: [record]
      summary: 资产后续记录（处置/接收/来源）聚合读
      parameters:
        - { name: assetId, in: path, required: true, schema: { type: integer, format: int64 } }
      responses:
        '200': { description: OK }
    put:
      tags: [record]
      summary: 资产后续记录聚合写（单事务，全量 diff；disposalRecords 段被忽略）
      parameters:
        - { name: assetId, in: path, required: true, schema: { type: integer, format: int64 } }
      responses:
        '200': { description: OK }
  /api/v1/projects/{projectId}/record-sheet:
    get:
      tags: [record]
      summary: 项目后续记录聚合读
      parameters:
        - { name: projectId, in: path, required: true, schema: { type: integer, format: int64 } }
      responses:
        '200': { description: OK }
    put:
      tags: [record]
      summary: 项目后续记录聚合写
      parameters:
        - { name: projectId, in: path, required: true, schema: { type: integer, format: int64 } }
      responses:
        '200': { description: OK }
  /api/v1/projects/{projectId}/zones/{zoneId}/record-sheet:
    get:
      tags: [record]
      summary: 分区后续记录聚合读
      parameters:
        - { name: projectId, in: path, required: true, schema: { type: integer, format: int64 } }
        - { name: zoneId, in: path, required: true, schema: { type: integer, format: int64 } }
      responses:
        '200': { description: OK }
    put:
      tags: [record]
      summary: 分区后续记录聚合写
      parameters:
        - { name: projectId, in: path, required: true, schema: { type: integer, format: int64 } }
        - { name: zoneId, in: path, required: true, schema: { type: integer, format: int64 } }
      responses:
        '200': { description: OK }
  /api/v1/assets/{assetId}/disposals:
    get:
      tags: [disposal]
      summary: 某资产的处置单列表（只读，推进流程走 /disposals）
      parameters:
        - { name: assetId, in: path, required: true, schema: { type: integer, format: int64 } }
      responses:
        '200': { description: OK }
```

- [ ] **Step 2: 校验契约**

Run: `pnpm -C frontend api:lint`
Expected: 无 error。

- [ ] **Step 3: 回写需求规格说明书**

在 `docs/需求规格说明书.md` 中：
1. 找到「资产台账」与「项目管理」的字段表，各追加一行本节功能（如 `FR-AST-004 后续记录（处置/接收/来源）`）。
2. 把「处置记录」相关段落改为引用本设计：资产的处置复用 `disposal_order` 流程，项目/分区为 `biz_disposal_record` 台账。
3. 把 `docs/database/ams.dbml` 补上 5 张新表与 `disposal_order` 新增列。

> 这一步不写代码，但**必须做**：设计文档里点名的「文档交付清单」要求 DBML 与需求规格同步，
> 漏掉会让下一次有人按旧 DBML 写查询。

- [ ] **Step 4: 提交**

```bash
git add docs/api/openapi.yaml docs/需求规格说明书.md docs/database/ams.dbml
git commit -m "docs: 后续记录接口契约、需求条目与 DBML 同步"
```

---

## Self-Review

**1. Spec coverage（设计 → 任务）**

| 设计章节 | 落点 |
|----------|------|
| §4.1 五张表 / 字典 | Task 1 |
| §4.2 `disposal_order` 扩列 + 项目/分区台账 | Task 1、Task 9 |
| §4.3 通用附件模型 | Task 1、Task 3、Task 7 |
| §4.4 混合相对人 | Task 7（`actorName`）、Task 12 |
| §5.1 模块划分 | Task 2-8（结构见 File Structure） |
| §5.2 record-sheet 接口与动作映射 | Task 8、Task 13 |
| §5.3 权限与数据范围 | Task 5、Task 8、Task 9、Task 10 |
| §5.4 事务与并发 | Task 7（`@Transactional` + 全量 diff） |
| §6.1 资产表单分步 | Task 15 |
| §6.2 项目表单第 3 步 | Task 16 |
| §6.3 分区详情页 | Task 17 |
| §6.4 附件组件 | Task 11 |
| §6.5 相对人组件 | Task 12 |
| §6.6 三模块复用组件 | Task 14 |
| §7.1 资产处置流程 | Task 9（面板在 Task 14） |
| §7.2 新建资产的顺序约束 | Task 5（宿主不存在 404）、Task 15（新增态禁用第 3 步） |
| §7.3 分区删除级联 | Task 6 |
| §7.4 记录与附件删除（软删） | Task 7（`markDeleted`） |
| §8 实施顺序 | 任务顺序即实施顺序 |
| §9 验收 1-10 | Task 15(1)、Task 14(2)、Task 9+10(3)、Task 7(4)、Task 7(5)、Task 7(6)、Task 6(7)、Task 5+8(8)、Task 8+9(9)、Task 7(10) |

**2. 已知缺口（本计划刻意不做，避免范围蔓延）**

- `AssetDossierPage` / `ProjectDetailPage` 不展示后续记录（设计 §10 已列为本期不做）。
- 附件孤儿文件清理（设计 §10）。
- 遗留问题的整改闭环（设计 §10）。

**3. 类型一致性自查结论**

- `RecordOwnerType` 的三个值与两处权限码在 Task 3 定义，Task 5 / 7 / 8 全部直接引用，未出现重复字面量。
- `AttachmentOwner.bizType()` 是 `biz_attachment.biz_type` 的唯一来源，Task 7 的 `syncAttachments` 是唯一写入点。
- `RecordSheetPayload`（前端）与 `RecordSheetRequest`（后端）字段逐字对应：`receives` / `sourceInfo` / `disposalRecords`。
- 软删一律 `deleted_at`，唯一写入点是 Task 7 的 `markDeleted` 与 Task 6 的 `setSql("deleted_at = now()")`，**没有**任何 `deleteBatchIds` 调用。硬删只剩两处：`AssetService.deleteProject`（走同一守卫确认空壳后硬删分区与项目，设计 §7.3 的显式例外）与既有的资产 `deleteById`。

**4. 执行前必须人工确认的三项**

1. 本机无 JDK / Maven，后端每个「Run test」步骤都要在 CI 触发（push 分支或开 PR）。
2. Task 10 Step 2 只加三个 `operation.disposal:*` 动作码，**不要**加 `:view`。
3. Task 14 Step 3 末尾的处置按钮接线方式需要在实现时做出取舍（接上四个流转端点，或本期只做只读回显并去掉按钮）—— 不允许留下「点了没反应」的按钮。

**5. 代码块与实体字段的对照自查（防止「实体没有这个字段」）**

- `DisposalOrderView.amountWan` 由 `DisposalService.listByAsset` 从 `disposal_order.actual_amount` 赋值（Task 9），
  `lib/recordSheet.ts` 的 `normalizeSheet` 又做了 `amountWan ?? actualAmount` 兜底 —— 两处都写是为了让
  资产侧与项目侧在前端走同一套渲染，**不要**只保留其中一处。
- `SourceInput` 只有 `id` / `sourcePersonId` / `sourcePersonName` / `sourceUnit` / `sourceDate` /
  `sourceDesc` / `attachments`，**没有** `ownerType` / `ownerId`（它们由服务端按路径赋值）。
  任何试图给 DTO 加这两个字段的改动都会引入「客户端可指定归属」的越权面。
- `ProjectZone` **没有** `deletedAt` 字段（Task 6 的设计取舍）。分区的一切软删过滤走
  `ProjectZoneMapper.selectActiveById` / `selectActiveInProject` 与 `.apply("deleted_at IS NULL")`。
