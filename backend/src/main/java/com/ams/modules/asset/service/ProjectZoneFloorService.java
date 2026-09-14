package com.ams.modules.asset.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.dto.ProjectZoneFloorInput;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.entity.ProjectZoneFloor;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectZoneFloorMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分区楼层的读写（V50）。
 *
 * <p><strong>楼层与资产的关系是「约定」而不是外键</strong>：资产靠
 * {@code asset.zone_id + asset.floor_no} 两个既有字段落层。这样做的取舍 ——
 * 本表只维护「楼层清单」（编号 / 名称 / 备注），所以删改楼层不会碰任何资产归属字段，
 * 代价是**两处必须一起维护**：楼层的资产统计要按 floor_no 聚合，改楼层号要把该层的资产一起改号
 * （见 {@link #update}）。两个动作都在本类里，正是为了让这个约定只有一处实现。
 *
 * <p>权限与数据范围一律由控制器承担（{@code @RequiresPerm} + {@code assertProject}），
 * 本类只管「这条楼层是否属于 URL 上那个分区」——它同时是防跨项目写入的安全边界。
 */
@Service
public class ProjectZoneFloorService {

    private final ProjectZoneFloorMapper floorMapper;
    private final ProjectZoneMapper projectZoneMapper;
    private final AssetMapper assetMapper;

    public ProjectZoneFloorService(
            ProjectZoneFloorMapper floorMapper,
            ProjectZoneMapper projectZoneMapper,
            AssetMapper assetMapper) {
        this.floorMapper = floorMapper;
        this.projectZoneMapper = projectZoneMapper;
        this.assetMapper = assetMapper;
    }

    /**
     * 楼层清单。
     *
     * <p>返回的是 project_zone_floor 行与**资产实际用到的楼层号**的并集：
     * 资产表单可以手填楼层号，因此库里的 floor_no 可能没有对应的楼层行。
     * 只回楼层行会让这些资产在楼层栏里彻底消失（只剩「全部楼层」能看见），
     * 只回资产聚合又会让「还没放资产的新楼层」消失。并集把两种来源都兜住。
     *
     * <p>并集里**来自资产的楼层** {@code id} 为 null：它只是「有资产在用这个楼层号」这个事实，
     * 尚未成为可维护的楼层记录，前端据此把编辑 / 删除置灰。
     */
    public List<ProjectZoneFloorInput> list(Long projectId, Long zoneId) {
        ProjectZone zone = requireProjectZone(projectId, zoneId);
        Map<Integer, AssetStats> stats = floorStats(zone.getId());

        Map<Integer, ProjectZoneFloorInput> merged = new LinkedHashMap<>();
        for (ProjectZoneFloor row : activeFloors(zone.getId())) {
            ProjectZoneFloorInput view = toView(row);
            applyStats(view, stats.remove(row.getFloorNo()));
            merged.put(row.getFloorNo(), view);
        }
        // 剩余的 stats 就是「有资产但还没有楼层记录」的楼层号
        stats.forEach((floorNo, stat) -> {
            ProjectZoneFloorInput view = new ProjectZoneFloorInput();
            view.setZoneId(zone.getId());
            view.setFloorNo(floorNo);
            applyStats(view, stat);
            merged.put(floorNo, view);
        });

        List<ProjectZoneFloorInput> result = new ArrayList<>(merged.values());
        // 按楼层号升序即物理楼层顺序（负数在地下），不另设 sort 制造第二个排序来源
        result.sort((a, b) -> Integer.compare(a.getFloorNo(), b.getFloorNo()));
        return result;
    }

    /** 新增楼层：同分区内楼层号唯一；名称与备注可空（空名前端回落为「<floorNo>F」）。 */
    @Transactional
    public ProjectZoneFloorInput create(Long projectId, Long zoneId, ProjectZoneFloorInput input) {
        ProjectZone zone = requireProjectZone(projectId, zoneId);
        int floorNo = requireFloorNo(input);
        assertFloorNoAvailable(zone.getId(), floorNo, null);

        ProjectZoneFloor target = new ProjectZoneFloor();
        target.setZoneId(zone.getId());
        target.setFloorNo(floorNo);
        target.setName(trimToNull(input == null ? null : input.getName()));
        target.setRemark(trimToNull(input == null ? null : input.getRemark()));
        floorMapper.insert(target);
        return findView(projectId, zoneId, floorNo, toView(target));
    }

    /**
     * 编辑楼层。
     *
     * <p>改楼层号时**连同该分区的资产一起改号**：资产落层靠 floor_no 这个值，
     * 只改楼层记录会让原本挂在这层的资产瞬间「换层」—— 楼层 Tab 立刻显示 0 项，
     * 而资产还在库里带着旧号，变成谁也看不见的孤岛。
     *
     * <p>只影响**该分区**的资产（{@code zone_id = ?}）：其它分区可能有同样的楼层号，
     * 它们与本次改名无关。
     */
    @Transactional
    public ProjectZoneFloorInput update(
            Long projectId, Long zoneId, Long floorId, ProjectZoneFloorInput input) {
        ProjectZone zone = requireProjectZone(projectId, zoneId);
        ProjectZoneFloor existing = requireFloor(zone.getId(), floorId);
        int floorNo = requireFloorNo(input);
        int oldFloorNo = existing.getFloorNo();

        if (floorNo != oldFloorNo) {
            assertFloorNoAvailable(zone.getId(), floorNo, floorId);
        }
        existing.setFloorNo(floorNo);
        existing.setName(trimToNull(input == null ? null : input.getName()));
        existing.setRemark(trimToNull(input == null ? null : input.getRemark()));
        floorMapper.updateById(existing);

        if (floorNo != oldFloorNo) {
            assetMapper.update(null, new LambdaUpdateWrapper<Asset>()
                    .set(Asset::getFloorNo, floorNo)
                    .eq(Asset::getZoneId, zone.getId())
                    .eq(Asset::getFloorNo, oldFloorNo));
        }
        return findView(projectId, zoneId, floorNo, toView(existing));
    }

    /**
     * 删除楼层（软删）。
     *
     * <p>该层还有资产时**拒绝**并报出宗数：与分区删除同一口径（见
     * {@code AssetService#assertZoneRemovable}）—— 楼层的存在性不影响资产归属字段，
     * 所以删除本身不会造出悬空引用，但会让那批资产失去楼层入口；先让使用者搬走或改号。
     *
     * <p>这也意味着「资产楼层号没有对应楼层记录」是允许的中间状态（资产表单可手填），
     * 删除后再新建同号楼层仍能重新看到那批资产 —— 靠的是资产侧的 floor_no，不是本表的行。
     */
    @Transactional
    public void delete(Long projectId, Long zoneId, Long floorId) {
        ProjectZone zone = requireProjectZone(projectId, zoneId);
        ProjectZoneFloor existing = requireFloor(zone.getId(), floorId);
        Long assetCount = assetMapper.selectCount(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getZoneId, zone.getId())
                .eq(Asset::getFloorNo, existing.getFloorNo()));
        if (assetCount != null && assetCount > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "楼层「" + displayName(existing) + "」下有 "
                    + assetCount + " 项资产，无法删除");
        }
        floorMapper.update(null, new LambdaUpdateWrapper<ProjectZoneFloor>()
                .setSql("deleted_at = now()")
                .eq(ProjectZoneFloor::getId, floorId)
                .apply("deleted_at IS NULL"));
    }

    /**
     * 随分区一起软删楼层（分区删除的三条路径都调它）。
     *
     * <p>楼层是分区的从属结构、自身没有独立的生命周期，因此**不做可删性守卫**：
     * 分区能被删就说明它下面已经没有资产了（{@code assertZoneRemovable} 已判过），
     * 楼层自然也没有资产可挂。留在这里是为了避免分区软删后留下一批 zone_id 指向空分区的行。
     */
    public void softDeleteFloorsOfZones(Collection<Long> zoneIds) {
        if (zoneIds == null || zoneIds.isEmpty()) {
            return;
        }
        floorMapper.update(null, new LambdaUpdateWrapper<ProjectZoneFloor>()
                .setSql("deleted_at = now()")
                .in(ProjectZoneFloor::getZoneId, zoneIds)
                .apply("deleted_at IS NULL"));
    }

    /** 项目被整体删除时随分区物理删除楼层，与 {@code AssetService#deleteProject} 的删除口径一致。 */
    public void deleteFloorsOfZones(Collection<Long> zoneIds) {
        if (zoneIds == null || zoneIds.isEmpty()) {
            return;
        }
        floorMapper.delete(new LambdaQueryWrapper<ProjectZoneFloor>()
                .in(ProjectZoneFloor::getZoneId, zoneIds));
    }

    // ---- 内部 ----

    private List<ProjectZoneFloor> activeFloors(Long zoneId) {
        return floorMapper.selectList(new LambdaQueryWrapper<ProjectZoneFloor>()
                .eq(ProjectZoneFloor::getZoneId, zoneId)
                .apply("deleted_at IS NULL")
                .orderByAsc(ProjectZoneFloor::getFloorNo));
    }

    /**
     * 按 floor_no 聚合该分区的资产。
     *
     * <p>一条 SQL 出全部楼层，避免「每个楼层一次 count/sum」的 N+1；
     * 与 {@code AssetService#fillZoneAssetStats} 的口径一致（宗数 + 面积合计）。
     */
    private Map<Integer, AssetStats> floorStats(Long zoneId) {
        QueryWrapper<Asset> wrapper = new QueryWrapper<Asset>()
                .select("floor_no AS floor_no",
                        "COUNT(*) AS asset_count",
                        "COALESCE(SUM(area), 0) AS asset_area")
                .eq("zone_id", zoneId)
                .isNotNull("floor_no")
                .apply("deleted_at IS NULL")
                .groupBy("floor_no");
        Map<Integer, AssetStats> stats = new HashMap<>();
        for (Map<String, Object> row : assetMapper.selectMaps(wrapper)) {
            Object floorNo = row.get("floor_no");
            if (floorNo == null) {
                continue;
            }
            stats.put(((Number) floorNo).intValue(),
                    new AssetStats(toLong(row.get("asset_count")), toBigDecimal(row.get("asset_area"))));
        }
        return stats;
    }

    /** 写路径的返回值与列表元素同形：直接复用 list 的并集结果，避免两处字段口径漂移。 */
    private ProjectZoneFloorInput findView(
            Long projectId, Long zoneId, int floorNo, ProjectZoneFloorInput fallback) {
        return list(projectId, zoneId).stream()
                .filter(view -> Integer.valueOf(floorNo).equals(view.getFloorNo()))
                .findFirst()
                .orElse(fallback);
    }

    /** 取「属于该项目且未软删」的分区，否则抛「分区不存在」（存在性与归属合并成一次查询）。 */
    private ProjectZone requireProjectZone(Long projectId, Long zoneId) {
        ProjectZone zone = projectZoneMapper.selectOne(new LambdaQueryWrapper<ProjectZone>()
                .eq(ProjectZone::getId, zoneId)
                .eq(ProjectZone::getProjectId, projectId)
                .apply("deleted_at IS NULL"));
        if (zone == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "分区不存在");
        }
        return zone;
    }

    /** 取该分区下的楼层，否则抛「楼层不存在」—— 归属校验与存在性合并，杜绝跨分区改楼层。 */
    private ProjectZoneFloor requireFloor(Long zoneId, Long floorId) {
        ProjectZoneFloor floor = floorMapper.selectOne(new LambdaQueryWrapper<ProjectZoneFloor>()
                .eq(ProjectZoneFloor::getId, floorId)
                .eq(ProjectZoneFloor::getZoneId, zoneId)
                .apply("deleted_at IS NULL"));
        if (floor == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "楼层不存在");
        }
        return floor;
    }

    private int requireFloorNo(ProjectZoneFloorInput input) {
        if (input == null || input.getFloorNo() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请填写楼层号");
        }
        return input.getFloorNo();
    }

    /** 同分区内楼层号唯一。{@code excludeId} 为编辑时的自身 id。 */
    private void assertFloorNoAvailable(Long zoneId, int floorNo, Long excludeId) {
        Long count = floorMapper.selectCount(new LambdaQueryWrapper<ProjectZoneFloor>()
                .eq(ProjectZoneFloor::getZoneId, zoneId)
                .eq(ProjectZoneFloor::getFloorNo, floorNo)
                .ne(excludeId != null, ProjectZoneFloor::getId, excludeId)
                .apply("deleted_at IS NULL"));
        if (count != null && count > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "该分区已存在楼层号 " + floorNo + "，请勿重复");
        }
    }

    private ProjectZoneFloorInput toView(ProjectZoneFloor row) {
        ProjectZoneFloorInput view = new ProjectZoneFloorInput();
        view.setId(row.getId());
        view.setZoneId(row.getZoneId());
        view.setFloorNo(row.getFloorNo());
        view.setName(row.getName());
        view.setRemark(row.getRemark());
        return view;
    }

    private void applyStats(ProjectZoneFloorInput view, AssetStats stat) {
        view.setAssetCount(stat == null ? 0L : stat.count());
        view.setAssetArea(stat == null ? BigDecimal.ZERO : stat.area());
    }

    /** 报表 / 报错里的楼层名：与前端回落规则一致（无名称时用「<floorNo>F」）。 */
    private String displayName(ProjectZoneFloor floor) {
        return floor.getName() == null || floor.getName().isBlank()
                ? floor.getFloorNo() + "F"
                : floor.getName();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    /** 单层资产的汇总值。 */
    private record AssetStats(Long count, BigDecimal area) {
    }
}
