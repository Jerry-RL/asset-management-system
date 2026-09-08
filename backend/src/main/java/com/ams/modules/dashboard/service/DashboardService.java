package com.ams.modules.dashboard.service;

import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.mapper.CompanyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 经营看板（FR-DASH-001）+ 集团合并看板下钻（FR-DASH-002）。
 */
@Service
public class DashboardService {

    private final AssetMapper assetMapper;
    private final ContractMapper contractMapper;
    private final BillMapper billMapper;
    private final CompanyMapper companyMapper;
    private final ProjectMapper projectMapper;

    public DashboardService(
            AssetMapper assetMapper,
            ContractMapper contractMapper,
            BillMapper billMapper,
            CompanyMapper companyMapper,
            ProjectMapper projectMapper) {
        this.assetMapper = assetMapper;
        this.contractMapper = contractMapper;
        this.billMapper = billMapper;
        this.companyMapper = companyMapper;
        this.projectMapper = projectMapper;
    }

    public Map<String, Object> operations(Long companyId) {
        List<Asset> assets = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(companyId != null, Asset::getOperatingCompanyId, companyId));
        Set<Long> assetIds = assets.stream().map(Asset::getId).collect(Collectors.toSet());
        List<Contract> contracts = contractMapper.selectList(null);
        if (companyId != null) {
            contracts = contracts.stream()
                    .filter(c -> c.getAssetId() != null && assetIds.contains(c.getAssetId()))
                    .toList();
        }
        List<Bill> bills = billMapper.selectList(null);
        if (companyId != null) {
            Set<Long> contractIds = contracts.stream().map(Contract::getId).collect(Collectors.toSet());
            bills = bills.stream()
                    .filter(b -> b.getContractId() != null && contractIds.contains(b.getContractId()))
                    .toList();
        }
        return buildOps(assets, contracts, bills);
    }

    /** 集团合并看板（FR-DASH-002）：汇总 + 分子公司。 */
    public Map<String, Object> consolidate() {
        List<Asset> assets = assetMapper.selectList(null);
        List<Bill> bills = billMapper.selectList(null);
        long assetTotal = assets.size();
        long leasedCount = assets.stream()
                .filter(a -> "leased".equals(a.getLeaseControlStatus())
                        || "partial_leased".equals(a.getLeaseControlStatus()))
                .count();
        BigDecimal vacantArea = assets.stream()
                .filter(a -> "vacant".equals(a.getLeaseControlStatus()))
                .map(a -> a.getArea() == null ? BigDecimal.ZERO : a.getArea())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<Long, List<Asset>> byCompany = assets.stream()
                .filter(a -> a.getOperatingCompanyId() != null)
                .collect(Collectors.groupingBy(Asset::getOperatingCompanyId));

        List<Company> companies = companyMapper.selectList(null);
        Map<Long, String> nameMap = companies.stream()
                .collect(Collectors.toMap(Company::getId, c -> c.getName() == null ? "" : c.getName(), (a, b) -> a));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Long, List<Asset>> e : byCompany.entrySet()) {
            Long companyId = e.getKey();
            List<Asset> list = e.getValue();
            Set<Long> ids = list.stream().map(Asset::getId).collect(Collectors.toSet());
            List<Bill> companyBills = bills.stream()
                    .filter(b -> b.getAssetId() != null && ids.contains(b.getAssetId()))
                    .toList();
            Map<String, Object> ops = buildOps(list, List.of(), companyBills);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("companyId", companyId);
            row.put("companyName", nameMap.getOrDefault(companyId, "公司" + companyId));
            row.put("assetTotal", ops.get("assetTotal"));
            row.put("leasedRate", ops.get("leasedRate"));
            row.put("vacantArea", ops.get("vacantArea"));
            row.put("arrears", ops.get("arrears"));
            row.put("collectionRate", ops.get("collectionRate"));
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groupAssetTotal", assetTotal);
        result.put("groupLeasedRate", assetTotal == 0 ? 0 : (double) leasedCount / assetTotal);
        result.put("groupVacantArea", vacantArea);
        result.put("byCompany", rows);
        return result;
    }

    /** 下钻：公司 → 项目 → 资产。 */
    public Map<String, Object> drill(Long companyId, Long projectId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (companyId == null) {
            result.put("level", "group");
            result.put("items", consolidate().get("byCompany"));
            return result;
        }
        if (projectId == null) {
            List<Project> projects = projectMapper.selectList(
                    new LambdaQueryWrapper<Project>().eq(Project::getCompanyId, companyId));
            List<Asset> assets = assetMapper.selectList(
                    new LambdaQueryWrapper<Asset>().eq(Asset::getOperatingCompanyId, companyId));
            List<Map<String, Object>> items = new ArrayList<>();
            Map<Long, List<Asset>> byProject = assets.stream()
                    .filter(a -> a.getProjectId() != null)
                    .collect(Collectors.groupingBy(Asset::getProjectId));
            for (Project p : projects) {
                List<Asset> list = byProject.getOrDefault(p.getId(), List.of());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("projectId", p.getId());
                row.put("projectName", p.getName());
                row.put("assetTotal", list.size());
                row.put("vacantArea", list.stream()
                        .filter(a -> "vacant".equals(a.getLeaseControlStatus()))
                        .map(a -> a.getArea() == null ? BigDecimal.ZERO : a.getArea())
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
                long leased = list.stream()
                        .filter(a -> "leased".equals(a.getLeaseControlStatus())
                                || "partial_leased".equals(a.getLeaseControlStatus()))
                        .count();
                row.put("leasedRate", list.isEmpty() ? 0 : (double) leased / list.size());
                items.add(row);
            }
            // 无项目归属
            List<Asset> orphan = assets.stream().filter(a -> a.getProjectId() == null).toList();
            if (!orphan.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("projectId", null);
                row.put("projectName", "(未归属项目)");
                row.put("assetTotal", orphan.size());
                items.add(row);
            }
            result.put("level", "company");
            result.put("companyId", companyId);
            result.put("items", items);
            return result;
        }
        List<Asset> assets = assetMapper.selectList(
                new LambdaQueryWrapper<Asset>()
                        .eq(Asset::getOperatingCompanyId, companyId)
                        .eq(Asset::getProjectId, projectId));
        List<Map<String, Object>> items = assets.stream().map(a -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("assetId", a.getId());
            row.put("assetNo", a.getAssetNo());
            row.put("name", a.getName());
            row.put("area", a.getArea());
            row.put("leaseControlStatus", a.getLeaseControlStatus());
            return row;
        }).toList();
        result.put("level", "project");
        result.put("companyId", companyId);
        result.put("projectId", projectId);
        result.put("items", items);
        return result;
    }

    private Map<String, Object> buildOps(List<Asset> assets, List<Contract> contracts, List<Bill> bills) {
        long assetTotal = assets.size();
        BigDecimal totalArea = assets.stream()
                .map(a -> a.getArea() == null ? BigDecimal.ZERO : a.getArea())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long leasedCount = assets.stream()
                .filter(a -> "leased".equals(a.getLeaseControlStatus())
                        || "partial_leased".equals(a.getLeaseControlStatus()))
                .count();
        long vacantCount = assets.stream()
                .filter(a -> "vacant".equals(a.getLeaseControlStatus()))
                .count();
        BigDecimal vacantArea = assets.stream()
                .filter(a -> "vacant".equals(a.getLeaseControlStatus()))
                .map(a -> a.getArea() == null ? BigDecimal.ZERO : a.getArea())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal receivable = bills.stream()
                .map(b -> b.getAmount() == null ? BigDecimal.ZERO : b.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal received = bills.stream()
                .map(b -> b.getPaidAmount() == null ? BigDecimal.ZERO : b.getPaidAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal arrears = receivable.subtract(received);
        BigDecimal collectionRate = receivable.compareTo(BigDecimal.ZERO) > 0
                ? received.divide(receivable, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        double leasedRate = assetTotal == 0 ? 0 : (double) leasedCount / assetTotal;

        Map<String, Object> result = new HashMap<>();
        result.put("assetTotal", assetTotal);
        result.put("totalArea", totalArea);
        result.put("leasedCount", leasedCount);
        result.put("vacantCount", vacantCount);
        result.put("vacantArea", vacantArea);
        result.put("leasedRate", leasedRate);
        result.put("collectionRate", collectionRate);
        result.put("receivable", receivable);
        result.put("received", received);
        result.put("arrears", arrears);
        result.put("contractTotal", contracts.size());
        return result;
    }
}
