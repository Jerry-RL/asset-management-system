package com.ams.modules.disposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.modules.asset.service.CertificateService;
import com.ams.modules.asset.service.LeaseControlService;
import com.ams.modules.billing.service.PaymentService;
import com.ams.modules.disposal.dto.DisposalOrderInput;
import com.ams.modules.disposal.entity.DisposalOrder;
import com.ams.modules.disposal.mapper.DisposalOrderMapper;
import com.ams.modules.disposal.service.DisposalService;
import com.ams.modules.finance.service.ReconcileService;
import com.ams.modules.record.dto.AttachmentRef;
import com.ams.modules.record.service.RecordSheetService;
import com.ams.platform.approval.ApprovalEngine;
import com.ams.platform.event.DomainEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 资产处置单「全量同步」的草稿语义（{@code PUT /assets/{assetId}/disposals}）。
 *
 * <p>落点：资产表单第 3 步的处置面板与项目/分区台账共用同一套「改动攒在本地、保存时一次提交」
 * 的交互，但资产的落点是 {@code disposal_order}（带状态机），因此这里要钉死三条规则 ——
 * 它们**每一条都能被一次不小心的 diff 实现悄悄破掉**：
 *
 * <ol>
 *   <li>只有草稿会被改 / 删：审批中及之后的单子必须原样留在库里（改动它们等于绕过审批）；</li>
 *   <li>请求体里的 id 必须属于该资产（否则转发别人的 id 就能改到别的资产的处置数据）；</li>
 *   <li>新增的单子一律建成 {@code draft} 且先过抵押校验（与 {@code POST /disposals} 同口径）。</li>
 * </ol>
 *
 * <p>桩刻意不模拟 SQL：{@code LambdaQueryWrapper} 的条件读不出来，因此「查出来什么」
 * 一律显式桩定，并配反向对照用例（否则「恰好返回空列表」会让断言因为错误的原因通过）。
 */
class AssetDisposalSyncServiceTest {

    private static final long ASSET_ID = 7L;

    private final DisposalOrderMapper disposalOrderMapper = mock(DisposalOrderMapper.class);
    private final CertificateService certificateService = mock(CertificateService.class);
    private final RecordSheetService recordSheetService = mock(RecordSheetService.class);

    private final DisposalService service = new DisposalService(
            disposalOrderMapper,
            mock(LeaseControlService.class),
            mock(ApprovalEngine.class),
            certificateService,
            mock(PaymentService.class),
            mock(ReconcileService.class),
            mock(ObjectMapper.class),
            mock(DomainEventPublisher.class),
            recordSheetService);

    private void stubExisting(DisposalOrder... rows) {
        List<DisposalOrder> list = new ArrayList<>(List.of(rows));
        // 同一个 mapper 查询既服务「diff 前的现状」也服务结尾的 listByAsset 回显
        when(disposalOrderMapper.selectList(any())).thenReturn(list);
        when(recordSheetService.orderAttachments(anyLong())).thenReturn(new ArrayList<>());
    }

    @Test
    @DisplayName("无 id 的行建成草稿：assetId 取自路径、status 服务端强制 draft、先过抵押校验")
    void newRowBecomesDraft() {
        stubExisting();
        DisposalOrderInput input = new DisposalOrderInput();
        input.setDisposalType("sale");
        input.setActualAmount(new BigDecimal("120.50"));
        input.setDisposalDate(LocalDate.of(2026, 8, 2));
        input.setAttachments(List.of(attachment(31L)));

        service.syncForAsset(ASSET_ID, List.of(input));

        ArgumentCaptor<DisposalOrder> captor = ArgumentCaptor.forClass(DisposalOrder.class);
        verify(disposalOrderMapper).insert(captor.capture());
        DisposalOrder inserted = captor.getValue();
        assertThat(inserted.getAssetId()).isEqualTo(ASSET_ID);
        assertThat(inserted.getStatus()).isEqualTo("draft");
        assertThat(inserted.getActualAmount()).isEqualByComparingTo("120.50");
        assertThat(inserted.getDisposalDate()).isEqualTo(LocalDate.of(2026, 8, 2));
        verify(certificateService).assertNotMortgaged(ASSET_ID);
        // 附件按「单子落库后的 id」写，绝不能因为还没有 id 就丢掉附件
        verify(recordSheetService).syncOrderAttachments(any(), eq(input.getAttachments()));
    }

    @Test
    @DisplayName("已有 id 且是草稿：就地更新，附件一起同步")
    void draftRowIsUpdated() {
        DisposalOrder draft = order(11L, ASSET_ID, "draft");
        stubExisting(draft);
        DisposalOrderInput input = new DisposalOrderInput();
        input.setId(11L);
        input.setDisposalType("scrap");

        service.syncForAsset(ASSET_ID, List.of(input));

        ArgumentCaptor<DisposalOrder> captor = ArgumentCaptor.forClass(DisposalOrder.class);
        verify(disposalOrderMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(11L);
        assertThat(captor.getValue().getDisposalType()).isEqualTo("scrap");
        assertThat(captor.getValue().getStatus()).isEqualTo("draft");
        verify(disposalOrderMapper, never()).insert(any(DisposalOrder.class));
        verify(recordSheetService).syncOrderAttachments(eq(11L), any());
    }

    @Test
    @DisplayName("已有 id 但不是草稿：原样不动 —— 审批中及之后的单子只能走流程端点")
    void nonDraftRowIsUntouched() {
        DisposalOrder approving = order(12L, ASSET_ID, "approving");
        approving.setDisposalType("sale");
        stubExisting(approving);
        DisposalOrderInput input = new DisposalOrderInput();
        input.setId(12L);
        input.setDisposalType("scrap");

        service.syncForAsset(ASSET_ID, List.of(input));

        verify(disposalOrderMapper, never()).updateById(any(DisposalOrder.class));
        verify(disposalOrderMapper, never()).insert(any(DisposalOrder.class));
        assertThat(approving.getDisposalType()).isEqualTo("sale");
    }

    @Test
    @DisplayName("库中的草稿未出现在请求体里：清附件后真删除")
    void removedDraftIsDeleted() {
        DisposalOrder draft = order(13L, ASSET_ID, "draft");
        stubExisting(draft);

        service.syncForAsset(ASSET_ID, List.of());

        verify(recordSheetService).syncOrderAttachments(13L, List.of());
        verify(disposalOrderMapper).deleteById(13L);
    }

    @Test
    @DisplayName("库中的非草稿未出现在请求体里：不删（前端面板本就只读，这是兜底）")
    void removedNonDraftIsKept() {
        DisposalOrder completed = order(14L, ASSET_ID, "completed");
        stubExisting(completed);

        service.syncForAsset(ASSET_ID, List.of());

        verify(disposalOrderMapper, never()).deleteById(anyLong());
        verify(disposalOrderMapper, never()).updateById(any(DisposalOrder.class));
    }

    @Test
    @DisplayName("id 不属于该资产：400 且没有任何写入 —— 否则转发 id 就能改别的资产")
    void foreignIdIsRejected() {
        // 桩不模拟 SQL：真实的跨资产场景是「按 asset_id 过滤后查不到这条」，
        // 因此这里用「查不到」来复现它（给一个别的资产的 id 也能得到同一个分支）。
        stubExisting();
        DisposalOrderInput input = new DisposalOrderInput();
        input.setId(15L);

        assertThatThrownBy(() -> service.syncForAsset(ASSET_ID, List.of(input)))
                .isInstanceOf(AppException.class);

        verify(disposalOrderMapper, never()).updateById(any(DisposalOrder.class));
        verify(disposalOrderMapper, never()).insert(any(DisposalOrder.class));
        verify(disposalOrderMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("返回的是服务端规范结果（带 id 与回显附件），不是请求体的回声")
    void returnsCanonicalView() {
        stubExisting(order(16L, ASSET_ID, "draft"));

        var views = service.syncForAsset(ASSET_ID, List.of());

        assertThat(views).hasSize(1);
        assertThat(views.get(0).getId()).isEqualTo(16L);
        assertThat(views.get(0).getStatus()).isEqualTo("draft");
    }

    private DisposalOrder order(Long id, Long assetId, String status) {
        DisposalOrder order = new DisposalOrder();
        order.setId(id);
        order.setAssetId(assetId);
        order.setStatus(status);
        return order;
    }

    private AttachmentRef attachment(Long fileId) {
        AttachmentRef ref = new AttachmentRef();
        ref.setFileId(fileId);
        return ref;
    }
}
