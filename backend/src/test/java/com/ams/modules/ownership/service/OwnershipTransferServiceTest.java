package com.ams.modules.ownership.service;

import static com.ams.modules.ownership.TransferDirectionResolverTestSupport.CHENGTOU;
import static com.ams.modules.ownership.TransferDirectionResolverTestSupport.CHENGTOU_OPERATION;
import static com.ams.modules.ownership.TransferDirectionResolverTestSupport.OUTSIDE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.ownership.TransferDirectionResolverTestSupport;
import com.ams.modules.ownership.dto.OwnershipTransferInput;
import com.ams.modules.ownership.dto.OwnershipTransferView;
import com.ams.modules.ownership.dto.TransferAssetOption;
import com.ams.modules.ownership.entity.OwnershipTransfer;
import com.ams.modules.ownership.entity.OwnershipTransferAsset;
import com.ams.modules.ownership.mapper.OwnershipTransferAssetMapper;
import com.ams.modules.ownership.mapper.OwnershipTransferMapper;
import com.ams.modules.record.service.RecordSheetService;
import com.ams.platform.event.DomainEventPublisher;
import com.ams.platform.event.OwnershipTransferredEvent;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 权属流转的校验闭环与生效落库（设计 §5.3）。
 *
 * <p>两类关注点分开测：前半段是**草稿**（方向一致性、按权属类型的归属校验、在押拦截、
 * 非草稿不可改），后半段是**生效**（写哪些字段、外部流转标记、幂等、乐观锁、事件与交接清单）。
 * 分开写是为了改一处时错误信息指向正确的地方。
 */
class OwnershipTransferServiceTest {

    private static final long FROM = CHENGTOU;

    private static final long TO = CHENGTOU_OPERATION;

    /** mock 的 insert 不会回填自增主键，故用固定值模拟数据库回填。 */
    private static final long NEW_ID = 7L;

    private OwnershipTransferMapper transferMapper;
    private OwnershipTransferAssetMapper transferAssetMapper;
    private AssetMapper assetMapper;
    private ProjectMapper projectMapper;
    private ProjectZoneMapper projectZoneMapper;
    private CertificateService certificateService;
    private AssetHandoverBuilder handoverBuilder;
    private RecordSheetService recordSheetService;
    private UserMapper userMapper;
    private DomainEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        transferMapper = mock(OwnershipTransferMapper.class);
        transferAssetMapper = mock(OwnershipTransferAssetMapper.class);
        assetMapper = mock(AssetMapper.class);
        projectMapper = mock(ProjectMapper.class);
        projectZoneMapper = mock(ProjectZoneMapper.class);
        certificateService = mock(CertificateService.class);
        handoverBuilder = mock(AssetHandoverBuilder.class);
        recordSheetService = mock(RecordSheetService.class);
        userMapper = mock(UserMapper.class);
        eventPublisher = mock(DomainEventPublisher.class);
        // 明细默认空：列表 / 详情回读不展开明细，避免 mock 返回 null 造成 NPE
        when(transferAssetMapper.selectList(any())).thenReturn(List.of());
        // mock 的 selectBatchIds 默认返回 null（真实实现返回空集合），不桩住会让回填名称 NPE
        when(assetMapper.selectBatchIds(any())).thenReturn(List.of());
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of());
        when(projectZoneMapper.selectBatchIds(any())).thenReturn(List.of());
        // insert 之后 create/update 会立刻 get(id) 回读：让 selectById 能返回同一条，
        // 否则 mock 下主键为 null、回读必然 404 —— 那是测试夹具的缺口，不是被测行为
        when(transferMapper.insert(any(OwnershipTransfer.class))).thenAnswer(inv -> {
            OwnershipTransfer row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(NEW_ID);
            }
            when(transferMapper.selectById(row.getId())).thenReturn(row);
            return 1;
        });
    }

    private OwnershipTransferService newService() {
        return new OwnershipTransferService(
                transferMapper,
                transferAssetMapper,
                assetMapper,
                projectMapper,
                projectZoneMapper,
                new TransferDirectionResolver(TransferDirectionResolverTestSupport.tree()),
                certificateService,
                handoverBuilder,
                recordSheetService,
                new ObjectMapper(),
                userMapper,
                eventPublisher);
    }

    private static Asset asset(long id, Long propertyCompanyId, Long operatingCompanyId) {
        return asset(id, propertyCompanyId, operatingCompanyId, null, null);
    }

    private static Asset asset(long id, Long propertyCompanyId, Long operatingCompanyId,
            Long projectId, Long zoneId) {
        Asset a = new Asset();
        a.setId(id);
        a.setAssetNo("A-" + id);
        a.setName("资产" + id);
        a.setProjectId(projectId);
        a.setZoneId(zoneId);
        a.setFloorNo(3);
        a.setPropertyCompanyId(propertyCompanyId);
        a.setOperatingCompanyId(operatingCompanyId);
        a.setLifecycleStatus("in_book");
        // 与 V54 的列默认值一致：存量资产都是「集团内」
        a.setOwnershipStatus("in_group");
        return a;
    }

    private static Project project(Long id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        return p;
    }

    private static ProjectZone zone(Long id, String name) {
        ProjectZone z = new ProjectZone();
        z.setId(id);
        z.setName(name);
        return z;
    }

    private static OwnershipTransferInput input(String scope, Long... assetIds) {
        OwnershipTransferInput in = new OwnershipTransferInput();
        in.setDirection("internal");
        in.setTransferScope(scope);
        in.setFromCompanyId(FROM);
        in.setToCompanyId(TO);
        in.setTransferMode("allocate");
        in.setApplicantName("张三");
        in.setAssetIds(List.of(assetIds));
        return in;
    }

    private static OwnershipTransfer draft(long id, String scope, String direction) {
        OwnershipTransfer t = new OwnershipTransfer();
        t.setId(id);
        t.setStatus("draft");
        t.setDirection(direction);
        t.setTransferScope(scope);
        t.setFromCompanyId(FROM);
        t.setToCompanyId(TO);
        t.setTransferMode("allocate");
        t.setApplicantName("张三");
        return t;
    }

    private static OwnershipTransferAsset detailRow(long transferId, long assetId) {
        OwnershipTransferAsset row = new OwnershipTransferAsset();
        row.setId(assetId);
        row.setTransferId(transferId);
        row.setAssetId(assetId);
        return row;
    }

    /** 已经躺了一条草稿，明细挂着给定的资产。 */
    private void givenDraft(long transferId, String scope, String direction, long... assetIds) {
        when(transferMapper.selectById(transferId)).thenReturn(draft(transferId, scope, direction));
        when(transferAssetMapper.selectList(any())).thenReturn(
                java.util.Arrays.stream(assetIds).mapToObj(id -> detailRow(transferId, id)).toList());
    }

    private void givenDraftAssets(long transferId, long... assetIds) {
        givenDraft(transferId, "property", "internal", assetIds);
    }

    // ------------------------------------------------------------------
    // 草稿：校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("权属类型=产权：只校验 property_company_id 归属")
    void propertyScopeChecksPropertyCompany() {
        when(assetMapper.selectById(anyLong())).thenReturn(asset(11L, FROM, 999L));

        newService().create(input("property", 11L));

        verify(transferMapper).insert(any(OwnershipTransfer.class));
    }

    @Test
    @DisplayName("权属类型=产权 但资产产权公司不是所选原公司 -> 400，且不落库")
    void propertyScopeRejectsWrongCompany() {
        when(assetMapper.selectById(anyLong())).thenReturn(asset(11L, 999L, FROM));

        assertThatThrownBy(() -> newService().create(input("property", 11L)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不属于所选原公司");

        verify(transferMapper, never()).insert(any(OwnershipTransfer.class));
    }

    @Test
    @DisplayName("权属类型=经营权：校验 operating_company_id 归属（产权公司不同也放行）")
    void operatingScopeChecksOperatingCompany() {
        when(assetMapper.selectById(anyLong())).thenReturn(asset(11L, 999L, FROM));

        newService().create(input("operating", 11L));

        verify(transferMapper).insert(any(OwnershipTransfer.class));
    }

    @Test
    @DisplayName("权属类型=经营权且产权：两个字段都必须属于原公司")
    void bothScopeChecksBothCompanies() {
        when(assetMapper.selectById(anyLong())).thenReturn(asset(11L, FROM, 999L));

        assertThatThrownBy(() -> newService().create(input("both", 11L)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不属于所选原公司");

        verify(transferMapper, never()).insert(any(OwnershipTransfer.class));
    }

    @Test
    @DisplayName("在押资产一律拒绝流转（与处置/调拨同口径）")
    void rejectsMortgagedAsset() {
        when(assetMapper.selectById(anyLong())).thenReturn(asset(11L, FROM, FROM));
        // void 方法：只能用 doThrow，用 when(...).thenThrow 会因为方法返回 void 编译不过
        doThrow(new AppException(ErrorCode.BUSINESS_ERROR, "资产处于在押状态，须先解押"))
                .when(certificateService).assertNotMortgaged(anyLong());

        assertThatThrownBy(() -> newService().create(input("property", 11L)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("在押");

        verify(transferMapper, never()).insert(any(OwnershipTransfer.class));
    }

    @Test
    @DisplayName("资产列表去重：同一资产传两次只落一条明细")
    void deduplicatesAssetIds() {
        when(assetMapper.selectById(anyLong())).thenReturn(asset(11L, FROM, FROM));

        newService().create(input("property", 11L, 11L));

        verify(transferAssetMapper, times(1)).insert(any(OwnershipTransferAsset.class));
    }

    @Test
    @DisplayName("资产列表为空 -> 400")
    void rejectsEmptyAssetList() {
        assertThatThrownBy(() -> newService().create(input("property")))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("至少");

        verify(transferMapper, never()).insert(any(OwnershipTransfer.class));
    }

    @Test
    @DisplayName("新老公司相同 -> 400（无变化的流转必须挡在落库前）")
    void rejectsSameCompany() {
        OwnershipTransferInput in = input("property", 11L);
        in.setToCompanyId(FROM);

        assertThatThrownBy(() -> newService().create(in))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不能相同");
    }

    @Test
    @DisplayName("金额超过 2 位小数 -> 400（不依赖 PG 静默四舍五入）")
    void rejectsTooManyDecimals() {
        OwnershipTransferInput in = input("property", 11L);
        in.setAmountWan(new BigDecimal("12.345"));

        assertThatThrownBy(() -> newService().create(in))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("万元");
    }

    @Test
    @DisplayName("申请人：内员时姓名以 sys_user.name 为准，不信前端传的字符串")
    void applicantNameComesFromUserTable() {
        com.ams.modules.org.entity.User user = new com.ams.modules.org.entity.User();
        user.setId(31L);
        user.setName("李四");
        when(userMapper.selectById(31L)).thenReturn(user);
        when(assetMapper.selectById(anyLong())).thenReturn(asset(11L, FROM, FROM));
        OwnershipTransferInput in = input("property", 11L);
        in.setApplicantUserId(31L);
        in.setApplicantName("前端乱填的名字");

        newService().create(in);

        ArgumentCaptor<OwnershipTransfer> captor = ArgumentCaptor.forClass(OwnershipTransfer.class);
        verify(transferMapper).insert(captor.capture());
        assertThat(captor.getValue().getApplicantName()).isEqualTo("李四");
        assertThat(captor.getValue().getApplicantUserId()).isEqualTo(31L);
    }

    @Test
    @DisplayName("申请人：内员 id 不存在 -> 400，不落一张申请人指向虚空单")
    void rejectsUnknownApplicant() {
        OwnershipTransferInput in = input("property", 11L);
        in.setApplicantUserId(31L);

        assertThatThrownBy(() -> newService().create(in))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("申请人不存在");
    }

    @Test
    @DisplayName("非草稿不可改：completed 的单子拒绝 update")
    void rejectsUpdateOnCompleted() {
        OwnershipTransfer existing = draft(5L, "property", "internal");
        existing.setStatus("completed");
        when(transferMapper.selectById(5L)).thenReturn(existing);

        assertThatThrownBy(() -> newService().update(5L, input("property", 11L)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("草稿");
    }

    @Test
    @DisplayName("非草稿不可删：completed 的单子拒绝 delete")
    void rejectsDeleteOnCompleted() {
        OwnershipTransfer existing = draft(5L, "property", "internal");
        existing.setStatus("completed");
        when(transferMapper.selectById(5L)).thenReturn(existing);

        assertThatThrownBy(() -> newService().delete(5L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("草稿");
    }

    @Test
    @DisplayName("已软删的单子查不到 -> 404（列表与详情都不能冒出幽灵单）")
    void hiddenWhenSoftDeleted() {
        when(transferMapper.selectById(5L)).thenReturn(null);

        assertThatThrownBy(() -> newService().get(5L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("软删是打时间戳，不是物理删除（监管类数据禁止物理删除）")
    void deleteIsSoft() {
        OwnershipTransfer existing = draft(5L, "property", "internal");
        when(transferMapper.selectById(5L)).thenReturn(existing);

        newService().delete(5L);

        ArgumentCaptor<OwnershipTransfer> captor = ArgumentCaptor.forClass(OwnershipTransfer.class);
        verify(transferMapper).updateById(captor.capture());
        assertThat(captor.getValue().getDeletedAt()).isNotNull();
        verify(transferMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("方向与公司树不一致 -> 400")
    void rejectsDirectionMismatch() {
        when(assetMapper.selectById(anyLong())).thenReturn(asset(11L, FROM, FROM));
        OwnershipTransferInput in = input("property", 11L);
        in.setDirection("external");

        assertThatThrownBy(() -> newService().create(in))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("属于本集团");
    }

    @Test
    @DisplayName("外部流转的目标公司在集团外：方向校验放行（反向用例，防止把公司树判定写死成「一定内部」）")
    void acceptsExternalTargetOutsideGroup() {
        when(assetMapper.selectById(anyLong())).thenReturn(asset(11L, FROM, FROM));
        OwnershipTransferInput in = input("property", 11L);
        in.setDirection("external");
        in.setToCompanyId(OUTSIDE);

        newService().create(in);

        verify(transferMapper).insert(any(OwnershipTransfer.class));
    }

    @Test
    @DisplayName("列表按状态筛选：状态透传给查询条件")
    void pagePassesStatusFilter() {
        when(transferMapper.selectPage(any(), any())).thenReturn(new Page<OwnershipTransfer>());

        newService().page(1, 10, "draft", null, null, null);

        verify(transferMapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("详情：资产行的项目 / 分区名一次批量查全，不逐行查（展开 20 个资产不会打 20 个请求）")
    void detailFillsAssetDisplayNamesInOneBatch() {
        givenDraft(NEW_ID, "property", "internal", 11L, 12L);
        when(assetMapper.selectBatchIds(any())).thenReturn(List.of(
                asset(11L, FROM, 999L, 31L, 21L), asset(12L, FROM, 999L, 31L, 21L)));
        when(projectMapper.selectBatchIds(any()))
                .thenReturn(List.of(project(31L, "城投大厦")));
        when(projectZoneMapper.selectBatchIds(any()))
                .thenReturn(List.of(zone(21L, "A 分区")));

        OwnershipTransferView view = newService().get(NEW_ID);

        assertThat(view.getAssets()).hasSize(2);
        assertThat(view.getAssets().get(0).getProjectName()).isEqualTo("城投大厦");
        assertThat(view.getAssets().get(0).getZoneName()).isEqualTo("A 分区");
        assertThat(view.getAssets().get(0).getFloorNo()).isEqualTo(3);
        verify(projectMapper, times(1)).selectBatchIds(any());
        verify(projectZoneMapper, times(1)).selectBatchIds(any());
    }

    @Test
    @DisplayName("资产下拉：按权属类型选过滤字段，并把项目 / 分区名回填（非表字段查不出来）")
    void assetOptionsFiltersByScopeFieldAndFillsNames() {
        Asset matched = asset(11L, FROM, 999L, 31L, 21L);
        Page<Asset> page = new Page<>();
        page.setRecords(List.of(matched));
        page.setTotal(1);
        when(assetMapper.selectPage(any(), any())).thenReturn(page);
        when(projectMapper.selectBatchIds(any()))
                .thenReturn(List.of(project(31L, "城投大厦")));
        when(projectZoneMapper.selectBatchIds(any()))
                .thenReturn(List.of(zone(21L, "A 分区")));

        PageResult<TransferAssetOption> options =
                newService().assetOptions(FROM, "property", null, 1, 50);

        assertThat(options.getList()).hasSize(1);
        assertThat(options.getList().get(0).getProjectName()).isEqualTo("城投大厦");
        assertThat(options.getList().get(0).getZoneName()).isEqualTo("A 分区");
        assertThat(options.getList().get(0).getFloorNo()).isEqualTo(3);
    }

    @Test
    @DisplayName("资产下拉：没选原公司就直接拒绝，不去查全表")
    void assetOptionsRequiresCompany() {
        assertThatThrownBy(() -> newService().assetOptions(null, "property", null, 1, 50))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("请先选择原产权公司");

        verify(assetMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("编辑草稿保留已写下的原值快照，不被当前值覆盖")
    void updateKeepsWrittenSnapshot() {
        OwnershipTransfer existing = draft(5L, "property", "internal");
        when(transferMapper.selectById(5L)).thenReturn(existing);
        when(assetMapper.selectById(anyLong())).thenReturn(asset(11L, FROM, 999L));
        OwnershipTransferAsset old = detailRow(5L, 11L);
        old.setFromPropertyCompanyId(888L);
        old.setFromOperatingCompanyId(777L);
        when(transferAssetMapper.selectList(any())).thenReturn(List.of(old));

        newService().update(5L, input("property", 11L));

        ArgumentCaptor<OwnershipTransferAsset> captor = ArgumentCaptor.forClass(OwnershipTransferAsset.class);
        verify(transferAssetMapper).insert(captor.capture());
        assertThat(captor.getValue().getFromPropertyCompanyId()).isEqualTo(888L);
        assertThat(captor.getValue().getFromOperatingCompanyId()).isEqualTo(777L);
        verify(transferMapper).updateById(existing);
    }

    // ------------------------------------------------------------------
    // 生效
    // ------------------------------------------------------------------

    @Test
    @DisplayName("生效（产权）：只改 property_company_id，经营公司与权属状态都不动")
    void effectWritesPropertyCompanyOnly() {
        givenDraftAssets(NEW_ID, 11L);
        Asset asset = asset(11L, FROM, 999L);
        when(assetMapper.selectById(11L)).thenReturn(asset);
        when(assetMapper.updateById(any(Asset.class))).thenReturn(1);

        newService().effect(NEW_ID);

        assertThat(asset.getPropertyCompanyId()).isEqualTo(TO);
        assertThat(asset.getOperatingCompanyId()).as("产权口径不应动经营公司").isEqualTo(999L);
        assertThat(asset.getOwnershipStatus()).as("内部流转不打「已对外转出」").isEqualTo("in_group");
        assertThat(asset.getLeaseControlStatus()).as("租控状态一律不动").isNull();

        ArgumentCaptor<OwnershipTransferAsset> snapshot =
                ArgumentCaptor.forClass(OwnershipTransferAsset.class);
        verify(transferAssetMapper).updateById(snapshot.capture());
        assertThat(snapshot.getValue().getFromPropertyCompanyId())
                .as("快照必须记「生效那一刻的旧值」，不是改完之后的新值")
                .isEqualTo(FROM);
    }

    @Test
    @DisplayName("生效（经营权且产权）：两个公司都改成新公司")
    void effectWritesBothCompanies() {
        givenDraft(NEW_ID, "both", "internal", 11L);
        Asset asset = asset(11L, FROM, FROM);
        when(assetMapper.selectById(11L)).thenReturn(asset);
        when(assetMapper.updateById(any(Asset.class))).thenReturn(1);

        newService().effect(NEW_ID);

        assertThat(asset.getPropertyCompanyId()).isEqualTo(TO);
        assertThat(asset.getOperatingCompanyId()).isEqualTo(TO);
    }

    @Test
    @DisplayName("生效（经营权）：只改 operating_company_id，产权公司不动")
    void effectWritesOperatingCompanyOnly() {
        givenDraft(NEW_ID, "operating", "internal", 11L);
        Asset asset = asset(11L, 999L, FROM);
        when(assetMapper.selectById(11L)).thenReturn(asset);
        when(assetMapper.updateById(any(Asset.class))).thenReturn(1);

        newService().effect(NEW_ID);

        assertThat(asset.getOperatingCompanyId()).isEqualTo(TO);
        assertThat(asset.getPropertyCompanyId()).as("经营权口径不应动产权公司").isEqualTo(999L);
    }

    @Test
    @DisplayName("生效（外部）：打 transferred_out + lifecycle_status=exited，且不动租控")
    void effectMarksExternalTransfer() {
        // 外部流转：目标公司在集团外（9 自成一根），方向声明与公司树一致
        OwnershipTransfer externalDraft = draft(NEW_ID, "property", "external");
        externalDraft.setToCompanyId(OUTSIDE);
        when(transferMapper.selectById(NEW_ID)).thenReturn(externalDraft);
        when(transferAssetMapper.selectList(any())).thenReturn(List.of(detailRow(NEW_ID, 11L)));
        Asset asset = asset(11L, FROM, 999L);
        when(assetMapper.selectById(11L)).thenReturn(asset);
        when(assetMapper.updateById(any(Asset.class))).thenReturn(1);

        newService().effect(NEW_ID);

        assertThat(asset.getPropertyCompanyId()).isEqualTo(OUTSIDE);
        assertThat(asset.getOwnershipStatus()).isEqualTo("transferred_out");
        assertThat(asset.getLifecycleStatus()).isEqualTo("exited");
        assertThat(asset.getLeaseControlStatus())
                .as("ADR-0019：已退出属于生命周期，不是占用状态；租控状态机也只允许 DISPOSING→EXITED")
                .isNull();
    }

    @Test
    @DisplayName("生效幂等：completed 的单子直接返回，不再改一次资产")
    void effectIsIdempotent() {
        OwnershipTransfer done = draft(NEW_ID, "property", "internal");
        done.setStatus("completed");
        when(transferMapper.selectById(NEW_ID)).thenReturn(done);

        newService().effect(NEW_ID);

        verify(assetMapper, never()).updateById(any(Asset.class));
    }

    @Test
    @DisplayName("生效重跑校验：草稿期间资产产权公司被改过 -> 400 且不写任何资产")
    void effectRevalidatesBeforeWriting() {
        givenDraftAssets(NEW_ID, 11L);
        // 草稿提交后又有人改了这家公司 —— 生效必须重新校验
        when(assetMapper.selectById(11L)).thenReturn(asset(11L, 888L, 999L));

        assertThatThrownBy(() -> newService().effect(NEW_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不属于所选原公司");

        verify(assetMapper, never()).updateById(any(Asset.class));
    }

    @Test
    @DisplayName("生效拦住跨草稿抢跑：第二张单的资产已被第一张改走，第二张失败")
    void effectBlocksCrossDraftRace() {
        givenDraftAssets(NEW_ID, 11L);
        // 第一张单已生效：该资产产权公司现在是 TO，不再是本单的原公司 FROM
        when(assetMapper.selectById(11L)).thenReturn(asset(11L, TO, 999L));

        assertThatThrownBy(() -> newService().effect(NEW_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不属于所选原公司");

        verify(assetMapper, never()).updateById(any(Asset.class));
    }

    @Test
    @DisplayName("乐观锁冲突（updateById 返回 0）-> CONFLICT，整单回滚")
    void effectFailsOnOptimisticLock() {
        givenDraftAssets(NEW_ID, 11L);
        when(assetMapper.selectById(11L)).thenReturn(asset(11L, FROM, 999L));
        when(assetMapper.updateById(any(Asset.class))).thenReturn(0);

        assertThatThrownBy(() -> newService().effect(NEW_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("并发");
    }

    @Test
    @DisplayName("生效发布逐资产事件，且交接清单写进主单")
    void effectPublishesEventsAndStoresHandover() {
        givenDraftAssets(NEW_ID, 11L, 12L);
        when(assetMapper.selectById(11L)).thenReturn(asset(11L, FROM, 999L));
        when(assetMapper.selectById(12L)).thenReturn(asset(12L, FROM, 999L));
        when(assetMapper.updateById(any(Asset.class))).thenReturn(1);
        when(handoverBuilder.build(any(), any(), any(), any())).thenReturn(Map.of("assetId", 11L));

        newService().effect(NEW_ID);

        verify(eventPublisher, times(2)).publishAfterCommit(any(OwnershipTransferredEvent.class));
        verify(handoverBuilder, times(2)).build(any(), any(), any(), any());

        ArgumentCaptor<OwnershipTransfer> captor = ArgumentCaptor.forClass(OwnershipTransfer.class);
        verify(transferMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("completed");
        assertThat(captor.getValue().getEffectedAt()).isNotNull();
        assertThat(captor.getValue().getHandoverJson())
                .as("一张单一个交接清单，以 assetId → 快照 的 Map 存 JSON")
                .contains("\"11\"")
                .contains("\"12\"");
    }
}
