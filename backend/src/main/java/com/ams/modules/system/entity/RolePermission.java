package com.ams.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("role_permission")
public class RolePermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long roleId;
    private String menuCode;
    private String action;
    private String dataScope;
    private LocalDateTime createdAt;
}
