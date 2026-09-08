package com.ams.modules.contract.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.service.LeaseControlService;
import com.ams.modules.contract.ContractStatus;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.lease.service.TenantService;
import com.ams.platform.approval.ApprovalEngine;
import com.ams.platform.approval.entity.ApprovalInstance;
import com.ams.platform.event.ApprovalCompletedEvent;
import com.ams.platform.event.DomainEventPublisher;
import com.ams.modules.pricing.PlanGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合同服务（FR-CON-*）：签约、审批、续签、状态机。
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
    private final DomainEventPublisher eventPublisher;

    public ContractService(
            ContractMapper contractMapper,
            AssetMapper assetMapper,
            LeaseControlService leaseControlService,
            TenantService tenantService,
            ApprovalEngine approvalEngine,
            PlanGenerator planGenerator,
            DomainEventPublisher eventPublisher) {
        this.contractMapper = contractMapper;
        this.assetMapper = assetMapper;
        this.leaseControlService = leaseControlService;
        this.tenantService = tenantService;
        this.approvalEngine = approvalEngine;
        this.planGenerator = planGenerator;
        this.eventPublisher = eventPublisher;
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

    /** 创建合同草稿（低价合规 + 黑名单校验）。 */
    @Transactional
    public Contract create(Contract contract) {
        Asset asset = assetMapper.selectById(contract.getAssetId());
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        // 黑名单校验（FR-TENANT-CREDIT-002）
        tenantService.assertNotBlacklisted(contract.getTenantId());
        // 低价合规校验（FR-PRICE-006）：签约价低于备案底价阻断或触发超低价审批
        assertPriceNotBelowFloor(contract, asset);

        contract.setId(null);
        contract.setContractNo(generateContractNo());
        contract.setStatus(ContractStatus.DRAFT);
        contract.setVersion(1);
        contract.setPaymentStatus("unpaid");
        contractMapper.insert(contract);
        return contract;
    }

    /** 提交审批。 */
    @Transactional
    public ApprovalInstance submit(Long contractId) {
        Contract contract = get(contractId);
        if (!ContractStatus.DRAFT.equals(contract.getStatus())
                && !ContractStatus.RENEWABLE.equals(contract.getStatus())
                && !ContractStatus.EXPIRED.equals(contract.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "当前状态不可提交审批");
        }
        contract.setStatus(ContractStatus.APPROVING);
        contractMapper.updateById(contract);
        return approvalEngine.start("contract", contractId);
    }

    /** 监听审批完成 → 激活合同 + 生成缴费计划 + 租控在租；驳回回草稿。 */
    @EventListener
    @Transactional
    public void onApprovalCompleted(ApprovalCompletedEvent event) {
        if (!"contract".equals(event.getBizType())) {
            return;
        }
        Contract contract = get(event.getBizId());
        if (event.isApproved()) {
            if (!ContractStatus.APPROVING.equals(contract.getStatus())) {
                return;
            }
            activate(contract);
        } else {
            if (ContractStatus.APPROVING.equals(contract.getStatus())) {
                contract.setStatus(ContractStatus.DRAFT);
                contractMapper.updateById(contract);
            }
        }
    }

    private void activate(Contract contract) {
        if (ContractStatus.ACTIVE.equals(contract.getStatus())) {
            return; // 幂等
        }
        contract.setStatus(ContractStatus.ACTIVE);
        contractMapper.updateById(contract);
        // 租控 → 在租
        leaseControlService.transition(contract.getAssetId(), LeaseControlStatus.LEASED,
                "contract", contract.getId(), "合同生效");
        // 生成缴费计划（FR-PRICE-005）
        planGenerator.generate(contract.getId());
    }

    /** 续签（FR-CON-LC-001）：新合同或延期。 */
    @Transactional
    public Contract renew(Long contractId, LocalDate newEndDate, java.math.BigDecimal newRentAmount) {
        Contract contract = get(contractId);
        if (!ContractStatus.RENEWABLE.equals(contract.getStatus())
                && !ContractStatus.EXPIRED.equals(contract.getStatus())
                && !ContractStatus.EXPIRING.equals(contract.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "仅需续签/已到期合同可续签");
        }
        Contract renewed = new Contract();
        renewed.setAssetId(contract.getAssetId());
        renewed.setTenantId(contract.getTenantId());
        renewed.setStartDate(newEndDate != null && contract.getEndDate() != null
                ? contract.getEndDate().plusDays(1) : contract.getEndDate());
        renewed.setEndDate(newEndDate);
        renewed.setRentAmount(newRentAmount != null ? newRentAmount : contract.getRentAmount());
        renewed.setRentType(contract.getRentType());
        renewed.setDepositAmount(contract.getDepositAmount());
        renewed.setPaymentCycle(contract.getPaymentCycle());
        renewed.setFreeRentDays(contract.getFreeRentDays());
        renewed.setIncreaseRate(contract.getIncreaseRate());
        renewed.setGraceDays(contract.getGraceDays());
        renewed.setProrationBase(contract.getProrationBase());
        renewed.setLeaseArea(contract.getLeaseArea());
        renewed.setParentContractId(contract.getId());
        renewed.setStatus(ContractStatus.DRAFT);
        renewed.setContractNo(generateContractNo());
        contractMapper.insert(renewed);
        return renewed;
    }

    /** 合同审批（通过/驳回，FR-CON-003）。 */
    @Transactional
    public ApprovalInstance approveContract(Long contractId, boolean approve, String comment) {
        Contract contract = get(contractId);
        ApprovalInstance instance = approvalEngine.findPendingInstance("contract", contractId);
        if (instance == null) {
            // 无流程定义时直接激活（自动通过场景）
            if (approve) {
                activate(contract);
            } else {
                contract.setStatus(ContractStatus.DRAFT);
                contractMapper.updateById(contract);
            }
            return null;
        }
        return approve
                ? approvalEngine.approve(instance.getId(), comment)
                : approvalEngine.reject(instance.getId(), comment);
    }

    /** 终止（仅由退租结算或提前解约驱动，FR-CON-LC-005）。 */    @Transactional
    public void terminate(Long contractId, String by) {
        Contract contract = get(contractId);
        if (!ContractStatus.canTransition(contract.getStatus(), ContractStatus.TERMINATED)) {
            throw new AppException(ErrorCode.CONFLICT, "当前状态不可终止");
        }
        contract.setStatus(ContractStatus.TERMINATED);
        contractMapper.updateById(contract);
    }

    /** 作废（未生效合同）。 */
    @Transactional
    public void voidContract(Long contractId) {
        Contract contract = get(contractId);
        if (ContractStatus.ACTIVE.equals(contract.getStatus())
                || ContractStatus.TERMINATED.equals(contract.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "已生效/已终止合同不可作废");
        }
        contract.setStatus(ContractStatus.VOIDED);
        contractMapper.updateById(contract);
    }

    private void assertPriceNotBelowFloor(Contract contract, Asset asset) {
        if (contract.getRentAmount() == null) {
            return;
        }
        java.math.BigDecimal floor = asset.getBaseRentFloor();
        if (floor != null && floor.compareTo(java.math.BigDecimal.ZERO) > 0
                && contract.getRentAmount().compareTo(floor) < 0) {
            // 低价合规：默认阻断，提示走超低价审批（FR-COMP-001）
            throw new AppException(ErrorCode.PRICE_BELOW_FLOOR);
        }
    }

    private String generateContractNo() {
        return "XCZL" + LocalDate.now().format(NO_FMT)
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }
}
