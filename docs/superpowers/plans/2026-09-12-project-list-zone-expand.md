# 项目管理列表分区展开与增删改 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在项目列表（列表模式）中让每个项目行可展开查看其全部分区，并支持对分区做新增 / 编辑 / 删除。

**Architecture:** 后端在 `AssetService` 增加三个分区级 CRUD 方法（不再依赖「整体替换」的 `PUT /projects/{id}`），并由 `AssetController` 暴露 `POST/PUT/DELETE /projects/{id}/zones[/{zoneId}]`，统一复用既有权限码 `asset.project:update`。前端给通用 `ResourcePage` 增加一个可选 `expandable` 配置（仅列表模式生效），分区 UI 全部封装在新组件 `ProjectZonesPanel` 中，由 `modules.tsx` 的 `projects` 配置挂载。

**Tech Stack:** 后端 Spring Boot 3 + MyBatis-Plus + JUnit 5 + Mockito + AssertJ（Java 21）；前端 React 18 + TypeScript 5.6 + Vite + antd 6 + Tailwind。

## Global Constraints

- 设计依据：`docs/superpowers/specs/2026-09-12-project-list-zone-expand-design.md`（下称「设计」）。
- 权限：分区三个接口**一律** `@RequiresPerm("asset.project:update")`，不新增权限码、不新增菜单种子（设计 §2、§4.3）。
- 审计：三个写接口**一律**加 `@Audited(module = "asset", action = "create_project_zone" | "update_project_zone" | "delete_project_zone")`。
- 数据范围：三个接口**一律**先调 `AssetController` 既有的私有方法 `assertProject(id)`（对象级归属断言）。
- **不改** `AssetService.replaceZones(...)` 与 `PUT /projects/{id}`：两步走向导（`/projects/create`、`/projects/:id/edit`）行为必须保持不变（设计 §4.2）。
- 错误码：参数/业务错误用 `ErrorCode.BAD_REQUEST`。
- 前端权限判定码写完整形式 `asset.project:update`（不得用 by-path 推导，因为展开面板不在独立路由上）。
- 前端**不新增**路由：`/projects` 仍由 `RESOURCES.projects` + `ResourcePage` 渲染，`STANDALONE_ROUTES` / `PATH_TO_CODE` 不变。
- `ResourceConfig` 新增的 `expandable` 必须只影响**列表模式**，卡片模式行为不变（设计 §2、§5.1）。
- 反引号内的权限码、字段名、方法名必须逐字照抄，不得改写。

### 本机环境限制（影响「运行测试」步骤）

- 本机**没有 JDK 与 Maven**（`java -version` 报 `Unable to locate a Java Runtime`），因此后端 `mvn` 命令**无法在本机执行**。
- 后端测试的实际执行点是 CI 的 `backend` job（`.github/workflows/ci.yml` → `mvn -B verify`）。本机只能做静态检查（人工核对 + 前端检查）。
- 前端本机可用：`node v24.4.0`、`pnpm 10.10.0`。
- 因此每个后端的「Run test」步骤都标注为**CI 执行**；若执行者本机装有 JDK 21+ 与 Maven，可直接运行同一命令。

---

## File Structure

| 文件 | 职责 | 动作 |
|------|------|------|
| `backend/src/main/java/com/ams/modules/asset/service/AssetService.java` | 项目/资产台账服务；新增分区 CRUD 三个方法与两个私有校验辅助 | 修改 |
| `backend/src/main/java/com/ams/modules/asset/controller/AssetController.java` | 项目/资产接口；新增三个分区级接口 | 修改 |
| `backend/src/test/java/com/ams/modules/asset/AssetServiceZoneTest.java` | 分区 CRUD 的服务层单测（含删除保护 / 跨项目拒绝） | 新建 |
| `backend/src/test/java/com/ams/platform/security/AssetZoneEndpointPermissionTest.java` | 三个分区接口的权限闭环用例（真实拦截器 + 真实 RbacService） | 新建 |
| `frontend/admin-web/src/components/ResourcePage.tsx` | 通用列表页组件；新增可选 `expandable` 配置（仅列表模式） | 修改 |
| `frontend/admin-web/src/components/ProjectZonesPanel.tsx` | 分区面板：列表 + 新增/编辑弹窗 + 删除确认 + 权限显隐 | 新建 |
| `frontend/admin-web/src/pages/modules.tsx` | 模块注册表；给 `RESOURCES.projects` 挂 `expandable` | 修改 |

---

## Task 1: 后端 — 分区 CRUD 服务方法与单测

**Files:**
- Modify: `backend/src/main/java/com/ams/modules/asset/service/AssetService.java`（在 `replaceZones(...)` 之后、`// ---- 资产 ----` 之前插入）
- Test: `backend/src/test/java/com/ams/modules/asset/AssetServiceZoneTest.java`（新建）

**Interfaces:**
- Consumes: 现有 `AssetService.getProject(Long)`（`projectMapper.selectById`，为空抛 `AppException(ErrorCode.NOT_FOUND)`）、`AssetService.fillZoneAssetStats(List<ProjectZone>)`、`AssetMapper.selectCount(Wrapper)`、`ProjectZoneMapper`。
- Produces（Task 2 依赖这三个签名，必须逐字一致）：
  - `public ProjectZone createProjectZone(Long projectId, ProjectZone zone)`
  - `public ProjectZone updateProjectZone(Long projectId, Long zoneId, ProjectZone zone)`
  - `public void deleteProjectZone(Long projectId, Long zoneId)`

- [ ] **Step 1: 写失败的测试**

新建 `backend/src/test/java/com/ams/modules/asset/AssetServiceZoneTest.java`：

```java
package com.ams.modules.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.ams.modules.asset.service.AssetQrService;
import com.ams.modules.asset.service.AssetService;
import com.ams.modules.asset.service.AssetUnitService;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.billing.mapper.BillPaymentMapper;
import com.ams.modules.org.mapper.CompanyMapper;
import com.ams.modules.org.mapper.DepartmentMapper;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.org.service.CompanyTreeService;
import com.ams.platform.security.RbacService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 分区 CRUD 服务层测试（设计 docs/superpowers/specs/2026-09-12-project-list-zone-expand-design.md）。
 *
 * <p>覆盖三条最容易写错的语义：
 * <ol>
 *   <li>归属一律由路径参数决定，请求体里的 {@code id} / {@code projectId} 必须被忽略（防跨项目写入）；</li>
 *   <li>新增时排序缺省 = 当前最大排序 + 1（追加到末尾），而不是 0；</li>
 *   <li>删除挂资产的分区必须被拒 —— 这是与「两步走」整体保存刻意不同的一点，
 *       后者会把资产 {@code zone_id} 静默置空。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AssetServiceZoneTest {

    private static final long PROJECT_ID = 1L;
    private static final long ZONE_ID = 9L;

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ProjectZoneMapper projectZoneMapper;
    @Mock
    private AssetMapper assetMapper;
    @Mock
    private AssetQrService assetQrService;
    @Mock
    private CompanyTreeService companyTreeService;
    @Mock
    private CompanyMapper companyMapper;
    @Mock
    private DepartmentMapper departmentMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private RbacService rbacService;
    @Mock
    private BillMapper billMapper;
    @Mock
    private BillPaymentMapper billPaymentMapper;
    @Mock
    private AssetUnitService assetUnitService;

    @InjectMocks
    private AssetService service;

    @Test
    @DisplayName("新增分区：排序缺省取当前最大排序 + 1，归属与 id 均由服务端决定")
    void createZoneAppendsToEndAndIgnoresBodyIdentity() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectList(any())).thenReturn(List.of(zone(1L, 0), zone(2L, 4)));
        when(projectZoneMapper.insert(any(ProjectZone.class))).thenReturn(1);

        ProjectZone created = service.createProjectZone(PROJECT_ID, inputZone("C区"));

        assertThat(created.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(created.getName()).isEqualTo("C区");
        assertThat(created.getSort()).isEqualTo(5);
        assertThat(created.getAssetCount()).isZero();
        assertThat(created.getAssetArea()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("新增分区：该项目还没有分区时排序从 0 开始")
    void createZoneStartsAtZeroWhenProjectHasNoZone() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectList(any())).thenReturn(List.of());
        when(projectZoneMapper.insert(any(ProjectZone.class))).thenReturn(1);

        ProjectZone created = service.createProjectZone(PROJECT_ID, inputZone("A区"));

        assertThat(created.getSort()).isZero();
    }

    @Test
    @DisplayName("新增分区：分区名称为空白时拒绝，且不写库")
    void createZoneRejectsBlankName() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));

        assertThatThrownBy(() -> service.createProjectZone(PROJECT_ID, inputZone("   ")))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("分区名称");
        verify(projectZoneMapper, never()).insert(any(ProjectZone.class));
    }

    @Test
    @DisplayName("编辑分区：编号与归属保持路径参数值，请求体 id/projectId 被忽略")
    void updateZoneKeepsIdentityFromPath() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        ProjectZone existing = zone(ZONE_ID, 2);
        existing.setCode("A");
        existing.setRemark("旧备注");
        when(projectZoneMapper.selectById(ZONE_ID)).thenReturn(existing);
        when(assetMapper.selectMaps(any())).thenReturn(List.of());
        when(projectZoneMapper.updateById(any(ProjectZone.class))).thenReturn(1);

        ProjectZone input = inputZone("A区(新)");
        input.setId(888L);
        input.setCode("A1");
        input.setSort(7);
        input.setRemark("新备注");

        ProjectZone updated = service.updateProjectZone(PROJECT_ID, ZONE_ID, input);

        assertThat(updated.getId()).isEqualTo(ZONE_ID);
        assertThat(updated.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(updated.getName()).isEqualTo("A区(新)");
        assertThat(updated.getCode()).isEqualTo("A1");
        assertThat(updated.getSort()).isEqualTo(7);
        assertThat(updated.getRemark()).isEqualTo("新备注");
    }

    @Test
    @DisplayName("编辑分区：排序为 null 时保留原排序")
    void updateZoneKeepsSortWhenNull() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        ProjectZone existing = zone(ZONE_ID, 3);
        when(projectZoneMapper.selectById(ZONE_ID)).thenReturn(existing);
        when(assetMapper.selectMaps(any())).thenReturn(List.of());
        when(projectZoneMapper.updateById(any(ProjectZone.class))).thenReturn(1);

        ProjectZone input = inputZone("A区");
        input.setSort(null);

        assertThat(service.updateProjectZone(PROJECT_ID, ZONE_ID, input).getSort()).isEqualTo(3);
    }

    @Test
    @DisplayName("分区不属于该项目时拒绝（防跨项目写入）")
    void zoneOfAnotherProjectIsRejected() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        ProjectZone foreign = zone(ZONE_ID, 1);
        foreign.setProjectId(999L);
        when(projectZoneMapper.selectById(ZONE_ID)).thenReturn(foreign);

        assertThatThrownBy(() -> service.deleteProjectZone(PROJECT_ID, ZONE_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("分区不存在");
        verify(projectZoneMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除分区：分区下有资产时拒绝并提示数量，且不写库")
    void deleteZoneWithAssetsIsRejected() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectById(ZONE_ID)).thenReturn(zone(ZONE_ID, 1));
        when(assetMapper.selectCount(any())).thenReturn(3L);

        assertThatThrownBy(() -> service.deleteProjectZone(PROJECT_ID, ZONE_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("3 项资产");
        verify(projectZoneMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除分区：无资产时删除成功")
    void deleteZoneWithoutAssetsSucceeds() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectById(ZONE_ID)).thenReturn(zone(ZONE_ID, 1));
        when(assetMapper.selectCount(any())).thenReturn(0L);

        service.deleteProjectZone(PROJECT_ID, ZONE_ID);

        verify(projectZoneMapper).deleteById(ZONE_ID);
    }

    private static Project project(long id) {
        Project p = new Project();
        p.setId(id);
        return p;
    }

    private static ProjectZone zone(long id, int sort) {
        ProjectZone z = new ProjectZone();
        z.setId(id);
        z.setProjectId(PROJECT_ID);
        z.setName("分区" + id);
        z.setSort(sort);
        return z;
    }

    /** 请求体形态：刻意带上错误的 projectId，用于证明归属只认路径参数。 */
    private static ProjectZone inputZone(String name) {
        ProjectZone z = new ProjectZone();
        z.setProjectId(999L);
        z.setName(name);
        return z;
    }
}
```

- [ ] **Step 2: 运行测试确认失败（编译错误 = 预期失败）**

Run（CI / 有 JDK+Maven 的机器上）：
```bash
cd backend && mvn -B -Dtest=AssetServiceZoneTest test
```
Expected: FAIL —— 编译错误 `cannot find symbol: method createProjectZone(...)`（三个方法尚不存在）。
本机无 JDK/Maven，跳过本地执行，以 CI `backend` job 为准。

- [ ] **Step 3: 实现三个服务方法**

在 `backend/src/main/java/com/ams/modules/asset/service/AssetService.java` 中，找到 `replaceZones(...)` 方法的结束位置与紧随其后的注释行 `// ---- 资产 ----`，在**两者之间**插入：

```java
    // ---- 项目分区（项目列表展开行内的就地维护） ----

    /**
     * 新增分区：排序缺省取当前最大排序 + 1，即追加到末尾。
     *
     * <p>归属由路径参数决定，**忽略请求体里的 {@code id} / {@code projectId}** ——
     * 与「两步走」的整体保存不同，这里没有父请求体可以信任，只认 URL。
     */
    @Transactional
    public ProjectZone createProjectZone(Long projectId, ProjectZone zone) {
        getProject(projectId);
        ProjectZone target = new ProjectZone();
        target.setProjectId(projectId);
        target.setName(requireZoneName(zone));
        target.setCode(zone == null ? null : zone.getCode());
        target.setSort(zone == null || zone.getSort() == null
                ? nextZoneSort(projectId)
                : zone.getSort());
        target.setRemark(zone == null ? null : zone.getRemark());
        projectZoneMapper.insert(target);
        // 与列表接口字段形态一致：新分区必然没有资产，直接给 0，省掉一次聚合查询
        target.setAssetArea(BigDecimal.ZERO);
        target.setAssetCount(0L);
        return target;
    }

    /**
     * 编辑分区：只更新允许修改的字段，编号与归属取路径参数。
     *
     * <p>返回前回填只读统计，使返回值与 {@link #listProjectZones(Long)} 的元素形态一致。
     */
    @Transactional
    public ProjectZone updateProjectZone(Long projectId, Long zoneId, ProjectZone zone) {
        getProject(projectId);
        ProjectZone existing = requireProjectZone(projectId, zoneId);
        existing.setName(requireZoneName(zone));
        existing.setCode(zone == null ? null : zone.getCode());
        if (zone != null && zone.getSort() != null) {
            existing.setSort(zone.getSort());
        }
        existing.setRemark(zone == null ? null : zone.getRemark());
        projectZoneMapper.updateById(existing);
        List<ProjectZone> single = new ArrayList<>();
        single.add(existing);
        fillZoneAssetStats(single);
        return existing;
    }

    /**
     * 删除分区：分区下有资产时**拒绝**，提示资产数量。
     *
     * <p>与 {@link #replaceZones(Long, List)} 刻意不同：后者会把被删分区的资产
     * {@code zone_id} 静默置空，那会让资产归属在无感知的情况下丢失。就地删除改为显式拒绝，
     * 由使用者先把资产调整出去。
     */
    @Transactional
    public void deleteProjectZone(Long projectId, Long zoneId) {
        getProject(projectId);
        requireProjectZone(projectId, zoneId);
        Long assetCount = assetMapper.selectCount(
                new LambdaQueryWrapper<Asset>().eq(Asset::getZoneId, zoneId));
        if (assetCount != null && assetCount > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "该分区下有 " + assetCount + " 项资产，无法删除");
        }
        projectZoneMapper.deleteById(zoneId);
    }

    /** 下一个排序号：当前最大排序 + 1；无分区（或排序全为空）时从 0 开始。 */
    private int nextZoneSort(Long projectId) {
        List<ProjectZone> zones = projectZoneMapper.selectList(
                new LambdaQueryWrapper<ProjectZone>().eq(ProjectZone::getProjectId, projectId));
        return zones.stream()
                .map(ProjectZone::getSort)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .map(sort -> sort + 1)
                .orElse(0);
    }

    /** 分区必须存在且属于该项目；否则按参数错误拒绝（不依赖前端传参正确性）。 */
    private ProjectZone requireProjectZone(Long projectId, Long zoneId) {
        ProjectZone zone = projectZoneMapper.selectById(zoneId);
        if (zone == null || !projectId.equals(zone.getProjectId())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "分区不存在");
        }
        return zone;
    }

    /** 分区名称必填并去除首尾空白。 */
    private String requireZoneName(ProjectZone zone) {
        String name = zone == null || zone.getName() == null ? null : zone.getName().trim();
        if (name == null || name.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请填写分区名称");
        }
        return name;
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run（CI / 有 JDK+Maven 的机器上）：
```bash
cd backend && mvn -B -Dtest=AssetServiceZoneTest test
```
Expected: PASS —— 8 个用例全绿。
本机无 JDK/Maven，跳过本地执行，以 CI `backend` job 为准。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/ams/modules/asset/service/AssetService.java \
        backend/src/test/java/com/ams/modules/asset/AssetServiceZoneTest.java
git commit -m "feat(asset): 分区 CRUD 服务方法（删除挂资产分区时拒绝）"
```

---

## Task 2: 后端 — 三个分区级接口与权限用例

**Files:**
- Modify: `backend/src/main/java/com/ams/modules/asset/controller/AssetController.java`（在既有 `projectZones(...)` 方法之后插入）
- Test: `backend/src/test/java/com/ams/platform/security/AssetZoneEndpointPermissionTest.java`（新建）

**Interfaces:**
- Consumes: Task 1 的三个方法 `createProjectZone(Long, ProjectZone)` / `updateProjectZone(Long, Long, ProjectZone)` / `deleteProjectZone(Long, Long)`；既有私有方法 `assertProject(Long)`；既有 `@RequiresPerm`、`@Audited`。
- Produces: HTTP 接口
  - `POST /api/v1/projects/{id}/zones` → `ApiResponse<ProjectZone>`
  - `PUT /api/v1/projects/{id}/zones/{zoneId}` → `ApiResponse<ProjectZone>`
  - `DELETE /api/v1/projects/{id}/zones/{zoneId}` → `ApiResponse<Void>`

- [ ] **Step 1: 写失败的测试**

新建 `backend/src/test/java/com/ams/platform/security/AssetZoneEndpointPermissionTest.java`：

```java
package com.ams.platform.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ams.common.exception.GlobalExceptionHandler;
import com.ams.modules.asset.controller.AssetController;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.service.AssetDossierService;
import com.ams.modules.asset.service.AssetQrService;
import com.ams.modules.asset.service.AssetService;
import com.ams.modules.asset.service.AssetStructureService;
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
 * 项目列表展开行内的分区接口权限闭环（设计 §4.1、验收第 6 条）。
 *
 * <p>结构与 {@link WriteEndpointPermissionTest} 一致：真实控制器 + 真实拦截器，
 * 权限码由 {@link PermissionInterceptor} 从控制器方法上的 {@code @RequiresPerm} 本身解析，
 * 因此「注解被删」或「动作被改」都会让用例失败，而不是让 403 断言因为账号本来就没权限而空转。
 *
 * <p>设计口径：分区是项目配置的一部分，三个操作统一复用 {@code asset.project:update}。
 */
class AssetZoneEndpointPermissionTest {

    private static final long PROJECT_ID = 7L;
    private static final long ZONE_ID = 9L;
    /** 归属解析出的公司；被测账号 dataScope=all 且无排除，因此不受限、可通过归属校验。 */
    private static final long VISIBLE_COMPANY = 2L;

    private RbacService rbacService;
    private AssetService assetService;
    private AssetController controller;

    @BeforeEach
    void setUp() {
        rbacService = RbacFixtures.standard().newService(1L);
        assetService = mock(AssetService.class);
        OwnershipResolver ownershipResolver = mock(OwnershipResolver.class);
        when(ownershipResolver.ofProject(any())).thenReturn(VISIBLE_COMPANY);
        when(assetService.createProjectZone(any(), any())).thenReturn(new ProjectZone());
        when(assetService.updateProjectZone(any(), any(), any())).thenReturn(new ProjectZone());
        controller = new AssetController(assetService, mock(AssetStructureService.class),
                mock(AssetDossierService.class), mock(AssetQrService.class), ownershipResolver,
                rbacService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("新增分区 / 未授予 asset.project:update -> 403，且服务从未被调用")
    void createZoneWithoutPermissionIsForbidden() throws Exception {
        login(Set.of());

        mvc().perform(post("/api/v1/projects/{id}/zones", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A区\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        verify(assetService, never()).createProjectZone(any(), any());
    }

    @Test
    @DisplayName("新增分区 / 授予 asset.project:update -> 成功")
    void createZoneWithPermissionSucceeds() throws Exception {
        login(Set.of("asset.project:update"));

        mvc().perform(post("/api/v1/projects/{id}/zones", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A区\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(assetService).createProjectZone(eq(PROJECT_ID), any());
    }

    @Test
    @DisplayName("编辑分区 / 未授予 asset.project:update -> 403，且服务从未被调用")
    void updateZoneWithoutPermissionIsForbidden() throws Exception {
        login(Set.of());

        mvc().perform(put("/api/v1/projects/{id}/zones/{zoneId}", PROJECT_ID, ZONE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A区\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        verify(assetService, never()).updateProjectZone(any(), any(), any());
    }

    @Test
    @DisplayName("编辑分区 / 授予 asset.project:update -> 成功")
    void updateZoneWithPermissionSucceeds() throws Exception {
        login(Set.of("asset.project:update"));

        mvc().perform(put("/api/v1/projects/{id}/zones/{zoneId}", PROJECT_ID, ZONE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A区\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(assetService).updateProjectZone(eq(PROJECT_ID), eq(ZONE_ID), any());
    }

    @Test
    @DisplayName("删除分区 / 未授予 asset.project:update -> 403，且服务从未被调用")
    void deleteZoneWithoutPermissionIsForbidden() throws Exception {
        login(Set.of());

        mvc().perform(delete("/api/v1/projects/{id}/zones/{zoneId}", PROJECT_ID, ZONE_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        verify(assetService, never()).deleteProjectZone(any(), any());
    }

    @Test
    @DisplayName("删除分区 / 授予 asset.project:update -> 成功")
    void deleteZoneWithPermissionSucceeds() throws Exception {
        login(Set.of("asset.project:update"));

        mvc().perform(delete("/api/v1/projects/{id}/zones/{zoneId}", PROJECT_ID, ZONE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(assetService).deleteProjectZone(PROJECT_ID, ZONE_ID);
    }

    /** 以非 super_admin 身份登录：roles 不含 super_admin，否则拦截器直接放行、用例空转。 */
    private void login(Set<String> permissions) {
        LoginUser user = LoginUser.builder()
                .userId(9001L)
                .username("zone-probe")
                .name("分区权限探针")
                .companyId(VISIBLE_COMPANY)
                .homeCompanyId(VISIBLE_COMPANY)
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

    /** 真实控制器 + 真实拦截器 + 真实异常处理（把 AppException 翻成 HTTP 403 + code 40300）。 */
    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(rbacService, false))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run（CI / 有 JDK+Maven 的机器上）：
```bash
cd backend && mvn -B -Dtest=AssetZoneEndpointPermissionTest test
```
Expected: FAIL —— 授予权限的用例收到 404（`POST/PUT/DELETE` 三个路由尚不存在），或编译失败。
本机无 JDK/Maven，跳过本地执行，以 CI `backend` job 为准。

- [ ] **Step 3: 实现三个接口**

在 `backend/src/main/java/com/ams/modules/asset/controller/AssetController.java` 中，找到既有方法：

```java
    /** 项目分区列表（第二步配置内容） */
    @GetMapping("/projects/{id}/zones")
    @RequiresPerm("asset.project:view")
    public ApiResponse<List<ProjectZone>> projectZones(@PathVariable Long id) {
        assertProject(id);
        return ApiResponse.ok(assetService.listProjectZones(id), TraceIdUtil.get());
    }
```

在该方法**之后**插入：

```java
    /**
     * 新增分区（项目列表展开行内维护）。
     *
     * <p>归属由路径参数决定：请求体里的 {@code id} / {@code projectId} 在服务层被忽略。
     */
    @PostMapping("/projects/{id}/zones")
    @RequiresPerm("asset.project:update")
    @Audited(module = "asset", action = "create_project_zone")
    public ApiResponse<ProjectZone> createProjectZone(@PathVariable Long id,
            @RequestBody ProjectZone zone) {
        assertProject(id);
        return ApiResponse.ok(assetService.createProjectZone(id, zone), TraceIdUtil.get());
    }

    /** 编辑分区（项目列表展开行内维护）。 */
    @PutMapping("/projects/{id}/zones/{zoneId}")
    @RequiresPerm("asset.project:update")
    @Audited(module = "asset", action = "update_project_zone")
    public ApiResponse<ProjectZone> updateProjectZone(@PathVariable Long id,
            @PathVariable Long zoneId, @RequestBody ProjectZone zone) {
        assertProject(id);
        return ApiResponse.ok(assetService.updateProjectZone(id, zoneId, zone), TraceIdUtil.get());
    }

    /** 删除分区（项目列表展开行内维护；分区下有资产时返回 400 并提示数量）。 */
    @DeleteMapping("/projects/{id}/zones/{zoneId}")
    @RequiresPerm("asset.project:update")
    @Audited(module = "asset", action = "delete_project_zone")
    public ApiResponse<Void> deleteProjectZone(@PathVariable Long id, @PathVariable Long zoneId) {
        assertProject(id);
        assetService.deleteProjectZone(id, zoneId);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run（CI / 有 JDK+Maven 的机器上）：
```bash
cd backend && mvn -B -Dtest=AssetZoneEndpointPermissionTest test
```
Expected: PASS —— 6 个用例全绿。
本机无 JDK/Maven，跳过本地执行，以 CI `backend` job 为准。

- [ ] **Step 5: 校验权限与路由不变量未被破坏**

Run（本机可执行，仓库根目录）：
```bash
node scripts/check-perm-invariants.mjs
```
Expected: `检查通过`。三个新注解使用的是已存在的 `asset.project:update`，因此后端 `@RequiresPerm` 集合大小不变；前端此时还没有新增 `perm` 声明，也不应有新失败项。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/ams/modules/asset/controller/AssetController.java \
        backend/src/test/java/com/ams/platform/security/AssetZoneEndpointPermissionTest.java
git commit -m "feat(asset): 新增分区级增删改接口（复用 asset.project:update）"
```

---

## Task 3: 前端 — `ResourcePage` 支持可选展开行

**Files:**
- Modify: `frontend/admin-web/src/components/ResourcePage.tsx`

**Interfaces:**
- Consumes: 无（独立新增能力）。
- Produces（Task 5 依赖，必须逐字一致）：
  - `export interface ExpandableConfig { render: (row: Row) => React.ReactNode; rowExpandable?: (row: Row) => boolean; }`
  - `ResourceConfig.expandable?: ExpandableConfig`
  - `ResourceConfig.expandable.render` 在**列表模式**被用作 antd `Table` 的 `expandedRowRender`。
  - 注意：`Row` 是本文件内部的 `type Row = Record<string, unknown>`，**不导出**。

- [ ] **Step 1: 新增 `ExpandableConfig` 接口与配置字段**

在 `frontend/admin-web/src/components/ResourcePage.tsx` 中，找到：

```ts
export interface TagFilterConfig {
```

在该接口**之前**插入：

```ts
/**
 * 列表模式下的可展开行（设计 §5.1）。
 *
 * <p>只在**列表模式**透传给 antd Table：卡片模式没有「行」的概念，展开入口无处安放，
 * 因此配置了本项也不会让卡片模式出现展开按钮。
 *
 * <p>`render` 由调用方提供，`ResourcePage` 不认识被展开内容的语义 —— 分区、明细、日志
 * 都可以挂上来，组件本身不引入任何业务耦合。
 */
export interface ExpandableConfig {
  /** 展开行内容；`row` 为当前列表行 */
  render: (row: Row) => React.ReactNode;
  /** 可选：某行是否可展开（不配置则所有行可展开） */
  rowExpandable?: (row: Row) => boolean;
}
```

- [ ] **Step 2: 把 `expandable` 挂到 `ResourceConfig` 上**

在 `ResourceConfig` 中，找到：

```ts
  /** 标签式筛选（含「不限」） */
  tagFilters?: TagFilterConfig[];
```

在这一行**之前**插入：

```ts
  /** 列表模式下的可展开行（仅列表模式生效；卡片模式忽略） */
  expandable?: ExpandableConfig;
```

- [ ] **Step 3: 在列表分支的 Table 上启用展开**

找到列表分支：

```tsx
        <div className="ams-table-wrap">
          <Table
            rowKey={(row) => String(row[idField] ?? Math.random())}
            loading={loading}
            columns={tableColumns}
            dataSource={rows}
            pagination={false}
            size="middle"
            scroll={{ x: tableScrollX }}
            onRow={(row) => ({
              onClick: () => openDetail(row),
              className: 'cursor-pointer',
            })}
          />
        </div>
```

替换为：

```tsx
        <div className="ams-table-wrap">
          <Table
            rowKey={(row) => String(row[idField] ?? Math.random())}
            loading={loading}
            columns={tableColumns}
            dataSource={rows}
            pagination={false}
            size="middle"
            scroll={{ x: tableScrollX }}
            // 展开行仅列表模式支持；未配置 expandable 时保持原行为（不渲染展开列）
            expandable={
              config.expandable
                ? {
                    expandedRowRender: (row) => config.expandable!.render(row),
                    rowExpandable: config.expandable!.rowExpandable,
                  }
                : undefined
            }
            onRow={(row) => ({
              onClick: () => openDetail(row),
              className: 'cursor-pointer',
            })}
          />
        </div>
```

- [ ] **Step 4: 类型检查与 lint**

Run（仓库根目录）：
```bash
cd frontend && pnpm --filter admin-web build
```
Expected: 构建成功（`tsc -b` 无类型错误，vite 产出 `dist`）。若 `tsc` 报 `ExpandedRowRender` 与 `Row` 类型不兼容，把 `expandedRowRender` 的入参标注为 `(row: Row)`，不要用 `any`。

```bash
cd frontend && pnpm lint
```
Expected: 无新增 error。

- [ ] **Step 5: 校验权限不变量**

Run（仓库根目录）：
```bash
node scripts/check-perm-invariants.mjs
```
Expected: `检查通过`（本任务未新增 `perm` 声明）。

- [ ] **Step 6: 提交**

```bash
git add frontend/admin-web/src/components/ResourcePage.tsx
git commit -m "feat(admin): ResourcePage 支持列表模式可展开行"
```

---

## Task 4: 前端 — `ProjectZonesPanel` 分区面板组件

**Files:**
- Create: `frontend/admin-web/src/components/ProjectZonesPanel.tsx`

**Interfaces:**
- Consumes: `api.get<T>(path)` / `api.post(path, body)` / `api.put(path, body)` / `api.del(path)`（`@/lib/api`）；`confirmDelete`（`@/lib/confirm`）；`usePerm`、`PermissionGuard`（`@/lib/perm`）；`TableActions`（`@/components/TableActions`）。
- Produces（Task 5 依赖，必须逐字一致）：
  - `export interface ProjectZone { id?: number; projectId?: number; name: string; code?: string; sort?: number; remark?: string; assetArea?: number; assetCount?: number; }`
  - `export function ProjectZonesPanel({ projectId }: { projectId: number })`
  - 后端契约：`GET /projects/{projectId}/zones` 返回扁平数组。

- [ ] **Step 1: 创建组件**

新建 `frontend/admin-web/src/components/ProjectZonesPanel.tsx`：

```tsx
import { useCallback, useEffect, useState } from 'react';
import { Button, Empty, Form, Input, InputNumber, Modal, Spin, Table, message } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { api } from '@/lib/api';
import { confirmDelete } from '@/lib/confirm';
import { PermissionGuard, usePerm } from '@/lib/perm';
import { TableActions } from '@/components/TableActions';

/**
 * 项目分区（project_zone）行数据。
 *
 * <p>`assetArea` / `assetCount` 是后端汇总出来的**只读**字段：分区面积不接受人工维护，
 * 统一取该分区下资产面积合计（见 V25 迁移与 AssetService#fillZoneAssetStats）。
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

/** 面积千分位展示，空值按 0 处理（后端对无资产分区已补 0） */
const formatArea = (value: unknown) =>
  Number(value ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/**
 * 项目列表展开行内的分区面板（设计 §5.2）。
 *
 * <p>三个写操作与后端三个分区级接口一一对应，权限统一为 `asset.project:update`：
 * 这是**完整判定码**而不是 by-path 推导 —— 本组件挂在展开行里，没有自己的路由，
 * `usePermByPath()` 会解析不到 menuCode 而放行。
 */
export function ProjectZonesPanel({ projectId }: { projectId: number }) {
  const can = usePerm();
  const canUpdate = can('asset.project', 'update');

  const [zones, setZones] = useState<ProjectZone[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [editing, setEditing] = useState<ProjectZone | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    setLoadFailed(false);
    try {
      setZones(await api.get<ProjectZone[]>(`/projects/${projectId}/zones`));
    } catch {
      // 面板加载失败只影响本面板：外层项目列表继续可用，故不弹全局错误
      setZones([]);
      setLoadFailed(true);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 打开新增（zone=null）或编辑；forceRender 保证 Form 已挂载，可安全回填 */
  const openEditor = (zone: ProjectZone | null) => {
    setEditing(zone ?? { name: '' });
    form.setFieldsValue({
      name: zone?.name ?? '',
      code: zone?.code ?? '',
      sort: zone?.sort,
      remark: zone?.remark ?? '',
    });
  };

  const handleDelete = (zone: ProjectZone) => {
    confirmDelete({
      name: zone.name,
      resourceLabel: '分区',
      onOk: async () => {
        try {
          await api.del(`/projects/${projectId}/zones/${zone.id}`);
          message.success('已删除');
          await load();
        } catch (e) {
          // 后端在分区下有资产时返回 400，原因必须原样透出（设计 §3.2）
          message.error(e instanceof Error ? e.message : '删除失败');
          throw e;
        }
      },
    });
  };

  const handleSubmit = async () => {
    if (!editing) return;
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      if (editing.id != null) {
        await api.put(`/projects/${projectId}/zones/${editing.id}`, values);
        message.success('保存成功');
      } else {
        await api.post(`/projects/${projectId}/zones`, values);
        message.success('新增成功');
      }
      setEditing(null);
      await load();
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSubmitting(false);
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
              onClick: () => openEditor(zone),
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
          <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          <PermissionGuard perm="asset.project:update">
            <Button
              size="small"
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => openEditor(null)}
            >
              新增分区
            </Button>
          </PermissionGuard>
        </span>
      </div>

      {loadFailed ? (
        <div className="py-6 text-center text-sm text-gray-500">
          分区加载失败
          <Button type="link" size="small" onClick={() => void load()}>
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

      <Modal
        title={editing?.id != null ? '编辑分区' : '新增分区'}
        open={!!editing}
        // forceRender：Form 常驻挂载，openEditor 里的 setFieldsValue 不会因未连接而告警
        forceRender
        onCancel={() => setEditing(null)}
        onOk={() => void handleSubmit()}
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
          <Form.Item name="sort" label="排序" extra="留空表示追加到末尾">
            <InputNumber className="w-full" />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
```

- [ ] **Step 2: 类型检查与 lint**

Run（仓库根目录）：
```bash
cd frontend && pnpm --filter admin-web build
```
Expected: 构建成功。若报 `usePerm()` 返回类型不可调用，检查 `@/lib/perm` 的 `usePerm` 签名 —— 它返回 `(menuCode: string, action: PermAction) => boolean`，本步骤调用 `can('asset.project', 'update')` 必须类型通过。

```bash
cd frontend && pnpm lint
```
Expected: 无新增 error（尤其 `react-hooks/exhaustive-deps`：`load` 已用 `useCallback` 包，`useEffect` 依赖 `[load]`）。

- [ ] **Step 3: 校验权限不变量**

Run（仓库根目录）：
```bash
node scripts/check-perm-invariants.mjs
```
Expected: `检查通过`。本步骤新增的 `perm="asset.project:update"` 已存在于后端 `@RequiresPerm`，因此 `前端声明 ⊆ 后端` 检查通过。

- [ ] **Step 4: 提交**

```bash
git add frontend/admin-web/src/components/ProjectZonesPanel.tsx
git commit -m "feat(admin): 新增项目分区面板组件"
```

---

## Task 5: 前端 — 接入项目列表并做端到端验证

**Files:**
- Modify: `frontend/admin-web/src/pages/modules.tsx`

**Interfaces:**
- Consumes: Task 3 的 `ResourceConfig.expandable`；Task 4 的 `ProjectZonesPanel`。
- Produces: `/projects` 列表模式下每行可展开查看分区。

- [ ] **Step 1: 在配置对象外定义展开渲染函数**

在 `frontend/admin-web/src/pages/modules.tsx` 中，把文件顶部的 import 块改为（只新增一行）：

```ts
import type { ResourceConfig } from '@/components/ResourcePage';
import { ProjectZonesPanel } from '@/components/ProjectZonesPanel';
import { ASSET_QUICK_ACTIONS } from '@/lib/assetQuickActions';
import * as L from '@/lib/labels';
```

然后找到：

```ts
export const RESOURCES: Record<string, ResourceConfig> = {
```

在这一行**之前**插入：

```ts
/**
 * 项目列表展开行：分区就地维护（设计 §5.3）。
 *
 * <p>刻意定义在 `RESOURCES` 对象字面量**之外**：一是在配置里嵌 JSX 会让
 * `scripts/check-perm-invariants.mjs` 的区间解析更容易被箭头函数体带偏，
 * 二是具名函数在调试时能看到名字，而不是一个匿名箭头。
 */
const renderProjectZones = (row: Record<string, unknown>) => (
  <ProjectZonesPanel projectId={Number(row.id)} />
);

```

- [ ] **Step 2: 给 `projects` 配置挂上 `expandable`**

在 `RESOURCES` 的 `projects` 配置中，找到：

```ts
    // 详情走独立页面（行点击 / 卡片点击 / 「详情」按钮均进入），优先于默认抽屉详情
    detailLink: (id) => `/projects/${id}`,
    detailLinkLabel: '详情',
```

在这一段**之后**插入：

```ts
    /** 列表模式下展开行显示该项目的分区，可就地增删改（卡片模式不支持展开） */
    expandable: { render: renderProjectZones },
```

- [ ] **Step 3: 类型检查、lint 与权限不变量**

Run（仓库根目录）：
```bash
cd frontend && pnpm --filter admin-web build && pnpm lint && cd .. && node scripts/check-perm-invariants.mjs
```
Expected:
- `admin-web build` 成功；
- `pnpm lint` 无新增 error；
- 权限检查输出 `检查通过`。

- [ ] **Step 4: 人工验证清单（必须逐条执行并记录结果）**

启动：`cd frontend && pnpm dev`，用**已被授予** `asset.project:update` 的账号登录（按当前库里的角色权限矩阵为准；本轮矩阵默认未回填任何写动作，因此通常需要先用 super_admin 在角色权限页勾上该动作）。

1. 进入 `/projects`，**列表模式**下行首出现展开箭头；**卡片模式**下行首没有展开箭头。
2. 点击展开箭头 → 面板显示该项目分区列表（名称/编码/资产面积/资产数/排序/备注）；**页面不跳转到项目详情**。
3. 点击面板外的项目行 → 仍正常进入 `/projects/{id}` 详情页。
4. 「新增分区」只填名称保存 → 列表出现该分区，排序为末尾；再展开一次仍能看到（每次展开都重新拉取）。
5. 「编辑」改名并清空排序 → 保存成功，排序保留原值。
6. 编辑一个挂着资产的分区 → 删除 → 出现「该分区下有 N 项资产，无法删除」，分区仍在。
7. 删除一个无资产的分区 → 确认后消失。
8. 用一个**没有** `asset.project:update` 的账号（先用 super_admin 在角色权限页取消该动作）→ 展开面板看不到「新增分区」与「操作」列；直接调用 `POST /api/v1/projects/{id}/zones` 返回 403。
9. 回归：`/projects/create` 与 `/projects/{id}/edit` 的第二步「项目分区配置」保存分区仍正常（`PUT /projects/{id}` 未被改动）。

- [ ] **Step 5: 提交**

```bash
git add frontend/admin-web/src/pages/modules.tsx
git commit -m "feat(admin): 项目列表支持展开查看与维护分区"
```

- [ ] **Step 6: 推送并在 CI 上确认后端用例**

```bash
git push
```
Expected: CI `backend` job（`mvn -B verify`）与 `frontend-docs` job 均通过；其中 `AssetServiceZoneTest`（8 例）与 `AssetZoneEndpointPermissionTest`（6 例）全绿。

---

## 验收对照表（设计 §7）

| 设计验收项 | 落点 |
|------------|------|
| 1. 列表模式可展开/折叠，展开显示全部分区字段 | Task 3 + Task 5 Step 4.1/4.2 |
| 2. 卡片模式无展开入口；两步走向导不变 | Task 3 Step 3（仅列表分支）+ Task 5 Step 4.9 |
| 3. 新增分区（名称必填）、排序缺省生效 | Task 1 Step 3 `createProjectZone` + Task 5 Step 4.4 |
| 4. 编辑分区四个字段，列表即时更新 | Task 1 Step 3 `updateProjectZone` + Task 5 Step 4.5 |
| 5. 删除无资产分区成功；挂资产分区被拒且资产 `zone_id` 未动 | Task 1 `deleteProjectZone` + 单测 `deleteZoneWithAssetsIsRejected` + Task 5 Step 4.6/4.7 |
| 6. 无权看不到入口，直接调用三个接口 403 | Task 4 Step 1（`PermissionGuard` + `canUpdate` 列）+ Task 2 三个 403 用例 |
| 7. `zoneId` 不属于该项目的 project 时返回 400 | Task 1 `requireProjectZone` + 单测 `zoneOfAnotherProjectIsRejected` |
| 8. `check-perm-invariants.mjs` / `tsc` / `eslint` 通过 | Task 3 Step 4-5、Task 4 Step 2-3、Task 5 Step 3 |

## 设计 §8「本期不做」的守边界方式

- 卡片模式展开：Task 3 只改列表分支，不改 `ResourceCardGrid`。
- 唯一约束 / 拖拽排序 / 批量导入导出 / 分区独立授权：均无对应任务，不得顺手添加。
- 通用嵌套子资源（方案 B）：不引入 `ResourceConfig` 的嵌套 CRUD 能力，只加 `expandable` 一个字段。
