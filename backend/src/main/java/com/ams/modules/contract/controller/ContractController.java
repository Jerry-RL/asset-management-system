package com.ams.modules.contract.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.entity.VacateOrder;
import com.ams.modules.contract.service.ContractService;
import com.ams.modules.contract.service.VacateService;
import com.ams.platform.approval.entity.ApprovalInstance;
import com.ams.platform.security.Audited;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    public ContractController(ContractService contractService, VacateService vacateService) {
        this.contractService = contractService;
        this.vacateService = vacateService;
    }

    @GetMapping("/contracts")
    public ApiResponse<PageResult<Contract>> contracts(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(contractService.page(page, pageSize, status, keyword, null), TraceIdUtil.get());
    }

    @GetMapping("/contracts/{contractId}")
    public ApiResponse<Contract> contract(@PathVariable Long contractId) {
        return ApiResponse.ok(contractService.get(contractId), TraceIdUtil.get());
    }

    @PostMapping("/contracts")
    @Audited(module = "contract", action = "create")
    public ApiResponse<Contract> create(@RequestBody Contract contract) {
        return ApiResponse.ok(contractService.create(contract), TraceIdUtil.get());
    }

    @PostMapping("/contracts/{contractId}/submit")
    @Audited(module = "contract", action = "submit")
    public ApiResponse<ApprovalInstance> submit(@PathVariable Long contractId) {
        return ApiResponse.ok(contractService.submit(contractId), TraceIdUtil.get());
    }

    @PostMapping("/contracts/{contractId}/approve")
    @Audited(module = "contract", action = "approve")
    public ApiResponse<ApprovalInstance> approve(@PathVariable Long contractId, @RequestBody Map<String, String> body) {
        String action = body.getOrDefault("action", "approve");
        String comment = body.get("comment");
        ApprovalInstance instance = contractService.approveContract(contractId, "approve".equals(action), comment);
        return ApiResponse.ok(instance, TraceIdUtil.get());
    }

    @PostMapping("/contracts/{contractId}/renew")
    @Audited(module = "contract", action = "renew")
    public ApiResponse<Contract> renew(@PathVariable Long contractId, @RequestBody Map<String, Object> body) {
        LocalDate newEndDate = body.get("endDate") == null ? null : LocalDate.parse(body.get("endDate").toString());
        BigDecimal rent = body.get("rentAmount") == null ? null : new BigDecimal(body.get("rentAmount").toString());
        return ApiResponse.ok(contractService.renew(contractId, newEndDate, rent), TraceIdUtil.get());
    }

    @PostMapping("/contracts/{contractId}/void")
    @Audited(module = "contract", action = "void")
    public ApiResponse<Void> voidContract(@PathVariable Long contractId) {
        contractService.voidContract(contractId);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    // ---- 退租 ----
    @PostMapping("/contracts/{contractId}/vacate")
    @Audited(module = "vacate", action = "apply")
    public ApiResponse<VacateOrder> applyVacate(@PathVariable Long contractId, @RequestBody Map<String, Object> body) {
        LocalDate date = body.get("expectedVacateDate") == null ? null
                : LocalDate.parse(body.get("expectedVacateDate").toString());
        return ApiResponse.ok(vacateService.apply(contractId, (String) body.get("reason"), date), TraceIdUtil.get());
    }

    @GetMapping("/vacate-orders")
    public ApiResponse<List<VacateOrder>> vacateOrders(@RequestParam(required = false) Long contractId) {
        return ApiResponse.ok(vacateService.list(contractId), TraceIdUtil.get());
    }

    @PostMapping("/vacate-orders/{vacateOrderId}/inspection")
    @Audited(module = "vacate", action = "inspection")
    public ApiResponse<VacateOrder> inspection(@PathVariable Long vacateOrderId, @RequestBody Map<String, Object> body) {
        BigDecimal water = toDecimal(body.get("waterReading"));
        BigDecimal electric = toDecimal(body.get("electricReading"));
        return ApiResponse.ok(vacateService.submitInspection(vacateOrderId, water, electric,
                (String) body.get("remark")), TraceIdUtil.get());
    }

    @PostMapping("/vacate-orders/{vacateOrderId}/settlement")
    @Audited(module = "vacate", action = "settle")
    public ApiResponse<VacateOrder> settlement(@PathVariable Long vacateOrderId, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(vacateService.settle(vacateOrderId, toDecimal(body.get("damageCompensation"))), TraceIdUtil.get());
    }

    private BigDecimal toDecimal(Object v) {
        return v == null ? null : new BigDecimal(v.toString());
    }
}
