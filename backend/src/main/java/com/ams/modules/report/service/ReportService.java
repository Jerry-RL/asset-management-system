package com.ams.modules.report.service;

import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.maintenance.entity.RepairOrder;
import com.ams.modules.maintenance.mapper.RepairOrderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 固定报表（FR-REP-001）：资产/租赁/收费/维修台账。
 */
@Service
public class ReportService {

    private final AssetMapper assetMapper;
    private final ContractMapper contractMapper;
    private final BillMapper billMapper;
    private final RepairOrderMapper repairOrderMapper;

    public ReportService(
            AssetMapper assetMapper,
            ContractMapper contractMapper,
            BillMapper billMapper,
            RepairOrderMapper repairOrderMapper) {
        this.assetMapper = assetMapper;
        this.contractMapper = contractMapper;
        this.billMapper = billMapper;
        this.repairOrderMapper = repairOrderMapper;
    }

    public Map<String, Object> assetLedger(
            Long companyId, Long projectId, String assetType, String leaseControlStatus) {
        LambdaQueryWrapper<Asset> qw = new LambdaQueryWrapper<Asset>()
                .eq(projectId != null, Asset::getProjectId, projectId)
                .eq(hasText(assetType), Asset::getAssetType, assetType)
                .eq(hasText(leaseControlStatus), Asset::getLeaseControlStatus, leaseControlStatus)
                .orderByAsc(Asset::getId);
        // 经营公司 / 产权公司任一匹配即可
        if (companyId != null) {
            qw.and(w -> w.eq(Asset::getOperatingCompanyId, companyId)
                    .or()
                    .eq(Asset::getPropertyCompanyId, companyId));
        }
        List<Asset> assets = assetMapper.selectList(qw);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Asset a : assets) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("assetNo", a.getAssetNo());
            row.put("name", a.getName());
            row.put("assetType", a.getAssetType());
            row.put("area", a.getArea());
            row.put("leaseControlStatus", a.getLeaseControlStatus());
            row.put("projectId", a.getProjectId());
            row.put("address", a.getAddress());
            row.put("baseRentFloor", a.getBaseRentFloor());
            row.put("vacantReason", a.getVacantReason());
            row.put("vacantSince", a.getVacantSince());
            rows.add(row);
        }
        return wrap("asset-ledger", rows);
    }

    public Map<String, Object> leaseLedger(String status) {
        List<Contract> contracts = contractMapper.selectList(
                new LambdaQueryWrapper<Contract>()
                        .eq(hasText(status), Contract::getStatus, status)
                        .orderByDesc(Contract::getId));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Contract c : contracts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("contractNo", c.getContractNo());
            row.put("assetId", c.getAssetId());
            row.put("tenantId", c.getTenantId());
            row.put("status", c.getStatus());
            row.put("startDate", c.getStartDate());
            row.put("endDate", c.getEndDate());
            row.put("rentAmount", c.getRentAmount());
            row.put("depositAmount", c.getDepositAmount());
            row.put("leaseArea", c.getLeaseArea());
            rows.add(row);
        }
        return wrap("lease-ledger", rows);
    }

    public Map<String, Object> collectionLedger(Long contractId, String status, String billType) {
        List<Bill> bills = billMapper.selectList(
                new LambdaQueryWrapper<Bill>()
                        .eq(contractId != null, Bill::getContractId, contractId)
                        .eq(hasText(status), Bill::getStatus, status)
                        .eq(hasText(billType), Bill::getBillType, billType)
                        .orderByDesc(Bill::getDueDate));
        BigDecimal receivable = bills.stream().map(b -> nz(b.getAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal received = bills.stream().map(b -> nz(b.getPaidAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Bill b : bills) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("billNo", b.getBillNo());
            row.put("contractId", b.getContractId());
            row.put("billType", b.getBillType());
            row.put("dueDate", b.getDueDate());
            row.put("amount", b.getAmount());
            row.put("paidAmount", b.getPaidAmount());
            row.put("lateFeeAmount", b.getLateFeeAmount());
            row.put("status", b.getStatus());
            row.put("arrears", nz(b.getAmount()).subtract(nz(b.getPaidAmount()))
                    .add(nz(b.getLateFeeAmount()).subtract(nz(b.getLateFeePaidAmount()))));
            rows.add(row);
        }
        Map<String, Object> result = wrap("collection", rows);
        result.put("receivable", receivable);
        result.put("received", received);
        result.put("arrears", receivable.subtract(received));
        result.put("collectionRate", receivable.compareTo(BigDecimal.ZERO) == 0 ? 0
                : received.divide(receivable, 4, java.math.RoundingMode.HALF_UP));
        return result;
    }

    public Map<String, Object> maintenanceLedger(String status) {
        List<RepairOrder> orders = repairOrderMapper.selectList(
                new LambdaQueryWrapper<RepairOrder>()
                        .eq(hasText(status), RepairOrder::getStatus, status)
                        .orderByDesc(RepairOrder::getId));
        List<Map<String, Object>> rows = orders.stream().map(o -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", o.getId());
            row.put("assetId", o.getAssetId());
            row.put("status", o.getStatus());
            row.put("description", o.getDescription());
            row.put("vendorId", o.getVendorId());
            row.put("assigneeId", o.getAssigneeId());
            row.put("completedAt", o.getCompletedAt());
            row.put("createdAt", o.getCreatedAt());
            return row;
        }).collect(Collectors.toList());
        return wrap("maintenance", rows);
    }

    public String toCsv(Map<String, Object> report) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) report.get("rows");
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        List<String> headers = new ArrayList<>(rows.get(0).keySet());
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", headers)).append('\n');
        for (Map<String, Object> row : rows) {
            List<String> cols = new ArrayList<>();
            for (String h : headers) {
                Object v = row.get(h);
                String s = v == null ? "" : v.toString().replace(",", " ");
                cols.add(s);
            }
            sb.append(String.join(",", cols)).append('\n');
        }
        return sb.toString();
    }

    private Map<String, Object> wrap(String type, List<Map<String, Object>> rows) {
        Map<String, Object> result = new HashMap<>();
        result.put("reportType", type);
        result.put("total", rows.size());
        result.put("rows", rows);
        return result;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
