package com.ams.modules.record.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 费用明细（成本信息的子项）。
 *
 * <p>{@code costId} 刻意**不出现在本 DTO 里**：它由服务端按所属成本记录赋值，
 * 客户端无法指定，从而不可能把一条明细拼到别的成本记录上（同 {@link IssueInput}）。
 */
@Data
public class CostItemInput {

    /** 为空表示新增；非空表示更新已存在的行。 */
    private Long id;

    private String feeName;

    /** 取值见 sys_dict_type.code = cost_type。 */
    private String costType;

    private BigDecimal amount;

    private String remark;
}
