package com.ams.modules.invoice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("invoice_title")
public class InvoiceTitle {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private String title;
    private String taxNo;
    private String address;
    private String bank;
    private String accountNo;
    private Integer status;
}
