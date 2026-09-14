package com.ams.modules.asset.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.dto.AssetDossier;
import com.ams.modules.asset.dto.AssetDossier.TimelineItem;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.AssetCertificate;
import com.ams.modules.asset.entity.AssetStructureLog;
import com.ams.modules.asset.entity.AssetTransfer;
import com.ams.modules.asset.entity.LeaseControlLog;
import com.ams.modules.asset.entity.Mortgage;
import com.ams.modules.asset.mapper.AssetCertificateMapper;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.AssetStructureLogMapper;
import com.ams.modules.asset.mapper.AssetTransferMapper;
import com.ams.modules.asset.mapper.LeaseControlLogMapper;
import com.ams.modules.asset.mapper.MortgageMapper;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.entity.VacateOrder;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.contract.mapper.VacateOrderMapper;
import com.ams.modules.disposal.entity.DisposalOrder;
import com.ams.modules.disposal.mapper.DisposalOrderMapper;
import com.ams.modules.dunning.entity.DunningRecord;
import com.ams.modules.dunning.mapper.DunningRecordMapper;
import com.ams.modules.evaluation.entity.EvaluationRequest;
import com.ams.modules.evaluation.mapper.EvaluationRequestMapper;
import com.ams.modules.lease.entity.LeaseListing;
import com.ams.modules.lease.mapper.LeaseListingMapper;
import com.ams.modules.maintenance.entity.InspectionRecord;
import com.ams.modules.maintenance.entity.RepairOrder;
import com.ams.modules.maintenance.mapper.InspectionRecordMapper;
import com.ams.modules.maintenance.mapper.RepairOrderMapper;
import com.ams.modules.meter.entity.Meter;
import com.ams.modules.meter.mapper.MeterMapper;
import com.ams.modules.occupation.entity.OccupationOrder;
import com.ams.modules.occupation.mapper.OccupationOrderMapper;
import com.ams.modules.ownership.entity.OwnershipTransfer;
import com.ams.modules.ownership.entity.OwnershipTransferAsset;
import com.ams.modules.ownership.mapper.OwnershipTransferAssetMapper;
import com.ams.modules.ownership.mapper.OwnershipTransferMapper;
import com.ams.modules.transferrecord.entity.AssetTransferRecord;
import com.ams.modules.transferrecord.entity.AssetTransferRecordAsset;
import com.ams.modules.transferrecord.mapper.AssetTransferRecordAssetMapper;
import com.ams.modules.transferrecord.mapper.AssetTransferRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 资产一物一档聚合（FR-AST-003）：某一资产的状态与全链路历史。
 */
@Service
public class AssetDossierService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int TIMELINE_LIMIT = 200;

    private final AssetMapper assetMapper;
    private final LeaseControlLogMapper leaseControlLogMapper;
    private final AssetCertificateMapper certificateMapper;
    private final MortgageMapper mortgageMapper;
    private final AssetTransferMapper transferMapper;
    private final AssetStructureLogMapper structureLogMapper;
    private final ContractMapper contractMapper;
    private final VacateOrderMapper vacateOrderMapper;
    private final BillMapper billMapper;
    private final DunningRecordMapper dunningRecordMapper;
    private final RepairOrderMapper repairOrderMapper;
    private final InspectionRecordMapper inspectionRecordMapper;
    private final DisposalOrderMapper disposalOrderMapper;
    private final OccupationOrderMapper occupationOrderMapper;
    private final EvaluationRequestMapper evaluationRequestMapper;
    private final LeaseListingMapper leaseListingMapper;
    private final MeterMapper meterMapper;
    private final OwnershipTransferMapper ownershipTransferMapper;
    private final OwnershipTransferAssetMapper ownershipTransferAssetMapper;
    private final AssetTransferRecordMapper transferRecordMapper;
    private final AssetTransferRecordAssetMapper transferRecordAssetMapper;

    public AssetDossierService(
            AssetMapper assetMapper,
            LeaseControlLogMapper leaseControlLogMapper,
            AssetCertificateMapper certificateMapper,
            MortgageMapper mortgageMapper,
            AssetTransferMapper transferMapper,
            AssetStructureLogMapper structureLogMapper,
            ContractMapper contractMapper,
            VacateOrderMapper vacateOrderMapper,
            BillMapper billMapper,
            DunningRecordMapper dunningRecordMapper,
            RepairOrderMapper repairOrderMapper,
            InspectionRecordMapper inspectionRecordMapper,
            DisposalOrderMapper disposalOrderMapper,
            OccupationOrderMapper occupationOrderMapper,
            EvaluationRequestMapper evaluationRequestMapper,
            LeaseListingMapper leaseListingMapper,
            MeterMapper meterMapper,
            OwnershipTransferMapper ownershipTransferMapper,
            OwnershipTransferAssetMapper ownershipTransferAssetMapper,
            AssetTransferRecordMapper transferRecordMapper,
            AssetTransferRecordAssetMapper transferRecordAssetMapper) {
        this.assetMapper = assetMapper;
        this.leaseControlLogMapper = leaseControlLogMapper;
        this.certificateMapper = certificateMapper;
        this.mortgageMapper = mortgageMapper;
        this.transferMapper = transferMapper;
        this.structureLogMapper = structureLogMapper;
        this.contractMapper = contractMapper;
        this.vacateOrderMapper = vacateOrderMapper;
        this.billMapper = billMapper;
        this.dunningRecordMapper = dunningRecordMapper;
        this.repairOrderMapper = repairOrderMapper;
        this.inspectionRecordMapper = inspectionRecordMapper;
        this.disposalOrderMapper = disposalOrderMapper;
        this.occupationOrderMapper = occupationOrderMapper;
        this.evaluationRequestMapper = evaluationRequestMapper;
        this.leaseListingMapper = leaseListingMapper;
        this.meterMapper = meterMapper;
        this.ownershipTransferMapper = ownershipTransferMapper;
        this.ownershipTransferAssetMapper = ownershipTransferAssetMapper;
        this.transferRecordMapper = transferRecordMapper;
        this.transferRecordAssetMapper = transferRecordAssetMapper;
    }

    public AssetDossier getDossier(Long assetId) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产不存在");
        }

        AssetDossier dossier = new AssetDossier();
        dossier.setAsset(asset);

        List<LeaseControlLog> leaseLogs = leaseControlLogMapper.selectList(
                new LambdaQueryWrapper<LeaseControlLog>()
                        .eq(LeaseControlLog::getAssetId, assetId)
                        .orderByDesc(LeaseControlLog::getId));
        dossier.setLeaseControlLogs(leaseLogs);

        dossier.setCertificates(certificateMapper.selectList(
                new LambdaQueryWrapper<AssetCertificate>()
                        .eq(AssetCertificate::getAssetId, assetId)
                        .orderByDesc(AssetCertificate::getId)));
        dossier.setMortgages(mortgageMapper.selectList(
                new LambdaQueryWrapper<Mortgage>()
                        .eq(Mortgage::getAssetId, assetId)
                        .orderByDesc(Mortgage::getId)));
        dossier.setTransferRecords(transferRecords(assetId));
        dossier.setTransfers(transferMapper.selectList(
                new LambdaQueryWrapper<AssetTransfer>()
                        .eq(AssetTransfer::getAssetId, assetId)
                        .orderByDesc(AssetTransfer::getId)));
        dossier.setOwnershipTransfers(ownershipTransfers(assetId));

        String idToken = String.valueOf(assetId);
        List<AssetStructureLog> structureLogs = structureLogMapper.selectList(
                new LambdaQueryWrapper<AssetStructureLog>()
                        .and(w -> w.like(AssetStructureLog::getSourceAssetIds, idToken)
                                .or()
                                .like(AssetStructureLog::getResultAssetIds, idToken))
                        .orderByDesc(AssetStructureLog::getId)
                        .last("LIMIT 100"));
        dossier.setStructureLogs(structureLogs.stream()
                .filter(log -> containsAssetId(log.getSourceAssetIds(), assetId)
                        || containsAssetId(log.getResultAssetIds(), assetId))
                .collect(Collectors.toList()));

        List<Contract> contracts = contractMapper.selectList(
                new LambdaQueryWrapper<Contract>()
                        .eq(Contract::getAssetId, assetId)
                        .orderByDesc(Contract::getId));
        dossier.setContracts(contracts);

        List<Long> contractIds = contracts.stream().map(Contract::getId).filter(Objects::nonNull).toList();
        if (!contractIds.isEmpty()) {
            dossier.setVacateOrders(vacateOrderMapper.selectList(
                    new LambdaQueryWrapper<VacateOrder>()
                            .in(VacateOrder::getContractId, contractIds)
                            .orderByDesc(VacateOrder::getId)));
        }

        List<Bill> bills = billMapper.selectList(
                new LambdaQueryWrapper<Bill>()
                        .eq(Bill::getAssetId, assetId)
                        .orderByDesc(Bill::getId));
        dossier.setBills(bills);

        List<Long> billIds = bills.stream().map(Bill::getId).filter(Objects::nonNull).toList();
        List<Long> dunningContractIds = contractIds;
        if (!billIds.isEmpty() || !dunningContractIds.isEmpty()) {
            dossier.setDunningRecords(dunningRecordMapper.selectList(
                    new LambdaQueryWrapper<DunningRecord>()
                            .and(w -> {
                                if (!billIds.isEmpty()) {
                                    w.in(DunningRecord::getBillId, billIds);
                                }
                                if (!dunningContractIds.isEmpty()) {
                                    if (!billIds.isEmpty()) {
                                        w.or();
                                    }
                                    w.in(DunningRecord::getContractId, dunningContractIds);
                                }
                            })
                            .orderByDesc(DunningRecord::getId)
                            .last("LIMIT 200")));
        }

        dossier.setRepairs(repairOrderMapper.selectList(
                new LambdaQueryWrapper<RepairOrder>()
                        .eq(RepairOrder::getAssetId, assetId)
                        .orderByDesc(RepairOrder::getId)));
        dossier.setInspections(inspectionRecordMapper.selectList(
                new LambdaQueryWrapper<InspectionRecord>()
                        .eq(InspectionRecord::getAssetId, assetId)
                        .orderByDesc(InspectionRecord::getId)));
        dossier.setDisposals(disposalOrderMapper.selectList(
                new LambdaQueryWrapper<DisposalOrder>()
                        .eq(DisposalOrder::getAssetId, assetId)
                        .orderByDesc(DisposalOrder::getId)));
        dossier.setOccupations(occupationOrderMapper.selectList(
                new LambdaQueryWrapper<OccupationOrder>()
                        .eq(OccupationOrder::getAssetId, assetId)
                        .orderByDesc(OccupationOrder::getId)));
        dossier.setEvaluations(evaluationRequestMapper.selectList(
                new LambdaQueryWrapper<EvaluationRequest>()
                        .eq(EvaluationRequest::getAssetId, assetId)
                        .orderByDesc(EvaluationRequest::getId)));
        dossier.setLeaseListings(leaseListingMapper.selectList(
                new LambdaQueryWrapper<LeaseListing>()
                        .eq(LeaseListing::getAssetId, assetId)
                        .orderByDesc(LeaseListing::getId)));
        dossier.setMeters(meterMapper.selectList(
                new LambdaQueryWrapper<Meter>()
                        .eq(Meter::getAssetId, assetId)
                        .orderByDesc(Meter::getId)));

        fillStatusSummary(dossier);
        dossier.setTimeline(buildTimeline(dossier));
        return dossier;
    }

    /**
     * 资产被哪些权属流转单改过（设计 §5.7）。
     *
     * <p>查的是 {@code ownership_transfer_asset}（{@code asset_id} 上有索引），再按 id 取主单 ——
     * 走「按资产反查明细」而不是「扫主单 LIKE」，因为一次流转可以挂几十个资产。
     *
     * <p>软删的单据不显示：草稿删掉后不该在档案里留下痕迹。
     */
    private List<OwnershipTransfer> ownershipTransfers(Long assetId) {
        List<Long> transferIds = ownershipTransferAssetMapper.selectList(
                        new LambdaQueryWrapper<OwnershipTransferAsset>()
                                .eq(OwnershipTransferAsset::getAssetId, assetId))
                .stream()
                .map(OwnershipTransferAsset::getTransferId)
                .distinct()
                .toList();
        if (transferIds.isEmpty()) {
            return List.of();
        }
        return ownershipTransferMapper.selectList(new LambdaQueryWrapper<OwnershipTransfer>()
                .in(OwnershipTransfer::getId, transferIds)
                .isNull(OwnershipTransfer::getDeletedAt)
                .orderByDesc(OwnershipTransfer::getId));
    }

    /**
     * 该资产所在的资产调拨记录单（V55）。
     *
     * <p>查的是 {@code asset_transfer_record_asset}（{@code asset_id} 上有索引），再按 id 取主单，
     * 与 {@link #ownershipTransfers} 同一套做法 —— 一张调拨单也可以挂几十个资产，
     * 扫主单反推既慢又容易漏。
     *
     * <p>软删的单据不显示：草稿删掉后不该在档案里留下痕迹。
     */
    private List<AssetTransferRecord> transferRecords(Long assetId) {
        List<Long> recordIds = transferRecordAssetMapper.selectList(
                        new LambdaQueryWrapper<AssetTransferRecordAsset>()
                                .eq(AssetTransferRecordAsset::getAssetId, assetId))
                .stream()
                .map(AssetTransferRecordAsset::getRecordId)
                .distinct()
                .toList();
        if (recordIds.isEmpty()) {
            return List.of();
        }
        return transferRecordMapper.selectList(new LambdaQueryWrapper<AssetTransferRecord>()
                .in(AssetTransferRecord::getId, recordIds)
                .isNull(AssetTransferRecord::getDeletedAt)
                .orderByDesc(AssetTransferRecord::getId));
    }

    private void fillStatusSummary(AssetDossier dossier) {        Asset asset = dossier.getAsset();
        List<Bill> unpaid = dossier.getBills().stream()
                .filter(b -> "unpaid".equals(b.getStatus()) || "partial_paid".equals(b.getStatus()))
                .toList();
        BigDecimal arrears = unpaid.stream()
                .map(b -> nz(b.getAmount()).subtract(nz(b.getPaidAmount())).subtract(nz(b.getReducedAmount())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean mortgaged = dossier.getMortgages().stream()
                .anyMatch(m -> "active".equals(m.getStatus()));
        long activeContracts = dossier.getContracts().stream()
                .filter(c -> "active".equals(c.getStatus()) || "expiring".equals(c.getStatus())
                        || "renewable".equals(c.getStatus()))
                .count();

        dossier.getStatusSummary().put("leaseControlStatus", asset.getLeaseControlStatus());
        dossier.getStatusSummary().put("structureStatus", asset.getStructureStatus());
        dossier.getStatusSummary().put("mortgaged", mortgaged);
        dossier.getStatusSummary().put("activeContractCount", activeContracts);
        dossier.getStatusSummary().put("unpaidBillCount", unpaid.size());
        dossier.getStatusSummary().put("arrearsAmount", arrears);
        dossier.getStatusSummary().put("vacantSince", asset.getVacantSince());
        dossier.getStatusSummary().put("vacantReason", asset.getVacantReason());
        dossier.getStatusSummary().put("leaseControlChangeCount", dossier.getLeaseControlLogs().size());
        dossier.getStatusSummary().put("timelineCount", 0); // filled after timeline build
    }

    private List<TimelineItem> buildTimeline(AssetDossier dossier) {
        List<TimelineItem> items = new ArrayList<>();

        for (LeaseControlLog log : dossier.getLeaseControlLogs()) {
            items.add(item("lease_control", "租控变更",
                    log.getFromStatus() + " → " + log.getToStatus(),
                    log.getRemark(), log.getId(), log.getCreatedAt()));
        }
        for (Contract c : dossier.getContracts()) {
            items.add(item("contract", "合同 " + nullToDash(c.getContractNo()),
                    c.getStatus(), null, c.getId(), c.getCreatedAt()));
        }
        for (VacateOrder v : dossier.getVacateOrders()) {
            items.add(item("vacate", "退租单 #" + v.getId(),
                    v.getStatus(), v.getReason(), v.getId(), v.getCreatedAt()));
        }
        for (Bill b : dossier.getBills()) {
            items.add(item("bill", "账单 " + nullToDash(b.getBillNo()),
                    b.getStatus(), b.getRemark(), b.getId(), b.getCreatedAt()));
        }
        for (DunningRecord d : dossier.getDunningRecords()) {
            items.add(item("dunning", "催缴 L" + d.getLevel(),
                    d.getResult(), d.getContent(), d.getId(), d.getCreatedAt()));
        }
        for (RepairOrder r : dossier.getRepairs()) {
            items.add(item("repair", "报修 #" + r.getId(),
                    r.getStatus(), r.getDescription(), r.getId(), r.getCreatedAt()));
        }
        for (InspectionRecord i : dossier.getInspections()) {
            items.add(item("inspection", "巡查 #" + i.getId(),
                    i.getStatus(), i.getHazardDesc(), i.getId(), i.getCreatedAt()));
        }
        for (AssetTransfer t : dossier.getTransfers()) {
            items.add(item("transfer", "调拨 #" + t.getId(),
                    t.getStatus(), t.getReason(), t.getId(), t.getCreatedAt()));
        }
        // 与调拨刻意分成两类（D11）：合成一类会让档案里无法区分「调拨过」和「产权转出过」
        for (OwnershipTransfer t : dossier.getOwnershipTransfers()) {
            items.add(item("ownership_transfer", "权属流转 #" + t.getId(),
                    t.getStatus(), t.getReason(), t.getId(), t.getCreatedAt()));
        }
        // 与上面两类再分一类：本段改的是责任部门 / 责任人，不是资产归谁持有
        for (AssetTransferRecord t : dossier.getTransferRecords()) {
            items.add(item("asset_transfer_record", "资产调拨 #" + t.getId(),
                    t.getStatus(), t.getReason(), t.getId(), t.getCreatedAt()));
        }
        for (DisposalOrder d : dossier.getDisposals()) {
            items.add(item("disposal", "处置 #" + d.getId(),
                    d.getStatus(), d.getReason(), d.getId(), d.getCreatedAt()));
        }
        for (OccupationOrder o : dossier.getOccupations()) {
            items.add(item("occupation", "占用 #" + o.getId(),
                    o.getStatus(), o.getReason(), o.getId(), o.getCreatedAt()));
        }
        for (EvaluationRequest e : dossier.getEvaluations()) {
            items.add(item("evaluation", "评估 #" + e.getId(),
                    e.getStatus(), e.getPurpose(), e.getId(), e.getCreatedAt()));
        }
        for (LeaseListing l : dossier.getLeaseListings()) {
            items.add(item("listing", "招租 #" + l.getId(),
                    l.getStatus(), null, l.getId(), l.getCreatedAt()));
        }
        for (AssetStructureLog s : dossier.getStructureLogs()) {
            items.add(item("structure", "拆分合并 " + s.getOpType(),
                    s.getOpType(), s.getRemark(), s.getId(), s.getCreatedAt()));
        }
        for (Mortgage m : dossier.getMortgages()) {
            items.add(item("mortgage", "抵押 #" + m.getId(),
                    m.getStatus(), null, m.getId(), m.getCreatedAt()));
        }

        items.sort(Comparator.comparing(
                (TimelineItem t) -> t.getOccurredAt() == null ? "" : t.getOccurredAt()).reversed());
        if (items.size() > TIMELINE_LIMIT) {
            items = new ArrayList<>(items.subList(0, TIMELINE_LIMIT));
        }
        dossier.getStatusSummary().put("timelineCount", items.size());
        return items;
    }

    private static TimelineItem item(
            String category, String title, String status, String remark, Long bizId, LocalDateTime at) {
        TimelineItem t = new TimelineItem();
        t.setCategory(category);
        t.setTitle(title);
        t.setStatus(status);
        t.setRemark(remark);
        t.setBizId(bizId);
        t.setOccurredAt(at == null ? null : TS.format(at));
        return t;
    }

    private static boolean containsAssetId(String csvOrList, Long assetId) {
        if (csvOrList == null || assetId == null) {
            return false;
        }
        String raw = csvOrList.replace("[", "").replace("]", "").trim();
        if (raw.isEmpty()) {
            return false;
        }
        for (String part : raw.split(",")) {
            if (part.trim().equals(String.valueOf(assetId))) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String nullToDash(String v) {
        return v == null || v.isBlank() ? "-" : v;
    }
}
