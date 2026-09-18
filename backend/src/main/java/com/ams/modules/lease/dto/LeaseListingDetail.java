package com.ams.modules.lease.dto;

import com.ams.modules.asset.entity.Asset;
import com.ams.modules.lease.entity.LeaseListing;
import lombok.Data;

/**
 * 招租发布详情（FR-LEASE-001）。
 *
 * <p>需求约定「资产详情与发起人信息在**查看详情**时可见」—— 列表只给招租本身的字段，
 * 详情才把资产台账信息与发起人一并带出。
 *
 * <p>资产用 {@link Asset} 整实体而不是挑几个字段：`Asset` 上的
 * {@code projectName} / {@code zoneName} / {@code assetCompanyName} /
 * {@code responsibleUserName} 等非表字段本身就是「详情回显」的标准载体
 * （见 {@code AssetService#fillDisplayNames}），另挑字段会多一处需要同步维护的映射。
 */
@Data
public class LeaseListingDetail {

    /** 招租字段（含封面图、详情列表图、年租金、推荐、排序、介绍、驳回原因）。 */
    private LeaseListing listing;

    /** 资产详情（查看详情时可见）。 */
    private Asset asset;

    /** 发起人姓名（查看详情时可见）。 */
    private String createdByName;

    /** 发起人联系电话（查看详情时可见）。 */
    private String createdByPhone;
}
