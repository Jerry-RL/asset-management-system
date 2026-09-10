package com.ams.modules.asset.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 项目分区（FR-AST-001）：新增项目第二步「项目分区配置」。
 *
 * <p>分区面积不在本表维护，由「该分区下资产面积合计」实时统计得出（见 {@link #assetArea}）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_zone")
public class ProjectZone extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    /** 分区名称，如 A区、1号楼 */
    private String name;
    /** 分区编码 */
    private String code;
    /** 排序 */
    private Integer sort;
    /** 备注 */
    private String remark;

    /** 分区面积(㎡)：分区下资产面积合计（非表字段，查询时汇总填充） */
    @TableField(exist = false)
    private BigDecimal assetArea;

    /** 分区下资产数量（非表字段，查询时汇总填充） */
    @TableField(exist = false)
    private Long assetCount;
}
