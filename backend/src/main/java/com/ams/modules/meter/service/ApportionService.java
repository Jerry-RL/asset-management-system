package com.ams.modules.meter.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.contract.ContractStatus;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.meter.entity.Meter;
import com.ams.modules.meter.entity.UtilityBill;
import com.ams.modules.meter.mapper.MeterMapper;
import com.ams.modules.meter.mapper.UtilityBillMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公摊分摊引擎（FR-UTIL-006）：按面积/表计/户数/用量分摊共享费用。
 */
@Service
public class ApportionService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbcTemplate;
    private final AssetMapper assetMapper;
    private final ContractMapper contractMapper;
    private final MeterMapper meterMapper;
    private final BillMapper billMapper;
    private final UtilityBillMapper utilityBillMapper;

    public ApportionService(
            JdbcTemplate jdbcTemplate,
            AssetMapper assetMapper,
            ContractMapper contractMapper,
            MeterMapper meterMapper,
            BillMapper billMapper,
            UtilityBillMapper utilityBillMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.assetMapper = assetMapper;
        this.contractMapper = contractMapper;
        this.meterMapper = meterMapper;
        this.billMapper = billMapper;
        this.utilityBillMapper = utilityBillMapper;
    }

    public String resolveBasis(Long projectId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT apportion_basis FROM apportion_config WHERE enabled = TRUE"
                            + " AND (project_id = ? OR project_id IS NULL) ORDER BY project_id NULLS LAST LIMIT 1",
                    projectId);
            if (!rows.isEmpty() && rows.get(0).get("apportion_basis") != null) {
                return rows.get(0).get("apportion_basis").toString();
            }
        } catch (Exception ignored) {
            // table may miss columns
        }
        return "area";
    }

    /**
     * 将共享费用分摊到项目在租合同，生成 utility 账单；Σ 分摊额 ≤ totalCost。
     */
    @Transactional
    public Map<String, Object> computeApportion(Long projectId, BigDecimal totalCost, String basisOverride) {
        if (projectId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "projectId 必填");
        }
        if (totalCost == null || totalCost.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "分摊金额须大于 0");
        }
        String basis = basisOverride == null || basisOverride.isBlank()
                ? resolveBasis(projectId) : basisOverride;

        List<Contract> contracts = activeContractsByProject(projectId);
        if (contracts.isEmpty()) {
            throw new AppException(ErrorCode.CONFLICT, "项目下无在租合同可分摊");
        }

        Map<Long, BigDecimal> weights = new LinkedHashMap<>();
        BigDecimal weightSum = BigDecimal.ZERO;
        for (Contract c : contracts) {
            BigDecimal w = weightOf(c, basis);
            weights.put(c.getId(), w);
            weightSum = weightSum.add(w);
        }
        if (weightSum.compareTo(BigDecimal.ZERO) <= 0) {
            // 回退等额
            for (Contract c : contracts) {
                weights.put(c.getId(), BigDecimal.ONE);
            }
            weightSum = BigDecimal.valueOf(contracts.size());
            basis = "head";
        }

        List<Map<String, Object>> allocations = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < contracts.size(); i++) {
            Contract c = contracts.get(i);
            BigDecimal share;
            if (i == contracts.size() - 1) {
                share = totalCost.subtract(allocated).max(BigDecimal.ZERO);
            } else {
                share = totalCost.multiply(weights.get(c.getId()))
                        .divide(weightSum, 2, RoundingMode.HALF_UP);
                allocated = allocated.add(share);
            }
            Bill bill = createApportionBill(c, share, basis);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("contractId", c.getId());
            row.put("assetId", c.getAssetId());
            row.put("weight", weights.get(c.getId()));
            row.put("amount", share);
            row.put("billId", bill.getId());
            allocations.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId);
        result.put("basis", basis);
        result.put("totalCost", totalCost);
        result.put("allocated", allocations.stream()
                .map(a -> (BigDecimal) a.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        result.put("items", allocations);
        return result;
    }

    /** 退租时对合同所属项目做一次等额最小公摊结清（无共享费用时跳过）。 */
    @Transactional
    public BigDecimal settleMultiMeter(Long contractId, BigDecimal sharedCost) {
        if (sharedCost == null || sharedCost.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        Contract contract = contractMapper.selectById(contractId);
        if (contract == null || contract.getAssetId() == null) {
            return BigDecimal.ZERO;
        }
        Asset asset = assetMapper.selectById(contract.getAssetId());
        if (asset == null || asset.getProjectId() == null) {
            // 单合同直接出账
            createApportionBill(contract, sharedCost, "vacate");
            return sharedCost;
        }
        Map<String, Object> r = computeApportion(asset.getProjectId(), sharedCost, null);
        return (BigDecimal) r.get("allocated");
    }

    private List<Contract> activeContractsByProject(Long projectId) {
        List<Asset> assets = assetMapper.selectList(
                new LambdaQueryWrapper<Asset>().eq(Asset::getProjectId, projectId));
        List<Long> assetIds = assets.stream().map(Asset::getId).toList();
        if (assetIds.isEmpty()) {
            return List.of();
        }
        return contractMapper.selectList(
                new LambdaQueryWrapper<Contract>()
                        .in(Contract::getAssetId, assetIds)
                        .in(Contract::getStatus,
                                ContractStatus.ACTIVE, ContractStatus.EXPIRING, ContractStatus.RENEWABLE));
    }

    private BigDecimal weightOf(Contract contract, String basis) {
        return switch (basis == null ? "area" : basis) {
            case "meter" -> {
                Long cnt = meterMapper.selectCount(
                        new LambdaQueryWrapper<Meter>()
                                .eq(Meter::getContractId, contract.getId())
                                .eq(Meter::getStatus, 1));
                yield BigDecimal.valueOf(cnt == null ? 0 : cnt);
            }
            case "usage" -> {
                List<UtilityBill> ubs = utilityBillMapper.selectList(
                        new LambdaQueryWrapper<UtilityBill>()
                                .eq(UtilityBill::getContractId, contract.getId())
                                .ge(UtilityBill::getCreatedAt, LocalDate.now().withDayOfMonth(1).atStartOfDay()));
                BigDecimal u = ubs.stream()
                        .map(b -> b.getUsage() == null ? BigDecimal.ZERO : b.getUsage())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                yield u;
            }
            case "head" -> BigDecimal.ONE;
            default -> {
                Asset asset = assetMapper.selectById(contract.getAssetId());
                BigDecimal area = asset == null || asset.getArea() == null ? BigDecimal.ZERO : asset.getArea();
                if (contract.getLeaseArea() != null && contract.getLeaseArea().compareTo(BigDecimal.ZERO) > 0) {
                    area = contract.getLeaseArea();
                }
                yield area;
            }
        };
    }

    private Bill createApportionBill(Contract contract, BigDecimal amount, String basis) {
        Bill bill = new Bill();
        bill.setBillNo("GT" + LocalDate.now().format(NO_FMT)
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        bill.setContractId(contract.getId());
        bill.setAssetId(contract.getAssetId());
        bill.setTenantId(contract.getTenantId());
        bill.setBillType("utility");
        bill.setPeriodStart(LocalDate.now().withDayOfMonth(1));
        bill.setPeriodEnd(LocalDate.now());
        bill.setDueDate(LocalDate.now().plusDays(7));
        bill.setAmount(amount);
        bill.setPaidAmount(BigDecimal.ZERO);
        bill.setReducedAmount(BigDecimal.ZERO);
        bill.setLateFeeAmount(BigDecimal.ZERO);
        bill.setLateFeePaidAmount(BigDecimal.ZERO);
        bill.setStatus(BillStatus.UNPAID);
        bill.setDunningLevel(0);
        bill.setSource("system");
        bill.setRemark("apportion:" + basis);
        billMapper.insert(bill);

        UtilityBill ub = new UtilityBill();
        ub.setBillId(bill.getId());
        ub.setContractId(contract.getId());
        ub.setUsage(BigDecimal.ZERO);
        ub.setUnitPrice(BigDecimal.ZERO);
        ub.setApportionAmount(amount);
        ub.setAmount(amount);
        ub.setCreatedAt(LocalDateTime.now());
        utilityBillMapper.insert(ub);
        return bill;
    }
}
