package com.ams.modules.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("bank_flow")
public class BankFlow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String flowNo;
    private String bankAccount;
    private BigDecimal amount;
    private String direction; // in/out
    private LocalDate tradeDate;
    private String summary;
    private String matchStatus; // matched/unmatched/partial
    private Long paymentId;
    private LocalDateTime createdAt;
}
