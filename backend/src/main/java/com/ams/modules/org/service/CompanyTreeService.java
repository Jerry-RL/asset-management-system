package com.ams.modules.org.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.mapper.CompanyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 公司树图谱遍历：邻接表 + DFS/BFS，支撑子树过滤、面包屑、防环校验与数据范围扩展。
 *
 * <p>公司树以 {@code company.parent_id} 构成森林（通常单根「母公司」），本类只做遍历与校验，
 * 不负责持久化。
 */
@Service
public class CompanyTreeService {

    private final CompanyMapper companyMapper;

    public CompanyTreeService(CompanyMapper companyMapper) {
        this.companyMapper = companyMapper;
    }

    /** 全部启用公司（按 sort、id 排序）。 */
    public List<Company> listCompanies() {
        return companyMapper.selectList(new LambdaQueryWrapper<Company>()
                .orderByAsc(Company::getSort)
                .orderByAsc(Company::getId));
    }

    /** 邻接表：parentId → 子 id 列表。 */
    public Map<Long, List<Long>> buildChildrenIndex(List<Company> companies) {
        Map<Long, List<Long>> index = new LinkedHashMap<>();
        for (Company c : companies) {
            // 上级不存在时按根节点处理，避免脏数据导致节点丢失
            if (c.getParentId() == null) {
                continue;
            }
            index.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c.getId());
        }
        return index;
    }

    public Map<Long, List<Long>> childrenIndex() {
        return buildChildrenIndex(listCompanies());
    }

    /**
     * 子树公司 ID（含自身）。
     *
     * @return 含自身的 id 集合；{@code rootId} 为 null 时返回空集合
     */
    public Set<Long> descendantIds(Long rootId) {
        if (rootId == null) {
            return Set.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        result.add(rootId);
        Map<Long, List<Long>> index = childrenIndex();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            Long current = stack.pop();
            for (Long child : index.getOrDefault(current, List.of())) {
                if (result.add(child)) {
                    stack.push(child);
                }
            }
        }
        return result;
    }

    /** 祖先链（root → 自身，含自身）。 */
    public List<Long> ancestorIds(Long nodeId) {
        List<Long> path = new ArrayList<>();
        if (nodeId == null) {
            return path;
        }
        Map<Long, Long> parentIndex = new HashMap<>();
        for (Company c : listCompanies()) {
            parentIndex.put(c.getId(), c.getParentId());
        }
        Set<Long> guard = new HashSet<>();
        Long cursor = nodeId;
        while (cursor != null && guard.add(cursor)) {
            path.add(0, cursor);
            cursor = parentIndex.get(cursor);
        }
        return path;
    }

    /** 图谱根节点（parentId 为空的公司）。 */
    public Company rootCompany() {
        return companyMapper.selectOne(new LambdaQueryWrapper<Company>()
                .and(w -> w.isNull(Company::getParentId))
                .orderByAsc(Company::getSort)
                .orderByAsc(Company::getId)
                .last("LIMIT 1"));
    }

    /**
     * 变更上级公司，带防环校验。
     *
     * <p>拒绝：自己作为自己的上级；挂到自己的后代之下（会形成环）。
     */
    public Company changeParent(Long companyId, Long newParentId) {
        Company company = companyMapper.selectById(companyId);
        if (company == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "公司不存在");
        }
        if (Objects.equals(companyId, newParentId)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "不能将公司挂在自身之下");
        }
        if (newParentId != null) {
            if (companyMapper.selectById(newParentId) == null) {
                throw new AppException(ErrorCode.NOT_FOUND, "上级公司不存在");
            }
            if (descendantIds(companyId).contains(newParentId)) {
                throw new AppException(ErrorCode.BAD_REQUEST, "不能将公司挂到自己的下级，会形成循环");
            }
        }
        company.setParentId(newParentId);
        companyMapper.updateById(company);
        return companyMapper.selectById(companyId);
    }

    /** 是否存在下级公司。 */
    public boolean hasChildren(Long companyId) {
        Long count = companyMapper.selectCount(new LambdaQueryWrapper<Company>()
                .eq(Company::getParentId, companyId));
        return count != null && count > 0;
    }
}
