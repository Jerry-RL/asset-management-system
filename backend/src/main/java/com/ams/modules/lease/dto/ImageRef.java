package com.ams.modules.lease.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图片引用（招租发布的封面图 / 详情列表图）。
 *
 * <p>{@code fileId} 是附件真源（{@code sys_file.id}，可追溯、可清理），
 * {@code url} 是可直接渲染的地址 —— 前端上传组件（{@code ImageUploadField}）的值形态
 * 恰好就是 {@code {url, fileId}}，直接复用可省掉一层出入参映射。
 *
 * <p>文件本体必须先经 {@code POST /files/upload} 落库拿到 {@code fileId}，本类只承载引用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageRef {

    private Long fileId;
    private String url;
}
