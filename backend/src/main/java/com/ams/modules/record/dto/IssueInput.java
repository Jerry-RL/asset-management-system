package com.ams.modules.record.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 遗留问题（接收信息的子项）。设计 §4.1。
 *
 * <p>{@code receiveId} 刻意**不出现在本 DTO 里**：它由服务端按所属接收记录赋值，
 * 客户端无法指定，从而不可能把一条 issue 拼到别的接收记录上（设计 §5.4）。
 */
@Data
public class IssueInput {

    /** 为空表示新增；非空表示更新已存在的行。 */
    private Long id;

    /** 取值见 sys_dict_type.code = issue_type。 */
    private String issueType;

    private String description;

    /** sys_user.id；外部发现人留空，改填 {@link #discovererName}。 */
    private Long discovererId;

    private String discovererName;

    private List<AttachmentRef> attachments = new ArrayList<>();
}
