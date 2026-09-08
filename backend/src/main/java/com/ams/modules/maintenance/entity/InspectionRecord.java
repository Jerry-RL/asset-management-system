package com.ams.modules.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("inspection_record")
public class InspectionRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;
    private Long inspectorId;
    private LocalDate planDate;
    private String result;
    private String hazardDesc;
    private String status; // pending/done
    private LocalDateTime createdAt;
}
