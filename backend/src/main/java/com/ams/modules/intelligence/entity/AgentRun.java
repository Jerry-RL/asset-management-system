package com.ams.modules.intelligence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("agent_run")
public class AgentRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;
    private Long userId;
    private String intent;
    private String templateId;
    private String status; // running/succeeded/failed
    private String traceId;
    private String errorMsg;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
