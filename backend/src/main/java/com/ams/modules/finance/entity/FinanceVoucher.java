package com.ams.modules.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("finance_voucher")
public class FinanceVoucher {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bizType;
    private Long bizId;
    private String voucherNo;
    private String status; // pending/pushed
    private String contentJson;
    private LocalDateTime pushedAt;
    private LocalDateTime createdAt;
}
