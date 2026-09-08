package com.ams.modules.intelligence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("agent_session")
public class AgentSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long companyId;
    private String title;
    private String status; // active/archived
    private LocalDateTime createdAt;
}
