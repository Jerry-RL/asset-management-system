package com.ams.modules.ownership.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.ams.modules.asset.service.AssetHandoverBuilder;
import com.ams.modules.asset.service.CertificateService;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.ownership.dto.OwnershipTransferAssetView;
import com.ams.modules.ownership.dto.OwnershipTransferInput;
import com.ams.modules.ownership.dto.OwnershipTransferView;
import com.ams.modules.ownership.dto.TransferAssetOption;
import com.ams.modules.ownership.entity.OwnershipTransfer;
import com.ams.modules.ownership.entity.OwnershipTransferAsset;
import com.ams.modules.ownership.mapper.OwnershipTransferAssetMapper;
import com.ams.modules.ownership.mapper.OwnershipTransferMapper;
import com.ams.modules.record.AttachmentOwner;
import com.ams.modules.record.service.RecordSheetService;
import com.ams.platform.event.DomainEventPublisher;
import com.ams.platform.event.OwnershipTransferredEvent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * 权属流转（设计 §5）。一张单选多个资产，按权属类型改写
 * {@code property_company_id} / {@code operating_company_id}。
 *
 * <p>状态机只有 {@code draft → completed}：本期不接审批引擎（设计 §3.2），
 * 「生效」= 唯一的落库动作，且是**不可逆**的。
 *
 * <p><b>草稿不写任何预留行</b>：两个人可以同时对同一资产起草，谁先生效谁赢。
 * 这是刻意的 —— 本仓已为「预留永不收口」付过代价（见 {@code OccupationService.withdraw}
 * 的注释与改造清单 P0-5）。跨草稿的冲突改由「生效时按原公司重新校验」自然消解：
 * 第一张生效后，该资产的产权公司已不是第二张单的原公司，第二张生效会失败。
 *
 * <p><b>项目 / 分区名在本类回填</b>：{@code asset.projectName} / {@code asset.zoneName} 是
 * {@code @TableField(exist = false)}，查库查不出来。下拉开到 50 项、详情展开 20 个资产，
 * 靠前端逐行补名会打出等量请求，故在服务端一次批量查全（同 {@code AssetService.fillDisplayNames}）。
 */
@Service
public class OwnershipTransferService {

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_COMPLETED = "completed";

    private static final String SCOPE_PROPERTY = "property";
    private static final String SCOPE_OPERATING = "operating";
    private static final String SCOPE_BOTH = "both";

    /** 资产生命周期终态：已退出（处置完成 / 外部流转生效）。 */
    private static final String LIFECYCLE_EXITED = "exited";

    private static final Set<String> DIRECTIONS =
            Set.of(TransferDirectionResolver.INTERNAL, TransferDirectionResolver.EXTERNAL);
    private static final Set<String> SCOPES = Set.of(SCOPE_PROPERTY, SCOPE_OPERATING, SCOPE_BOTH);
    private static final Set<String> MODES = Set.of("allocate", "purchase", "auction");

    /** 资产下拉每页上限：前端每页 50，服务端夹一道防止 `pageSize=99999` 拖垮库。 */
    private static final long MAX_ASSET_OPTIONS_PAGE_SIZE = 200;

    private final OwnershipTransferMapper transferMapper;
    private final OwnershipTransferAssetMapper transferAssetMapper;
    private final AssetMapper assetMapper;
    private final ProjectMapper projectMapper;
    private final ProjectZoneMapper projectZoneMapper;
    private final TransferDirectionResolver directionResolver;
    private final CertificateService certificateService;
    private final AssetHandoverBuilder handoverBuilder;
    private final RecordSheetService recordSheetService;
    private final ObjectMapper objectMapper;
    private final UserMapper userMapper;
    private final DomainEventPublisher eventPublisher;

    public OwnershipTransferService(
            OwnershipTransferMapper transferMapper,
            OwnershipTransferAssetMapper transferAssetMapper,
            AssetMapper assetMapper,
            ProjectMapper projectMapper,
            ProjectZoneMapper projectZoneMapper,
            TransferDirectionResolver directionResolver,
            CertificateService certificateService,
            AssetHandoverBuilder handoverBuilder,
            RecordSheetService recordSheetService,
            ObjectMapper objectMapper,
            UserMapper userMapper,
            DomainEventPublisher eventPublisher) {
        this.transferMapper = transferMapper;
        this.transferAssetMapper = transferAssetMapper;
        this.assetMapper = assetMapper;
        this.projectMapper = projectMapper;
        this.projectZoneMapper = projectZoneMapper;
        this.directionResolver = directionResolver;
        this.certificateService = certificateService;
        this.handoverBuilder = handoverBuilder;
        this.recordSheetService = recordSheetService;
        this.objectMapper = objectMapper;
        this.userMapper = userMapper;
        this.eventPublisher = eventPublisher;
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    public PageResult<OwnershipTransferView> page(long page, long pageSize, String status,
            String direction, Long fromCompanyId, String keyword) {
        Page<OwnershipTransfer> result = transferMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<OwnershipTransfer>()
                        .isNull(OwnershipTransfer::getDeletedAt)
                        .eq(status != null, OwnershipTransfer::getStatus, status)
                        .eq(direction != null, OwnershipTransfer::getDirection, direction)
                        .eq(fromCompanyId != null, OwnershipTransfer::getFromCompanyId, fromCompanyId)
                        .and(keyword != null && !keyword.isBlank(),
                                w -> w.like(OwnershipTransfer::getReason, keyword)
                                        .or()
                                        .like(OwnershipTransfer::getApplicantName, keyword))
                        .orderByDesc(OwnershipTransfer::getId));
        Map<Long, String> companyNames = directionResolver.namesById();
        Map<Long, Integer> assetCounts = assetCountsByTransfer(
                result.getRecords().stream().map(OwnershipTransfer::getId).toList());
        List<OwnershipTransferView> views = new ArrayList<>();
        for (OwnershipTransfer row : result.getRecords()) {
            views.add(toView(row, companyNames, assetCounts.getOrDefault(row.getId(), 0), false));
        }
        return PageResult.of(views, result.getTotal(), page, pageSize);
    }

    public OwnershipTransferView get(Long id) {
        OwnershipTransfer row = require(id);
        Map<Long, String> companyNames = directionResolver.namesById();
        Map<Long, Integer> assetCounts = assetCountsByTransfer(List.of(id));
        return toView(row, companyNames, assetCounts.getOrDefault(id, 0), true);
    }

    /**
     * 资产下拉：按「原公司」联动过滤（设计 §5.1 / D5）。
     *
     * <p><b>为什么不用既有的 {@code GET /assets}</b>：两个原因。其一是它的 {@code companyId}
     * 过滤的是 {@code operating_company_id}，而这里要按**权属类型**选过滤字段；其二是它要求
     * {@code asset.ledger:view}，而权属流转岗未必持有资产台账权限 —— 让他们因为缺台账权限
     * 就选不到资产，功能等于不存在。
     *
     * <p>已退出（{@code lifecycle_status = 'exited'}）的资产不出现在下拉里：处置完成的资产
     * 不能再流转，出现在候选里只会让用户选完再被拒。
     */
    public PageResult<TransferAssetOption> assetOptions(Long companyId, String transferScope,
            String keyword, long page, long pageSize) {
        if (companyId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请先选择原产权公司");
        }
        String scope = normalizeScope(transferScope);
        long size = Math.min(Math.max(pageSize, 1), MAX_ASSET_OPTIONS_PAGE_SIZE);
        // 产权口径（property / both）过滤 property_company_id；经营权口径过滤 operating_company_id
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .isNull(Asset::getDeletedAt)
                .ne(Asset::getLifecycleStatus, LIFECYCLE_EXITED);
        if (SCOPE_OPERATING.equals(scope)) {
            wrapper.eq(Asset::getOperatingCompanyId, companyId);
        } else {
            wrapper.eq(Asset::getPropertyCompanyId, companyId);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(Asset::getName, keyword)
                    .or()
                    .like(Asset::getAssetNo, keyword));
        }
        wrapper.orderByAsc(Asset::getId);
        Page<Asset> result = assetMapper.selectPage(new Page<>(page, size), wrapper);
        fillDisplayNames(result.getRecords());
        List<TransferAssetOption> options = result.getRecords().stream().map(a -> {
            TransferAssetOption option = new TransferAssetOption();
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
    public OwnershipTransferView create(OwnershipTransferInput input) {
        OwnershipTransfer entity = new OwnershipTransfer();
        entity.setStatus(STATUS_DRAFT);
        DraftValidation validated = validateDraft(entity, input);
        transferMapper.insert(entity);
        replaceAssets(entity.getId(), validated.assetIds(), validated.assets(), Map.of());
        recordSheetService.syncAttachments(
                AttachmentOwner.OWNERSHIP_TRANSFER, entity.getId(), input.getAttachments());
        return get(entity.getId());
    }

    @Transactional
    public OwnershipTransferView update(Long id, OwnershipTransferInput input) {
        OwnershipTransfer entity = requireDraft(id);
        DraftValidation validated = validateDraft(entity, input);
        transferMapper.updateById(entity);
        replaceAssets(entity.getId(), validated.assetIds(), validated.assets(),
                existingSnapshot(entity.getId()));
        recordSheetService.syncAttachments(
                AttachmentOwner.OWNERSHIP_TRANSFER, entity.getId(), input.getAttachments());
        return get(entity.getId());
    }

    @Transactional
    public void delete(Long id) {
        OwnershipTransfer entity = requireDraft(id);
        // 软删：附件行不清理（与处置单同口径）—— 附件挂在主单上，主单过滤掉就不会被读到，
        // 而物理删附件会让「谁在什么时候传过什么」这段审计信息消失
        entity.setDeletedAt(LocalDateTime.now());
        transferMapper.updateById(entity);
    }

    // ------------------------------------------------------------------
    // 写：生效
    // ------------------------------------------------------------------

    /**
     * 生效：把每个资产的对应公司字段改成新公司（设计 §5.3）。
     *
     * <p><b>重跑全部校验</b>：草稿可能已经躺了很久 —— 公司树、抵押状态、资产的当前公司都可能
     * 变过。这一步同时是「跨草稿防重」机制：第一张单生效后，该资产的公司已不是第二张单的
     * 原公司，第二张生效会在这里失败。
     *
     * <p><b>为什么逐资产用乐观锁而不是一条 UPDATE</b>：{@code asset.version} 是既有的并发保护；
     * 返回 0 说明有人在这期间改了资产，此时**整单回滚**比「改一半」安全 ——
     * 部分资产换了公司而单据没生效，是最难排查的状态。
     *
     * <p><b>幂等</b>：已完成的单直接返回，不会把公司再改一遍（改了也没影响，但会重复发事件
     * 与重复写快照）。
     */
    @Transactional
    public OwnershipTransferView effect(Long id) {
        OwnershipTransfer entity = require(id);
        if (STATUS_COMPLETED.equals(entity.getStatus())) {
            return get(id);
        }
        if (!STATUS_DRAFT.equals(entity.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "只有草稿可以生效，当前状态：" + entity.getStatus());
        }

        DraftValidation validated = validateDraft(entity, toInput(entity));
        boolean external = TransferDirectionResolver.EXTERNAL.equals(entity.getDirection());

        // 快照与交接清单都必须在**改写资产之前**取：它们是「生效那一刻的旧状态」，
        // 放到循环之后就会记成改完的新值（草稿可能躺了几个月，中途公司已经变过）
        snapshotBeforeWrite(id, validated.assets(), existingSnapshot(id));
        entity.setHandoverJson(buildHandover(entity, validated.assets()));

        for (Asset asset : validated.assets()) {
            if (external) {
                // 不写租控状态：ADR-0019 已把「已退出」归还给生命周期字段，
                // 且 LeaseControlStatus.canTransition 只允许 DISPOSING→EXITED（设计 §5.4）
                asset.setOwnershipStatus("transferred_out");
                asset.setLifecycleStatus(LIFECYCLE_EXITED);
            }
            if (SCOPE_OPERATING.equals(entity.getTransferScope())) {
                asset.setOperatingCompanyId(entity.getToCompanyId());
            } else if (SCOPE_PROPERTY.equals(entity.getTransferScope())) {
                asset.setPropertyCompanyId(entity.getToCompanyId());
            } else {
                asset.setPropertyCompanyId(entity.getToCompanyId());
                asset.setOperatingCompanyId(entity.getToCompanyId());
            }
            if (assetMapper.updateById(asset) == 0) {
                throw new AppException(ErrorCode.CONFLICT,
                        "资产已被并发修改，请刷新重试：" + asset.getId());
            }
        }

        entity.setStatus(STATUS_COMPLETED);
        entity.setEffectedAt(LocalDateTime.now());
        transferMapper.updateById(entity);

        // 逐资产发事件（不是一张单一条）：通知与下游投影都按资产粒度消费
        for (Asset asset : validated.assets()) {
            eventPublisher.publishAfterCommit(new OwnershipTransferredEvent(
                    id, asset.getId(), entity.getFromCompanyId(), entity.getToCompanyId(),
                    entity.getDirection()));
        }
        return get(id);
    }

    // ------------------------------------------------------------------
    // 校验：唯一的一份实现，create / update / effect 三处共用（设计 §5.5）
    // ------------------------------------------------------------------

    /** {@code validateDraft} 的产物：去重后的资产 id + 校验通过的资产实体。 */
    private record DraftValidation(List<Long> assetIds, List<Asset> assets) {
    }

    /**
     * 校验草稿并把请求体写进 {@code entity}，返回去重后的资产 id 与校验通过的资产。
     *
     * <p><b>只有这一份校验</b>：{@code create}、{@code update}、{@code effect} 三处必须调它。
     * 仓内已为「同一语义两处判定漂移」付过代价（{@code replaceZones} vs
     * {@code deleteProjectZone} 的 {@code assertZoneRemovable} 收敛过程），
     * 本模块从一开始就不留第二份。
     */
    private DraftValidation validateDraft(OwnershipTransfer entity, OwnershipTransferInput input) {
        if (input.getFromCompanyId() == null || input.getToCompanyId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "原公司与新公司均必填");
        }
        if (Objects.equals(input.getFromCompanyId(), input.getToCompanyId())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "新公司与原公司不能相同");
        }
        String direction = requireIn(input.getDirection(), DIRECTIONS, "流转方向");
        String scope = normalizeScope(input.getTransferScope());
        String mode = requireIn(input.getTransferMode(), MODES, "流转类型");

        // 方向必须与公司树一致（不存在的公司在这里被挡掉 —— 否则 ancestorIds 会把它当自己的根）
        directionResolver.assertDirectionMatches(
                direction, input.getFromCompanyId(), input.getToCompanyId());

        assertAmountScale(input.getAmountWan());

        // 申请人：内员时用 sys_user.name 覆盖快照，外部人员要求手填非空（设计 §5.3 规则 5）
        String applicantName = applicantName(input);

        List<Long> assetIds = dedupeAssetIds(input.getAssetIds());
        List<Asset> assets = loadAndValidateAssets(assetIds, scope, input.getFromCompanyId());

        entity.setDirection(direction);
        entity.setTransferScope(scope);
        entity.setFromCompanyId(input.getFromCompanyId());
        entity.setToCompanyId(input.getToCompanyId());
        entity.setTransferMode(mode);
        entity.setApplicantUserId(input.getApplicantUserId());
        entity.setApplicantName(applicantName);
        entity.setApprovalDeadline(input.getApprovalDeadline());
        entity.setAmountWan(input.getAmountWan());
        entity.setReason(input.getReason());
        return new DraftValidation(assetIds, assets);
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

    private List<Asset> loadAndValidateAssets(List<Long> assetIds, String scope, Long fromCompanyId) {
        List<Asset> assets = new ArrayList<>(assetIds.size());
        for (Long assetId : assetIds) {
            Asset asset = assetMapper.selectById(assetId);
            if (asset == null || asset.getDeletedAt() != null) {
                throw new AppException(ErrorCode.NOT_FOUND, "资产不存在或已删除：" + assetId);
            }
            if (LIFECYCLE_EXITED.equals(asset.getLifecycleStatus())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "资产已退出，不可流转：" + assetId);
            }
            // 按权属类型校验归属：property/both 看产权公司，operating 看经营公司
            if (SCOPE_OPERATING.equals(scope)) {
                assertSameCompany(asset.getOperatingCompanyId(), fromCompanyId, assetId, "经营公司");
            } else if (SCOPE_PROPERTY.equals(scope)) {
                assertSameCompany(asset.getPropertyCompanyId(), fromCompanyId, assetId, "产权公司");
            } else {
                assertSameCompany(asset.getPropertyCompanyId(), fromCompanyId, assetId, "产权公司");
                assertSameCompany(asset.getOperatingCompanyId(), fromCompanyId, assetId, "经营公司");
            }
            certificateService.assertNotMortgaged(assetId);
            assets.add(asset);
        }
        return assets;
    }

    private void assertSameCompany(Long actual, Long expected, Long assetId, String label) {
        if (!Objects.equals(actual, expected)) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "资产 #" + assetId + " 的" + label + "不属于所选原公司");
        }
    }

    private String applicantName(OwnershipTransferInput input) {
        if (input.getApplicantUserId() != null) {
            // 内员：姓名以数据库为准，不用前端传来的字符串（前端可改，而姓名是快照）
            User user = userMapper.selectById(input.getApplicantUserId());
            if (user == null) {
                throw new AppException(ErrorCode.BAD_REQUEST, "申请人不存在：" + input.getApplicantUserId());
            }
            return user.getName();
        }
        if (input.getApplicantName() == null || input.getApplicantName().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "变更申请人必填");
        }
        return input.getApplicantName().trim();
    }

    private void assertAmountScale(BigDecimal amountWan) {
        if (amountWan != null && amountWan.scale() > 2) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "金额(万元)最多 2 位小数，当前传入 " + amountWan.toPlainString());
        }
    }

    private String normalizeScope(String raw) {
        return requireIn(raw, SCOPES, "权属类型");
    }

    private String requireIn(String raw, Set<String> allowed, String label) {
        if (raw == null || !allowed.contains(raw)) {
            throw new AppException(ErrorCode.BAD_REQUEST, label + "取值非法：" + raw);
        }
        return raw;
    }

    // ------------------------------------------------------------------
    // 明细与视图
    // ------------------------------------------------------------------

    /** 全量替换明细（草稿阶段）：先删后插，与 {@code DisposalService.syncForAsset} 同一套做法。 */
    private void replaceAssets(Long transferId, List<Long> assetIds, List<Asset> assets,
            Map<Long, OwnershipTransferAsset> previous) {
        Map<Long, Asset> byId = assets.stream()
                .collect(Collectors.toMap(Asset::getId, a -> a, (a, b) -> a));
        transferAssetMapper.delete(new LambdaQueryWrapper<OwnershipTransferAsset>()
                .eq(OwnershipTransferAsset::getTransferId, transferId));
        for (Long assetId : assetIds) {
            OwnershipTransferAsset row = new OwnershipTransferAsset();
            row.setTransferId(transferId);
            row.setAssetId(assetId);
            OwnershipTransferAsset old = previous.get(assetId);
            if (old != null) {
                // 编辑草稿时保留已写下的原值快照，不要用当前值覆盖
                row.setFromPropertyCompanyId(old.getFromPropertyCompanyId());
                row.setFromOperatingCompanyId(old.getFromOperatingCompanyId());
            } else {
                Asset asset = byId.get(assetId);
                row.setFromPropertyCompanyId(asset == null ? null : asset.getPropertyCompanyId());
                row.setFromOperatingCompanyId(asset == null ? null : asset.getOperatingCompanyId());
            }
            transferAssetMapper.insert(row);
        }
    }

    private Map<Long, OwnershipTransferAsset> existingSnapshot(Long transferId) {
        return transferAssetMapper.selectList(new LambdaQueryWrapper<OwnershipTransferAsset>()
                        .eq(OwnershipTransferAsset::getTransferId, transferId))
                .stream()
                .collect(Collectors.toMap(OwnershipTransferAsset::getAssetId, a -> a, (a, b) -> a,
                        LinkedHashMap::new));
    }

    /**
     * 生效前把「改之前」的公司值写进明细 —— 快照必须是**生效那一刻**的值，
     * 而不是草稿创建时的值（草稿可能躺了几个月，中间公司已经变过）。
     */
    private void snapshotBeforeWrite(Long transferId, List<Asset> assets,
            Map<Long, OwnershipTransferAsset> previous) {
        for (Asset asset : assets) {
            OwnershipTransferAsset row = previous.get(asset.getId());
            if (row == null) {
                row = new OwnershipTransferAsset();
                row.setTransferId(transferId);
                row.setAssetId(asset.getId());
            }
            row.setFromPropertyCompanyId(asset.getPropertyCompanyId());
            row.setFromOperatingCompanyId(asset.getOperatingCompanyId());
            if (row.getId() == null) {
                transferAssetMapper.insert(row);
            } else {
                transferAssetMapper.updateById(row);
            }
        }
    }

    /**
     * 交接清单快照：**每张单一个**（不是每资产一列），以「assetId → 快照」的 Map 存 JSON。
     *
     * <p>一张单可以有几十个资产，逐个存一列会需要第二张表；而快照只用于双方对账查看，
     * 一次读全更实用。键是字符串（JSON 对象的键必须是字符串），前端读时注意。
     */
    private String buildHandover(OwnershipTransfer entity, List<Asset> assets) {
        Map<String, Object> all = new LinkedHashMap<>();
        for (Asset asset : assets) {
            all.put(String.valueOf(asset.getId()),
                    handoverBuilder.build(asset, entity.getFromCompanyId(), entity.getToCompanyId(),
                            Map.of("transferScope", entity.getTransferScope(),
                                    "transferMode", entity.getTransferMode(),
                                    "direction", entity.getDirection())));
        }
        try {
            return objectMapper.writeValueAsString(all);
        } catch (Exception ex) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "生成交接清单失败");
        }
    }

    private Map<Long, Integer> assetCountsByTransfer(List<Long> transferIds) {
        if (transferIds.isEmpty()) {
            return Map.of();
        }
        List<OwnershipTransferAsset> rows = transferAssetMapper.selectList(
                new LambdaQueryWrapper<OwnershipTransferAsset>()
                        .in(OwnershipTransferAsset::getTransferId, transferIds));
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (OwnershipTransferAsset row : rows) {
            counts.merge(row.getTransferId(), 1, Integer::sum);
        }
        return counts;
    }

    private OwnershipTransfer require(Long id) {
        OwnershipTransfer row = transferMapper.selectById(id);
        if (row == null || row.getDeletedAt() != null) {
            throw new AppException(ErrorCode.NOT_FOUND, "权属流转单不存在：" + id);
        }
        return row;
    }

    private OwnershipTransfer requireDraft(Long id) {
        OwnershipTransfer row = require(id);
        if (!STATUS_DRAFT.equals(row.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "只有草稿可以修改或删除");
        }
        return row;
    }

    /** 把已落库的主单还原成 {@code validateDraft} 能吃的入参（生效要重跑同一份校验）。 */
    private OwnershipTransferInput toInput(OwnershipTransfer entity) {
        OwnershipTransferInput input = new OwnershipTransferInput();
        input.setDirection(entity.getDirection());
        input.setTransferScope(entity.getTransferScope());
        input.setFromCompanyId(entity.getFromCompanyId());
        input.setToCompanyId(entity.getToCompanyId());
        input.setTransferMode(entity.getTransferMode());
        input.setApplicantUserId(entity.getApplicantUserId());
        input.setApplicantName(entity.getApplicantName());
        input.setApprovalDeadline(entity.getApprovalDeadline());
        input.setAmountWan(entity.getAmountWan());
        input.setReason(entity.getReason());
        input.setAssetIds(transferAssetMapper.selectList(
                        new LambdaQueryWrapper<OwnershipTransferAsset>()
                                .eq(OwnershipTransferAsset::getTransferId, entity.getId())
                                .orderByAsc(OwnershipTransferAsset::getId))
                .stream()
                .map(OwnershipTransferAsset::getAssetId)
                .toList());
        return input;
    }

    private OwnershipTransferView toView(OwnershipTransfer row, Map<Long, String> companyNames,
            int assetCount, boolean withAssets) {
        OwnershipTransferView view = new OwnershipTransferView();
        view.setId(row.getId());
        view.setDirection(row.getDirection());
        view.setTransferScope(row.getTransferScope());
        view.setFromCompanyId(row.getFromCompanyId());
        view.setFromCompanyName(companyNames.get(row.getFromCompanyId()));
        view.setToCompanyId(row.getToCompanyId());
        view.setToCompanyName(companyNames.get(row.getToCompanyId()));
        view.setTransferMode(row.getTransferMode());
        view.setApplicantUserId(row.getApplicantUserId());
        view.setApplicantName(row.getApplicantName());
        view.setApprovalDeadline(row.getApprovalDeadline());
        view.setAmountWan(row.getAmountWan());
        view.setReason(row.getReason());
        view.setStatus(row.getStatus());
        view.setEffectedAt(row.getEffectedAt());
        view.setCreatedAt(row.getCreatedAt());
        view.setAssetCount(assetCount);
        view.setAssets(withAssets ? assetViews(row.getId()) : null);
        view.setAttachments(
                recordSheetService.toAttachmentRefs(AttachmentOwner.OWNERSHIP_TRANSFER, row.getId()));
        return view;
    }

    private List<OwnershipTransferAssetView> assetViews(Long transferId) {
        List<OwnershipTransferAsset> rows = transferAssetMapper.selectList(
                new LambdaQueryWrapper<OwnershipTransferAsset>()
                        .eq(OwnershipTransferAsset::getTransferId, transferId)
                        .orderByAsc(OwnershipTransferAsset::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> assetIds = rows.stream()
                .map(OwnershipTransferAsset::getAssetId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Asset> assets = assetMapper.selectBatchIds(assetIds).stream()
                .collect(Collectors.toMap(Asset::getId, a -> a, (a, b) -> a));
        fillDisplayNames(new ArrayList<>(assets.values()));
        List<OwnershipTransferAssetView> views = new ArrayList<>(rows.size());
        for (OwnershipTransferAsset row : rows) {
            Asset asset = assets.get(row.getAssetId());
            OwnershipTransferAssetView view = new OwnershipTransferAssetView();
            view.setAssetId(row.getAssetId());
            view.setFromPropertyCompanyId(row.getFromPropertyCompanyId());
            view.setFromOperatingCompanyId(row.getFromOperatingCompanyId());
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
}
