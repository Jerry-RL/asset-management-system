package com.ams.modules.selfuse.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("self_use_order")
public class SelfUseOrder extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;
    private String department;
    private String purpose;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal area;
    private String status; // draft/approving/self_use/ended
}
