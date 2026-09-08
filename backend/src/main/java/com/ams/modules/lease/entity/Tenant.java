package com.ams.modules.lease.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tenant")
public class Tenant extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String phone;
    private String idNo;
    private String idNoHash;
    private String tenantType; // person / enterprise
    private Boolean blacklist;
    private Integer creditScore;
    private String wechatOpenid;
    private String legalRep;
    private Integer status;
}
