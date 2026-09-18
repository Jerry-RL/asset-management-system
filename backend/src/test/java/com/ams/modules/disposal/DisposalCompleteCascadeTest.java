package com.ams.modules.disposal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.modules.asset.service.CertificateService;
import com.ams.modules.asset.service.LeaseControlService;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.service.PaymentService;
import com.ams.modules.disposal.entity.DisposalOrder;
import com.ams.modules.disposal.mapper.DisposalOrderMapper;
import com.ams.modules.disposal.service.DisposalService;
import com.ams.modules.finance.entity.FinanceVoucher;
import com.ams.modules.finance.service.ReconcileService;
import com.ams.modules.record.DisposalCascadePort;
import com.ams.modules.record.DisposalCascadeSnapshot;
import com.ams.modules.record.service.RecordSheetService;
import com.ams.platform.approval.ApprovalEngine;
import com.ams.platform.event.DomainEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 资产级处置「完成」必须级联（V57）。
 *
 * <p>本用例存在的意义：{@code complete} 是本仓最容易「加一行忘了改另一处」的方法 ——
 * 它已经做了损益、收款、凭证、租控、事件五件事。级联若漏掉，表现是
 * **资产处置显示已完成、但它仍属于原产权公司**，界面上一切正常，只有对账时才会发现。
 *
 * <p>只断言「级联被以正确的参数调用」：级联本身的语义由
 * {@code AssetDisposalRecordServiceTest} 覆盖，两处合起来才是完整链路。
 */
class DisposalCompleteCascadeTest {

    private static final long ORDER_ID = 55L;
    private static final long ASSET_ID = 7L;

    private final DisposalOrderMapper disposalOrderMapper = mock(DisposalOrderMapper.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final ReconcileService reconcileService = mock(ReconcileService.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final DisposalCascadePort disposalCascadePort = mock(DisposalCascadePort.class);

    private final DisposalService service = new DisposalService(
            disposalOrderMapper,
            mock(LeaseControlService.class),
            mock(ApprovalEngine.class),
            mock(CertificateService.class),
            paymentService,
            reconcileService,
            objectMapper,
            mock(DomainEventPublisher.class),
            mock(RecordSheetService.class),
            disposalCascadePort);

    @Test
    @DisplayName("完成即级联：以资产层级 + 处置单 id 调用，金额单位是元")
    void completeCascadesWithOrderMetadata() throws Exception {
        DisposalOrder order = new DisposalOrder();
        order.setId(ORDER_ID);
        order.setAssetId(ASSET_ID);
        order.setStatus("executing");
        order.setDisposalType("sale");
        order.setActualAmount(new BigDecimal("120.50"));
        order.setDisposalUserName("张三");
        order.setRemark("闲置处置");
        when(disposalOrderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(paymentService.registerConfirmed(any(), any(), any(), any(), any()))
                .thenReturn(new Payment());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(reconcileService.createVoucher(any(), any(), any())).thenReturn(new FinanceVoucher());

        service.complete(ORDER_ID);

        verify(disposalCascadePort).cascadeDispose(
                eq(DisposalCascadePort.TARGET_ASSET),
                eq(ASSET_ID),
                eq(new DisposalCascadeSnapshot("sale", new BigDecimal("120.50"),
                        DisposalCascadeSnapshot.UNIT_YUAN, null, null, "张三", "闲置处置")),
                eq(ORDER_ID),
                isNull());
    }

    @Test
    @DisplayName("状态不合法时提前 409：绝不先级联再报错（否则资产已被处置、单据却停在执行中）")
    void invalidStatusDoesNotCascade() {
        DisposalOrder order = new DisposalOrder();
        order.setId(ORDER_ID);
        order.setAssetId(ASSET_ID);
        order.setStatus("draft");
        when(disposalOrderMapper.selectById(ORDER_ID)).thenReturn(order);

        assertThatThrownBy(() -> service.complete(ORDER_ID)).isInstanceOf(AppException.class);

        verify(disposalCascadePort, never())
                .cascadeDispose(any(), any(), any(), any(), any());
    }
}
