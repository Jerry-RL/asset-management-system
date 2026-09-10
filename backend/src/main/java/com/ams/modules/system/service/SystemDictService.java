package com.ams.modules.system.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.system.dto.SysDictRelationBatch;
import com.ams.modules.system.entity.SysDictItem;
import com.ams.modules.system.entity.SysDictModule;
import com.ams.modules.system.entity.SysDictRelation;
import com.ams.modules.system.entity.SysDictType;
import com.ams.modules.system.mapper.SysDictItemMapper;
import com.ams.modules.system.mapper.SysDictModuleMapper;
import com.ams.modules.system.mapper.SysDictRelationMapper;
import com.ams.modules.system.mapper.SysDictTypeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 系统字典（系统管理 → 系统字典）：模块 / 字典 / 字典项三级维护 + 字典关联。
 *
 * <p>层级：{@code sys_dict_module}（模块，如「资产管理字典」）→ {@code sys_dict_type}（字典，如「资产类型」）
 * → {@code sys_dict_item}（字典项，如「廉租房」），支持对某一字典单独新增字典项。
 *
 * <p>关联：{@code sys_dict_relation} 记录「字典 → 另一字典的若干个字典值」，供关联查询使用，
 * 不参与业务提交校验。
 */
@Service
public class SystemDictService {

    private final SysDictModuleMapper moduleMapper;
    private final SysDictTypeMapper typeMapper;
    private final SysDictItemMapper itemMapper;
    private final SysDictRelationMapper relationMapper;

    public SystemDictService(
            SysDictModuleMapper moduleMapper,
            SysDictTypeMapper typeMapper,
            SysDictItemMapper itemMapper,
            SysDictRelationMapper relationMapper) {
        this.moduleMapper = moduleMapper;
        this.typeMapper = typeMapper;
        this.itemMapper = itemMapper;
        this.relationMapper = relationMapper;
    }

    // ---------------------------------------------------------------- 查询

    /** 一次性返回完整字典树：模块（含字典，字典含字典项），前端左右布局直接渲染。 */
    public List<SysDictModule> tree() {
        List<SysDictModule> modules = listModules();
        List<SysDictType> types = listTypes(null);
        List<SysDictItem> items = listItems(null);

        Map<Long, List<SysDictItem>> itemsByType = items.stream()
                .collect(Collectors.groupingBy(SysDictItem::getTypeId, LinkedHashMap::new, Collectors.toList()));
        for (SysDictType type : types) {
            type.setItems(itemsByType.getOrDefault(type.getId(), new ArrayList<>()));
        }

        Map<Long, List<SysDictType>> typesByModule = types.stream()
                .collect(Collectors.groupingBy(SysDictType::getModuleId, LinkedHashMap::new, Collectors.toList()));
        for (SysDictModule module : modules) {
            module.setTypes(typesByModule.getOrDefault(module.getId(), new ArrayList<>()));
        }
        return modules;
    }

    public List<SysDictModule> listModules() {
        return moduleMapper.selectList(new LambdaQueryWrapper<SysDictModule>()
                .orderByAsc(SysDictModule::getSort)
                .orderByAsc(SysDictModule::getId));
    }

    public List<SysDictType> listTypes(Long moduleId) {
        return typeMapper.selectList(new LambdaQueryWrapper<SysDictType>()
                .eq(moduleId != null, SysDictType::getModuleId, moduleId)
                .orderByAsc(SysDictType::getSort)
                .orderByAsc(SysDictType::getId));
    }

    public List<SysDictItem> listItems(Long typeId) {
        return listItems(typeId, null);
    }

    /** 支持按字典 ID 或字典编码查询；两者同时给出时以 ID 为准。 */
    public List<SysDictItem> listItems(Long typeId, String typeCode) {
        return listItems(typeId, typeCode, null, null);
    }

    /**
     * 支持按字典 ID / 字典编码查询，并按「字典项级级联」过滤。
     *
     * <p>{@code parentTypeCode + parentValue} 为父字典的编码与取值：
     * 若该组合下配置了级联规则，则只返回规则内的字典项；未配置规则时不限制（向后兼容）。
     * 两者缺一即视为不启用级联过滤。
     */
    public List<SysDictItem> listItems(
            Long typeId, String typeCode, String parentTypeCode, String parentValue) {
        Long resolvedTypeId = typeId;
        if (resolvedTypeId == null && StringUtils.hasText(typeCode)) {
            SysDictType type = typeMapper.selectOne(
                    new LambdaQueryWrapper<SysDictType>().eq(SysDictType::getCode, typeCode));
            if (type == null) {
                return new ArrayList<>();
            }
            resolvedTypeId = type.getId();
        }
        List<SysDictItem> items = itemMapper.selectList(new LambdaQueryWrapper<SysDictItem>()
                .eq(resolvedTypeId != null, SysDictItem::getTypeId, resolvedTypeId)
                .orderByAsc(SysDictItem::getSort)
                .orderByAsc(SysDictItem::getId));

        List<Long> allowed = cascadedItemIds(resolvedTypeId, parentTypeCode, parentValue);
        if (allowed == null) {
            return items;
        }
        // 级联命中时：只保留白名单内的项，并按级联规则自身的排序展示（字典页可调整先后）
        Map<Long, SysDictItem> byId = items.stream()
                .collect(Collectors.toMap(SysDictItem::getId, Function.identity(), (a, b) -> a));
        return allowed.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    /**
     * 解析级联白名单（按规则 sort 排序）：{@code null} 表示不做限制（未配置任何规则或参数不完整）。
     */
    private List<Long> cascadedItemIds(Long childTypeId, String parentTypeCode, String parentValue) {
        if (childTypeId == null
                || !StringUtils.hasText(parentTypeCode)
                || !StringUtils.hasText(parentValue)) {
            return null;
        }
        SysDictType parentType = typeMapper.selectOne(
                new LambdaQueryWrapper<SysDictType>().eq(SysDictType::getCode, parentTypeCode));
        if (parentType == null) {
            return null;
        }
        SysDictItem parentItem = itemMapper.selectOne(new LambdaQueryWrapper<SysDictItem>()
                .eq(SysDictItem::getTypeId, parentType.getId())
                .eq(SysDictItem::getValue, parentValue));
        if (parentItem == null) {
            return null;
        }
        List<SysDictRelation> rules = relationMapper.selectList(new LambdaQueryWrapper<SysDictRelation>()
                .eq(SysDictRelation::getSourceItemId, parentItem.getId())
                .eq(SysDictRelation::getTargetTypeId, childTypeId)
                .eq(SysDictRelation::getStatus, 1)
                .orderByAsc(SysDictRelation::getSort)
                .orderByAsc(SysDictRelation::getId));
        if (rules.isEmpty()) {
            return null;
        }
        return rules.stream()
                .map(SysDictRelation::getTargetItemId)
                .distinct()
                .toList();
    }

    // ---------------------------------------------------------------- 模块

    @Transactional
    public SysDictModule createModule(SysDictModule module) {
        module.setId(null);
        requireText(module.getCode(), "模块编码");
        requireText(module.getName(), "模块名称");
        ensureModuleCodeUnique(module.getCode(), null);
        normalizeModule(module);
        moduleMapper.insert(module);
        return moduleMapper.selectById(module.getId());
    }

    @Transactional
    public SysDictModule updateModule(Long id, SysDictModule module) {
        requireExists(moduleMapper.selectById(id), "字典模块不存在");
        requireText(module.getCode(), "模块编码");
        requireText(module.getName(), "模块名称");
        ensureModuleCodeUnique(module.getCode(), id);
        module.setId(id);
        normalizeModule(module);
        moduleMapper.updateById(module);
        return moduleMapper.selectById(id);
    }

    @Transactional
    public void deleteModule(Long id) {
        requireExists(moduleMapper.selectById(id), "字典模块不存在");
        List<SysDictType> types = listTypes(id);
        for (SysDictType type : types) {
            deleteRelationsByTypeId(type.getId());
            itemMapper.delete(new LambdaQueryWrapper<SysDictItem>().eq(SysDictItem::getTypeId, type.getId()));
        }
        typeMapper.delete(new LambdaQueryWrapper<SysDictType>().eq(SysDictType::getModuleId, id));
        moduleMapper.deleteById(id);
    }

    // ---------------------------------------------------------------- 字典

    @Transactional
    public SysDictType createType(SysDictType type) {
        type.setId(null);
        if (type.getModuleId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请先选择所属模块");
        }
        requireExists(moduleMapper.selectById(type.getModuleId()), "字典模块不存在");
        requireText(type.getCode(), "字典编码");
        requireText(type.getName(), "字典名称");
        ensureTypeCodeUnique(type.getCode(), null);
        normalizeType(type);
        typeMapper.insert(type);
        return typeMapper.selectById(type.getId());
    }

    @Transactional
    public SysDictType updateType(Long id, SysDictType type) {
        SysDictType current = typeMapper.selectById(id);
        requireExists(current, "字典不存在");
        Long moduleId = type.getModuleId() == null ? current.getModuleId() : type.getModuleId();
        requireExists(moduleMapper.selectById(moduleId), "字典模块不存在");
        requireText(type.getCode(), "字典编码");
        requireText(type.getName(), "字典名称");
        ensureTypeCodeUnique(type.getCode(), id);
        type.setId(id);
        type.setModuleId(moduleId);
        normalizeType(type);
        typeMapper.updateById(type);
        return typeMapper.selectById(id);
    }

    @Transactional
    public void deleteType(Long id) {
        requireExists(typeMapper.selectById(id), "字典不存在");
        deleteRelationsByTypeId(id);
        itemMapper.delete(new LambdaQueryWrapper<SysDictItem>().eq(SysDictItem::getTypeId, id));
        typeMapper.deleteById(id);
    }

    // ---------------------------------------------------------------- 字典项

    @Transactional
    public SysDictItem createItem(SysDictItem item) {
        item.setId(null);
        if (item.getTypeId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请先选择所属字典");
        }
        requireExists(typeMapper.selectById(item.getTypeId()), "字典不存在");
        requireText(item.getLabel(), "字典项名称");
        if (!StringUtils.hasText(item.getValue())) {
            item.setValue(item.getLabel());
        }
        ensureItemValueUnique(item.getTypeId(), item.getValue(), null);
        normalizeItem(item);
        itemMapper.insert(item);
        return itemMapper.selectById(item.getId());
    }

    @Transactional
    public SysDictItem updateItem(Long id, SysDictItem item) {
        SysDictItem current = itemMapper.selectById(id);
        requireExists(current, "字典项不存在");
        Long typeId = item.getTypeId() == null ? current.getTypeId() : item.getTypeId();
        requireExists(typeMapper.selectById(typeId), "字典不存在");
        requireText(item.getLabel(), "字典项名称");
        if (!StringUtils.hasText(item.getValue())) {
            item.setValue(item.getLabel());
        }
        ensureItemValueUnique(typeId, item.getValue(), id);
        item.setId(id);
        item.setTypeId(typeId);
        normalizeItem(item);
        itemMapper.updateById(item);
        return itemMapper.selectById(id);
    }

    @Transactional
    public void deleteItem(Long id) {
        requireExists(itemMapper.selectById(id), "字典项不存在");
        // 被关联引用的字典项一并清理，避免留下悬空关联（作为被关联项 / 作为级联父项）
        relationMapper.delete(new LambdaQueryWrapper<SysDictRelation>()
                .eq(SysDictRelation::getTargetItemId, id));
        relationMapper.delete(new LambdaQueryWrapper<SysDictRelation>()
                .eq(SysDictRelation::getSourceItemId, id));
        itemMapper.deleteById(id);
    }

    // ---------------------------------------------------------------- 字典关联

    public List<SysDictRelation> listRelations(Long sourceTypeId, Long targetTypeId) {
        return relationMapper.selectList(new LambdaQueryWrapper<SysDictRelation>()
                .eq(sourceTypeId != null, SysDictRelation::getSourceTypeId, sourceTypeId)
                .eq(targetTypeId != null, SysDictRelation::getTargetTypeId, targetTypeId)
                .orderByAsc(SysDictRelation::getTargetTypeId)
                .orderByAsc(SysDictRelation::getSort)
                .orderByAsc(SysDictRelation::getId));
    }

    /**
     * 批量替换某字典的全部关联（含字典级与字典项级）。未出现在 {@code groups} 中、
     * 或 {@code itemIds} 为空的分组表示不建立关联。
     */
    @Transactional
    public List<SysDictRelation> replaceRelations(SysDictRelationBatch batch) {
        if (batch == null || batch.getSourceTypeId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请先选择字典");
        }
        requireExists(typeMapper.selectById(batch.getSourceTypeId()), "字典不存在");
        relationMapper.delete(new LambdaQueryWrapper<SysDictRelation>()
                .eq(SysDictRelation::getSourceTypeId, batch.getSourceTypeId()));

        int sort = 0;
        for (SysDictRelationBatch.Group group : batch.getGroups() == null
                ? List.<SysDictRelationBatch.Group>of()
                : batch.getGroups()) {
            if (group == null || group.getTargetTypeId() == null) {
                continue;
            }
            if (group.getTargetTypeId().equals(batch.getSourceTypeId())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "不能关联字典自身");
            }
            requireExists(typeMapper.selectById(group.getTargetTypeId()), "被关联的字典不存在");
            // 字典项级关联：父字典项必须属于当前字典
            if (group.getSourceItemId() != null) {
                SysDictItem sourceItem = itemMapper.selectById(group.getSourceItemId());
                if (sourceItem == null
                        || !sourceItem.getTypeId().equals(batch.getSourceTypeId())) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "父字典项与当前字典不匹配");
                }
            }
            Map<Long, SysDictItem> allowed = listItems(group.getTargetTypeId()).stream()
                    .collect(Collectors.toMap(SysDictItem::getId, Function.identity(), (a, b) -> a));
            for (Long itemId : group.getItemIds() == null ? List.<Long>of() : group.getItemIds()) {
                if (!allowed.containsKey(itemId)) {
                    throw new AppException(
                            ErrorCode.BAD_REQUEST, "字典值与目标字典不匹配：" + itemId);
                }
                SysDictRelation relation = new SysDictRelation();
                relation.setSourceTypeId(batch.getSourceTypeId());
                relation.setSourceItemId(group.getSourceItemId());
                relation.setTargetTypeId(group.getTargetTypeId());
                relation.setTargetItemId(itemId);
                relation.setSort(sort++);
                relation.setStatus(1);
                relationMapper.insert(relation);
            }
        }
        return listRelations(batch.getSourceTypeId(), null);
    }

    @Transactional
    public void deleteRelation(Long id) {
        requireExists(relationMapper.selectById(id), "字典关联不存在");
        relationMapper.deleteById(id);
    }

    /**
     * 关联查询：按「父字典项 + 目标字典」分组返回该字典挂接的全部关联值。
     * 字典级关联的 {@code sourceItemId} 为空。
     */
    public Map<String, Object> relationsOfType(Long sourceTypeId) {
        SysDictType source = typeMapper.selectById(sourceTypeId);
        requireExists(source, "字典不存在");

        List<SysDictRelation> relations = listRelations(sourceTypeId, null).stream()
                .filter(r -> !Integer.valueOf(0).equals(r.getStatus()))
                .collect(Collectors.toList());

        Map<Long, SysDictItem> sourceItems = listItems(sourceTypeId).stream()
                .collect(Collectors.toMap(SysDictItem::getId, Function.identity(), (a, b) -> a));

        // 先按父字典项、再按目标字典分组；父字典项为 null 时用 0 占位以支持 LinkedHashMap 排序
        Map<Long, Map<Long, List<SysDictRelation>>> grouped = new LinkedHashMap<>();
        relations.forEach(r -> grouped
                .computeIfAbsent(r.getSourceItemId() == null ? 0L : r.getSourceItemId(),
                        k -> new LinkedHashMap<>())
                .computeIfAbsent(r.getTargetTypeId(), k -> new ArrayList<>())
                .add(r));

        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<Long, Map<Long, List<SysDictRelation>>> bySource : grouped.entrySet()) {
            SysDictItem sourceItem = sourceItems.get(bySource.getKey());
            for (Map.Entry<Long, List<SysDictRelation>> entry : bySource.getValue().entrySet()) {
                SysDictType target = typeMapper.selectById(entry.getKey());
                if (target == null) {
                    continue;
                }
                Map<Long, SysDictItem> items = listItems(target.getId()).stream()
                        .collect(Collectors.toMap(SysDictItem::getId, Function.identity(), (a, b) -> a));
                List<Map<String, Object>> values = new ArrayList<>();
                for (SysDictRelation relation : entry.getValue()) {
                    SysDictItem item = items.get(relation.getTargetItemId());
                    if (item == null) {
                        continue;
                    }
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("itemId", item.getId());
                    value.put("value", item.getValue());
                    value.put("label", item.getLabel());
                    values.add(value);
                }
                Map<String, Object> group = new LinkedHashMap<>();
                group.put("sourceItemId", sourceItem == null ? null : sourceItem.getId());
                group.put("sourceItemValue", sourceItem == null ? null : sourceItem.getValue());
                group.put("sourceItemLabel", sourceItem == null ? null : sourceItem.getLabel());
                group.put("targetTypeId", target.getId());
                group.put("targetTypeCode", target.getCode());
                group.put("targetTypeName", target.getName());
                group.put("items", values);
                groups.add(group);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceTypeId", source.getId());
        result.put("sourceTypeCode", source.getCode());
        result.put("sourceTypeName", source.getName());
        result.put("total", relations.size());
        result.put("groups", groups);
        return result;
    }

    private void deleteRelationsByTypeId(Long typeId) {
        relationMapper.delete(new LambdaQueryWrapper<SysDictRelation>()
                .eq(SysDictRelation::getSourceTypeId, typeId));
        relationMapper.delete(new LambdaQueryWrapper<SysDictRelation>()
                .eq(SysDictRelation::getTargetTypeId, typeId));
        // 该字典的字典项若作为父字典项参与级联，关联一并清理，避免悬空
        List<Long> itemIds = listItems(typeId).stream().map(SysDictItem::getId).toList();
        if (!itemIds.isEmpty()) {
            relationMapper.delete(new LambdaQueryWrapper<SysDictRelation>()
                    .in(SysDictRelation::getSourceItemId, itemIds));
        }
    }

    // ---------------------------------------------------------------- 校验

    private void ensureModuleCodeUnique(String code, Long excludeId) {
        Long count = moduleMapper.selectCount(new LambdaQueryWrapper<SysDictModule>()
                .eq(SysDictModule::getCode, code)
                .ne(excludeId != null, SysDictModule::getId, excludeId));
        if (count != null && count > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "模块编码已存在：" + code);
        }
    }

    private void ensureTypeCodeUnique(String code, Long excludeId) {
        Long count = typeMapper.selectCount(new LambdaQueryWrapper<SysDictType>()
                .eq(SysDictType::getCode, code)
                .ne(excludeId != null, SysDictType::getId, excludeId));
        if (count != null && count > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "字典编码已存在：" + code);
        }
    }

    private void ensureItemValueUnique(Long typeId, String value, Long excludeId) {
        Long count = itemMapper.selectCount(new LambdaQueryWrapper<SysDictItem>()
                .eq(SysDictItem::getTypeId, typeId)
                .eq(SysDictItem::getValue, value)
                .ne(excludeId != null, SysDictItem::getId, excludeId));
        if (count != null && count > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "同一字典下已存在该字典值：" + value);
        }
    }

    private void normalizeModule(SysDictModule module) {
        if (module.getSort() == null) {
            module.setSort(0);
        }
        if (module.getStatus() == null) {
            module.setStatus(1);
        }
        module.setCode(module.getCode().trim());
        module.setName(module.getName().trim());
    }

    private void normalizeType(SysDictType type) {
        if (type.getSort() == null) {
            type.setSort(0);
        }
        if (type.getStatus() == null) {
            type.setStatus(1);
        }
        type.setCode(type.getCode().trim());
        type.setName(type.getName().trim());
    }

    private void normalizeItem(SysDictItem item) {
        if (item.getSort() == null) {
            item.setSort(0);
        }
        if (item.getStatus() == null) {
            item.setStatus(1);
        }
        item.setValue(item.getValue().trim());
        item.setLabel(item.getLabel().trim());
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new AppException(ErrorCode.BAD_REQUEST, field + "不能为空");
        }
    }

    private void requireExists(Object entity, String message) {
        if (Objects.isNull(entity)) {
            throw new AppException(ErrorCode.NOT_FOUND, message);
        }
    }
}
