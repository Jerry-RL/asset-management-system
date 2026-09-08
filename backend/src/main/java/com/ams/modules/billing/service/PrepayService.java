package com.ams.modules.billing.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.billing.entity.Prepay;
import com.ams.modules.billing.mapper.PrepayMapper;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预收预交（FR-PREPAY-*）：预交登记、余额台账、流水对账、按账单自动抵扣。
 */
@Service
public class PrepayService {

    private final PrepayMapper prepayMapper;
    private final ContractMapper contractMapper;

    public PrepayService(PrepayMapper prepayMapper, ContractMapper contractMapper) {
        this.prepayMapper = prepayMapper;
        this.contractMapper = contractMapper;
    }

    @Transactional
    public Prepay add(Long contractId, Long tenantId, BigDecimal amount, String remark) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        Prepay prepay = new Prepay();
        prepay.setContractId(contractId);
        prepay.setTenantId(tenantId);
        prepay.setAmount(amount);
        prepay.setUsedAmount(BigDecimal.ZERO);
        prepay.setBalance(amount);
        prepay.setRemark(remark);
        prepayMapper.insert(prepay);

        // 合同预收余额累计
        if (contractId != null) {
            Contract contract = contractMapper.selectById(contractId);
            if (contract != null) {
                contract.setPrepayAmount((contract.getPrepayAmount() == null
                        ? BigDecimal.ZERO : contract.getPrepayAmount()).add(amount));
                contractMapper.updateById(contract);
            }
        }
        return prepay;
    }

    public List<Prepay> listByContract(Long contractId) {
        return prepayMapper.selectList(
                new LambdaQueryWrapper<Prepay>()
                        .eq(Prepay::getContractId, contractId)
                        .orderByDesc(Prepay::getId));
    }

    public BigDecimal totalBalance(Long contractId) {
        List<Prepay> list = listByContract(contractId);
        return list.stream().map(p -> p.getBalance() == null ? BigDecimal.ZERO : p.getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 预收资金池对账单（FR-PREPAY-003）：入账、已用、余额及明细。
     */
    public Map<String, Object> statement(Long contractId) {
        List<Prepay> entries = listByContract(contractId);
        BigDecimal totalIn = BigDecimal.ZERO;
        BigDecimal totalUsed = BigDecimal.ZERO;
        BigDecimal totalBal = BigDecimal.ZERO;
        for (Prepay p : entries) {
            totalIn = totalIn.add(p.getAmount() == null ? BigDecimal.ZERO : p.getAmount());
            totalUsed = totalUsed.add(p.getUsedAmount() == null ? BigDecimal.ZERO : p.getUsedAmount());
            totalBal = totalBal.add(p.getBalance() == null ? BigDecimal.ZERO : p.getBalance());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractId", contractId);
        result.put("totalIn", totalIn);
        result.put("totalUsed", totalUsed);
        result.put("balance", totalBal);
        result.put("entries", entries);
        return result;
    }

    /**
     * 按 FIFO 消耗预收余额，返回实际抵扣金额。
     */
    @Transactional
    public BigDecimal consume(Long contractId, BigDecimal amount, String remark) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0 || contractId == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal remain = amount;
        BigDecimal consumed = BigDecimal.ZERO;
        List<Prepay> list = prepayMapper.selectList(
                new LambdaQueryWrapper<Prepay>()
                        .eq(Prepay::getContractId, contractId)
                        .gt(Prepay::getBalance, BigDecimal.ZERO)
                        .orderByAsc(Prepay::getId));
        for (Prepay prepay : list) {
            if (remain.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal bal = prepay.getBalance() == null ? BigDecimal.ZERO : prepay.getBalance();
            BigDecimal use = remain.min(bal);
            if (use.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            prepay.setUsedAmount((prepay.getUsedAmount() == null ? BigDecimal.ZERO : prepay.getUsedAmount()).add(use));
            prepay.setBalance(bal.subtract(use));
            if (remark != null && !remark.isBlank()) {
                prepay.setRemark((prepay.getRemark() == null ? "" : prepay.getRemark() + ";") + remark);
            }
            prepayMapper.updateById(prepay);
            remain = remain.subtract(use);
            consumed = consumed.add(use);
        }
        if (consumed.compareTo(BigDecimal.ZERO) > 0) {
            Contract contract = contractMapper.selectById(contractId);
            if (contract != null) {
                BigDecimal current = contract.getPrepayAmount() == null ? BigDecimal.ZERO : contract.getPrepayAmount();
                contract.setPrepayAmount(current.subtract(consumed).max(BigDecimal.ZERO));
                contractMapper.updateById(contract);
            }
        }
        return consumed;
    }
}
