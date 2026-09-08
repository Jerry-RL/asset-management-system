package com.ams.modules.intelligence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("agent_prompt_template")
public class AgentPromptTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String templateCode;
    private String name;
    private String definition;
    private Integer version;
    private Boolean enabled;
    private LocalDateTime createdAt;
}
