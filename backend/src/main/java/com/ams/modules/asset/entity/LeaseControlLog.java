package com.ams.modules.asset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("lease_control_log")
public class LeaseControlLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;
    private String fromStatus;
    private String toStatus;
    private String bizType;
    private Long bizId;
    private Long operatorId;
    private String remark;
    private LocalDateTime createdAt;
}
