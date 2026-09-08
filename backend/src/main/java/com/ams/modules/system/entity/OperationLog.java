package com.ams.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String username;
    private String module;
    private String action;
    private Long refId;
    private String detailJson;
    private String ip;
    private String traceId;
    private LocalDateTime createdAt;
}
