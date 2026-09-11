package com.ams.platform.compensation;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 流程步骤与补偿记录（{@code process_step}）。
 *
 * <p>只记录<b>含外部副作用、需要补偿</b>的编排（当前为退款冲正），不是通用流程引擎：
 * 单纯跨上下文但无外部副作用的流程用领域事件即可（见[业务闭环编排设计] §2 编排分层）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("process_step")
public class ProcessStep extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 流程类型：vacate / refund / transfer。 */
    private String processType;

    /** 业务单据 ID。 */
    private Long bizId;

    /** 执行顺序（1 起，逆序补偿依据）。 */
    private Integer stepNo;

    /** 步骤名。 */
    private String stepName;

    /** 见 {@link ProcessStepStatus}。 */
    private String status;

    /** 见 {@link CompensateKind}；为空表示未声明。 */
    private String compensateKind;

    /** 补偿或处置所需入参快照（JSON 文本）；预留字段，当前未写入（人工经 {@code bizId} 反查即可定位）。 */
    private String payload;

    /** 失败原因。 */
    private String error;
}
