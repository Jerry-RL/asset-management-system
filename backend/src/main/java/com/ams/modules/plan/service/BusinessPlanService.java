package com.ams.modules.plan.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.dashboard.service.DashboardService;
import com.ams.modules.plan.dto.PlanDeviationResult;
import com.ams.modules.plan.entity.BusinessPlan;
import com.ams.modules.plan.mapper.BusinessPlanMapper;
import com.ams.modules.task.entity.Task;
import com.ams.modules.task.service.TaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 经营计划与预算（FR-PLAN-*）：年度/月度计划、计划 vs 实际偏差、超阈督办。
 */
@Service
public class BusinessPlanService {

    private final BusinessPlanMapper planMapper;
    private final DashboardService dashboardService;
    private final TaskService taskService;

    public BusinessPlanService(
            BusinessPlanMapper planMapper,
            DashboardService dashboardService,
            TaskService taskService) {
        this.planMapper = planMapper;
        this.dashboardService = dashboardService;
        this.taskService = taskService;
    }

    public List<BusinessPlan> list(Long companyId, Integer year) {
        return planMapper.selectList(
                new LambdaQueryWrapper<BusinessPlan>()
                        .eq(companyId != null, BusinessPlan::getCompanyId, companyId)
                        .eq(year != null, BusinessPlan::getPlanYear, year)
                        .orderByDesc(BusinessPlan::getPlanYear)
                        .orderByDesc(BusinessPlan::getPlanMonth));
    }

    public BusinessPlan create(BusinessPlan plan) {
        plan.setVersion(1);
        plan.setStatus("active");
        if (plan.getDeviationThreshold() == null) {
            plan.setDeviationThreshold(new BigDecimal("0.05"));
        }
        planMapper.insert(plan);
        return plan;
    }

    public BusinessPlan update(Long id, BusinessPlan plan) {
        BusinessPlan existing = planMapper.selectById(id);
        if (existing == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        plan.setId(id);
        plan.setVersion(existing.getVersion() + 1);
        planMapper.updateById(plan);
        return planMapper.selectById(id);
    }

    /** 单计划偏差计算（不写库）。 */
    public PlanDeviationResult evaluate(Long planId) {
        BusinessPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "经营计划不存在");
        }
        return evaluate(plan, dashboardService.operations(plan.getCompanyId()));
    }

    /** 扫描全部 active 计划，超阈生成督办任务。 */
    @Transactional
    public List<PlanDeviationResult> scanDeviations() {
        List<BusinessPlan> plans = planMapper.selectList(
                new LambdaQueryWrapper<BusinessPlan>().eq(BusinessPlan::getStatus, "active"));
        List<PlanDeviationResult> results = new ArrayList<>();
        for (BusinessPlan plan : plans) {
            Map<String, Object> ops = dashboardService.operations(plan.getCompanyId());
            PlanDeviationResult result = evaluate(plan, ops);
            results.add(result);
            plan.setLastScannedAt(LocalDateTime.now());
            planMapper.updateById(plan);
            if (result.isOverdue()) {
                ensureSuperviseTask(plan, result);
            }
        }
        return results;
    }

    private PlanDeviationResult evaluate(BusinessPlan plan, Map<String, Object> ops) {
        BigDecimal threshold = plan.getDeviationThreshold() == null
                ? new BigDecimal("0.05")
                : plan.getDeviationThreshold();
        PlanDeviationResult result = new PlanDeviationResult();
        result.setPlanId(plan.getId());
        result.setCompanyId(plan.getCompanyId());
        result.setPlanYear(plan.getPlanYear());
        result.setPlanMonth(plan.getPlanMonth());
        result.setThreshold(threshold);

        BigDecimal leasedRate = toBd(ops.get("leasedRate"));
        BigDecimal collectionRate = toBd(ops.get("collectionRate"));
        BigDecimal received = toBd(ops.get("received"));
        BigDecimal vacantArea = toBd(ops.get("vacantArea"));

        addMetric(result, "rentalRate", plan.getTargetRentalRate(), leasedRate, threshold, false);
        addMetric(result, "collectionRate", plan.getTargetCollectionRate(), collectionRate, threshold, false);
        addMetric(result, "income", plan.getTargetIncome(), received, threshold, false);
        // 空置面积：实际高于目标为偏差
        addMetric(result, "vacantArea", plan.getTargetVacantArea(), vacantArea, threshold, true);

        result.setOverdue(result.getMetrics().stream().anyMatch(PlanDeviationResult.MetricDeviation::isExceeded));
        return result;
    }

    private void addMetric(
            PlanDeviationResult result,
            String name,
            BigDecimal target,
            BigDecimal actual,
            BigDecimal threshold,
            boolean higherWorse) {
        if (target == null) {
            return;
        }
        PlanDeviationResult.MetricDeviation m = new PlanDeviationResult.MetricDeviation();
        m.setMetric(name);
        m.setTarget(target);
        m.setActual(actual == null ? BigDecimal.ZERO : actual);
        if (target.compareTo(BigDecimal.ZERO) == 0) {
            m.setDeviationRatio(BigDecimal.ZERO);
            m.setExceeded(false);
        } else {
            BigDecimal gap = higherWorse
                    ? m.getActual().subtract(target)
                    : target.subtract(m.getActual());
            BigDecimal ratio = gap.max(BigDecimal.ZERO).divide(target.abs(), 4, RoundingMode.HALF_UP);
            m.setDeviationRatio(ratio);
            m.setExceeded(ratio.compareTo(threshold) > 0);
        }
        result.getMetrics().add(m);
    }

    private void ensureSuperviseTask(BusinessPlan plan, PlanDeviationResult result) {
        List<Task> pending = taskService.listPendingByRef("plan_supervise", plan.getId());
        if (!pending.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("计划偏差督办");
        for (PlanDeviationResult.MetricDeviation m : result.getMetrics()) {
            if (m.isExceeded()) {
                sb.append(' ').append(m.getMetric()).append('=')
                        .append(m.getDeviationRatio());
            }
        }
        Task task = new Task();
        task.setTaskType("plan_supervise");
        task.setRefId(plan.getId());
        task.setRefNo(sb.toString());
        task.setCompanyId(plan.getCompanyId());
        task.setDeadline(LocalDateTime.now().plusDays(7));
        task.setStatus("pending");
        taskService.create(task);
    }

    private static BigDecimal toBd(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(v.toString());
    }
}
