package com.ams.modules.asset.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 分区楼层（{@code project_zone_floor}，V50 迁移）。
 *
 * <p>楼层是**分区的从属结构**，因此只带 {@link #zoneId}，不带 project_id：
 * 归属判定沿 zone → project 上溯一次即可，多存一个 project_id 只会多一份可能写错的冗余。
 *
 * <p>资产与楼层的关联方式**不是外键**：资产仍靠 {@code asset.zone_id + asset.floor_no}
 * 两个既有字段定位楼层（见 AssetService#pageAssets）。这样本表只承载「楼层清单」，
 * 删改楼层记录不会影响任何存量资产的归属字段。
 *
 * <p>{@link #assetCount} / {@link #assetArea} 是**只读汇总**，由服务层按 floor_no 聚合回填，
 * 不接受写入（与 {@code project_zone.asset_area} 同口径），因此实体上没有对应列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_zone_floor")
public class ProjectZoneFloor extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long zoneId;

    /** 楼层号。负数表示地下层（B1 = -1）。 */
    private Integer floorNo;

    /** 展示名；为空时前端回落为「<floorNo>F」。 */
    private String name;

    private String remark;

    /** 只读：该层资产宗数（非表字段，查询时汇总填充）。 */
    @TableField(exist = false)
    private Long assetCount;

    /** 只读：该层资产面积合计(㎡)（非表字段，查询时汇总填充）。 */
    @TableField(exist = false)
    private BigDecimal assetArea;

    private LocalDateTime deletedAt;
}
