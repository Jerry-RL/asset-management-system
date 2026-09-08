package com.ams.modules.fixedasset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("fa_inventory_plan")
public class FaInventoryPlan {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String planNo;
    private Long companyId;
    private String title;
    private String status;
    private LocalDate plannedDate;
    private LocalDateTime closedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
}
