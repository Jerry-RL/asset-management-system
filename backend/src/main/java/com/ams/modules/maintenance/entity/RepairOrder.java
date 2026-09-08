package com.ams.modules.maintenance.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("repair_order")
public class RepairOrder extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;
    private Long reporterId;
    private String reporterName;
    private String reporterPhone;
    private String description;
    private String status; // pending_review/dispatched/accepted/repairing/pending_accept/completed/rejected
    private Long vendorId;
    private Long assigneeId;
    private LocalDateTime slaResponseDeadline;
    private LocalDateTime slaCompleteDeadline;
    private LocalDateTime acceptedAt;
    private LocalDateTime completedAt;
    private String resultRemark;
    private Boolean slaBreached;
}
