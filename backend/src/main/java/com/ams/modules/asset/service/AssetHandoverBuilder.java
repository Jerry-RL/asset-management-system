package com.ams.modules.asset.service;

import com.ams.modules.asset.entity.Asset;
import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.billing.service.PrepayService;
import com.ams.modules.contract.ContractStatus;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 资产交接清单快照（欠费 / 保证金 / 预收 / 在租合同）。
 *
 * <p>资产**换归属公司**时双方要对的那几张账，都长在这一个结构里：合同清单、每份合同的
 * 预收余额、未收账单（本金 + 未收滞纳金）、以及三个合计。调拨（{@code TransferService}）
 * 与权属流转（{@code OwnershipTransferService}）共用同一份口径 —— 复制第二份必然漂移。
 *
 * <p><b>键顺序是契约的一部分</b>：快照以 JSON 文本存进 {@code *_json} 列，键顺序变了历史快照
 * 与新快照就长得不一样。故 {@code extraRootFields} 在 {@code toCompanyId} 之后、{@code contracts}
 * 之前插入，让调用方特有的键（调拨的 {@code transferType}、权属流转的
 * {@code transferScope/transferMode/direction}）落在固定位置。
 *
 * <p><b>刻意不在此处迁移任何数据</b>：本类只做「读现状 → 生成快照」。账单归属、数据范围的
 * 迁移不在范围内（设计 §3.2），SRS 里调拨的「归属同步迁移」目前也没有实现。
 */
@Service
public class AssetHandoverBuilder {

    private final ContractMapper contractMapper;
    private final BillMapper billMapper;
    private final PrepayService prepayService;

    public AssetHandoverBuilder(
            ContractMapper contractMapper, BillMapper billMapper, PrepayService prepayService) {
        this.contractMapper = contractMapper;
        this.billMapper = billMapper;
        this.prepayService = prepayService;
    }

    /**
     * @param fromCompanyId   转出方（为 null 时回落该资产当前的经营公司）
     * @param toCompanyId     转入方
     * @param extraRootFields 调用方特有的根字段，插在 {@code toCompanyId} 之后。
     *                        传 {@code null} 表示没有，等价于空 Map。
     */
    public Map<String, Object> build(Asset asset, Long fromCompanyId, Long toCompanyId,
            Map<String, Object> extraRootFields) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("assetId", asset.getId());
        root.put("assetNo", asset.getAssetNo());
        root.put("fromCompanyId", fromCompanyId != null ? fromCompanyId : asset.getOperatingCompanyId());
        root.put("toCompanyId", toCompanyId);
        if (extraRootFields != null) {
            root.putAll(extraRootFields);
        }

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
