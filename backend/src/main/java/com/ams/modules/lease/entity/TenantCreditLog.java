package com.ams.modules.lease.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("tenant_credit_log")
public class TenantCreditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private String eventType;
    private Integer scoreDelta;
    private String remark;
    private LocalDateTime createdAt;
}
