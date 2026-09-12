package com.ams.modules.contract.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.entity.VacateOrder;
import com.ams.modules.contract.service.ContractService;
import com.ams.modules.contract.service.ESignService;
import com.ams.modules.contract.service.VacateService;
import com.ams.platform.approval.entity.ApprovalInstance;
import com.ams.platform.security.Audited;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import com.ams.platform.security.RequiresPerm;
import com.ams.platform.security.OwnershipResolver;
import com.ams.platform.security.RbacService;
import com.ams.platform.security.SecurityUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合同与退租接口（FR-CON-*、FR-VACATE-*）。
 */
@RestController
@RequestMapping("/api/v1")
public class ContractController {

    private final ContractService contractService;
    private final VacateService vacateService;
    private final ESignService eSignService;
    private final OwnershipResolver ownershipResolver;
    private final RbacService rbacService;

    public ContractController(
            ContractService contractService,
            VacateService vacateService,
            ESignService eSignService,
            OwnershipResolver ownershipResolver,
            RbacService rbacService) {
        this.contractService = contractService;
        this.vacateService = vacateService;
        this.eSignService = eSignService;
        this.ownershipResolver = ownershipResolver;
        this.rbacService = rbacService;
    }

    @GetMapping("/contracts")
    @RequiresPerm("contract.ledger:view")
    public ApiResponse<PageResult<Contract>> contracts(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(contractService.page(page, pageSize, status, keyword, null), TraceIdUtil.get());
    }

    @GetMapping("/contracts/{contractId}")
    @RequiresPerm("contract.ledger:view")
    public ApiResponse<Contract> contract(@PathVariable Long contractId) {
        assertContract(contractId);
        return ApiResponse.ok(contractService.get(contractId), TraceIdUtil.get());
    }

    @PostMapping("/contracts")
    @RequiresPerm("contract.ledger:create")
    @Audited(module = "contract", action = "create")
    public ApiResponse<Contract> create(@RequestBody Map<String, Object> body) {
        Contract contract = mapContract(body);
        // 新建：合同没有公司列，归属由请求体给出的资产推导
        rbacService.assertCompanyAccess(SecurityUtils.current(), ownershipResolver.ofAsset(contract.getAssetId()));
        boolean special = body.get("requestSpecialApproval") != null
                && Boolean.parseBoolean(body.get("requestSpecialApproval").toString());
        if (special) {
            contract.setSpecialApprovalRequired(true);
        }
        return ApiResponse.ok(contractService.create(contract, special
                || Boolean.TRUE.equals(contract.getSpecialApprovalRequired())), TraceIdUtil.get());
    }

    @PostMapping("/contracts/{contractId}/submit")
    @RequiresPerm("contract.ledger:update")
    @Audited(module = "contract", action = "submit")
    public ApiResponse<ApprovalInstance> submit(@PathVariable Long contractId) {
        assertContract(contractId);
        return ApiResponse.ok(contractService.submit(contractId), TraceIdUtil.get());
    }

    @PostMapping("/contracts/{contractId}/approve")
    @RequiresPerm("contract.ledger:approve")
    @Audited(module = "contract", action = "approve")
    public ApiResponse<ApprovalInstance> approve(@PathVariable Long contractId, @RequestBody Map<String, String> body) {
        assertContract(contractId);
        String action = body.getOrDefault("action", "approve");
        String comment = body.get("comment");
        ApprovalInstance instance = contractService.approveContract(contractId, "approve".equals(action), comment);
        return ApiResponse.ok(instance, TraceIdUtil.get());
    }

    @PostMapping("/contracts/{contractId}/renew")
    @RequiresPerm("contract.ledger:update")
    @Audited(module = "contract", action = "renew")
    public ApiResponse<Contract> renew(@PathVariable Long contractId, @RequestBody Map<String, Object> body) {
        assertContract(contractId);
        LocalDate newEndDate = body.get("endDate") == null ? null : LocalDate.parse(body.get("endDate").toString());
        BigDecimal rent = body.get("rentAmount") == null ? null : new BigDecimal(body.get("rentAmount").toString());
        return ApiResponse.ok(contractService.renew(contractId, newEndDate, rent), TraceIdUtil.get());
    }

    @PostMapping("/contracts/{contractId}/transfer")
    @RequiresPerm("contract.ledger:update")
    @Audited(module = "contract", action = "transfer")
    public ApiResponse<Contract> transfer(@PathVariable Long contractId, @RequestBody Map<String, Object> body) {
        assertContract(contractId);
        Long newTenantId = Long.valueOf(body.get("newTenantId").toString());
        BigDecimal rent = body.get("rentAmount") == null ? null : new BigDecimal(body.get("rentAmount").toString());
        return ApiResponse.ok(contractService.transfer(contractId, newTenantId, rent), TraceIdUtil.get());
    }

    @PostMapping("/contracts/{contractId}/change-party")
    @RequiresPerm("contract.ledger:update")
    @Audited(module = "contract", action = "change_party")
    public ApiResponse<Contract> changeParty(@PathVariable Long contractId, @RequestBody Map<String, Object> body) {
        assertContract(contractId);
        Long newTenantId = Long.valueOf(body.get("newTenantId").toString());
        return ApiResponse.ok(contractService.changeParty(contractId, newTenantId), TraceIdUtil.get());
    }

    @PostMapping("/contracts/{contractId}/change-area")
    @RequiresPerm("contract.ledger:update")
    @Audited(module = "contract", action = "change_area")
    public ApiResponse<Contract> changeArea(@PathVariable Long contractId, @RequestBody Map<String, Object> body) {
        assertContract(contractId);
        BigDecimal area = new BigDecimal(body.get("leaseArea").toString());
        BigDecimal rent = body.get("rentAmount") == null ? null : new BigDecimal(body.get("rentAmount").toString());
        return ApiResponse.ok(contractService.changeArea(contractId, area, rent), TraceIdUtil.get());
    }

    @PostMapping("/contracts/{contractId}/early-terminate")
    @RequiresPerm("contract.ledger:update")
    @Audited(module = "contract", action = "early_terminate")
    public ApiResponse<Map<String, Object>> earlyTerminate(
            @PathVariable Long contractId, @RequestBody Map<String, Object> body) {
        BigDecimal penalty = body.get("penaltyAmount") == null ? null
                : new BigDecimal(body.get("penaltyAmount").toString());
        return ApiResponse.ok(contractService.earlyTerminate(contractId,
                (String) body.get("reason"), penalty), TraceIdUtil.get());
    }

    @PostMapping("/contracts/{contractId}/void")
    @RequiresPerm("contract.ledger:update")
    @Audited(module = "contract", action = "void")
    public ApiResponse<Void> voidContract(@PathVariable Long contractId) {
        assertContract(contractId);
        contractService.voidContract(contractId);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    // ---- 电子签 ----
    @PostMapping("/contracts/{contractId}/esign/start")
    @RequiresPerm("contract.ledger:update")
    @Audited(module = "esign", action = "start")
    public ApiResponse<Map<String, Object>> esignStart(@PathVariable Long contractId) {
        assertContract(contractId);
        return ApiResponse.ok(eSignService.start(contractId), TraceIdUtil.get());
    }

    @GetMapping("/contracts/{contractId}/esign")
    @RequiresPerm("contract.ledger:view")
    public ApiResponse<Map<String, Object>> esignStatus(@PathVariable Long contractId) {
        assertContract(contractId);
        return ApiResponse.ok(eSignService.status(contractId), TraceIdUtil.get());
    }

    // ---- 退租 ----
    @PostMapping("/contracts/{contractId}/vacate")
    @RequiresPerm("contract.vacate:create")
    @Audited(module = "vacate", action = "apply")
    public ApiResponse<VacateOrder> applyVacate(@PathVariable Long contractId, @RequestBody Map<String, Object> body) {
        assertContract(contractId);
        LocalDate date = body.get("expectedVacateDate") == null ? null
                : LocalDate.parse(body.get("expectedVacateDate").toString());
        return ApiResponse.ok(vacateService.apply(contractId, (String) body.get("reason"), date), TraceIdUtil.get());
    }

    @GetMapping("/vacate-orders")
    @RequiresPerm("contract.vacate:view")
    public ApiResponse<List<VacateOrder>> vacateOrders(@RequestParam(required = false) Long contractId) {
        return ApiResponse.ok(vacateService.list(contractId), TraceIdUtil.get());
    }

    @PostMapping("/vacate-orders/{vacateOrderId}/inspection")
    @RequiresPerm("contract.vacate:update")
    @Audited(module = "vacate", action = "inspection")
    public ApiResponse<VacateOrder> inspection(@PathVariable Long vacateOrderId, @RequestBody Map<String, Object> body) {
        assertVacateOrder(vacateOrderId);
        BigDecimal water = toDecimal(body.get("waterReading"));
        BigDecimal electric = toDecimal(body.get("electricReading"));
        return ApiResponse.ok(vacateService.submitInspection(vacateOrderId, water, electric,
                (String) body.get("remark"),
                body.get("fileIds") == null ? null : body.get("fileIds").toString()), TraceIdUtil.get());
    }

    @PostMapping("/vacate-orders/{vacateOrderId}/settlement")
    @RequiresPerm("contract.vacate:update")
    @Audited(module = "vacate", action = "settle")
    public ApiResponse<VacateOrder> settlement(@PathVariable Long vacateOrderId, @RequestBody Map<String, Object> body) {
        assertVacateOrder(vacateOrderId);
        return ApiResponse.ok(vacateService.settle(vacateOrderId, toDecimal(body.get("damageCompensation"))), TraceIdUtil.get());
    }

    private BigDecimal toDecimal(Object v) {
        return v == null ? null : new BigDecimal(v.toString());
    }

    private Contract mapContract(Map<String, Object> body) {
        Contract c = new Contract();
        if (body.get("assetId") != null) {
            c.setAssetId(Long.valueOf(body.get("assetId").toString()));
        }
        if (body.get("tenantId") != null) {
            c.setTenantId(Long.valueOf(body.get("tenantId").toString()));
        }
        if (body.get("startDate") != null) {
            c.setStartDate(LocalDate.parse(body.get("startDate").toString()));
        }
        if (body.get("endDate") != null) {
            c.setEndDate(LocalDate.parse(body.get("endDate").toString()));
        }
        if (body.get("rentAmount") != null) {
            c.setRentAmount(new BigDecimal(body.get("rentAmount").toString()));
        }
        if (body.get("depositAmount") != null) {
            c.setDepositAmount(new BigDecimal(body.get("depositAmount").toString()));
        }
        if (body.get("leaseArea") != null) {
            c.setLeaseArea(new BigDecimal(body.get("leaseArea").toString()));
        }
        if (body.get("rentType") != null) {
            c.setRentType(body.get("rentType").toString());
        }
        if (body.get("paymentCycle") != null) {
            c.setPaymentCycle(body.get("paymentCycle").toString());
        }
        if (body.get("remark") != null) {
            c.setRemark(body.get("remark").toString());
        }
        return c;
    }

    private void assertContract(Long contractId) {
        rbacService.assertCompanyAccess(SecurityUtils.current(), ownershipResolver.ofContract(contractId));
    }

    private void assertVacateOrder(Long vacateOrderId) {
        rbacService.assertCompanyAccess(SecurityUtils.current(), ownershipResolver.ofVacateOrder(vacateOrderId));
    }
}
