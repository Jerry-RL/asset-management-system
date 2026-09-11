package com.ams.modules.billing.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.pricing.entity.PaymentPlan;
import com.ams.modules.pricing.mapper.PaymentPlanMapper;
import com.ams.platform.event.BillIssuedEvent;
import com.ams.platform.event.DomainEventPublisher;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账单服务：出账（缴费计划 → 账单）、账单列表（收费大厅 FR-BILL-001）。
 */
@Service
public class BillService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final BillMapper billMapper;
    private final PaymentPlanMapper planMapper;
    private final ContractMapper contractMapper;
    private final DomainEventPublisher eventPublisher;

    public BillService(BillMapper billMapper, PaymentPlanMapper planMapper, ContractMapper contractMapper,
            DomainEventPublisher eventPublisher) {
        this.billMapper = billMapper;
        this.planMapper = planMapper;
        this.contractMapper = contractMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 出账：将到期（due_date <= today）的待出账缴费计划生成账单（FR-PRICE-005 / 收费大厅数据源）。
     */
    @Transactional
    public int issueBills() {
        List<PaymentPlan> plans = planMapper.selectList(
                new LambdaQueryWrapper<PaymentPlan>()
                        .eq(PaymentPlan::getStatus, "pending")
                        .le(PaymentPlan::getDueDate, LocalDate.now()));
        int issued = 0;
        for (PaymentPlan plan : plans) {
            if (billMapper.selectCount(
                    new LambdaQueryWrapper<Bill>().eq(Bill::getPlanId, plan.getId())) > 0) {
                continue;
            }
            Contract contract = contractMapper.selectById(plan.getContractId());
            Bill bill = new Bill();
            bill.setBillNo(generateBillNo());
            bill.setContractId(plan.getContractId());
            bill.setPlanId(plan.getId());
            bill.setAssetId(contract == null ? null : contract.getAssetId());
            bill.setTenantId(contract == null ? null : contract.getTenantId());
            bill.setBillType("rent");
            bill.setPeriodStart(plan.getPeriodStart());
            bill.setPeriodEnd(plan.getPeriodEnd());
            bill.setDueDate(plan.getDueDate());
            bill.setAmount(plan.getPlannedAmount());
            bill.setPaidAmount(java.math.BigDecimal.ZERO);
            bill.setReducedAmount(java.math.BigDecimal.ZERO);
            bill.setLateFeeAmount(java.math.BigDecimal.ZERO);
            bill.setLateFeePaidAmount(java.math.BigDecimal.ZERO);
            bill.setStatus(BillStatus.UNPAID);
            bill.setDunningLevel(0);
            bill.setSource("system");
            billMapper.insert(bill);
            plan.setStatus("issued");
            planMapper.updateById(plan);
            // FR-NOTIF / DSD §4.8：出账 → BillIssued（租户缴费提醒、下游投影）
            eventPublisher.publishAfterCommit(new BillIssuedEvent(
                    bill.getId(), bill.getBillNo(), bill.getContractId(),
                    bill.getAmount(), bill.getDueDate()));
            issued++;
        }
        return issued;
    }

    public PageResult<Bill> page(long page, long pageSize, Long contractId, String status) {
        Page<Bill> result = billMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<Bill>()
                        .eq(contractId != null, Bill::getContractId, contractId)
                        .eq(status != null, Bill::getStatus, status)
                        .orderByDesc(Bill::getId));
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    public Bill get(Long id) {
        Bill bill = billMapper.selectById(id);
        if (bill == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return bill;
    }

    public List<Bill> listByContract(Long contractId) {
        return billMapper.selectList(
                new LambdaQueryWrapper<Bill>()
                        .eq(Bill::getContractId, contractId)
                        .orderByAsc(Bill::getDueDate));
    }

    public void updateBillStatus(Bill bill) {
        billMapper.updateById(bill);
    }

    public void update(Bill bill) {
        billMapper.updateById(bill);
    }

    private String generateBillNo() {
        return "ZD" + LocalDate.now().format(NO_FMT)
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }
}
