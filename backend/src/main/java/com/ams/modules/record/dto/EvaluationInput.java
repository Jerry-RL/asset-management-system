package com.ams.modules.record.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 评估信息（后续记录扩展）。三种主体共用，1:N。
 *
 * <p>评估有效期限以 {@link #validFrom} ~ {@link #validTo} 两个日期表达（闭区间），
 * 前端用 RangePicker 采集后拆成两个字段提交。
 */
@Data
public class EvaluationInput {

    /** 为空表示新增；非空表示更新已存在的行。 */
    private Long id;

    /** 评估机构（自由文本，多为外部单位）。 */
    private String institution;

    private BigDecimal assetValue;

    /** 租赁单价。 */
    private BigDecimal rentUnitPrice;

    /** 租赁价格。 */
    private BigDecimal rentPrice;

    private LocalDate evaluateDate;

    /** 评估有效期限起（含）。 */
    private LocalDate validFrom;

    /** 评估有效期限止（含）。 */
    private LocalDate validTo;

    private List<AttachmentRef> attachments = new ArrayList<>();
}
