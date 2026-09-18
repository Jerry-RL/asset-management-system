package com.ams.modules.disposal.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.ams.modules.disposal.dto.AssetDisposalRecordView;
import com.ams.modules.disposal.entity.AssetDisposalRecord;
import com.ams.modules.disposal.mapper.AssetDisposalRecordMapper;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.mapper.CompanyMapper;
import com.ams.modules.record.DisposalCascadePort;
import com.ams.modules.record.DisposalCascadeSnapshot;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
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
 * 资产处置记录（V57）：**读**是「被处置资产」台账列表，**写**只有一个入口 ——
 * {@link #cascadeDispose}（级联处置）。
 *
 * <h2>级联处置做了什么</h2>
 * <ol>
 *   <li>按处置对象（资产 / 项目 / 分区）解析出受影响的资产；</li>
 *   <li>对每个**尚未退出**的资产：先快照原产权 / 经营公司，再清空
 *       {@code asset.property_company_id}，置 {@code ownership_status='disposed'}、
 *       {@code lifecycle_status='exited'}；</li>
 *   <li>写一行 {@code asset_disposal_record}（含快照与处置信息），供记录菜单展示与追溯。</li>
 * </ol>
 *
 * <h2>三条不能省的约束</h2>
 * <ul>
 *   <li><b>幂等</b>：只处置 {@code lifecycle_status <> 'exited'} 的资产；重复级联（同一张
 *       项目处置台账被改动、或两个层级同时处置同一批资产）不会产生第二批副作用。
 *       表上另有 {@code uk_asset_disposal_record_asset} 唯一索引兜底并发。</li>
 *   <li><b>不可逆</b>：级联**只增不减**。来源的 {@code biz_disposal_record} 被软删时不回收
 *       资产状态 —— 处置已经发生，把产权公司再"还回去"要靠权属流转重新登记，不能让
 *       删一张记录表把历史抹掉。</li>
 *   <li><b>先快照后清空</b>：公司字段在资产上被清空，只看资产反推不出「从哪家公司处置出去」，
 *       所以快照必须落在台账里。</li>
 * </ul>
 */
@Service
public class AssetDisposalRecordService implements DisposalCascadePort {

    private static final Set<String> TARGET_TYPES =
            Set.of(DisposalCascadePort.TARGET_ASSET, DisposalCascadePort.TARGET_PROJECT,
                    DisposalCascadePort.TARGET_ZONE);

    /** 资产生命周期终态：已退出（处置完成 / 对外转出）。 */
    private static final String LIFECYCLE_EXITED = "exited";

    /** asset.ownership_status 的处置取值（V54 建列，V57 起被处置写入）。 */
    private static final String OWNERSHIP_DISPOSED = "disposed";

    private final AssetDisposalRecordMapper disposalRecordMapper;
    private final AssetMapper assetMapper;
    private final ProjectMapper projectMapper;
    private final ProjectZoneMapper projectZoneMapper;
    private final CompanyMapper companyMapper;

    public AssetDisposalRecordService(
            AssetDisposalRecordMapper disposalRecordMapper,
            AssetMapper assetMapper,
            ProjectMapper projectMapper,
            ProjectZoneMapper projectZoneMapper,
            CompanyMapper companyMapper) {
        this.disposalRecordMapper = disposalRecordMapper;
        this.assetMapper = assetMapper;
        this.projectMapper = projectMapper;
        this.projectZoneMapper = projectZoneMapper;
        this.companyMapper = companyMapper;
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 被处置资产列表。
     *
     * @param fromCompanyId 原产权公司（台账里的快照列，资产上已被清空）
     * @param targetType    asset / project / zone
     * @param disposalType  处置方式
     * @param keyword       资产编号 / 名称（经子查询命中 asset，避免把关键字当字符串拼进 SQL）
     */
    public PageResult<AssetDisposalRecordView> page(long page, long pageSize, Long fromCompanyId,
            String targetType, String disposalType, String keyword) {
        LambdaQueryWrapper<AssetDisposalRecord> wrapper = new LambdaQueryWrapper<AssetDisposalRecord>()
                .eq(fromCompanyId != null, AssetDisposalRecord::getFromPropertyCompanyId, fromCompanyId)
                .eq(targetType != null && !targetType.isBlank(),
                        AssetDisposalRecord::getTargetType, targetType)
                .eq(disposalType != null && !disposalType.isBlank(),
                        AssetDisposalRecord::getDisposalType, disposalType);
        if (keyword != null && !keyword.isBlank()) {
            // {0} 是 JDBC 参数占位（MyBatis-Plus apply 会做参数绑定），不是字符串拼接
            String like = "%" + keyword.trim() + "%";
            wrapper.apply("asset_id IN (SELECT id FROM asset"
                            + " WHERE deleted_at IS NULL AND (name LIKE {0} OR asset_no LIKE {0}))",
                    like);
        }
        wrapper.orderByDesc(AssetDisposalRecord::getId);
        Page<AssetDisposalRecord> result = disposalRecordMapper.selectPage(new Page<>(page, pageSize), wrapper);
        return PageResult.of(toViews(result.getRecords()), result.getTotal(), page, pageSize);
    }

    public AssetDisposalRecordView get(Long id) {
        AssetDisposalRecord row = disposalRecordMapper.selectById(id);
        if (row == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产处置记录不存在：" + id);
        }
        List<AssetDisposalRecordView> views = toViews(List.of(row));
        return views.get(0);
    }

    // ------------------------------------------------------------------
    // 写：级联处置（唯一入口）
    // ------------------------------------------------------------------

    /**
     * 把某个处置对象下的资产级联为「已处置」。
     *
     * <p>调用点只有两个，且都在同一个事务里：
     * <ul>
     *   <li>{@code DisposalService.complete} —— 资产级处置单完成；</li>
     *   <li>{@code RecordSheetService.syncDisposalRecords} —— 项目 / 分区新增一条处置台账。</li>
     * </ul>
     *
     * @return 本次真正被处置的资产数（不含此前已退出的资产）
     */
    @Override
    @Transactional
    public int cascadeDispose(String targetType, Long targetId, DisposalCascadeSnapshot snapshot,
            Long sourceOrderId, Long sourceRecordId) {
        if (targetType == null || !TARGET_TYPES.contains(targetType)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "处置对象层级非法：" + targetType);
        }
        if (targetId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "处置对象不能为空");
        }
        List<Asset> assets = resolveAssets(targetType, targetId);
        int disposed = 0;
        for (Asset asset : assets) {
            if (disposeOne(asset, targetType, targetId, snapshot, sourceOrderId, sourceRecordId)) {
                disposed++;
            }
        }
        return disposed;
    }

    /**
     * 处置单个资产。
     *
     * @return 是否真的写入了（已退出的资产返回 false，不产生副作用也不报错）
     */
    private boolean disposeOne(Asset asset, String targetType, Long targetId,
            DisposalCascadeSnapshot snapshot, Long sourceOrderId, Long sourceRecordId) {
        if (LIFECYCLE_EXITED.equals(asset.getLifecycleStatus())) {
            // 已退出：既不重复清空公司字段，也不重复写台账。这是幂等的唯一判据 ——
            // 不要额外比对 ownership_status，否则「对外转出」的资产会被误判为可再次处置。
            return false;
        }
        AssetDisposalRecord record = new AssetDisposalRecord();
        record.setAssetId(asset.getId());
        record.setTargetType(targetType);
        record.setTargetId(targetId);
        record.setSourceOrderId(sourceOrderId);
        record.setSourceRecordId(sourceRecordId);
        record.setFromPropertyCompanyId(asset.getPropertyCompanyId());
        record.setFromOperatingCompanyId(asset.getOperatingCompanyId());
        if (snapshot != null) {
            record.setDisposalType(snapshot.disposalType());
            record.setDisposalAmount(snapshot.disposalAmount());
            record.setAmountUnit(snapshot.amountUnit());
            record.setDisposalDate(snapshot.disposalDate());
            record.setDisposalUserId(snapshot.disposalUserId());
            record.setDisposalUserName(snapshot.disposalUserName());
            record.setRemark(snapshot.remark());
        }
        record.setDisposedAt(LocalDateTime.now());

        // 先落台账再改资产：唯一索引（asset_id）在并发下会先拦住第二个写入者，
        // 而不是等到两个事务都把产权公司清空之后才冲突（那时回滚面更大）。
        disposalRecordMapper.insert(record);

        // 处置 = 脱离原产权公司：清空产权公司（经营公司保留，资产仍留在原经营主体的数据范围里），
        // ownership_status='disposed' 与 transferred_out 区分开，「卖掉了」和「转出去了」不会混淆。
        asset.setOwnershipStatus(OWNERSHIP_DISPOSED);
        asset.setLifecycleStatus(LIFECYCLE_EXITED);
        asset.setPropertyCompanyId(null);
        if (assetMapper.updateById(asset) == 0) {
            throw new AppException(ErrorCode.CONFLICT,
                    "资产已被并发修改，处置未生效，请刷新重试：" + asset.getId());
        }
        return true;
    }

    /**
     * 解析处置对象下的资产。
     *
     * <p>项目 / 分区维度**只取未软删的资产**；已退出的资产留给 {@link #disposeOne} 跳过，
     * 这样「级联」的语义是「把还在的资产一并处置」，而不是「必须一个不少」。
     */
    private List<Asset> resolveAssets(String targetType, Long targetId) {
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .isNull(Asset::getDeletedAt);
        switch (targetType) {
            case TARGET_ASSET -> {
                Asset asset = assetMapper.selectById(targetId);
                if (asset == null || asset.getDeletedAt() != null) {
                    throw new AppException(ErrorCode.NOT_FOUND, "资产不存在或已删除：" + targetId);
                }
                return List.of(asset);
            }
            case TARGET_PROJECT -> wrapper.eq(Asset::getProjectId, targetId);
            case TARGET_ZONE -> wrapper.eq(Asset::getZoneId, targetId);
            default -> throw new AppException(ErrorCode.BAD_REQUEST, "处置对象层级非法：" + targetType);
        }
        return assetMapper.selectList(wrapper);
    }

    // ------------------------------------------------------------------
    // 视图装配：名称批量回填，避免列表 N+1
    // ------------------------------------------------------------------

    private List<AssetDisposalRecordView> toViews(List<AssetDisposalRecord> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Set<Long> assetIds = rows.stream()
                .map(AssetDisposalRecord::getAssetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Asset> assets = assetIds.isEmpty()
                ? Map.of()
                : assetMapper.selectBatchIds(assetIds).stream()
                        .collect(Collectors.toMap(Asset::getId, a -> a, (a, b) -> a));
        fillDisplayNames(new ArrayList<>(assets.values()));

        Map<Long, String> companyNames = companyNames(rows.stream()
                .flatMap(r -> java.util.stream.Stream.of(
                        r.getFromPropertyCompanyId(), r.getFromOperatingCompanyId()))
                .toList());

        List<AssetDisposalRecordView> views = new ArrayList<>(rows.size());
        for (AssetDisposalRecord row : rows) {
            AssetDisposalRecordView view = new AssetDisposalRecordView();
            view.setId(row.getId());
            view.setAssetId(row.getAssetId());
            view.setTargetType(row.getTargetType());
            view.setTargetId(row.getTargetId());
            view.setSourceOrderId(row.getSourceOrderId());
            view.setSourceRecordId(row.getSourceRecordId());
            view.setFromPropertyCompanyId(row.getFromPropertyCompanyId());
            view.setFromPropertyCompanyName(
                    row.getFromPropertyCompanyId() == null
                            ? null
                            : companyNames.get(row.getFromPropertyCompanyId()));
            view.setFromOperatingCompanyId(row.getFromOperatingCompanyId());
            view.setFromOperatingCompanyName(
                    row.getFromOperatingCompanyId() == null
                            ? null
                            : companyNames.get(row.getFromOperatingCompanyId()));
            view.setDisposalType(row.getDisposalType());
            view.setDisposalAmount(row.getDisposalAmount());
            view.setAmountUnit(row.getAmountUnit());
            view.setDisposalDate(row.getDisposalDate());
            view.setDisposalUserId(row.getDisposalUserId());
            view.setDisposalUserName(row.getDisposalUserName());
            view.setRemark(row.getRemark());
            view.setDisposedAt(row.getDisposedAt());
            Asset asset = assets.get(row.getAssetId());
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
     * 一次批量查全（而不是逐行查），避免列表页变成 N+1（同 {@code AssetTransferRecordService}）。
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

    private Map<Long, String> companyNames(Collection<Long> ids) {
        Set<Long> unique = ids == null ? Set.of() : ids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (unique.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = companyMapper.selectBatchIds(unique).stream()
                .collect(Collectors.toMap(Company::getId, Company::getName, (a, b) -> a,
                        LinkedHashMap::new));
        return names;
    }
}
