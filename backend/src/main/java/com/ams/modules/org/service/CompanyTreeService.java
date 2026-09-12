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
import java.util.stream.Collectors;
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

    /**
     * 全部公司（**含已停用**），按 sort、id 排序。
     *
     * <p>刻意不按 {@code status} 过滤，因为本方法同时是数据范围的取值来源
     * （{@code descendantIds} / 数据范围基线都经由它）：<strong>停用公司仍留在其上级公司的
     * 子树内，其名下数据对范围内用户依然可读</strong>。这是刻意的口径 —— 「停用」表示不再
     * 新增业务，不等于抹掉历史数据；把停用公司一并剔除会让历史记录凭空消失。
     *
     * <p>需要「不可选择」语义的地方必须自行按 {@code status} 过滤，例如公司切换器
     * （{@code AuthService} 过滤 {@code status == 1}）与 {@link #isActiveCompany(Long)}。
     * 这两处管的是「能不能切进去」，与「能不能看到数据」是两件事。
     */
    public List<Company> listCompanies() {
        return companyMapper.selectList(new LambdaQueryWrapper<Company>()
                .orderByAsc(Company::getSort)
                .orderByAsc(Company::getId));
    }

    /**
     * 公司列表按「公司树深度优先」排序（父节点总在子节点之前）。
     *
     * <p>{@link #listCompanies()} 只按 sort/id 排序，同级公司混排会让下拉里的层级缩进失去意义；
     * 本方法按 parentId 做 DFS 展开，便于公司切换下拉、组织树等展示场景。
     * 脏数据（父节点不存在）按根节点处理，互为父子成环时按已访问集合截断。
     */
    public List<Company> listCompaniesTreeOrdered() {
        List<Company> all = listCompanies();
        Set<Long> known = all.stream().map(Company::getId).collect(Collectors.toSet());
        Map<Long, List<Company>> childrenIndex = new LinkedHashMap<>();
        List<Company> roots = new ArrayList<>();
        for (Company c : all) {
            if (c.getParentId() == null || !known.contains(c.getParentId())) {
                roots.add(c);
            } else {
                childrenIndex.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
            }
        }
        List<Company> ordered = new ArrayList<>(all.size());
        Set<Long> visited = new HashSet<>();
        Deque<Company> stack = new ArrayDeque<>();
        // 逆序压栈，保证弹出顺序与 roots / children 的原有排序一致
        for (int i = roots.size() - 1; i >= 0; i--) {
            stack.push(roots.get(i));
        }
        while (!stack.isEmpty()) {
            Company current = stack.pop();
            if (!visited.add(current.getId())) {
                continue;
            }
            ordered.add(current);
            List<Company> kids = childrenIndex.getOrDefault(current.getId(), List.of());
            for (int i = kids.size() - 1; i >= 0; i--) {
                stack.push(kids.get(i));
            }
        }
        return ordered;
    }

    /** 公司是否存在且处于启用状态（公司切换的目标校验，避免切到已删除 / 停用 / 不存在的公司）。 */
    public boolean isActiveCompany(Long companyId) {
        if (companyId == null) {
            return false;
        }
        return listCompanies().stream()
                .anyMatch(c -> companyId.equals(c.getId())
                        && c.getStatus() != null && c.getStatus() == 1);
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
