package com.ams.platform.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ams.common.exception.GlobalExceptionHandler;
import com.ams.modules.billing.controller.BillingController;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.entity.RefundOrder;
import com.ams.modules.billing.service.AllocationService;
import com.ams.modules.billing.service.BillService;
import com.ams.modules.billing.service.PaymentService;
import com.ams.modules.billing.service.PrepayService;
import com.ams.modules.billing.service.RefundService;
import com.ams.modules.billing.service.WechatPayService;
import com.ams.modules.contract.controller.ContractController;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.service.ContractService;
import com.ams.modules.contract.service.ESignService;
import com.ams.modules.contract.service.VacateService;
import com.ams.modules.invoice.controller.InvoiceController;
import com.ams.modules.invoice.entity.Invoice;
import com.ams.modules.invoice.service.InvoiceService;
import com.ams.modules.pricing.mapper.PaymentPlanMapper;
import com.ams.support.RbacFixtures;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 验收标准 #8 的可执行用例：<strong>未授予对应动作的账号调用 收费 / 退款 / 发票 / 合同
 * 的写接口返回 403；授予后成功 —— 不得只测资产台账。</strong>
 *
 * <h2>为什么用真实控制器 + 真实拦截器</h2>
 * 权限码不是测试里手抄的字符串，而是由 {@link PermissionInterceptor} 从
 * <strong>控制器方法上的 {@code @RequiresPerm} 本身</strong>解析出来的。因此：
 * <ul>
 *   <li>注解被删 → 默认档下拦截器放行 → 403 用例收到 200，失败；</li>
 *   <li>动作被改（如 {@code :create} 改成 {@code :update}）→ 授予用例被拒 → 200 用例收到 403，失败。</li>
 * </ul>
 * 两个方向合起来才能锁住「注解与预期不一致」这类回归 —— 只断言 403 是拦不住的：
 * 动作写错时用户同样没有该动作，403 用例会照常通过。
 *
 * <h2>为什么只把服务层换成桩</h2>
 * {@code assertPermission} / {@code assertCompanyAccess} 都走真实的 {@link RbacService}
 * 与真实的 {@link LoginUser} 判定（{@code companyScope} 完全由 LoginUser 字段决定，
 * 不查库），只有「服务层」和「归属解析」被替换，避免依赖数据库 schema。
 *
 * <p>另外每个 403 用例都断言<strong>写操作从未发生</strong>：403 若发生在副作用之后，
 * 状态码再正确也没有意义。
 */
class WriteEndpointPermissionTest {

    /** 归属解析出来的公司；被测账号 dataScope=all 且无排除，因此不受限、可通过归属校验。 */
    private static final long VISIBLE_COMPANY = 2L;

    private RbacService rbacService;

    @BeforeEach
    void setUp() {
        // 用夹具装配真实 RbacService（只替换 mapper）；判定逻辑全部是生产代码
        rbacService = RbacFixtures.standard().newService(1L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // =====================================================================
    // 收费：POST /api/v1/billing/bills/{billId}/collect
    // =====================================================================

    @Nested
    @DisplayName("收费 / finance.payment:create")
    class Collect {

        @Test
        @DisplayName("未授予 finance.payment:create -> 403，且收款服务从未被调用")
        void withoutPermissionIsForbidden() throws Exception {
            Billing billing = billingController();
            login(Set.of());

            mvc(billing.controller())
                    .perform(post("/api/v1/billing/bills/7/collect")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"100.00\",\"paymentMethod\":\"cash\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(40300));

            verify(billing.paymentService(), never())
                    .registerConfirmed(any(), any(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("授予 finance.payment:create -> 成功，收款服务被调用一次")
        void withPermissionSucceeds() throws Exception {
            Billing billing = billingController();
            login(Set.of("finance.payment:create"));

            mvc(billing.controller())
                    .perform(post("/api/v1/billing/bills/7/collect")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"100.00\",\"paymentMethod\":\"cash\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));

            verify(billing.paymentService())
                    .registerConfirmed(eq(11L), eq(22L), any(), eq("cash"), eq("pc"));
        }
    }

    // =====================================================================
    // 退款：POST /api/v1/refunds
    // =====================================================================

    @Nested
    @DisplayName("退款 / finance.refund:create")
    class Refund {

        @Test
        @DisplayName("未授予 finance.refund:create -> 403，且退款服务从未被调用")
        void withoutPermissionIsForbidden() throws Exception {
            Billing billing = billingController();
            login(Set.of());

            mvc(billing.controller())
                    .perform(post("/api/v1/refunds")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"paymentId\":\"5\",\"amount\":\"50.00\","
                                    + "\"reason\":\"客户退款\",\"channel\":\"cash\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(40300));

            verify(billing.refundService(), never()).apply(any(), any(), any(), any());
        }

        @Test
        @DisplayName("授予 finance.refund:create -> 成功，退款服务被调用一次")
        void withPermissionSucceeds() throws Exception {
            Billing billing = billingController();
            login(Set.of("finance.refund:create"));

            mvc(billing.controller())
                    .perform(post("/api/v1/refunds")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"paymentId\":\"5\",\"amount\":\"50.00\","
                                    + "\"reason\":\"客户退款\",\"channel\":\"cash\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));

            verify(billing.refundService()).apply(eq(5L), any(), eq("客户退款"), eq("cash"));
        }
    }

    // =====================================================================
    // 发票：POST /api/v1/invoices
    // =====================================================================

    @Nested
    @DisplayName("发票 / finance.invoice:create")
    class InvoiceIssue {

        @Test
        @DisplayName("未授予 finance.invoice:create -> 403，且开票服务从未被调用")
        void withoutPermissionIsForbidden() throws Exception {
            InvoiceFixture fixture = invoiceController();
            login(Set.of());

            mvc(fixture.controller())
                    .perform(post("/api/v1/invoices")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"paymentId\":\"5\",\"titleId\":\"9\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(40300));

            verify(fixture.invoiceService(), never()).issue(any(), any(), any());
        }

        @Test
        @DisplayName("授予 finance.invoice:create -> 成功，开票服务被调用一次")
        void withPermissionSucceeds() throws Exception {
            InvoiceFixture fixture = invoiceController();
            login(Set.of("finance.invoice:create"));

            mvc(fixture.controller())
                    .perform(post("/api/v1/invoices")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"paymentId\":\"5\",\"titleId\":\"9\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));

            verify(fixture.invoiceService()).issue(eq(5L), eq(9L), isNull());
        }
    }

    // =====================================================================
    // 合同：POST /api/v1/contracts
    // =====================================================================

    @Nested
    @DisplayName("合同 / contract.ledger:create")
    class ContractCreate {

        @Test
        @DisplayName("未授予 contract.ledger:create -> 403，且合同服务从未被调用")
        void withoutPermissionIsForbidden() throws Exception {
            ContractFixture fixture = contractController();
            login(Set.of());

            mvc(fixture.controller())
                    .perform(post("/api/v1/contracts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"assetId\":\"3\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(40300));

            verify(fixture.contractService(), never()).create(any(), anyBoolean());
        }

        @Test
        @DisplayName("授予 contract.ledger:create -> 成功，合同服务被调用一次")
        void withPermissionSucceeds() throws Exception {
            ContractFixture fixture = contractController();
            login(Set.of("contract.ledger:create"));

            mvc(fixture.controller())
                    .perform(post("/api/v1/contracts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"assetId\":\"3\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));

            verify(fixture.contractService()).create(any(), eq(false));
        }
    }

    // =====================================================================
    // 夹具
    // =====================================================================

    /**
     * 以非 {@code super_admin} 身份登录。
     *
     * <p>{@code dataScope} 取 {@code all} 是刻意的：本用例测的是「动作授权」，
     * 不能让数据范围成为混淆变量（数据范围另有 {@code RbacDataScopeTest} 覆盖）。
     * {@code roles} 不含 {@code super_admin}，否则拦截器会直接放行、测试变成空转。
     */
    private void login(Set<String> permissions) {
        LoginUser user = LoginUser.builder()
                .userId(9001L)
                .username("perm-probe")
                .name("权限探针")
                .companyId(VISIBLE_COMPANY)
                .homeCompanyId(VISIBLE_COMPANY)
                .departmentId(1L)
                .clientType("admin")
                .roles(Set.of("finance"))
                .permissions(permissions)
                .dataScope("all")
                .companyScoped(false)
                .excludedCompanyIds(Set.of())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    /** 独立容器：真实控制器 + 真实拦截器 + 真实异常处理（把 AppException 翻成 HTTP 403 + code 40300）。 */
    private MockMvc mvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(rbacService, false))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private record Billing(BillingController controller, PaymentService paymentService,
                           RefundService refundService) {
    }

    private Billing billingController() {
        BillService billService = mock(BillService.class);
        PaymentService paymentService = mock(PaymentService.class);
        AllocationService allocationService = mock(AllocationService.class);
        PrepayService prepayService = mock(PrepayService.class);
        RefundService refundService = mock(RefundService.class);
        PaymentPlanMapper planMapper = mock(PaymentPlanMapper.class);
        WechatPayService wechatPayService = mock(WechatPayService.class);
        OwnershipResolver ownershipResolver = mock(OwnershipResolver.class);

        Bill bill = new Bill();
        bill.setContractId(11L);
        bill.setTenantId(22L);
        when(billService.get(any())).thenReturn(bill);
        when(ownershipResolver.ofBill(any())).thenReturn(VISIBLE_COMPANY);
        when(ownershipResolver.ofPayment(any())).thenReturn(VISIBLE_COMPANY);
        when(paymentService.registerConfirmed(any(), any(), any(), anyString(), anyString()))
                .thenReturn(new Payment());
        when(refundService.apply(any(), any(), any(), any())).thenReturn(new RefundOrder());

        return new Billing(new BillingController(billService, paymentService, allocationService,
                prepayService, refundService, planMapper, wechatPayService, ownershipResolver,
                rbacService), paymentService, refundService);
    }

    private record InvoiceFixture(InvoiceController controller, InvoiceService invoiceService) {
    }

    private InvoiceFixture invoiceController() {
        InvoiceService invoiceService = mock(InvoiceService.class);
        OwnershipResolver ownershipResolver = mock(OwnershipResolver.class);

        when(ownershipResolver.ofPayment(any())).thenReturn(VISIBLE_COMPANY);
        when(invoiceService.issue(any(), any(), any())).thenReturn(new Invoice());

        return new InvoiceFixture(new InvoiceController(invoiceService, ownershipResolver, rbacService),
                invoiceService);
    }

    private record ContractFixture(ContractController controller, ContractService contractService) {
    }

    private ContractFixture contractController() {
        ContractService contractService = mock(ContractService.class);
        VacateService vacateService = mock(VacateService.class);
        ESignService eSignService = mock(ESignService.class);
        OwnershipResolver ownershipResolver = mock(OwnershipResolver.class);

        when(ownershipResolver.ofAsset(any())).thenReturn(VISIBLE_COMPANY);
        when(contractService.create(any(), anyBoolean())).thenReturn(new Contract());

        return new ContractFixture(new ContractController(contractService, vacateService, eSignService,
                ownershipResolver, rbacService), contractService);
    }
}
