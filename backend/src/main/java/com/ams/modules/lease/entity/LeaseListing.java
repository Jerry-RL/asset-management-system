package com.ams.modules.lease.entity;

import com.ams.modules.lease.dto.ImageRef;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 招租发布（FR-LEASE-001/002）。
 *
 * <p><b>状态机</b>（V59 由二值扩为四值，{@code active} / {@code closed} 语义不变）：
 * {@code pending 待审批 → active 招租中 → closed 已关闭}，审批驳回落到 {@code rejected}。
 * 只有 {@code active} 会被租控派生视图识别为 {@code leasing}（V40 §租控派生），
 * 因此「审批通过后小程序可见」与「资产变为招租中」是同一个条件。
 *
 * <p><b>本类不继承 BaseEntity</b>：{@code lease_listing} 表没有 {@code created_by} /
 * {@code updated_by} 的自动填充约定（{@code created_by} 是 V59 新加的**业务字段**语义
 * ——「发起人」，由服务层显式写入，不是审计列）。
 */
@Data
@TableName("lease_listing")
public class LeaseListing {

    /* 招租状态常量：避免各调用点散落字符串字面量。 */

    /** 待审批：已提交，租控不变，小程序不可见。 */
    public static final String STATUS_PENDING = "pending";
    /** 招租中：审批通过，租控派生为 leasing，小程序可见。 */
    public static final String STATUS_ACTIVE = "active";
    /** 已驳回：审批驳回，原因见 {@link #rejectReason}。 */
    public static final String STATUS_REJECTED = "rejected";
    /** 已关闭：人工结束招租。 */
    public static final String STATUS_CLOSED = "closed";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;
    /** 招租标的单元（asset_unit.id）；是「意向」而非占用，不参与区间互斥（ADR-0019） */
    private Long assetUnitId;

    /** 年租金（元）。表单主字段；提交时同步写入 {@link #rentAmount}。 */
    private BigDecimal annualRent;
    /**
     * 挂牌租金（元）。**V59 起由 {@link #annualRent} 同步写入**，不再是独立输入项
     * —— 保留该列是为了让既有底价校验（{@code EvaluationService.effectiveFloor}）
     * 与小程序端的旧展示口径零改造。
     */
    private BigDecimal rentAmount;
    private Boolean rentNegotiable;
    /** 租金类型（RENT_TYPE 字典：fixed_monthly / fixed_yearly / per_area / per_unit）。 */
    private String rentType;

    /** 封面图附件（sys_file.id）。 */
    private Long coverImageFileId;
    /** 封面图地址（冗余列，公开读 {@code /api/v1/files/object/**}）。 */
    private String coverImageUrl;

    /**
     * 详情列表图原始 JSON（库列 {@code detail_images}）。
     *
     * <p>对**外不序列化**：客户端只应看到解析后的 {@link #detailImages}，
     * 露出原始 JSON 字符串会让每个消费端各写一遍解析。对**内**（MyBatis）仍是普通文本列。
     */
    @JsonIgnore
    @TableField("detail_images")
    private String detailImagesJson;

    /** 详情列表图（非表字段，由服务层解析 {@link #detailImagesJson} 回填）。 */
    @TableField(exist = false)
    private List<ImageRef> detailImages = new ArrayList<>();

    /** 是否推荐（小程序端「推荐招租」）。 */
    private Boolean recommended;
    /** 排序号，越小越前。 */
    private Integer sortNo;
    /** 介绍。 */
    private String intro;

    /** 驳回原因（审批驳回时由审批事件回填，仅查看详情时可见）。 */
    private String rejectReason;

    /** 发起人（sys_user.id）。 */
    private Long createdBy;

    // ---- 非表字段：列表 / 详情回显 ----

    /** 资产编号（非表字段）。 */
    @TableField(exist = false)
    private String assetNo;

    /** 资产名称（非表字段）。 */
    @TableField(exist = false)
    private String assetName;

    private String status;
    private LocalDateTime publishedAt;
    private LocalDateTime closedAt;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
