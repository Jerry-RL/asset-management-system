package com.ams.modules.map.service;

import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 资产地图点位（FR-MAP-001）：优先资产坐标，回退项目坐标。
 */
@Service
public class MapService {

    private final AssetMapper assetMapper;
    private final ProjectMapper projectMapper;

    public MapService(AssetMapper assetMapper, ProjectMapper projectMapper) {
        this.assetMapper = assetMapper;
        this.projectMapper = projectMapper;
    }

    public List<Map<String, Object>> points(Long companyId, String province, String city) {
        List<Asset> assets = assetMapper.selectList(
                new LambdaQueryWrapper<Asset>()
                        .eq(companyId != null, Asset::getOperatingCompanyId, companyId)
                        .eq(province != null && !province.isBlank(), Asset::getProvince, province)
                        .eq(city != null && !city.isBlank(), Asset::getCity, city)
                        .orderByAsc(Asset::getId));
        Map<Long, Project> projects = new HashMap<>();
        for (Project p : projectMapper.selectList(null)) {
            projects.put(p.getId(), p);
        }
        List<Map<String, Object>> points = new ArrayList<>();
        for (Asset a : assets) {
            BigDecimal lng = a.getLongitude();
            BigDecimal lat = a.getLatitude();
            Project project = a.getProjectId() == null ? null : projects.get(a.getProjectId());
            if ((lng == null || lat == null) && project != null) {
                lng = project.getLongitude();
                lat = project.getLatitude();
            }
            if (lng == null || lat == null) {
                continue;
            }
            Map<String, Object> pt = new HashMap<>();
            pt.put("assetId", a.getId());
            pt.put("assetNo", a.getAssetNo());
            pt.put("name", a.getName());
            pt.put("leaseControlStatus", a.getLeaseControlStatus());
            pt.put("area", a.getArea());
            pt.put("address", a.getAddress());
            pt.put("province", a.getProvince());
            pt.put("city", a.getCity());
            pt.put("district", a.getDistrict());
            pt.put("projectId", a.getProjectId());
            pt.put("longitude", lng);
            pt.put("latitude", lat);
            pt.put("vacantDays", a.getVacantSince() == null ? null
                    : java.time.temporal.ChronoUnit.DAYS.between(a.getVacantSince(), java.time.LocalDateTime.now()));
            points.add(pt);
        }
        return points;
    }
}
