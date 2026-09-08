package com.ams.modules.revitalization.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("revitalization_task")
public class RevitalizationTask extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;
    private String vacantReason;
    private String planType;
    private Long assigneeId;
    private LocalDate targetDate;
    private String status; // pending/listing/signed/done
}
