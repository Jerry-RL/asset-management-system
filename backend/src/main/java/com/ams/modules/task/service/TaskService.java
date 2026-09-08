package com.ams.modules.task.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.task.entity.Task;
import com.ams.modules.task.mapper.TaskMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务中心（FR-TASK-001/002）：待办创建、办理、超时升级。
 */
@Service
public class TaskService {

    private final TaskMapper taskMapper;

    public TaskService(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
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

    /** 超时监控（FR-TASK-002）：标记超时任务。 */
    @Transactional
    public int markOverdue() {
        List<Task> pending = taskMapper.selectList(
                new LambdaQueryWrapper<Task>()
                        .eq(Task::getStatus, "pending")
                        .lt(Task::getDeadline, LocalDateTime.now()));
        for (Task task : pending) {
            long minutes = java.time.Duration.between(task.getDeadline(), LocalDateTime.now()).toMinutes();
            task.setOverdueMinutes(minutes);
            taskMapper.updateById(task);
        }
        return pending.size();
    }

    private Task require(Long id) {
        Task task = taskMapper.selectById(id);
        if (task == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return task;
    }
}
