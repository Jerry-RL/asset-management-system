package com.ams.modules.task.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task")
public class Task extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskType; // contract_approval/dunning/revitalization/repair/...
    private Long refId;
    private String refNo;
    private Long companyId;
    private Long assigneeId;
    private LocalDateTime deadline;
    private String status; // pending/completed
    private Long overdueMinutes;
    private LocalDateTime completedAt;
    private LocalDateTime remindedAt;
    private LocalDateTime escalatedAt;
}
