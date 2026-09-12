package com.ams.modules.record.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通用附件关联（{@code biz_attachment}）—— 设计 §4.3。
 *
 * <p>{@code ownerType} 是**附件的直接宿主**（{@code receive_record} / {@code receive_issue} /
 * {@code source_info} / {@code disposal_record} / {@code disposal_order}），不是资产/项目/分区本身：
 * 「交接文件」挂在接收信息上、「现场文件」挂在遗留问题上，删除父记录时附件随之一并软删。
 *
 * <p>{@link #bizType} 与 {@code file_metadata.biz_type} 职责不同：前者是「这笔附件属于哪个字段」，
 * 后者是「上传来源」。两者都不为空，不要合并。
 *
 * <p>软删只写 {@link #deletedAt}：全局 {@code logic-delete-field: deleted} 与实际列
 * {@code deleted_at} 不一致，逻辑删除不会自动生效，查询必须显式 {@code isNull("deleted_at")}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_attachment")
public class BizAttachment extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ownerType;
    private Long ownerId;
    private String bizType;
    private Long fileId;
    private Integer sort;
    private LocalDateTime deletedAt;
}
