package com.ams.modules.regulation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("regulation_report")
public class RegulationReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String reportType;
    private String period;
    private String contentJson;
    private String sourceJson;
    private String status; // draft/reviewed/submitted
    private LocalDateTime submittedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
}
