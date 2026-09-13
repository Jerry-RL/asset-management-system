package com.ams.modules.ownership.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 权属流转主单（{@code ownership_transfer}，V54 迁移）。
 *
 * <p>一张单选**多个**资产，按 {@link #transferScope} 决定改写资产的
 * {@code property_company_id} 还是 {@code operating_company_id}（或两个都改）。
 *
 * <p>状态机只有两态：{@code draft → completed}。本期不接审批引擎（设计 §3.2），
 * 所以没有 {@code approving}，也没有 {@code approval_instance_id} / {@code reject_reason}
 * —— 不留悬空字段。{@link #approvalDeadline} 保留但只是业务留痕（不校验、不提醒）。
 *
 * <p>软删用 {@link #deletedAt} + 显式 {@code isNull} 过滤，**不用** {@code @TableLogic}：
 * 全仓逻辑删除字段的口径是 {@code deleted:0/1}，与 {@code deleted_at} 范式不一致
 * （见 {@code AssetUnit} 的注释）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ownership_transfer")
public class OwnershipTransfer extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** internal 内部流转 / external 外部流转，取字典 transfer_direction。 */
    private String direction;

    /** both 经营权且产权 / property 产权 / operating 经营权，取字典 transfer_scope。 */
    private String transferScope;

    private Long fromCompanyId;

    private Long toCompanyId;

    /** allocate 直接划拨 / purchase 购买流转 / auction 拍卖流转，取字典 transfer_mode。 */
    private String transferMode;

    /** 内员时非空（并据此外查 sys_user.name 覆盖姓名快照）；外部人员为 null。 */
    private Long applicantUserId;

    /** 姓名快照。内员由服务端覆盖，外部人员由用户手填。 */
    private String applicantName;

    /** 审批截止时间。本期无审批环节，纯记录字段。 */
    private LocalDateTime approvalDeadline;

    /** 金额(万元)，2 位小数。 */
    private BigDecimal amountWan;

    private String reason;

    /** 生效时生成的交接清单快照（JSON 文本，形态见 AssetHandoverBuilder）。 */
    private String handoverJson;

    /** draft / completed。 */
    private String status;

    private LocalDateTime effectedAt;

    private LocalDateTime deletedAt;
}
