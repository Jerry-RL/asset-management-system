package com.ams.modules.revitalization.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.revitalization.entity.RevitalizationTask;
import com.ams.modules.revitalization.mapper.RevitalizationTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 空置资产盘活（FR-VACANT-*）：空置原因、盘活方案、盘活任务。
 */
@Service
public class RevitalizationService {

    private final RevitalizationTaskMapper taskMapper;

    public RevitalizationService(RevitalizationTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    public List<RevitalizationTask> list(String status) {
        return taskMapper.selectList(
                new LambdaQueryWrapper<RevitalizationTask>()
                        .eq(status != null, RevitalizationTask::getStatus, status)
                        .orderByDesc(RevitalizationTask::getId));
    }

    public RevitalizationTask create(RevitalizationTask task) {
        task.setStatus("pending");
        taskMapper.insert(task);
        return task;
    }

    public RevitalizationTask updateStatus(Long id, String status) {
        RevitalizationTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        task.setStatus(status);
        taskMapper.updateById(task);
        return task;
    }
}
