package com.ams.modules.org.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import java.util.List;
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
    /** 微信 openid（小程序登录绑定） */
    private String wechatOpenid;
    /** 关联租户（租户端账号） */
    private Long tenantId;

    // ---- 以下为人员维护列表/表单的补充展示字段（非表字段，由 UserService 组装） ----

    /** 所属公司名称 */
    @TableField(exist = false)
    private String companyName;

    /** 所属部门名称 */
    @TableField(exist = false)
    private String departmentName;

    /** 已分配角色 ID（编辑表单回显） */
    @TableField(exist = false)
    private List<Long> roleIds;

    /** 已分配角色名称（列表展示） */
    @TableField(exist = false)
    private List<String> roleNames;
}
