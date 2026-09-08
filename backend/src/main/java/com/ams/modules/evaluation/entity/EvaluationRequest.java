package com.ams.modules.evaluation.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("evaluation_request")
public class EvaluationRequest extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;
    private Long projectId;
    private String purpose; // lease/disposal/filing
    private String institution;
    private String status; // applying/accepted/evaluating/reported
    private BigDecimal resultValue;
    private Long reportFileId;
}
