package com.ams.platform.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * 审批完成事件（DSD §4.8）：ApprovalCompleted → 触发缴费计划生成、通知等。
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApprovalCompletedEvent extends DomainEvent {

    private final String bizType;
    private final Long bizId;
    private final boolean approved;

    /**
     * 审批意见（通过或驳回时审批人填写的内容）。
     *
     * <p><b>为什么必须进事件而不是让监听器回查 {@code approval_task.comment}</b>：
     * 招租发布这类单据要求「驳回原因」直接落在业务行上（{@code lease_listing.reject_reason}），
     * 而监听器拿到的只有 {@code (bizType, bizId, approved)}。回查需要把
     * {@code ApprovalTaskMapper} 注入每个业务服务，且「取哪一条任务的意见」在多节点流程下
     * 本身就有歧义 —— 意见是审批动作的**结果载荷**，理应由事件携带。
     *
     * <p>可为空：V59 之前写入发件箱的存量事件没有这个字段，反序列化后为 {@code null}。
     */
    private final String comment;

    /** 兼容三参构造（存量调用点 / 测试）；意见为空。 */
    public ApprovalCompletedEvent(String bizType, Long bizId, boolean approved) {
        this(bizType, bizId, approved, null);
    }

    @JsonCreator
    public ApprovalCompletedEvent(
            @JsonProperty("bizType") String bizType,
            @JsonProperty("bizId") Long bizId,
            @JsonProperty("approved") boolean approved,
            @JsonProperty("comment") String comment) {
        this.bizType = bizType;
        this.bizId = bizId;
        this.approved = approved;
        this.comment = comment;
    }

    @Override
    public String aggregateType() {
        return bizType;
    }

    @Override
    public Long aggregateId() {
        return bizId;
    }
}
