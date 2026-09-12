package com.ams.modules.record.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 接收信息（设计 §4.1）。读写复用，读时附件带 fileName/url。 */
@Data
public class ReceiveInput {

    /** 为空表示新增。 */
    private Long id;

    /** 取值见 sys_dict_type.code = handover_type。 */
    private String handoverType;

    private String docName;

    /** sys_user.id；外部交接人留空。 */
    private Long handoverUserId;

    /** 姓名快照：内员则由服务端用员工姓名覆盖，外部人员为手填值。 */
    private String handoverUserName;

    /** 前端必填、DB 可空（设计 §12.2）。 */
    private LocalDate handoverDate;

    private String remark;

    private List<IssueInput> issues = new ArrayList<>();

    /** 「交接文件」附件。 */
    private List<AttachmentRef> attachments = new ArrayList<>();
}
