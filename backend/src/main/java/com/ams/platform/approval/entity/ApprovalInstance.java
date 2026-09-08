package com.ams.platform.approval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("approval_instance")
public class ApprovalInstance {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long flowDefId;
    private String bizType;
    private Long bizId;
    private String status; // pending/approved/rejected
    private Long submittedBy;
    private LocalDateTime submittedAt;
    private String currentNode;
    private LocalDateTime completedAt;
}
