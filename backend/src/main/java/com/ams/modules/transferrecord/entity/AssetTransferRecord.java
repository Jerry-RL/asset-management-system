package com.ams.modules.transferrecord.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 资产调拨记录主单（{@code asset_transfer_record}，V55 迁移）。
 *
 * <p>一张单选**多个**资产，把它们的 {@code responsible_department_id} /
 * {@code responsible_user_id} 一起改成 {@link #toDepartmentId} / {@link #toUserId}。
 *
 * <p>与 {@code AssetTransfer}（资产调拨，单资产、改 {@code operating_company_id}）的区别：
 * 那个是**跨公司**的主体变更并带审批；本模块是**公司内部**的责任交接，状态机只有两态
 * {@code draft → completed}，没有 {@code approving}，也不留 {@code approval_instance_id}
 * 这类悬空字段。{@link #approvalDeadline} 保留但只是业务留痕（不校验、不提醒）。
 *
 * <p>{@link #companyId} 是「资产所属公司」，生效时**不改**它：改公司属于权属流转的职责。
 *
 * <p>软删用 {@link #deletedAt} + 显式 {@code isNull} 过滤，**不用** {@code @TableLogic}：
 * 全仓逻辑删除字段的口径是 {@code deleted:0/1}，与 {@code deleted_at} 范式不一致。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("asset_transfer_record")
public class AssetTransferRecord extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属公司（{@code asset.asset_company_id}）；所选资产必须全部属于它。 */
    private Long companyId;

    /**
     * 前责任部门：业务留痕。
     *
     * <p><b>刻意不做跨资产一致性校验</b>：一张单可以挂来自不同部门的资产（把几个人名下的
     * 资产集中交给一个部门托管），强制「所有资产原部门必须相同」会让这类正常业务做不了。
     * 每个资产真实的原部门由明细行的 {@code from_department_id} 快照承载。
     */
    private Long fromDepartmentId;

    /** 新责任部门：生效时写入 {@code asset.responsible_department_id}。 */
    private Long toDepartmentId;

    /** 新责任人：生效时写入 {@code asset.responsible_user_id}。 */
    private Long toUserId;

    /** 审批截止时间（本期无审批环节，纯记录）。 */
    private LocalDateTime approvalDeadline;

    private String reason;

    private String remark;

    /** draft / completed。 */
    private String status;

    private LocalDateTime effectedAt;

    private LocalDateTime deletedAt;
}
