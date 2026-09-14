package com.ams.modules.mortgage.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * 抵押记录（V56）：一张单抵押**一个**标的（项目 / 分区 / 资产）。
 *
 * <p><b>状态机只有 {@code draft → active}</b>。解押不在本类：{@code active → released}
 * 沿用既有的 {@code mortgage_release} 审批流（{@code CertificateService.submitRelease}），
 * 复用同一套审批定义、同一处权证状态同步，而不是在新模块里再实现一遍解押。
 *
 * <p><b>为什么落在既有的 {@code mortgage} 表</b>（而不是新开一张 {@code mortgage_record}）：
 * 见 {@code V56__mortgage_record.sql} 顶部注释 —— 那张表已经被 5 处前置校验、
 * 权证抵押状态、到期预警依赖，「项目被抵押」如果只活在一张新表里，
 * 被抵押项目下的资产照样能被处置和流转。
 *
 * <p><b>为什么不拦「同一标的多笔在押」</b>：一个资产确实可以同时抵押给两家银行
 * （分别对应不同的债权），强行唯一会拒掉真实业务。真正的防错在
 * 「金额 / 合同编号 / 期限」这些会被人复核的字段上，而不是在数量上。
 *
 * <p><b>标的名称在服务端批量回填</b>：列表页每行都要显示「项目 · 分区 · 楼层 · 名称」，
 * 逐行查会变成 N+1。三级标的的 id 空间不共享，所以先按类型分桶、再自下而上补齐
 * （资产 → 其分区 → 其项目），全程 3 次批量查询而不是 per-row。
 */
@Service
public class MortgageRecordService {

    /** 标的类型白名单。放成常量集合而不是逐个 {@code equals}：新增类型时只有一处要改。 */
    private static final Set<String> TARGET_TYPES =
            Set.of(Mortgage.TARGET_PROJECT, Mortgage.TARGET_ZONE, Mortgage.TARGET_ASSET);

    /** 资产下拉每页上限：前端每页 50，服务端夹一道防止 {@code pageSize=99999} 拖垮库。 */
    private static final long MAX_TARGET_OPTIONS_PAGE_SIZE = 200;

    private static final String LIFECYCLE_EXITED = "exited";

    private static final int MAX_MORTGAGEE_LENGTH = 200;
    private static final int MAX_BANK_LENGTH = 200;
    private static final int MAX_CONTRACT_NO_LENGTH = 100;

    /** 期限上限 100 年。不设上限时 {@code plusMonths} 会对 absurd 的月数抛 DateTimeException。 */
    private static final int MAX_TERM_MONTHS = 1200;

    private final MortgageMapper mortgageMapper;
    private final AssetMapper assetMapper;
    private final ProjectMapper projectMapper;
    private final ProjectZoneMapper projectZoneMapper;
    private final CompanyMapper companyMapper;
    private final CompanyTreeService companyTreeService;
    private final RecordSheetService recordSheetService;
    private final CertificateService certificateService;

    public MortgageRecordService(
            MortgageMapper mortgageMapper,
            AssetMapper assetMapper,
            ProjectMapper projectMapper,
            ProjectZoneMapper projectZoneMapper,
            CompanyMapper companyMapper,
            CompanyTreeService companyTreeService,
            RecordSheetService recordSheetService,
            CertificateService certificateService) {
        this.mortgageMapper = mortgageMapper;
        this.assetMapper = assetMapper;
        this.projectMapper = projectMapper;
        this.projectZoneMapper = projectZoneMapper;
        this.companyMapper = companyMapper;
        this.companyTreeService = companyTreeService;
        this.recordSheetService = recordSheetService;
        this.certificateService = certificateService;
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    public PageResult<MortgageRecordView> page(long page, long pageSize, String status,
            String targetType, Long companyId, String keyword) {
        LambdaQueryWrapper<Mortgage> wrapper = new LambdaQueryWrapper<Mortgage>()
                .isNull(Mortgage::getDeletedAt)
                .eq(status != null && !status.isBlank(), Mortgage::getStatus, status)
                .eq(targetType != null && !targetType.isBlank(), Mortgage::getTargetType, targetType)
                .eq(companyId != null, Mortgage::getCompanyId, companyId);
        if (keyword != null && !keyword.isBlank()) {
            // 关键字只搜「人 / 银行 / 合同号」：这三个是用户手里真实有的线索。
            // 加日期或金额范围会让筛选项变成一组，而列表页只留了一个输入框。
            wrapper.and(w -> w.like(Mortgage::getMortgagee, keyword)
                    .or()
                    .like(Mortgage::getBank, keyword)
                    .or()
                    .like(Mortgage::getContractNo, keyword));
        }
        wrapper.orderByDesc(Mortgage::getId);
        Page<Mortgage> result = mortgageMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<Mortgage> rows = result.getRecords();
        Map<String, TargetNames> targets = resolveTargetNames(rows);
        Map<Long, String> companyNames = companyNames(
                rows.stream().map(Mortgage::getCompanyId).toList());
        List<MortgageRecordView> views = new ArrayList<>(rows.size());
        for (Mortgage row : rows) {
            views.add(toView(row, targets, companyNames, false));
        }
        return PageResult.of(views, result.getTotal(), page, pageSize);
    }

    public MortgageRecordView get(Long id) {
        Mortgage row = require(id);
        Map<String, TargetNames> targets = resolveTargetNames(List.of(row));
        Map<Long, String> companyNames = companyNames(singleton(row.getCompanyId()));
        return toView(row, targets, companyNames, true);
    }

    /**
     * 标的选题项：按标的类型 + 所属公司联动过滤。
     *
     * <p><b>为什么不用既有的 {@code GET /projects} / {@code GET /assets}</b>：它们要求
     * {@code asset.project:view} / {@code asset.ledger:view}，而做抵押登记的人（权证岗）
     * 未必持有这两个权限 —— 缺权限就选不到标的，功能等于不存在。
     *
     * <p><b>为什么分区要求先选公司</b>：{@code project_zone} 没有 {@code company_id}，
     * 公司只能经 {@code project} 推导。不限定公司就等于把全部项目的分区都开出去，
     * 而本模块的校验口径是「标的必须属于所选公司」—— 下拉里能选、保存时被拒，是最差的组合。
     */
    public PageResult<MortgageTargetOption> targetOptions(String targetType, Long companyId,
            String keyword, long page, long pageSize) {
        if (targetType == null || !TARGET_TYPES.contains(targetType)) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "标的类型必须是 project / zone / asset 之一：" + targetType);
        }
        if (companyId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请先选择所属公司");
        }
        long size = Math.min(Math.max(pageSize, 1), MAX_TARGET_OPTIONS_PAGE_SIZE);
        return switch (targetType) {
            case Mortgage.TARGET_PROJECT -> projectOptions(companyId, keyword, page, size);
            case Mortgage.TARGET_ZONE -> zoneOptions(companyId, keyword, page, size);
            default -> assetOptions(companyId, keyword, page, size);
        };
    }

    private PageResult<MortgageTargetOption> projectOptions(Long companyId, String keyword,
            long page, long size) {
        Page<Project> result = projectMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Project>()
                        .apply("deleted_at IS NULL")
                        .eq(Project::getCompanyId, companyId)
                        .like(keyword != null && !keyword.isBlank(), Project::getName, keyword)
                        .orderByAsc(Project::getId));
        // 项目标的的「上级项目名」填它自己：展示层对三种类型用同一套拼接规则，
        // 让 projectName 为空会逼前端为 project 分支写特例
        List<MortgageTargetOption> options = result.getRecords().stream()
                .map(p -> option(p.getId(), p.getName(), p.getName(), null, null))
                .toList();
        return PageResult.of(options, result.getTotal(), page, size);
    }

    private PageResult<MortgageTargetOption> zoneOptions(Long companyId, String keyword,
            long page, long size) {
        Map<Long, Project> projects = projectsOfCompany(companyId);
        if (projects.isEmpty()) {
            return PageResult.of(List.of(), 0, page, size);
        }
        Page<ProjectZone> result = projectZoneMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<ProjectZone>()
                        .apply("deleted_at IS NULL")
                        .in(ProjectZone::getProjectId, projects.keySet())
                        .like(keyword != null && !keyword.isBlank(), ProjectZone::getName, keyword)
                        .orderByAsc(ProjectZone::getId));
        List<MortgageTargetOption> options = result.getRecords().stream()
                .map(z -> option(z.getId(), z.getName(),
                        projectName(projects.get(z.getProjectId())), null, null))
                .toList();
        return PageResult.of(options, result.getTotal(), page, size);
    }

    private PageResult<MortgageTargetOption> assetOptions(Long companyId, String keyword,
            long page, long size) {
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

        Map<Long, String> projectNames = projectNames(result.getRecords());
        Map<Long, String> zoneNames = zoneNames(result.getRecords());
        List<MortgageTargetOption> options = result.getRecords().stream()
                .map(a -> option(a.getId(), a.getName(), at(projectNames, a.getProjectId()),
                        at(zoneNames, a.getZoneId()), a.getFloorNo()))
                .toList();
        return PageResult.of(options, result.getTotal(), page, size);
    }

    // ------------------------------------------------------------------
    // 写：草稿
    // ------------------------------------------------------------------

    @Transactional
    public MortgageRecordView create(MortgageRecordInput input) {
        Mortgage entity = new Mortgage();
        entity.setStatus(Mortgage.STATUS_DRAFT);
        applyAndValidate(entity, input, null);
        entity.setCreatedAt(LocalDateTime.now());
        mortgageMapper.insert(entity);
        recordSheetService.syncAttachments(
                AttachmentOwner.MORTGAGE, entity.getId(), input.getAttachments());
        return get(entity.getId());
    }

    @Transactional
    public MortgageRecordView update(Long id, MortgageRecordInput input) {
        Mortgage entity = requireDraft(id);
        applyAndValidate(entity, input, id);
        entity.setUpdatedAt(LocalDateTime.now());
        mortgageMapper.updateById(entity);
        recordSheetService.syncAttachments(
                AttachmentOwner.MORTGAGE, entity.getId(), input.getAttachments());
        return get(entity.getId());
    }

    @Transactional
    public void delete(Long id) {
        Mortgage entity = requireDraft(id);
        // 软删：附件行不清理（与权属流转 / 调拨记录同口径）—— 附件挂在主单上，
        // 主单过滤掉就再也读不到，而物理删附件会让「谁在什么时候传过什么」这段审计信息消失
        entity.setDeletedAt(LocalDateTime.now());
        mortgageMapper.updateById(entity);
    }

    // ------------------------------------------------------------------
    // 写：生效
    // ------------------------------------------------------------------

    /**
     * 生效：{@code draft → active}，从此这条记录开始拦截处置 / 流转 / 调拨。
     *
     * <p><b>重跑全部校验</b>：草稿可能已经躺了很久 —— 公司可能停用、标的可能被删、
     * 资产可能换了所属公司。生效时以当时的真实情况为准，而不是起草时的。
     *
     * <p><b>幂等</b>：已生效的直接返回。再写一遍状态看不出差别，但会重复刷新
     * {@code updated_at}，让「什么时候生效的」这个线索变得不可信。
     *
     * <p><b>同步权证状态</b>：{@code asset_certificate.mortgage_status} 是投影，
     * 生效后才第一次变成「在押」。项目 / 分区级抵押会一次性重算其下所有资产的权证状态
     * （一条 UPDATE，见 {@code MortgageMapper.refreshCertMortgageStatus}）。
     */
    @Transactional
    public MortgageRecordView effect(Long id) {
        Mortgage entity = require(id);
        if (Mortgage.STATUS_ACTIVE.equals(entity.getStatus())) {
            return get(id);
        }
        if (!Mortgage.STATUS_DRAFT.equals(entity.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT,
                    "只有草稿可以生效，当前状态：" + entity.getStatus());
        }
        applyAndValidate(entity, toInput(entity), id);
        entity.setStatus(Mortgage.STATUS_ACTIVE);
        entity.setUpdatedAt(LocalDateTime.now());
        mortgageMapper.updateById(entity);
        certificateService.refreshCertMortgageStatus(entity.getTargetType(), entity.getTargetId());
        return get(id);
    }

    // ------------------------------------------------------------------
    // 校验：唯一的一份实现，create / update / effect 三处共用
    // ------------------------------------------------------------------

    /**
     * 校验入参并写进 {@code entity}。
     *
     * <p><b>只有这一份校验</b>：{@code create}、{@code update}、{@code effect} 三处必须调它。
     * 仓内已为「同一语义两处判定漂移」付过代价（{@code replaceZones} vs
     * {@code deleteProjectZone} 的 {@code assertZoneRemovable} 收敛过程），本模块不留第二份。
     *
     * @param selfId 编辑草稿时是当前记录 id，用于把「合同编号唯一」检查排除掉自己；
     *               新建时传 {@code null}
     */
    private void applyAndValidate(Mortgage entity, MortgageRecordInput input, Long selfId) {
        if (input.getCompanyId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "所属公司必填");
        }
        if (!companyTreeService.isActiveCompany(input.getCompanyId())) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "所属公司不存在或已停用：" + input.getCompanyId());
        }
        String targetType = input.getTargetType();
        if (targetType == null || !TARGET_TYPES.contains(targetType)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请选择项目类型（项目 / 分区 / 资产）");
        }
        if (input.getTargetId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请选择抵押标的");
        }
        assertMortgagee(input.getMortgagee());
        assertAmount(input.getAmount());
        assertInterestRate(input.getInterestRate());
        assertTerm(input.getStartDate(), input.getTermMonths());
        String contractNo = trimmedOrNull(input.getContractNo());
        if (contractNo != null && contractNo.length() > MAX_CONTRACT_NO_LENGTH) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "抵押合同编号最多 " + MAX_CONTRACT_NO_LENGTH + " 个字符");
        }
        String bank = trimmedOrNull(input.getBank());
        if (bank != null && bank.length() > MAX_BANK_LENGTH) {
            throw new AppException(ErrorCode.BAD_REQUEST, "抵押银行最多 " + MAX_BANK_LENGTH + " 个字符");
        }
        assertContractNoUnique(contractNo, selfId);

        // 标的归属校验放在最后：它是最贵的一步（要查项目 / 分区 / 资产），
        // 让纯格式错误先返回，用户改一个字段就能过，而不是先查库再报"金额没填"
        assertTargetBelongsToCompany(targetType, input.getTargetId(), input.getCompanyId());

        entity.setCompanyId(input.getCompanyId());
        entity.setTargetType(targetType);
        entity.setTargetId(input.getTargetId());
        // 资产级抵押维持 asset_id 与 target_id 同值，见 Mortgage#assetId 的注释；
        // 项目 / 分区抵押必须把 asset_id 清空，否则「按 asset_id 反查」的地方
        // 会认为这条记录属于某个资产
        entity.setAssetId(Mortgage.TARGET_ASSET.equals(targetType) ? input.getTargetId() : null);
        entity.setMortgagee(trimmedOrNull(input.getMortgagee()));
        entity.setAmount(input.getAmount());
        entity.setInterestRate(input.getInterestRate());
        entity.setBank(bank);
        entity.setRepaymentDate(input.getRepaymentDate());
        entity.setStartDate(input.getStartDate());
        entity.setTermMonths(input.getTermMonths());
        entity.setContractNo(contractNo);
        entity.setEndDate(input.getStartDate().plusMonths(input.getTermMonths()));
    }

    /** 抵押权人必填：没有权利人的抵押记录既拦不住人、也无法追索，只是脏数据。 */
    private void assertMortgagee(String mortgagee) {
        String trimmed = trimmedOrNull(mortgagee);
        if (trimmed == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "抵押公司/人必填");
        }
        if (trimmed.length() > MAX_MORTGAGEE_LENGTH) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "抵押公司/人最多 " + MAX_MORTGAGEE_LENGTH + " 个字符");
        }
    }

    /**
     * 抵押金额必填且非负且最多 2 位小数。
     *
     * <p>卡小数位是因为列是 {@code NUMERIC(18,2)}：不卡的话 PG 会静默四舍五入，
     * 用户填 100.005 存成 100.01 —— 金额被改而不报错是最不该发生的一类问题。
     */
    private void assertAmount(BigDecimal amount) {
        if (amount == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "抵押金额必填");
        }
        if (amount.signum() < 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "抵押金额不能为负");
        }
        if (amount.scale() > 2) {
            throw new AppException(ErrorCode.BAD_REQUEST, "抵押金额最多 2 位小数");
        }
    }

    /** 利率可选；给了就必须是 0~100 的百分数且最多 4 位小数（列是 {@code NUMERIC(8,4)}）。 */
    private void assertInterestRate(BigDecimal rate) {
        if (rate == null) {
            return;
        }
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "利率应在 0 ~ 100 之间（百分数）");
        }
        if (rate.scale() > 4) {
            throw new AppException(ErrorCode.BAD_REQUEST, "利率最多 4 位小数");
        }
    }

    /**
     * 起始时间与期限必填。
     *
     * <p>这两个字段是 {@code end_date} 的唯一来源，而 {@code end_date} 驱动到期预警 ——
     * 允许为空就等于允许造出一条永远不会被预警的抵押。
     */
    private void assertTerm(LocalDate startDate, Integer termMonths) {
        if (startDate == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "抵押起始时间必填");
        }
        if (termMonths == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "抵押期限（月）必填");
        }
        if (termMonths < 1) {
            throw new AppException(ErrorCode.BAD_REQUEST, "抵押期限至少 1 个月");
        }
        if (termMonths > MAX_TERM_MONTHS) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "抵押期限最多 " + MAX_TERM_MONTHS + " 个月");
        }
    }

    /**
     * 合同编号在「已填写且未软删」范围内唯一。
     *
     * <p>库里有 {@code uk_mortgage_contract_no} 兜底，但这里必须先查一次：唯一索引冲突
     * 抛的是 {@code DuplicateKeyException}，到用户眼里是 500 + 一句英文约束名。
     *
     * <p>空白视作未填写（与索引的 {@code contract_no IS NOT NULL} 口径一致），
     * 否则多张草稿都用 {@code ''} 会互相撞。
     */
    private void assertContractNoUnique(String contractNo, Long selfId) {
        if (contractNo == null) {
            return;
        }
        List<Mortgage> existing = mortgageMapper.selectList(new LambdaQueryWrapper<Mortgage>()
                .eq(Mortgage::getContractNo, contractNo)
                .isNull(Mortgage::getDeletedAt)
                .ne(selfId != null, Mortgage::getId, selfId)
                .last("LIMIT 1"));
        if (!existing.isEmpty()) {
            throw new AppException(ErrorCode.CONFLICT, "抵押合同编号已被占用：" + contractNo);
        }
    }

    /**
     * 标的必须存在且归属所选公司。
     *
     * <p>分区经 {@code project} 推导公司：{@code project_zone} 没有 {@code company_id}，
     * 不推导就无法验证「这个分区属于所选公司」，而跨公司抵押是最需要拦住的一类越权。
     */
    private void assertTargetBelongsToCompany(String targetType, Long targetId, Long companyId) {
        switch (targetType) {
            case Mortgage.TARGET_PROJECT -> {
                Project project = requireActiveProject(targetId);
                if (!Objects.equals(project.getCompanyId(), companyId)) {
                    throw new AppException(ErrorCode.BAD_REQUEST,
                            "项目不属于所选公司：" + targetId);
                }
            }
            case Mortgage.TARGET_ZONE -> {
                ProjectZone zone = requireActiveZone(targetId);
                Project project = requireActiveProject(zone.getProjectId());
                if (!Objects.equals(project.getCompanyId(), companyId)) {
                    throw new AppException(ErrorCode.BAD_REQUEST,
                            "分区所属项目不属于所选公司：" + targetId);
                }
            }
            default -> {
                Asset asset = assetMapper.selectById(targetId);
                if (asset == null || asset.getDeletedAt() != null) {
                    throw new AppException(ErrorCode.NOT_FOUND, "资产不存在或已删除：" + targetId);
                }
                if (LIFECYCLE_EXITED.equals(asset.getLifecycleStatus())) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "资产已退出，不可抵押：" + targetId);
                }
                if (!Objects.equals(asset.getAssetCompanyId(), companyId)) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "资产不属于所选公司：" + targetId);
                }
            }
        }
    }

    private Project requireActiveProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "项目不存在：" + projectId);
        }
        return project;
    }

    private ProjectZone requireActiveZone(Long zoneId) {
        ProjectZone zone = projectZoneMapper.selectActiveById(zoneId);
        if (zone == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "分区不存在或已删除：" + zoneId);
        }
        return zone;
    }

    // ------------------------------------------------------------------
    // 视图
    // ------------------------------------------------------------------

    /** 把已落库的记录还原成 {@code applyAndValidate} 能吃的入参（生效要重跑同一份校验）。 */
    private MortgageRecordInput toInput(Mortgage entity) {
        MortgageRecordInput input = new MortgageRecordInput();
        input.setCompanyId(entity.getCompanyId());
        input.setTargetType(entity.getTargetType());
        input.setTargetId(entity.getTargetId());
        input.setMortgagee(entity.getMortgagee());
        input.setAmount(entity.getAmount());
        input.setInterestRate(entity.getInterestRate());
        input.setBank(entity.getBank());
        input.setRepaymentDate(entity.getRepaymentDate());
        input.setStartDate(entity.getStartDate());
        input.setTermMonths(entity.getTermMonths());
        input.setContractNo(entity.getContractNo());
        return input;
    }

    private MortgageRecordView toView(Mortgage row, Map<String, TargetNames> targets,
            Map<Long, String> companyNames, boolean withAttachments) {
        MortgageRecordView view = new MortgageRecordView();
        view.setId(row.getId());
        view.setCompanyId(row.getCompanyId());
        view.setCompanyName(at(companyNames, row.getCompanyId()));
        view.setTargetType(row.getTargetType());
        view.setTargetId(row.getTargetId());
        TargetNames names = at(targets, targetKey(row.getTargetType(), row.getTargetId()));
        if (names != null) {
            view.setTargetName(names.name());
            view.setProjectName(names.projectName());
            view.setZoneName(names.zoneName());
            view.setFloorNo(names.floorNo());
            view.setAssetNo(names.assetNo());
        }
        view.setMortgagee(row.getMortgagee());
        view.setAmount(row.getAmount());
        view.setInterestRate(row.getInterestRate());
        view.setBank(row.getBank());
        view.setRepaymentDate(row.getRepaymentDate());
        view.setStartDate(row.getStartDate());
        view.setEndDate(row.getEndDate());
        view.setTermMonths(row.getTermMonths());
        view.setContractNo(row.getContractNo());
        view.setStatus(row.getStatus());
        view.setReleaseStatus(row.getReleaseStatus());
        view.setReleaseRemark(row.getReleaseRemark());
        view.setReleasedAt(row.getReleasedAt());
        view.setCreatedAt(row.getCreatedAt());
        view.setUpdatedAt(row.getUpdatedAt());
        view.setAttachments(withAttachments
                ? recordSheetService.toAttachmentRefs(AttachmentOwner.MORTGAGE, row.getId())
                : null);
        return view;
    }

    /** 标的的展示名原料：三级的取值位置不同，但展示层只关心「哪几段有值」。 */
    private record TargetNames(String name, String projectName, String zoneName, Integer floorNo,
            String assetNo) {
    }

    /**
     * 批量解析标的名称。
     *
     * <p>顺序是刻意的：先取资产（才知道它们的 zone / project），再取分区（才知道它们的 project），
     * 最后取项目。反过来做就得分两轮查询同一张表。
     */
    private Map<String, TargetNames> resolveTargetNames(List<Mortgage> rows) {
        Set<Long> projectIds = new LinkedHashSet<>();
        Set<Long> zoneIds = new LinkedHashSet<>();
        Set<Long> assetIds = new LinkedHashSet<>();
        for (Mortgage row : rows) {
            if (row.getTargetId() == null) {
                continue;
            }
            if (Mortgage.TARGET_PROJECT.equals(row.getTargetType())) {
                projectIds.add(row.getTargetId());
            } else if (Mortgage.TARGET_ZONE.equals(row.getTargetType())) {
                zoneIds.add(row.getTargetId());
            } else if (Mortgage.TARGET_ASSET.equals(row.getTargetType())) {
                assetIds.add(row.getTargetId());
            }
        }
        Map<Long, Asset> assets = assetIds.isEmpty()
                ? Map.of()
                : assetMapper.selectBatchIds(assetIds).stream()
                        .collect(Collectors.toMap(Asset::getId, a -> a, (a, b) -> a));
        for (Asset asset : assets.values()) {
            addIfNotNull(zoneIds, asset.getZoneId());
            addIfNotNull(projectIds, asset.getProjectId());
        }
        Map<Long, ProjectZone> zones = zoneIds.isEmpty()
                ? Map.of()
                : projectZoneMapper.selectBatchIds(zoneIds).stream()
                        .collect(Collectors.toMap(ProjectZone::getId, z -> z, (a, b) -> a));
        for (ProjectZone zone : zones.values()) {
            addIfNotNull(projectIds, zone.getProjectId());
        }
        Map<Long, Project> projects = projectIds.isEmpty()
                ? Map.of()
                : projectMapper.selectBatchIds(projectIds).stream()
                        .collect(Collectors.toMap(Project::getId, p -> p, (a, b) -> a));

        Map<String, TargetNames> resolved = new LinkedHashMap<>();
        for (Mortgage row : rows) {
            String key = targetKey(row.getTargetType(), row.getTargetId());
            if (key == null || resolved.containsKey(key)) {
                continue;
            }
            resolved.put(key, names(row, assets, zones, projects));
        }
        return resolved;
    }

    private TargetNames names(Mortgage row, Map<Long, Asset> assets, Map<Long, ProjectZone> zones,
            Map<Long, Project> projects) {
        if (Mortgage.TARGET_PROJECT.equals(row.getTargetType())) {
            Project project = at(projects, row.getTargetId());
            String name = projectName(project);
            // 项目标的的「上级项目名」就是它自己：展示层对三种类型用同一套拼接规则，
            // 让 projectName 为空会逼前端为 project 分支写特例
            return new TargetNames(name, name, null, null, null);
        }
        if (Mortgage.TARGET_ZONE.equals(row.getTargetType())) {
            ProjectZone zone = at(zones, row.getTargetId());
            String projectName = zone == null ? null : projectName(at(projects, zone.getProjectId()));
            return new TargetNames(zone == null ? null : zone.getName(), projectName, null, null, null);
        }
        Asset asset = at(assets, row.getTargetId());
        if (asset == null) {
            return new TargetNames(null, null, null, null, null);
        }
        ProjectZone zone = at(zones, asset.getZoneId());
        return new TargetNames(asset.getName(),
                projectName(at(projects, asset.getProjectId())),
                zone == null ? null : zone.getName(),
                asset.getFloorNo(),
                asset.getAssetNo());
    }

    private Map<Long, Project> projectsOfCompany(Long companyId) {
        return projectMapper.selectList(new LambdaQueryWrapper<Project>()
                        .apply("deleted_at IS NULL")
                        .eq(Project::getCompanyId, companyId))
                .stream()
                .collect(Collectors.toMap(Project::getId, p -> p, (a, b) -> a, LinkedHashMap::new));
    }

    private Map<Long, String> projectNames(List<Asset> assets) {
        Set<Long> ids = assets.stream()
                .map(Asset::getProjectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return projectMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Project::getId, Project::getName, (a, b) -> a));
    }

    private Map<Long, String> zoneNames(List<Asset> assets) {
        Set<Long> ids = assets.stream()
                .map(Asset::getZoneId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return projectZoneMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ProjectZone::getId, ProjectZone::getName, (a, b) -> a));
    }

    private Map<Long, String> companyNames(Collection<Long> ids) {
        Set<Long> unique = ids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (unique.isEmpty()) {
            return Map.of();
        }
        return companyMapper.selectBatchIds(unique).stream()
                .collect(Collectors.toMap(Company::getId, Company::getName, (a, b) -> a));
    }

    private MortgageTargetOption option(Long targetId, String name, String projectName,
            String zoneName, Integer floorNo) {
        MortgageTargetOption option = new MortgageTargetOption();
        option.setTargetId(targetId);
        option.setName(name);
        option.setProjectName(projectName);
        option.setZoneName(zoneName);
        option.setFloorNo(floorNo);
        return option;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private Mortgage require(Long id) {
        Mortgage row = mortgageMapper.selectById(id);
        if (row == null || row.getDeletedAt() != null) {
            throw new AppException(ErrorCode.NOT_FOUND, "抵押记录不存在：" + id);
        }
        return row;
    }

    private Mortgage requireDraft(Long id) {
        Mortgage row = require(id);
        if (!Mortgage.STATUS_DRAFT.equals(row.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "只有草稿可以修改或删除");
        }
        return row;
    }

    /**
     * 标的唯一键。
     *
     * <p>必须带类型：{@code project.id}、{@code project_zone.id}、{@code asset.id} 是三个
     * 独立的序列，都从 1 开始 —— 只用 id 做键时，一条项目级和一条资产级的抵押
     * （恰好都叫 #1）会被当成同一个标的，后者显示的是前者的名字。
     */
    private String targetKey(String targetType, Long targetId) {
        if (targetType == null || targetId == null) {
            return null;
        }
        return targetType + ":" + targetId;
    }

    private String projectName(Project project) {
        return project == null ? null : project.getName();
    }

    private void addIfNotNull(Set<Long> ids, Long id) {
        if (id != null) {
            ids.add(id);
        }
    }

    /**
     * 可空安全的按 id 取值。
     *
     * <p><b>必须走这个方法，不能直接 {@code map.get(id)}</b>：无 id 时这些映射是
     * {@code Map.of()}（不可变集合），而不可变集合对 **null 键**直接抛 NPE ——
     * {@code asset.projectId} / {@code mortgage.companyId} 都是可空列，
     * 所以「一个没有项目的资产」会让整个资产下拉 500。
     */
    private <K, T> T at(Map<K, T> byKey, K key) {
        return key == null ? null : byKey.get(key);
    }

    private String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 单元素 id 列表。
     *
     * <p><b>不能用 {@code List.of}</b>：它对 null 元素抛 NPE，而 {@code company_id}
     * 允许为空（存量行没回填到）。
     */
    private List<Long> singleton(Long id) {
        List<Long> ids = new ArrayList<>(1);
        ids.add(id);
        return ids;
    }
}
