package com.ams.modules.task.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.config.service.ConfigVersionService;
import com.ams.modules.notification.service.NotificationService;
import com.ams.modules.task.entity.Task;
import com.ams.modules.task.mapper.TaskMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务中心（FR-TASK-001/002）：待办创建、办理、临期提醒、超时升级。
 */
@Service
public class TaskService {

    private final TaskMapper taskMapper;
    private final NotificationService notificationService;
    private final ConfigVersionService configVersionService;

    public TaskService(
            TaskMapper taskMapper,
            NotificationService notificationService,
            ConfigVersionService configVersionService) {
        this.taskMapper = taskMapper;
        this.notificationService = notificationService;
        this.configVersionService = configVersionService;
    }

    public PageResult<Task> page(long page, long pageSize, String scope, String status,
            String taskType, Long companyId) {
        Long userId = SecurityUtils.currentUserIdOrNull();
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<Task>()
                .eq("mine".equals(scope), Task::getAssigneeId, userId)
                .eq(status != null, Task::getStatus, status)
                .eq(taskType != null, Task::getTaskType, taskType)
                .eq(companyId != null, Task::getCompanyId, companyId)
                .orderByDesc(Task::getId);
        Page<Task> result = taskMapper.selectPage(new Page<>(page, pageSize), wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    public Task create(Task task) {
        if (task.getStatus() == null) {
            task.setStatus("pending");
        }
        task.setOverdueMinutes(0L);
        taskMapper.insert(task);
        return task;
    }

    public Task complete(Long taskId) {
        Task task = require(taskId);
        task.setStatus("completed");
        task.setCompletedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    public Task get(Long id) {
        return require(id);
    }

    public List<Task> listPending() {
        Long userId = SecurityUtils.currentUserIdOrNull();
        return taskMapper.selectList(
                new LambdaQueryWrapper<Task>()
                        .eq(Task::getAssigneeId, userId)
                        .eq(Task::getStatus, "pending")
                        .orderByAsc(Task::getDeadline));
    }

    /**
     * 超时监控（FR-TASK-002）：临期提醒 + 超时标记 + 升级督办（幂等）。
     * @return [reminded, overdue, escalated]
     */
    @Transactional
    public int[] monitorTimeout() {
        LocalDateTime now = LocalDateTime.now();
        int remindHours = 24;
        try {
            remindHours = Integer.parseInt(configVersionService.getValue("task.remind_hours", "24"));
        } catch (NumberFormatException ignored) {
            remindHours = 24;
        }
        int reminded = 0;
        int overdue = 0;
        int escalated = 0;

        // 临期提醒
        List<Task> near = taskMapper.selectList(
                new LambdaQueryWrapper<Task>()
                        .eq(Task::getStatus, "pending")
                        .isNull(Task::getRemindedAt)
                        .isNotNull(Task::getDeadline)
                        .gt(Task::getDeadline, now)
                        .le(Task::getDeadline, now.plusHours(remindHours)));
        for (Task task : near) {
            notificationService.sendByTemplate(
                    "task_remind",
                    task.getAssigneeId(),
                    Map.of(
                            "taskId", String.valueOf(task.getId()),
                            "taskType", task.getTaskType() == null ? "" : task.getTaskType(),
                            "deadline", String.valueOf(task.getDeadline())),
                    "task",
                    task.getId());
            task.setRemindedAt(now);
            taskMapper.updateById(task);
            reminded++;
        }

        List<Task> pending = taskMapper.selectList(
                new LambdaQueryWrapper<Task>()
                        .eq(Task::getStatus, "pending")
                        .isNotNull(Task::getDeadline)
                        .lt(Task::getDeadline, now));
        for (Task task : pending) {
            long minutes = java.time.Duration.between(task.getDeadline(), now).toMinutes();
            task.setOverdueMinutes(minutes);
            overdue++;
            if (task.getEscalatedAt() == null) {
                Task esc = new Task();
                esc.setTaskType("task_escalate");
                esc.setRefId(task.getId());
                esc.setRefNo(task.getTaskType());
                esc.setCompanyId(task.getCompanyId());
                esc.setAssigneeId(task.getAssigneeId());
                esc.setDeadline(now.plusDays(1));
                esc.setStatus("pending");
                esc.setOverdueMinutes(0L);
                taskMapper.insert(esc);

                notificationService.sendByTemplate(
                        "task_overdue",
                        task.getAssigneeId(),
                        Map.of(
                                "taskId", String.valueOf(task.getId()),
                                "taskType", task.getTaskType() == null ? "" : task.getTaskType(),
                                "overdueMinutes", String.valueOf(minutes)),
                        "task",
                        task.getId());
                task.setEscalatedAt(now);
                escalated++;
            }
            taskMapper.updateById(task);
        }
        return new int[]{reminded, overdue, escalated};
    }

    /** @deprecated 使用 monitorTimeout */
    @Transactional
    public int markOverdue() {
        return monitorTimeout()[1];
    }

    public List<Task> listPendingByRef(String taskType, Long refId) {
        return taskMapper.selectList(
                new LambdaQueryWrapper<Task>()
                        .eq(Task::getTaskType, taskType)
                        .eq(Task::getRefId, refId)
                        .eq(Task::getStatus, "pending"));
    }

    /** 按任务类型前缀列出待办（如 dunning_）。 */
    public List<Task> listPendingByTypePrefix(String typePrefix) {
        return taskMapper.selectList(
                new LambdaQueryWrapper<Task>()
                        .likeRight(Task::getTaskType, typePrefix)
                        .eq(Task::getStatus, "pending")
                        .orderByAsc(Task::getDeadline));
    }

    private Task require(Long id) {
        Task task = taskMapper.selectById(id);
        if (task == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return task;
    }
}
