package com.ams.modules.asset.dto;

import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.AssetCertificate;
import com.ams.modules.asset.entity.AssetStructureLog;
import com.ams.modules.asset.entity.AssetTransfer;
import com.ams.modules.asset.entity.LeaseControlLog;
import com.ams.modules.asset.entity.Mortgage;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.entity.VacateOrder;
import com.ams.modules.disposal.entity.DisposalOrder;
import com.ams.modules.dunning.entity.DunningRecord;
import com.ams.modules.evaluation.entity.EvaluationRequest;
import com.ams.modules.lease.entity.LeaseListing;
import com.ams.modules.maintenance.entity.InspectionRecord;
import com.ams.modules.maintenance.entity.RepairOrder;
import com.ams.modules.meter.entity.Meter;
import com.ams.modules.occupation.entity.OccupationOrder;
import com.ams.modules.ownership.entity.OwnershipTransfer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 资产一物一档聚合视图（FR-AST-003）：基础信息 + 当前状态 + 各业务历史。
 */
@Data
public class AssetDossier {

    private Asset asset;
    /** 当前状态摘要：租控、结构、欠费等。 */
    private Map<String, Object> statusSummary = new LinkedHashMap<>();
    private List<LeaseControlLog> leaseControlLogs = new ArrayList<>();
    private List<AssetCertificate> certificates = new ArrayList<>();
    private List<Mortgage> mortgages = new ArrayList<>();
    private List<AssetTransfer> transfers = new ArrayList<>();
    /**
     * 该资产被哪些权属流转单改过（V54）。
     *
     * <p>与 {@link #transfers}（调拨）**刻意分成两段**：调拨改的是经营公司、单资产；
     * 权属流转按权属类型改产权 / 经营公司、一单多资产。合成一段会让档案里无法区分
     * 「调拨过」与「产权转出过」。
     */
    private List<OwnershipTransfer> ownershipTransfers = new ArrayList<>();
    private List<AssetStructureLog> structureLogs = new ArrayList<>();
    private List<Contract> contracts = new ArrayList<>();
    private List<VacateOrder> vacateOrders = new ArrayList<>();
    private List<Bill> bills = new ArrayList<>();
    private List<DunningRecord> dunningRecords = new ArrayList<>();
    private List<RepairOrder> repairs = new ArrayList<>();
    private List<InspectionRecord> inspections = new ArrayList<>();
    private List<DisposalOrder> disposals = new ArrayList<>();
    private List<OccupationOrder> occupations = new ArrayList<>();
    private List<EvaluationRequest> evaluations = new ArrayList<>();
    private List<LeaseListing> leaseListings = new ArrayList<>();
    private List<Meter> meters = new ArrayList<>();
    /** 按时间倒序的统一时间线（跨业务）。 */
    private List<TimelineItem> timeline = new ArrayList<>();

    @Data
    public static class TimelineItem {
        private String category;
        private String title;
        private String status;
        private String remark;
        private Long bizId;
        private String occurredAt;
    }
}
