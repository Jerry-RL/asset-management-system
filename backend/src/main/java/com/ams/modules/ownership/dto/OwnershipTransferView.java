package com.ams.modules.ownership.dto;

import com.ams.modules.record.dto.AttachmentRef;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 权属流转列表 / 详情出参。
 *
 * <p>公司名与附件都内联：列表页每行要显示「A → B」，详情页要显示附件，
 * 让前端为每行再发两次请求会让列表页变成 N+1。
 */
@Data
public class OwnershipTransferView {

    private Long id;

    private String direction;
    private String transferScope;

    private Long fromCompanyId;
    private String fromCompanyName;
    private Long toCompanyId;
    private String toCompanyName;

    private String transferMode;

    private Long applicantUserId;
    private String applicantName;

    private LocalDateTime approvalDeadline;
    private BigDecimal amountWan;
    private String reason;

    private String status;
    private LocalDateTime effectedAt;
    private LocalDateTime createdAt;

    /** 资产数（列表页不必展开明细，故单独给一个计数）。 */
    private int assetCount;

    /** 详情才有值；列表页留空以省一次 join。 */
    private List<OwnershipTransferAssetView> assets;

    private List<AttachmentRef> attachments;
}
