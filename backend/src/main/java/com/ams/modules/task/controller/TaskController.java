package com.ams.modules.task.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.task.entity.Task;
import com.ams.modules.task.service.TaskService;
import com.ams.platform.security.Audited;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务中心接口（FR-TASK-*）。
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public ApiResponse<PageResult<Task>> tasks(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) Long companyId) {
        return ApiResponse.ok(taskService.page(page, pageSize, scope, status, taskType, companyId), TraceIdUtil.get());
    }

    @GetMapping("/mine/pending")
    public ApiResponse<List<Task>> myPending() {
        return ApiResponse.ok(taskService.listPending(), TraceIdUtil.get());
    }

    @PostMapping
    @Audited(module = "task", action = "create")
    public ApiResponse<Task> create(@RequestBody Task task) {
        return ApiResponse.ok(taskService.create(task), TraceIdUtil.get());
    }

    @PostMapping("/{taskId}/complete")
    @Audited(module = "task", action = "complete")
    public ApiResponse<Task> complete(@PathVariable Long taskId) {
        return ApiResponse.ok(taskService.complete(taskId), TraceIdUtil.get());
    }
}
