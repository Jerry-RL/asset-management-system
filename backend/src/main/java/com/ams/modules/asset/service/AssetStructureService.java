package com.ams.modules.asset.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.AssetCodeMapping;
import com.ams.modules.asset.entity.AssetStructureLog;
import com.ams.modules.asset.mapper.AssetCodeMappingMapper;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.AssetStructureLogMapper;
import com.ams.modules.contract.ContractStatus;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资产主数据拆分合并（FR-MDM-001~004）。
 */
@Service
public class AssetStructureService {

    private final AssetMapper assetMapper;
    private final AssetStructureLogMapper structureLogMapper;
    private final AssetCodeMappingMapper codeMappingMapper;
    private final ContractMapper contractMapper;
    private final CertificateService certificateService;
    private final AssetQrService assetQrService;
    private final ObjectMapper objectMapper;

    public AssetStructureService(
            AssetMapper assetMapper,
            AssetStructureLogMapper structureLogMapper,
            AssetCodeMappingMapper codeMappingMapper,
            ContractMapper contractMapper,
            CertificateService certificateService,
            AssetQrService assetQrService,
            ObjectMapper objectMapper) {
        this.assetMapper = assetMapper;
        this.structureLogMapper = structureLogMapper;
        this.codeMappingMapper = codeMappingMapper;
        this.contractMapper = contractMapper;
        this.certificateService = certificateService;
        this.assetQrService = assetQrService;
        this.objectMapper = objectMapper;
    }

    /**
     * 拆分：原资产冻结；子资产继承权属。
     * contractStrategy=attach_parent：在租合同挂在父资产；=block：有在租则拒绝。
     */
    @Transactional
    public Map<String, Object> split(Long parentId, List<BigDecimal> childAreas, String contractStrategy, String remark) {
        Asset parent = requireActive(parentId);
        certificateService.assertNotMortgaged(parentId);
        if (childAreas == null || childAreas.size() < 2) {
            throw new AppException(ErrorCode.BAD_REQUEST, "至少拆分为 2 个子资产");
        }
        BigDecimal sum = childAreas.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (parent.getArea() != null && sum.subtract(parent.getArea()).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "子面积之和须等于原资产面积");
        }

        boolean leased = LeaseControlStatus.LEASED.equals(parent.getLeaseControlStatus())
                || LeaseControlStatus.PARTIAL_LEASED.equals(parent.getLeaseControlStatus());
        String strategy = contractStrategy == null ? "block" : contractStrategy;
        if (leased && !"attach_parent".equals(strategy)) {
            throw new AppException(ErrorCode.CONFLICT, "在租资产拆分须先处理合同，或指定 contractStrategy=attach_parent");
        }
        if (!leased && !LeaseControlStatus.VACANT.equals(parent.getLeaseControlStatus())
                && !LeaseControlStatus.LEASING.equals(parent.getLeaseControlStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "仅空置/招租中/在租(挂接父合同)资产可拆分");
        }

        Long rootId = parent.getRootAssetId() == null ? parent.getId() : parent.getRootAssetId();
        List<Asset> children = new ArrayList<>();
        List<Map<String, Object>> mapping = new ArrayList<>();
        int idx = 1;
        for (BigDecimal area : childAreas) {
            if (area == null || area.compareTo(BigDecimal.ZERO) <= 0) {
                throw new AppException(ErrorCode.BAD_REQUEST, "子面积须大于0");
            }
            Asset child = cloneAsset(parent);
            child.setId(null);
            child.setParentAssetId(parent.getId());
            child.setRootAssetId(rootId);
            child.setArea(area.setScale(2, RoundingMode.HALF_UP));
            child.setAssetNo(nextChildNo(parent.getAssetNo(), idx++));
            child.setName(parent.getName() + "-拆分" + (idx - 1));
            child.setStructureStatus("active");
            child.setOldAssetNo(parent.getAssetNo());
            child.setLeaseControlStatus(LeaseControlStatus.VACANT);
            child.setVersion(0);
            assetMapper.insert(child);
            assetQrService.ensureQrCode(child);
            children.add(child);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("childAssetId", child.getId());
            row.put("childAssetNo", child.getAssetNo());
            row.put("area", child.getArea());
            mapping.add(row);

            saveCodeMapping(parent.getAssetNo(), child, "split", null);
        }

        parent.setStructureStatus("frozen");
        parent.setRootAssetId(rootId);
        // 挂接父合同时父资产保持在租；否则冻结后保持空置
        if (!leased) {
            parent.setLeaseControlStatus(LeaseControlStatus.VACANT);
        }
        assetMapper.updateById(parent);

        AssetStructureLog log = writeLog("split", List.of(parent.getId()),
                children.stream().map(Asset::getId).toList(), mapping, remark);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("parent", parent);
        result.put("children", children);
        result.put("structureLogId", log.getId());
        result.put("contractStrategy", strategy);
        if (leased) {
            long contracts = contractMapper.selectCount(
                    new LambdaQueryWrapper<Contract>()
                            .eq(Contract::getAssetId, parentId)
                            .in(Contract::getStatus, ContractStatus.ACTIVE, ContractStatus.EXPIRING,
                                    ContractStatus.RENEWABLE));
            result.put("attachedContracts", contracts);
        }
        return result;
    }

    /** 合并：仅空置 active 资产可合并。 */
    @Transactional
    public Map<String, Object> merge(List<Long> sourceIds, String newName, String remark) {
        if (sourceIds == null || sourceIds.size() < 2) {
            throw new AppException(ErrorCode.BAD_REQUEST, "至少合并 2 个资产");
        }
        List<Asset> sources = new ArrayList<>();
        BigDecimal totalArea = BigDecimal.ZERO;
        Asset first = null;
        for (Long id : sourceIds) {
            Asset a = requireActive(id);
            certificateService.assertNotMortgaged(id);
            if (!LeaseControlStatus.VACANT.equals(a.getLeaseControlStatus())) {
                throw new AppException(ErrorCode.CONFLICT, "仅空置资产可合并: " + a.getAssetNo());
            }
            sources.add(a);
            totalArea = totalArea.add(a.getArea() == null ? BigDecimal.ZERO : a.getArea());
            if (first == null) {
                first = a;
            }
        }

        Asset merged = cloneAsset(first);
        merged.setId(null);
        merged.setParentAssetId(null);
        merged.setRootAssetId(first.getRootAssetId() == null ? first.getId() : first.getRootAssetId());
        merged.setArea(totalArea);
        merged.setAssetNo("MG" + System.currentTimeMillis());
        merged.setName(newName == null || newName.isBlank() ? first.getName() + "-合并" : newName);
        merged.setStructureStatus("active");
        merged.setLeaseControlStatus(LeaseControlStatus.VACANT);
        merged.setOldAssetNo(first.getAssetNo());
        merged.setVersion(0);
        assetMapper.insert(merged);
        assetQrService.ensureQrCode(merged);

        List<Map<String, Object>> mapping = new ArrayList<>();
        for (Asset src : sources) {
            src.setStructureStatus("merged_out");
            src.setParentAssetId(merged.getId());
            assetMapper.updateById(src);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceAssetId", src.getId());
            row.put("sourceAssetNo", src.getAssetNo());
            row.put("targetAssetId", merged.getId());
            mapping.add(row);
            saveCodeMapping(src.getAssetNo(), merged, "merge", null);
        }

        AssetStructureLog log = writeLog("merge", sourceIds, List.of(merged.getId()), mapping, remark);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("merged", merged);
        result.put("sources", sources);
        result.put("structureLogId", log.getId());
        return result;
    }

    /** 结构树：从根向下。 */
    public Map<String, Object> structureTree(Long assetId) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        Long rootId = asset.getRootAssetId() != null ? asset.getRootAssetId()
                : (asset.getParentAssetId() == null ? asset.getId() : findRoot(asset));
        Asset root = assetMapper.selectById(rootId);
        if (root == null) {
            root = asset;
            rootId = asset.getId();
        }
        return buildNode(root);
    }

    public List<AssetCodeMapping> codeMappings(String oldAssetNo) {
        return codeMappingMapper.selectList(
                new LambdaQueryWrapper<AssetCodeMapping>()
                        .eq(oldAssetNo != null, AssetCodeMapping::getOldAssetNo, oldAssetNo)
                        .orderByDesc(AssetCodeMapping::getId));
    }

    public List<AssetStructureLog> listLogs(int limit) {
        return structureLogMapper.selectList(
                new LambdaQueryWrapper<AssetStructureLog>()
                        .orderByDesc(AssetStructureLog::getId)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    private Map<String, Object> buildNode(Asset asset) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("assetId", asset.getId());
        node.put("assetNo", asset.getAssetNo());
        node.put("name", asset.getName());
        node.put("area", asset.getArea());
        node.put("structureStatus", asset.getStructureStatus());
        node.put("leaseControlStatus", asset.getLeaseControlStatus());
        List<Asset> children = assetMapper.selectList(
                new LambdaQueryWrapper<Asset>()
                        .eq(Asset::getParentAssetId, asset.getId())
                        .ne(Asset::getStructureStatus, "merged_out")
                        .orderByAsc(Asset::getId));
        // 合并结果的子：parent 指向 merged 的是 merged_out sources，树用拆分 children
        List<Asset> splitChildren = assetMapper.selectList(
                new LambdaQueryWrapper<Asset>()
                        .eq(Asset::getParentAssetId, asset.getId())
                        .eq(Asset::getStructureStatus, "active")
                        .orderByAsc(Asset::getId));
        List<Map<String, Object>> childNodes = new ArrayList<>();
        for (Asset c : splitChildren) {
            childNodes.add(buildNode(c));
        }
        node.put("children", childNodes);
        return node;
    }

    private Long findRoot(Asset asset) {
        Asset cur = asset;
        int guard = 0;
        while (cur.getParentAssetId() != null && guard++ < 50) {
            if (cur.getRootAssetId() != null) {
                return cur.getRootAssetId();
            }
            Asset p = assetMapper.selectById(cur.getParentAssetId());
            if (p == null) {
                break;
            }
            cur = p;
        }
        return cur.getId();
    }

    private Asset requireActive(Long id) {
        Asset asset = assetMapper.selectById(id);
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        if ("frozen".equals(asset.getStructureStatus()) || "merged_out".equals(asset.getStructureStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "资产结构状态不可用: " + asset.getStructureStatus());
        }
        return asset;
    }

    private Asset cloneAsset(Asset src) {
        Asset a = new Asset();
        a.setProjectId(src.getProjectId());
        a.setAssetType(src.getAssetType());
        a.setSourceType(src.getSourceType());
        a.setOwnershipType(src.getOwnershipType());
        a.setPropertyCompanyId(src.getPropertyCompanyId());
        a.setOperatingCompanyId(src.getOperatingCompanyId());
        a.setBaseRentAssessed(src.getBaseRentAssessed());
        a.setBaseRentFloor(src.getBaseRentFloor());
        a.setMarketRefRent(src.getMarketRefRent());
        a.setProvince(src.getProvince());
        a.setCity(src.getCity());
        a.setDistrict(src.getDistrict());
        a.setAddress(src.getAddress());
        a.setStructureType(src.getStructureType());
        a.setUsageType(src.getUsageType());
        a.setOriginalValue(src.getOriginalValue());
        a.setLongitude(src.getLongitude());
        a.setLatitude(src.getLatitude());
        return a;
    }

    private String nextChildNo(String parentNo, int idx) {
        String base = parentNo == null ? "A" : parentNo;
        return base + "-S" + idx + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }

    private void saveCodeMapping(String oldNo, Asset neu, String opType, Long logId) {
        AssetCodeMapping m = new AssetCodeMapping();
        m.setOldAssetNo(oldNo);
        m.setNewAssetId(neu.getId());
        m.setNewAssetNo(neu.getAssetNo());
        m.setOpType(opType);
        m.setStructureLogId(logId);
        m.setCreatedAt(LocalDateTime.now());
        codeMappingMapper.insert(m);
    }

    private AssetStructureLog writeLog(
            String opType, List<Long> sources, List<Long> results, Object mapping, String remark) {
        AssetStructureLog log = new AssetStructureLog();
        log.setOpType(opType);
        log.setSourceAssetIds(sources.toString());
        log.setResultAssetIds(results.toString());
        try {
            log.setMappingJson(objectMapper.writeValueAsString(mapping));
        } catch (Exception e) {
            log.setMappingJson(String.valueOf(mapping));
        }
        log.setRemark(remark);
        log.setOperatorId(SecurityUtils.currentUserIdOrNull());
        log.setCreatedAt(LocalDateTime.now());
        structureLogMapper.insert(log);
        return log;
    }
}
