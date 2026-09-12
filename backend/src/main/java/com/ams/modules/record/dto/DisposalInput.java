package com.ams.modules.record.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 处置台账（设计 §4.2）—— 仅项目 / 分区使用。
 *
 * <p>资产的处置段在 record-sheet 里被忽略（走 {@code disposal_order} 与
 * `POST /disposals` 流程），因此本类型不出现在资产的写入路径上。
 */
@Data
public class DisposalInput {

    private Long id;

    /** 取值见 sys_dict_type.code = disposal_type。 */
    private String disposalType;

    private Long disposalUserId;

    /** 姓名快照，规则同 {@link ReceiveInput#getHandoverUserName()}。 */
    private String disposalUserName;

    /** 处置金额，单位**万元**。 */
    private BigDecimal amountWan;

    private LocalDate disposalDate;

    private String remark;

    private List<AttachmentRef> attachments = new ArrayList<>();
}
