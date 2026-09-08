package com.ams.modules.intelligence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("agent_report_citation")
public class AgentReportCitation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reportId;
    private String claimKey;
    private String claimValue;
    private Long toolCallId;
    private String apiPath;
    private Boolean verified;
}
