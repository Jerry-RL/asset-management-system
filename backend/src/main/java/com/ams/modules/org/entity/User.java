package com.ams.modules.org.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "\"user\"")
public class User extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String passwordHash;
    private String name;
    private String phone;
    private Long departmentId;
    private Long companyId;
    private Integer status;
    private LocalDateTime lastLoginAt;
}
