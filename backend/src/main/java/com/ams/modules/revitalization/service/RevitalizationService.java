package com.ams.modules.revitalization.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.alert.entity.AlertRecord;
import com.ams.modules.alert.service.AlertService;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.revitalization.entity.RevitalizationTask;
import com.ams.modules.revitalization.mapper.RevitalizationTaskMapper;
import com.ams.modules.task.entity.Task;
import com.ams.modules.task.service.TaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 空置资产盘活（FR-VACANT-*）：空置原因、盘活任务、自动联动、超期督办、KPI 看板。
 */
@Service
public class RevitalizationService {

    private final RevitalizationTaskMapper taskMapper;
    private final AssetMapper assetMapper;
    private final AlertService alertService;
    private final TaskService taskService;

    public RevitalizationService(
            RevitalizationTaskMapper taskMapper,
            AssetMapper assetMapper,
            AlertService alertService,
            TaskService taskService) {
        this.taskMapper = taskMapper;
        this.assetMapper = assetMapper;
        this.alertService = alertService;
        this.taskService = taskService;
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

    /**
     * 租控变空置时自动建盘活任务（幂等）。
     */
    @Transactional
    public RevitalizationTask createOnVacant(Long assetId, String vacantReason, String planType) {
        if (assetId == null) {
            return null;
        }
        Long open = taskMapper.selectCount(
                new LambdaQueryWrapper<RevitalizationTask>()
                        .eq(RevitalizationTask::getAssetId, assetId)
                        .in(RevitalizationTask::getStatus, "pending", "listing"));
        if (open != null && open > 0) {
            return taskMapper.selectOne(
                    new LambdaQueryWrapper<RevitalizationTask>()
                            .eq(RevitalizationTask::getAssetId, assetId)
                            .in(RevitalizationTask::getStatus, "pending", "listing")
                            .last("LIMIT 1"));
        }
        Asset asset = assetMapper.selectById(assetId);
        if (asset != null) {
            asset.setVacantReason(vacantReason);
            asset.setVacantSince(LocalDateTime.now());
            assetMapper.updateById(asset);
        }
        RevitalizationTask task = new RevitalizationTask();
        task.setAssetId(assetId);
        task.setVacantReason(vacantReason == null ? "空置" : vacantReason);
        task.setPlanType(planType == null ? "招租盘活" : planType);
        task.setTargetDate(LocalDate.now().plusDays(30));
        task.setStatus("pending");
        taskMapper.insert(task);

        Task todo = new Task();
        todo.setTaskType("revitalization");
        todo.setRefId(task.getId());
        todo.setRefNo("VACANT-" + assetId);
        todo.setDeadline(LocalDateTime.now().plusDays(30));
        todo.setStatus("pending");
        taskService.create(todo);
        return task;
    }

    /** 签约/招租成功后关闭盘活任务。 */
    @Transactional
    public void closeOnLeased(Long assetId) {
        List<RevitalizationTask> open = taskMapper.selectList(
                new LambdaQueryWrapper<RevitalizationTask>()
                        .eq(RevitalizationTask::getAssetId, assetId)
                        .in(RevitalizationTask::getStatus, "pending", "listing"));
        for (RevitalizationTask task : open) {
            task.setStatus("signed");
            taskMapper.updateById(task);
        }
        Asset asset = assetMapper.selectById(assetId);
        if (asset != null) {
            asset.setVacantReason(null);
            asset.setVacantSince(null);
            assetMapper.updateById(asset);
        }
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

    /** 空置超 90/180 天督办（定时任务）。 */
    @Transactional
    public int escalateLongVacant() {
        List<Asset> vacant = assetMapper.selectList(
                new LambdaQueryWrapper<Asset>()
                        .eq(Asset::getLeaseControlStatus, LeaseControlStatus.VACANT)
                        .isNotNull(Asset::getVacantSince));
        int n = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Asset asset : vacant) {
            long days = ChronoUnit.DAYS.between(asset.getVacantSince(), now);
            if (days < 90) {
                continue;
            }
            AlertRecord alert = new AlertRecord();
            alert.setAlertType("vacant_overdue");
            alert.setSubType(days >= 180 ? "d180" : "d90");
            alert.setLevel(days >= 180 ? 3 : 2);
            alert.setBizType("asset");
            alert.setBizId(asset.getId());
            alert.setTitle(days >= 180 ? "空置超180天督办" : "空置超90天督办");
            alert.setContent("资产 " + asset.getAssetNo() + " 已空置 " + days + " 天，请加快盘活");
            alertService.trigger(alert);
            createOnVacant(asset.getId(), asset.getVacantReason(), "超期督办");
            n++;
        }
        return n;
    }

    public Map<String, Object> dashboard() {
        List<Asset> vacant = assetMapper.selectList(
                new LambdaQueryWrapper<Asset>().eq(Asset::getLeaseControlStatus, LeaseControlStatus.VACANT));
        List<RevitalizationTask> tasks = list(null);
        long pending = tasks.stream().filter(t -> "pending".equals(t.getStatus())).count();
        long listing = tasks.stream().filter(t -> "listing".equals(t.getStatus())).count();
        long signed = tasks.stream().filter(t -> "signed".equals(t.getStatus()) || "done".equals(t.getStatus())).count();

        Map<Long, Map<String, Object>> byProject = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();
        for (Asset a : vacant) {
            Long pid = a.getProjectId() == null ? 0L : a.getProjectId();
            Map<String, Object> row = byProject.computeIfAbsent(pid, k -> {
                Map<String, Object> m = new HashMap<>();
                m.put("projectId", k);
                m.put("vacantCount", 0L);
                m.put("vacantArea", java.math.BigDecimal.ZERO);
                m.put("over90", 0L);
                return m;
            });
            row.put("vacantCount", ((Long) row.get("vacantCount")) + 1);
            java.math.BigDecimal area = a.getArea() == null ? java.math.BigDecimal.ZERO : a.getArea();
            row.put("vacantArea", ((java.math.BigDecimal) row.get("vacantArea")).add(area));
            if (a.getVacantSince() != null && ChronoUnit.DAYS.between(a.getVacantSince(), now) >= 90) {
                row.put("over90", ((Long) row.get("over90")) + 1);
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("vacantTotal", vacant.size());
        result.put("taskPending", pending);
        result.put("taskListing", listing);
        result.put("taskSigned", signed);
        result.put("conversionRate", tasks.isEmpty() ? 0
                : (double) signed / tasks.size());
        result.put("byProject", new ArrayList<>(byProject.values()));
        return result;
    }
}
