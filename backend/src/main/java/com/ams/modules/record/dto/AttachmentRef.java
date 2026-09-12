package com.ams.modules.record.dto;

import lombok.Data;

/**
 * 附件引用（设计 §5.2）。
 *
 * <p>读写复用：写路径只读 {@link #fileId} / {@link #sort}，读路径额外回填
 * {@link #fileName} / {@link #url} 供前端预览。文件本体必须先经
 * {@code POST /files/upload} 落库拿到 {@code fileId}。
 */
@Data
public class AttachmentRef {

    /** file_metadata.id。 */
    private Long fileId;

    /** 保序；为空时服务端按数组下标赋值。 */
    private Integer sort;

    /** 读路径回显；写路径忽略。 */
    private String fileName;

    /** 读路径回显（对象存储可访问地址）；写路径忽略。 */
    private String url;
}
