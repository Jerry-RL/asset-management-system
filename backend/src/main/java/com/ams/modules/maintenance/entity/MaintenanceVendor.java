package com.ams.modules.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("maintenance_vendor")
public class MaintenanceVendor {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String contact;
    private String phone;
    private String scope;
    private Integer status;
    private LocalDateTime createdAt;
}
