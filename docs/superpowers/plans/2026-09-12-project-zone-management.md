# 项目分区管理页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增侧栏页「项目分区管理」（`/project-zones`）：左侧项目列表、右上分区 Tab、右下该分区资产表，支持分区与资产的增删改（复用既有接口与既有资产表单页）。

**Architecture:** 后端**零业务改动**，只加一个菜单种子迁移；前端新增 3 个组件与 1 个共享逻辑模块，把分区的读写逻辑从 `ProjectZonesPanel` 抽出为单一来源；资产写操作通过 URL 参数复用 `AssetFormPage`。选中态（`projectId` / `zoneId`）以 URL query 为唯一真相，因为跳去资产表单页再返回时本页会整页重新挂载。

**Tech Stack:** 后端 Java 21 + Spring Boot 3.3 + Flyway + PostgreSQL + MyBatis-Plus（仅迁移，无 Java 业务代码）；前端 React 18 + TypeScript + Vite + antd 6 + Tailwind + react-router-dom 6。

**Spec:** `docs/superpowers/specs/2026-09-12-project-zone-management-design.md`

## Global Constraints

以下约束来自 spec，**每个任务都隐含包含本节**，违反任一条即为任务失败：

- **后端零业务改动**：不新增接口、不新增 `@RequiresPerm`、不改 `AssetService` / `AssetController` / 实体 / Mapper。后端唯一产物是 1 个迁移文件 + 1 个测试。
- **权限码白名单**：前端只允许判定这 6 个已强制码 —— `asset.project:view`、`asset.project:update`、`asset.ledger:view`、`asset.ledger:create`、`asset.ledger:update`、`asset.ledger:delete`。
- **禁止写 `perm` 字面量指向新码**：不得出现 `perm="asset.projectZone:..."` 或 `perm: 'asset.projectZone:...'`。`asset.projectZone` 只存在于菜单表与 `PATH_TO_CODE` 镜像，**没有任何后端注解强制它**；一旦写进 JSX，`scripts/check-perm-invariants.mjs` 的「前端声明 ⊆ 后端 `@RequiresPerm`」检查会硬失败。动作级判定一律走 `usePerm(code, action)` 函数式调用。
- **页面级守卫不写 `code`**：`<RequirePerm>` 无参形态（`AdminLayout` 已就位，本计划不改它），由 `PATH_TO_CODE` 从 path 推导 `asset.projectZone:view`。
- **6 处注册缺一即静默失效**：`App.tsx` 路由、`STANDALONE_ROUTES`、`PATH_TO_CODE`、静态 `MENU`、`PATH_ICONS`、V47 迁移（详见 spec §3.2）。
- **分区只读字段不进表单**：`assetArea` / `assetCount` 由后端汇总，只用于展示与 Tab 徽标。
- **不新增前端依赖**，不引入任何测试框架；前端验证 = `tsc -b` + `eslint` + `check-perm-invariants.mjs` + 手动路径。
- **UI 文案一律中文**，与现有页面风格一致。
- **命令（照抄，不要改写）**：
  - 前端构建：`cd frontend && pnpm --filter admin-web build`
  - 前端 lint：`cd frontend && pnpm lint`
  - 权限/路由守卫：`node scripts/check-perm-invariants.mjs`（仓库根目录）
  - 后端单测：`cd backend && mvn test -Dtest=V47MigrationContractTest`
  - 后端全量：`cd backend && mvn test`
- **`ProjectZone` 类型只有一处定义**：`frontend/admin-web/src/lib/projectZones.ts`。`ProjectZonesPanel.tsx` 与 `ProjectFormPage.tsx` 里的本地副本必须删除。

---

## File Structure

| 文件 | 责任 | 任务 |
|------|------|------|
| `backend/src/main/resources/db/migration/V47__project_zone_menu.sql` | 菜单行 + view 回填 | Task 1 |
| `backend/src/test/java/com/ams/modules/asset/V47MigrationContractTest.java` | 迁移契约守卫（资源文件文本断言，不加载 Spring） | Task 1 |
| `frontend/admin-web/src/lib/projectZones.ts` | `ProjectZone` 类型 + `useProjectZones` 钩子（分区读写唯一来源） | Task 2 |
| `frontend/admin-web/src/components/ZoneFormModal.tsx` | 分区新增/编辑弹窗（两个入口共用） | Task 2 |
| `frontend/admin-web/src/components/ProjectZonesPanel.tsx` | 项目列表展开行内的分区块（**改造为消费共享逻辑**，呈现形态不变） | Task 2 |
| `frontend/admin-web/src/pages/ProjectFormPage.tsx` | 项目两步走向导（**仅删除本地 `ProjectZone` 副本**） | Task 2 |
| `frontend/admin-web/src/pages/AssetFormPage.tsx` | 资产表单页（**加 query 预填/锁定 + 按来源返回**） | Task 3 |
| `frontend/admin-web/src/components/ProjectListPane.tsx` | 左栏项目列表（搜索 + 列表 + 分页） | Task 4 |
| `frontend/admin-web/src/components/ZoneAssetPane.tsx` | 右上分区 Tab 栏 + 右下资产表（含分区 CRUD 与资产删改跳转） | Task 5 |
| `frontend/admin-web/src/pages/ProjectZonesPage.tsx` | 页面编排：URL 选中态 + 左右布局 | Task 6 |
| `frontend/admin-web/src/App.tsx` | 新增路由 | Task 6 |
| `frontend/admin-web/src/lib/routeRegistry.ts` | `STANDALONE_ROUTES` + 注释计数 | Task 6 |
| `frontend/admin-web/src/lib/pathToCode.ts` | 镜像新增条目 | Task 6 |
| `frontend/admin-web/src/pages/modules.tsx` | 静态 `MENU` 新增条目 | Task 6 |
| `frontend/admin-web/src/lib/menuIcons.tsx` | `PATH_ICONS` 新增图标 | Task 6 |

**任务顺序的理由**：Task 2 先建立共享逻辑（Task 5 依赖它）；Task 3 独立（只动 `AssetFormPage`，可并行）；Task 4 / 5 各建一个组件；Task 6 收口接线，此时页面才能编译通过 —— 因此 Task 6 之前 `pnpm build` 会因 `ProjectListPane` / `ZoneAssetPane` 未被引用而正常通过（未使用文件不报错），但 Task 6 之后才会真正渲染。

---

## Task 1: V47 迁移 + 契约测试

**Files:**
- Create: `backend/src/main/resources/db/migration/V47__project_zone_menu.sql`
- Test: `backend/src/test/java/com/ams/modules/asset/V47MigrationContractTest.java`

**Interfaces:**
- Consumes: 无（纯新增文件）
- Produces: 菜单行 `menu.code = 'asset.projectZone'`（`menu_type = 'menu'`、`path = '/project-zones'`、父目录 `asset`、`sort = 15`）+ 该菜单对所有非超管角色的 `view` 授权。Task 6 的 `PATH_TO_CODE` 镜像值必须与本文件的 `code` / `path` 逐字一致。

> **为什么测试放在 `modules/asset/`**：被种子的菜单是 `asset.projectZone`、页面属资产域，与被守卫的特性就近（同目录已有 `AssetServiceZoneTest`）。菜单只是它的落地形式，测试的语义是「分区管理入口必须存在且可见」。
>
> **为什么断言迁移文本而不是查库**：测试 profile 关闭了 Flyway（H2 内存库无 schema），没有任何运行期校验能证明这些行真的落库。文本断言抓的是「合并冲突时被误删一行」「SQL 被换成扫全表」这类真实事故，而不是 SQL 语法（那交给部署时的 Flyway）。

- [ ] **Step 1: 写失败的测试**

创建 `backend/src/test/java/com/ams/modules/asset/V47MigrationContractTest.java`：

```java
package com.ams.modules.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V47 迁移的契约守卫（设计 §7）。
 *
 * <p>本迁移只做两件事：种一行「项目分区管理」菜单、把它的 view 授权回填给非超管角色。
 * 这三条断言分别对应三类真实事故：
 * <ol>
 *   <li>菜单行的 code / path 与前端 {@code PATH_TO_CODE} 镜像漂移 —— 表现为点菜单落回首页，
 *       或权限判定恒真（镜像缺条目时 {@code canByPath} 放行）；</li>
 *   <li>漏回填 view —— 除超管外所有角色看不到新入口，功能表现为「没做出来」；
 *       反向误回填写动作 —— 静默越权，且长期留在库里没人复核（V45 §5.3 明令禁止）；</li>
 *   <li>把业务表 DML 混进菜单迁移 —— 合并冲突或复制粘贴的典型产物。</li>
 * </ol>
 *
 * <p>刻意断言**具体串**而不是整段 SQL：注释调整不该误报，而「扫全表回填」「硬编码 parent_id」
 * 这类写法必须变红。
 */
class V47MigrationContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V47__project_zone_menu.sql";

    /** 本页菜单码：必须与前端 `PATH_TO_CODE['/project-zones']` 逐字一致。 */
    private static final String MENU_CODE = "asset.projectZone";
    private static final String MENU_PATH = "/project-zones";

    private static String SQL;

    @BeforeAll
    static void loadMigration() {
        SQL = readMigration();
    }

    private static String readMigration() {
        try (InputStream in = V47MigrationContractTest.class.getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("V47 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("菜单行按设计 §3.1 落种子：code / name / type / path / 排序 / 父目录")
    void seedsProjectZoneMenu() {
        assertThat(SQL)
                .as("必须插入 asset.projectZone 菜单行，且路径与名称与设计一致")
                .contains("'" + MENU_CODE + "'", "'项目分区管理'", "menu", "'" + MENU_PATH + "'");
        // 父目录由 code 解析，不能硬编码 parent_id（V45 的 id 在其它环境不保证一致）
        assertThat(SQL)
                .as("父目录必须按 code 解析（d.code = 'asset'），不得硬编码 parent_id")
                .contains("WHERE d.code = 'asset'")
                .doesNotContain("parent_id) VALUES");
        // 排序 15：落在「项目管理」(10) 与「资产台账」(20) 之间
        assertThat(SQL)
                .as("排序必须是 15，插在项目管理(10) 与资产台账(20) 之间")
                .contains(", 15, d.id");
        assertThat(SQL)
                .as("菜单种子必须幂等，重复执行不报错")
                .contains("ON CONFLICT (code) DO NOTHING");
    }

    @Test
    @DisplayName("view 回填：只回填 view、排除 super_admin、范围只限本菜单")
    void backfillsViewForNonSuperAdmin() {
        assertThat(SQL)
                .as("回填语句必须写 role_permission(role_id, menu_id, menu_code, action)")
                .contains("INSERT INTO role_permission (role_id, menu_id, menu_code, action)");
        assertThat(SQL)
                .as("必须只回填 view")
                .contains("m.code, 'view'");
        assertThat(SQL)
                .as("必须排除 super_admin（它走 isSuperAdmin 旁路，不需要数据行）")
                .contains("r.code <> 'super_admin'");
        assertThat(SQL)
                .as("回填范围必须限定在本菜单，不得扫全表")
                .contains("m.code = '" + MENU_CODE + "'");
        assertThat(SQL)
                .as("写动作一律不回填（V45 §5.3）：出现带引号的 create/update/delete 即为越权回填")
                .doesNotContain("'create'", "'update'", "'delete'");
        assertThat(SQL)
                .as("回填必须幂等，重跑不产生重复授权")
                .contains("ON CONFLICT DO NOTHING");
    }

    @Test
    @DisplayName("不触碰业务表与既有菜单：无 DDL，且只允许两条 INSERT")
    void touchesNoBusinessTables() {
        assertThat(SQL)
                .as("本迁移只种菜单，不做任何 DDL")
                .doesNotContain("ALTER TABLE", "CREATE TABLE", "DROP TABLE", "CREATE INDEX", "DROP INDEX");
        assertThat(SQL)
                .as("不得改动分区 / 项目 / 资产数据")
                .doesNotContain("project_zone", "INSERT INTO project", "UPDATE project", "DELETE FROM project")
                .doesNotContain("INTO asset", "UPDATE asset", "DELETE FROM asset")
                .doesNotContain("menu_type = 'menu'");
        assertThat(SQL.split("INSERT INTO", -1))
                .as("只允许两条 INSERT：菜单行 + view 回填")
                .hasSize(3);
    }
}
```

- [ ] **Step 2: 运行测试，确认以「迁移文件不存在」失败**

```bash
cd backend && mvn test -Dtest=V47MigrationContractTest
```

Expected: **FAIL**，`V47MigrationContractTest.seedsProjectZoneMenu` 报 `V47 迁移文件必须存在（放在 src/main/resources/db/migration 下）`，并且 3 个用例都因 `SQL` 初始化失败而报错。

- [ ] **Step 3: 创建迁移文件**

创建 `backend/src/main/resources/db/migration/V47__project_zone_menu.sql`：

```sql
-- ============================================================================
-- V47 项目分区管理菜单
-- 设计：docs/superpowers/specs/2026-09-12-project-zone-management-design.md §3.1
--
-- 新增侧栏页「项目分区管理」（code = asset.projectZone，路径 /project-zones），
-- 挂在「资产台账」(asset) 目录下，排序 15 —— 落在「项目管理」(10) 与「资产台账」(20) 之间。
--
-- 本迁移只碰 menu 与 role_permission 两张权限表：
--   - 不新增任何 @RequiresPerm 引用的编码（本页复用 asset.project:* 与 asset.ledger:*）；
--   - project_zone / project / asset 等业务表无 DDL、无 DML。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 菜单行
--
--    icon 留空：与 V45 口径一致（「图标只给目录：菜单留空，侧边栏回退到前端路由注册表的
--    path 图标」），前端 PATH_ICONS['/project-zones'] 提供兜底图标。
--    父目录按 code 解析，不硬编码 parent_id —— 各环境 menu.id 不保证一致。
-- ---------------------------------------------------------------------------
INSERT INTO menu (code, name, menu_type, path, icon, sort, parent_id)
SELECT 'asset.projectZone', '项目分区管理', 'menu', '/project-zones', NULL, 15, d.id
FROM menu d
WHERE d.code = 'asset'
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. view 回填
--
--    本菜单在 V45 §5.1 的批量回填之后才出现，不回填则除 super_admin 外所有角色都看不到
--    入口 —— 新功能会表现为「没做出来」，而不是报错。
--
--    与 V45 §5.1 保持同一口径：只回填 view、显式排除 super_admin、范围只限本菜单。
--    写动作（create / update / delete）一律不回填 —— V45 §5.3 明确把「动作级回填」列为
--    上线前置人工步骤，误回填的宽权限是静默的且会长期留在库里。
-- ---------------------------------------------------------------------------
INSERT INTO role_permission (role_id, menu_id, menu_code, action)
SELECT r.id, m.id, m.code, 'view'
FROM role r
CROSS JOIN menu m
WHERE r.code <> 'super_admin'
  AND m.code = 'asset.projectZone'
ON CONFLICT DO NOTHING;
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd backend && mvn test -Dtest=V47MigrationContractTest
```

Expected: **PASS**，3 个测试全部通过。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/resources/db/migration/V47__project_zone_menu.sql \
        backend/src/test/java/com/ams/modules/asset/V47MigrationContractTest.java
git commit -m "feat(asset): V47 项目分区管理菜单种子与非超管 view 回填"
```

---

## Task 2: 抽出共享分区逻辑与表单弹窗

**Files:**
- Create: `frontend/admin-web/src/lib/projectZones.ts`
- Create: `frontend/admin-web/src/components/ZoneFormModal.tsx`
- Modify: `frontend/admin-web/src/components/ProjectZonesPanel.tsx`（整体重写为消费共享逻辑）
- Modify: `frontend/admin-web/src/pages/ProjectFormPage.tsx:53-63`（删除本地 `ProjectZone` 副本，改为 import）

**Interfaces:**
- Consumes: 既有接口 `GET /projects/{id}/zones`、`POST /projects/{id}/zones`、`PUT /projects/{id}/zones/{zoneId}`、`DELETE /projects/{id}/zones/{zoneId}`
- Produces:
  - `interface ProjectZone { id?: number; projectId?: number; name: string; code?: string; sort?: number; remark?: string; assetArea?: number; assetCount?: number }`
  - `interface UseProjectZonesResult { zones: ProjectZone[]; loading: boolean; loadFailed: boolean; saving: boolean; reload: () => Promise<void>; save: (zone: ProjectZone) => Promise<boolean>; remove: (zone: ProjectZone) => Promise<void> }`
  - `function useProjectZones(projectId: number | null | undefined): UseProjectZonesResult`
  - `function ZoneFormModal(props: { editing: ProjectZone | null; submitting: boolean; onCancel: () => void; onSubmit: (values: ProjectZone) => void }): JSX.Element`

> **本任务是重构，不是新功能**：`ProjectZonesPanel` 的**呈现形态与行为必须完全不变**（同样的列、同样的按钮、同样的提示文案、同样的报错透传）。改动只是把逻辑搬进 `useProjectZones` / `ZoneFormModal`，让 Task 5 的新页面复用同一份逻辑与同一个弹窗。

- [ ] **Step 1: 创建 `lib/projectZones.ts`**

创建 `frontend/admin-web/src/lib/projectZones.ts`：

```ts
import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';

/**
 * 项目分区（project_zone）行数据。
 *
 * <p>`assetArea` / `assetCount` 是后端汇总出来的**只读**字段：分区面积不接受人工维护，
 * 统一取该分区下资产面积合计（见 V25 迁移与 AssetService#fillZoneAssetStats）。
 *
 * <p>**这是全仓唯一一份 `ProjectZone` 定义**：`ProjectZonesPanel.tsx` 与 `ProjectFormPage.tsx`
 * 曾各写一份，字段口径靠人工同步必然漂移；三者（含「项目分区管理」页）共用同一批接口，
 * 类型必须同源。
 */
export interface ProjectZone {
  id?: number;
  projectId?: number;
  name: string;
  code?: string;
  sort?: number;
  remark?: string;
  /** 只读：该分区下资产面积合计(㎡) */
  assetArea?: number;
  /** 只读：该分区下资产数量 */
  assetCount?: number;
}

export interface UseProjectZonesResult {
  zones: ProjectZone[];
  loading: boolean;
  /** 加载失败：由调用方在栏内渲染错误态 + 重试，不弹全局 message */
  loadFailed: boolean;
  /** 新增/编辑提交中：驱动弹窗的 confirmLoading */
  saving: boolean;
  /** 重新拉取；失败置 loadFailed，不抛异常 */
  reload: () => Promise<void>;
  /** 无 id → POST，有 id → PUT。成功返回 true 且已 reload；失败抛异常由调用方提示 */
  save: (zone: ProjectZone) => Promise<boolean>;
  /** 删除。失败抛异常（后端拒绝原因必须原样透出） */
  remove: (zone: ProjectZone) => Promise<void>;
}

/**
 * 项目分区的读写（设计 §6.2）：`ProjectZonesPanel` 与「项目分区管理」页共用的唯一实现。
 *
 * <p>`projectId` 为空时**不发请求**、直接返回空列表 —— 页面未选项目、或调用方无权查看项目时
 * 都不该打出一条注定失败/403 的请求。
 *
 * <p>加载失败刻意不弹全局 `message`：分区只是页面的一部分（展开行、右栏），
 * 整页弹错会掩盖「只有这一块失败」的事实。
 */
export function useProjectZones(projectId: number | null | undefined): UseProjectZonesResult {
  const [zones, setZones] = useState<ProjectZone[]>([]);
  const [loading, setLoading] = useState(projectId != null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [saving, setSaving] = useState(false);

  const reload = useCallback(async () => {
    if (projectId == null) {
      setZones([]);
      setLoading(false);
      setLoadFailed(false);
      return;
    }
    setLoading(true);
    setLoadFailed(false);
    try {
      const list = await api.get<ProjectZone[]>(`/projects/${projectId}/zones`);
      setZones(Array.isArray(list) ? list : []);
    } catch {
      setZones([]);
      setLoadFailed(true);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const save = useCallback(
    async (zone: ProjectZone) => {
      if (projectId == null) return false;
      setSaving(true);
      try {
        if (zone.id != null) {
          await api.put(`/projects/${projectId}/zones/${zone.id}`, zone);
        } else {
          await api.post(`/projects/${projectId}/zones`, zone);
        }
        await reload();
        return true;
      } finally {
        setSaving(false);
      }
    },
    [projectId, reload],
  );

  const remove = useCallback(
    async (zone: ProjectZone) => {
      if (projectId == null || zone.id == null) return;
      await api.del(`/projects/${projectId}/zones/${zone.id}`);
      await reload();
    },
    [projectId, reload],
  );

  return { zones, loading, loadFailed, saving, reload, save, remove };
}
```

- [ ] **Step 2: 创建 `components/ZoneFormModal.tsx`**

创建 `frontend/admin-web/src/components/ZoneFormModal.tsx`：

```tsx
import { useEffect } from 'react';
import { Form, Input, InputNumber, Modal } from 'antd';
import type { ProjectZone } from '@/lib/projectZones';

export interface ZoneFormModalProps {
  /** null = 关闭；`{ name: '' }` = 新增；带 id 的对象 = 编辑 */
  editing: ProjectZone | null;
  /** 提交中：由 useProjectZones().saving 驱动 */
  submitting: boolean;
  onCancel: () => void;
  /** 校验通过后回调；提交与报错由调用方负责 */
  onSubmit: (values: ProjectZone) => void;
}

/**
 * 分区新增/编辑弹窗（设计 §6.3）。
 *
 * <p>两个入口共用：项目列表展开行（{@link ProjectZonesPanel}）与「项目分区管理」页。
 * 共用是刻意的 —— 排序缺省提示、资产面积/资产数**不进表单**（只读、由后端汇总）这两条口径
 * 一旦各写一份，必然出现「一个入口能填面积、另一个不能」的分叉。
 */
export function ZoneFormModal({ editing, submitting, onCancel, onSubmit }: ZoneFormModalProps) {
  const [form] = Form.useForm();

  /**
   * 回填：`editing` 每次打开都是新对象（新增是字面量、编辑来自列表行），故 effect 必定重跑。
   * `forceRender` 让 Form 常驻挂载，setFieldsValue 不会因未连接而告警。
   */
  useEffect(() => {
    if (!editing) return;
    form.setFieldsValue({
      name: editing.name ?? '',
      code: editing.code ?? '',
      sort: editing.sort,
      remark: editing.remark ?? '',
    });
  }, [editing, form]);

  /** 校验通过才回调；校验失败时 antd 已在字段上标红，这里静默返回 */
  const handleOk = async () => {
    try {
      const values = (await form.validateFields()) as ProjectZone;
      onSubmit(values);
    } catch {
      /* 校验失败：不回调 */
    }
  };

  return (
    <Modal
      title={editing?.id != null ? '编辑分区' : '新增分区'}
      open={!!editing}
      // forceRender：Form 常驻挂载，回填不因未连接而告警
      forceRender
      onCancel={onCancel}
      onOk={() => void handleOk()}
      confirmLoading={submitting}
      width={Math.min(480, typeof window !== 'undefined' ? window.innerWidth - 32 : 480)}
    >
      <Form form={form} layout="vertical" className="mt-2">
        <Form.Item
          name="name"
          label="分区名称"
          rules={[{ required: true, message: '请填写分区名称' }]}
        >
          <Input placeholder="如 A区" />
        </Form.Item>
        <Form.Item name="code" label="分区编码">
          <Input placeholder="如 A" />
        </Form.Item>
        <Form.Item
          name="sort"
          label="排序"
          extra={editing?.id != null ? '留空表示保持原排序' : '留空表示追加到末尾'}
        >
          <InputNumber className="w-full" />
        </Form.Item>
        <Form.Item name="remark" label="备注">
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
```

- [ ] **Step 3: 重写 `components/ProjectZonesPanel.tsx`**

用以下内容整体替换 `frontend/admin-web/src/components/ProjectZonesPanel.tsx`（**列、按钮、文案、空态与错误态与改造前逐字一致**，只把逻辑换成共享实现）：

```tsx
import { useState } from 'react';
import { Button, Empty, Spin, Table, message } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { confirmDelete } from '@/lib/confirm';
import { PermissionGuard, usePerm } from '@/lib/perm';
import { TableActions } from '@/components/TableActions';
import { ZoneFormModal } from '@/components/ZoneFormModal';
import { useProjectZones, type ProjectZone } from '@/lib/projectZones';

/** 面积千分位展示，空值按 0 处理（后端对无资产分区已补 0） */
const formatArea = (value: unknown) =>
  Number(value ?? 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

/**
 * 项目列表展开行内的分区面板（设计 §5.2）。
 *
 * <p>三个写操作与后端三个分区级接口一一对应，权限统一为 `asset.project:update`：
 * 这是**完整判定码**而不是 by-path 推导 —— 本组件挂在展开行里，没有自己的路由，
 * `usePermByPath()` 会解析不到 menuCode 而放行。
 *
 * <p>读写逻辑与「项目分区管理」页共用 {@link useProjectZones}，弹窗共用 {@link ZoneFormModal}：
 * 两条入口必须给出同样的排序缺省、只读字段口径与报错透传。
 */
export function ProjectZonesPanel({ projectId }: { projectId: number }) {
  const can = usePerm();
  const canUpdate = can('asset.project', 'update');
  const { zones, loading, loadFailed, saving, reload, save, remove } = useProjectZones(projectId);
  const [editing, setEditing] = useState<ProjectZone | null>(null);

  const handleDelete = (zone: ProjectZone) => {
    confirmDelete({
      name: zone.name,
      resourceLabel: '分区',
      onOk: async () => {
        try {
          await remove(zone);
          message.success('已删除');
        } catch (e) {
          // 后端在分区下有资产 / 有后续记录时返回 400，原因必须原样透出
          message.error(e instanceof Error ? e.message : '删除失败');
          throw e;
        }
      },
    });
  };

  const handleSubmit = async (values: ProjectZone) => {
    if (!editing) return;
    try {
      // 编辑时 id 取自 editing（表单里没有这个字段），其余字段以表单为准
      await save(editing.id != null ? { ...values, id: editing.id } : values);
      message.success(editing.id != null ? '保存成功' : '新增成功');
      setEditing(null);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    }
  };

  const columns: ColumnsType<ProjectZone> = [
    { title: '分区名称', dataIndex: 'name', key: 'name', width: 160 },
    {
      title: '分区编码',
      dataIndex: 'code',
      key: 'code',
      width: 120,
      render: (value: unknown) => String(value ?? '—'),
    },
    {
      title: '资产面积(㎡)',
      dataIndex: 'assetArea',
      key: 'assetArea',
      width: 140,
      render: (value: unknown) => formatArea(value),
    },
    {
      title: '资产数',
      dataIndex: 'assetCount',
      key: 'assetCount',
      width: 90,
      render: (value: unknown) => String(value ?? 0),
    },
    {
      title: '排序',
      dataIndex: 'sort',
      key: 'sort',
      width: 80,
      render: (value: unknown) => String(value ?? '—'),
    },
    {
      title: '备注',
      dataIndex: 'remark',
      key: 'remark',
      render: (value: unknown) => String(value ?? '—'),
    },
  ];

  // 无权时整列不出现，而不是留一个每行都空白的「操作」列
  if (canUpdate) {
    columns.push({
      title: '操作',
      key: '_actions',
      width: 140,
      render: (_: unknown, zone: ProjectZone) => (
        <TableActions
          actions={[
            {
              key: 'edit',
              label: '编辑',
              icon: <EditOutlined />,
              onClick: () => setEditing(zone),
            },
            {
              key: 'delete',
              label: '删除',
              icon: <DeleteOutlined />,
              danger: true,
              onClick: () => handleDelete(zone),
            },
          ]}
        />
      ),
    });
  }

  return (
    <div className="space-y-3 py-1">
      <div className="flex items-center justify-between gap-3">
        <span className="text-sm text-gray-600">
          项目分区
          <span className="text-xs text-gray-400 ml-2">
            分区面积由该分区下资产面积自动汇总，只读
          </span>
        </span>
        <span className="flex items-center gap-2">
          <Button size="small" icon={<ReloadOutlined />} onClick={() => void reload()}>
            刷新
          </Button>
          <PermissionGuard perm="asset.project:update">
            <Button
              size="small"
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setEditing({ name: '' })}
            >
              新增分区
            </Button>
          </PermissionGuard>
        </span>
      </div>

      {loadFailed ? (
        <div className="py-6 text-center text-sm text-gray-500">
          分区加载失败
          <Button type="link" size="small" onClick={() => void reload()}>
            重试
          </Button>
        </div>
      ) : loading ? (
        <div className="py-6 text-center">
          <Spin />
        </div>
      ) : zones.length === 0 ? (
        <Empty description="暂无分区" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        <Table
          rowKey={(zone) => String(zone.id ?? zone.name)}
          columns={columns}
          dataSource={zones}
          pagination={false}
          size="small"
        />
      )}

      <ZoneFormModal
        editing={editing}
        submitting={saving}
        onCancel={() => setEditing(null)}
        onSubmit={(values) => void handleSubmit(values)}
      />
    </div>
  );
}
```

- [ ] **Step 4: 删除 `ProjectFormPage.tsx` 里的 `ProjectZone` 副本**

在 `frontend/admin-web/src/pages/ProjectFormPage.tsx` 中：

(a) 在 import 区（`ProjectFormPage.tsx:36` 的 `import { ImageUploadField, type ImageValue } from '@/components/ImageUploadField';` 之后）新增一行：

```tsx
import type { ProjectZone } from '@/lib/projectZones';
```

(b) 删除本地接口定义（`ProjectFormPage.tsx:53-63`），即整段：

```tsx
interface ProjectZone {
  id?: number;
  name: string;
  code?: string;
  sort?: number;
  remark?: string;
  /** 分区面积(㎡)：后端按分区下资产面积汇总，只读 */
  assetArea?: number;
  /** 分区下资产数量：只读 */
  assetCount?: number;
}
```

删除后，`ProjectFormPage.tsx` 上方保留的注释块（`// 分区面积不在此手工维护…`）与本文件第 50-51 行的说明注释保持原样，**不要删除任何注释**。

- [ ] **Step 5: 构建 + lint + 权限守卫**

```bash
cd frontend && pnpm --filter admin-web build && pnpm lint
node scripts/check-perm-invariants.mjs
```

Expected:
- `build` 通过（`tsc -b` 无类型错误 —— 若 `ProjectFormPage` 的 `zones` 状态与共享类型不兼容会在此报错）。
- `lint` 通过（无 unused import：确认 `ProjectZonesPanel.tsx` 不再 import `api` / `useCallback` / `useEffect`）。
- `check-perm-invariants.mjs` 输出 `检查通过`；`前端 perm 声明` 计数应与本任务前一致（本任务只保留 `perm="asset.project:update"` 这一处既有声明）。

- [ ] **Step 6: 手动回归（行为必须与改造前逐字一致）**

启动 `cd frontend && pnpm dev`，进入 `/projects`：

1. 切到**列表模式**，展开任意项目行 → 分区块出现，列顺序为「分区名称 / 分区编码 / 资产面积(㎡) / 资产数 / 排序 / 备注 / 操作」。
2. 点「新增分区」→ 弹窗字段为「分区名称（必填）/ 分区编码 / 排序 / 备注」，**看不到资产面积与资产数**；排序的 extra 文案是「留空表示追加到末尾」。
3. 只填名称提交 → 成功，列表出现新分区，排序自动追加到末尾。
4. 点行内「编辑」→ 排序 extra 文案变为「保持原排序」；清空排序后保存 → 排序不变。
5. 删除一个**有资产**的分区 → 提示「分区「X」下有 N 项资产，无法删除」，分区仍存在。
6. 删除一个无资产无记录的分区 → 成功，列表移除。
7. 切到**卡片模式** → 不出现展开入口（`expandable` 只在列表模式生效）。
8. 进入 `/projects/:id/edit`（第二步「项目分区配置」）→ 分区表格正常加载与编辑，行为与本任务前一致。

- [ ] **Step 7: 提交**

```bash
git add frontend/admin-web/src/lib/projectZones.ts \
        frontend/admin-web/src/components/ZoneFormModal.tsx \
        frontend/admin-web/src/components/ProjectZonesPanel.tsx \
        frontend/admin-web/src/pages/ProjectFormPage.tsx
git commit -m "refactor(admin): 抽出 ProjectZone 类型 / useProjectZones / ZoneFormModal，消除分区逻辑三处重复"
```

---

## Task 3: `AssetFormPage` 支持归属预填、锁定与来源返回

**Files:**
- Modify: `frontend/admin-web/src/pages/AssetFormPage.tsx`

**Interfaces:**
- Consumes: `useSearchParams`（react-router-dom）、既有 `useBackNavigate`（`@/lib/navigation`）
- Produces: `AssetFormPage` 支持的 query 参数契约 ——
  - `?projectId=<number>`：**新增**时预填「项目」；编辑时忽略
  - `?zoneId=<number>`：**新增**时预填「分区」；编辑时忽略
  - `?lockScope=1`：禁用「项目」「分区」两个下拉并显示锁定提示
  - 保存成功后按 `location.state.from` 返回，fallback `/assets`

> **不改变既有入口行为**：从 `/assets` 进入时 `ResourcePage` 已传 `state.from = '/assets'`，`goBack()` 会 navigate 回 `/assets` —— 与现在的硬编码 `navigate('/assets')` 完全等价。从深链（无 `state.from`）进入时沿用 `useBackNavigate` 的既有语义（浏览器上一页 / 兜底 `/assets`）。

- [ ] **Step 1: 加 import**

把 `frontend/admin-web/src/pages/AssetFormPage.tsx:2` 改为：

```tsx
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
```

- [ ] **Step 2: 加 URL 数字解析辅助函数**

在 `AssetFormPage.tsx` 中 `export function AssetFormPage() {` 之前（第 68 行附近）插入：

```tsx
/** URL 数字参数解析：缺失 / 非法 / 非正数一律视为「未提供」，避免拼出 ?projectId=NaN */
const toPositiveNumber = (raw: string | null): number | undefined => {
  if (!raw) return undefined;
  const value = Number(raw);
  return Number.isFinite(value) && value > 0 ? value : undefined;
};
```

- [ ] **Step 3: 读取 query 参数**

在 `AssetFormPage.tsx` 的 `const goBack = useBackNavigate('/assets');`（第 73 行）之后插入：

```tsx
  const [searchParams] = useSearchParams();
  /**
   * 「项目分区管理」页跳转过来时带的归属预设。
   * 编辑态**不使用**它：归属一律以 `/assets/{id}` 的返回值为准，两处都写同一字段会产生竞争。
   */
  const presetProjectId = toPositiveNumber(searchParams.get('projectId'));
  const presetZoneId = toPositiveNumber(searchParams.get('zoneId'));
  /** 归属锁定：本入口不允许把资产挪到别的项目 / 分区（越权仍由后端 validateZone 拦截） */
  const lockScope = searchParams.get('lockScope') === '1';
```

- [ ] **Step 4: 用 `initialValues` 预填归属**

把 `AssetFormPage.tsx` 中现有的：

```tsx
      <Form form={form} layout="vertical" initialValues={{ assetType: 'property' }}>
```

改为：

```tsx
      <Form form={form} layout="vertical" initialValues={initialValues}>
```

并在 `lockScope` 声明之后（第 3 步插入的代码下方）新增：

```tsx
  /** 新增时预填归属；编辑态只保留 assetType，归属交给接口返回值 */
  const initialValues = isEdit
    ? { assetType: 'property' }
    : { assetType: 'property', projectId: presetProjectId, zoneId: presetZoneId };
```

- [ ] **Step 5: 「项目」下拉加锁定**

把 `AssetFormPage.tsx` 中「项目」的 `Form.Item`（`name="projectId"`）改为：

```tsx
              <Form.Item
                name="projectId"
                label="项目"
                rules={[{ required: true, message: '请选择项目' }]}
                extra={lockScope ? '由项目分区管理进入，归属已锁定' : undefined}
              >
                <Select
                  allowClear
                  showSearch
                  optionFilterProp="label"
                  loading={projectOptions.loading}
                  disabled={lockScope || !assetCompanyId}
                  placeholder={assetCompanyId ? '请选择项目（可搜索）' : '请先选择资产公司'}
                  options={projectOptions.options}
                />
              </Form.Item>
```

（相对原实现只有两处变化：新增 `extra`，以及 `disabled` 由 `!assetCompanyId` 变为 `lockScope || !assetCompanyId`。`extra` 在非锁定态取 `undefined` 是有意的 —— 原实现此处**没有**任何提示文案，加一句「项目、责任部门均按资产公司级联」会让既有入口凭空多出一行字，属于本任务不该引入的 UI 变化。）

- [ ] **Step 6: 「分区」下拉加锁定**

把 `AssetFormPage.tsx` 中「分区」的 `Form.Item`（`name="zoneId"`）改为：

```tsx
              <Form.Item name="zoneId" label="分区" extra={lockScope ? '归属已锁定' : undefined}>
                <Select
                  allowClear
                  showSearch
                  optionFilterProp="label"
                  loading={zoneOptions.loading}
                  disabled={lockScope || !watchProjectId}
                  placeholder={watchProjectId ? '请选择分区' : '请先选择项目'}
                  options={zoneOptions.options}
                />
              </Form.Item>
```

（相对原实现只有两处变化：新增 `extra`，以及 `disabled` 由 `!watchProjectId` 变为 `lockScope || !watchProjectId`。）

- [ ] **Step 7: 保存后按来源返回**

把 `AssetFormPage.tsx` 中 `handleSubmit` 里保存成功后的：

```tsx
      navigate('/assets');
```

改为：

```tsx
      // 按来源返回：从资产台账进来回 /assets（与改造前一致），从「项目分区管理」进来
      // 回到带 projectId/zoneId 的原地（见 useBackNavigate 的 state.from 约定）
      goBack();
```

`navigate` 在本文件仍有其它用途（「维护字典」按钮跳 `/system/dict`），**保留该 import**。

- [ ] **Step 8: 构建 + lint**

```bash
cd frontend && pnpm --filter admin-web build && pnpm lint
```

Expected: 均通过，无类型错误、无 unused 变量（`navigate` 仍被使用，不应出现 unused 告警）。

- [ ] **Step 9: 手动验证（含回归）**

`cd frontend && pnpm dev`，逐条核对：

1. **回归 —— 从资产台账新增**：进入 `/assets` → 点「新增」→ URL 为 `/assets/create`，项目与分区下拉**可编辑**，且**没有**任何锁定提示文案（与改造前逐字一致）；填完保存 → 跳回 `/assets`（与改造前一致）。
2. **回归 —— 从资产台账编辑**：点行内「编辑」→ URL 为 `/assets/{id}/edit`，归属可编辑，保存后跳回 `/assets`。
3. **预填**：直接访问 `/assets/create?projectId=1&zoneId=2&lockScope=1` → 「项目」已选中 id=1，「分区」已选中 id=2，「分区」下拉的选项来自该项目的分区列表。
4. **锁定**：「项目」「分区」两个下拉均**灰化不可改**，且分别显示锁定提示。
5. **返回来源**：在资产台账里点「新增」前先进 `/assets`，然后手动访问 `/assets/create?projectId=1&zoneId=2&lockScope=1`（无 `state.from`）→ 保存后走 `useBackNavigate` 的兜底路径（浏览器上一页 / `/assets`），不报错。
6. **编辑态不预填**：访问 `/assets/{id}/edit?projectId=999&lockScope=1` → 「项目」显示的是该资产真实归属（**不是 999**），且下拉灰化。

- [ ] **Step 10: 提交**

```bash
git add frontend/admin-web/src/pages/AssetFormPage.tsx
git commit -m "feat(admin): 资产表单页支持 projectId/zoneId 预填与 lockScope 归属锁定，并按来源返回"
```

---

## Task 4: 左栏项目列表 `ProjectListPane`

**Files:**
- Create: `frontend/admin-web/src/components/ProjectListPane.tsx`

**Interfaces:**
- Consumes: `GET /projects?page=&pageSize=&keyword=`（返回 `PageResult<ProjectRow>`，含后端聚合的 `assetCount` / `assetArea`）；`usePerm` 由调用方算好后以 `canView` 传入
- Produces:
  - `interface ProjectListPaneProps { selectedId: number | null; onSelect: (projectId: number) => void; canView: boolean }`
  - `function ProjectListPane(props: ProjectListPaneProps): JSX.Element`

> **为什么左栏自持关键字与分页**：把它们提升到 `ProjectZonesPage` 会让编排层同时管理左栏分页、右栏分页与两套错误态；而右栏完全不关心左栏翻到了第几页。
>
> **为什么用 `reloadToken` 而不是直接调用 `load`**：关键字与页码都没变时（点「查询」/「刷新」/「重试」），`setState` 同值不会触发 effect，只能靠一个自增计数器把请求打出去。同一个 idempotent 的写法也避免了「先发旧页码、再补发第 1 页」的双请求。

- [ ] **Step 1: 创建组件**

创建 `frontend/admin-web/src/components/ProjectListPane.tsx`：

```tsx
import { useCallback, useEffect, useState } from 'react';
import { Button, Empty, Input, Pagination, Spin } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { api, type PageResult } from '@/lib/api';

/** 项目行：资产统计字段由后端在列表里聚合（AssetService#fillProjectAssetStats），无需额外请求 */
interface ProjectRow {
  id: number;
  name: string;
  assetCount?: number;
  assetArea?: number;
}

export interface ProjectListPaneProps {
  /** 当前选中项目（来自 URL），null 表示未选 */
  selectedId: number | null;
  onSelect: (projectId: number) => void;
  /** asset.project:view：无权时不发请求，显示提示 */
  canView: boolean;
}

const PAGE_SIZE = 20;

const formatArea = (value: unknown) =>
  Number(value ?? 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

/**
 * 「项目分区管理」左栏：项目列表（设计 §6.4）。
 */
export function ProjectListPane({ selectedId, onSelect, canView }: ProjectListPaneProps) {
  const [keyword, setKeyword] = useState('');
  /** 已提交的关键字：与输入框分离，避免每敲一个字都发请求 */
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  /** 重拉信号：关键字与页码都没变时（查询 / 刷新 / 重试）靠它触发一次请求 */
  const [reloadToken, setReloadToken] = useState(0);
  const [rows, setRows] = useState<ProjectRow[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);

  const load = useCallback(
    async (p: number, kw: string) => {
      if (!canView) return;
      setLoading(true);
      setLoadFailed(false);
      try {
        const params = new URLSearchParams({ page: String(p), pageSize: String(PAGE_SIZE) });
        if (kw) params.set('keyword', kw);
        // /projects 确定返回 PageResult；后端未返回 list 时按空列表处理，避免白屏
        const result = await api.get<PageResult<ProjectRow>>(`/projects?${params.toString()}`);
        setRows(result?.list ?? []);
        setTotal(Number(result?.total ?? 0));
      } catch {
        // 栏内错误态：不弹全局 message，右栏不受影响
        setRows([]);
        setTotal(0);
        setLoadFailed(true);
      } finally {
        setLoading(false);
      }
    },
    [canView],
  );

  useEffect(() => {
    void load(page, query);
  }, [load, page, query, reloadToken]);

  /** 查询：页码归 1；关键字没变时靠 reloadToken 重拉（同值 setState 不会触发 effect） */
  const handleSearch = () => {
    setPage(1);
    if (query === keyword) setReloadToken((token) => token + 1);
    else setQuery(keyword);
  };

  if (!canView) {
    return (
      <div className="w-full lg:w-[280px] shrink-0 border border-[var(--ams-border)] rounded-lg bg-white p-4 text-sm text-gray-500">
        无项目查看权限
      </div>
    );
  }

  return (
    <div className="w-full lg:w-[280px] shrink-0 border border-[var(--ams-border)] rounded-lg bg-white p-3 flex flex-col min-w-0">
      <div className="flex items-center gap-2 mb-2">
        <Input
          allowClear
          size="small"
          placeholder="搜索项目名称"
          prefix={<SearchOutlined className="text-gray-400" />}
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onPressEnter={handleSearch}
        />
        <Button
          size="small"
          icon={<SearchOutlined />}
          onClick={handleSearch}
          aria-label="查询项目"
        />
        <Button
          size="small"
          icon={<ReloadOutlined />}
          onClick={() => setReloadToken((token) => token + 1)}
          aria-label="刷新项目列表"
        />
      </div>

      <div className="flex-1 min-h-0 overflow-auto">
        {loadFailed ? (
          <div className="py-6 text-center text-sm text-gray-500">
            项目加载失败
            <Button type="link" size="small" onClick={() => setReloadToken((token) => token + 1)}>
              重试
            </Button>
          </div>
        ) : loading ? (
          <div className="py-6 text-center">
            <Spin />
          </div>
        ) : rows.length === 0 ? (
          <Empty description="暂无项目" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <div className="space-y-1">
            {rows.map((row) => {
              const active = row.id === selectedId;
              return (
                <div
                  key={row.id}
                  role="button"
                  tabIndex={0}
                  aria-label={`选择项目 ${row.name}`}
                  aria-current={active ? 'true' : undefined}
                  onClick={() => onSelect(row.id)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      onSelect(row.id);
                    }
                  }}
                  className={`px-3 py-2 rounded cursor-pointer border transition-colors ${
                    active ? 'border-blue-200 bg-blue-50' : 'border-transparent hover:bg-gray-50'
                  }`}
                >
                  <div
                    className={`text-sm truncate ${
                      active ? 'text-blue-600 font-medium' : 'text-gray-800'
                    }`}
                    title={row.name}
                  >
                    {row.name}
                  </div>
                  <div className="text-xs text-gray-400 tabular-nums mt-0.5">
                    资产 {Number(row.assetCount ?? 0)} 宗 · {formatArea(row.assetArea)} ㎡
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      <div className="flex justify-center mt-2 pt-2 border-t border-[var(--ams-border)]">
        <Pagination
          size="small"
          current={page}
          pageSize={PAGE_SIZE}
          total={total}
          showSizeChanger={false}
          onChange={(p) => setPage(p)}
        />
      </div>
    </div>
  );
}
```

- [ ] **Step 2: 构建 + lint**

```bash
cd frontend && pnpm --filter admin-web build && pnpm lint
```

Expected: 均通过。此时组件尚未被引用，`tsc -b` 仍会编译它（在 `src` 下），因此类型错误不会被漏掉。

- [ ] **Step 3: 提交**

```bash
git add frontend/admin-web/src/components/ProjectListPane.tsx
git commit -m "feat(admin): 项目分区管理左栏项目列表（搜索 + 分页）"
```

---

## Task 5: 分区 Tab 栏 + 资产表 `ZoneAssetPane`

**Files:**
- Create: `frontend/admin-web/src/components/ZoneAssetPane.tsx`

**Interfaces:**
- Consumes:
  - Task 2：`useProjectZones(projectId)`、`ProjectZone`、`ZoneFormModal`
  - Task 3：`/assets/create?projectId&zoneId&lockScope=1`、`/assets/{id}/edit?lockScope=1`
  - 既有接口：`GET /assets?projectId=&zoneId=&page=&pageSize=`（`zoneId` 可选，缺省即不过滤）、`DELETE /assets/{id}`
  - `usePerm()`、`currentPath(location)`（`@/lib/navigation`）、`confirmDelete`、`TableActions`
- Produces: `interface ZoneAssetPaneProps { projectId: number | null; zoneId: number | null; onZoneChange: (zoneId: number | null) => void }` 与 `function ZoneAssetPane(props): JSX.Element`

> **联动契约（spec §5.2）**：切换项目/Tab/点刷新 → 资产归第 1 页并重拉；分区增删改 → 重拉 Tab（`useProjectZones` 内部已做）；**资产删除后必须同时 `reloadZones()`** —— Tab 上的资产数与面积由后端按分区汇总，只刷资产表会让 Tab 数字与表内行数对不上。资产的**新增/编辑**发生在独立表单页，返回时整个 `ProjectZonesPage` 重新挂载，因此天然重拉，无需额外回调。

- [ ] **Step 1: 创建组件**

创建 `frontend/admin-web/src/components/ZoneAssetPane.tsx`：

```tsx
import { useCallback, useEffect, useState } from 'react';
import { Button, Empty, Pagination, Space, Spin, Table, Tabs, message } from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  FileTextOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useLocation, useNavigate } from 'react-router-dom';
import { api, type PageResult } from '@/lib/api';
import { confirmDelete } from '@/lib/confirm';
import { ASSET_TYPE, LEASE_CONTROL_STATUS } from '@/lib/labels';
import { currentPath } from '@/lib/navigation';
import { usePerm } from '@/lib/perm';
import { useProjectZones, type ProjectZone } from '@/lib/projectZones';
import { TableActions, type TableActionItem } from '@/components/TableActions';
import { ZoneFormModal } from '@/components/ZoneFormModal';

/** 资产行：列表接口已回显 zoneName（AssetService#fillZoneNames），无需前端再查 */
interface AssetRow {
  id: number;
  assetNo?: string;
  name?: string;
  assetType?: string;
  zoneId?: number | null;
  zoneName?: string | null;
  floorNo?: number | null;
  area?: number;
  leaseControlStatus?: string;
}

export interface ZoneAssetPaneProps {
  projectId: number | null;
  /** null = 「全部分区」 */
  zoneId: number | null;
  onZoneChange: (zoneId: number | null) => void;
}

/** 「全部分区」Tab 的 key：分区 id 都是正数，不会与它冲突 */
const ALL_ZONES_KEY = 'all';
const PAGE_SIZE = 10;

const formatArea = (value: unknown) =>
  Number(value ?? 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

/**
 * 「项目分区管理」右侧：分区 Tab 栏 + 该分区资产表（设计 §5.1 / §6.5）。
 */
export function ZoneAssetPane({ projectId, zoneId, onZoneChange }: ZoneAssetPaneProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const can = usePerm();

  const canViewProject = can('asset.project', 'view');
  const canUpdateProject = can('asset.project', 'update');
  const canViewLedger = can('asset.ledger', 'view');
  const canCreateLedger = can('asset.ledger', 'create');
  const canUpdateLedger = can('asset.ledger', 'update');
  const canDeleteLedger = can('asset.ledger', 'delete');

  // 无权查看项目时连请求都不发：useProjectZones 对 null 直接返回空列表
  const {
    zones,
    loading: zonesLoading,
    loadFailed: zonesFailed,
    saving: zoneSaving,
    reload: reloadZones,
    save: saveZone,
    remove: removeZone,
  } = useProjectZones(canViewProject ? projectId : null);

  const [editingZone, setEditingZone] = useState<ProjectZone | null>(null);

  const [assets, setAssets] = useState<AssetRow[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [assetsLoading, setAssetsLoading] = useState(false);
  const [assetsFailed, setAssetsFailed] = useState(false);
  const [assetsReloadToken, setAssetsReloadToken] = useState(0);

  const currentZone = zoneId == null ? null : (zones.find((zone) => zone.id === zoneId) ?? null);

  const loadAssets = useCallback(
    async (p: number) => {
      if (projectId == null || !canViewLedger) {
        setAssets([]);
        setTotal(0);
        return;
      }
      setAssetsLoading(true);
      setAssetsFailed(false);
      try {
        const params = new URLSearchParams({
          projectId: String(projectId),
          page: String(p),
          pageSize: String(PAGE_SIZE),
        });
        // 「全部分区」不拼 zoneId：后端该参数可选，缺省即不过滤（spec §2）
        if (zoneId != null) params.set('zoneId', String(zoneId));
        const result = await api.get<PageResult<AssetRow>>(`/assets?${params.toString()}`);
        setAssets(result?.list ?? []);
        setTotal(Number(result?.total ?? 0));
      } catch {
        setAssets([]);
        setTotal(0);
        setAssetsFailed(true);
      } finally {
        setAssetsLoading(false);
      }
    },
    [projectId, zoneId, canViewLedger],
  );

  /**
   * 上下文变化（项目 / 分区 / 重拉信号）→ 归第 1 页并重拉。
   *
   * <p>刻意**不把 `page` 纳入依赖**：那会在切换分区时先用旧页码发一次请求、再补发第 1 页。
   * 翻页走 {@link handlePageChange} 的显式调用。
   */
  useEffect(() => {
    setPage(1);
    void loadAssets(1);
    // loadAssets 的依赖已覆盖 projectId / zoneId / canViewLedger；把它列进依赖会因
    // identity 变化而重复触发（同 ResourcePage 的处理）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, zoneId, canViewLedger, assetsReloadToken]);

  /** URL 里的 zoneId 不属于当前项目（删分区后回退、手改链接）→ 回到「全部分区」 */
  useEffect(() => {
    if (zoneId == null || zonesLoading || zonesFailed) return;
    if (zones.some((zone) => zone.id === zoneId)) return;
    onZoneChange(null);
  }, [zoneId, zones, zonesLoading, zonesFailed, onZoneChange]);

  const handlePageChange = (p: number) => {
    setPage(p);
    void loadAssets(p);
  };

  const reloadAssets = () => setAssetsReloadToken((token) => token + 1);

  const handleSubmitZone = async (values: ProjectZone) => {
    if (!editingZone) return;
    try {
      // 编辑时 id 取自 editingZone（表单不含该字段），其余以表单为准
      await saveZone(editingZone.id != null ? { ...values, id: editingZone.id } : values);
      message.success(editingZone.id != null ? '保存成功' : '新增成功');
      setEditingZone(null);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    }
  };

  const handleDeleteZone = () => {
    if (!currentZone) return;
    confirmDelete({
      name: currentZone.name,
      resourceLabel: '分区',
      onOk: async () => {
        try {
          await removeZone(currentZone);
          message.success('已删除');
          // 删的就是当前 Tab：回到「全部分区」（spec §5.2 第 4 条）
          onZoneChange(null);
        } catch (e) {
          // 有资产 / 有后续记录时后端返回 400，原因原样透出
          message.error(e instanceof Error ? e.message : '删除失败');
          throw e;
        }
      },
    });
  };

  const handleAddAsset = () => {
    const params = new URLSearchParams({ lockScope: '1' });
    if (projectId != null) params.set('projectId', String(projectId));
    // 「全部分区」下不预填分区：归属留空由使用者在表单里选
    if (zoneId != null) params.set('zoneId', String(zoneId));
    navigate(`/assets/create?${params.toString()}`, { state: { from: currentPath(location) } });
  };

  const handleEditAsset = (assetId: number) => {
    navigate(`/assets/${assetId}/edit?lockScope=1`, {
      state: { from: currentPath(location) },
    });
  };

  const handleDeleteAsset = (asset: AssetRow) => {
    confirmDelete({
      name: asset.name,
      resourceLabel: '资产',
      onOk: async () => {
        try {
          await api.del(`/assets/${asset.id}`);
          message.success('已删除');
          // Tab 上的资产数/面积由后端按分区汇总，资产变动后必须一并重拉（spec §5.2 第 5 条）
          await reloadZones();
          reloadAssets();
        } catch (e) {
          message.error(e instanceof Error ? e.message : '删除失败');
          throw e;
        }
      },
    });
  };

  const tabItems = [
    { key: ALL_ZONES_KEY, label: '全部分区' },
    ...(canViewProject
      ? zones.map((zone) => ({
          key: String(zone.id),
          label: (
            <span className="inline-flex items-center gap-1">
              {zone.name}
              <span className="text-xs text-gray-400 tabular-nums">
                {Number(zone.assetCount ?? 0)}
              </span>
            </span>
          ),
        }))
      : []),
  ];

  const zoneColumn: ColumnsType<AssetRow> =
    zoneId == null
      ? [
          {
            title: '分区',
            dataIndex: 'zoneName',
            key: 'zoneName',
            width: 130,
            ellipsis: true,
            render: (value: unknown) => String(value ?? '—'),
          },
        ]
      : [];

  const columns: ColumnsType<AssetRow> = [
    { title: '资产编号', dataIndex: 'assetNo', key: 'assetNo', width: 150, ellipsis: true },
    { title: '资产名称', dataIndex: 'name', key: 'name', width: 180, ellipsis: true },
    {
      title: '资产类型',
      dataIndex: 'assetType',
      key: 'assetType',
      width: 110,
      render: (value: unknown) => ASSET_TYPE[String(value ?? '')] ?? String(value ?? '—'),
    },
    // 「全部分区」下各行的分区不同才有必要展示；选中具体分区时该列恒为同一个值
    ...zoneColumn,
    {
      title: '分区楼层',
      dataIndex: 'floorNo',
      key: 'floorNo',
      width: 100,
      render: (value: unknown) => (value == null ? '—' : `${value}F`),
    },
    {
      title: '资产面积(㎡)',
      dataIndex: 'area',
      key: 'area',
      width: 130,
      render: (value: unknown) => formatArea(value),
    },
    {
      title: '租控状态',
      dataIndex: 'leaseControlStatus',
      key: 'leaseControlStatus',
      width: 110,
      render: (value: unknown) => LEASE_CONTROL_STATUS[String(value ?? '')] ?? String(value ?? '—'),
    },
  ];

  if (canViewLedger) {
    columns.push({
      title: '操作',
      key: '_actions',
      width: 170,
      render: (_: unknown, asset: AssetRow) => {
        const actions: TableActionItem[] = [];
        if (canUpdateLedger) {
          actions.push({
            key: 'edit',
            label: '编辑',
            icon: <EditOutlined />,
            onClick: () => handleEditAsset(asset.id),
          });
        }
        actions.push({
          key: 'dossier',
          label: '一物一档',
          icon: <FileTextOutlined />,
          to: `/assets/${asset.id}/dossier`,
        });
        const more: TableActionItem[] = [];
        if (canDeleteLedger) {
          more.push({
            key: 'delete',
            label: '删除',
            icon: <DeleteOutlined />,
            danger: true,
            onClick: () => handleDeleteAsset(asset),
          });
        }
        return <TableActions actions={actions} more={more} max={2} />;
      },
    });
  }

  if (projectId == null) {
    return (
      <div className="flex-1 min-w-0 border border-[var(--ams-border)] rounded-lg bg-white p-4">
        <Empty description="请先在左侧选择项目" className="py-16" />
      </div>
    );
  }

  const scopeLabel = currentZone?.name ?? '全部分区';

  return (
    <div className="flex-1 min-w-0 border border-[var(--ams-border)] rounded-lg bg-white p-3 flex flex-col">
      {!canViewProject ? (
        <div className="py-2 text-sm text-gray-500">无项目查看权限，仅显示全部分区资产</div>
      ) : zonesFailed ? (
        <div className="py-2 text-center text-sm text-gray-500">
          分区加载失败
          <Button type="link" size="small" onClick={() => void reloadZones()}>
            重试
          </Button>
        </div>
      ) : (
        <Tabs
          size="small"
          activeKey={zoneId == null ? ALL_ZONES_KEY : String(zoneId)}
          onChange={(key) => onZoneChange(key === ALL_ZONES_KEY ? null : Number(key))}
          items={tabItems}
          tabBarExtraContent={
            canUpdateProject ? (
              <Space size={4} wrap>
                <Button
                  size="small"
                  type="primary"
                  icon={<PlusOutlined />}
                  onClick={() => setEditingZone({ name: '' })}
                >
                  新增分区
                </Button>
                <Button
                  size="small"
                  icon={<EditOutlined />}
                  disabled={!currentZone}
                  onClick={() => currentZone && setEditingZone(currentZone)}
                >
                  编辑
                </Button>
                <Button
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                  disabled={!currentZone}
                  onClick={handleDeleteZone}
                >
                  删除
                </Button>
              </Space>
            ) : undefined
          }
        />
      )}

      <div className="flex items-center justify-between gap-3 mb-3 flex-wrap">
        <span className="text-sm text-gray-600 min-w-0">
          <span className="font-medium">{scopeLabel}</span>
          <span className="text-xs text-gray-400 ml-2 tabular-nums">共 {total} 宗</span>
          {currentZone && (
            <span className="text-xs text-gray-400 ml-2 tabular-nums">
              合计 {formatArea(currentZone.assetArea)} ㎡
            </span>
          )}
        </span>
        <span className="flex items-center gap-2">
          <Button
            size="small"
            icon={<ReloadOutlined />}
            onClick={reloadAssets}
            aria-label="刷新资产列表"
          />
          {canCreateLedger && (
            <Button size="small" type="primary" icon={<PlusOutlined />} onClick={handleAddAsset}>
              新增资产
            </Button>
          )}
        </span>
      </div>

      {!canViewLedger ? (
        <div className="py-16 text-center text-sm text-gray-500">无资产查看权限</div>
      ) : assetsFailed ? (
        <div className="py-16 text-center text-sm text-gray-500">
          资产加载失败
          <Button type="link" size="small" onClick={reloadAssets}>
            重试
          </Button>
        </div>
      ) : assetsLoading && assets.length === 0 ? (
        <div className="py-16 text-center">
          <Spin />
        </div>
      ) : assets.length === 0 ? (
        <Empty
          description={zoneId == null ? '该项目暂无资产' : '该分区暂无资产'}
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          className="py-10"
        />
      ) : (
        <>
          <Table
            rowKey={(asset) => String(asset.id)}
            columns={columns}
            dataSource={assets}
            loading={assetsLoading}
            pagination={false}
            size="small"
            scroll={{ x: 960 }}
          />
          <div className="flex justify-end mt-3">
            <Pagination
              size="small"
              current={page}
              pageSize={PAGE_SIZE}
              total={total}
              showSizeChanger={false}
              showTotal={(t) => `共 ${t} 条`}
              onChange={handlePageChange}
            />
          </div>
        </>
      )}

      <ZoneFormModal
        editing={editingZone}
        submitting={zoneSaving}
        onCancel={() => setEditingZone(null)}
        onSubmit={(values) => void handleSubmitZone(values)}
      />
    </div>
  );
}
```

- [ ] **Step 2: 构建 + lint**

```bash
cd frontend && pnpm --filter admin-web build && pnpm lint
```

Expected: 均通过。若 `eslint` 报 `react-hooks/exhaustive-deps`，确认该行上方已有 `// eslint-disable-next-line react-hooks/exhaustive-deps`；**不要**改成把 `loadAssets` 放进依赖（会产生双请求）。

- [ ] **Step 3: 提交**

```bash
git add frontend/admin-web/src/components/ZoneAssetPane.tsx
git commit -m "feat(admin): 项目分区管理右侧分区 Tab 与资产表（含分区 CRUD 与资产删改跳转）"
```

---

## Task 6: 页面编排与全量注册接线

**Files:**
- Create: `frontend/admin-web/src/pages/ProjectZonesPage.tsx`
- Modify: `frontend/admin-web/src/App.tsx`
- Modify: `frontend/admin-web/src/lib/routeRegistry.ts`
- Modify: `frontend/admin-web/src/lib/pathToCode.ts`
- Modify: `frontend/admin-web/src/pages/modules.tsx`
- Modify: `frontend/admin-web/src/lib/menuIcons.tsx`

**Interfaces:**
- Consumes: Task 4 的 `ProjectListPane`、Task 5 的 `ZoneAssetPane`、`usePerm`、`useSearchParams`（react-router-dom）
- Produces: 侧栏可达路由 `/project-zones`（菜单码 `asset.projectZone`），页面级守卫由 `AdminLayout` 的 `<RequirePerm>` 从 `PATH_TO_CODE` 推导

> **为什么选中态必须落在 URL（spec §5.4）**：从本页跳去 `/assets/create` 再返回时，React Router 渲染的是另一个路由元素 → `ProjectZonesPage` **整页重新挂载**，组件内部 state 全部归零。选中项只在 state 里就回不来，验收标准（返回后原分区仍选中）无法成立。附带收益：可刷新保持、链接可分享，且「切项目自动回到全部分区」变成 `zoneId` 缺省的自然结果，不需要额外的 `useEffect` 去清空。

- [ ] **Step 1: 创建页面**

创建 `frontend/admin-web/src/pages/ProjectZonesPage.tsx`：

```tsx
import { useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { PartitionOutlined } from '@ant-design/icons';
import { usePerm } from '@/lib/perm';
import { ProjectListPane } from '@/components/ProjectListPane';
import { ZoneAssetPane } from '@/components/ZoneAssetPane';

/** URL 数字参数解析：缺失 / 非法 / 非正数一律视为「未选中」 */
const toPositiveInt = (raw: string | null): number | null => {
  if (!raw) return null;
  const value = Number(raw);
  return Number.isFinite(value) && value > 0 ? value : null;
};

/**
 * 项目分区管理（设计 §5 / §6.6）：左侧项目、右上分区 Tab、右下资产。
 *
 * <p>本组件只持有「跨栏共享」的两件事：URL 上的选中态与左右布局。
 * 左侧的项目列表、右侧的分区与资产读写都在各自组件里，避免一个文件承担全部状态。
 *
 * <p><strong>选中态以 URL query 为唯一真相</strong>：跳去资产表单页再返回时本页会整页
 * 重新挂载，state 全部丢失，选中项不落在 URL 上就回不来。
 */
export function ProjectZonesPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const can = usePerm();
  const canViewProject = can('asset.project', 'view');

  const projectId = toPositiveInt(searchParams.get('projectId'));
  const zoneId = toPositiveInt(searchParams.get('zoneId'));

  /**
   * 切换项目：**不保留 `zoneId`**，即回到「全部分区」（spec §5.2 第 1 条）。
   * 用 replace 写入：切换项目/Tab 不该在浏览器历史里留下每一步。
   */
  const selectProject = useCallback(
    (id: number) => setSearchParams({ projectId: String(id) }, { replace: true }),
    [setSearchParams],
  );

  /** 切换分区：null 表示「全部分区」，对应 URL 上 `zoneId` 缺省 */
  const selectZone = useCallback(
    (id: number | null) => {
      if (projectId == null) return;
      setSearchParams(
        id == null
          ? { projectId: String(projectId) }
          : { projectId: String(projectId), zoneId: String(id) },
        { replace: true },
      );
    },
    [projectId, setSearchParams],
  );

  return (
    <div className="space-y-3 min-w-0">
      <h2 className="text-base font-semibold m-0 flex items-center gap-2 min-w-0">
        <span className="inline-flex items-center justify-center w-7 h-7 rounded-md bg-blue-50 text-[var(--ams-primary)] text-sm shrink-0">
          <PartitionOutlined />
        </span>
        <span className="truncate">项目分区管理</span>
      </h2>

      <div className="flex flex-col lg:flex-row gap-3 min-w-0 items-stretch">
        <ProjectListPane
          selectedId={projectId}
          onSelect={selectProject}
          canView={canViewProject}
        />
        <ZoneAssetPane projectId={projectId} zoneId={zoneId} onZoneChange={selectZone} />
      </div>
    </div>
  );
}
```

- [ ] **Step 2: `App.tsx` 加路由**

(a) 在 `frontend/admin-web/src/App.tsx` 的 `import ProjectDetailPage from '@/pages/ProjectDetailPage';` 之后新增：

```tsx
import { ProjectZonesPage } from '@/pages/ProjectZonesPage';
```

(b) 在 `App.tsx` 的项目详情路由之后新增一行（紧接 `<Route path="projects/:id" element={<ProjectDetailPage />} />`）：

```tsx
              {/* 项目分区管理：左侧项目 / 右上分区 Tab / 右下资产 */}
              <Route path="project-zones" element={<ProjectZonesPage />} />
```

- [ ] **Step 3: `lib/routeRegistry.ts` 登记**

(a) 在 `STANDALONE_ROUTES` 数组中 `'/asset-map',` 之后新增：

```ts
  '/project-zones',
```

(b) 把该文件第 68 行注释里的计数由 64 改为 65：

```
 * <p><strong>`titleByPath` 不是注册来源</strong>：静态 `MENU` 覆盖了全部 65 个 path，
```

- [ ] **Step 4: `lib/pathToCode.ts` 加镜像条目**

在 `frontend/admin-web/src/lib/pathToCode.ts` 的 `PATH_TO_CODE` 里，`'/projects': 'asset.project',` 之后新增一行：

```ts
  '/project-zones': 'asset.projectZone',
```

> 该文件头部的「由 `V45__menu_tree_and_role_data_scope.sql` 生成，勿手改」指的是既有条目的来源；本次新增条目由 V47 提供，与 Task 1 的 `code` / `path` 必须逐字一致。`check-perm-invariants.mjs` 只校验「已登记路由是否都在镜像里」，不校验镜像内容与迁移一致 —— 所以这一步写错不会报错，只会让页面守卫恒真，必须人工核对。

- [ ] **Step 5: `pages/modules.tsx` 加静态菜单项**

在 `MENU` 的「资产台账」分组里，`{ path: '/projects', title: '项目管理' },` 之后新增：

```tsx
      { path: '/project-zones', title: '项目分区管理' },
```

顺序必须与 V47 的 `sort = 15` 一致（项目管理 10 → 项目分区管理 15 → 资产台账 20）：静态 `MENU` 是接口失败时的降级菜单，顺序不一致会让降级态与正常态看起来是两个产品。

- [ ] **Step 6: `lib/menuIcons.tsx` 加图标**

在 `PATH_ICONS` 里 `'/projects': <ProjectOutlined />,` 之后新增：

```tsx
  '/project-zones': <PartitionOutlined />,
```

`PartitionOutlined` 已在该文件顶部 import（`/org/structure` 在用），**不要**重复 import。

- [ ] **Step 7: 构建 + lint + 权限守卫**

```bash
cd frontend && pnpm --filter admin-web build && pnpm lint
node scripts/check-perm-invariants.mjs
```

Expected:
- `build` / `lint` 通过。
- `check-perm-invariants.mjs`：`RESOURCES` 仍为 49、`STANDALONE_ROUTES` 由 16 变为 **17**、`PATH_TO_CODE` 由 64 变为 **65**、`前端 perm 声明` 计数不变；结论为 `检查通过`，且**不得出现** `失败` 段。
- 若脚本报 `RESOURCES 的 /project-zones 不在 PATH_TO_CODE 镜像里` → 回 Step 4 补镜像条目。

- [ ] **Step 8: 手动验证（联调 + 缺权）**

`cd frontend && pnpm dev`，逐条核对（对应 spec §9 的 20 条验收）：

1. **入口**：侧栏「资产台账」目录下出现「项目分区管理」（图标与「组织架构图谱」同款），点击进入 `/project-zones`；刷新后退化菜单（断网模拟 `/system/menus` 失败）里也有该入口。
2. **左栏**：项目列表可搜索、可翻页；每行显示名称 + 「资产 N 宗 · X ㎡」；点击选中并高亮。
3. **URL 选中态**：选中项目 → URL 变为 `/project-zones?projectId=<id>`；点某分区 Tab → 变为 `?...&zoneId=<id>`；**刷新页面**后两者仍保持选中。
4. **未选项目**：直接访问 `/project-zones`（无 query）→ 右栏显示「请先在左侧选择项目」，网络面板中**没有** `/projects/{id}/zones` 与 `/assets` 请求。
5. **Tab 栏**：第一个 Tab 为「全部分区」，其余为该项目分区且各带资产数；切项目后选中 Tab **回到「全部分区」**，资产分页归 1。
6. **全部分区**：选中它时资产列多出「分区」列；「编辑」「删除」为禁用态；`GET /assets` 的 query 里**没有** `zoneId`。
7. **切分区**：选择具体分区 → 资产归第 1 页并重拉，请求带 `zoneId`；表头显示「分区名 共 N 宗 合计 X ㎡」；「分区」列消失。
8. **新增分区**：Tab 栏右侧「新增分区」→ 弹窗提交 → Tab 栏立即出现新分区。
9. **编辑/删除分区**：编辑当前 Tab 的分区并保存 → Tab 文案更新；删除无资产无记录的分区 → 成功且选中 Tab **回到「全部分区」**；删除有资产的分区 → 提示含资产数量，分区仍在。
10. **删除资产与 Tab 计数联动**：选中某分区，删掉一个空置资产 → 资产表少一行，**且该 Tab 上的资产数与表头「共 N 宗」同步变小**（这是最容易漏的一条）。
11. **新增资产往返**：点「新增资产」→ URL 为 `/assets/create?projectId=<id>&zoneId=<id>&lockScope=1`，项目与分区已预填且**灰化不可改**；保存 → 回到 `/project-zones?projectId=<id>&zoneId=<id>`，原分区仍选中，资产列表出现新资产。
12. **编辑资产往返**：点行内「编辑」→ `/assets/{id}/edit?lockScope=1`，归属两项灰化；保存后回到本页且选中分区不变。
13. **刷新按钮**：点右上「刷新」→ Tab 栏与资产列表同时重拉，资产归第 1 页。
14. **手改 URL 兜底**：把 `zoneId` 改成某个不属于该项目的数字 → 自动回到「全部分区」（或后端 400 后回到「全部分区」），页面不白屏。
15. **缺权逐条**（用无相应权限的账号登录，对照 spec §4.2）：
    - 缺 `asset.project:update` → Tab 栏右侧三个按钮**整块消失**，Tab 仍可切换；
    - 缺 `asset.ledger:view` → 资产区显示「无资产查看权限」，网络面板**没有** `/assets` 请求；
    - 缺 `asset.ledger:create/update/delete` → 相应按钮消失。
16. **回归**：`/projects` 列表展开行的分区维护、`/assets` 的新增/编辑入口行为与改动前一致。

- [ ] **Step 9: 提交**

```bash
git add frontend/admin-web/src/pages/ProjectZonesPage.tsx \
        frontend/admin-web/src/App.tsx \
        frontend/admin-web/src/lib/routeRegistry.ts \
        frontend/admin-web/src/lib/pathToCode.ts \
        frontend/admin-web/src/pages/modules.tsx \
        frontend/admin-web/src/lib/menuIcons.tsx
git commit -m "feat(admin): 项目分区管理页（左项目 / 右上分区 Tab / 右下资产）+ 路由菜单注册"
```

---

## Task 7: 全量回归

**Files:** 无文件改动（纯验证）

**Interfaces:**
- Consumes: Task 1-6 的全部产物
- Produces: 可交付状态与验证记录

- [ ] **Step 1: 后端全量测试**

```bash
cd backend && mvn test
```

Expected: 全部通过。重点关注：
- `V47MigrationContractTest` 3 个用例通过；
- `PermissionRegistryTest` 仍通过（本任务未新增 `@RequiresPerm`，`enforced` 集合应为 **57 个**前后不变）；
- `AssetZoneEndpointPermissionTest` 仍通过（既有三个分区接口的权限未被触碰）。

- [ ] **Step 2: 前端全量校验**

```bash
cd frontend && pnpm --filter admin-web build && pnpm lint
node scripts/check-perm-invariants.mjs
```

Expected: `build` / `lint` 通过；守卫脚本输出 `检查通过`，其中：

```
后端 @RequiresPerm : 57 个
前端 perm 声明     : 11 处（去重 9 个码）
PATH_TO_CODE       : 65 条
RESOURCES          : 49 个
STANDALONE_ROUTES  : 17 条
```

若 `前端 perm 声明` 的去重码数由 9 变大 → 说明误把 `asset.projectZone` 之类的新码写成了 `perm` 字面量，必须删掉（spec §4.1）。若 `后端 @RequiresPerm` 不是 57 → 说明动到了控制器，违反 Global Constraints 的「后端零业务改动」。

- [ ] **Step 3: 确认后端零业务改动**

```bash
git diff --stat HEAD~6 -- backend/src/main/java backend/src/main/resources/db/migration
```

Expected: 只出现 `backend/src/main/resources/db/migration/V47__project_zone_menu.sql`（1 file changed）。`backend/src/main/java` 下**必须无任何改动**；若出现 `AssetController.java` / `AssetService.java` 等，即为违规，需回退。

- [ ] **Step 4: 记录验证结果**

把 Step 1-3 的实际输出（测试通过数、守卫脚本的计数块、`git diff --stat` 结果）追加到本计划的末尾，作为交付证据。不需要新建文档。

---

## Self-Review 记录

**1. Spec 覆盖检查**

| Spec 章节 | 落地任务 |
|-----------|----------|
| §3.1 菜单种子 + view 回填 | Task 1 |
| §3.2 前端注册 6 处 | Task 6（Step 2-6）+ Task 1（第 6 处） |
| §4 权限模型（4 个模块 6 个码） | Task 4（项目 view）、Task 5（分区 update / 资产 4 个码）、Task 6（页面级守卫） |
| §4.1 不写 `perm` 字面量 | Global Constraints + Task 2 Step 5 / Task 7 Step 2 的守卫校验 |
| §4.2 缺权逐条表现 | Task 4 Step 1（项目 view）、Task 5 Step 1（分区/资产缺权）+ Task 6 Step 8 第 15 条 |
| §5.1 布局（左项目 / 右上 Tab / 右下资产） | Task 4 + Task 5 + Task 6 |
| §5.2 联动刷新 7 条 | Task 5 Step 1（`loadAssets` effect + `reloadZones()` on 资产删除 + 自动回退）+ Task 6（切项目清 `zoneId`） |
| §5.3 空态与边界 | Task 4 Step 1（空/失败/未选）、Task 5 Step 1（三种空态 + `projectId == null` 引导态） |
| §5.4 跳转与返回 + URL 选中态 | Task 6 Step 1（`useSearchParams`）+ Task 3（`state.from`）+ Task 5（导航带 `state.from`） |
| §6.2 `lib/projectZones.ts` | Task 2 Step 1 |
| §6.3 `ZoneFormModal` | Task 2 Step 2 |
| §6.4 `ProjectListPane` | Task 4 |
| §6.5 `ZoneAssetPane` | Task 5 |
| §6.6 `ProjectZonesPage` 编排 | Task 6 Step 1 |
| §6.7 `AssetFormPage` query 参数 | Task 3 |
| §7 后端（迁移 + 测试） | Task 1 |
| §7.1 既有接口复用点 | Task 1 无改动 + Task 5 复用 `/assets` 双参数 |
| §8 错误处理与边界 | Task 3（`lockScope` 越权兜底说明）+ Task 5（400 原样透出）+ Task 6 Step 8 第 14 条 |
| §9 验收 20 条 | Task 6 Step 8（1-16 条）+ Task 7（17-20 条） |
| §10 本期不做 | 无任务涉及（未做「未划分区」独立 Tab、批量操作、拖动排序、唯一约束、左栏项目 CRUD、精简弹窗、新接口、`ZoneDetailPage`、卡片模式展开） |

无遗漏。

**2. Placeholder 扫描**：全文无 TBD / TODO /「类似 Task N」/「补充错误处理」等占位；每个改动步骤都给出了完整可粘贴的代码。

**3. 类型一致性检查**

- `ProjectZone`：Task 2 定义 8 个字段（`id?` / `projectId?` / `name` / `code?` / `sort?` / `remark?` / `assetArea?` / `assetCount?`），Task 2 的弹窗、Task 5 的 `editingZone` / `saveZone` / `currentZone` 均沿用，无新增字段。
- `UseProjectZonesResult`：Task 2 定义为 `{ zones, loading, loadFailed, saving, reload, save, remove }`；Task 2 的 `ProjectZonesPanel` 与 Task 5 的 `ZoneAssetPane` 解构的字段名与数量完全一致（Task 5 用 `loading: zonesLoading` / `loadFailed: zonesFailed` / `saving: zoneSaving` / `reload: reloadZones` / `save: saveZone` / `remove: removeZone` 别名，均为同名字段重命名，不是新字段）。
- `ZoneFormModalProps`：Task 2 定义为 `{ editing, submitting, onCancel, onSubmit }`；Task 2 与 Task 5 的调用点传参一致（`onSubmit` 收到的是校验后的 `ProjectZone`，不含 `id`；两个调用点都用 `{ ...values, id: editing.id }` 补 id）。
- `ProjectListPaneProps`：Task 4 定义为 `{ selectedId, onSelect, canView }`；Task 6 传参一致。
- `ZoneAssetPaneProps`：Task 5 定义为 `{ projectId, zoneId, onZoneChange }`；Task 6 传参一致。
- `onZoneChange` 签名 `(zoneId: number | null) => void`：Task 6 的 `selectZone` 与之逐字匹配，`null` 表示「全部分区」。
- 菜单码/路径：`asset.projectZone` 与 `/project-zones` 在 Task 1 的迁移、Task 6 的镜像、`MENU`、`PATH_ICONS`、`App.tsx` 路由中逐字一致。
- 权限码：Task 2 / 4 / 5 / 6 使用的 6 个码全部来自 Global Constraints 白名单，与后端既有 `@RequiresPerm` 一致。
