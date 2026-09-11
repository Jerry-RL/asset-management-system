package com.ams.modules.asset.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 占用生效层（{@code asset_occupancy}）：单据审批通过时写入，<b>区间收口代替删除</b>。
 *
 * <p>同一单元同一时点最多一条生效占用，由数据库 EXCLUDE 约束
 * （{@code ex_occupancy_unit_no_overlap}）硬保证——服务层校验只负责给出可读错误，
 * 不作为正确性依赖。
 *
 * <p>{@link #dateTo} 为空表示无固定期限（在租合同）；释放时写入收口日，
 * 保留完整历史以支持「任意时点占用回溯」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("asset_occupancy")
public class AssetOccupancy extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;
    /** 占用粒度：计租单元（NOT NULL，由「每资产至少一个单元」不变量保证） */
    private Long assetUnitId;
    /** 见 {@link com.ams.modules.asset.OccupancyType} */
    private String occupancyType;
    /** 来源单据类型：contract / occupation_order / self_use_order / disposal_order */
    private String subjectType;
    /** 来源单据 ID */
    private Long subjectId;
    /** 占用面积；原子单元模型下恒等于 asset_unit.area */
    private BigDecimal area;
    private LocalDate dateFrom;
    /** 为空表示无固定期限（在租合同） */
    private LocalDate dateTo;
    /** 见 {@link com.ams.modules.asset.OccupancyBizStatus} */
    private String bizStatus;
    /** false 的占用不参与互斥（预留给非排他记录，如巡查临时进入） */
    private Boolean exclusive;
    private LocalDateTime releasedAt;
    private String remark;
}
