package com.ams.modules.assetoperator.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 运营人员 × 资产运营范围（{@code asset_operator_scope}，V60 迁移）。
 *
 * <p><b>范围是「项目 / 分区 / 资产」三类标的，可混选</b>（已确认口径）。类型常量沿用
 * 抵押记录（V56）的三值口径 —— 同一个语义在全仓只有一种写法，前端类型标签也能复用。
 *
 * <p><b>为什么是 {@code scope_type} + {@code scope_id} 两列而不是三个可空列</b>：
 * 三个可空列无法用一条唯一约束表达「同类型同标的只出现一次」—— 组合唯一索引里
 * 那两列恒为 NULL，而 PG 把 NULL 视为互不相等，去重会**静默失效**。
 *
 * <p>明细行**不带软删列**：编辑时全量替换（先删后插）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("asset_operator_scope")
public class AssetOperatorScope extends BaseEntity {

    /** 范围类型：项目（{@code project.id}）。 */
    public static final String TYPE_PROJECT = "project";
    /** 范围类型：项目分区（{@code project_zone.id}）。 */
    public static final String TYPE_ZONE = "zone";
    /** 范围类型：资产（{@code asset.id}）。 */
    public static final String TYPE_ASSET = "asset";

    /** 本模块认可的范围类型；其余取值一律 400（而不是静默当成资产）。 */
    public static final java.util.List<String> TYPES = java.util.List.of(TYPE_PROJECT, TYPE_ZONE, TYPE_ASSET);

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long operatorId;

    /** project / zone / asset。 */
    private String scopeType;

    /** 范围 id：{@code project.id} / {@code project_zone.id} / {@code asset.id}。 */
    private Long scopeId;
}
