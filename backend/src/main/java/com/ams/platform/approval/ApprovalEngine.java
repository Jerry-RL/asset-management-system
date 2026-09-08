package com.ams.platform.approval;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.platform.approval.entity.ApprovalFlowDef;
import com.ams.platform.approval.entity.ApprovalInstance;
import com.ams.platform.approval.entity.ApprovalTask;
import com.ams.platform.approval.mapper.ApprovalFlowDefMapper;
import com.ams.platform.approval.mapper.ApprovalInstanceMapper;
import com.ams.platform.approval.mapper.ApprovalTaskMapper;
import com.ams.platform.event.ApprovalCompletedEvent;
import com.ams.platform.event.DomainEventPublisher;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 轻量审批引擎（ADR-0009 / DSD §4.7）。
 * 流程定义 JSON：{ "bizType": "...", "nodes": [ { "id": "...", "type": "serial", "role": "..." } ] }
 * 简化实现：单节点串行审批（每个 node 顺序处理），完成后发布 ApprovalCompleted 事件。
 */
@Service
public class ApprovalEngine {

    private final ApprovalFlowDefMapper flowDefMapper;
    private final ApprovalInstanceMapper instanceMapper;
    private final ApprovalTaskMapper taskMapper;
    private final DomainEventPublisher eventPublisher;

    public ApprovalEngine(
            ApprovalFlowDefMapper flowDefMapper,
            ApprovalInstanceMapper instanceMapper,
            ApprovalTaskMapper taskMapper,
            DomainEventPublisher eventPublisher) {
        this.flowDefMapper = flowDefMapper;
        this.instanceMapper = instanceMapper;
        this.taskMapper = taskMapper;
        this.eventPublisher = eventPublisher;
    }

    /** 启动审批流程。 */
    @Transactional
    public ApprovalInstance start(String bizType, Long bizId) {
        ApprovalFlowDef def = flowDefMapper.selectOne(
                new LambdaQueryWrapper<ApprovalFlowDef>()
                        .eq(ApprovalFlowDef::getBizType, bizType)
                        .eq(ApprovalFlowDef::getEnabled, true)
                        .orderByDesc(ApprovalFlowDef::getVersion)
                        .last("LIMIT 1"));
        if (def == null) {
            // 无流程定义时视为自动通过（如某些简单单据）
            ApprovalInstance instance = new ApprovalInstance();
            instance.setBizType(bizType);
            instance.setBizId(bizId);
            instance.setStatus("approved");
            instance.setSubmittedBy(SecurityUtils.currentUserIdOrNull());
            instance.setSubmittedAt(LocalDateTime.now());
            instance.setCompletedAt(LocalDateTime.now());
            instanceMapper.insert(instance);
            eventPublisher.publishAfterCommit(new ApprovalCompletedEvent(bizType, bizId, true));
            return instance;
        }
        ApprovalInstance instance = new ApprovalInstance();
        instance.setFlowDefId(def.getId());
        instance.setBizType(bizType);
        instance.setBizId(bizId);
        instance.setStatus("pending");
        instance.setSubmittedBy(SecurityUtils.currentUserIdOrNull());
        instance.setSubmittedAt(LocalDateTime.now());
        instance.setCurrentNode("node0");
        instanceMapper.insert(instance);

        // 创建第一个节点的待办任务
        ApprovalTask task = new ApprovalTask();
        task.setInstanceId(instance.getId());
        task.setNodeId("node0");
        task.setStatus("pending");
        taskMapper.insert(task);
        return instance;
    }

    /** 审批通过当前节点。 */
    @Transactional
    public ApprovalInstance approve(Long instanceId, String comment) {
        ApprovalInstance instance = require(instanceId);
        if (!"pending".equals(instance.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "审批已结束");
        }
        // 完成当前节点任务
        completeCurrentTask(instanceId, comment, true);

        // 简化：单节点通过即整体通过
        instance.setStatus("approved");
        instance.setCompletedAt(LocalDateTime.now());
        instanceMapper.updateById(instance);
        eventPublisher.publishAfterCommit(
                new ApprovalCompletedEvent(instance.getBizType(), instance.getBizId(), true));
        return instance;
    }

    /** 驳回。 */
    @Transactional
    public ApprovalInstance reject(Long instanceId, String comment) {
        ApprovalInstance instance = require(instanceId);
        if (!"pending".equals(instance.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "审批已结束");
        }
        completeCurrentTask(instanceId, comment, false);
        instance.setStatus("rejected");
        instance.setCompletedAt(LocalDateTime.now());
        instanceMapper.updateById(instance);
        eventPublisher.publishAfterCommit(
                new ApprovalCompletedEvent(instance.getBizType(), instance.getBizId(), false));
        return instance;
    }

    public List<ApprovalTask> myPendingTasks() {
        Long userId = SecurityUtils.currentUserIdOrNull();
        return taskMapper.selectList(
                new LambdaQueryWrapper<ApprovalTask>()
                        .eq(ApprovalTask::getStatus, "pending"));
    }

    /** 按业务类型与业务单号查找进行中的审批实例。 */
    public ApprovalInstance findPendingInstance(String bizType, Long bizId) {
        return instanceMapper.selectOne(
                new LambdaQueryWrapper<ApprovalInstance>()
                        .eq(ApprovalInstance::getBizType, bizType)
                        .eq(ApprovalInstance::getBizId, bizId)
                        .eq(ApprovalInstance::getStatus, "pending")
                        .last("LIMIT 1"));
    }

    private ApprovalInstance require(Long instanceId) {
        ApprovalInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "审批实例不存在");
        }
        return instance;
    }

    private void completeCurrentTask(Long instanceId, String comment, boolean done) {
        List<ApprovalTask> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<ApprovalTask>()
                        .eq(ApprovalTask::getInstanceId, instanceId)
                        .eq(ApprovalTask::getStatus, "pending"));
        for (ApprovalTask task : tasks) {
            task.setStatus("done");
            task.setComment(comment);
            task.setActedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        }
    }
}
