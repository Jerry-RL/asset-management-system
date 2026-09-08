package com.ams.modules.lease.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("tender_application")
public class TenderApplication {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long announcementId;
    private Long tenantId;
    private String auditStatus; // pending/approved/rejected
    private Boolean depositPaid;
    private BigDecimal depositAmount;
    private Boolean depositRefunded;
    private Long materialsFileId;
    private String auditComment;
    private Integer rankNo;
    private String result;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
