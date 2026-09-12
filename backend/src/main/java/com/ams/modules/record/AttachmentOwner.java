package com.ams.modules.record;

/**
 * 附件的**直接宿主**（设计 §4.3）—— 与 {@link RecordOwnerType} 刻意分开：
 *
 * <ul>
 *   <li>{@link RecordOwnerType} 决定「权限码 + 归属公司」，粒度是资产 / 项目 / 分区；</li>
 *   <li>{@code AttachmentOwner} 决定「这行附件挂在哪条记录上」，粒度到子实体。</li>
 * </ul>
 *
 * <p>合并成一个枚举会让「接收信息的附件」在权限上被当成一个独立主体，
 * 从而必须先回溯到接收信息、再回溯到资产才能判权限 —— 那正是设计要避免的散落判断。
 *
 * <p>{@link #bizType()} 与 {@code file_metadata.biz_type} 不同：它是「属于哪个字段」。
 */
public enum AttachmentOwner {

    RECEIVE_RECORD("receive_record", "receive_doc"),
    RECEIVE_ISSUE("receive_issue", "issue_scene"),
    SOURCE_INFO("source_info", "source_attach"),
    DISPOSAL_RECORD("disposal_record", "disposal_attach"),
    /** 资产侧处置单：附件的读写权限跟资产走，状态流转另用 operation.disposal。 */
    DISPOSAL_ORDER("disposal_order", "disposal_attach");

    private final String code;
    private final String bizType;

    AttachmentOwner(String code, String bizType) {
        this.code = code;
        this.bizType = bizType;
    }

    public String code() {
        return code;
    }

    public String bizType() {
        return bizType;
    }
}
