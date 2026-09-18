package com.ams.modules.assetoperator.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 运营人员 × 角色（{@code asset_operator_role}，V60 迁移）。
 *
 * <p>「角色选择」的**记录**：{@code roleId} 指向 {@code role} 表，但本行**不写**
 * {@code user_role} —— 登记不等于授权，真正授权仍在「系统管理 → 角色权限」。
 *
 * <p>明细行**不带软删列**：编辑时全量替换（先删后插），留软删只会制造永不清理的孤儿行。
 * 代价是每次编辑都会换新 id，因此明细行的 id **不能**用作对外引用
 * （与 {@code asset_transfer_record_asset} 同口径）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("asset_operator_role")
public class AssetOperatorRole extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long operatorId;

    private Long roleId;
}
