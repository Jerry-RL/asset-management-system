package com.ams.modules.record.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.record.DisposalCascadePort;
import com.ams.modules.record.DisposalCascadeSnapshot;
import com.ams.modules.record.AttachmentOwner;
import com.ams.modules.record.RecordOwnerType;
import com.ams.modules.record.dto.AttachmentRef;
import com.ams.modules.record.dto.CostInput;
import com.ams.modules.record.dto.CostItemInput;
import com.ams.modules.record.dto.DisposalInput;
import com.ams.modules.record.dto.EvaluationInput;
import com.ams.modules.record.dto.IssueInput;
import com.ams.modules.record.dto.ReceiveInput;
import com.ams.modules.record.dto.RecordSheetRequest;
import com.ams.modules.record.dto.RecordSheetView;
import com.ams.modules.record.dto.SourceInput;
import com.ams.modules.record.entity.BizAttachment;
import com.ams.modules.record.entity.CostItem;
import com.ams.modules.record.entity.CostRecord;
import com.ams.modules.record.entity.DisposalRecord;
import com.ams.modules.record.entity.EvaluationInfo;
import com.ams.modules.record.entity.ReceiveIssue;
import com.ams.modules.record.entity.ReceiveRecord;
import com.ams.modules.record.entity.SourceInfo;
import com.ams.modules.record.mapper.BizAttachmentMapper;
import com.ams.modules.record.mapper.CostItemMapper;
import com.ams.modules.record.mapper.CostRecordMapper;
import com.ams.modules.record.mapper.DisposalRecordMapper;
import com.ams.modules.record.mapper.EvaluationInfoMapper;
import com.ams.modules.record.mapper.ReceiveIssueMapper;
import com.ams.modules.record.mapper.ReceiveRecordMapper;
import com.ams.modules.record.mapper.SourceInfoMapper;
import com.ams.modules.system.service.FileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后续记录聚合读写（设计 §5.2 / §5.4）。
 * 三个不变量见计划 Task 7 的说明；实现里每个方法都对应一条。
 */
@Service
public class RecordSheetService {

    private final ReceiveRecordMapper receiveRecordMapper;
    private final ReceiveIssueMapper receiveIssueMapper;
    private final SourceInfoMapper sourceInfoMapper;
    private final DisposalRecordMapper disposalRecordMapper;
    private final CostRecordMapper costRecordMapper;
    private final CostItemMapper costItemMapper;
    private final EvaluationInfoMapper evaluationInfoMapper;
    private final BizAttachmentMapper bizAttachmentMapper;
    private final UserMapper userMapper;
    private final FileService fileService;
    private final DisposalCascadePort disposalCascadePort;

    public RecordSheetService(
            ReceiveRecordMapper receiveRecordMapper,
            ReceiveIssueMapper receiveIssueMapper,
            SourceInfoMapper sourceInfoMapper,
            DisposalRecordMapper disposalRecordMapper,
            CostRecordMapper costRecordMapper,
            CostItemMapper costItemMapper,
            EvaluationInfoMapper evaluationInfoMapper,
            BizAttachmentMapper bizAttachmentMapper,
            UserMapper userMapper,
            FileService fileService,
            DisposalCascadePort disposalCascadePort) {
        this.receiveRecordMapper = receiveRecordMapper;
        this.receiveIssueMapper = receiveIssueMapper;
        this.sourceInfoMapper = sourceInfoMapper;
        this.disposalRecordMapper = disposalRecordMapper;
        this.costRecordMapper = costRecordMapper;
        this.costItemMapper = costItemMapper;
        this.evaluationInfoMapper = evaluationInfoMapper;
        this.bizAttachmentMapper = bizAttachmentMapper;
        this.userMapper = userMapper;
        this.fileService = fileService;
        this.disposalCascadePort = disposalCascadePort;
    }

    @Transactional
    public RecordSheetView save(RecordOwnerType type, Long ownerId, RecordSheetRequest request) {
        syncReceives(type, ownerId, request.getReceives());
        syncSourceInfo(type, ownerId, request.getSourceInfo());
        // 资产的处置走 disposal_order（Task 9），这里完全不碰台账表
        if (type != RecordOwnerType.ASSET) {
            syncDisposalRecords(type, ownerId, request.getDisposalRecords());
        }
        // 成本 / 评估两段三种主体共用，不做资产特判
        syncCostRecords(type, ownerId, request.getCostRecords());
        syncEvaluations(type, ownerId, request.getEvaluations());
        return read(type, ownerId);
    }

    public RecordSheetView read(RecordOwnerType type, Long ownerId) {
        RecordSheetView view = new RecordSheetView();
        for (ReceiveRecord record : activeReceives(type, ownerId)) {
            view.getReceives().add(toReceiveInput(record));
        }
        SourceInfo source = findSourceInfo(type, ownerId);
        view.setSourceInfo(source == null ? null : toSourceInput(source));
        // asset 的 disposalRecords 保持字段初始化的空列表；资产的处置走 disposal_order（Task 9）
        if (type != RecordOwnerType.ASSET) {
            for (DisposalRecord record : activeDisposals(type, ownerId)) {
                view.getDisposalRecords().add(toDisposalInput(record));
            }
        }
        for (CostRecord record : activeCostRecords(type, ownerId)) {
            view.getCostRecords().add(toCostInput(record));
        }
        for (EvaluationInfo record : activeEvaluations(type, ownerId)) {
            view.getEvaluations().add(toEvaluationInput(record));
        }
        return view;
    }

    private List<ReceiveRecord> activeReceives(RecordOwnerType type, Long ownerId) {
        return receiveRecordMapper.selectList(notDeleted(new LambdaQueryWrapper<ReceiveRecord>()
                .eq(ReceiveRecord::getOwnerType, type.code())
                .eq(ReceiveRecord::getOwnerId, ownerId))
                .orderByAsc(ReceiveRecord::getId));
    }

    private SourceInfo findSourceInfo(RecordOwnerType type, Long ownerId) {
        return sourceInfoMapper.selectOne(notDeleted(new LambdaQueryWrapper<SourceInfo>()
                .eq(SourceInfo::getOwnerType, type.code())
                .eq(SourceInfo::getOwnerId, ownerId)));
    }

    private List<DisposalRecord> activeDisposals(RecordOwnerType type, Long ownerId) {
        return disposalRecordMapper.selectList(notDeleted(new LambdaQueryWrapper<DisposalRecord>()
                .eq(DisposalRecord::getOwnerType, type.code())
                .eq(DisposalRecord::getOwnerId, ownerId))
                .orderByAsc(DisposalRecord::getId));
    }

    private List<CostRecord> activeCostRecords(RecordOwnerType type, Long ownerId) {
        return costRecordMapper.selectList(notDeleted(new LambdaQueryWrapper<CostRecord>()
                .eq(CostRecord::getOwnerType, type.code())
                .eq(CostRecord::getOwnerId, ownerId))
                .orderByAsc(CostRecord::getId));
    }

    private List<CostItem> activeCostItems(Long costId) {
        return costItemMapper.selectList(notDeleted(new LambdaQueryWrapper<CostItem>()
                .eq(CostItem::getCostId, costId))
                .orderByAsc(CostItem::getSort)
                .orderByAsc(CostItem::getId));
    }

    private List<EvaluationInfo> activeEvaluations(RecordOwnerType type, Long ownerId) {
        return evaluationInfoMapper.selectList(notDeleted(new LambdaQueryWrapper<EvaluationInfo>()
                .eq(EvaluationInfo::getOwnerType, type.code())
                .eq(EvaluationInfo::getOwnerId, ownerId))
                .orderByAsc(EvaluationInfo::getId));
    }

    private List<ReceiveIssue> activeIssues(Long receiveId) {
        return receiveIssueMapper.selectList(notDeleted(new LambdaQueryWrapper<ReceiveIssue>()
                .eq(ReceiveIssue::getReceiveId, receiveId))
                .orderByAsc(ReceiveIssue::getSort)
                .orderByAsc(ReceiveIssue::getId));
    }

    private List<BizAttachment> activeAttachments(AttachmentOwner owner, Long ownerId) {
        return bizAttachmentMapper.selectList(notDeleted(new LambdaQueryWrapper<BizAttachment>()
                .eq(BizAttachment::getOwnerType, owner.code())
                .eq(BizAttachment::getOwnerId, ownerId))
                .orderByAsc(BizAttachment::getSort)
                .orderByAsc(BizAttachment::getId));
    }

    /**
     * 显式排除软删行。全局 {@code logic-delete-field: deleted} 与实际的 {@code deleted_at}
     * 列口径不一致，逻辑删除**不会**自动生效，每一条查询都必须自己带上这个条件。
     * 收敛成一个方法，是为了让「漏写」只可能发生在这一处。
     */
    private <T> LambdaQueryWrapper<T> notDeleted(LambdaQueryWrapper<T> wrapper) {
        return wrapper.apply("deleted_at IS NULL");
    }

    private void syncReceives(RecordOwnerType type, Long ownerId, List<ReceiveInput> inputs) {
        List<ReceiveInput> incoming = inputs == null ? List.of() : inputs;
        Map<Long, ReceiveRecord> existing = activeReceives(type, ownerId).stream()
                .collect(Collectors.toMap(ReceiveRecord::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Set<Long> kept = new LinkedHashSet<>();
        for (ReceiveInput input : incoming) {
            ReceiveRecord target;
            if (input.getId() != null && existing.containsKey(input.getId())) {
                target = existing.get(input.getId());
                applyReceive(input, target);
                receiveRecordMapper.updateById(target);
            } else {
                target = new ReceiveRecord();
                target.setOwnerType(type.code());
                target.setOwnerId(ownerId);
                applyReceive(input, target);
                receiveRecordMapper.insert(target);
            }
            kept.add(target.getId());
            syncIssues(target.getId(), input.getIssues());
            syncAttachments(AttachmentOwner.RECEIVE_RECORD, target.getId(), input.getAttachments());
        }
        for (Long id : existing.keySet()) {
            if (!kept.contains(id)) {
                softDeleteReceive(id);
            }
        }
    }

    private void applyReceive(ReceiveInput input, ReceiveRecord target) {
        target.setHandoverType(input.getHandoverType());
        target.setDocName(input.getDocName());
        target.setHandoverUserId(input.getHandoverUserId());
        target.setHandoverUserName(actorName(input.getHandoverUserId(), input.getHandoverUserName()));
        target.setHandoverDate(input.getHandoverDate());
        target.setRemark(input.getRemark());
    }

    /** 软删接收记录：连同其遗留问题与附件。附件必须一起走，否则会留下孤儿关联。 */
    private void softDeleteReceive(Long receiveId) {
        for (ReceiveIssue issue : activeIssues(receiveId)) {
            syncAttachments(AttachmentOwner.RECEIVE_ISSUE, issue.getId(), List.of());
            softDeleteIssue(issue.getId());
        }
        syncAttachments(AttachmentOwner.RECEIVE_RECORD, receiveId, List.of());
        markDeleted(receiveRecordMapper, ReceiveRecord::getId, receiveId);
    }

    private void syncIssues(Long receiveId, List<IssueInput> inputs) {
        List<IssueInput> incoming = inputs == null ? List.of() : inputs;
        Map<Long, ReceiveIssue> existing = activeIssues(receiveId).stream()
                .collect(Collectors.toMap(ReceiveIssue::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Set<Long> kept = new LinkedHashSet<>();
        int index = 0;
        for (IssueInput input : incoming) {
            ReceiveIssue target;
            if (input.getId() != null && existing.containsKey(input.getId())) {
                target = existing.get(input.getId());
                applyIssue(input, target, index);
                receiveIssueMapper.updateById(target);
            } else {
                target = new ReceiveIssue();
                // receive_id 一律由服务端赋值：客户端无法把一条问题拼到别的接收记录上
                target.setReceiveId(receiveId);
                applyIssue(input, target, index);
                receiveIssueMapper.insert(target);
            }
            kept.add(target.getId());
            syncAttachments(AttachmentOwner.RECEIVE_ISSUE, target.getId(), input.getAttachments());
            index++;
        }
        for (Long id : existing.keySet()) {
            if (!kept.contains(id)) {
                syncAttachments(AttachmentOwner.RECEIVE_ISSUE, id, List.of());
                softDeleteIssue(id);
            }
        }
    }

    private void applyIssue(IssueInput input, ReceiveIssue target, int index) {
        target.setIssueType(input.getIssueType());
        target.setDescription(input.getDescription());
        target.setDiscovererId(input.getDiscovererId());
        target.setDiscovererName(actorName(input.getDiscovererId(), input.getDiscovererName()));
        target.setSort(index);
    }

    private void softDeleteIssue(Long id) {
        markDeleted(receiveIssueMapper, ReceiveIssue::getId, id);
    }

    private void syncSourceInfo(RecordOwnerType type, Long ownerId, SourceInput input) {
        if (input == null) {
            return;
        }
        SourceInfo existing = findSourceInfo(type, ownerId);
        if (isEmptySource(input)) {
            // 全字段为空视为「清空」：软删该行，附件一并软删
            if (existing != null) {
                syncAttachments(AttachmentOwner.SOURCE_INFO, existing.getId(), List.of());
                markDeleted(sourceInfoMapper, SourceInfo::getId, existing.getId());
            }
            return;
        }
        SourceInfo target = existing != null ? existing : new SourceInfo();
        target.setOwnerType(type.code());
        target.setOwnerId(ownerId);
        target.setSourcePersonId(input.getSourcePersonId());
        target.setSourcePersonName(actorName(input.getSourcePersonId(), input.getSourcePersonName(), true));
        target.setSourceUnit(input.getSourceUnit());
        target.setSourceDate(input.getSourceDate());
        target.setSourceDesc(input.getSourceDesc());
        if (existing != null) {
            sourceInfoMapper.updateById(target);
        } else {
            sourceInfoMapper.insert(target);
        }
        syncAttachments(AttachmentOwner.SOURCE_INFO, target.getId(), input.getAttachments());
    }

    private boolean isEmptySource(SourceInput input) {
        return input.getSourcePersonId() == null
                && blank(input.getSourcePersonName())
                && blank(input.getSourceUnit())
                && blank(input.getSourceDesc())
                && input.getSourceDate() == null
                && (input.getAttachments() == null || input.getAttachments().isEmpty());
    }

    private void syncDisposalRecords(RecordOwnerType type, Long ownerId, List<DisposalInput> inputs) {
        List<DisposalInput> incoming = inputs == null ? List.of() : inputs;
        Map<Long, DisposalRecord> existing = activeDisposals(type, ownerId).stream()
                .collect(Collectors.toMap(DisposalRecord::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Set<Long> kept = new LinkedHashSet<>();
        for (DisposalInput input : incoming) {
            DisposalRecord target;
            if (input.getId() != null && existing.containsKey(input.getId())) {
                target = existing.get(input.getId());
                applyDisposal(input, target);
                disposalRecordMapper.updateById(target);
            } else {
                target = new DisposalRecord();
                target.setOwnerType(type.code());
                target.setOwnerId(ownerId);
                applyDisposal(input, target);
                disposalRecordMapper.insert(target);
                // V57：项目 / 分区**新增**一条处置台账 = 一次处置登记，立即级联 —— 其下资产
                // 脱离原产权公司（清空 property_company_id）并写入「资产处置记录」台账。
                //
                // 只在新增时级联：编辑已有记录不重复处置（处置不可逆，台账是处置那一刻的快照）；
                // 删除记录也不回滚资产状态 —— 把产权公司"还回去"要靠权属流转重新登记，
                // 不能让删一张记录表把已发生的处置抹掉。级联本身幂等（已退出的资产被跳过）。
                disposalCascadePort.cascadeDispose(
                        type.code(), ownerId,
                        new DisposalCascadeSnapshot(target.getDisposalType(), target.getAmountWan(),
                                DisposalCascadeSnapshot.UNIT_WAN, target.getDisposalDate(),
                                target.getDisposalUserId(), target.getDisposalUserName(),
                                target.getRemark()),
                        null, target.getId());
            }
            kept.add(target.getId());
            syncAttachments(AttachmentOwner.DISPOSAL_RECORD, target.getId(), input.getAttachments());
        }
        for (Long id : existing.keySet()) {
            if (!kept.contains(id)) {
                syncAttachments(AttachmentOwner.DISPOSAL_RECORD, id, List.of());
                markDeleted(disposalRecordMapper, DisposalRecord::getId, id);
            }
        }
    }

    private void applyDisposal(DisposalInput input, DisposalRecord target) {
        target.setDisposalType(input.getDisposalType());
        target.setDisposalUserId(input.getDisposalUserId());
        target.setDisposalUserName(actorName(input.getDisposalUserId(), input.getDisposalUserName()));
        target.setAmountWan(input.getAmountWan());
        target.setDisposalDate(input.getDisposalDate());
        target.setRemark(input.getRemark());
    }

    // ---- 成本信息（1:N，含费用明细子表）----

    private void syncCostRecords(RecordOwnerType type, Long ownerId, List<CostInput> inputs) {
        List<CostInput> incoming = inputs == null ? List.of() : inputs;
        Map<Long, CostRecord> existing = activeCostRecords(type, ownerId).stream()
                .collect(Collectors.toMap(CostRecord::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Set<Long> kept = new LinkedHashSet<>();
        for (CostInput input : incoming) {
            CostRecord target;
            if (input.getId() != null && existing.containsKey(input.getId())) {
                target = existing.get(input.getId());
                applyCost(input, target);
                costRecordMapper.updateById(target);
            } else {
                target = new CostRecord();
                target.setOwnerType(type.code());
                target.setOwnerId(ownerId);
                applyCost(input, target);
                costRecordMapper.insert(target);
            }
            kept.add(target.getId());
            syncCostItems(target.getId(), input.getItems());
            syncAttachments(AttachmentOwner.COST_RECORD, target.getId(), input.getAttachments());
        }
        for (Long id : existing.keySet()) {
            if (!kept.contains(id)) {
                softDeleteCostRecord(id);
            }
        }
    }

    private void applyCost(CostInput input, CostRecord target) {
        target.setAmountWan(input.getAmountWan());
        target.setCostDate(input.getCostDate());
        target.setRemark(input.getRemark());
    }

    /** 软删成本记录：连同其费用明细与附件。附件必须一起走，否则会留下孤儿关联。 */
    private void softDeleteCostRecord(Long costId) {
        for (CostItem item : activeCostItems(costId)) {
            markDeleted(costItemMapper, CostItem::getId, item.getId());
        }
        syncAttachments(AttachmentOwner.COST_RECORD, costId, List.of());
        markDeleted(costRecordMapper, CostRecord::getId, costId);
    }

    private void syncCostItems(Long costId, List<CostItemInput> inputs) {
        List<CostItemInput> incoming = inputs == null ? List.of() : inputs;
        Map<Long, CostItem> existing = activeCostItems(costId).stream()
                .collect(Collectors.toMap(CostItem::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Set<Long> kept = new LinkedHashSet<>();
        int index = 0;
        for (CostItemInput input : incoming) {
            CostItem target;
            if (input.getId() != null && existing.containsKey(input.getId())) {
                target = existing.get(input.getId());
                applyCostItem(input, target, index);
                costItemMapper.updateById(target);
            } else {
                target = new CostItem();
                // cost_id 一律由服务端赋值：客户端无法把一条明细拼到别的成本记录上
                target.setCostId(costId);
                applyCostItem(input, target, index);
                costItemMapper.insert(target);
            }
            kept.add(target.getId());
            index++;
        }
        for (Long id : existing.keySet()) {
            if (!kept.contains(id)) {
                markDeleted(costItemMapper, CostItem::getId, id);
            }
        }
    }

    private void applyCostItem(CostItemInput input, CostItem target, int index) {
        target.setFeeName(input.getFeeName());
        target.setCostType(input.getCostType());
        target.setAmount(input.getAmount());
        target.setRemark(input.getRemark());
        target.setSort(index);
    }

    // ---- 评估信息（1:N）----

    private void syncEvaluations(RecordOwnerType type, Long ownerId, List<EvaluationInput> inputs) {
        List<EvaluationInput> incoming = inputs == null ? List.of() : inputs;
        Map<Long, EvaluationInfo> existing = activeEvaluations(type, ownerId).stream()
                .collect(Collectors.toMap(EvaluationInfo::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Set<Long> kept = new LinkedHashSet<>();
        for (EvaluationInput input : incoming) {
            EvaluationInfo target;
            if (input.getId() != null && existing.containsKey(input.getId())) {
                target = existing.get(input.getId());
                applyEvaluation(input, target);
                evaluationInfoMapper.updateById(target);
            } else {
                target = new EvaluationInfo();
                target.setOwnerType(type.code());
                target.setOwnerId(ownerId);
                applyEvaluation(input, target);
                evaluationInfoMapper.insert(target);
            }
            kept.add(target.getId());
            syncAttachments(AttachmentOwner.EVALUATION_INFO, target.getId(), input.getAttachments());
        }
        for (Long id : existing.keySet()) {
            if (!kept.contains(id)) {
                syncAttachments(AttachmentOwner.EVALUATION_INFO, id, List.of());
                markDeleted(evaluationInfoMapper, EvaluationInfo::getId, id);
            }
        }
    }

    private void applyEvaluation(EvaluationInput input, EvaluationInfo target) {
        target.setInstitution(input.getInstitution());
        target.setAssetValue(input.getAssetValue());
        target.setRentUnitPrice(input.getRentUnitPrice());
        target.setRentPrice(input.getRentPrice());
        target.setEvaluateDate(input.getEvaluateDate());
        target.setValidFrom(input.getValidFrom());
        target.setValidTo(input.getValidTo());
    }

    /**
     * 附件全量 diff（设计 §4.3）。以 {@code fileId} 为身份：同一宿主下重复提交同一个文件
     * 只保留一行，未提交的行软删。
     */
    /**
     * 附件全量 diff 写入（`owner` 决定这行附件挂在哪条记录上）。
     *
     * <p>**public 的边界**：本方法只做「按宿主 diff」，不判权限 —— 调用方必须自己先过
     * 「该宿主单据的写权限」。处置单走 {@link #syncOrderAttachments}，权属流转走
     * `OWNERSHIP_TRANSFER`，两者共用这唯一的写入点（设计 §4.4）。
     *
     * <p>之所以必须是**参数化的同一个方法**而不是各模块自己写一份：`biz_attachment` 是多态宿主表，
     * 两处各写一份 `owner_type` / `biz_type` 的赋值，只要有一处写错成别的宿主枚举，
     * 附件就会静默挂到另一张单上 —— 页面照常显示、没有任何报错。
     */
    public void syncAttachments(AttachmentOwner owner, Long ownerId, List<AttachmentRef> refs) {
        if (ownerId == null) {
            // 宿主还没落库（新增失败）时不能写附件，否则会造出 owner_id 为 null 的孤儿行
            return;
        }
        List<AttachmentRef> incoming = refs == null ? List.of() : refs;
        Map<Long, BizAttachment> existing = activeAttachments(owner, ownerId).stream()
                .collect(Collectors.toMap(BizAttachment::getFileId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        // 只校验新挂上来的 fileId：已挂在该宿主上的旧引用原样放过，
        // 否则存量脏引用（file_metadata 已被清理但本期不做清理）会让整张表单再也保存不了。
        // null 元素先滤掉：循环里的 `ref == null` 仍会给出 400，不能在这里先抛 NPE。
        fileService.assertAttachable(incoming.stream()
                .filter(Objects::nonNull)
                .map(AttachmentRef::getFileId)
                .filter(Objects::nonNull)
                .filter(fileId -> !existing.containsKey(fileId))
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        Set<Long> kept = new LinkedHashSet<>();
        int index = 0;
        for (AttachmentRef ref : incoming) {
            if (ref == null || ref.getFileId() == null) {
                throw new AppException(ErrorCode.BAD_REQUEST, "附件缺少 fileId，请先调用 /files/upload");
            }
            int sort = ref.getSort() == null ? index : ref.getSort();
            BizAttachment row = existing.get(ref.getFileId());
            if (row == null) {
                row = new BizAttachment();
                row.setOwnerType(owner.code());
                row.setOwnerId(ownerId);
                row.setBizType(owner.bizType());
                row.setFileId(ref.getFileId());
                row.setSort(sort);
                bizAttachmentMapper.insert(row);
            } else {
                row.setSort(sort);
                bizAttachmentMapper.updateById(row);
            }
            kept.add(ref.getFileId());
            index++;
        }
        for (BizAttachment row : existing.values()) {
            if (!kept.contains(row.getFileId())) {
                markDeleted(bizAttachmentMapper, BizAttachment::getId, row.getId());
            }
        }
    }

    /** 供 Task 9 的处置单写入复用：资产侧处置单的附件与记录表共用一条 diff 逻辑。 */
    public void syncOrderAttachments(Long orderId, List<AttachmentRef> refs) {
        syncAttachments(AttachmentOwner.DISPOSAL_ORDER, orderId, refs);
    }

    /** 供 Task 9 回显处置单附件。 */
    public List<AttachmentRef> orderAttachments(Long orderId) {
        return toAttachmentRefs(AttachmentOwner.DISPOSAL_ORDER, orderId);
    }

    /** 读路径：内员姓名用快照原样返回，不再回查 sys_user（历史凭证要保留当时的姓名）。 */
    private String actorName(Long userId, String providedName) {
        return actorName(userId, providedName, false);
    }

    /**
     * @param optional true 表示该字段非必填（来源人可以为空）；false 表示必填
     */
    private String actorName(Long userId, String providedName, boolean optional) {
        if (userId == null) {
            if (blank(providedName)) {
                if (optional) {
                    return null;
                }
                throw new AppException(ErrorCode.BAD_REQUEST, "请选择系统内人员或填写人员姓名");
            }
            return providedName.trim();
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "所选人员不存在：" + userId);
        }
        return user.getName();
    }

    /** 软删的唯一落点：只写 deleted_at，绝不 deleteById（设计 §4.1）。 */
    private <T> void markDeleted(BaseMapper<T> mapper, SFunction<T, ?> idGetter, Long id) {
        mapper.update(null, new LambdaUpdateWrapper<T>()
                .setSql("deleted_at = now()")
                .eq(idGetter, id)
                .apply("deleted_at IS NULL"));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ReceiveInput toReceiveInput(ReceiveRecord record) {
        ReceiveInput input = new ReceiveInput();
        input.setId(record.getId());
        input.setHandoverType(record.getHandoverType());
        input.setDocName(record.getDocName());
        input.setHandoverUserId(record.getHandoverUserId());
        input.setHandoverUserName(record.getHandoverUserName());
        input.setHandoverDate(record.getHandoverDate());
        input.setRemark(record.getRemark());
        for (ReceiveIssue issue : activeIssues(record.getId())) {
            IssueInput issueInput = new IssueInput();
            issueInput.setId(issue.getId());
            issueInput.setIssueType(issue.getIssueType());
            issueInput.setDescription(issue.getDescription());
            issueInput.setDiscovererId(issue.getDiscovererId());
            issueInput.setDiscovererName(issue.getDiscovererName());
            issueInput.setAttachments(toAttachmentRefs(AttachmentOwner.RECEIVE_ISSUE, issue.getId()));
            input.getIssues().add(issueInput);
        }
        input.setAttachments(toAttachmentRefs(AttachmentOwner.RECEIVE_RECORD, record.getId()));
        return input;
    }

    private SourceInput toSourceInput(SourceInfo source) {
        SourceInput input = new SourceInput();
        input.setId(source.getId());
        input.setSourcePersonId(source.getSourcePersonId());
        input.setSourcePersonName(source.getSourcePersonName());
        input.setSourceUnit(source.getSourceUnit());
        input.setSourceDate(source.getSourceDate());
        input.setSourceDesc(source.getSourceDesc());
        input.setAttachments(toAttachmentRefs(AttachmentOwner.SOURCE_INFO, source.getId()));
        return input;
    }

    private DisposalInput toDisposalInput(DisposalRecord record) {
        DisposalInput input = new DisposalInput();
        input.setId(record.getId());
        input.setDisposalType(record.getDisposalType());
        input.setDisposalUserId(record.getDisposalUserId());
        input.setDisposalUserName(record.getDisposalUserName());
        input.setAmountWan(record.getAmountWan());
        input.setDisposalDate(record.getDisposalDate());
        input.setRemark(record.getRemark());
        input.setAttachments(toAttachmentRefs(AttachmentOwner.DISPOSAL_RECORD, record.getId()));
        return input;
    }

    private CostInput toCostInput(CostRecord record) {
        CostInput input = new CostInput();
        input.setId(record.getId());
        input.setAmountWan(record.getAmountWan());
        input.setCostDate(record.getCostDate());
        input.setRemark(record.getRemark());
        for (CostItem item : activeCostItems(record.getId())) {
            CostItemInput itemInput = new CostItemInput();
            itemInput.setId(item.getId());
            itemInput.setFeeName(item.getFeeName());
            itemInput.setCostType(item.getCostType());
            itemInput.setAmount(item.getAmount());
            itemInput.setRemark(item.getRemark());
            input.getItems().add(itemInput);
        }
        input.setAttachments(toAttachmentRefs(AttachmentOwner.COST_RECORD, record.getId()));
        return input;
    }

    private EvaluationInput toEvaluationInput(EvaluationInfo record) {
        EvaluationInput input = new EvaluationInput();
        input.setId(record.getId());
        input.setInstitution(record.getInstitution());
        input.setAssetValue(record.getAssetValue());
        input.setRentUnitPrice(record.getRentUnitPrice());
        input.setRentPrice(record.getRentPrice());
        input.setEvaluateDate(record.getEvaluateDate());
        input.setValidFrom(record.getValidFrom());
        input.setValidTo(record.getValidTo());
        input.setAttachments(toAttachmentRefs(AttachmentOwner.EVALUATION_INFO, record.getId()));
        return input;
    }

    /**
     * 批量回显附件：一次查全部 fileId，避免每条附件一次查询。
     *
     * <p>查不到 file 的引用**保留**（{@code fileName}/{@code url} 为 null），前端按 null 兜底展示；
     * **不丢行** —— 丢弃会让该 fileId 在下次保存时不在请求体里，从而被 {@code syncAttachments}
     * 静默软删，把一个展示层的取舍变成静默数据丢失（设计 §4.3：不删 {@code file_metadata}、
     * 本期不做孤儿文件清理）。
     *
     * <p>与 {@link #syncAttachments} 同一套宿主口径：读与写必须成对，`owner` 由调用方显式传入，
     * 不能默认成某个宿主（处置单专用入口见 {@link #orderAttachments}）。
     */
    public List<AttachmentRef> toAttachmentRefs(AttachmentOwner owner, Long ownerId) {
        List<BizAttachment> rows = activeAttachments(owner, ownerId);
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, Map<String, Object>> views =
                fileService.viewsByIds(rows.stream().map(BizAttachment::getFileId).toList());
        List<AttachmentRef> refs = new ArrayList<>();
        for (BizAttachment row : rows) {
            AttachmentRef ref = new AttachmentRef();
            ref.setFileId(row.getFileId());
            ref.setSort(row.getSort());
            Map<String, Object> view = views.get(row.getFileId());
            if (view != null) {
                ref.setFileName((String) view.get("fileName"));
                ref.setUrl((String) view.get("url"));
            }
            refs.add(ref);
        }
        return refs;
    }
}
