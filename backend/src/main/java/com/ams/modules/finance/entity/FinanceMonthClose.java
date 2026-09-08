package com.ams.modules.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("finance_month_close")
public class FinanceMonthClose {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** yyyy-MM */
    private String period;
    /** open / locked */
    private String status;
    private LocalDateTime lockedAt;
    private Long lockedBy;
    private String remark;
    private LocalDateTime createdAt;
}
