package com.ams.modules.asset.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project")
public class Project extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;
    private String name;
    private String address;
    // 省市区
    private String province;
    private String city;
    private String district;
    // 项目类型：park 园区 / building 楼宇 / land 地块 / other 其他
    private String type;
    // 项目图片（对象存储 URL 与附件 ID）
    private String imageUrl;
    private Long imageFileId;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private Integer status;

    // ---- 项目下资产统计（非表字段，列表/卡片实时聚合填充，口径见 AssetLeaseGroups） ----

    /** 资产宗数：项目下资产数量 */
    @TableField(exist = false)
    private Long assetCount;

    /** 资产面积合计(㎡) */
    @TableField(exist = false)
    private BigDecimal assetArea;

    /** 闲置宗数：空置 + 招租中 */
    @TableField(exist = false)
    private Long idleCount;

    /** 盘活宗数：在租 + 部分出租 */
    @TableField(exist = false)
    private Long revitalizedCount;

    /** 资产利用率(%)：按宗数计，(资产宗数 - 闲置宗数) / 资产宗数 */
    @TableField(exist = false)
    private BigDecimal utilizationRate;
}
