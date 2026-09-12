package com.ams.modules.record.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 来源明细（设计 §4.1），与宿主 1:1。读写复用。 */
@Data
public class SourceInput {

    /** 为空表示该宿主还没有来源明细；服务端按 owner 定位，不按 id 定位。 */
    private Long id;

    private Long sourcePersonId;

    /** 姓名快照，规则同 {@link ReceiveInput#getHandoverUserName()}。 */
    private String sourcePersonName;

    /** 自由文本：原产权 / 移交单位多为外部单位，不建字典。 */
    private String sourceUnit;

    private LocalDate sourceDate;

    private String sourceDesc;

    /** 「来源附件」。 */
    private List<AttachmentRef> attachments = new ArrayList<>();
}
