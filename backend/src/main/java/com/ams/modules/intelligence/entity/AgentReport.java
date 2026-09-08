package com.ams.modules.intelligence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("agent_report")
public class AgentReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;
    private String title;
    private String format; // html/pptx/docx/pdf
    private String status; // draft/verified/approved/exported
    private String previewPath;
    private String filePath;
    private Boolean verified;
    private Long approvedBy;
    private LocalDateTime createdAt;
}
