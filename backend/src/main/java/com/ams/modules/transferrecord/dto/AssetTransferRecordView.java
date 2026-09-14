package com.ams.modules.transferrecord.dto;

import com.ams.modules.record.dto.AttachmentRef;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 资产调拨记录列表 / 详情出参。
 *
 * <p>部门名、人员名与附件都内联：列表页每行要显示「前部门 → 新部门」与「新责任人」，
 * 详情页要显示附件，让前端为每行再发三次请求会把列表页变成 N+1。
 */
@Data
public class AssetTransferRecordView {

    private Long id;

    private Long companyId;
    private String companyName;

    private Long fromDepartmentId;
    private String fromDepartmentName;
    private Long toDepartmentId;
    private String toDepartmentName;

    private Long toUserId;
    private String toUserName;

    private LocalDateTime approvalDeadline;
    private String reason;
    private String remark;

    private String status;
    private LocalDateTime effectedAt;
    private LocalDateTime createdAt;

    /** 资产数（列表页不必展开明细，故单独给一个计数）。 */
    private int assetCount;

    /** 详情才有值；列表页留空以省一次 join。 */
    private List<AssetTransferRecordAssetView> assets;

    private List<AttachmentRef> attachments;
}
