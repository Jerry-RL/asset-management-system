package com.ams.modules.audit.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("asset_audit_plan")
public class AssetAuditPlan extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String planNo;
    private String title;
    private Long companyId;
    private Long projectId;
    private String scopeType; // full / sample
    private String status; // draft/in_progress/completed/cancelled
    private LocalDate plannedStart;
    private LocalDate plannedEnd;
    private String remark;
}
