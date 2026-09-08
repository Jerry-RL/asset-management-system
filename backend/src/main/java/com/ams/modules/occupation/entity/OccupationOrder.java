package com.ams.modules.occupation.entity;

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
@TableName("occupation_order")
public class OccupationOrder extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;
    private String reason;
    private String department;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal area;
    private String status; // draft/approving/occupied/released
}
