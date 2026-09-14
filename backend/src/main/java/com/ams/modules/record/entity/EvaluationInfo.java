package com.ams.modules.record.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评估信息（{@code biz_evaluation_info}）—— 后续记录扩展，1:N，三种主体共用。
 *
 * <p>一次评估一条：评估机构、资产价值、租赁单价、租赁价格、评估时间与有效期限
 * （{@link #validFrom} ~ {@link #validTo}）。附件挂在本行上（PDF / Word 等）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_evaluation_info")
public class EvaluationInfo extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ownerType;
    private Long ownerId;
    /** 自由文本：评估机构多为外部单位，不建字典。 */
    private String institution;
    private BigDecimal assetValue;
    private BigDecimal rentUnitPrice;
    private BigDecimal rentPrice;
    private LocalDate evaluateDate;
    private LocalDate validFrom;
    private LocalDate validTo;
    private LocalDateTime deletedAt;
}
