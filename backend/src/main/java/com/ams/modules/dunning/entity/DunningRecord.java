package com.ams.modules.dunning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
@TableName("dunning_record")
public class DunningRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long billId;
    private Long contractId;
    private Long tenantId;
    private Integer level; // L1-L5
    private String method; // sms/notice_post/lawyer_letter/legal
    private String content;
    private Long operatorId;
    private String tenantFeedback;
    private String result;
    private String photoFileIds; // 逗号分隔
    private LocalDateTime createdAt;
}
