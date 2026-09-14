package com.ams.modules.mortgage.dto;

import com.ams.modules.record.dto.AttachmentRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 抵押记录列表 / 详情出参。
 *
 * <p><b>标的名称只回原料，不拼 label</b>：{@code targetType} 决定该怎么拼
 * （项目 → 项目名；分区 → 项目名 · 分区名；资产 → 项目名 · 分区名 · 楼层 · 资产名），
 * 拼接规则属于展示层。服务端拼好会变成「改一次文案要发一次后端版本」，
 * 而且同一个资产在不同页面会有两套拼法。
 *
 * <p><b>附件只在详情返回</b>（{@code attachments} 为 null 表示"列表页没查"）：
 * 附件名要按行去 {@code biz_attachment} 取，列表页每行一次就是 N+1。
 * 列表页只需要状态与金额，要附件时打开详情。
 */
@Data
public class MortgageRecordView {

    private Long id;

    private Long companyId;
    private String companyName;

    private String targetType;
    private Long targetId;

    /** 标的自身的名字：项目名 / 分区名 / 资产名。 */
    private String targetName;
    /** 资产 / 分区标的的上级项目名；项目标的有值、其余情况下同 {@link #targetName} 的来源。 */
    private String projectName;
    /** 资产标的的所属分区名。 */
    private String zoneName;
    /** 资产标的的楼层号。 */
    private Integer floorNo;
    /** 资产标的的资产编号。 */
    private String assetNo;

    private String mortgagee;
    private BigDecimal amount;
    private BigDecimal interestRate;
    private String bank;
    private LocalDate repaymentDate;
    private LocalDate startDate;
    /** 由 startDate + termMonths 推导，只读。 */
    private LocalDate endDate;
    private Integer termMonths;
    private String contractNo;

    private String status;
    private String releaseStatus;
    private String releaseRemark;
    private LocalDateTime releasedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 详情才有值；列表页为 null。 */
    private List<AttachmentRef> attachments;
}
