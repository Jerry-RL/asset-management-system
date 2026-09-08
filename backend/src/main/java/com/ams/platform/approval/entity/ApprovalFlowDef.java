package com.ams.platform.approval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("approval_flow_def")
public class ApprovalFlowDef {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bizType;
    private String name;
    private String definition;
    private Integer version;
    private Boolean enabled;
    private LocalDateTime createdAt;
}
