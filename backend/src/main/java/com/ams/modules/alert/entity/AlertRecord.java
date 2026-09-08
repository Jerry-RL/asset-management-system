package com.ams.modules.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("alert_record")
public class AlertRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ruleId;
    private Long companyId;
    private String alertType;
    private String subType;
    private Integer level;
    private String bizType;
    private Long bizId;
    private String title;
    private String content;
    private String status; // pending/processing/escalated/closed
    private Long assigneeId;
    private String handleRemark;
    private LocalDateTime handledAt;
    private LocalDateTime createdAt;
}
