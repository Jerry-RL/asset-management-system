package com.ams.modules.intelligence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("agent_tool_call")
public class AgentToolCall {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;
    private String toolName;
    private String requestJson;
    private String responseHash;
    private String responseSummary;
    private Integer durationMs;
    private LocalDateTime createdAt;
}
