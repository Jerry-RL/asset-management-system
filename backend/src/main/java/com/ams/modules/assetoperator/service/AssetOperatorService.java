package com.ams.modules.assetoperator.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.ams.modules.assetoperator.dto.AssetOperatorInput;
import com.ams.modules.assetoperator.dto.AssetOperatorRoleOption;
import com.ams.modules.assetoperator.dto.AssetOperatorScopeOption;
import com.ams.modules.assetoperator.dto.AssetOperatorScopeRef;
import com.ams.modules.assetoperator.dto.AssetOperatorScopeView;
import com.ams.modules.assetoperator.dto.AssetOperatorUserOption;
import com.ams.modules.assetoperator.dto.AssetOperatorView;
import com.ams.modules.assetoperator.entity.AssetOperator;
import com.ams.modules.assetoperator.entity.AssetOperatorRole;
import com.ams.modules.assetoperator.entity.AssetOperatorScope;
import com.ams.modules.assetoperator.mapper.AssetOperatorMapper;
import com.ams.modules.assetoperator.mapper.AssetOperatorRoleMapper;
import com.ams.modules.assetoperator.mapper.AssetOperatorScopeMapper;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.entity.Department;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.CompanyMapper;
import com.ams.modules.org.mapper.DepartmentMapper;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.system.entity.Role;
import com.ams.modules.system.mapper.RoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
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
 * 资产运营人员管理（V60，FR-OP-001 扩展）：人员 + 角色 + 资产运营范围。
 *
 * <h2>与鉴权的边界（已确认口径）</h2>
 * <ul>
 *   <li><b>角色只登记、不授权</b>：{@code asset_operator_role} 不写 {@code user_role}，
 *       本模块不产生任何实际权限。真正授权仍在「系统管理 → 角色权限」与「人员维护」——
 *       若在这里同步写 {@code user_role}，一个业务模块的页面就等于多出一条提权路径，
 *       而它的权限码（{@code ops.assetOperator:*}）不是提权类权限码。</li>
 *   <li><b>范围只登记、不拦截</b>：{@code asset_operator_scope} 不参与
 *       {@code RbacService} 的数据范围收敛。表结构按可扩展口径建（类型 + id 分开存），
 *       后续要接入「按标的收敛」不需要改表。</li>
 *   <li><b>不做公司级数据范围断言</b>：与 V55 资产调拨记录 / V56 抵押记录同口径 ——
 *       它们是管理侧的登记台账，由权限码控制入口，不逐行按公司收敛
 *       （那样会让「跨公司的运营人员名册」这一用途无法使用）。</li>
 * </ul>
 *
 * <h2>角色 / 范围都是全量替换</h2>
 * 编辑时按请求体给出的完整集合先删后插，不做差集 diff：这样「连续两次提交相同内容」是幂等的
 * （与 {@code asset_transfer_record_asset} 的明细处理同口径）。代价是每次编辑都会换明细行 id，
 * 因此明细 id 不对外当引用。
 */
@Service
public class AssetOperatorService {

    /** 选项类接口的单页上限：前端每页 50，服务端夹一道防止 `pageSize=99999` 拖垮库。 */
    private static final long MAX_OPTIONS_PAGE_SIZE = 200;

    /** 资产生命周期终态：已退出（处置完成 / 对外转出）。不应再登记为运营范围。 */
    private static final String LIFECYCLE_EXITED = "exited";

    private final AssetOperatorMapper operatorMapper;
    private final AssetOperatorRoleMapper operatorRoleMapper;
    private final AssetOperatorScopeMapper operatorScopeMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final CompanyMapper companyMapper;
    private final DepartmentMapper departmentMapper;
    private final ProjectMapper projectMapper;
    private final ProjectZoneMapper projectZoneMapper;
    private final AssetMapper assetMapper;

    public AssetOperatorService(
            AssetOperatorMapper operatorMapper,
            AssetOperatorRoleMapper operatorRoleMapper,
            AssetOperatorScopeMapper operatorScopeMapper,
            UserMapper userMapper,
            RoleMapper roleMapper,
            CompanyMapper companyMapper,
            DepartmentMapper departmentMapper,
            ProjectMapper projectMapper,
            ProjectZoneMapper projectZoneMapper,
            AssetMapper assetMapper) {
        this.operatorMapper = operatorMapper;
        this.operatorRoleMapper = operatorRoleMapper;
        this.operatorScopeMapper = operatorScopeMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.companyMapper = companyMapper;
        this.departmentMapper = departmentMapper;
        this.projectMapper = projectMapper;
        this.projectZoneMapper = projectZoneMapper;
        this.assetMapper = assetMapper;
    }

    // ==================================================================
    // 读
    // ==================================================================

    /**
     * 运营人员列表。
     *
     * <p>关键字命中三类：**人员**（姓名 / 手机 / 账号）、**备注**、**运营范围标的**
     * （项目名 / 分区名 / 资产名 / 资产编号）。最后一类必须支持 —— 这个页面最常见的用法
     * 就是「谁在管这个项目」，只按人名搜等于把范围这一列排除在检索之外。
     */
    public PageResult<AssetOperatorView> page(long page, long pageSize, String keyword,
            Integer status) {
        LambdaQueryWrapper<AssetOperator> wrapper = new LambdaQueryWrapper<AssetOperator>()
                .isNull(AssetOperator::getDeletedAt)
                .eq(status != null, AssetOperator::getStatus, status);
        applyKeyword(wrapper, keyword);
        wrapper.orderByDesc(AssetOperator::getId);

        Page<AssetOperator> result = operatorMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<AssetOperator> rows = result.getRecords();
        if (rows.isEmpty()) {
            return PageResult.of(List.of(), result.getTotal(), page, pageSize);
        }

        List<Long> ids = rows.stream().map(AssetOperator::getId).toList();
        Map<Long, List<Long>> roleIdsByOperator = roleIdsByOperator(ids);
        Map<Long, int[]> scopeCounts = scopeCountsByOperator(ids);
        Map<Long, String> roleNames = roleNames(flatten(roleIdsByOperator.values()));
        Map<Long, User> users = usersOf(rows.stream().map(AssetOperator::getUserId).toList());
        Map<Long, String> companyNames = companyNames(users.values().stream()
                .map(User::getCompanyId).toList());
        Map<Long, String> departmentNames = departmentNames(users.values().stream()
                .map(User::getDepartmentId).toList());

        List<AssetOperatorView> views = new ArrayList<>(rows.size());
        for (AssetOperator row : rows) {
            views.add(toView(row, users.get(row.getUserId()), roleIdsByOperator, roleNames,
                    companyNames, departmentNames, scopeCounts.get(row.getId()), false));
        }
        return PageResult.of(views, result.getTotal(), page, pageSize);
    }

    /** 详情：比列表多返回范围明细（含标的名）。 */
    public AssetOperatorView get(Long id) {
        AssetOperator row = require(id);
        User user = row.getUserId() == null ? null : userMapper.selectById(row.getUserId());
        List<Long> roleIds = roleIdsByOperator(List.of(id)).getOrDefault(id, List.of());
        int[] counts = scopeCountsByOperator(List.of(id)).getOrDefault(id, new int[3]);
        AssetOperatorView view = toView(row, user, Map.of(id, roleIds), roleNames(roleIds),
                companyNames(idsOf(user == null ? null : user.getCompanyId())),
                departmentNames(idsOf(user == null ? null : user.getDepartmentId())),
                counts, true);
        view.setScopes(scopeViews(id));
        return view;
    }

    /**
     * 单元素 id 列表（可空安全）。
     *
     * <p><b>不能用 {@code List.of(id)}</b>：它对 null 元素抛 NPE，而 {@code user.department_id}
     * 本来就是可空列 —— 没填部门的人员一打开详情就 500。仓内已为这个坑立过规矩
     * （见 {@code AssetTransferRecordService#singleton} 的注释），本类不重犯。
     */
    private List<Long> idsOf(Long id) {
        return id == null ? List.of() : List.of(id);
    }

    /** 某个档案的运营范围明细（含标的名与上级项目名）。 */
    public List<AssetOperatorScopeView> scopeViews(Long operatorId) {
        List<AssetOperatorScope> rows = operatorScopeMapper.selectList(
                new LambdaQueryWrapper<AssetOperatorScope>()
                        .eq(AssetOperatorScope::getOperatorId, operatorId)
                        .orderByAsc(AssetOperatorScope::getScopeType)
                        .orderByAsc(AssetOperatorScope::getScopeId));
        return toScopeViews(rows);
    }

    // ==================================================================
    // 写
    // ==================================================================

    @Transactional
    public AssetOperatorView create(AssetOperatorInput input) {
        AssetOperator entity = new AssetOperator();
        Validated validated = validate(entity, input, null);
        entity.setStatus(input.getStatus() == null ? AssetOperator.STATUS_ENABLED : input.getStatus());
        operatorMapper.insert(entity);
        replaceRoles(entity.getId(), validated.roleIds());
        replaceScopes(entity.getId(), validated.scopes());
        return get(entity.getId());
    }

    @Transactional
    public AssetOperatorView update(Long id, AssetOperatorInput input) {
        AssetOperator entity = require(id);
        Validated validated = validate(entity, input, id);
        if (input.getStatus() != null) {
            entity.setStatus(input.getStatus());
        }
        operatorMapper.updateById(entity);
        replaceRoles(id, validated.roleIds());
        replaceScopes(id, validated.scopes());
        return get(id);
    }

    /**
     * 软删。
     *
     * <p>明细行（角色 / 范围）**不清理**：档案软删后这些行读不到（读取一律经主表过滤），
     * 但保留着才可能回答「这个人当初负责过什么」。主表软删也让
     * {@code uk_asset_operator_user} 放开该人员，允许重新登记。
     */
    @Transactional
    public void delete(Long id) {
        AssetOperator entity = require(id);
        entity.setDeletedAt(LocalDateTime.now());
        operatorMapper.updateById(entity);
    }

    /** 启用 / 停用（档案保留）。 */
    @Transactional
    public AssetOperatorView updateStatus(Long id, Integer status) {
        if (status == null || (status != AssetOperator.STATUS_ENABLED
                && status != AssetOperator.STATUS_DISABLED)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "状态取值只能是 1（启用）或 0（停用）");
        }
        AssetOperator entity = require(id);
        entity.setStatus(status);
        operatorMapper.updateById(entity);
        return get(id);
    }

    // ==================================================================
    // 下拉候选：各自用本模块权限，不要求 org.user / system.role / asset.ledger
    // ==================================================================

    public PageResult<AssetOperatorUserOption> userOptions(String keyword, Long companyId,
            Long departmentId, long page, long pageSize) {
        long size = clampPageSize(pageSize);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>().eq(User::getStatus, 1);
        if (companyId != null) {
            wrapper.eq(User::getCompanyId, companyId);
        }
        if (departmentId != null) {
            wrapper.eq(User::getDepartmentId, departmentId);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(User::getName, kw)
                    .or()
                    .like(User::getPhone, kw)
                    .or()
                    .like(User::getUsername, kw));
        }
        wrapper.orderByAsc(User::getId);
        Page<User> result = userMapper.selectPage(new Page<>(page, size), wrapper);
        List<User> records = result.getRecords();
        Map<Long, String> companyNames =
                companyNames(records.stream().map(User::getCompanyId).toList());
        Map<Long, String> departmentNames =
                departmentNames(records.stream().map(User::getDepartmentId).toList());
        List<AssetOperatorUserOption> options = records.stream().map(user -> {
            AssetOperatorUserOption option = new AssetOperatorUserOption();
            option.setUserId(user.getId());
            option.setName(user.getName());
            option.setPhone(user.getPhone());
            option.setCompanyId(user.getCompanyId());
            option.setCompanyName(lookup(companyNames, user.getCompanyId()));
            option.setDepartmentId(user.getDepartmentId());
            option.setDepartmentName(lookup(departmentNames, user.getDepartmentId()));
            return option;
        }).toList();
        return PageResult.of(options, result.getTotal(), page, size);
    }

    /** 角色下拉：只返回已启用角色。 */
    public List<AssetOperatorRoleOption> roleOptions() {
        return roleMapper.selectList(new LambdaQueryWrapper<Role>()
                        .eq(Role::getStatus, 1)
                        .orderByAsc(Role::getId))
                .stream()
                .map(role -> {
                    AssetOperatorRoleOption option = new AssetOperatorRoleOption();
                    option.setRoleId(role.getId());
                    option.setCode(role.getCode());
                    option.setName(role.getName());
                    option.setDataScope(role.getDataScope());
                    return option;
                })
                .toList();
    }

    /**
     * 运营范围下拉：项目 / 分区 / 资产三类标的共用一个端点，按 {@code scopeType} 分派。
     *
     * <p><b>必须带公司</b>：项目有 {@code company_id}；分区与资产分别经
     * {@code project.company_id} / {@code asset.asset_company_id} 收敛 ——
     * 与 V56 抵押标的候选同口径。否则会把别家公司的标的列进来，用户选完提交才发现选错。
     *
     * @param projectId 可选，仅对分区 / 资产生效（在项目详情里登记运营人员时可直接收窄）
     */
    public PageResult<AssetOperatorScopeOption> scopeOptions(String scopeType, Long companyId,
            Long projectId, String keyword, long page, long pageSize) {
        if (companyId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请先选择所属公司");
        }
        if (scopeType == null || !AssetOperatorScope.TYPES.contains(scopeType)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "范围类型只能是 project / zone / asset");
        }
        long size = clampPageSize(pageSize);
        return switch (scopeType) {
            case AssetOperatorScope.TYPE_PROJECT -> projectOptions(companyId, keyword, page, size);
            case AssetOperatorScope.TYPE_ZONE ->
                    zoneOptions(companyId, projectId, keyword, page, size);
            default -> assetOptions(companyId, projectId, keyword, page, size);
        };
    }

    private PageResult<AssetOperatorScopeOption> projectOptions(Long companyId, String keyword,
            long page, long size) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<Project>()
                // Project 没有 deletedAt 属性（BaseEntity 只含审计列），只能走字符串逃生口；
                // 无入参的字面量，不构成注入面（与 OwnerResolver 的既有写法一致）
                .apply("deleted_at IS NULL")
                .eq(Project::getCompanyId, companyId);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(Project::getName, keyword.trim());
        }
        wrapper.orderByAsc(Project::getId);
        Page<Project> result = projectMapper.selectPage(new Page<>(page, size), wrapper);
        List<AssetOperatorScopeOption> options = result.getRecords().stream().map(project -> {
            AssetOperatorScopeOption option = new AssetOperatorScopeOption();
            option.setScopeId(project.getId());
            option.setScopeType(AssetOperatorScope.TYPE_PROJECT);
            option.setName(project.getName());
            // 项目是顶层：它的上级就是自己，返回 null 而不是重复一次名字
            option.setParentName(null);
            return option;
        }).toList();
        return PageResult.of(options, result.getTotal(), page, size);
    }

    private PageResult<AssetOperatorScopeOption> zoneOptions(Long companyId, Long projectId,
            String keyword, long page, long size) {
        Set<Long> projectIds = projectIdsOfCompany(companyId, projectId);
        if (projectIds.isEmpty()) {
            // 公司下没有项目 → 必然没有分区。短路掉，避免拼出 `IN ()` 这类无效条件
            return PageResult.of(List.of(), 0, page, size);
        }
        LambdaQueryWrapper<ProjectZone> wrapper = new LambdaQueryWrapper<ProjectZone>()
                .apply("deleted_at IS NULL")
                .in(ProjectZone::getProjectId, projectIds);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(ProjectZone::getName, keyword.trim());
        }
        wrapper.orderByAsc(ProjectZone::getId);
        Page<ProjectZone> result = projectZoneMapper.selectPage(new Page<>(page, size), wrapper);

        Map<Long, String> projectNames =
                projectNames(result.getRecords().stream().map(ProjectZone::getProjectId).toList());
        List<AssetOperatorScopeOption> options = result.getRecords().stream().map(zone -> {
            AssetOperatorScopeOption option = new AssetOperatorScopeOption();
            option.setScopeId(zone.getId());
            option.setScopeType(AssetOperatorScope.TYPE_ZONE);
            option.setName(zone.getName());
            option.setParentName(lookup(projectNames, zone.getProjectId()));
            return option;
        }).toList();
        return PageResult.of(options, result.getTotal(), page, size);
    }

    private PageResult<AssetOperatorScopeOption> assetOptions(Long companyId, Long projectId,
            String keyword, long page, long size) {
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .isNull(Asset::getDeletedAt)
                .eq(Asset::getAssetCompanyId, companyId)
                .eq(projectId != null, Asset::getProjectId, projectId)
                // 已退出的资产不再作为运营范围候选（与调拨记录的资产下拉同口径）
                .ne(Asset::getLifecycleStatus, LIFECYCLE_EXITED);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Asset::getName, kw).or().like(Asset::getAssetNo, kw));
        }
        wrapper.orderByAsc(Asset::getId);
        Page<Asset> result = assetMapper.selectPage(new Page<>(page, size), wrapper);

        Map<Long, String> projectNames =
                projectNames(result.getRecords().stream().map(Asset::getProjectId).toList());
        List<AssetOperatorScopeOption> options = result.getRecords().stream().map(asset -> {
            AssetOperatorScopeOption option = new AssetOperatorScopeOption();
            option.setScopeId(asset.getId());
            option.setScopeType(AssetOperatorScope.TYPE_ASSET);
            option.setName(asset.getName());
            option.setParentName(lookup(projectNames, asset.getProjectId()));
            option.setAssetNo(asset.getAssetNo());
            return option;
        }).toList();
        return PageResult.of(options, result.getTotal(), page, size);
    }

    // ==================================================================
    // 校验：唯一的一份实现，create / update 共用
    // ==================================================================

    /** {@code validate} 的产物：去重后的角色 id 与运营范围。 */
    private record Validated(List<Long> roleIds, List<AssetOperatorScopeRef> scopes) {
    }

    /**
     * 校验入参并把业务字段写进 {@code entity}。
     *
     * <p><b>只有这一份校验</b>：{@code create} 与 {@code update} 都必须调它 ——
     * 仓内已为「同一语义两处判定漂移」付过代价（见 {@code AssetTransferRecordService} 的注释），
     * 本模块不留第二份。
     *
     * @param excludeId 编辑时传自身 id（唯一性校验要排除自己），新增传 null
     */
    private Validated validate(AssetOperator entity, AssetOperatorInput input, Long excludeId) {
        if (input == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        if (input.getUserId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请选择人员");
        }
        User user = userMapper.selectById(input.getUserId());
        if (user == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "人员不存在：" + input.getUserId());
        }
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new AppException(ErrorCode.BAD_REQUEST, "人员已停用：" + user.getName());
        }
        assertUserNotRegistered(input.getUserId(), excludeId);

        List<Long> roleIds = dedupe(input.getRoleIds());
        if (roleIds.isEmpty()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请至少选择一个角色");
        }
        assertRolesUsable(roleIds);

        List<AssetOperatorScopeRef> scopes = dedupeScopes(input.getScopes());
        if (scopes.isEmpty()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请至少选择一个资产运营范围");
        }
        for (AssetOperatorScopeRef scope : scopes) {
            assertScopeExists(scope);
        }

        entity.setUserId(input.getUserId());
        entity.setRemark(input.getRemark());
        return new Validated(roleIds, scopes);
    }

    /**
     * 一个人员只能有一份**有效**档案。
     *
     * <p>迁移里已有部分唯一索引兜底，但那条索引报的是数据库约束冲突（500 且文案不可读），
     * 这里提前给出可读原因；两者都要有 —— 索引负责并发，本方法负责体验。
     */
    private void assertUserNotRegistered(Long userId, Long excludeId) {
        Long exists = operatorMapper.selectCount(new LambdaQueryWrapper<AssetOperator>()
                .isNull(AssetOperator::getDeletedAt)
                .eq(AssetOperator::getUserId, userId)
                .ne(excludeId != null, AssetOperator::getId, excludeId));
        if (exists != null && exists > 0) {
            throw new AppException(ErrorCode.CONFLICT, "该人员已有资产运营人员档案，请直接编辑");
        }
    }

    /** 角色必须存在且启用：把停用角色登记进档案，会让人以为那个人还在承担该职责。 */
    private void assertRolesUsable(List<Long> roleIds) {
        Map<Long, Role> roles = roleMapper.selectBatchIds(roleIds).stream()
                .collect(Collectors.toMap(Role::getId, role -> role, (a, b) -> a));
        for (Long roleId : roleIds) {
            Role role = roles.get(roleId);
            if (role == null) {
                throw new AppException(ErrorCode.BAD_REQUEST, "角色不存在：" + roleId);
            }
            if (role.getStatus() != null && role.getStatus() != 1) {
                throw new AppException(ErrorCode.BAD_REQUEST, "角色已停用：" + role.getName());
            }
        }
    }

    /**
     * 范围标的必须存在（且未软删 / 未退出）。
     *
     * <p>分区额外校验它所属项目仍存在：分区是项目的下级，项目被删后单独留一条分区范围，
     * 在展示上会变成挂不到任何项目的孤儿标签。
     */
    private void assertScopeExists(AssetOperatorScopeRef scope) {
        String type = scope.getScopeType();
        Long targetId = scope.getScopeId();
        switch (type) {
            case AssetOperatorScope.TYPE_PROJECT -> {
                if (getActiveProject(targetId) == null) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "项目不存在或已删除：" + targetId);
                }
            }
            case AssetOperatorScope.TYPE_ZONE -> {
                ProjectZone zone = projectZoneMapper.selectActiveById(targetId);
                if (zone == null) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "分区不存在或已删除：" + targetId);
                }
                if (getActiveProject(zone.getProjectId()) == null) {
                    throw new AppException(ErrorCode.BAD_REQUEST,
                            "分区所属项目不存在或已删除：" + zone.getProjectId());
                }
            }
            case AssetOperatorScope.TYPE_ASSET -> {
                Asset asset = targetId == null ? null : assetMapper.selectById(targetId);
                if (asset == null || asset.getDeletedAt() != null) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "资产不存在或已删除：" + targetId);
                }
                if (LIFECYCLE_EXITED.equals(asset.getLifecycleStatus())) {
                    throw new AppException(ErrorCode.BAD_REQUEST,
                            "资产已退出，不可登记为运营范围：" + targetId);
                }
            }
            default -> throw new AppException(ErrorCode.BAD_REQUEST,
                    "范围类型只能是 project / zone / asset：" + type);
        }
    }

    private Project getActiveProject(Long projectId) {
        if (projectId == null) {
            return null;
        }
        return projectMapper.selectOne(new LambdaQueryWrapper<Project>()
                .apply("deleted_at IS NULL")
                .eq(Project::getId, projectId));
    }

    /** 角色去重，保持入参顺序。 */
    private List<Long> dedupe(List<Long> raw) {
        if (raw == null) {
            return List.of();
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : raw) {
            if (id != null) {
                unique.add(id);
            }
        }
        return new ArrayList<>(unique);
    }

    /**
     * 范围去重：以 {@code (type, id)} 为键。
     *
     * <p>裸 id 去重是错的 —— {@code project:3} 与 {@code asset:3} 是两条不同的范围，
     * 按 id 去重会把其中一条悄悄吃掉。
     */
    private List<AssetOperatorScopeRef> dedupeScopes(List<AssetOperatorScopeRef> raw) {
        if (raw == null) {
            return List.of();
        }
        Map<String, AssetOperatorScopeRef> unique = new LinkedHashMap<>();
        for (AssetOperatorScopeRef ref : raw) {
            if (ref == null || ref.getScopeType() == null || ref.getScopeId() == null) {
                throw new AppException(ErrorCode.BAD_REQUEST, "运营范围必须同时给出类型与 id");
            }
            String type = ref.getScopeType().trim();
            if (!AssetOperatorScope.TYPES.contains(type)) {
                throw new AppException(ErrorCode.BAD_REQUEST,
                        "范围类型只能是 project / zone / asset：" + ref.getScopeType());
            }
            AssetOperatorScopeRef normalized = new AssetOperatorScopeRef();
            normalized.setScopeType(type);
            normalized.setScopeId(ref.getScopeId());
            unique.putIfAbsent(type + ":" + ref.getScopeId(), normalized);
        }
        return new ArrayList<>(unique.values());
    }

    private void replaceRoles(Long operatorId, List<Long> roleIds) {
        operatorRoleMapper.delete(new LambdaQueryWrapper<AssetOperatorRole>()
                .eq(AssetOperatorRole::getOperatorId, operatorId));
        for (Long roleId : roleIds) {
            AssetOperatorRole row = new AssetOperatorRole();
            row.setOperatorId(operatorId);
            row.setRoleId(roleId);
            operatorRoleMapper.insert(row);
        }
    }

    private void replaceScopes(Long operatorId, List<AssetOperatorScopeRef> scopes) {
        operatorScopeMapper.delete(new LambdaQueryWrapper<AssetOperatorScope>()
                .eq(AssetOperatorScope::getOperatorId, operatorId));
        for (AssetOperatorScopeRef ref : scopes) {
            AssetOperatorScope row = new AssetOperatorScope();
            row.setOperatorId(operatorId);
            row.setScopeType(ref.getScopeType());
            row.setScopeId(ref.getScopeId());
            operatorScopeMapper.insert(row);
        }
    }

    // ==================================================================
    // 关键字与展示名回填
    // ==================================================================

    /**
     * 关键字条件。
     *
     * <p>三段之间是 OR，因此必须整体包在一个 {@code and(...)} 里 ——
     * 否则外层已有的 {@code deleted_at IS NULL} 会被 OR 拆散，查询退化成
     * 「命中关键字即可，已软删的也算」（仓内已为这个括号付过代价，见 AuditLogService 的注释）。
     */
    private void applyKeyword(LambdaQueryWrapper<AssetOperator> wrapper, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String kw = keyword.trim();
        Set<Long> userIds = userMapper.selectList(new LambdaQueryWrapper<User>()
                        .like(User::getName, kw)
                        .or()
                        .like(User::getPhone, kw)
                        .or()
                        .like(User::getUsername, kw))
                .stream()
                .map(User::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> operatorIds = operatorIdsByScopeName(kw);
        wrapper.and(w -> {
            w.like(AssetOperator::getRemark, kw);
            if (!userIds.isEmpty()) {
                w.or().in(AssetOperator::getUserId, userIds);
            }
            if (!operatorIds.isEmpty()) {
                w.or().in(AssetOperator::getId, operatorIds);
            }
        });
    }

    /** 标的名命中 → 拥有该范围的运营人员 id。按类型分三次查，避免拼一个 OR 嵌套的五行条件。 */
    private Set<Long> operatorIdsByScopeName(String keyword) {
        Set<Long> operatorIds = new LinkedHashSet<>();
        List<Long> projectIds = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                        .apply("deleted_at IS NULL")
                        .like(Project::getName, keyword))
                .stream()
                .map(Project::getId)
                .toList();
        collectOperatorIds(AssetOperatorScope.TYPE_PROJECT, projectIds, operatorIds);

        List<Long> zoneIds = projectZoneMapper.selectList(new LambdaQueryWrapper<ProjectZone>()
                        .apply("deleted_at IS NULL")
                        .like(ProjectZone::getName, keyword))
                .stream()
                .map(ProjectZone::getId)
                .toList();
        collectOperatorIds(AssetOperatorScope.TYPE_ZONE, zoneIds, operatorIds);

        List<Long> assetIds = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                        .isNull(Asset::getDeletedAt)
                        .and(w -> w.like(Asset::getName, keyword)
                                .or()
                                .like(Asset::getAssetNo, keyword)))
                .stream()
                .map(Asset::getId)
                .toList();
        collectOperatorIds(AssetOperatorScope.TYPE_ASSET, assetIds, operatorIds);
        return operatorIds;
    }

    private void collectOperatorIds(String scopeType, Collection<Long> scopeIds,
            Set<Long> collector) {
        if (scopeIds.isEmpty()) {
            return;
        }
        operatorScopeMapper.selectList(new LambdaQueryWrapper<AssetOperatorScope>()
                        .eq(AssetOperatorScope::getScopeType, scopeType)
                        .in(AssetOperatorScope::getScopeId, scopeIds))
                .forEach(row -> collector.add(row.getOperatorId()));
    }

    private AssetOperatorView toView(AssetOperator row, User user,
            Map<Long, List<Long>> roleIdsByOperator, Map<Long, String> roleNames,
            Map<Long, String> companyNames, Map<Long, String> departmentNames,
            int[] counts, boolean withScopes) {
        AssetOperatorView view = new AssetOperatorView();
        view.setId(row.getId());
        view.setUserId(row.getUserId());
        view.setStatus(row.getStatus());
        view.setRemark(row.getRemark());
        view.setCreatedAt(row.getCreatedAt());
        view.setUpdatedAt(row.getUpdatedAt());
        if (user != null) {
            view.setUserName(user.getName());
            view.setPhone(user.getPhone());
            view.setCompanyId(user.getCompanyId());
            view.setCompanyName(lookup(companyNames, user.getCompanyId()));
            view.setDepartmentId(user.getDepartmentId());
            view.setDepartmentName(lookup(departmentNames, user.getDepartmentId()));
        }
        List<Long> roleIds = roleIdsByOperator.getOrDefault(row.getId(), List.of());
        view.setRoleIds(new ArrayList<>(roleIds));
        view.setRoleNames(roleIds.stream()
                .map(id -> roleNames.getOrDefault(id, "#" + id))
                .toList());
        int[] c = counts == null ? new int[3] : counts;
        view.setProjectCount(c[0]);
        view.setZoneCount(c[1]);
        view.setAssetCount(c[2]);
        if (withScopes) {
            view.setScopes(scopeViews(row.getId()));
        }
        return view;
    }

    /** 范围行 → 视图（批量回填标的名，避免逐行查）。 */
    private List<AssetOperatorScopeView> toScopeViews(List<AssetOperatorScope> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> projectIds = new LinkedHashSet<>();
        Set<Long> zoneIds = new LinkedHashSet<>();
        Set<Long> assetIds = new LinkedHashSet<>();
        for (AssetOperatorScope row : rows) {
            switch (row.getScopeType() == null ? "" : row.getScopeType()) {
                case AssetOperatorScope.TYPE_PROJECT -> addIfNotNull(projectIds, row.getScopeId());
                case AssetOperatorScope.TYPE_ZONE -> addIfNotNull(zoneIds, row.getScopeId());
                case AssetOperatorScope.TYPE_ASSET -> addIfNotNull(assetIds, row.getScopeId());
                // 未知类型（历史脏数据）保留 id 展示，不抛错：一条坏行不该让详情 500
                default -> {
                }
            }
        }
        Map<Long, Project> projects = projectIds.isEmpty()
                ? Map.of()
                : projectMapper.selectBatchIds(projectIds).stream()
                        .collect(Collectors.toMap(Project::getId, p -> p,
                                (a, b) -> a, LinkedHashMap::new));
        Map<Long, ProjectZone> zones = zonesByIds(zoneIds);
        Map<Long, Asset> assets = assetsByIds(assetIds);

        // 分区 / 资产的上级项目名要再补一批（上一批只含 project 类型的范围）
        Set<Long> parentProjectIds = new LinkedHashSet<>();
        zones.values().forEach(zone -> addIfNotNull(parentProjectIds, zone.getProjectId()));
        assets.values().forEach(asset -> addIfNotNull(parentProjectIds, asset.getProjectId()));
        parentProjectIds.removeAll(projects.keySet());
        Map<Long, String> parentNames = projectNames(parentProjectIds);

        Map<Long, String> projectNames = new LinkedHashMap<>();
        projects.values().forEach(project -> projectNames.put(project.getId(), project.getName()));
        projectNames.putAll(parentNames);

        List<AssetOperatorScopeView> views = new ArrayList<>(rows.size());
        for (AssetOperatorScope row : rows) {
            AssetOperatorScopeView view = new AssetOperatorScopeView();
            view.setScopeType(row.getScopeType());
            view.setScopeId(row.getScopeId());
            switch (row.getScopeType() == null ? "" : row.getScopeType()) {
                case AssetOperatorScope.TYPE_PROJECT -> {
                    Project project = lookup(projects, row.getScopeId());
                    view.setScopeName(nameOrPlaceholder(
                            project == null ? null : project.getName(), row.getScopeId()));
                    // 项目是顶层，没有上级
                    view.setParentName(null);
                }
                case AssetOperatorScope.TYPE_ZONE -> {
                    ProjectZone zone = lookup(zones, row.getScopeId());
                    view.setScopeName(nameOrPlaceholder(
                            zone == null ? null : zone.getName(), row.getScopeId()));
                    view.setParentName(zone == null ? null
                            : lookup(projectNames, zone.getProjectId()));
                }
                case AssetOperatorScope.TYPE_ASSET -> {
                    Asset asset = lookup(assets, row.getScopeId());
                    view.setScopeName(nameOrPlaceholder(
                            asset == null ? null : asset.getName(), row.getScopeId()));
                    view.setParentName(asset == null ? null
                            : lookup(projectNames, asset.getProjectId()));
                }
                default -> view.setScopeName("未知范围 #" + row.getScopeId());
            }
            views.add(view);
        }
        return views;
    }

    private Map<Long, ProjectZone> zonesByIds(Set<Long> zoneIds) {
        if (zoneIds.isEmpty()) {
            return Map.of();
        }
        return projectZoneMapper.selectBatchIds(zoneIds).stream()
                .collect(Collectors.toMap(ProjectZone::getId, zone -> zone,
                        (a, b) -> a, LinkedHashMap::new));
    }

    private Map<Long, Asset> assetsByIds(Set<Long> assetIds) {
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        return assetMapper.selectBatchIds(assetIds).stream()
                .collect(Collectors.toMap(Asset::getId, asset -> asset,
                        (a, b) -> a, LinkedHashMap::new));
    }

    private void addIfNotNull(Set<Long> target, Long id) {
        if (id != null) {
            target.add(id);
        }
    }

    /**
     * 标的名缺失时的占位。
     *
     * <p>标的可能已被硬删（或数据不一致），此时**不能**返回 null：界面上会出现一个空白标签，
     * 读者无从判断是「没填」还是「标的没了」。显式写出 id 才能被排查。
     */
    private String nameOrPlaceholder(String name, Long id) {
        return name == null || name.isBlank() ? "已删除标的 #" + id : name;
    }

    private Map<Long, List<Long>> roleIdsByOperator(List<Long> operatorIds) {
        if (operatorIds.isEmpty()) {
            return Map.of();
        }
        List<AssetOperatorRole> rows = operatorRoleMapper.selectList(
                new LambdaQueryWrapper<AssetOperatorRole>()
                        .in(AssetOperatorRole::getOperatorId, operatorIds)
                        .orderByAsc(AssetOperatorRole::getId));
        Map<Long, List<Long>> grouped = new LinkedHashMap<>();
        for (AssetOperatorRole row : rows) {
            grouped.computeIfAbsent(row.getOperatorId(), key -> new ArrayList<>())
                    .add(row.getRoleId());
        }
        return grouped;
    }

    /** 每个档案的范围计数：{@code [项目数, 分区数, 资产数]}。 */
    private Map<Long, int[]> scopeCountsByOperator(List<Long> operatorIds) {
        if (operatorIds.isEmpty()) {
            return Map.of();
        }
        List<AssetOperatorScope> rows = operatorScopeMapper.selectList(
                new LambdaQueryWrapper<AssetOperatorScope>()
                        .in(AssetOperatorScope::getOperatorId, operatorIds));
        Map<Long, int[]> counts = new LinkedHashMap<>();
        for (AssetOperatorScope row : rows) {
            int[] c = counts.computeIfAbsent(row.getOperatorId(), key -> new int[3]);
            switch (row.getScopeType() == null ? "" : row.getScopeType()) {
                case AssetOperatorScope.TYPE_PROJECT -> c[0]++;
                case AssetOperatorScope.TYPE_ZONE -> c[1]++;
                case AssetOperatorScope.TYPE_ASSET -> c[2]++;
                // 未知类型不计数（不映射成一个「其他」桶：那会让总数与明细对不上）
                default -> {
                }
            }
        }
        return counts;
    }

    private Map<Long, User> usersOf(Collection<Long> userIds) {
        Set<Long> ids = union(userIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId, user -> user, (a, b) -> a,
                        LinkedHashMap::new));
    }

    private Map<Long, String> roleNames(Collection<Long> roleIds) {
        Set<Long> ids = union(roleIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return roleMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Role::getId, Role::getName, (a, b) -> a));
    }

    private Map<Long, String> companyNames(Collection<Long> companyIds) {
        Set<Long> ids = union(companyIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return companyMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Company::getId, Company::getName, (a, b) -> a));
    }

    private Map<Long, String> departmentNames(Collection<Long> departmentIds) {
        Set<Long> ids = union(departmentIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return departmentMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName, (a, b) -> a));
    }

    /** 公司下的项目 id（可再用 {@code projectId} 收窄到单个项目）。 */
    private Set<Long> projectIdsOfCompany(Long companyId, Long projectId) {
        if (projectId != null) {
            Project project = getActiveProject(projectId);
            // 项目不属于所选公司 / 已删除：返回空集而不是抛错 ——
            // 这是**下拉候选**，候选为空是正常结果（与分区下拉「公司没有项目就返回空」同口径）
            if (project == null || !Objects.equals(project.getCompanyId(), companyId)) {
                return Set.of();
            }
            return Set.of(projectId);
        }
        return projectMapper.selectList(new LambdaQueryWrapper<Project>()
                        .apply("deleted_at IS NULL")
                        .eq(Project::getCompanyId, companyId))
                .stream()
                .map(Project::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<Long, String> projectNames(Collection<Long> projectIds) {
        Set<Long> ids = union(projectIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return projectMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Project::getId, Project::getName, (a, b) -> a));
    }

    /**
     * 可空安全的字典取值。
     *
     * <p><b>为什么不能直接 {@code map.get(key)}</b>：本类多处用 {@code Map.of()} 表示「一个都
     * 没查到」，而它是 {@code ImmutableCollections}，{@code get(null)} 会抛 NPE。
     * 被查的键本来就可能是 null（人员的 {@code department_id} 可空、资产的
     * {@code project_id} 可空），所以每个取值点都必须显式挡住 null 键
     * （仓内 {@code AssetService#fillResponsibleNames} 已为同一个坑立过注释）。
     */
    private static <T> T lookup(Map<Long, T> map, Long key) {
        return key == null ? null : map.get(key);
    }

    /** 去 null 后的 id 集合（可空安全的去重入口）。 */
    private Set<Long> union(Collection<Long> ids) {
        Set<Long> all = new LinkedHashSet<>();
        if (ids != null) {
            ids.stream().filter(Objects::nonNull).forEach(all::add);
        }
        return all;
    }

    private List<Long> flatten(Collection<List<Long>> lists) {
        List<Long> all = new ArrayList<>();
        if (lists != null) {
            for (List<Long> list : lists) {
                if (list != null) {
                    all.addAll(list);
                }
            }
        }
        return all;
    }

    private long clampPageSize(long pageSize) {
        return Math.min(Math.max(pageSize, 1), MAX_OPTIONS_PAGE_SIZE);
    }

    private AssetOperator require(Long id) {
        AssetOperator row = id == null ? null : operatorMapper.selectById(id);
        if (row == null || row.getDeletedAt() != null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产运营人员档案不存在：" + id);
        }
        return row;
    }
}
