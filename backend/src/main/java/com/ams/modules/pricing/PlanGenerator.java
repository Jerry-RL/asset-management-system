package com.ams.modules.pricing;

import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.pricing.entity.PaymentPlan;
import com.ams.modules.pricing.mapper.PaymentPlanMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 缴费计划生成器（DSD §4.2）：合同审批通过后自动生成全周期缴费计划（FR-PRICE-005）。
 * 通过 ApprovalCompleted 事件驱动。
 */
@Service
public class PlanGenerator {

    private final PricingEngine pricingEngine;
    private final PaymentPlanMapper planMapper;
    private final ContractMapper contractMapper;

    public PlanGenerator(
            PricingEngine pricingEngine,
            PaymentPlanMapper planMapper,
            ContractMapper contractMapper) {
        this.pricingEngine = pricingEngine;
        this.planMapper = planMapper;
        this.contractMapper = contractMapper;
    }

    @Transactional
    public List<PaymentPlan> generate(Long contractId) {
        Contract contract = contractMapper.selectById(contractId);
        if (contract == null) {
            throw new com.ams.common.exception.AppException(
                    com.ams.common.exception.ErrorCode.NOT_FOUND, "合同不存在");
        }
        // 幂等：已存在计划则跳过
        long existing = planMapper.selectCount(
                new LambdaQueryWrapper<PaymentPlan>().eq(PaymentPlan::getContractId, contractId));
        if (existing > 0) {
            return planMapper.selectList(
                    new LambdaQueryWrapper<PaymentPlan>().eq(PaymentPlan::getContractId, contractId));
        }

        List<PricingEngine.PlanItem> items = pricingEngine.generate(contract);
        int no = 1;
        for (PricingEngine.PlanItem item : items) {
            PaymentPlan plan = new PaymentPlan();
            plan.setContractId(contractId);
            plan.setPeriodNo(no++);
            plan.setPeriodStart(item.periodStart());
            plan.setPeriodEnd(item.periodEnd());
            plan.setPlannedAmount(item.amount());
            plan.setDueDate(item.dueDate());
            plan.setStatus("pending");
            planMapper.insert(plan);
        }
        return planMapper.selectList(
                new LambdaQueryWrapper<PaymentPlan>().eq(PaymentPlan::getContractId, contractId));
    }

    public List<PaymentPlan> listByContract(Long contractId) {
        return planMapper.selectList(
                new LambdaQueryWrapper<PaymentPlan>()
                        .eq(PaymentPlan::getContractId, contractId)
                        .orderByAsc(PaymentPlan::getPeriodNo));
    }
}
