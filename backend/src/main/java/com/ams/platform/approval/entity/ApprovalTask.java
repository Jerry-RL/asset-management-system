package com.ams.platform.approval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("approval_task")
public class ApprovalTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long instanceId;
    private String nodeId;
    private Long assigneeId;
    private String status; // pending/done
    private String comment;
    private LocalDateTime actedAt;
}
