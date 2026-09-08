package com.ams.modules.contract.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("contract_version")
public class ContractVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long contractId;
    private Integer version;
    private String changeType;
    private String beforeJson;
    private String afterJson;
    private Long operatorId;
    private LocalDateTime createdAt;
}
