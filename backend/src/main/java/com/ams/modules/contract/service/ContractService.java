package com.ams.modules.contract.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.service.LeaseControlService;
import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.config.service.ConfigVersionService;
import com.ams.modules.contract.ContractStatus;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.entity.VacateOrder;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.evaluation.service.EvaluationService;
import com.ams.modules.lease.service.TenantService;
import com.ams.modules.pricing.PlanGenerator;
import com.ams.modules.revitalization.service.RevitalizationService;
import com.ams.platform.approval.ApprovalEngine;
import com.ams.platform.approval.entity.ApprovalInstance;
import com.ams.platform.event.ApprovalCompletedEvent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.BeanUtils;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合同服务（FR-CON-* / FR-CON-LC-* / FR-PRICE-006）：签约、审批、续签/转租/主体/面积变更、低价特批。
 */
@Service
public class ContractService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ContractMapper contractMapper;
    private final AssetMapper assetMapper;
    private final LeaseControlService leaseControlService;
    private final TenantService tenantService;
    private final ApprovalEngine approvalEngine;
    private final PlanGenerator planGenerator;
    private final ContractVersionService contractVersionService;
    private final RevitalizationService revitalizationService;
    private final BillMapper billMapper;
    private final ConfigVersionService configVersionService;
    private final VacateService vacateService;

    public ContractService(
            ContractMapper contractMapper,
            AssetMapper assetMapper,
            LeaseControlService leaseControlService,
            TenantService tenantService,
            ApprovalEngine approvalEngine,
            PlanGenerator planGenerator,
            ContractVersionService contractVersionService,
            RevitalizationService revitalizationService,
            BillMapper billMapper,
            ConfigVersionService configVersionService,
            @Lazy VacateService vacateService) {
        this.contractMapper = contractMapper;
        this.assetMapper = assetMapper;
        this.leaseControlService = leaseControlService;
        this.tenantService = tenantService;
        this.approvalEngine = approvalEngine;
        this.planGenerator = planGenerator;
        this.contractVersionService = contractVersionService;
        this.revitalizationService = revitalizationService;
        this.billMapper = billMapper;
        this.configVersionService = configVersionService;
        this.vacateService = vacateService;
    }

    public PageResult<Contract> page(long page, long pageSize, String status, String keyword, Long companyId) {
        Page<Contract> result = contractMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<Contract>()
                        .eq(status != null, Contract::getStatus, status)
                        .like(keyword != null && !keyword.isBlank(), Contract::getContractNo, keyword)
                        .orderByDesc(Contract::getId));
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    public Contract get(Long id) {
        Contract contract = contractMapper.selectById(id);
        if (contract == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return contract;
    }

    /** 创建合同草稿；低于底价须 requestSpecialApproval=true 走超低价特批。 */
    @Transactional
    public Contract create(Contract contract) {
        return create(contract, Boolean.TRUE.equals(contract.getSpecialApprovalRequired()));
    }

    @Transactional
    public Contract create(Contract contract, boolean requestSpecialApproval) {
        Asset asset = assetMapper.selectById(contract.getAssetId());
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        tenantService.assertNotBlacklisted(contract.getTenantId());

        boolean belowFloor = isBelowFloor(contract, asset);
        if (belowFloor && !requestSpecialApproval) {
            throw new AppException(ErrorCode.PRICE_BELOW_FLOOR);
        }

        contract.setId(null);
        contract.setContractNo(generateContractNo());
        contract.setVersion(1);
        contract.setPaymentStatus("unpaid");
        contract.setSpecialApprovalRequired(belowFloor);
        contract.setBelowFloorCleared(false);

        if (belowFloor) {
            contract.setStatus(ContractStatus.APPROVING);
            contractMapper.insert(contract);
            contractVersionService.snapshot(null, contract, "create_low_price");
            approvalEngine.start("contract_low_price", contract.getId());
            return contract;
        }

        contract.setStatus(ContractStatus.DRAFT);
        contractMapper.insert(contract);
        contractVersionService.snapshot(null, contract, "create");
        return contract;
    }

    @Transactional
    public ApprovalInstance submit(Long contractId) {
        Contract contract = get(contractId);
        if (Boolean.TRUE.equals(contract.getSpecialApprovalRequired())
                && !Boolean.TRUE.equals(contract.getBelowFloorCleared())) {
            throw new AppException(ErrorCode.CONFLICT, "超低价特批尚未通过，不可提交签约审批");
        }
        if (!ContractStatus.DRAFT.equals(contract.getStatus())
                && !ContractStatus.RENEWABLE.equals(contract.getStatus())
                && !ContractStatus.EXPIRED.equals(contract.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "当前状态不可提交审批");
        }
        contract.setStatus(ContractStatus.APPROVING);
        contractMapper.updateById(contract);
        return approvalEngine.start("contract", contractId);
    }

    @EventListener
    @Transactional
    public void onApprovalCompleted(ApprovalCompletedEvent event) {
        if ("contract_low_price".equals(event.getBizType())) {
            handleLowPriceApproval(event);
            return;
        }
        if (!"contract".equals(event.getBizType())) {
            return;
        }
        Contract contract = get(event.getBizId());
        if (event.isApproved()) {
            if (!ContractStatus.APPROVING.equals(contract.getStatus())) {
                return;
            }
            activate(contract);
        } else if (ContractStatus.APPROVING.equals(contract.getStatus())) {
            contract.setStatus(ContractStatus.DRAFT);
            contractMapper.updateById(contract);
        }
    }

    private void handleLowPriceApproval(ApprovalCompletedEvent event) {
        Contract contract = get(event.getBizId());
        Contract before = copy(contract);
        if (event.isApproved()) {
            contract.setSpecialApprovalRequired(false);
            contract.setBelowFloorCleared(true);
            contract.setStatus(ContractStatus.DRAFT);
            contract.setRemark(appendRemark(contract.getRemark(), "超低价特批已通过"));
            contractMapper.updateById(contract);
            contractVersionService.snapshot(before, contract, "low_price_approved");
        } else {
            contract.setStatus(ContractStatus.VOIDED);
            contract.setRemark(appendRemark(contract.getRemark(), "超低价特批驳回"));
            contractMapper.updateById(contract);
            contractVersionService.snapshot(before, contract, "low_price_rejected");
        }
    }

    private void activate(Contract contract) {
        if (ContractStatus.ACTIVE.equals(contract.getStatus())) {
            return;
        }
        Contract before = copy(contract);
        contract.setStatus(ContractStatus.ACTIVE);
        contractMapper.updateById(contract);
        leaseControlService.transition(contract.getAssetId(), LeaseControlStatus.LEASED,
                "contract", contract.getId(), "合同生效");
        revitalizationService.closeOnLeased(contract.getAssetId());
        planGenerator.generate(contract.getId());
        contractVersionService.snapshot(before, contract, "activate");
    }

    @Transactional
    public Contract renew(Long contractId, LocalDate newEndDate, BigDecimal newRentAmount) {
        Contract contract = get(contractId);
        if (!ContractStatus.RENEWABLE.equals(contract.getStatus())
                && !ContractStatus.EXPIRED.equals(contract.getStatus())
                && !ContractStatus.EXPIRING.equals(contract.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "仅需续签/已到期合同可续签");
        }
        Contract renewed = new Contract();
        BeanUtils.copyProperties(contract, renewed, "id", "contractNo", "status", "version",
                "parentContractId", "createdAt", "updatedAt", "createdBy", "updatedBy",
                "specialApprovalRequired", "belowFloorCleared");
        renewed.setStartDate(newEndDate != null && contract.getEndDate() != null
                ? contract.getEndDate().plusDays(1) : contract.getEndDate());
        renewed.setEndDate(newEndDate);
        renewed.setRentAmount(newRentAmount != null ? newRentAmount : contract.getRentAmount());
        renewed.setParentContractId(contract.getId());
        renewed.setStatus(ContractStatus.DRAFT);
        renewed.setContractNo(generateContractNo());
        renewed.setVersion(1);
        renewed.setSpecialApprovalRequired(false);
        renewed.setBelowFloorCleared(false);
        contractMapper.insert(renewed);
        contractVersionService.snapshot(contract, renewed, "renew");
        return renewed;
    }

    /** 转租（FR-CON-LC-002）：新租户草稿合同，原合同保持生效直至新合同激活或退租。 */
    @Transactional
    public Contract transfer(Long contractId, Long newTenantId, BigDecimal newRentAmount) {
        Contract contract = requireActiveLike(contractId);
        tenantService.assertNotBlacklisted(newTenantId);
        Contract draft = cloneAsDraft(contract, newTenantId, newRentAmount, null, null);
        draft.setParentContractId(contract.getId());
        draft.setRemark(appendRemark(draft.getRemark(), "转租自合同 " + contract.getContractNo()));
        contractMapper.insert(draft);
        contractVersionService.snapshot(contract, draft, "transfer");
        return draft;
    }

    /** 主体变更（FR-CON-LC-003）：在租合同直接更换租户并留痕。 */
    @Transactional
    public Contract changeParty(Long contractId, Long newTenantId) {
        Contract contract = requireActiveLike(contractId);
        tenantService.assertNotBlacklisted(newTenantId);
        Contract before = copy(contract);
        contract.setTenantId(newTenantId);
        bumpVersion(contract);
        contractMapper.updateById(contract);
        contractVersionService.snapshot(before, contract, "change_party");
        return contract;
    }

    /** 面积变更（FR-CON-LC-004）：更新面积/租金并重算未出账计划。 */
    @Transactional
    public Contract changeArea(Long contractId, BigDecimal newArea, BigDecimal newRentAmount) {
        Contract contract = requireActiveLike(contractId);
        if (newArea == null || newArea.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "面积须大于 0");
        }
        Contract before = copy(contract);
        contract.setLeaseArea(newArea);
        if (newRentAmount != null) {
            Asset asset = assetMapper.selectById(contract.getAssetId());
            if (asset != null && isBelowFloor(contractWithRent(contract, newRentAmount), asset)
                    && !Boolean.TRUE.equals(contract.getBelowFloorCleared())) {
                throw new AppException(ErrorCode.PRICE_BELOW_FLOOR, "调价低于底价，请先走超低价特批或调整价格");
            }
            contract.setRentAmount(newRentAmount);
        }
        bumpVersion(contract);
        contractMapper.updateById(contract);
        planGenerator.regeneratePending(contract.getId());
        contractVersionService.snapshot(before, contract, "change_area");
        return contract;
    }

    @Transactional
    public ApprovalInstance approveContract(Long contractId, boolean approve, String comment) {
        Contract contract = get(contractId);
        String bizType = Boolean.TRUE.equals(contract.getSpecialApprovalRequired())
                && !Boolean.TRUE.equals(contract.getBelowFloorCleared())
                ? "contract_low_price" : "contract";
        ApprovalInstance instance = approvalEngine.findPendingInstance(bizType, contractId);
        if (instance == null && "contract".equals(bizType)) {
            if (approve) {
                activate(contract);
            } else {
                contract.setStatus(ContractStatus.DRAFT);
                contractMapper.updateById(contract);
            }
            return null;
        }
        if (instance == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "无待审特批实例");
        }
        return approve
                ? approvalEngine.approve(instance.getId(), comment)
                : approvalEngine.reject(instance.getId(), comment);
    }

    @Transactional
    public void terminate(Long contractId, String by) {
        Contract contract = get(contractId);
        if (!ContractStatus.canTransition(contract.getStatus(), ContractStatus.TERMINATED)) {
            throw new AppException(ErrorCode.CONFLICT, "当前状态不可终止");
        }
        Contract before = copy(contract);
        contract.setStatus(ContractStatus.TERMINATED);
        contractMapper.updateById(contract);
        contractVersionService.snapshot(before, contract, "terminate:" + by);
    }

    /**
     * 提前解约（FR-CON-LC-005）：生成违约金账单并发起退租申请。
     * 罚则默认 = 月租金 × 配置月数（不超过剩余租期月数）。
     */
    @Transactional
    public Map<String, Object> earlyTerminate(Long contractId, String reason, BigDecimal penaltyOverride) {
        Contract contract = get(contractId);
        if (!ContractStatus.ACTIVE.equals(contract.getStatus())
                && !ContractStatus.EXPIRING.equals(contract.getStatus())
                && !ContractStatus.RENEWABLE.equals(contract.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "当前合同状态不可提前解约");
        }
        LocalDate today = LocalDate.now();
        LocalDate end = contract.getEndDate() == null ? today : contract.getEndDate();
        long remainMonths = Math.max(0, ChronoUnit.MONTHS.between(today.withDayOfMonth(1), end.withDayOfMonth(1)));
        if (end.isAfter(today) && remainMonths == 0) {
            remainMonths = 1;
        }
        int configured = 1;
        try {
            configured = Integer.parseInt(configVersionService.getValue(
                    "contract.early_terminate_penalty_months", "1"));
        } catch (NumberFormatException ignored) {
            configured = 1;
        }
        long chargeMonths = Math.min(remainMonths, Math.max(1, configured));
        BigDecimal rent = contract.getRentAmount() == null ? BigDecimal.ZERO : contract.getRentAmount();
        BigDecimal penalty = penaltyOverride != null
                ? penaltyOverride
                : rent.multiply(BigDecimal.valueOf(chargeMonths));

        Bill bill = null;
        if (penalty.compareTo(BigDecimal.ZERO) > 0) {
            bill = new Bill();
            bill.setBillNo("WY" + LocalDate.now().format(NO_FMT)
                    + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
            bill.setContractId(contract.getId());
            bill.setAssetId(contract.getAssetId());
            bill.setTenantId(contract.getTenantId());
            bill.setBillType("penalty");
            bill.setPeriodStart(today);
            bill.setPeriodEnd(end);
            bill.setDueDate(today.plusDays(7));
            bill.setAmount(penalty);
            bill.setPaidAmount(BigDecimal.ZERO);
            bill.setReducedAmount(BigDecimal.ZERO);
            bill.setLateFeeAmount(BigDecimal.ZERO);
            bill.setLateFeePaidAmount(BigDecimal.ZERO);
            bill.setStatus(BillStatus.UNPAID);
            bill.setDunningLevel(0);
            bill.setSource("system");
            bill.setRemark("early_terminate:" + (reason == null ? "" : reason));
            billMapper.insert(bill);
        }

        VacateOrder vacate = vacateService.apply(contractId,
                reason == null ? "提前解约" : "提前解约:" + reason, today);
        contractVersionService.snapshot(contract, contract, "early_terminate");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractId", contractId);
        result.put("remainMonths", remainMonths);
        result.put("chargeMonths", chargeMonths);
        result.put("penaltyAmount", penalty);
        result.put("penaltyBillId", bill == null ? null : bill.getId());
        result.put("vacateOrderId", vacate.getId());
        return result;
    }

    @Transactional
    public void voidContract(Long contractId) {
        Contract contract = get(contractId);
        if (ContractStatus.ACTIVE.equals(contract.getStatus())
                || ContractStatus.TERMINATED.equals(contract.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "已生效/已终止合同不可作废");
        }
        Contract before = copy(contract);
        contract.setStatus(ContractStatus.VOIDED);
        contractMapper.updateById(contract);
        contractVersionService.snapshot(before, contract, "void");
    }

    private Contract requireActiveLike(Long contractId) {
        Contract contract = get(contractId);
        if (!ContractStatus.ACTIVE.equals(contract.getStatus())
                && !ContractStatus.EXPIRING.equals(contract.getStatus())
                && !ContractStatus.RENEWABLE.equals(contract.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "仅在租/临期合同可办理变更");
        }
        return contract;
    }

    private Contract cloneAsDraft(Contract source, Long tenantId, BigDecimal rent,
            BigDecimal area, LocalDate endDate) {
        Contract draft = new Contract();
        BeanUtils.copyProperties(source, draft, "id", "contractNo", "status", "version",
                "createdAt", "updatedAt", "createdBy", "updatedBy",
                "specialApprovalRequired", "belowFloorCleared", "parentContractId");
        draft.setTenantId(tenantId != null ? tenantId : source.getTenantId());
        if (rent != null) {
            draft.setRentAmount(rent);
        }
        if (area != null) {
            draft.setLeaseArea(area);
        }
        if (endDate != null) {
            draft.setEndDate(endDate);
        }
        draft.setStatus(ContractStatus.DRAFT);
        draft.setContractNo(generateContractNo());
        draft.setVersion(1);
        draft.setSpecialApprovalRequired(false);
        draft.setBelowFloorCleared(false);
        return draft;
    }

    private void bumpVersion(Contract contract) {
        contract.setVersion((contract.getVersion() == null ? 1 : contract.getVersion()) + 1);
    }

    private boolean isBelowFloor(Contract contract, Asset asset) {
        if (contract.getRentAmount() == null) {
            return false;
        }
        BigDecimal floor = EvaluationService.effectiveFloor(asset);
        return floor != null && floor.compareTo(BigDecimal.ZERO) > 0
                && contract.getRentAmount().compareTo(floor) < 0;
    }

    private Contract contractWithRent(Contract contract, BigDecimal rent) {
        Contract c = copy(contract);
        c.setRentAmount(rent);
        return c;
    }

    private Contract copy(Contract src) {
        Contract c = new Contract();
        BeanUtils.copyProperties(src, c);
        return c;
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

    private String generateContractNo() {
        return "XCZL" + LocalDate.now().format(NO_FMT)
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }
}
