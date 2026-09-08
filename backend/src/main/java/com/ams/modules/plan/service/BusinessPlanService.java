package com.ams.modules.plan.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.plan.entity.BusinessPlan;
import com.ams.modules.plan.mapper.BusinessPlanMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 经营计划与预算（FR-PLAN-*）：年度/月度计划、版本留痕。
 */
@Service
public class BusinessPlanService {

    private final BusinessPlanMapper planMapper;

    public BusinessPlanService(BusinessPlanMapper planMapper) {
        this.planMapper = planMapper;
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
}
