package com.ams.modules.transferrecord.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.entity.Department;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.CompanyMapper;
import com.ams.modules.org.mapper.DepartmentMapper;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.org.service.CompanyTreeService;
import com.ams.modules.record.AttachmentOwner;
import com.ams.modules.record.service.RecordSheetService;
import com.ams.modules.transferrecord.dto.AssetTransferRecordAssetView;
import com.ams.modules.transferrecord.dto.AssetTransferRecordInput;
import com.ams.modules.transferrecord.dto.AssetTransferRecordView;
import com.ams.modules.transferrecord.dto.TransferRecordAssetOption;
import com.ams.modules.transferrecord.entity.AssetTransferRecord;
import com.ams.modules.transferrecord.entity.AssetTransferRecordAsset;
import com.ams.modules.transferrecord.mapper.AssetTransferRecordAssetMapper;
import com.ams.modules.transferrecord.mapper.AssetTransferRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资产调拨记录（V55）：一张单选多个资产，把它们的责任部门与责任人一起改掉。
 *
 * <p>状态机只有 {@code draft → completed}：本期不接审批引擎，「生效」= 唯一的落库动作，
 * 且**不可逆**（生效后要改回去只能再开一张单）。
 *
 * <p><b>草稿不写任何预留行</b>：两个人可以同时对同一资产起草，谁先生效谁赢。这是刻意的 ——
 * 本仓已为「预留永不收口」付过代价（见 {@code OccupationService.withdraw} 的注释）。
 * 跨草稿冲突改由「生效时重新校验」自然消解：本模块的校验口径是**所属公司**，
 * 责任人调岗并不会让校验失败，所以这里不假装它有防重能力 —— 责任交接的语义是
 * 「最后一次生效为准」，与权属流转（改的是主体归属）不同。
 *
 * <p><b>项目 / 分区名在本类回填</b>：{@code asset.projectName} / {@code asset.zoneName} 是
 * {@code @TableField(exist = false)}，查库查不出来。下拉开到 200 项、详情展开几十个资产，
 * 靠前端逐行补名会打出等量请求，故在服务端一次批量查全（同 {@code OwnershipTransferService}）。
 */
@Service
public class AssetTransferRecordService {

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_COMPLETED = "completed";

    /** 资产生命周期终态：已退出（处置完成 / 对外转出）。不能再交接责任。 */
    private static final String LIFECYCLE_EXITED = "exited";

    /** 资产下拉每页上限：前端每页 50，服务端夹一道防止 `pageSize=99999` 拖垮库。 */
    private static final long MAX_ASSET_OPTIONS_PAGE_SIZE = 200;

    /** 组织行/人员行的启用状态（{@code status = 1}，与 {@code OrgService} / {@code CompanyTreeService} 同口径）。 */
    private static final int STATUS_ENABLED = 1;

    private final AssetTransferRecordMapper recordMapper;
    private final AssetTransferRecordAssetMapper recordAssetMapper;
    private final AssetMapper assetMapper;
    private final ProjectMapper projectMapper;
    private final ProjectZoneMapper projectZoneMapper;
    private final CompanyMapper companyMapper;
    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;
    private final CompanyTreeService companyTreeService;
    private final RecordSheetService recordSheetService;

    public AssetTransferRecordService(
            AssetTransferRecordMapper recordMapper,
            AssetTransferRecordAssetMapper recordAssetMapper,
            AssetMapper assetMapper,
            ProjectMapper projectMapper,
            ProjectZoneMapper projectZoneMapper,
            CompanyMapper companyMapper,
            DepartmentMapper departmentMapper,
            UserMapper userMapper,
            CompanyTreeService companyTreeService,
            RecordSheetService recordSheetService) {
        this.recordMapper = recordMapper;
        this.recordAssetMapper = recordAssetMapper;
        this.assetMapper = assetMapper;
        this.projectMapper = projectMapper;
        this.projectZoneMapper = projectZoneMapper;
        this.companyMapper = companyMapper;
        this.departmentMapper = departmentMapper;
        this.userMapper = userMapper;
        this.companyTreeService = companyTreeService;
        this.recordSheetService = recordSheetService;
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    public PageResult<AssetTransferRecordView> page(long page, long pageSize, String status,
            Long companyId, String keyword) {
        Page<AssetTransferRecord> result = recordMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<AssetTransferRecord>()
                        .isNull(AssetTransferRecord::getDeletedAt)
                        .eq(status != null, AssetTransferRecord::getStatus, status)
                        .eq(companyId != null, AssetTransferRecord::getCompanyId, companyId)
                        .and(keyword != null && !keyword.isBlank(),
                                w -> w.like(AssetTransferRecord::getReason, keyword)
                                        .or()
                                        .like(AssetTransferRecord::getRemark, keyword))
                        .orderByDesc(AssetTransferRecord::getId));
        List<AssetTransferRecord> rows = result.getRecords();
        Map<Long, Integer> assetCounts =
                assetCountsByRecord(rows.stream().map(AssetTransferRecord::getId).toList());
        List<AssetTransferRecordView> views = new ArrayList<>(rows.size());
        for (AssetTransferRecord row : rows) {
            views.add(toView(row, assetCounts.getOrDefault(row.getId(), 0), false));
        }
        return PageResult.of(views, result.getTotal(), page, pageSize);
    }

    public AssetTransferRecordView get(Long id) {
        AssetTransferRecord row = require(id);
        Map<Long, Integer> assetCounts = assetCountsByRecord(List.of(id));
        return toView(row, assetCounts.getOrDefault(id, 0), true);
    }

    /**
     * 资产下拉：按「所属公司」联动过滤。
     *
     * <p><b>为什么不用既有的 {@code GET /assets}</b>：它要求 {@code asset.ledger:view}，
     * 而做调拨的人未必持有资产台账权限 —— 让他们因为缺台账权限就选不到资产，功能等于不存在。
     *
     * <p><b>为什么按 {@code asset_company_id} 而不是经营/产权公司</b>：本模块的「所属公司」
     * 就是资产台账上的所属公司（资产表单里责任部门/责任人的联动上级也是它），
     * 两个界面必须按同一个字段过滤，否则同一批资产在一个界面选得到、在另一个界面选不到。
     *
     * <p>已退出（{@code lifecycle_status = 'exited'}）的资产不出现在下拉里：处置完成或已对外
     * 转出的资产不再交接内部责任，出现在候选里只会让用户选完再被拒。
     */
    public PageResult<TransferRecordAssetOption> assetOptions(Long companyId, String keyword,
            long page, long pageSize) {
        if (companyId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请先选择所属公司");
        }
        long size = Math.min(Math.max(pageSize, 1), MAX_ASSET_OPTIONS_PAGE_SIZE);
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .isNull(Asset::getDeletedAt)
                .eq(Asset::getAssetCompanyId, companyId)
                .ne(Asset::getLifecycleStatus, LIFECYCLE_EXITED);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(Asset::getName, keyword)
                    .or()
                    .like(Asset::getAssetNo, keyword));
        }
        wrapper.orderByAsc(Asset::getId);
        Page<Asset> result = assetMapper.selectPage(new Page<>(page, size), wrapper);
        fillDisplayNames(result.getRecords());
        List<TransferRecordAssetOption> options = result.getRecords().stream().map(a -> {
            TransferRecordAssetOption option = new TransferRecordAssetOption();
            option.setAssetId(a.getId());
            option.setAssetNo(a.getAssetNo());
            option.setName(a.getName());
            option.setProjectName(a.getProjectName());
            option.setZoneName(a.getZoneName());
            option.setFloorNo(a.getFloorNo());
            return option;
        }).toList();
        return PageResult.of(options, result.getTotal(), page, size);
    }

    // ------------------------------------------------------------------
    // 写：草稿
    // ------------------------------------------------------------------

    @Transactional
    public AssetTransferRecordView create(AssetTransferRecordInput input) {
        AssetTransferRecord entity = new AssetTransferRecord();
        entity.setStatus(STATUS_DRAFT);
        DraftValidation validated = validateDraft(entity, input);
        recordMapper.insert(entity);
        replaceAssets(entity.getId(), validated.assetIds(), validated.assets(), Map.of());
        recordSheetService.syncAttachments(
                AttachmentOwner.ASSET_TRANSFER_RECORD, entity.getId(), input.getAttachments());
        return get(entity.getId());
    }

    @Transactional
    public AssetTransferRecordView update(Long id, AssetTransferRecordInput input) {
        AssetTransferRecord entity = requireDraft(id);
        DraftValidation validated = validateDraft(entity, input);
        recordMapper.updateById(entity);
        replaceAssets(entity.getId(), validated.assetIds(), validated.assets(),
                existingSnapshot(entity.getId()));
        recordSheetService.syncAttachments(
                AttachmentOwner.ASSET_TRANSFER_RECORD, entity.getId(), input.getAttachments());
        return get(entity.getId());
    }

    @Transactional
    public void delete(Long id) {
        AssetTransferRecord entity = requireDraft(id);
        // 软删：附件行不清理（与权属流转 / 处置单同口径）—— 附件挂在主单上，主单过滤掉就不会
        // 被读到，而物理删附件会让「谁在什么时候传过什么」这段审计信息消失
        entity.setDeletedAt(LocalDateTime.now());
        recordMapper.updateById(entity);
    }

    // ------------------------------------------------------------------
    // 写：生效
    // ------------------------------------------------------------------

    /**
     * 生效：把每个资产的责任部门 / 责任人改成单上的新值。
     *
     * <p><b>重跑全部校验</b>：草稿可能已经躺了很久 —— 公司、部门、人员、资产的归属都可能变过
     * （部门撤销、人员调岗、资产换了所属公司）。生效时以当时的真实情况为准，而不是草稿创建时的。
     *
     * <p><b>为什么逐资产用乐观锁而不是一条 UPDATE</b>：{@code asset.version} 是既有的并发保护；
     * 返回 0 说明有人在这期间改了资产，此时**整单回滚**比「改一半」安全 —— 部分资产换了责任人
     * 而单据没生效，是最难排查的状态。
     *
     * <p><b>幂等</b>：已完成的单直接返回，不会把责任部门再写一遍（写了也看不出，但会重复刷新
     * 明细快照，让「原值」变成「现值」—— 那等于把快照毁了）。
     */
    @Transactional
    public AssetTransferRecordView effect(Long id) {
        AssetTransferRecord entity = require(id);
        if (STATUS_COMPLETED.equals(entity.getStatus())) {
            return get(id);
        }
        if (!STATUS_DRAFT.equals(entity.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "只有草稿可以生效，当前状态：" + entity.getStatus());
        }

        DraftValidation validated = validateDraft(entity, toInput(entity));

        // 快照必须在**改写资产之前**取：它是「生效那一刻的旧状态」，放到循环之后就会记成
        // 改完的新值（草稿可能躺了几个月，中途责任部门已经变过）
        snapshotBeforeWrite(id, validated.assets(), existingSnapshot(id));

        for (Asset asset : validated.assets()) {
            asset.setResponsibleDepartmentId(entity.getToDepartmentId());
            asset.setResponsibleUserId(entity.getToUserId());
            if (assetMapper.updateById(asset) == 0) {
                throw new AppException(ErrorCode.CONFLICT,
                        "资产已被并发修改，请刷新重试：" + asset.getId());
            }
        }

        entity.setStatus(STATUS_COMPLETED);
        entity.setEffectedAt(LocalDateTime.now());
        recordMapper.updateById(entity);
        return get(id);
    }

    // ------------------------------------------------------------------
    // 校验：唯一的一份实现，create / update / effect 三处共用
    // ------------------------------------------------------------------

    /** {@code validateDraft} 的产物：去重后的资产 id + 校验通过的资产实体。 */
    private record DraftValidation(List<Long> assetIds, List<Asset> assets) {
    }

    /**
     * 校验草稿并把请求体写进 {@code entity}，返回去重后的资产 id 与校验通过的资产。
     *
     * <p><b>只有这一份校验</b>：{@code create}、{@code update}、{@code effect} 三处必须调它。
     * 仓内已为「同一语义两处判定漂移」付过代价（{@code replaceZones} vs
     * {@code deleteProjectZone} 的 {@code assertZoneRemovable} 收敛过程），本模块不留第二份。
     *
     * <p><b>为什么不校验抵押</b>：权属流转拦在押资产，是因为它改的是**产权主体**
     * （抵押权人关心谁持有）。本模块改的是公司内部的责任岗位，与抵押权无关，
     * 拦下来只会让正常交接做不了。
     *
     * <p><b>为什么不校验「新部门不得等于原部门」</b>：一张单挂多个资产，它们的原部门本来就
     * 可能各不相同，逐资产比较会在「把 A、B 两人的资产都交给 C 部门，其中恰好有一个已在 C」
     * 这种正常场景下误拒。
     */
    private DraftValidation validateDraft(AssetTransferRecord entity, AssetTransferRecordInput input) {
        if (input.getCompanyId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "所属公司必填");
        }
        if (!companyTreeService.isActiveCompany(input.getCompanyId())) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "所属公司不存在或已停用：" + input.getCompanyId());
        }
        if (input.getToDepartmentId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "新责任部门必填");
        }
        if (input.getToUserId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "新责任人必填");
        }

        assertDepartment(input.getToDepartmentId(), input.getCompanyId());
        if (input.getFromDepartmentId() != null) {
            assertDepartment(input.getFromDepartmentId(), input.getCompanyId());
        }
        assertUserInDepartment(input.getToUserId(), input.getToDepartmentId());

        List<Long> assetIds = dedupeAssetIds(input.getAssetIds());
        List<Asset> assets = loadAndValidateAssets(assetIds, input.getCompanyId());

        entity.setCompanyId(input.getCompanyId());
        entity.setFromDepartmentId(input.getFromDepartmentId());
        entity.setToDepartmentId(input.getToDepartmentId());
        entity.setToUserId(input.getToUserId());
        entity.setApprovalDeadline(input.getApprovalDeadline());
        entity.setReason(input.getReason());
        entity.setRemark(input.getRemark());
        return new DraftValidation(assetIds, assets);
    }

    /** 部门必须存在、启用，且属于所选公司 —— 否则会把资产交接给别的公司的部门。 */
    private void assertDepartment(Long departmentId, Long companyId) {
        Department department = departmentMapper.selectById(departmentId);
        if (department == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "责任部门不存在：" + departmentId);
        }
        if (department.getStatus() != null && department.getStatus() != STATUS_ENABLED) {
            throw new AppException(ErrorCode.BAD_REQUEST, "责任部门已停用：" + departmentId);
        }
        if (!Objects.equals(department.getCompanyId(), companyId)) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "责任部门不属于所选公司：" + departmentId);
        }
    }

    /** 责任人必须存在、启用，且属于新责任部门 —— 否则资产上会出现「部门与人对不上」。 */
    private void assertUserInDepartment(Long userId, Long departmentId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "责任人不存在：" + userId);
        }
        if (user.getStatus() != null && user.getStatus() != STATUS_ENABLED) {
            throw new AppException(ErrorCode.BAD_REQUEST, "责任人已停用：" + userId);
        }
        if (!Objects.equals(user.getDepartmentId(), departmentId)) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "责任人不属于新责任部门：" + userId);
        }
    }

    private List<Long> dedupeAssetIds(List<Long> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "资产列表至少选择 1 个资产");
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : raw) {
            if (id != null) {
                unique.add(id);
            }
        }
        if (unique.isEmpty()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "资产列表至少选择 1 个资产");
        }
        return new ArrayList<>(unique);
    }

    private List<Asset> loadAndValidateAssets(List<Long> assetIds, Long companyId) {
        List<Asset> assets = new ArrayList<>(assetIds.size());
        for (Long assetId : assetIds) {
            Asset asset = assetMapper.selectById(assetId);
            if (asset == null || asset.getDeletedAt() != null) {
                throw new AppException(ErrorCode.NOT_FOUND, "资产不存在或已删除：" + assetId);
            }
            if (LIFECYCLE_EXITED.equals(asset.getLifecycleStatus())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "资产已退出，不可调拨：" + assetId);
            }
            if (!Objects.equals(asset.getAssetCompanyId(), companyId)) {
                throw new AppException(ErrorCode.BAD_REQUEST,
                        "资产 #" + assetId + " 不属于所选公司");
            }
            assets.add(asset);
        }
        return assets;
    }

    // ------------------------------------------------------------------
    // 明细与视图
    // ------------------------------------------------------------------

    /** 全量替换明细（草稿阶段）：先删后插，与 {@code DisposalService.syncForAsset} 同一套做法。 */
    private void replaceAssets(Long recordId, List<Long> assetIds, List<Asset> assets,
            Map<Long, AssetTransferRecordAsset> previous) {
        Map<Long, Asset> byId = assets.stream()
                .collect(Collectors.toMap(Asset::getId, a -> a, (a, b) -> a));
        recordAssetMapper.delete(new LambdaQueryWrapper<AssetTransferRecordAsset>()
                .eq(AssetTransferRecordAsset::getRecordId, recordId));
        for (Long assetId : assetIds) {
            AssetTransferRecordAsset row = new AssetTransferRecordAsset();
            row.setRecordId(recordId);
            row.setAssetId(assetId);
            AssetTransferRecordAsset old = previous.get(assetId);
            if (old != null) {
                // 编辑草稿时保留已写下的原值快照，不要用当前值覆盖
                row.setFromDepartmentId(old.getFromDepartmentId());
                row.setFromUserId(old.getFromUserId());
            } else {
                Asset asset = byId.get(assetId);
                row.setFromDepartmentId(asset == null ? null : asset.getResponsibleDepartmentId());
                row.setFromUserId(asset == null ? null : asset.getResponsibleUserId());
            }
            recordAssetMapper.insert(row);
        }
    }

    private Map<Long, AssetTransferRecordAsset> existingSnapshot(Long recordId) {
        return recordAssetMapper.selectList(new LambdaQueryWrapper<AssetTransferRecordAsset>()
                        .eq(AssetTransferRecordAsset::getRecordId, recordId))
                .stream()
                .collect(Collectors.toMap(AssetTransferRecordAsset::getAssetId, a -> a, (a, b) -> a,
                        LinkedHashMap::new));
    }

    /**
     * 生效前把「改之前」的责任部门 / 责任人写进明细 —— 快照必须是**生效那一刻**的值，
     * 而不是草稿创建时的值（草稿可能躺了几个月，中间部门已经变过）。
     */
    private void snapshotBeforeWrite(Long recordId, List<Asset> assets,
            Map<Long, AssetTransferRecordAsset> previous) {
        for (Asset asset : assets) {
            AssetTransferRecordAsset row = previous.get(asset.getId());
            if (row == null) {
                row = new AssetTransferRecordAsset();
                row.setRecordId(recordId);
                row.setAssetId(asset.getId());
            }
            row.setFromDepartmentId(asset.getResponsibleDepartmentId());
            row.setFromUserId(asset.getResponsibleUserId());
            if (row.getId() == null) {
                recordAssetMapper.insert(row);
            } else {
                recordAssetMapper.updateById(row);
            }
        }
    }

    private Map<Long, Integer> assetCountsByRecord(List<Long> recordIds) {
        if (recordIds.isEmpty()) {
            return Map.of();
        }
        List<AssetTransferRecordAsset> rows = recordAssetMapper.selectList(
                new LambdaQueryWrapper<AssetTransferRecordAsset>()
                        .in(AssetTransferRecordAsset::getRecordId, recordIds));
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (AssetTransferRecordAsset row : rows) {
            counts.merge(row.getRecordId(), 1, Integer::sum);
        }
        return counts;
    }

    private AssetTransferRecord require(Long id) {
        AssetTransferRecord row = recordMapper.selectById(id);
        if (row == null || row.getDeletedAt() != null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产调拨记录不存在：" + id);
        }
        return row;
    }

    private AssetTransferRecord requireDraft(Long id) {
        AssetTransferRecord row = require(id);
        if (!STATUS_DRAFT.equals(row.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "只有草稿可以修改或删除");
        }
        return row;
    }

    /** 把已落库的主单还原成 {@code validateDraft} 能吃的入参（生效要重跑同一份校验）。 */
    private AssetTransferRecordInput toInput(AssetTransferRecord entity) {
        AssetTransferRecordInput input = new AssetTransferRecordInput();
        input.setCompanyId(entity.getCompanyId());
        input.setFromDepartmentId(entity.getFromDepartmentId());
        input.setToDepartmentId(entity.getToDepartmentId());
        input.setToUserId(entity.getToUserId());
        input.setApprovalDeadline(entity.getApprovalDeadline());
        input.setReason(entity.getReason());
        input.setRemark(entity.getRemark());
        input.setAssetIds(recordAssetMapper.selectList(
                        new LambdaQueryWrapper<AssetTransferRecordAsset>()
                                .eq(AssetTransferRecordAsset::getRecordId, entity.getId())
                                .orderByAsc(AssetTransferRecordAsset::getId))
                .stream()
                .map(AssetTransferRecordAsset::getAssetId)
                .toList());
        return input;
    }

    private AssetTransferRecordView toView(AssetTransferRecord row, int assetCount,
            boolean withAssets) {
        AssetTransferRecordView view = new AssetTransferRecordView();
        view.setId(row.getId());
        view.setCompanyId(row.getCompanyId());
        view.setCompanyName(companyNames(singleton(row.getCompanyId())).get(row.getCompanyId()));
        view.setFromDepartmentId(row.getFromDepartmentId());
        view.setToDepartmentId(row.getToDepartmentId());
        view.setToUserId(row.getToUserId());
        view.setToUserName(userNames(singleton(row.getToUserId())).get(row.getToUserId()));
        Map<Long, String> departmentNames =
                departmentNames(pair(row.getFromDepartmentId(), row.getToDepartmentId()));
        view.setFromDepartmentName(
                row.getFromDepartmentId() == null ? null : departmentNames.get(row.getFromDepartmentId()));
        view.setToDepartmentName(departmentNames.get(row.getToDepartmentId()));
        view.setApprovalDeadline(row.getApprovalDeadline());
        view.setReason(row.getReason());
        view.setRemark(row.getRemark());
        view.setStatus(row.getStatus());
        view.setEffectedAt(row.getEffectedAt());
        view.setCreatedAt(row.getCreatedAt());
        view.setAssetCount(assetCount);
        view.setAssets(withAssets ? assetViews(row.getId()) : null);
        view.setAttachments(
                recordSheetService.toAttachmentRefs(AttachmentOwner.ASSET_TRANSFER_RECORD, row.getId()));
        return view;
    }

    private List<AssetTransferRecordAssetView> assetViews(Long recordId) {
        List<AssetTransferRecordAsset> rows = recordAssetMapper.selectList(
                new LambdaQueryWrapper<AssetTransferRecordAsset>()
                        .eq(AssetTransferRecordAsset::getRecordId, recordId)
                        .orderByAsc(AssetTransferRecordAsset::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> assetIds = rows.stream()
                .map(AssetTransferRecordAsset::getAssetId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Asset> assets = assetMapper.selectBatchIds(assetIds).stream()
                .collect(Collectors.toMap(Asset::getId, a -> a, (a, b) -> a));
        fillDisplayNames(new ArrayList<>(assets.values()));
        // 两批名一次取全（而不是逐行查）：明细可以有几行到几十行
        Map<Long, String> departmentNames = departmentNames(rows.stream()
                .map(AssetTransferRecordAsset::getFromDepartmentId)
                .toList());
        Map<Long, String> userNames = userNames(rows.stream()
                .map(AssetTransferRecordAsset::getFromUserId)
                .toList());
        List<AssetTransferRecordAssetView> views = new ArrayList<>(rows.size());
        for (AssetTransferRecordAsset row : rows) {
            Asset asset = assets.get(row.getAssetId());
            AssetTransferRecordAssetView view = new AssetTransferRecordAssetView();
            view.setAssetId(row.getAssetId());
            view.setFromDepartmentId(row.getFromDepartmentId());
            view.setFromDepartmentName(row.getFromDepartmentId() == null
                    ? null
                    : departmentNames.get(row.getFromDepartmentId()));
            view.setFromUserId(row.getFromUserId());
            view.setFromUserName(
                    row.getFromUserId() == null ? null : userNames.get(row.getFromUserId()));
            if (asset != null) {
                view.setAssetNo(asset.getAssetNo());
                view.setAssetName(asset.getName());
                view.setProjectName(asset.getProjectName());
                view.setZoneName(asset.getZoneName());
                view.setFloorNo(asset.getFloorNo());
            }
            views.add(view);
        }
        return views;
    }

    /**
     * 回填项目 / 分区名：{@code projectName} / {@code zoneName} 是非表字段，查库查不出来。
     * 一次批量查全（而不是逐行查），避免列表页与资产下拉变成 N+1。
     */
    private void fillDisplayNames(List<Asset> assets) {
        if (assets == null || assets.isEmpty()) {
            return;
        }
        Set<Long> projectIds = assets.stream()
                .map(Asset::getProjectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!projectIds.isEmpty()) {
            Map<Long, String> projectNames = projectMapper.selectBatchIds(projectIds).stream()
                    .collect(Collectors.toMap(Project::getId, Project::getName, (a, b) -> a));
            assets.forEach(a -> a.setProjectName(projectNames.get(a.getProjectId())));
        }
        Set<Long> zoneIds = assets.stream()
                .map(Asset::getZoneId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!zoneIds.isEmpty()) {
            Map<Long, String> zoneNames = projectZoneMapper.selectBatchIds(zoneIds).stream()
                    .collect(Collectors.toMap(ProjectZone::getId, ProjectZone::getName, (a, b) -> a));
            assets.forEach(a -> a.setZoneName(zoneNames.get(a.getZoneId())));
        }
    }

    // ------------------------------------------------------------------
    // 名称批量解析：列表页每行都要显示公司 / 部门 / 人员名，逐行查就是 N+1
    // ------------------------------------------------------------------

    private Map<Long, String> companyNames(Collection<Long> ids) {
        Set<Long> unique = nonNullIds(ids);
        if (unique.isEmpty()) {
            return Map.of();
        }
        return companyMapper.selectBatchIds(unique).stream()
                .collect(Collectors.toMap(Company::getId, Company::getName, (a, b) -> a));
    }

    private Map<Long, String> departmentNames(Collection<Long> ids) {
        Set<Long> unique = nonNullIds(ids);
        if (unique.isEmpty()) {
            return Map.of();
        }
        return departmentMapper.selectBatchIds(unique).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName, (a, b) -> a));
    }

    private Map<Long, String> userNames(Collection<Long> ids) {
        Set<Long> unique = nonNullIds(ids);
        if (unique.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(unique).stream()
                .collect(Collectors.toMap(User::getId, User::getName, (a, b) -> a));
    }

    private Set<Long> nonNullIds(Collection<Long> ids) {
        if (ids == null) {
            return Set.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 单元素 id 列表。
     *
     * <p><b>不能用 {@code List.of}</b>：它对 null 元素抛 NPE，而 {@code from_department_id}
     * 本来就是可空列 —— 详情页打开一张没填前部门的单会 500。
     */
    private List<Long> singleton(Long id) {
        return Collections.singletonList(id);
    }

    /** 两个 id 一起批量取名（可空安全的 {@code List.of} 替代）。 */
    private List<Long> pair(Long first, Long second) {
        List<Long> ids = new ArrayList<>(2);
        ids.add(first);
        ids.add(second);
        return ids;
    }
}
