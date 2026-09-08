package com.ams.modules.contract.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.service.LeaseControlService;
import com.ams.modules.contract.ContractStatus;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.entity.DepositTransaction;
import com.ams.modules.contract.entity.VacateOrder;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.contract.mapper.DepositTransactionMapper;
import com.ams.modules.contract.mapper.VacateOrderMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退租清场与保证金闭环（FR-VACATE-* / §4.24.3）。
 * 标准流程：退租申请 → 清场验收 → 费用结算 → 保证金退/补 → 合同终止 → 租控空置。
 */
@Service
public class VacateService {

    private final VacateOrderMapper vacateOrderMapper;
    private final ContractMapper contractMapper;
    private final DepositTransactionMapper depositTransactionMapper;
    private final LeaseControlService leaseControlService;

    public VacateService(
            VacateOrderMapper vacateOrderMapper,
            ContractMapper contractMapper,
            DepositTransactionMapper depositTransactionMapper,
            LeaseControlService leaseControlService) {
        this.vacateOrderMapper = vacateOrderMapper;
        this.contractMapper = contractMapper;
        this.depositTransactionMapper = depositTransactionMapper;
        this.leaseControlService = leaseControlService;
    }

    /** 发起退租（FR-VACATE-001）。 */
    @Transactional
    public VacateOrder apply(Long contractId, String reason, java.time.LocalDate expectedVacateDate) {
        Contract contract = contractMapper.selectById(contractId);
        if (contract == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "合同不存在");
        }
        if (!ContractStatus.ACTIVE.equals(contract.getStatus())
                && !ContractStatus.EXPIRING.equals(contract.getStatus())
                && !ContractStatus.RENEWABLE.equals(contract.getStatus())
                && !ContractStatus.EXPIRED.equals(contract.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "当前合同状态不可退租");
        }
        VacateOrder order = new VacateOrder();
        order.setContractId(contractId);
        order.setStatus("applying");
        order.setReason(reason);
        order.setExpectedVacateDate(expectedVacateDate);
        vacateOrderMapper.insert(order);
        return order;
    }

    /** 提交清场验收（工作端 FR-MPW-008）。 */
    @Transactional
    public VacateOrder submitInspection(Long vacateOrderId, BigDecimal waterReading,
            BigDecimal electricReading, String remark) {
        VacateOrder order = require(vacateOrderId);
        order.setStatus("inspecting");
        order.setWaterReading(waterReading);
        order.setElectricReading(electricReading);
        order.setInspectionRemark(remark);
        order.setInspectedBy(SecurityUtils.currentUserIdOrNull());
        order.setInspectedAt(LocalDateTime.now());
        vacateOrderMapper.updateById(order);
        return order;
    }

    /**
     * 退租结算（FR-VACATE-002）：欠租 + 杂费 + 赔偿 − 保证金抵扣 − 预收抵扣。
     * 默认抵扣顺序：损坏赔偿 → 租金欠费 → 杂费欠费 → 保证金 → 预收 → 退还余额。
     */
    @Transactional
    public VacateOrder settle(Long vacateOrderId, BigDecimal damageCompensation) {
        VacateOrder order = require(vacateOrderId);
        Contract contract = contractMapper.selectById(order.getContractId());
        BigDecimal deposit = contract.getDepositAmount() == null ? BigDecimal.ZERO : contract.getDepositAmount();
        BigDecimal prepay = contract.getPrepayAmount() == null ? BigDecimal.ZERO : contract.getPrepayAmount();
        BigDecimal damage = damageCompensation == null ? BigDecimal.ZERO : damageCompensation;

        // 简化：结算金额 = 赔偿（欠费部分由账单域核算），保证金抵扣后余额退还
        BigDecimal depositAfterDamage = deposit.subtract(damage);
        BigDecimal refund = depositAfterDamage.max(BigDecimal.ZERO);

        order.setStatus("settling");
        order.setDamageCompensation(damage);
        order.setSettlementAmount(damage);
        order.setDepositRefund(refund);
        order.setPrepayRefund(prepay);
        order.setSettledAt(LocalDateTime.now());
        order.setStatus("completed");
        vacateOrderMapper.updateById(order);

        // 保证金流水（FR-VACATE-003）：抵扣 + 退还
        if (damage.compareTo(BigDecimal.ZERO) > 0) {
            recordDeposit(contract.getId(), "deduct", damage, "退租赔偿抵扣");
        }
        if (refund.compareTo(BigDecimal.ZERO) > 0) {
            recordDeposit(contract.getId(), "refund", refund, "退租保证金退还");
        }

        // 合同终止 + 租控空置
        contract.setStatus(ContractStatus.TERMINATED);
        contractMapper.updateById(contract);
        leaseControlService.transition(contract.getAssetId(), LeaseControlStatus.VACANT,
                "vacate", order.getId(), "退租完成");

        return order;
    }

    public List<VacateOrder> list(Long contractId) {
        return vacateOrderMapper.selectList(
                new LambdaQueryWrapper<VacateOrder>()
                        .eq(contractId != null, VacateOrder::getContractId, contractId)
                        .orderByDesc(VacateOrder::getId));
    }

    private VacateOrder require(Long id) {
        VacateOrder order = vacateOrderMapper.selectById(id);
        if (order == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "退租单不存在");
        }
        return order;
    }

    private void recordDeposit(Long contractId, String type, BigDecimal amount, String remark) {
        DepositTransaction tx = new DepositTransaction();
        tx.setContractId(contractId);
        tx.setType(type);
        tx.setAmount(amount);
        tx.setRemark(remark);
        tx.setCreatedBy(SecurityUtils.currentUserIdOrNull());
        tx.setCreatedAt(LocalDateTime.now());
        depositTransactionMapper.insert(tx);
    }
}
