package com.ams.modules.transferrecord.dto;

import com.ams.modules.record.dto.AttachmentRef;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 资产调拨记录表单入参（新建 / 编辑草稿共用）。
 *
 * <p>没有 {@code status} 字段：状态只能由服务端的状态机推进，不接受表单写入 ——
 * 否则可以把一张已完成单改回 {@code draft}，再生效一次，把责任部门改第二遍。
 */
@Data
public class AssetTransferRecordInput {

    /** 编辑草稿时由路径上的 id 提供；新建时忽略。 */
    private Long id;

    /** 所属公司（资产公司）。 */
    private Long companyId;

    /** 前责任部门：业务留痕，可不填（多资产可能来自不同部门）。 */
    private Long fromDepartmentId;

    private Long toDepartmentId;

    private Long toUserId;

    private LocalDateTime approvalDeadline;

    private String reason;

    private String remark;

    /** 选中的资产。至少 1 个，服务端去重并逐条校验所属公司。 */
    private List<Long> assetIds;

    private List<AttachmentRef> attachments;
}
