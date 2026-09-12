package com.ams.modules.disposal.dto;

import com.ams.modules.disposal.entity.DisposalOrder;
import com.ams.modules.record.dto.AttachmentRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 处置申请：实体字段 + 附件关联（设计 §4.3）。附件先经 {@code POST /files/upload} 拿到 fileId。 */
@Data
public class DisposalCreateRequest {

    private Long assetId;
    private String disposalType;
    private String reason;
    private BigDecimal assessedValue;
    private BigDecimal bookValue;
    private BigDecimal actualAmount;
    private String counterparty;
    private Long disposalUserId;
    private String disposalUserName;
    private LocalDate disposalDate;
    private String remark;
    private List<AttachmentRef> attachments = new ArrayList<>();

    /**
     * 只搬业务字段，**不搬 status**：状态由服务端按流程设置
     * （{@code DisposalService.create} 会强制 {@code draft}），客户端无法直造一个「已审批」的单子。
     */
    public DisposalOrder toOrder() {
        DisposalOrder order = new DisposalOrder();
        order.setAssetId(assetId);
        order.setDisposalType(disposalType);
        order.setReason(reason);
        order.setAssessedValue(assessedValue);
        order.setBookValue(bookValue);
        order.setActualAmount(actualAmount);
        order.setCounterparty(counterparty);
        order.setDisposalUserId(disposalUserId);
        order.setDisposalUserName(disposalUserName);
        order.setDisposalDate(disposalDate);
        order.setRemark(remark);
        return order;
    }
}
