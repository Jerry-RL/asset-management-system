package com.ams.modules.assetoperator.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 资产运营人员档案（{@code asset_operator}，V60 迁移）：一人一份。
 *
 * <h2>与 {@code user} / {@code user_role} 的边界</h2>
 * 本表是「谁在负责哪些资产的运营」这份**业务档案**，不是账号表，也不参与鉴权：
 * <ul>
 *   <li>{@link #userId} 指向 {@code sys_user}，但本表**不写** {@code user_role}；
 *       这里登记的角色只是档案信息，不产生任何实际授权（已确认口径）—— 否则一个业务模块
 *       的页面就等于多了一条提权路径，而它的权限码不是提权类权限码。</li>
 *   <li>{@link AssetOperatorScope} 只是登记，本期不接入 {@code RbacService} 的越权拦截。</li>
 * </ul>
 *
 * <h2>为什么软删 + 部分唯一索引</h2>
 * {@code uk_asset_operator_user} 是「仅未软删行有效」的部分唯一索引：一个人只能有一份
 * 有效档案，但软删后可以重新登记（否则该人员永久占位、再也建不出来）。
 *
 * <p>软删用 {@link #deletedAt} + 显式 {@code isNull} 过滤，**不用** {@code @TableLogic}：
 * 全仓逻辑删除字段的口径是 {@code deleted:0/1}，与 {@code deleted_at} 范式不一致。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("asset_operator")
public class AssetOperator extends BaseEntity {

    /** 启用。 */
    public static final int STATUS_ENABLED = 1;
    /** 停用（档案保留，只是不再生效）。 */
    public static final int STATUS_DISABLED = 0;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 人员（{@code sys_user.id}）。不设 FK：人员被停用 / 软删后档案仍需可读。 */
    private Long userId;

    /** 1 启用 / 0 停用。停用不删档案 —— 运营范围是历史信息。 */
    private Integer status;

    private String remark;

    private LocalDateTime deletedAt;
}
