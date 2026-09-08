package com.ams.modules.dashboard.service;

import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.billing.mapper.PaymentMapper;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 经营看板（FR-DASH-001）+ 集团合并看板（FR-DASH-002）。
 * 指标：资产规模、出租率、收缴率、空置面积、在租面积、欠费金额等。
 */
@Service
public class DashboardService {

    private final AssetMapper assetMapper;
    private final ContractMapper contractMapper;
    private final BillMapper billMapper;
    private final PaymentMapper paymentMapper;

    public DashboardService(
            AssetMapper assetMapper,
            ContractMapper contractMapper,
            BillMapper billMapper,
            PaymentMapper paymentMapper) {
        this.assetMapper = assetMapper;
        this.contractMapper = contractMapper;
        this.billMapper = billMapper;
        this.paymentMapper = paymentMapper;
    }

    /** 经营看板指标（FR-DASH-001）。 */
    public Map<String, Object> operations(Long companyId) {
        List<Asset> assets = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(companyId != null, Asset::getOperatingCompanyId, companyId));
        List<Contract> contracts = contractMapper.selectList(null);
        List<Bill> bills = billMapper.selectList(null);

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

        BigDecimal receivable = bills.stream().map(Bill::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal received = bills.stream().map(Bill::getPaidAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal arrears = receivable.subtract(received);
        BigDecimal collectionRate = receivable.compareTo(BigDecimal.ZERO) > 0
                ? received.divide(receivable, 4, java.math.RoundingMode.HALF_UP)
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

    /** 集团合并看板（FR-DASH-002）：跨公司汇总核心指标。 */
    public Map<String, Object> consolidate() {
        List<Asset> assets = assetMapper.selectList(null);
        long assetTotal = assets.size();
        long leasedCount = assets.stream()
                .filter(a -> "leased".equals(a.getLeaseControlStatus())
                        || "partial_leased".equals(a.getLeaseControlStatus()))
                .count();
        BigDecimal vacantArea = assets.stream()
                .filter(a -> "vacant".equals(a.getLeaseControlStatus()))
                .map(a -> a.getArea() == null ? BigDecimal.ZERO : a.getArea())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new HashMap<>();
        result.put("groupAssetTotal", assetTotal);
        result.put("groupLeasedRate", assetTotal == 0 ? 0 : (double) leasedCount / assetTotal);
        result.put("groupVacantArea", vacantArea);
        return result;
    }
}
