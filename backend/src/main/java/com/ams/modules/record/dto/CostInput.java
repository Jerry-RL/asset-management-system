package com.ams.modules.record.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 成本信息（后续记录扩展）。三种主体（资产 / 项目 / 分区）共用，1:N。
 *
 * <p>读写复用：写路径只认业务字段与 {@link #attachments} 的 {@code fileId}，
 * 归属（{@code owner_type} / {@code owner_id}）由服务端赋值。
 */
@Data
public class CostInput {

    /** 为空表示新增；非空表示更新已存在的行。 */
    private Long id;

    /** 成本金额，单位**万元**。 */
    private BigDecimal amountWan;

    private LocalDate costDate;

    private String remark;

    /** 费用明细（1:N）。未出现在列表里的行由服务端软删。 */
    private List<CostItemInput> items = new ArrayList<>();

    private List<AttachmentRef> attachments = new ArrayList<>();
}
