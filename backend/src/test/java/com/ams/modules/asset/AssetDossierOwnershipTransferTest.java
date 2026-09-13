package com.ams.modules.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ams.modules.asset.dto.AssetDossier;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetCertificateMapper;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.AssetStructureLogMapper;
import com.ams.modules.asset.mapper.AssetTransferMapper;
import com.ams.modules.asset.mapper.LeaseControlLogMapper;
import com.ams.modules.asset.mapper.MortgageMapper;
import com.ams.modules.asset.service.AssetDossierService;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.contract.mapper.VacateOrderMapper;
import com.ams.modules.disposal.mapper.DisposalOrderMapper;
import com.ams.modules.dunning.mapper.DunningRecordMapper;
import com.ams.modules.evaluation.mapper.EvaluationRequestMapper;
import com.ams.modules.lease.mapper.LeaseListingMapper;
import com.ams.modules.maintenance.mapper.InspectionRecordMapper;
import com.ams.modules.maintenance.mapper.RepairOrderMapper;
import com.ams.modules.meter.mapper.MeterMapper;
import com.ams.modules.occupation.mapper.OccupationOrderMapper;
import com.ams.modules.ownership.entity.OwnershipTransfer;
import com.ams.modules.ownership.entity.OwnershipTransferAsset;
import com.ams.modules.ownership.mapper.OwnershipTransferAssetMapper;
import com.ams.modules.ownership.mapper.OwnershipTransferMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 一物一档接入权属流转（设计 §5.7）。
 *
 * <p>两条断言各自对应一个易错点：
 * <ul>
 *   <li>权属流转**单列一段**而不是并入调拨 —— 合成一类后档案里就无法区分
 *       「调拨过」与「产权转出过」（D11）；</li>
 *   <li>反查走明细表（{@code asset_id} 上有索引）：走主单 LIKE 会在单据量大时全表扫。</li>
 * </ul>
 */
class AssetDossierOwnershipTransferTest {

    private static final long ASSET_ID = 11L;

    /** 其余 14 个业务段都返回空集合：本用例只关心权属流转那一段。 */
    private final AssetMapper assetMapper = mock(AssetMapper.class);
    private final LeaseControlLogMapper leaseControlLogMapper = mock(LeaseControlLogMapper.class);
    private final AssetCertificateMapper certificateMapper = mock(AssetCertificateMapper.class);
    private final MortgageMapper mortgageMapper = mock(MortgageMapper.class);
    private final AssetTransferMapper transferMapper = mock(AssetTransferMapper.class);
    private final AssetStructureLogMapper structureLogMapper = mock(AssetStructureLogMapper.class);
    private final ContractMapper contractMapper = mock(ContractMapper.class);
    private final VacateOrderMapper vacateOrderMapper = mock(VacateOrderMapper.class);
    private final BillMapper billMapper = mock(BillMapper.class);
    private final DunningRecordMapper dunningRecordMapper = mock(DunningRecordMapper.class);
    private final RepairOrderMapper repairOrderMapper = mock(RepairOrderMapper.class);
    private final InspectionRecordMapper inspectionRecordMapper = mock(InspectionRecordMapper.class);
    private final DisposalOrderMapper disposalOrderMapper = mock(DisposalOrderMapper.class);
    private final OccupationOrderMapper occupationOrderMapper = mock(OccupationOrderMapper.class);
    private final EvaluationRequestMapper evaluationRequestMapper = mock(EvaluationRequestMapper.class);
    private final LeaseListingMapper leaseListingMapper = mock(LeaseListingMapper.class);
    private final MeterMapper meterMapper = mock(MeterMapper.class);
    private final OwnershipTransferMapper ownershipTransferMapper = mock(OwnershipTransferMapper.class);
    private final OwnershipTransferAssetMapper ownershipTransferAssetMapper =
            mock(OwnershipTransferAssetMapper.class);

    @BeforeEach
    void setUp() {
        Asset asset = new Asset();
        asset.setId(ASSET_ID);
        asset.setAssetNo("A-11");
        asset.setName("资产11");
        asset.setLifecycleStatus("in_book");
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset);
        // dossier 的各段落都会 setXxx(mapper.selectList(...))，未桩住会传 null 并在后续 NPE
        when(leaseControlLogMapper.selectList(any())).thenReturn(List.of());
        when(certificateMapper.selectList(any())).thenReturn(List.of());
        when(mortgageMapper.selectList(any())).thenReturn(List.of());
        when(transferMapper.selectList(any())).thenReturn(List.of());
        when(structureLogMapper.selectList(any())).thenReturn(List.of());
        when(contractMapper.selectList(any())).thenReturn(List.of());
        when(billMapper.selectList(any())).thenReturn(List.of());
        when(repairOrderMapper.selectList(any())).thenReturn(List.of());
        when(inspectionRecordMapper.selectList(any())).thenReturn(List.of());
        when(disposalOrderMapper.selectList(any())).thenReturn(List.of());
        when(occupationOrderMapper.selectList(any())).thenReturn(List.of());
        when(evaluationRequestMapper.selectList(any())).thenReturn(List.of());
        when(leaseListingMapper.selectList(any())).thenReturn(List.of());
        when(meterMapper.selectList(any())).thenReturn(List.of());
        when(ownershipTransferAssetMapper.selectList(any())).thenReturn(List.of());
        when(ownershipTransferMapper.selectList(any())).thenReturn(List.of());
    }

    private AssetDossierService newService() {
        return new AssetDossierService(
                assetMapper, leaseControlLogMapper, certificateMapper, mortgageMapper, transferMapper,
                structureLogMapper, contractMapper, vacateOrderMapper, billMapper, dunningRecordMapper,
                repairOrderMapper, inspectionRecordMapper, disposalOrderMapper, occupationOrderMapper,
                evaluationRequestMapper, leaseListingMapper, meterMapper,
                ownershipTransferMapper, ownershipTransferAssetMapper);
    }

    private static OwnershipTransferAsset detail(long transferId) {
        OwnershipTransferAsset row = new OwnershipTransferAsset();
        row.setTransferId(transferId);
        row.setAssetId(ASSET_ID);
        return row;
    }

    private static OwnershipTransfer transfer(long id, String status, String reason) {
        OwnershipTransfer t = new OwnershipTransfer();
        t.setId(id);
        t.setStatus(status);
        t.setReason(reason);
        t.setFromCompanyId(2L);
        t.setToCompanyId(3L);
        t.setCreatedAt(LocalDateTime.of(2026, 9, 13, 10, 0));
        return t;
    }

    @Test
    @DisplayName("档案里有权属流转段，且时间线单独一类（不并入调拨）")
    void dossierExposesOwnershipTransfersAsOwnCategory() {
        when(ownershipTransferAssetMapper.selectList(any())).thenReturn(List.of(detail(88L)));
        when(ownershipTransferMapper.selectList(any())).thenReturn(List.of(
                transfer(88L, "completed", "集团内划拨")));

        AssetDossier dossier = newService().getDossier(ASSET_ID);

        assertThat(dossier.getOwnershipTransfers()).hasSize(1);
        assertThat(dossier.getOwnershipTransfers().get(0).getId()).isEqualTo(88L);

        List<AssetDossier.TimelineItem> timeline = dossier.getTimeline();
        assertThat(timeline).anySatisfy(t -> {
            assertThat(t.getCategory()).isEqualTo("ownership_transfer");
            assertThat(t.getTitle()).isEqualTo("权属流转 #88");
            assertThat(t.getStatus()).isEqualTo("completed");
            assertThat(t.getRemark()).isEqualTo("集团内划拨");
        });
        assertThat(timeline)
                .as("调拨段仍走 transfer 分类，两类不能混成一条")
                .noneSatisfy(t -> assertThat(t.getCategory()).isEqualTo("transfer"));
    }

    @Test
    @DisplayName("该资产没有被流转过时：段为空，且不去查主单（避免空的 in () 查询）")
    void noDetailRowsSkipsMainQuery() {
        AssetDossier dossier = newService().getDossier(ASSET_ID);

        assertThat(dossier.getOwnershipTransfers()).isEmpty();
        org.mockito.Mockito.verify(ownershipTransferMapper, org.mockito.Mockito.never())
                .selectList(any());
    }
}
