package com.ams.modules.asset.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 资产计租单元（{@code asset_unit}）—— 计租最小原子单位（ADR-0019 决策 A1）。
 *
 * <p>单元是合同 / 招租 / 占用 / 计量的<b>唯一挂载点</b>。部分出租或部分占用
 * 通过拆分单元表达，而不是在面积字段上做减法——这是「互斥可由数据库保证」的前提。
 *
 * <p>{@link #unitStatus} 是<b>物化派生列</b>，只由
 * {@link com.ams.modules.asset.service.LeaseStatusDeriver} 从
 * {@code v_unit_lease_status} 写入，业务代码禁止直改。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("asset_unit")
public class AssetUnit extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;
    /** 资产内唯一：{@code <资产编号>-U<序号>}，见 UnitNoGenerator */
    private String unitNo;
    private String unitName;
    /** 单元面积；0 表示「面积待补」占位单元，不参与面积统计 */
    private BigDecimal area;
    /** 可租面积；为空表示等同 area */
    private BigDecimal rentableArea;
    /** 单元底价；为空则回退 asset.base_rent_floor */
    private BigDecimal baseRent;
    /** 物化派生列（vacant/leasing/leased/self_use/occupied/disposing/exited） */
    private String unitStatus;
    private Integer floorNo;
    private Integer sort;
    private String remark;

    @Version
    private Integer version;

    /**
     * 软删除时间；仅由单元拆分写入。
     * 不使用 {@code @TableLogic}：全局逻辑删除字段口径为 {@code deleted:0/1}，
     * 与既有 {@code deleted_at TIMESTAMPTZ} 范式不一致，故沿用显式过滤。
     */
    private LocalDateTime deletedAt;
}
