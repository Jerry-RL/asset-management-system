package com.ams.modules.asset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 抵押记录（{@code mortgage}，V2 建表、V10 加解押审批、V56 扩成三级标的）。
 *
 * <p><b>一条记录一个标的</b>，由 {@link #targetType} 决定 {@link #targetId} 指向哪张表：
 * <ul>
 *   <li>{@code project} → {@code project.id}</li>
 *   <li>{@code zone} → {@code project_zone.id}（zone 没有 company_id，公司经 project 推导）</li>
 *   <li>{@code asset} → {@code asset.id}，此时 {@link #assetId} 与 {@link #targetId} 同值</li>
 * </ul>
 *
 * <p><b>{@link #assetId} 为什么保留</b>：它是 V2 以来的历史列，仓内多处按它反查
 * （资产档案、按资产列抵押）。资产级抵押下它与 {@code target_id} 同值，由服务层维持 ——
 * 两个列写同一件事确实不理想，但删掉它要同时改掉所有按资产反查的地方，
 * 而那些地方与「抵押标的模型」无关。
 *
 * <p><b>状态机 {@code draft → active → released}</b> 落在同一个 {@link #status} 列上：
 * {@code CertificateService.hasActiveMortgage} 与 {@code listExpiring} 都按
 * {@code status = 'active'} 过滤，所以草稿天然不算在押、不进预警、不动权证状态。
 * 另加一列 {@code record_status} 的话，这三个读点都得同时改，漏一处就是
 * 「草稿被当成在押」或「在押被当成草稿」。
 *
 * <p>本类**不继承** {@code BaseEntity}：{@code mortgage} 表没有 {@code created_by} /
 * {@code updated_by} 两列，继承后 MyBatis-Plus 的自动填充会去写不存在的列。
 */
@Data
@TableName("mortgage")
public class Mortgage {

    /** 标的类型：项目（标的=project 表）。 */
    public static final String TARGET_PROJECT = "project";
    /** 标的类型：项目分区（标的=project_zone 表）。 */
    public static final String TARGET_ZONE = "zone";
    /** 标的类型：资产（标的=asset 表）。 */
    public static final String TARGET_ASSET = "asset";

    /** 状态：草稿（未生效，不算在押）。 */
    public static final String STATUS_DRAFT = "draft";
    /** 状态：在押。 */
    public static final String STATUS_ACTIVE = "active";
    /** 状态：已解押。 */
    public static final String STATUS_RELEASED = "released";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标的类型：{@link #TARGET_PROJECT} / {@link #TARGET_ZONE} / {@link #TARGET_ASSET}。 */
    private String targetType;

    /** 标的 id：project.id / project_zone.id / asset.id。 */
    private Long targetId;

    /**
     * 资产 id。**仅资产级抵押有值**，与 {@link #targetId} 同值。
     *
     * <p>历史列：V2 建表时它是唯一标的列，被多处按资产反查依赖。V56 起允许为 NULL。
     */
    private Long assetId;

    /** 所属公司（标的归属公司）。列表筛选与「标的必须属于该公司」校验用。 */
    private Long companyId;

    /** 抵押权人 / 抵押公司。 */
    private String mortgagee;

    private BigDecimal amount;

    /** 利率，百分数（如 4.3500 表示 4.35%），最多 4 位小数。 */
    private BigDecimal interestRate;

    /** 抵押银行。 */
    private String bank;

    /** 还款日（业务到期日）。 */
    private LocalDate repaymentDate;

    /** 抵押起始时间。 */
    private LocalDate startDate;

    /**
     * 抵押到期日。**由 {@link #startDate} + {@link #termMonths} 推导**，不是用户直接填写。
     *
     * <p>保留这个派生列而不是每次现算：{@code listExpiring}（到期预警 / 运营日历）
     * 按它做范围查询，现算会让那条查询无法走索引。
     */
    private LocalDate endDate;

    /** 抵押期限（月）。 */
    private Integer termMonths;

    /** 抵押合同编号。已填写且未软删时唯一（{@code uk_mortgage_contract_no}）。 */
    private String contractNo;

    private String status; // draft / active / released

    private String releaseStatus; // releasing / released / rejected

    private String releaseRemark;

    private LocalDateTime releasedAt;

    /** 单文件字段：V56 之前的资产级抵押用它挂扫描件。多附件走 {@code biz_attachment}。 */
    private Long fileId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 软删时间。只有草稿会被删。 */
    private LocalDateTime deletedAt;
}
