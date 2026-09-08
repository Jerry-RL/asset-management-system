package com.ams.modules.contract.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.service.LeaseControlService;
import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.service.BillService;
import com.ams.modules.billing.service.PrepayService;
import com.ams.modules.config.service.ConfigVersionService;
import com.ams.modules.contract.ContractStatus;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.entity.DepositTransaction;
import com.ams.modules.contract.entity.VacateOrder;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.contract.mapper.DepositTransactionMapper;
import com.ams.modules.contract.mapper.VacateOrderMapper;
import com.ams.modules.revitalization.service.RevitalizationService;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退租清场与保证金闭环（FR-VACATE-* / FR-PREPAY-004）。
 * 默认资金池顺序：损坏 → 租金 → 杂费 → 滞纳金 → 保证金抵扣 → 预收抵扣 → 退还余额（可配置）。
 */
@Service
public class VacateService {

    private final VacateOrderMapper vacateOrderMapper;
    private final ContractMapper contractMapper;
    private final DepositTransactionMapper depositTransactionMapper;
    private final LeaseControlService leaseControlService;
    private final BillService billService;
    private final PrepayService prepayService;
    private final RevitalizationService revitalizationService;
    private final ConfigVersionService configVersionService;

    public VacateService(
            VacateOrderMapper vacateOrderMapper,
            ContractMapper contractMapper,
            DepositTransactionMapper depositTransactionMapper,
            LeaseControlService leaseControlService,
            BillService billService,
            PrepayService prepayService,
            RevitalizationService revitalizationService,
            ConfigVersionService configVersionService) {
        this.vacateOrderMapper = vacateOrderMapper;
        this.contractMapper = contractMapper;
        this.depositTransactionMapper = depositTransactionMapper;
        this.leaseControlService = leaseControlService;
        this.billService = billService;
        this.prepayService = prepayService;
        this.revitalizationService = revitalizationService;
        this.configVersionService = configVersionService;
    }

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
        long open = vacateOrderMapper.selectCount(
                new LambdaQueryWrapper<VacateOrder>()
                        .eq(VacateOrder::getContractId, contractId)
                        .ne(VacateOrder::getStatus, "completed"));
        if (open > 0) {
            throw new AppException(ErrorCode.CONFLICT, "已有进行中的退租单");
        }
        VacateOrder order = new VacateOrder();
        order.setContractId(contractId);
        order.setStatus("applying");
        order.setReason(reason);
        order.setExpectedVacateDate(expectedVacateDate);
        vacateOrderMapper.insert(order);
        try {
            leaseControlService.transition(contract.getAssetId(), LeaseControlStatus.VACATING,
                    "vacate", order.getId(), "发起退租");
        } catch (AppException ex) {
            // 已是退租中等合法中间态时不阻断申请
            if (ex.getErrorCode() != ErrorCode.CONFLICT) {
                throw ex;
            }
        }
        return order;
    }

    @Transactional
    public VacateOrder submitInspection(Long vacateOrderId, BigDecimal waterReading,
            BigDecimal electricReading, String remark) {
        return submitInspection(vacateOrderId, waterReading, electricReading, remark, null);
    }

    @Transactional
    public VacateOrder submitInspection(Long vacateOrderId, BigDecimal waterReading,
            BigDecimal electricReading, String remark, String fileIds) {
        VacateOrder order = require(vacateOrderId);
        if ("completed".equals(order.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "退租单已完成");
        }
        order.setStatus("inspecting");
        order.setWaterReading(waterReading);
        order.setElectricReading(electricReading);
        order.setInspectionRemark(remark);
        order.setInspectionFileIds(fileIds);
        order.setInspectedBy(SecurityUtils.currentUserIdOrNull());
        order.setInspectedAt(LocalDateTime.now());
        vacateOrderMapper.updateById(order);
        return order;
    }

    /**
     * 退租结算：损坏赔偿 → 租金欠费 → 杂费欠费 → 滞纳金 → 保证金抵扣 → 预收抵扣 → 余额退还。
     */
    @Transactional
    public VacateOrder settle(Long vacateOrderId, BigDecimal damageCompensation) {
        VacateOrder order = require(vacateOrderId);
        if ("completed".equals(order.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "退租单已结算");
        }
        if (!"inspecting".equals(order.getStatus()) && !"settling".equals(order.getStatus())
                && !"applying".equals(order.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "当前状态不可结算");
        }

        Contract contract = contractMapper.selectById(order.getContractId());
        if (contract == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "合同不存在");
        }

        order.setStatus("settling");
        vacateOrderMapper.updateById(order);

        List<Bill> bills = billService.listByContract(contract.getId());
        BigDecimal rentArrears = BigDecimal.ZERO;
        BigDecimal utilityArrears = BigDecimal.ZERO;
        BigDecimal lateFeeArrears = BigDecimal.ZERO;
        BigDecimal penaltyArrears = BigDecimal.ZERO;
        for (Bill bill : bills) {
            if (BillStatus.VOIDED.equals(bill.getStatus()) || BillStatus.PAID.equals(bill.getStatus())) {
                continue;
            }
            // 冻结：标记备注，阻止新收款以外的状态回退（保留 unpaid/partial 供抵扣核算）
            bill.setRemark(appendRemark(bill.getRemark(), "vacate_frozen:" + order.getId()));
            billService.update(bill);

            BigDecimal principalDue = nz(bill.getAmount()).subtract(nz(bill.getPaidAmount()))
                    .subtract(nz(bill.getReducedAmount()));
            BigDecimal lateDue = nz(bill.getLateFeeAmount()).subtract(nz(bill.getLateFeePaidAmount()));
            lateFeeArrears = lateFeeArrears.add(lateDue.max(BigDecimal.ZERO));
            if ("utility".equalsIgnoreCase(bill.getBillType())) {
                utilityArrears = utilityArrears.add(principalDue.max(BigDecimal.ZERO));
            } else if ("penalty".equalsIgnoreCase(bill.getBillType())) {
                penaltyArrears = penaltyArrears.add(principalDue.max(BigDecimal.ZERO));
            } else {
                rentArrears = rentArrears.add(principalDue.max(BigDecimal.ZERO));
            }
        }

        BigDecimal damage = nz(damageCompensation);
        BigDecimal depositPool = nz(contract.getDepositAmount());

        String orderCfg = configVersionService.getValue(
                ConfigVersionService.KEY_VACATE_POOL_ORDER,
                ConfigVersionService.DEFAULT_VACATE_POOL_ORDER);
        List<String> steps = Arrays.stream(orderCfg.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        // 按配置顺序累加应付义务（资金池抵扣前）
        BigDecimal obligation = BigDecimal.ZERO;
        for (String step : steps) {
            obligation = switch (step) {
                case "damage" -> obligation.add(damage);
                case "penalty" -> obligation.add(penaltyArrears);
                case "rent" -> obligation.add(rentArrears);
                case "utility" -> obligation.add(utilityArrears);
                case "late_fee" -> obligation.add(lateFeeArrears);
                default -> obligation;
            };
        }
        if (obligation.compareTo(BigDecimal.ZERO) == 0) {
            obligation = damage.add(penaltyArrears).add(rentArrears).add(utilityArrears).add(lateFeeArrears);
        }

        BigDecimal depositDeduct = BigDecimal.ZERO;
        BigDecimal prepayDeduct = BigDecimal.ZERO;
        BigDecimal remain = obligation;
        for (String step : steps) {
            if ("deposit".equals(step) && remain.compareTo(BigDecimal.ZERO) > 0) {
                depositDeduct = depositPool.min(remain);
                remain = remain.subtract(depositDeduct);
            } else if ("prepay".equals(step) && remain.compareTo(BigDecimal.ZERO) > 0) {
                prepayDeduct = prepayService.consume(contract.getId(), remain, "退租结算抵扣");
                remain = remain.subtract(prepayDeduct);
            }
        }
        // 若配置未含 deposit/prepay，回退默认
        if (!steps.contains("deposit") && !steps.contains("prepay")) {
            depositDeduct = depositPool.min(remain);
            remain = remain.subtract(depositDeduct);
            prepayDeduct = prepayService.consume(contract.getId(), remain, "退租结算抵扣");
            remain = remain.subtract(prepayDeduct);
        }

        BigDecimal depositRefund = depositPool.subtract(depositDeduct).max(BigDecimal.ZERO);
        BigDecimal remainingObligation = remain.max(BigDecimal.ZERO);
        BigDecimal prepayBalance = prepayService.totalBalance(contract.getId());
        BigDecimal prepayRefund = prepayBalance;
        if (prepayRefund.compareTo(BigDecimal.ZERO) > 0) {
            prepayService.consume(contract.getId(), prepayRefund, "退租预收退还清零");
        }

        BigDecimal cover = depositDeduct.add(prepayDeduct);
        applyCoverToBills(bills, cover);

        if (depositDeduct.compareTo(BigDecimal.ZERO) > 0) {
            recordDeposit(contract.getId(), "deduct", depositDeduct, "退租费用抵扣保证金");
        }
        if (depositRefund.compareTo(BigDecimal.ZERO) > 0) {
            recordDeposit(contract.getId(), "refund", depositRefund, "退租保证金退还");
        }

        contract.setDepositAmount(BigDecimal.ZERO);
        contract.setPrepayAmount(BigDecimal.ZERO);
        contract.setStatus(ContractStatus.TERMINATED);
        contractMapper.updateById(contract);

        order.setDamageCompensation(damage);
        order.setRentArrears(rentArrears);
        order.setUtilityArrears(utilityArrears);
        order.setLateFeeArrears(lateFeeArrears);
        order.setPenaltyAmount(penaltyArrears);
        order.setDepositDeducted(depositDeduct);
        order.setPrepayDeducted(prepayDeduct);
        order.setSettlementAmount(obligation);
        order.setDepositRefund(depositRefund);
        order.setPrepayRefund(prepayRefund);
        order.setSettledAt(LocalDateTime.now());
        order.setStatus("completed");
        order.setInspectionRemark(appendRemark(order.getInspectionRemark(),
                "fund_pool_order=" + orderCfg));
        if (remainingObligation.compareTo(BigDecimal.ZERO) > 0) {
            order.setInspectionRemark(appendRemark(order.getInspectionRemark(),
                    "未结清余额:" + remainingObligation.toPlainString()));
        }
        vacateOrderMapper.updateById(order);

        leaseControlService.transition(contract.getAssetId(), LeaseControlStatus.VACANT,
                "vacate", order.getId(), "退租完成");
        revitalizationService.createOnVacant(contract.getAssetId(),
                order.getReason() == null ? "退租" : order.getReason(), "招租盘活");
        return order;
    }

    public List<VacateOrder> list(Long contractId) {
        return vacateOrderMapper.selectList(
                new LambdaQueryWrapper<VacateOrder>()
                        .eq(contractId != null, VacateOrder::getContractId, contractId)
                        .orderByDesc(VacateOrder::getId));
    }

    private BigDecimal applyCoverToBills(List<Bill> bills, BigDecimal cover) {
        BigDecimal remain = cover;
        // 顺序：损坏不入账单；租金 → 杂费 → 滞纳金（按账单到期）
        List<Bill> ordered = bills.stream()
                .filter(b -> !BillStatus.VOIDED.equals(b.getStatus()) && !BillStatus.PAID.equals(b.getStatus()))
                .sorted((a, b) -> {
                    int ta = typeOrder(a.getBillType());
                    int tb = typeOrder(b.getBillType());
                    if (ta != tb) {
                        return Integer.compare(ta, tb);
                    }
                    if (a.getDueDate() == null || b.getDueDate() == null) {
                        return Long.compare(a.getId(), b.getId());
                    }
                    return a.getDueDate().compareTo(b.getDueDate());
                })
                .toList();
        for (Bill bill : ordered) {
            if (remain.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            // 先本金后滞纳金（租金/水电优先于滞纳金）
            BigDecimal principalDue = nz(bill.getAmount()).subtract(nz(bill.getPaidAmount()))
                    .subtract(nz(bill.getReducedAmount()));
            BigDecimal lateDue = nz(bill.getLateFeeAmount()).subtract(nz(bill.getLateFeePaidAmount()));
            BigDecimal principalApply = remain.min(principalDue.max(BigDecimal.ZERO));
            if (principalApply.compareTo(BigDecimal.ZERO) > 0) {
                bill.setPaidAmount(nz(bill.getPaidAmount()).add(principalApply));
                remain = remain.subtract(principalApply);
            }
            BigDecimal lateApply = remain.min(lateDue.max(BigDecimal.ZERO));
            if (lateApply.compareTo(BigDecimal.ZERO) > 0) {
                bill.setLateFeePaidAmount(nz(bill.getLateFeePaidAmount()).add(lateApply));
                remain = remain.subtract(lateApply);
            }
            bill.setStatus(resolveBillStatus(bill));
            billService.update(bill);
        }
        return remain;
    }

    private static int typeOrder(String billType) {
        if ("penalty".equalsIgnoreCase(billType)) {
            return 0;
        }
        if ("rent".equalsIgnoreCase(billType)) {
            return 1;
        }
        if ("utility".equalsIgnoreCase(billType)) {
            return 2;
        }
        return 3;
    }

    private String resolveBillStatus(Bill bill) {
        BigDecimal principalDue = nz(bill.getAmount()).subtract(nz(bill.getPaidAmount()))
                .subtract(nz(bill.getReducedAmount()));
        BigDecimal lateFeeDue = nz(bill.getLateFeeAmount()).subtract(nz(bill.getLateFeePaidAmount()));
        if (principalDue.compareTo(BigDecimal.ZERO) <= 0 && lateFeeDue.compareTo(BigDecimal.ZERO) <= 0) {
            return BillStatus.PAID;
        }
        if (nz(bill.getPaidAmount()).compareTo(BigDecimal.ZERO) > 0
                || nz(bill.getLateFeePaidAmount()).compareTo(BigDecimal.ZERO) > 0) {
            return BillStatus.PARTIAL_PAID;
        }
        return BillStatus.UNPAID;
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

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String appendRemark(String base, String add) {
        if (add == null || add.isBlank()) {
            return base;
        }
        if (base == null || base.isBlank()) {
            return add;
        }
        return base + ";" + add;
    }
}
