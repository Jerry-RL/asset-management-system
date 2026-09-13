package com.ams.modules.ownership.dto;

import com.ams.modules.record.dto.AttachmentRef;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 权属流转表单入参（新建 / 编辑草稿共用）。
 *
 * <p>没有 {@code status} 字段：状态只能由服务端的状态机推进，不接受表单写入 ——
 * 否则可以把一张已完成单改回 {@code draft}，再生效一次，把产权改第二遍。
 */
@Data
public class OwnershipTransferInput {

    /** 编辑草稿时由路径上的 id 提供；新建时忽略。 */
    private Long id;

    private String direction;
    private String transferScope;
    private Long fromCompanyId;
    private Long toCompanyId;
    private String transferMode;

    /** 内员 id；为空表示外部人员（此时 applicantName 必填）。 */
    private Long applicantUserId;
    private String applicantName;

    private LocalDateTime approvalDeadline;

    /** 金额(万元)，超过 2 位小数一律 400。 */
    private BigDecimal amountWan;

    private String reason;

    /** 选中的资产。至少 1 个，服务端去重并逐条校验归属。 */
    private List<Long> assetIds;

    private List<AttachmentRef> attachments;
}
