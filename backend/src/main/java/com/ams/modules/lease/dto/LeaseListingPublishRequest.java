package com.ams.modules.lease.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 发布招租请求（FR-LEASE-001/002）。
 *
 * <p>「发布」在 V59 起 = <b>提交审批</b>，不再直接生效；审批通过后 {@code status=active}，
 * 租控派生为 {@code leasing}，小程序端可见。
 *
 * <p>为什么用独立 DTO 而不是直接收 {@code LeaseListing} 实体：表单的图片字段是
 * {@code {fileId,url}} 的嵌套形态（与前端上传组件同形），而实体把它们摊平成了
 * {@code cover_image_file_id} / {@code cover_image_url} / {@code detail_images} 三列。
 * 让控制器接收实体就只能把嵌套形态塞进实体做非表字段，读写路径会互相污染。
 */
@Data
public class LeaseListingPublishRequest {

    /**
     * 资产（必填）。
     *
     * <p>从资产列表行内发起时由前端注入（列表中操作无需选择），
     * 从「招租管理 / 资产租赁管理」新增入口发起时由用户选择。
     */
    private Long assetId;

    /** 计租单元（可选）；为空时按 ADR-0019 自动取该资产第一个可租单元。 */
    private Long assetUnitId;

    /** 租金类型（RENT_TYPE 字典）。 */
    private String rentType;

    /** 年租金（元）。 */
    private BigDecimal annualRent;

    /** 是否可议价；低于评估/备案底价时须置 true 才能提交。 */
    private Boolean rentNegotiable;

    /** 封面图。 */
    private ImageRef coverImage;

    /** 详情列表图。 */
    private List<ImageRef> detailImages = new ArrayList<>();

    /** 是否推荐。 */
    private Boolean recommended;

    /** 排序号（越小越前）。 */
    private Integer sortNo;

    /** 介绍。 */
    private String intro;

    /** 备注 / 招租说明。 */
    private String remark;
}
