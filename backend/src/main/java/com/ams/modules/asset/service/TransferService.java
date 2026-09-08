package com.ams.modules.asset.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.AssetTransfer;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.AssetTransferMapper;
import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.billing.service.PrepayService;
import com.ams.modules.contract.ContractStatus;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资产调拨（FR-CERT-002）：跨公司调拨、带租迁移、交接清单、双方留痕。
 */
@Service
public class TransferService {

    private final AssetTransferMapper transferMapper;
    private final AssetMapper assetMapper;
    private final CertificateService certificateService;
    private final ContractMapper contractMapper;
    private final BillMapper billMapper;
    private final PrepayService prepayService;
    private final ObjectMapper objectMapper;

    public TransferService(
            AssetTransferMapper transferMapper,
            AssetMapper assetMapper,
            CertificateService certificateService,
            ContractMapper contractMapper,
            BillMapper billMapper,
            PrepayService prepayService,
            ObjectMapper objectMapper) {
        this.transferMapper = transferMapper;
        this.assetMapper = assetMapper;
        this.certificateService = certificateService;
        this.contractMapper = contractMapper;
        this.billMapper = billMapper;
        this.prepayService = prepayService;
        this.objectMapper = objectMapper;
    }

    public AssetTransfer create(AssetTransfer transfer) {
        transfer.setStatus("draft");
        transferMapper.insert(transfer);
        return transfer;
    }

    public List<AssetTransfer> list(Long assetId) {
        return transferMapper.selectList(
                new LambdaQueryWrapper<AssetTransfer>()
                        .eq(assetId != null, AssetTransfer::getAssetId, assetId)
                        .orderByDesc(AssetTransfer::getId));
    }

    public AssetTransfer get(Long id) {
        AssetTransfer transfer = transferMapper.selectById(id);
        if (transfer == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return transfer;
    }

    /**
     * 调拨审批通过后执行迁移（DSD §4.16）：
     * - 空置资产：直接迁移经营公司。
     * - 在租资产：仅允许「带租调拨」；生成交接清单（合同/欠费/保证金/预收）。
     */
    @Transactional
    public AssetTransfer approve(Long id) {
        AssetTransfer transfer = transferMapper.selectById(id);
        if (transfer == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        if ("completed".equals(transfer.getStatus())) {
            return transfer; // 幂等
        }
        Asset asset = assetMapper.selectById(transfer.getAssetId());
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        certificateService.assertNotMortgaged(transfer.getAssetId());
        String status = asset.getLeaseControlStatus();
        boolean leased = "leased".equals(status) || "partial_leased".equals(status);
        if (leased && !"with_contract".equals(transfer.getTransferType())) {
            throw new AppException(ErrorCode.BUSINESS_ERROR,
                    "在租资产须选择「带租调拨」，或先退租/解除占用");
        }

        Map<String, Object> handover = buildHandover(asset, transfer);
        try {
            transfer.setHandoverJson(objectMapper.writeValueAsString(handover));
        } catch (Exception ex) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "生成交接清单失败");
        }

        // 迁移经营公司（合同随资产经营主体，欠费/保证金/预收留在合同上随迁）
        Long from = asset.getOperatingCompanyId();
        if (transfer.getFromCompanyId() == null) {
            transfer.setFromCompanyId(from);
        }
        asset.setOperatingCompanyId(transfer.getToCompanyId());
        assetMapper.updateById(asset);

        transfer.setStatus("completed");
        transferMapper.updateById(transfer);
        return transfer;
    }

    private Map<String, Object> buildHandover(Asset asset, AssetTransfer transfer) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("assetId", asset.getId());
        root.put("assetNo", asset.getAssetNo());
        root.put("fromCompanyId", transfer.getFromCompanyId() != null
                ? transfer.getFromCompanyId() : asset.getOperatingCompanyId());
        root.put("toCompanyId", transfer.getToCompanyId());
        root.put("transferType", transfer.getTransferType());

        List<Contract> contracts = contractMapper.selectList(
                new LambdaQueryWrapper<Contract>()
                        .eq(Contract::getAssetId, asset.getId())
                        .in(Contract::getStatus,
                                ContractStatus.ACTIVE, ContractStatus.EXPIRING,
                                ContractStatus.RENEWABLE, ContractStatus.EXPIRED));
        List<Map<String, Object>> contractRows = new ArrayList<>();
        BigDecimal totalArrears = BigDecimal.ZERO;
        BigDecimal totalDeposit = BigDecimal.ZERO;
        BigDecimal totalPrepay = BigDecimal.ZERO;
        for (Contract c : contracts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("contractId", c.getId());
            row.put("contractNo", c.getContractNo());
            row.put("tenantId", c.getTenantId());
            row.put("status", c.getStatus());
            row.put("depositAmount", c.getDepositAmount());
            BigDecimal prepayBal = prepayService.totalBalance(c.getId());
            row.put("prepayBalance", prepayBal);
            totalDeposit = totalDeposit.add(c.getDepositAmount() == null ? BigDecimal.ZERO : c.getDepositAmount());
            totalPrepay = totalPrepay.add(prepayBal);

            List<Bill> bills = billMapper.selectList(
                    new LambdaQueryWrapper<Bill>()
                            .eq(Bill::getContractId, c.getId())
                            .in(Bill::getStatus, BillStatus.UNPAID, BillStatus.PARTIAL_PAID));
            BigDecimal arrears = BigDecimal.ZERO;
            for (Bill b : bills) {
                BigDecimal due = nz(b.getAmount()).subtract(nz(b.getPaidAmount())).subtract(nz(b.getReducedAmount()));
                BigDecimal late = nz(b.getLateFeeAmount()).subtract(nz(b.getLateFeePaidAmount()));
                arrears = arrears.add(due.max(BigDecimal.ZERO)).add(late.max(BigDecimal.ZERO));
            }
            row.put("arrears", arrears);
            totalArrears = totalArrears.add(arrears);
            contractRows.add(row);
        }
        root.put("contracts", contractRows);
        root.put("totalArrears", totalArrears);
        root.put("totalDeposit", totalDeposit);
        root.put("totalPrepay", totalPrepay);
        return root;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
