package com.ams.modules.mortgage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.common.web.PageResult;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.Mortgage;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.MortgageMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.ams.modules.asset.service.CertificateService;
import com.ams.modules.mortgage.dto.MortgageRecordInput;
import com.ams.modules.mortgage.dto.MortgageRecordView;
import com.ams.modules.mortgage.dto.MortgageTargetOption;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.mapper.CompanyMapper;
import com.ams.modules.org.service.CompanyTreeService;
import com.ams.modules.record.AttachmentOwner;
import com.ams.modules.record.service.RecordSheetService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 抵押记录的校验闭环与生效落库（V56）。
 *
 * <p>关注点分四段：
 * <ol>
 *   <li><b>草稿校验</b>：标的类型白名单、金额 / 利率 / 期限的边界（它们直接进
 *       {@code NUMERIC(8,4)} / {@code NUMERIC(18,2)} 列，越界会被 PG 静默改写）、
 *       三级标的的归属（分区要经项目推导公司）；</li>
 *   <li><b>字段落位</b>：{@code end_date} 的推导、资产级抵押下 {@code asset_id} 与
 *       {@code target_id} 同值、项目 / 分区级必须清空 {@code asset_id}；</li>
 *   <li><b>生效</b>：状态推进、权证状态刷新、幂等、重跑校验；</li>
 *   <li><b>本模块刻意不做的事</b>：不拦「同一标的多笔在押」（一个资产可以抵给两家银行）。</li>
 * </ol>
 *
 * <p>纯单测里 {@code LambdaQueryWrapper} 需要 {@code TableInfoHelper} 的 lambda 缓存才能把
 * 方法引用解析成列名（平时由 MyBatis-Plus 注册 Mapper 时建立），因此这里手工初始化一次 ——
 * 否则断言查询条件的那两个用例会以「can not find lambda cache」失败。
 */
class MortgageRecordServiceTest {

    private static final long COMPANY = 2L;
    private static final long OTHER_COMPANY = 999L;
    private static final long PROJECT = 301L;
    private static final long PROJECT_OF_OTHER_COMPANY = 302L;
    private static final long ZONE = 401L;
    private static final long ZONE_OF_OTHER_COMPANY = 402L;
    private static final long ASSET = 501L;
    private static final long ASSET_OF_OTHER_COMPANY = 502L;
    private static final long EXITED_ASSET = 503L;

    /** mock 的 insert 不会回填自增主键，故用固定值模拟数据库回填。 */
    private static final long NEW_ID = 9L;

    private MortgageMapper mortgageMapper;
    private AssetMapper assetMapper;
    private ProjectMapper projectMapper;
    private ProjectZoneMapper projectZoneMapper;
    private CompanyMapper companyMapper;
    private CompanyTreeService companyTreeService;
    private RecordSheetService recordSheetService;
    private CertificateService certificateService;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Mortgage.class);
        TableInfoHelper.initTableInfo(assistant, Asset.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
        TableInfoHelper.initTableInfo(assistant, ProjectZone.class);
    }

    @BeforeEach
    void setUp() {
        mortgageMapper = mock(MortgageMapper.class);
        assetMapper = mock(AssetMapper.class);
        projectMapper = mock(ProjectMapper.class);
        projectZoneMapper = mock(ProjectZoneMapper.class);
        companyMapper = mock(CompanyMapper.class);
        companyTreeService = mock(CompanyTreeService.class);
        recordSheetService = mock(RecordSheetService.class);
        certificateService = mock(CertificateService.class);

        // 默认夹具：公司有效、三级标的都归属 2 号公司 —— 单条用例只推翻它关心的那一项
        when(companyTreeService.isActiveCompany(COMPANY)).thenReturn(true);
        when(companyMapper.selectBatchIds(any())).thenReturn(List.of(company(COMPANY)));
        when(projectMapper.selectById(PROJECT)).thenReturn(project(PROJECT, COMPANY));
        when(projectMapper.selectById(PROJECT_OF_OTHER_COMPANY))
                .thenReturn(project(PROJECT_OF_OTHER_COMPANY, OTHER_COMPANY));
        when(projectZoneMapper.selectActiveById(ZONE)).thenReturn(zone(ZONE, PROJECT));
        when(projectZoneMapper.selectActiveById(ZONE_OF_OTHER_COMPANY))
                .thenReturn(zone(ZONE_OF_OTHER_COMPANY, PROJECT_OF_OTHER_COMPANY));
        when(assetMapper.selectById(ASSET)).thenReturn(asset(ASSET, COMPANY, "active"));
        when(assetMapper.selectById(ASSET_OF_OTHER_COMPANY))
                .thenReturn(asset(ASSET_OF_OTHER_COMPANY, OTHER_COMPANY, "active"));
        when(assetMapper.selectById(EXITED_ASSET)).thenReturn(asset(EXITED_ASSET, COMPANY, "exited"));

        // mock 的 selectBatchIds 默认返回 null（真实实现返回空集合），不桩住会让回填名称 NPE
        when(assetMapper.selectBatchIds(any())).thenReturn(List.of());
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of());
        when(projectZoneMapper.selectBatchIds(any())).thenReturn(List.of());
        when(mortgageMapper.selectList(any())).thenReturn(List.of());
        when(recordSheetService.toAttachmentRefs(any(), any())).thenReturn(List.of());
        when(mortgageMapper.selectPage(any(), any())).thenReturn(new Page<>());
        when(projectMapper.selectPage(any(), any())).thenReturn(new Page<>());
        when(projectZoneMapper.selectPage(any(), any())).thenReturn(new Page<>());
        when(assetMapper.selectPage(any(), any())).thenReturn(new Page<>());

        // insert 之后 create/update/effect 会立刻 get(id) 回读：让 selectById 能返回同一条，
        // 否则 mock 下主键为 null、回读必然 404 —— 那是测试夹具的缺口，不是被测行为
        when(mortgageMapper.insert(any(Mortgage.class))).thenAnswer(inv -> {
            Mortgage row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(NEW_ID);
            }
            if (row.getStatus() == null) {
                row.setStatus(Mortgage.STATUS_DRAFT);
            }
            when(mortgageMapper.selectById(row.getId())).thenReturn(row);
            return 1;
        });
    }

    private MortgageRecordService newService() {
        return new MortgageRecordService(
                mortgageMapper,
                assetMapper,
                projectMapper,
                projectZoneMapper,
                companyMapper,
                companyTreeService,
                recordSheetService,
                certificateService);
    }

    // ------------------------------------------------------------------
    // 草稿：必填与枚举
    // ------------------------------------------------------------------

    @Test
    @DisplayName("所属公司已停用 -> 400，且不插入任何行")
    void rejectsInactiveCompany() {
        when(companyTreeService.isActiveCompany(COMPANY)).thenReturn(false);

        assertThatThrownBy(() -> newService().create(validInput()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("已停用");
        verify(mortgageMapper, never()).insert(any(Mortgage.class));
    }

    @Test
    @DisplayName("标的类型不在白名单 -> 400（拼错一个字母等于把记录挂到不存在的层级上）")
    void rejectsUnknownTargetType() {
        MortgageRecordInput input = validInput();
        input.setTargetType("building");

        assertThatThrownBy(() -> newService().create(input))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("项目类型");
        verify(mortgageMapper, never()).insert(any(Mortgage.class));
    }

    @Test
    @DisplayName("未选标的 -> 400，不插入")
    void rejectsMissingTarget() {
        MortgageRecordInput input = validInput();
        input.setTargetId(null);

        assertThatThrownBy(() -> newService().create(input))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("抵押标的");
        verify(mortgageMapper, never()).insert(any(Mortgage.class));
    }

    @Test
    @DisplayName("抵押权人缺失或全空白 -> 400（没有权利人的抵押既拦不住人、也无法追索）")
    void rejectsBlankMortgagee() {
        MortgageRecordInput input = validInput();
        input.setMortgagee("   ");

        assertThatThrownBy(() -> newService().create(input))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("抵押公司/人必填");
    }

    // ------------------------------------------------------------------
    // 草稿：数值边界（直接进 NUMERIC 列，越界会被 PG 静默改写）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("金额缺失 / 为负 / 超过 2 位小数都 -> 400")
    void rejectsBadAmount() {
        MortgageRecordInput missing = validInput();
        missing.setAmount(null);
        assertThatThrownBy(() -> newService().create(missing))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("抵押金额必填");

        MortgageRecordInput negative = validInput();
        negative.setAmount(new BigDecimal("-1"));
        assertThatThrownBy(() -> newService().create(negative))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不能为负");

        MortgageRecordInput tooPrecise = validInput();
        tooPrecise.setAmount(new BigDecimal("100.005"));
        assertThatThrownBy(() -> newService().create(tooPrecise))
                .as("列是 NUMERIC(18,2)，不卡小数位时 PG 会静默四舍五入成 100.01")
                .isInstanceOf(AppException.class)
                .hasMessageContaining("2 位小数");
    }

    @Test
    @DisplayName("利率越界或超过 4 位小数 -> 400；不填则放行")
    void rejectsBadInterestRate() {
        MortgageRecordInput tooHigh = validInput();
        tooHigh.setInterestRate(new BigDecimal("100.1"));
        assertThatThrownBy(() -> newService().create(tooHigh))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("0 ~ 100");

        MortgageRecordInput tooPrecise = validInput();
        tooPrecise.setInterestRate(new BigDecimal("4.35001"));
        assertThatThrownBy(() -> newService().create(tooPrecise))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("4 位小数");

        MortgageRecordInput absent = validInput();
        absent.setInterestRate(null);
        assertThat(newService().create(absent).getInterestRate()).isNull();
    }

    @Test
    @DisplayName("起始时间与期限必填，期限 1 ~ 1200 个月")
    void rejectsBadTerm() {
        MortgageRecordInput noStart = validInput();
        noStart.setStartDate(null);
        assertThatThrownBy(() -> newService().create(noStart))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("起始时间必填");

        MortgageRecordInput noTerm = validInput();
        noTerm.setTermMonths(null);
        assertThatThrownBy(() -> newService().create(noTerm))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("抵押期限（月）必填");

        MortgageRecordInput zero = validInput();
        zero.setTermMonths(0);
        assertThatThrownBy(() -> newService().create(zero))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("至少 1 个月");

        MortgageRecordInput absurd = validInput();
        absurd.setTermMonths(1201);
        assertThatThrownBy(() -> newService().create(absurd))
                .as("不设上限时 plusMonths 会对 absurd 的月数抛 DateTimeException")
                .isInstanceOf(AppException.class)
                .hasMessageContaining("最多 1200 个月");
    }

    @Test
    @DisplayName("合同编号重复 -> 409，且不插入（唯一索引冲突到用户眼里是 500 + 英文约束名）")
    void rejectsDuplicateContractNo() {
        when(mortgageMapper.selectList(any()))
                .thenReturn(List.of(existingMortgage(Mortgage.STATUS_DRAFT, "DY-0001")));

        MortgageRecordInput input = validInput();
        input.setContractNo("DY-0001");

        assertThatThrownBy(() -> newService().create(input))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("已被占用");
        verify(mortgageMapper, never()).insert(any(Mortgage.class));
    }

    @Test
    @DisplayName("空白合同编号视作未填写 -> 不查唯一性、落库为 NULL")
    void blankContractNoBecomesNull() {
        MortgageRecordInput input = validInput();
        input.setContractNo("   ");

        MortgageRecordView view = newService().create(input);
        assertThat(view.getContractNo()).isNull();
        verify(mortgageMapper, never()).selectList(any());
    }

    // ------------------------------------------------------------------
    // 草稿：三级标的归属
    // ------------------------------------------------------------------

    @Test
    @DisplayName("项目不属于所选公司 -> 400")
    void rejectsProjectOfAnotherCompany() {
        MortgageRecordInput input = validInput();
        input.setTargetType(Mortgage.TARGET_PROJECT);
        input.setTargetId(PROJECT_OF_OTHER_COMPANY);

        assertThatThrownBy(() -> newService().create(input))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("项目不属于所选公司");
    }

    @Test
    @DisplayName("分区经项目推导公司：分区所属项目不属于所选公司 -> 400")
    void rejectsZoneOfAnotherCompany() {
        MortgageRecordInput input = validInput();
        input.setTargetType(Mortgage.TARGET_ZONE);
        input.setTargetId(ZONE_OF_OTHER_COMPANY);

        assertThatThrownBy(() -> newService().create(input))
                .as("分区表没有 company_id，不推导就无法拦住跨公司抵押")
                .isInstanceOf(AppException.class)
                .hasMessageContaining("分区所属项目不属于所选公司");
    }

    @Test
    @DisplayName("分区不存在或已软删 -> 404")
    void rejectsMissingZone() {
        when(projectZoneMapper.selectActiveById(ZONE)).thenReturn(null);

        MortgageRecordInput input = validInput();
        input.setTargetType(Mortgage.TARGET_ZONE);
        input.setTargetId(ZONE);

        assertThatThrownBy(() -> newService().create(input))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("分区不存在");
    }

    @Test
    @DisplayName("资产不属于所选公司 -> 400；资产不存在 -> 404；资产已退出 -> 400")
    void rejectsBadAsset() {
        MortgageRecordInput otherCompany = validInput();
        otherCompany.setTargetId(ASSET_OF_OTHER_COMPANY);
        assertThatThrownBy(() -> newService().create(otherCompany))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("资产不属于所选公司");

        MortgageRecordInput missing = validInput();
        missing.setTargetId(9999L);
        assertThatThrownBy(() -> newService().create(missing))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("资产不存在");

        MortgageRecordInput exited = validInput();
        exited.setTargetId(EXITED_ASSET);
        assertThatThrownBy(() -> newService().create(exited))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("已退出");
    }

    // ------------------------------------------------------------------
    // 字段落位
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新建即草稿：status=draft，且不刷新权证状态")
    void createStoresDraftWithoutTouchingCertificates() {
        MortgageRecordView view = newService().create(validInput());

        assertThat(view.getStatus())
                .as("新建直接 active 会让「先起草再补材料」的中间态消失，草稿一存就冻结资产")
                .isEqualTo(Mortgage.STATUS_DRAFT);
        verify(certificateService, never()).refreshCertMortgageStatus(any(), any());
    }

    @Test
    @DisplayName("到期日由 起始时间 + 期限（月）推导")
    void derivesEndDate() {
        MortgageRecordInput input = validInput();
        input.setStartDate(LocalDate.of(2026, 1, 31));
        input.setTermMonths(24);

        assertThat(newService().create(input).getEndDate())
                .as("plusMonths 对月末的处理由 java.time 决定，这里只钉「推导而不是另填」")
                .isEqualTo(LocalDate.of(2026, 1, 31).plusMonths(24));
    }

    @Test
    @DisplayName("资产级抵押：asset_id 与 target_id 同值；项目 / 分区级：asset_id 必须清空")
    void keepsAssetIdOnlyForAssetTargets() {
        // 项目级
        MortgageRecordInput projectInput = validInput();
        projectInput.setTargetType(Mortgage.TARGET_PROJECT);
        projectInput.setTargetId(PROJECT);
        Mortgage projectRow = captureLastInsert(projectInput);
        assertThat(projectRow.getTargetType()).isEqualTo(Mortgage.TARGET_PROJECT);
        assertThat(projectRow.getAssetId())
                .as("项目级抵押留着 asset_id 会让「按 asset_id 反查」的地方认为它属于某个资产")
                .isNull();

        // 分区级
        MortgageRecordInput zoneInput = validInput();
        zoneInput.setTargetType(Mortgage.TARGET_ZONE);
        zoneInput.setTargetId(ZONE);
        assertThat(captureLastInsert(zoneInput).getAssetId()).isNull();

        // 资产级
        Mortgage assetRow = captureLastInsert(validInput());
        assertThat(assetRow.getAssetId())
                .as("资产级抵押两个标的列必须同值：只写一个会让两条反查路径给出不同结果")
                .isEqualTo(ASSET);
        assertThat(assetRow.getTargetId()).isEqualTo(ASSET);
    }

    /**
     * 跑一次 {@code create} 并回吐最后一条被 insert 的记录。
     *
     * <p>{@code ArgumentCaptor} 在 {@code verify} 时会把**所有**匹配的调用收进来，
     * 因此 {@code getValue()} 拿到的正是本次（最后一次）插入的那条。
     */
    private Mortgage captureLastInsert(MortgageRecordInput input) {
        newService().create(input);
        ArgumentCaptor<Mortgage> captor = ArgumentCaptor.forClass(Mortgage.class);
        verify(mortgageMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------
    // 生效
    // ------------------------------------------------------------------

    @Test
    @DisplayName("生效：draft -> active，并按标的刷新权证状态")
    void effectActivatesAndRefreshesCertificates() {
        Mortgage row = existingMortgage(Mortgage.STATUS_DRAFT, null);
        row.setTargetType(Mortgage.TARGET_PROJECT);
        row.setTargetId(PROJECT);
        when(mortgageMapper.selectById(NEW_ID)).thenReturn(row);

        MortgageRecordView view = newService().effect(NEW_ID);

        assertThat(view.getStatus()).isEqualTo(Mortgage.STATUS_ACTIVE);
        verify(certificateService).refreshCertMortgageStatus(Mortgage.TARGET_PROJECT, PROJECT);
    }

    @Test
    @DisplayName("生效幂等：已 active 直接返回，不重复刷新权证状态")
    void effectIsIdempotent() {
        Mortgage row = existingMortgage(Mortgage.STATUS_ACTIVE, null);
        when(mortgageMapper.selectById(NEW_ID)).thenReturn(row);

        assertThat(newService().effect(NEW_ID).getStatus()).isEqualTo(Mortgage.STATUS_ACTIVE);
        verify(certificateService, never()).refreshCertMortgageStatus(any(), any());
        verify(mortgageMapper, never()).updateById(any(Mortgage.class));
    }

    @Test
    @DisplayName("已解押的记录不能再生效 -> 409")
    void effectRejectsReleased() {
        when(mortgageMapper.selectById(NEW_ID))
                .thenReturn(existingMortgage(Mortgage.STATUS_RELEASED, null));

        assertThatThrownBy(() -> newService().effect(NEW_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("只有草稿可以生效");
    }

    @Test
    @DisplayName("生效时重跑校验：公司中途被停用 -> 400，状态不推进")
    void effectRevalidates() {
        when(mortgageMapper.selectById(NEW_ID))
                .thenReturn(existingMortgage(Mortgage.STATUS_DRAFT, null));
        when(companyTreeService.isActiveCompany(COMPANY)).thenReturn(false);

        assertThatThrownBy(() -> newService().effect(NEW_ID))
                .as("草稿可能躺了几个月，公司 / 标的的归属都可能变过，生效必须以当时的真实情况为准")
                .isInstanceOf(AppException.class)
                .hasMessageContaining("已停用");
        verify(mortgageMapper, never()).updateById(any(Mortgage.class));
    }

    // ------------------------------------------------------------------
    // 改 / 删
    // ------------------------------------------------------------------

    @Test
    @DisplayName("只有草稿能改、能删；在押记录 -> 409")
    void onlyDraftsCanBeEditedOrDeleted() {
        when(mortgageMapper.selectById(NEW_ID))
                .thenReturn(existingMortgage(Mortgage.STATUS_ACTIVE, null));

        assertThatThrownBy(() -> newService().update(NEW_ID, validInput()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("只有草稿");
        assertThatThrownBy(() -> newService().delete(NEW_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("只有草稿");
    }

    @Test
    @DisplayName("删除是软删：写 deleted_at，不物理删行")
    void deleteIsSoft() {
        Mortgage row = existingMortgage(Mortgage.STATUS_DRAFT, null);
        when(mortgageMapper.selectById(NEW_ID)).thenReturn(row);

        newService().delete(NEW_ID);

        assertThat(row.getDeletedAt())
                .as("物理删掉行会让附件与审计线索指向不存在的宿主")
                .isNotNull();
        verify(mortgageMapper).updateById(row);
    }

    @Test
    @DisplayName("软删的记录读不到 -> 404")
    void softDeletedRecordsAreInvisible() {
        Mortgage row = existingMortgage(Mortgage.STATUS_DRAFT, null);
        row.setDeletedAt(LocalDateTime.now());
        when(mortgageMapper.selectById(NEW_ID)).thenReturn(row);

        assertThatThrownBy(() -> newService().get(NEW_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不存在");
    }

    // ------------------------------------------------------------------
    // 读：标的选题项
    // ------------------------------------------------------------------

    @Test
    @DisplayName("选题项：标的类型非法或未选公司 -> 400")
    void targetOptionsValidateInput() {
        assertThatThrownBy(() -> newService().targetOptions("building", COMPANY, null, 1, 50))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("标的类型");
        assertThatThrownBy(() -> newService().targetOptions(Mortgage.TARGET_PROJECT, null, null, 1, 50))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("请先选择所属公司");
    }

    @Test
    @DisplayName("项目下拉：按公司过滤，且返回项目自身名")
    void projectOptionsFilterByCompany() {
        Page<Project> page = new Page<>(1, 50);
        page.setRecords(List.of(project(PROJECT, COMPANY)));
        page.setTotal(1);
        when(projectMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<MortgageTargetOption> result =
                newService().targetOptions(Mortgage.TARGET_PROJECT, COMPANY, null, 1, 50);

        assertThat(result.getList()).hasSize(1);
        MortgageTargetOption option = result.getList().get(0);
        assertThat(option.getTargetId()).isEqualTo(PROJECT);
        assertThat(option.getName()).isEqualTo("项目301");
        assertThat(option.getProjectName())
                .as("项目标的的上级项目名就是它自己：展示层对三种类型用同一套拼接规则")
                .isEqualTo("项目301");
    }

    @Test
    @DisplayName("分区下拉：公司没有项目时直接返回空，不去查分区表")
    void zoneOptionsShortCircuitWhenCompanyHasNoProject() {
        when(projectMapper.selectList(any())).thenReturn(List.of());

        PageResult<MortgageTargetOption> result =
                newService().targetOptions(Mortgage.TARGET_ZONE, COMPANY, null, 1, 50);

        assertThat(result.getList()).isEmpty();
        assertThat(result.getTotal()).isZero();
        verify(projectZoneMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("资产下拉：排除已退出资产、按公司过滤")
    void assetOptionsExcludeExitedAssets() {
        Page<Asset> page = new Page<>(1, 50);
        page.setRecords(List.of(asset(ASSET, COMPANY, "active")));
        page.setTotal(1);
        when(assetMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<MortgageTargetOption> result =
                newService().targetOptions(Mortgage.TARGET_ASSET, COMPANY, null, 1, 50);

        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getTargetId()).isEqualTo(ASSET);

        // 条件必须同时含「所属公司」与「非已退出」
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Asset>>
                captor = ArgumentCaptor.forClass(
                        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(assetMapper).selectPage(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).contains("asset_company_id", "lifecycle_status");
    }

    // ------------------------------------------------------------------
    // 读：列表 / 详情
    // ------------------------------------------------------------------

    @Test
    @DisplayName("列表页不查附件（每行一次查询就是 N+1），详情页才查")
    void attachmentsOnlyOnDetail() {
        Mortgage row = existingMortgage(Mortgage.STATUS_DRAFT, null);
        Page<Mortgage> page = new Page<>(1, 10);
        page.setRecords(List.of(row));
        page.setTotal(1);
        when(mortgageMapper.selectPage(any(), any())).thenReturn(page);
        when(mortgageMapper.selectById(NEW_ID)).thenReturn(row);
        when(recordSheetService.toAttachmentRefs(AttachmentOwner.MORTGAGE, NEW_ID))
                .thenReturn(List.of());

        MortgageRecordService service = newService();
        assertThat(service.page(1, 10, null, null, null, null).getList().get(0).getAttachments())
                .isNull();
        assertThat(service.get(NEW_ID).getAttachments()).isEmpty();
        verify(recordSheetService).toAttachmentRefs(AttachmentOwner.MORTGAGE, NEW_ID);
    }

    @Test
    @DisplayName("不同标的类型的同名 id 不能被混为一谈（两个序列都从 1 开始）")
    void targetNamesAreKeyedByTypeNotJustId() {
        // 一条项目级、一条分区级，target_id 都是 1
        Mortgage projectRow = existingMortgage(Mortgage.STATUS_DRAFT, null);
        projectRow.setId(1L);
        projectRow.setTargetType(Mortgage.TARGET_PROJECT);
        projectRow.setTargetId(1L);
        Mortgage zoneRow = existingMortgage(Mortgage.STATUS_DRAFT, null);
        zoneRow.setId(2L);
        zoneRow.setTargetType(Mortgage.TARGET_ZONE);
        zoneRow.setTargetId(1L);

        Page<Mortgage> page = new Page<>(1, 10);
        page.setRecords(List.of(projectRow, zoneRow));
        page.setTotal(2);
        when(mortgageMapper.selectPage(any(), any())).thenReturn(page);
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project(1L, COMPANY)));
        when(projectZoneMapper.selectBatchIds(any())).thenReturn(List.of(zone(1L, 1L)));

        List<MortgageRecordView> rows = newService().page(1, 10, null, null, null, null).getList();

        assertThat(rows.get(0).getTargetName())
                .as("只按 id 做键时，分区 #1 会显示成项目 #1 的名字")
                .isEqualTo("项目1");
        assertThat(rows.get(1).getTargetName()).isEqualTo("分区1");
    }

    @Test
    @DisplayName("列表页按标的类型筛选时把条件交给 SQL，而不是全量拉回再过滤")
    void pagePassesFiltersToSql() {
        newService().page(1, 10, Mortgage.STATUS_ACTIVE, Mortgage.TARGET_ZONE, COMPANY, "银行");

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Mortgage>>
                captor = ArgumentCaptor.forClass(
                        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(mortgageMapper).selectPage(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).contains("status", "target_type", "company_id", "mortgagee", "bank",
                "contract_no", "deleted_at");
    }

    // ------------------------------------------------------------------
    // 本模块刻意不做的事
    // ------------------------------------------------------------------

    @Test
    @DisplayName("不拦「同一标的多笔在押」：一个资产可以同时抵给两家银行")
    void allowsMultipleActiveMortgagesOnSameTarget() {
        MortgageRecordService service = newService();

        // 同一标的连落两条草稿，都不报错
        assertThat(service.create(validInput()).getTargetId()).isEqualTo(ASSET);
        assertThat(service.create(validInput()).getTargetId()).isEqualTo(ASSET);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private MortgageRecordInput validInput() {
        MortgageRecordInput input = new MortgageRecordInput();
        input.setCompanyId(COMPANY);
        input.setTargetType(Mortgage.TARGET_ASSET);
        input.setTargetId(ASSET);
        input.setMortgagee("江苏银行淮安分行");
        input.setAmount(new BigDecimal("500000.00"));
        input.setInterestRate(new BigDecimal("4.3500"));
        input.setBank("江苏银行淮安分行");
        input.setRepaymentDate(LocalDate.of(2028, 1, 1));
        input.setStartDate(LocalDate.of(2026, 1, 1));
        input.setTermMonths(24);
        input.setContractNo("DY-2026-0001");
        return input;
    }

    private Mortgage existingMortgage(String status, String contractNo) {
        Mortgage row = new Mortgage();
        row.setId(NEW_ID);
        row.setTargetType(Mortgage.TARGET_ASSET);
        row.setTargetId(ASSET);
        row.setAssetId(ASSET);
        row.setCompanyId(COMPANY);
        row.setMortgagee("江苏银行淮安分行");
        row.setAmount(new BigDecimal("500000.00"));
        row.setStartDate(LocalDate.of(2026, 1, 1));
        row.setTermMonths(24);
        row.setEndDate(LocalDate.of(2028, 1, 1));
        row.setContractNo(contractNo);
        row.setStatus(status);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    private Project project(Long id, Long companyId) {
        Project project = new Project();
        project.setId(id);
        project.setCompanyId(companyId);
        project.setName("项目" + id);
        return project;
    }

    private ProjectZone zone(Long id, Long projectId) {
        ProjectZone zone = new ProjectZone();
        zone.setId(id);
        zone.setProjectId(projectId);
        zone.setName("分区" + id);
        return zone;
    }

    private Asset asset(Long id, Long companyId, String lifecycleStatus) {
        Asset asset = new Asset();
        asset.setId(id);
        asset.setAssetCompanyId(companyId);
        asset.setLifecycleStatus(lifecycleStatus);
        asset.setAssetNo("ZC-" + id);
        asset.setName("资产" + id);
        return asset;
    }

    private Company company(Long id) {
        Company company = new Company();
        company.setId(id);
        company.setName("公司" + id);
        return company;
    }
}
