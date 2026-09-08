package com.ams.modules.billing.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.billing.entity.Prepay;
import com.ams.modules.billing.mapper.PrepayMapper;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预收预交（FR-PREPAY-*）：预交登记、余额台账、按账单自动抵扣。
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
        return list.stream().map(Prepay::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
