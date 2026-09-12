package com.ams.modules.record.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 来源明细（{@code biz_source_info}）—— 设计 §4.1，与宿主 1:1。
 *
 * <p>唯一性由**部分唯一索引**保证（{@code WHERE deleted_at IS NULL}）：软删后允许重新录入，
 * 因此服务层的「取现有行」查询也必须带 {@code isNull("deleted_at")}，否则会读到已删的行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_source_info")
public class SourceInfo extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ownerType;
    private Long ownerId;
    private Long sourcePersonId;
    private String sourcePersonName;
    private String sourceUnit;
    private LocalDate sourceDate;
    private String sourceDesc;
    private LocalDateTime deletedAt;
}
