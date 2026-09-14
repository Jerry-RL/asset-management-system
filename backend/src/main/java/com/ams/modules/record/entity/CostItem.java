package com.ams.modules.record.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 费用明细（{@code biz_cost_item}）—— 成本信息的子表，1:N。
 *
 * <p>{@link #costId} 由服务端按所属成本记录赋值，**不接受客户端传入**，
 * 从结构上杜绝把一条明细拼到别的成本记录上（同 {@link ReceiveIssue}）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_cost_item")
public class CostItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long costId;
    private String feeName;
    private String costType;
    private BigDecimal amount;
    private String remark;
    private Integer sort;
    private LocalDateTime deletedAt;
}
