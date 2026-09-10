package com.ams.modules.asset.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.asset.AssetLeaseGroups;
import com.ams.modules.asset.dto.ProjectOverview;
import com.ams.modules.asset.dto.ProjectSaveRequest;
import com.ams.modules.asset.dto.ProjectStats;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.entity.BillPayment;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.billing.mapper.BillPaymentMapper;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.entity.Department;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.CompanyMapper;
import com.ams.modules.org.mapper.DepartmentMapper;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.org.service.CompanyTreeService;
import com.ams.platform.security.LoginUser;
import com.ams.platform.security.RbacService;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 项目与资产台账（FR-AST-001/002/003）。
 */
@Service
public class AssetService {

    private final ProjectMapper projectMapper;
    private final ProjectZoneMapper projectZoneMapper;
    private final AssetMapper assetMapper;
    private final AssetQrService assetQrService;
    private final CompanyTreeService companyTreeService;
    private final CompanyMapper companyMapper;
    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;
    private final RbacService rbacService;
    private final BillMapper billMapper;
    private final BillPaymentMapper billPaymentMapper;

    public AssetService(
            ProjectMapper projectMapper,
            ProjectZoneMapper projectZoneMapper,
            AssetMapper assetMapper,
            AssetQrService assetQrService,
            CompanyTreeService companyTreeService,
            CompanyMapper companyMapper,
            DepartmentMapper departmentMapper,
            UserMapper userMapper,
            RbacService rbacService,
            BillMapper billMapper,
            BillPaymentMapper billPaymentMapper) {
        this.projectMapper = projectMapper;
        this.projectZoneMapper = projectZoneMapper;
        this.assetMapper = assetMapper;
        this.assetQrService = assetQrService;
        this.companyTreeService = companyTreeService;
        this.companyMapper = companyMapper;
        this.departmentMapper = departmentMapper;
        this.userMapper = userMapper;
        this.rbacService = rbacService;
        this.billMapper = billMapper;
        this.billPaymentMapper = billPaymentMapper;
    }

    // ---- 项目 ----

    /**
     * 项目查询条件（列表与统计共用，保证「统计条」与「列表」口径一致）。
     *
     * <p>含关键字、指定公司、以及全局公司切换后的数据范围。
     */
    private LambdaQueryWrapper<Project> projectQuery(String keyword, Long companyId) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<Project>()
                .like(keyword != null && !keyword.isBlank(), Project::getName, keyword)
                .eq(companyId != null, Project::getCompanyId, companyId);
        // 全局公司切换：项目按所属公司收敛（未切换公司的高权限账号不加条件）
        rbacService.applyCompanyScope(wrapper, SecurityUtils.current(), Project::getCompanyId);
        return wrapper;
    }

    public PageResult<Project> pageProjects(long page, long pageSize, String keyword, Long companyId) {
        LambdaQueryWrapper<Project> wrapper = projectQuery(keyword, companyId)
                .orderByDesc(Project::getId);
        Page<Project> result = projectMapper.selectPage(new Page<>(page, pageSize), wrapper);
        fillProjectAssetStats(result.getRecords());
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    /**
     * 项目管理顶部统计条：项目数 / 资产宗数 / 资产利用率 / 闲置宗数 / 盘活宗数。
     *
     * <p>先按与列表完全相同的条件取出项目 ID，再对这些项目的资产做一次聚合
     * （两步而非 SQL 关联，是为了复用同一套数据范围条件，避免统计口径分叉）。
     */
    public ProjectStats projectStats(String keyword, Long companyId) {
        List<Long> projectIds = projectMapper
                .selectList(projectQuery(keyword, companyId).select(Project::getId))
                .stream()
                .map(Project::getId)
                .toList();
        long assetCount = 0;
        long idleCount = 0;
        long revitalizedCount = 0;
        if (!projectIds.isEmpty()) {
            List<Map<String, Object>> rows = assetMapper.selectMaps(new QueryWrapper<Asset>()
                    .select("COUNT(*) AS asset_count",
                            "SUM(CASE WHEN lease_control_status IN "
                                    + AssetLeaseGroups.sqlInList(AssetLeaseGroups.IDLE)
                                    + " THEN 1 ELSE 0 END) AS idle_count",
                            "SUM(CASE WHEN lease_control_status IN "
                                    + AssetLeaseGroups.sqlInList(AssetLeaseGroups.REVITALIZED)
                                    + " THEN 1 ELSE 0 END) AS revitalized_count")
                    .in("project_id", projectIds));
            Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
            assetCount = toLong(row.get("asset_count"));
            idleCount = toLong(row.get("idle_count"));
            revitalizedCount = toLong(row.get("revitalized_count"));
        }
        return ProjectStats.builder()
                .projectCount(projectIds.size())
                .assetCount(assetCount)
                .idleCount(idleCount)
                .revitalizedCount(revitalizedCount)
                .utilizationRate(utilizationRate(assetCount, idleCount))
                .build();
    }

    /**
     * 批量填充项目下的资产统计：一次分组查询取回「项目 → 面积合计 + 宗数 + 闲置/盘活宗数」。
     *
     * <p>刻意不做逐个项目的循环查询（N+1）；无资产的项目补 0，避免前端出现 null。
     */
    private void fillProjectAssetStats(List<Project> projects) {
        if (projects.isEmpty()) {
            return;
        }
        List<Long> projectIds = projects.stream().map(Project::getId).toList();
        List<Map<String, Object>> rows = assetMapper.selectMaps(new QueryWrapper<Asset>()
                .select("project_id",
                        "COALESCE(SUM(area), 0) AS asset_area",
                        "COUNT(*) AS asset_count",
                        "SUM(CASE WHEN lease_control_status IN "
                                + AssetLeaseGroups.sqlInList(AssetLeaseGroups.IDLE)
                                + " THEN 1 ELSE 0 END) AS idle_count",
                        "SUM(CASE WHEN lease_control_status IN "
                                + AssetLeaseGroups.sqlInList(AssetLeaseGroups.REVITALIZED)
                                + " THEN 1 ELSE 0 END) AS revitalized_count")
                .in("project_id", projectIds)
                .groupBy("project_id"));
        Map<Long, Map<String, Object>> rowByProject = rows.stream()
                .collect(Collectors.toMap(row -> toLong(row.get("project_id")), row -> row,
                        (a, b) -> a));
        for (Project project : projects) {
            Map<String, Object> row = rowByProject.get(project.getId());
            long assetCount = row == null ? 0L : toLong(row.get("asset_count"));
            long idleCount = row == null ? 0L : toLong(row.get("idle_count"));
            project.setAssetCount(assetCount);
            project.setIdleCount(idleCount);
            project.setRevitalizedCount(row == null ? 0L : toLong(row.get("revitalized_count")));
            project.setAssetArea(row == null ? BigDecimal.ZERO : toDecimal(row.get("asset_area")));
            project.setUtilizationRate(utilizationRate(assetCount, idleCount));
        }
    }

    /**
     * 资产利用率(%)：按宗数计，(资产宗数 - 闲置宗数) / 资产宗数 × 100，保留一位小数。
     * 无资产时返回 0，避免出现 NaN。
     */
    private static BigDecimal utilizationRate(long assetCount, long idleCount) {
        if (assetCount <= 0) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(assetCount - idleCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(assetCount), 1, RoundingMode.HALF_UP);
    }

    public Project getProject(Long id) {
        Project p = projectMapper.selectById(id);
        if (p == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return p;
    }

    /**
     * 新增项目：第一步写入基本信息，第二步写入分区配置。
     * 伪代码：校验名称/公司 → 落 project → 全量写 project_zone。
     */
    @Transactional
    public Project createProject(ProjectSaveRequest request) {
        String name = validate(request);
        Project project = new Project();
        applyProject(project, request, name);
        projectMapper.insert(project);
        replaceZones(project.getId(), request.getZones());
        return project;
    }

    /** 编辑项目：基本信息 + 分区配置一并保存（分区全量替换）。 */
    @Transactional
    public Project updateProject(Long id, ProjectSaveRequest request) {
        Project project = getProject(id);
        String name = validate(request);
        applyProject(project, request, name);
        projectMapper.updateById(project);
        replaceZones(id, request.getZones());
        return getProject(id);
    }

    /**
     * 项目分区列表：按排序号升序，并汇总每个分区的资产面积/资产数。
     *
     * <p>分区面积不接受人工维护，统一取该分区下资产面积合计。
     */
    public List<ProjectZone> listProjectZones(Long projectId) {
        getProject(projectId);
        List<ProjectZone> zones = projectZoneMapper.selectList(
                new LambdaQueryWrapper<ProjectZone>()
                        .eq(ProjectZone::getProjectId, projectId)
                        .orderByAsc(ProjectZone::getSort)
                        .orderByAsc(ProjectZone::getId));
        fillZoneAssetStats(zones);
        return zones;
    }

    /**
     * 填充分区资产统计：一次分组查询取回「分区 → 面积合计 + 资产数」。
     * 无资产的分区补 0，避免前端出现 null。
     */
    private void fillZoneAssetStats(List<ProjectZone> zones) {
        if (zones.isEmpty()) {
            return;
        }
        List<Long> zoneIds = zones.stream().map(ProjectZone::getId).toList();
        List<Map<String, Object>> stats = assetMapper.selectMaps(new QueryWrapper<Asset>()
                .select("zone_id",
                        "COALESCE(SUM(area), 0) AS asset_area",
                        "COUNT(*) AS asset_count")
                .in("zone_id", zoneIds)
                .groupBy("zone_id"));
        Map<Long, ProjectZone> zoneById = zones.stream()
                .collect(Collectors.toMap(ProjectZone::getId, zone -> zone));
        for (Map<String, Object> row : stats) {
            ProjectZone zone = zoneById.get(toLong(row.get("zone_id")));
            if (zone == null) {
                continue;
            }
            zone.setAssetArea(toDecimal(row.get("asset_area")));
            zone.setAssetCount(toLong(row.get("asset_count")));
        }
        for (ProjectZone zone : zones) {
            if (zone.getAssetArea() == null) {
                zone.setAssetArea(BigDecimal.ZERO);
            }
            if (zone.getAssetCount() == null) {
                zone.setAssetCount(0L);
            }
        }
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.longValue() : Long.valueOf(value.toString());
    }

    /** 计数场景：聚合列可能为 null（如无匹配行），统一回落为 0 */
    private static long toCount(Object value) {
        Long count = toLong(value);
        return count == null ? 0L : count;
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    /** 删除项目：项目下存在资产时禁止删除，否则一并清除分区配置。 */
    @Transactional
    public void deleteProject(Long id) {
        getProject(id);
        Long assetCount = assetMapper.selectCount(
                new LambdaQueryWrapper<Asset>().eq(Asset::getProjectId, id));
        if (assetCount != null && assetCount > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "项目下存在资产，无法删除");
        }
        projectZoneMapper.delete(
                new LambdaQueryWrapper<ProjectZone>().eq(ProjectZone::getProjectId, id));
        projectMapper.deleteById(id);
    }

    private String validate(ProjectSaveRequest request) {
        String name = request.getName() == null ? null : request.getName().trim();
        if (name == null || name.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请填写项目名称");
        }
        if (request.getCompanyId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请选择所属公司");
        }
        return name;
    }

    private void applyProject(Project project, ProjectSaveRequest request, String name) {
        project.setCompanyId(request.getCompanyId());
        project.setName(name);
        project.setAddress(request.getAddress());
        project.setProvince(request.getProvince());
        project.setCity(request.getCity());
        project.setDistrict(request.getDistrict());
        project.setType(request.getType());
        project.setImageUrl(request.getImageUrl());
        project.setImageFileId(request.getImageFileId());
        project.setLongitude(request.getLongitude());
        project.setLatitude(request.getLatitude());
        project.setStatus(request.getStatus() == null ? 1 : request.getStatus());
    }

    /**
     * 分区全量保存：按 id 增量写入。
     *
     * <p>刻意不做「先删后插」—— 分区 id 若每次保存都变化，资产的 zone_id 会立即失效；
     * 这里保留既有 id 仅更新字段，未提交的分区才删除，并把其下资产的归属置空。
     */
    private void replaceZones(Long projectId, List<ProjectZone> zones) {
        List<ProjectZone> existing = projectZoneMapper.selectList(
                new LambdaQueryWrapper<ProjectZone>().eq(ProjectZone::getProjectId, projectId));
        Map<Long, ProjectZone> existingById = existing.stream()
                .collect(Collectors.toMap(ProjectZone::getId, zone -> zone));
        Set<Long> kept = new LinkedHashSet<>();
        int index = 0;
        for (ProjectZone zone : zones == null ? List.<ProjectZone>of() : zones) {
            if (zone == null || zone.getName() == null || zone.getName().isBlank()) {
                continue;
            }
            zone.setProjectId(projectId);
            zone.setName(zone.getName().trim());
            zone.setSort(zone.getSort() == null ? index : zone.getSort());
            Long zoneId = zone.getId();
            if (zoneId != null && existingById.containsKey(zoneId)) {
                projectZoneMapper.updateById(zone);
                kept.add(zoneId);
            } else {
                zone.setId(null);
                projectZoneMapper.insert(zone);
                kept.add(zone.getId());
            }
            index++;
        }
        List<Long> removed = existing.stream()
                .map(ProjectZone::getId)
                .filter(zoneId -> !kept.contains(zoneId))
                .toList();
        if (removed.isEmpty()) {
            return;
        }
        projectZoneMapper.deleteBatchIds(removed);
        // 分区已删除，其下资产的归属同步置空，避免遗留悬空 zone_id
        assetMapper.update(null, new UpdateWrapper<Asset>()
                .setSql("zone_id = NULL")
                .in("zone_id", removed));
    }

    // ---- 资产 ----
    /**
     * 项目详情页聚合视图：项目主体 + 资产基本信息 / 资产创收 / 租赁概况 + 分区汇总。
     *
     * <p>聚合策略：能一次分组算完的走 SQL（租控状态、资产类型、分区维度），
     * 需要跨月累计的实收在内存里按 {@code allocated_at} 归月（数据量受单个项目资产数约束）。
     * 全部查询都以「项目下资产」为锚点，与列表页统计口径一致。
     */
    public ProjectOverview projectOverview(Long projectId) {
        Project project = getProject(projectId);

        // 一次分组覆盖「项目总计 + 分区维度 + 租控状态分布」三类口径
        List<Map<String, Object>> statusRows = assetMapper.selectMaps(new QueryWrapper<Asset>()
                .select("zone_id", "lease_control_status",
                        "COUNT(*) AS asset_count",
                        "COALESCE(SUM(area), 0) AS asset_area")
                .eq("project_id", projectId)
                .groupBy("zone_id", "lease_control_status"));
        List<Map<String, Object>> typeRows = assetMapper.selectMaps(new QueryWrapper<Asset>()
                .select("asset_type", "COUNT(*) AS asset_count", "COALESCE(SUM(area), 0) AS asset_area")
                .eq("project_id", projectId)
                .groupBy("asset_type"));
        List<Map<String, Object>> floorRows = assetMapper.selectMaps(new QueryWrapper<Asset>()
                .select("zone_id", "COUNT(DISTINCT floor_no) AS floor_count")
                .eq("project_id", projectId)
                .groupBy("zone_id"));

        long assetCount = 0;
        long idleCount = 0;
        long rentedCount = 0;
        BigDecimal totalArea = BigDecimal.ZERO;
        Map<String, long[]> statusCounts = new LinkedHashMap<>();
        Map<String, BigDecimal> statusAreas = new LinkedHashMap<>();
        Map<Long, ZoneAgg> zoneAggs = new LinkedHashMap<>();
        for (Map<String, Object> row : statusRows) {
            String status = row.get("lease_control_status") == null
                    ? null : row.get("lease_control_status").toString();
            long count = toCount(row.get("asset_count"));
            BigDecimal area = toDecimal(row.get("asset_area"));
            assetCount += count;
            totalArea = totalArea.add(area);
            if (status != null) {
                if (AssetLeaseGroups.IDLE.contains(status)) {
                    idleCount += count;
                }
                if (AssetLeaseGroups.REVITALIZED.contains(status)) {
                    rentedCount += count;
                }
                statusCounts.computeIfAbsent(status, k -> new long[1])[0] += count;
                statusAreas.merge(status, area, BigDecimal::add);
            }
            Long zoneId = toLong(row.get("zone_id"));
            ZoneAgg agg = zoneAggs.computeIfAbsent(zoneId, k -> new ZoneAgg());
            agg.assetCount += count;
            agg.assetArea = agg.assetArea.add(area);
            if (status != null) {
                agg.statusCounts.merge(status, count, Long::sum);
                if (AssetLeaseGroups.IDLE.contains(status)) {
                    agg.idleCount += count;
                }
            }
        }

        Map<Long, Long> floorCountByZone = new HashMap<>();
        long totalFloors = 0;
        for (Map<String, Object> row : floorRows) {
            long floors = toCount(row.get("floor_count"));
            floorCountByZone.put(toLong(row.get("zone_id")), floors);
            totalFloors += floors;
        }

        List<ProjectZone> zones = projectZoneMapper.selectList(new LambdaQueryWrapper<ProjectZone>()
                .eq(ProjectZone::getProjectId, projectId)
                .orderByAsc(ProjectZone::getSort)
                .orderByAsc(ProjectZone::getId));
        List<ProjectOverview.ZoneSummary> zoneSummaries = new ArrayList<>();
        for (ProjectZone zone : zones) {
            ZoneAgg agg = zoneAggs.getOrDefault(zone.getId(), new ZoneAgg());
            zoneSummaries.add(ProjectOverview.ZoneSummary.builder()
                    .id(zone.getId())
                    .name(zone.getName())
                    .code(zone.getCode())
                    .sort(zone.getSort())
                    .assetCount(agg.assetCount)
                    .assetArea(agg.assetArea)
                    .idleCount(agg.idleCount)
                    .inUseCount(agg.assetCount - agg.idleCount)
                    .floorCount(floorCountByZone.getOrDefault(zone.getId(), 0L))
                    .statusCounts(agg.statusCounts)
                    .build());
        }
        // 未归入任何分区的资产单独成组，避免「分区资产数合计 ≠ 项目资产数」对不上账
        ZoneAgg unzoned = zoneAggs.get(null);
        if (unzoned != null && unzoned.assetCount > 0) {
            zoneSummaries.add(ProjectOverview.ZoneSummary.builder()
                    .id(null)
                    .name("未划分区")
                    .code(null)
                    .sort(Integer.MAX_VALUE)
                    .assetCount(unzoned.assetCount)
                    .assetArea(unzoned.assetArea)
                    .idleCount(unzoned.idleCount)
                    .inUseCount(unzoned.assetCount - unzoned.idleCount)
                    .floorCount(floorCountByZone.getOrDefault(null, 0L))
                    .statusCounts(unzoned.statusCounts)
                    .build());
        }

        ProjectOverview.Metrics.MetricsBuilder metrics = ProjectOverview.Metrics.builder()
                .utilizationRate(utilizationRate(assetCount, idleCount))
                .totalArea(totalArea)
                .assetCount(assetCount)
                .idleCount(idleCount)
                .inUseCount(assetCount - idleCount)
                .leaseRate(ratio(BigDecimal.valueOf(rentedCount), BigDecimal.valueOf(assetCount)))
                .rentedCount(rentedCount)
                .unrentedCount(assetCount - rentedCount)
                .leaseStatusBreakdown(toSlices(statusCounts, statusAreas))
                .assetTypeBreakdown(toSlices(typeRows));
        fillRevenue(projectId, metrics);

        return ProjectOverview.builder()
                .project(ProjectOverview.ProjectDetail.builder()
                        .id(project.getId())
                        .name(project.getName())
                        .type(project.getType())
                        .status(project.getStatus())
                        .address(project.getAddress())
                        .province(project.getProvince())
                        .city(project.getCity())
                        .district(project.getDistrict())
                        .longitude(project.getLongitude())
                        .latitude(project.getLatitude())
                        .imageUrl(project.getImageUrl())
                        .imageFileId(project.getImageFileId())
                        .companyId(project.getCompanyId())
                        .companyName(companyName(project.getCompanyId()))
                        .createdAt(project.getCreatedAt())
                        .zoneCount(zones.size())
                        .floorCount(totalFloors)
                        .build())
                .metrics(metrics.build())
                .zones(zoneSummaries)
                .build();
    }

    /**
     * 填充「资产创收 / 租赁概况」中的金额类指标。
     *
     * <p>实收以核销记录 {@code bill_payment.allocated_at} 归月（现金实现制），
     * 收缴率以账单维度的「已核销本金 / (应收本金 - 减免)」计算，两者各自可对账。
     */
    private void fillRevenue(Long projectId, ProjectOverview.Metrics.MetricsBuilder metrics) {
        List<Long> assetIds = assetMapper
                .selectList(new LambdaQueryWrapper<Asset>()
                        .select(Asset::getId)
                        .eq(Asset::getProjectId, projectId))
                .stream()
                .map(Asset::getId)
                .toList();
        if (assetIds.isEmpty()) {
            metrics.accumulatedReceived(BigDecimal.ZERO)
                    .yearReceived(BigDecimal.ZERO)
                    .monthlyReceived(emptyMonthlySeries())
                    .lastMonthCollectRate(BigDecimal.ZERO)
                    .lastMonthReceivable(BigDecimal.ZERO)
                    .lastMonthReceived(BigDecimal.ZERO)
                    .lastMonthLabel(lastMonth().toString());
            return;
        }
        List<Bill> bills = billMapper.selectList(new LambdaQueryWrapper<Bill>()
                .in(Bill::getAssetId, assetIds));
        if (bills.isEmpty()) {
            metrics.accumulatedReceived(BigDecimal.ZERO)
                    .yearReceived(BigDecimal.ZERO)
                    .monthlyReceived(emptyMonthlySeries())
                    .lastMonthCollectRate(BigDecimal.ZERO)
                    .lastMonthReceivable(BigDecimal.ZERO)
                    .lastMonthReceived(BigDecimal.ZERO)
                    .lastMonthLabel(lastMonth().toString());
            return;
        }

        // 上月收缴率：按账单口径，只统计「应付款落在上月」的账单
        YearMonth lastMonth = lastMonth();
        BigDecimal receivable = BigDecimal.ZERO;
        BigDecimal receivedOnDue = BigDecimal.ZERO;
        for (Bill bill : bills) {
            if (bill.getDueDate() == null || !YearMonth.from(bill.getDueDate()).equals(lastMonth)) {
                continue;
            }
            receivable = receivable.add(nvl(bill.getAmount()).subtract(nvl(bill.getReducedAmount())));
            receivedOnDue = receivedOnDue.add(nvl(bill.getPaidAmount()));
        }

        // 实收：按核销时间归月（现金实现制），覆盖近 13 个月用于「近一年」柱状图
        List<Long> billIds = bills.stream().map(Bill::getId).toList();
        List<BillPayment> allocations = billPaymentMapper.selectList(
                new LambdaQueryWrapper<BillPayment>()
                        .in(BillPayment::getBillId, billIds)
                        .eq(BillPayment::getAmountType, "principal"));
        BigDecimal accumulated = BigDecimal.ZERO;
        BigDecimal yearReceived = BigDecimal.ZERO;
        LocalDate seriesStart = YearMonth.from(LocalDate.now()).minusMonths(11).atDay(1);
        Map<String, BigDecimal> byMonth = new LinkedHashMap<>();
        for (BillPayment allocation : allocations) {
            BigDecimal amount = nvl(allocation.getAmount());
            accumulated = accumulated.add(amount);
            LocalDateTime allocatedAt = allocation.getAllocatedAt();
            if (allocatedAt == null) {
                continue;
            }
            if (allocatedAt.getYear() == LocalDate.now().getYear()) {
                yearReceived = yearReceived.add(amount);
            }
            if (!allocatedAt.toLocalDate().isBefore(seriesStart)) {
                String month = YearMonth.from(allocatedAt).toString();
                byMonth.merge(month, amount, BigDecimal::add);
            }
        }

        metrics.accumulatedReceived(accumulated)
                .yearReceived(yearReceived)
                .monthlyReceived(monthlySeries(byMonth))
                .lastMonthCollectRate(ratio(receivedOnDue, receivable))
                .lastMonthReceivable(receivable)
                .lastMonthReceived(receivedOnDue)
                .lastMonthLabel(lastMonth.toString());
    }

    /** 近 12 个月序列（含当月），缺月补 0，保证前端柱状图刻度稳定 */
    private static List<ProjectOverview.MonthlyAmount> monthlySeries(Map<String, BigDecimal> byMonth) {
        List<ProjectOverview.MonthlyAmount> series = new ArrayList<>(12);
        YearMonth cursor = YearMonth.from(LocalDate.now()).minusMonths(11);
        for (int i = 0; i < 12; i++) {
            String key = cursor.toString();
            series.add(ProjectOverview.MonthlyAmount.builder()
                    .month(key)
                    .amount(byMonth.getOrDefault(key, BigDecimal.ZERO))
                    .build());
            cursor = cursor.plusMonths(1);
        }
        return series;
    }

    private static List<ProjectOverview.MonthlyAmount> emptyMonthlySeries() {
        return monthlySeries(Map.of());
    }

    private static YearMonth lastMonth() {
        return YearMonth.from(LocalDate.now()).minusMonths(1);
    }

    private static List<ProjectOverview.Slice> toSlices(
            Map<String, long[]> counts, Map<String, BigDecimal> areas) {
        List<ProjectOverview.Slice> slices = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : counts.entrySet()) {
            slices.add(ProjectOverview.Slice.builder()
                    .value(entry.getKey())
                    .count(entry.getValue()[0])
                    .area(areas.getOrDefault(entry.getKey(), BigDecimal.ZERO))
                    .build());
        }
        slices.sort((a, b) -> Long.compare(b.getCount(), a.getCount()));
        return slices;
    }

    private static List<ProjectOverview.Slice> toSlices(List<Map<String, Object>> rows) {
        List<ProjectOverview.Slice> slices = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            slices.add(ProjectOverview.Slice.builder()
                    .value(row.get("asset_type") == null ? null : row.get("asset_type").toString())
                    .count(toCount(row.get("asset_count")))
                    .area(toDecimal(row.get("asset_area")))
                    .build());
        }
        slices.sort((a, b) -> Long.compare(b.getCount(), a.getCount()));
        return slices;
    }

    private String companyName(Long companyId) {
        if (companyId == null) {
            return null;
        }
        Company company = companyMapper.selectById(companyId);
        return company == null ? null : company.getName();
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 比例(%)：分母为 0 时返回 0，避免出现 NaN / Infinity */
    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
        return nvl(numerator).multiply(BigDecimal.valueOf(100))
                .divide(denominator, 1, RoundingMode.HALF_UP);
    }

    /** 分区聚合中间态（按 zone_id 归拢租控状态行） */
    private static final class ZoneAgg {
        private long assetCount;
        private long idleCount;
        private BigDecimal assetArea = BigDecimal.ZERO;
        private final Map<String, Long> statusCounts = new LinkedHashMap<>();
    }

    /**
     * 资产分页。
     *
     * @param projectType 「项目属性」筛选，口径为所属项目的 project.type
     *                    （取值见 sys_dict_type.code = project_property）；
     *                    与「资产来源」级联筛选配合使用。
     * @param projectId   限定项目（项目详情页底部资产列表）
     * @param zoneId      限定分区（项目详情页切换分区后）
     */
    public PageResult<Asset> pageAssets(long page, long pageSize, String assetType, String keyword,
            String sourceType, String ownershipType, String leaseControlStatus, Long companyId,
            String projectType, Long projectId, Long zoneId) {
        LoginUser user = SecurityUtils.current();
        Set<Long> scope = rbacService.companyScope(user);
        Page<Asset> result = assetMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<Asset>()
                        .eq(assetType != null, Asset::getAssetType, assetType)
                        .eq(sourceType != null, Asset::getSourceType, sourceType)
                        .eq(ownershipType != null, Asset::getOwnershipType, ownershipType)
                        .eq(leaseControlStatus != null, Asset::getLeaseControlStatus, leaseControlStatus)
                        .eq(companyId != null, Asset::getOperatingCompanyId, companyId)
                        .eq(projectId != null, Asset::getProjectId, projectId)
                        .eq(zoneId != null, Asset::getZoneId, zoneId)
                        // 项目属性在 project 表上，用 EXISTS 子查询过滤，避免先查项目 ID 再回填
                        .exists(hasText(projectType),
                                "select 1 from project p where p.id = asset.project_id"
                                        + " and p.deleted_at is null and p.type = {0}",
                                projectType)
                        .in(!scope.isEmpty(), Asset::getOperatingCompanyId, scope)
                        .and(keyword != null && !keyword.isBlank(),
                                w -> w.like(Asset::getName, keyword).or().like(Asset::getAssetNo, keyword))
                        .orderByAsc(Asset::getZoneId)
                        .orderByAsc(Asset::getFloorNo)
                        .orderByDesc(Asset::getId));
        fillDisplayNames(result.getRecords());
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    /** 回显名称：一次批量查询补齐项目 / 分区 / 公司 / 责任部门 / 责任人，避免列表露出裸 ID。 */
    private void fillDisplayNames(List<Asset> assets) {
        if (assets == null || assets.isEmpty()) {
            return;
        }
        fillProjectNames(assets);
        fillZoneNames(assets);
        fillCompanyNames(assets);
        fillResponsibleNames(assets);
    }

    /** 回显所属项目名称与项目属性（项目属性决定「资产来源」的可选范围）。 */
    private void fillProjectNames(List<Asset> assets) {
        Set<Long> projectIds = assets.stream()
                .map(Asset::getProjectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (projectIds.isEmpty()) {
            return;
        }
        Map<Long, Project> projects = projectMapper.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(Project::getId, p -> p, (a, b) -> a));
        for (Asset asset : assets) {
            Project project = projects.get(asset.getProjectId());
            if (project == null) {
                continue;
            }
            asset.setProjectName(project.getName());
            asset.setProjectType(project.getType());
        }
    }

    /** 回显资产公司 / 产权公司名称。 */
    private void fillCompanyNames(List<Asset> assets) {
        Set<Long> companyIds = new LinkedHashSet<>();
        assets.forEach(asset -> {
            if (asset.getAssetCompanyId() != null) {
                companyIds.add(asset.getAssetCompanyId());
            }
            if (asset.getPropertyCompanyId() != null) {
                companyIds.add(asset.getPropertyCompanyId());
            }
        });
        if (companyIds.isEmpty()) {
            return;
        }
        Map<Long, String> names = companyMapper.selectBatchIds(companyIds).stream()
                .collect(Collectors.toMap(Company::getId, Company::getName, (a, b) -> a));
        for (Asset asset : assets) {
            asset.setAssetCompanyName(names.get(asset.getAssetCompanyId()));
            asset.setPropertyCompanyName(names.get(asset.getPropertyCompanyId()));
        }
    }

    /** 回显责任部门与责任人姓名。 */
    private void fillResponsibleNames(List<Asset> assets) {
        Set<Long> departmentIds = assets.stream()
                .map(Asset::getResponsibleDepartmentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> userIds = assets.stream()
                .map(Asset::getResponsibleUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // 注意：不能用 Map.of() 兜底 —— 它是 ImmutableCollections，get(null) 会抛 NPE，
        // 而未设置责任部门/责任人的资产（存量数据普遍如此）查的就是 null 键。
        Map<Long, String> departmentNames = departmentIds.isEmpty()
                ? Collections.emptyMap()
                : departmentMapper.selectBatchIds(departmentIds).stream()
                        .collect(Collectors.toMap(Department::getId, Department::getName, (a, b) -> a));
        Map<Long, String> userNames = userIds.isEmpty()
                ? Collections.emptyMap()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getName, (a, b) -> a));
        for (Asset asset : assets) {
            asset.setResponsibleDepartmentName(
                    departmentNames.get(asset.getResponsibleDepartmentId()));
            asset.setResponsibleUserName(userNames.get(asset.getResponsibleUserId()));
        }
    }

    /** 回显分区名称：一次查询取回本页资产涉及的分区。 */
    private void fillZoneNames(List<Asset> assets) {
        Set<Long> zoneIds = assets.stream()
                .map(Asset::getZoneId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (zoneIds.isEmpty()) {
            return;
        }
        Map<Long, String> names = projectZoneMapper.selectBatchIds(zoneIds).stream()
                .collect(Collectors.toMap(ProjectZone::getId, ProjectZone::getName));
        for (Asset asset : assets) {
            if (asset.getZoneId() != null) {
                asset.setZoneName(names.get(asset.getZoneId()));
            }
        }
    }

    public Asset getAsset(Long id) {
        Asset asset = assetMapper.selectById(id);
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        fillDisplayNames(List.of(asset));
        return asset;
    }

    /** 校验分区归属：分区必须存在且属于该资产所在项目。 */
    private void validateZone(Long projectId, Long zoneId) {
        if (zoneId == null) {
            return;
        }
        if (projectId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请先选择所属项目再选择分区");
        }
        ProjectZone zone = projectZoneMapper.selectById(zoneId);
        if (zone == null || !projectId.equals(zone.getProjectId())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "所选分区不属于该项目");
        }
    }

    /**
     * 级联校验（表单联动口径）：
     * 资产公司 → 项目（项目须属于该资产公司）→ 分区；
     * 资产公司 → 责任部门（部门须属于该资产公司）→ 责任人（须属于该责任部门）。
     *
     * <p>服务端必须自行校验，不能只依赖前端联动，否则越权/串数据可通过直接调用接口写入。
     */
    private void validateReferences(Asset asset) {
        validateZone(asset.getProjectId(), asset.getZoneId());
        if (asset.getProjectId() != null) {
            Project project = projectMapper.selectById(asset.getProjectId());
            if (project == null) {
                throw new AppException(ErrorCode.BAD_REQUEST, "所选项目不存在");
            }
            if (asset.getAssetCompanyId() != null
                    && project.getCompanyId() != null
                    && !project.getCompanyId().equals(asset.getAssetCompanyId())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "所选项目不属于该资产公司");
            }
        }
        if (asset.getResponsibleDepartmentId() != null) {
            Department department = departmentMapper.selectById(asset.getResponsibleDepartmentId());
            if (department == null) {
                throw new AppException(ErrorCode.BAD_REQUEST, "所选责任部门不存在");
            }
            if (asset.getAssetCompanyId() != null
                    && department.getCompanyId() != null
                    && !department.getCompanyId().equals(asset.getAssetCompanyId())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "所选责任部门不属于该资产公司");
            }
        }
        if (asset.getResponsibleUserId() != null) {
            User responsible = userMapper.selectById(asset.getResponsibleUserId());
            if (responsible == null) {
                throw new AppException(ErrorCode.BAD_REQUEST, "所选责任人不存在");
            }
            if (asset.getResponsibleDepartmentId() != null
                    && responsible.getDepartmentId() != null
                    && !responsible.getDepartmentId().equals(asset.getResponsibleDepartmentId())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "所选责任人不属于该责任部门");
            }
        }
    }

    /**
     * 「资产公司」为表单权威归属，同步镜像到 {@code operating_company_id}。
     *
     * <p>数据范围隔离（见 {@code RbacService#companyScope}）、经营看板分组、监管报送与资产划拨
     * 均以 {@code operating_company_id} 为锚点；若新表单只写资产公司，新资产会落到
     * 非全量用户的数据范围之外（看板统计也会漏掉）。故在此保持两者一致。
     */
    private void syncOperatingCompany(Asset asset) {
        if (asset.getAssetCompanyId() != null) {
            asset.setOperatingCompanyId(asset.getAssetCompanyId());
        }
    }

    public Asset createAsset(Asset asset) {
        validateReferences(asset);
        syncOperatingCompany(asset);
        if (asset.getLeaseControlStatus() == null) {
            asset.setLeaseControlStatus("vacant");
        }
        if (asset.getStructureStatus() == null) {
            asset.setStructureStatus("active");
        }
        asset.setVersion(0);
        assetMapper.insert(asset);
        return assetQrService.ensureQrCode(asset);
    }

    public Asset updateAsset(Long id, Asset asset) {
        Asset existing = getAsset(id);
        validateReferences(asset);
        syncOperatingCompany(asset);
        asset.setId(id);
        // 基础信息可改，租控状态不可通过此接口直改（须走业务单据）
        asset.setLeaseControlStatus(existing.getLeaseControlStatus());
        // 可编辑字段需支持「清空」，而 null 在默认更新策略下会被忽略：
        // 先从实体上摘除（避免与 wrapper 的 SET 拼出重复赋值），再用显式 set 写回。
        int updated = assetMapper.update(asset, new LambdaUpdateWrapper<Asset>()
                .eq(Asset::getId, id)
                .set(Asset::getProjectId, take(asset.getProjectId(), asset::setProjectId))
                .set(Asset::getZoneId, take(asset.getZoneId(), asset::setZoneId))
                .set(Asset::getAssetCompanyId,
                        take(asset.getAssetCompanyId(), asset::setAssetCompanyId))
                .set(Asset::getAssetType, take(asset.getAssetType(), asset::setAssetType))
                .set(Asset::getArea, take(asset.getArea(), asset::setArea))
                .set(Asset::getLeaseArea, take(asset.getLeaseArea(), asset::setLeaseArea))
                .set(Asset::getFloorNo, take(asset.getFloorNo(), asset::setFloorNo))
                .set(Asset::getSourceType, take(asset.getSourceType(), asset::setSourceType))
                .set(Asset::getOwnershipType, take(asset.getOwnershipType(), asset::setOwnershipType))
                .set(Asset::getPartialLeaseStatus,
                        take(asset.getPartialLeaseStatus(), asset::setPartialLeaseStatus))
                .set(Asset::getAssetNature, take(asset.getAssetNature(), asset::setAssetNature))
                .set(Asset::getBuildingPlan, take(asset.getBuildingPlan(), asset::setBuildingPlan))
                .set(Asset::getUsageType, take(asset.getUsageType(), asset::setUsageType))
                .set(Asset::getHouseType, take(asset.getHouseType(), asset::setHouseType))
                .set(Asset::getStructureType, take(asset.getStructureType(), asset::setStructureType))
                .set(Asset::getRegisteredAt, take(asset.getRegisteredAt(), asset::setRegisteredAt))
                .set(Asset::getResponsibleDepartmentId,
                        take(asset.getResponsibleDepartmentId(), asset::setResponsibleDepartmentId))
                .set(Asset::getResponsibleUserId,
                        take(asset.getResponsibleUserId(), asset::setResponsibleUserId))
                .set(Asset::getOriginalValue,
                        take(asset.getOriginalValue(), asset::setOriginalValue))
                .set(Asset::getImageUrl, take(asset.getImageUrl(), asset::setImageUrl))
                .set(Asset::getImageFileId, take(asset.getImageFileId(), asset::setImageFileId))
                .set(Asset::getWaterMeterNo,
                        take(asset.getWaterMeterNo(), asset::setWaterMeterNo))
                .set(Asset::getElectricMeterNo,
                        take(asset.getElectricMeterNo(), asset::setElectricMeterNo))
                .set(Asset::getPropertyCompanyId,
                        take(asset.getPropertyCompanyId(), asset::setPropertyCompanyId))
                .set(Asset::getOperatingCompanyId,
                        take(asset.getOperatingCompanyId(), asset::setOperatingCompanyId))
                .set(Asset::getProvince, take(asset.getProvince(), asset::setProvince))
                .set(Asset::getCity, take(asset.getCity(), asset::setCity))
                .set(Asset::getDistrict, take(asset.getDistrict(), asset::setDistrict))
                .set(Asset::getAddress, take(asset.getAddress(), asset::setAddress))
                .set(Asset::getBaseRentFloor,
                        take(asset.getBaseRentFloor(), asset::setBaseRentFloor))
                .set(Asset::getBaseRentAssessed,
                        take(asset.getBaseRentAssessed(), asset::setBaseRentAssessed))
                .set(Asset::getMarketRefRent,
                        take(asset.getMarketRefRent(), asset::setMarketRefRent)));
        // @Version 乐观锁：版本不一致时更新 0 行，必须显式失败，否则并发修改会被静默丢弃
        if (updated == 0) {
            throw new AppException(ErrorCode.CONFLICT, "资产已被其他操作修改，请刷新后重试");
        }
        return getAsset(id);
    }

    /**
     * 取出待写入的值，并在实体上清空同名字段。
     *
     * <p>MyBatis-Plus 默认更新策略会跳过 null 字段，导致列无法被置空；而实体字段与
     * {@code wrapper.set(...)} 同时携带同一列会拼出重复的 SET 赋值（PostgreSQL 直接报错）。
     * 因此把「清空」显式交给 wrapper：先从实体摘除，再按原值写回。
     */
    private static <T> T take(T value, Consumer<T> clearOnEntity) {
        clearOnEntity.accept(null);
        return value;
    }

    /** 字符串非空判断：用于可选查询条件的「有值才拼条件」。 */
    private static boolean hasText(String value) {
        return StringUtils.hasText(value);
    }

    public void deleteAsset(Long id) {
        Asset asset = getAsset(id);
        if (!"vacant".equals(asset.getLeaseControlStatus())) {
            throw new AppException(ErrorCode.BUSINESS_ERROR, "非空置资产不可删除");
        }
        assetMapper.deleteById(id);
    }
}
