package com.ams.config;

import com.ams.modules.alert.entity.AlertRecord;
import com.ams.modules.alert.mapper.AlertRecordMapper;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.AssetCertificate;
import com.ams.modules.asset.entity.AssetTransfer;
import com.ams.modules.asset.entity.Mortgage;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.mapper.AssetCertificateMapper;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.AssetTransferMapper;
import com.ams.modules.asset.mapper.MortgageMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.entity.BillPayment;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.entity.Prepay;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.billing.mapper.BillPaymentMapper;
import com.ams.modules.billing.mapper.PaymentMapper;
import com.ams.modules.billing.mapper.PrepayMapper;
import com.ams.modules.config.entity.ConfigVersion;
import com.ams.modules.config.mapper.ConfigVersionMapper;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.entity.DepositTransaction;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.contract.mapper.DepositTransactionMapper;
import com.ams.modules.disposal.entity.DisposalOrder;
import com.ams.modules.disposal.mapper.DisposalOrderMapper;
import com.ams.modules.evaluation.entity.EvaluationRequest;
import com.ams.modules.evaluation.mapper.EvaluationRequestMapper;
import com.ams.modules.finance.entity.BankFlow;
import com.ams.modules.finance.entity.FinanceVoucher;
import com.ams.modules.finance.mapper.BankFlowMapper;
import com.ams.modules.finance.mapper.FinanceVoucherMapper;
import com.ams.modules.intangible.entity.IntangibleAsset;
import com.ams.modules.intangible.mapper.IntangibleAssetMapper;
import com.ams.modules.invoice.entity.Invoice;
import com.ams.modules.invoice.entity.InvoiceTitle;
import com.ams.modules.invoice.mapper.InvoiceMapper;
import com.ams.modules.invoice.mapper.InvoiceTitleMapper;
import com.ams.modules.lease.entity.LeaseListing;
import com.ams.modules.lease.entity.Tenant;
import com.ams.modules.lease.entity.TenantCreditLog;
import com.ams.modules.lease.entity.TenderAnnouncement;
import com.ams.modules.lease.entity.TenderApplication;
import com.ams.modules.lease.mapper.LeaseListingMapper;
import com.ams.modules.lease.mapper.TenantCreditLogMapper;
import com.ams.modules.lease.mapper.TenantMapper;
import com.ams.modules.lease.mapper.TenderAnnouncementMapper;
import com.ams.modules.lease.mapper.TenderApplicationMapper;
import com.ams.modules.maintenance.entity.InspectionRecord;
import com.ams.modules.maintenance.entity.MaintenanceVendor;
import com.ams.modules.maintenance.entity.RepairOrder;
import com.ams.modules.maintenance.mapper.InspectionRecordMapper;
import com.ams.modules.maintenance.mapper.MaintenanceVendorMapper;
import com.ams.modules.maintenance.mapper.RepairOrderMapper;
import com.ams.modules.meter.entity.Meter;
import com.ams.modules.meter.entity.MeterReading;
import com.ams.modules.meter.mapper.MeterMapper;
import com.ams.modules.meter.mapper.MeterReadingMapper;
import com.ams.modules.migration.entity.MigrationBatch;
import com.ams.modules.migration.mapper.MigrationBatchMapper;
import com.ams.modules.notification.entity.Notification;
import com.ams.modules.notification.mapper.NotificationMapper;
import com.ams.modules.occupation.entity.OccupationOrder;
import com.ams.modules.occupation.mapper.OccupationOrderMapper;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.entity.Department;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.CompanyMapper;
import com.ams.modules.org.mapper.DepartmentMapper;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.plan.entity.BusinessPlan;
import com.ams.modules.plan.mapper.BusinessPlanMapper;
import com.ams.modules.pricing.entity.PaymentPlan;
import com.ams.modules.pricing.mapper.PaymentPlanMapper;
import com.ams.modules.regulation.entity.RegulationReport;
import com.ams.modules.regulation.mapper.RegulationReportMapper;
import com.ams.modules.revitalization.entity.RevitalizationTask;
import com.ams.modules.revitalization.mapper.RevitalizationTaskMapper;
import com.ams.modules.selfuse.entity.SelfUseOrder;
import com.ams.modules.selfuse.mapper.SelfUseOrderMapper;
import com.ams.modules.system.entity.UserRole;
import com.ams.modules.system.mapper.UserRoleMapper;
import com.ams.modules.task.entity.Task;
import com.ams.modules.task.mapper.TaskMapper;
import com.ams.modules.fixedasset.entity.FixedAsset;
import com.ams.modules.fixedasset.mapper.FixedAssetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演示环境数据初始化器（仅 demo profile 启用，生产隔离）。
 *
 * <p>核心目标：保证「一本账」数据一致性 ——
 * <ul>
 *   <li>合同总额 = 缴费计划合计（120000.00）</li>
 *   <li>已出账账单本金合计（80000.00）= 已核销（64000.00）+ 欠费（16000.00）</li>
 *   <li>收款合计（64000.00）= 核销流水合计（64000.00），无超额</li>
 *   <li>期初迁移试算平衡 balance_result = 0</li>
 * </ul>
 */
@Component
@Profile("demo")
@Order(100)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final CompanyMapper companyMapper;
    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final ProjectMapper projectMapper;
    private final AssetMapper assetMapper;
    private final AssetCertificateMapper certificateMapper;
    private final MortgageMapper mortgageMapper;
    private final AssetTransferMapper transferMapper;
    private final TenantMapper tenantMapper;
    private final TenantCreditLogMapper creditLogMapper;
    private final LeaseListingMapper listingMapper;
    private final TenderAnnouncementMapper announcementMapper;
    private final TenderApplicationMapper applicationMapper;
    private final ContractMapper contractMapper;
    private final DepositTransactionMapper depositTxMapper;
    private final PaymentPlanMapper planMapper;
    private final BillMapper billMapper;
    private final PaymentMapper paymentMapper;
    private final BillPaymentMapper billPaymentMapper;
    private final PrepayMapper prepayMapper;
    private final InvoiceMapper invoiceMapper;
    private final InvoiceTitleMapper invoiceTitleMapper;
    private final RepairOrderMapper repairMapper;
    private final MaintenanceVendorMapper vendorMapper;
    private final InspectionRecordMapper inspectionMapper;
    private final TaskMapper taskMapper;
    private final AlertRecordMapper alertRecordMapper;
    private final NotificationMapper notificationMapper;
    private final BusinessPlanMapper businessPlanMapper;
    private final RevitalizationTaskMapper revitalizationMapper;
    private final DisposalOrderMapper disposalMapper;
    private final OccupationOrderMapper occupationMapper;
    private final SelfUseOrderMapper selfUseMapper;
    private final EvaluationRequestMapper evaluationMapper;
    private final MigrationBatchMapper migrationBatchMapper;
    private final ConfigVersionMapper configVersionMapper;
    private final RegulationReportMapper regulationMapper;
    private final MeterMapper meterMapper;
    private final MeterReadingMapper meterReadingMapper;
    private final BankFlowMapper bankFlowMapper;
    private final FinanceVoucherMapper voucherMapper;
    private final IntangibleAssetMapper intangibleMapper;
    private final FixedAssetMapper fixedAssetMapper;
    private final PasswordEncoder passwordEncoder;

    public DemoDataSeeder(
            CompanyMapper companyMapper,
            DepartmentMapper departmentMapper,
            UserMapper userMapper,
            UserRoleMapper userRoleMapper,
            ProjectMapper projectMapper,
            AssetMapper assetMapper,
            AssetCertificateMapper certificateMapper,
            MortgageMapper mortgageMapper,
            AssetTransferMapper transferMapper,
            TenantMapper tenantMapper,
            TenantCreditLogMapper creditLogMapper,
            LeaseListingMapper listingMapper,
            TenderAnnouncementMapper announcementMapper,
            TenderApplicationMapper applicationMapper,
            ContractMapper contractMapper,
            DepositTransactionMapper depositTxMapper,
            PaymentPlanMapper planMapper,
            BillMapper billMapper,
            PaymentMapper paymentMapper,
            BillPaymentMapper billPaymentMapper,
            PrepayMapper prepayMapper,
            InvoiceMapper invoiceMapper,
            InvoiceTitleMapper invoiceTitleMapper,
            RepairOrderMapper repairMapper,
            MaintenanceVendorMapper vendorMapper,
            InspectionRecordMapper inspectionMapper,
            TaskMapper taskMapper,
            AlertRecordMapper alertRecordMapper,
            NotificationMapper notificationMapper,
            BusinessPlanMapper businessPlanMapper,
            RevitalizationTaskMapper revitalizationMapper,
            DisposalOrderMapper disposalMapper,
            OccupationOrderMapper occupationMapper,
            SelfUseOrderMapper selfUseMapper,
            EvaluationRequestMapper evaluationMapper,
            MigrationBatchMapper migrationBatchMapper,
            ConfigVersionMapper configVersionMapper,
            RegulationReportMapper regulationMapper,
            MeterMapper meterMapper,
            MeterReadingMapper meterReadingMapper,
            BankFlowMapper bankFlowMapper,
            FinanceVoucherMapper voucherMapper,
            IntangibleAssetMapper intangibleMapper,
            FixedAssetMapper fixedAssetMapper,
            PasswordEncoder passwordEncoder) {
        this.companyMapper = companyMapper;
        this.departmentMapper = departmentMapper;
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.projectMapper = projectMapper;
        this.assetMapper = assetMapper;
        this.certificateMapper = certificateMapper;
        this.mortgageMapper = mortgageMapper;
        this.transferMapper = transferMapper;
        this.tenantMapper = tenantMapper;
        this.creditLogMapper = creditLogMapper;
        this.listingMapper = listingMapper;
        this.announcementMapper = announcementMapper;
        this.applicationMapper = applicationMapper;
        this.contractMapper = contractMapper;
        this.depositTxMapper = depositTxMapper;
        this.planMapper = planMapper;
        this.billMapper = billMapper;
        this.paymentMapper = paymentMapper;
        this.billPaymentMapper = billPaymentMapper;
        this.prepayMapper = prepayMapper;
        this.invoiceMapper = invoiceMapper;
        this.invoiceTitleMapper = invoiceTitleMapper;
        this.repairMapper = repairMapper;
        this.vendorMapper = vendorMapper;
        this.inspectionMapper = inspectionMapper;
        this.taskMapper = taskMapper;
        this.alertRecordMapper = alertRecordMapper;
        this.notificationMapper = notificationMapper;
        this.businessPlanMapper = businessPlanMapper;
        this.revitalizationMapper = revitalizationMapper;
        this.disposalMapper = disposalMapper;
        this.occupationMapper = occupationMapper;
        this.selfUseMapper = selfUseMapper;
        this.evaluationMapper = evaluationMapper;
        this.migrationBatchMapper = migrationBatchMapper;
        this.configVersionMapper = configVersionMapper;
        this.regulationMapper = regulationMapper;
        this.meterMapper = meterMapper;
        this.meterReadingMapper = meterReadingMapper;
        this.bankFlowMapper = bankFlowMapper;
        this.voucherMapper = voucherMapper;
        this.intangibleMapper = intangibleMapper;
        this.fixedAssetMapper = fixedAssetMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (companyMapper.selectCount(null) > 0) {
            log.info("[demo] 演示数据已存在，尝试补齐淮安资产地图坐标");
            enrichHuaiAnMapDataIfNeeded();
            return;
        }
        log.info("[demo] 开始初始化演示数据（淮安市原型）…");

        // 1. 组织与用户
        Company group = company("淮安市国资集团", null, "group");
        Company sub = company("淮安城投资产管理有限公司", group.getId(), "subsidiary");
        Department assetDept = department(sub.getId(), "资产管理部");
        Department financeDept = department(sub.getId(), "财务部");
        // 工作端 / PC：员工账号（同名密码 admin123；手机号供工作端微信绑定）
        User admin = user("admin", "系统管理员", "13900000001", sub.getId(), assetDept.getId(), "super_admin");
        user("operator", "运营管理员", "13900000002", sub.getId(), assetDept.getId(), "operator");
        user("assetmgr", "资产管理员", "13900000003", sub.getId(), assetDept.getId(), "asset_mgr");
        user("finance", "财务人员", "13900000004", sub.getId(), financeDept.getId(), "finance");
        user("leader", "决策层领导", "13900000005", sub.getId(), assetDept.getId(), "leader");
        user("maintenance", "维修管理员", "13900000006", sub.getId(), assetDept.getId(), "maintenance");
        user("approver", "审批人员", "13900000007", sub.getId(), assetDept.getId(), "approver");
        user("clerk", "办事员", "13900000008", sub.getId(), assetDept.getId(), "clerk");

        // 2. 项目与资产（淮安多区县落点，覆盖主要租控状态）
        Project qingjiangpu = project(sub.getId(), "清江浦智慧产业园",
                "江苏省淮安市清江浦区枚乘东路88号", "119.0452000", "33.5821000");
        Project chuanzhou = project(sub.getId(), "楚州古城文旅资产包",
                "江苏省淮安市淮安区镇淮楼东路16号", "119.1485000", "33.5062000");
        Project huaiyin = project(sub.getId(), "淮阴仓储物流园",
                "江苏省淮安市淮阴区北京北路128号", "119.0412000", "33.6358000");
        Project jingkai = project(sub.getId(), "经开区标准厂房群",
                "江苏省淮安市经济技术开发区富强路66号", "119.1886000", "33.5754000");
        Project hongze = project(sub.getId(), "洪泽湖畔商业综合体",
                "江苏省淮安市洪泽区东风路58号", "118.8752000", "33.2986000");

        Asset leased = asset(qingjiangpu.getId(), "AST-2026-001", "1号厂房", "property",
                "1200.00", "leased", sub.getId(), sub.getId(),
                "江苏省", "淮安市", "清江浦区", "枚乘东路88号1号厂房",
                "119.0461000", "33.5830000", null);
        Asset vacant = asset(qingjiangpu.getId(), "AST-2026-002", "2号办公楼", "property",
                "800.00", "vacant", sub.getId(), sub.getId(),
                "江苏省", "淮安市", "清江浦区", "枚乘东路88号2号办公楼",
                "119.0443000", "33.5812000", LocalDateTime.now().minusDays(45));
        Asset leasing = asset(qingjiangpu.getId(), "AST-2026-003", "3号商铺", "property",
                "150.00", "leasing", sub.getId(), sub.getId(),
                "江苏省", "淮安市", "清江浦区", "枚乘东路商业街3号商铺",
                "119.0475000", "33.5828000", null);
        Asset selfUse = asset(qingjiangpu.getId(), "AST-2026-004", "仓储用房", "property",
                "500.00", "self_use", sub.getId(), sub.getId(),
                "江苏省", "淮安市", "清江浦区", "枚乘东路88号仓储区",
                "119.0438000", "33.5805000", null);
        Asset occupied = asset(qingjiangpu.getId(), "AST-2026-005", "临时周转地块", "land",
                "3000.00", "occupied", sub.getId(), sub.getId(),
                "江苏省", "淮安市", "清江浦区", "枚乘东路南侧临时地块",
                "119.0482000", "33.5798000", null);

        // 淮安多区县补充点位（地图展示用）
        seedExtraHuaiAnAssets(sub.getId(), chuanzhou, huaiyin, jingkai, hongze);

        // 权证与抵押
        certificate(leased.getId(), "property_cert", "CQZ-0001", "淮安城投资产管理有限公司");
        mortgage(leasing.getId(), "江苏银行淮安分行", "500000.00", LocalDate.of(2026, 1, 1),
                LocalDate.of(2027, 1, 1), "active");

        // 3. 租户 + 用户端门户账号（密码均为 admin123）
        Tenant tenantA = tenant("张三（个人）", "13800000001", "person", false, 100);
        Tenant tenantB = tenant("某商贸有限公司", "13800000002", "enterprise", false, 95);
        tenant("某失信企业", "13800000003", "enterprise", true, 20);
        tenantUser("tenant", "张三（个人）", "13800000001", tenantA.getId());
        tenantUser("tenant_corp", "某商贸有限公司", "13800000002", tenantB.getId());
        creditLog(tenantB.getId(), "renewal_priority", 5, "信用良好，进入续签优先队列");

        // 4. 招租与公开招租
        listing(leasing.getId(), "6000.00", false);
        listing(vacant.getId(), null, true);
        TenderAnnouncement announcement = announcement("2号办公楼公开招租",
                List.of(vacant.getId()), LocalDate.of(2026, 9, 20), 30);
        application(announcement.getId(), tenantA.getId(), "pending", false, null, false, null, null);
        application(announcement.getId(), tenantB.getId(), "approved", true,
                "20000.00", false, 1, null);

        // 5. 合同（生效合同，金额勾稽基准：月租 10000 × 12 = 120000）
        Contract contract = contract(leased.getId(), tenantA.getId(), "CT-2026-001",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "10000.00", "30000.00", "monthly");

        // 6. 缴费计划（12 期 × 10000 = 120000，与合同总额一致）
        for (int m = 1; m <= 12; m++) {
            LocalDate start = LocalDate.of(2026, m, 1);
            LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
            plan(contract.getId(), m, start, end, "10000.00", start);
        }

        // 7. 账单（出账 8 期：1~8 月，本金合计 80000）
        //    状态勾稽：B1~B6 已缴、B7 部分缴(4000)、B8 待缴
        Long[] billIds = new Long[8];
        for (int m = 1; m <= 8; m++) {
            LocalDate start = LocalDate.of(2026, m, 1);
            LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
            String status = m <= 6 ? "paid" : (m == 7 ? "partial_paid" : "unpaid");
            BigDecimal paid = m <= 6 ? new BigDecimal("10000.00")
                    : (m == 7 ? new BigDecimal("4000.00") : BigDecimal.ZERO);
            billIds[m - 1] = bill(contract.getId(), leased.getId(), tenantA.getId(),
                    "ZD2026" + String.format("%02d", m), start, end, "10000.00", paid, status,
                    m == 8 ? 1 : 0);
        }

        // 8. 收款 + 核销（收款合计 64000 = 核销合计 64000）
        //    P1~P6 各 10000 全额核销 B1~B6；P7 = 4000 部分核销 B7
        for (int i = 1; i <= 6; i++) {
            Payment p = payment(contract.getId(), tenantA.getId(), "SK202600" + i,
                    "10000.00", "wechat", "user_mp", "confirmed");
            billPayment(billIds[i - 1], p.getId(), "principal", "10000.00");
        }
        Payment p7 = payment(contract.getId(), tenantA.getId(), "SK2026007",
                "4000.00", "cash", "worker_mp", "pending");
        billPayment(billIds[6], p7.getId(), "principal", "4000.00");

        // 9. 预收与保证金流水
        prepay(contract.getId(), tenantA.getId(), "5000.00", "0.00", "5000.00", "期初预交租金");
        depositTx(contract.getId(), "collect", "30000.00", null, "签约收取保证金");

        // 10. 发票
        InvoiceTitle title = invoiceTitle(tenantA.getId(), "张三", "110101199001011234");
        invoice("FP2026090001", p7.getId(), title.getId(), "4000.00", "0.09", "issued");

        // 11. 维修与巡查
        MaintenanceVendor vendor = vendor("淮安城投物业维修公司", "李师傅", "13900000001");
        repair(leased.getId(), "卫生间漏水", "pending_accept", vendor.getId(), admin.getId());
        inspection(leased.getId(), admin.getId(), "正常", null);

        // 12. 任务 / 预警 / 通知
        task("dunning", "CT-2026-001", sub.getId(), admin.getId(), LocalDateTime.now().plusDays(2), "pending");
        alert(sub.getId(), "rent_overdue", 2, "contract", contract.getId(), "租金逾期提醒",
                "合同 CT-2026-001 存在逾期账单", "pending", admin.getId());
        notification(admin.getId(), "合同审批待办", "合同 CT-2026-001 待审批", "contract", contract.getId(), "in_app");

        // 13. 经营计划 / 盘活 / 处置 / 占用 / 自用 / 评估 / 调拨
        businessPlan(sub.getId(), qingjiangpu.getId(), 2026, 9);
        revitalization(vacant.getId(), "退租", "招租", admin.getId(), LocalDate.of(2026, 10, 31), "listing");
        disposal(occupied.getId(), "scrap", "闲置报废", "100000.00", "120000.00", "draft");
        occupation(occupied.getId(), "临时施工占用", "工程部",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), "occupied");
        selfUse(selfUse.getId(), "综合部", "仓储", LocalDate.of(2026, 1, 1), null, "self_use");
        evaluation(leased.getId(), "lease", "XX评估机构", "reported", "12000.00");
        transfer(leased.getId(), sub.getId(), group.getId(), "with_contract", "draft");

        // 14. 期初迁移（试算平衡 balance_result = 0）
        migrationBatch(LocalDate.of(2026, 1, 1), "locked", BigDecimal.ZERO);

        // 15. 配置版本 / 监管报送
        configVersion("tax_rate", "0.09", LocalDate.of(2026, 1, 1), 1);
        regulation("asset_summary", "2026-08", "draft");

        // 16. 表计与抄表 / 银行流水 / 凭证
        Meter meter = meter(leased.getId(), contract.getId(), "electric", "DB-0001");
        meterReading(meter.getId(), "12580.00", LocalDate.of(2026, 9, 1));
        bankFlow("LS20260901", "10000.00", "in", LocalDate.of(2026, 9, 1), "租金收款", "matched");
        voucher("payment", p7.getId(), "PZ2026090001", "pending");

        // 17. 固定资产 / 无形资产
        fixedAsset("GZ-0001", "办公电脑", "electronic", "5000.00", sub.getId());
        intangible("WX-0001", "软件著作权", "software", "100000.00", LocalDate.of(2028, 1, 1));

        log.info("[demo] 演示数据初始化完成：统一密码 admin123");
        log.info("[demo] 用户端账号 tenant / tenant_corp；工作端推荐 clerk / maintenance / approver；PC 推荐 admin");
    }

    /**
     * 已有演示库但资产无经纬度时，按淮安市原型补齐地图点位（不破坏业务演示数据）。
     */
    private void enrichHuaiAnMapDataIfNeeded() {
        Long withCoords = assetMapper.selectCount(
                new LambdaQueryWrapper<Asset>().isNotNull(Asset::getLongitude));
        if (withCoords != null && withCoords > 0) {
            log.info("[demo] 资产地图坐标已存在（{} 条），跳过补齐", withCoords);
            return;
        }
        Company company = companyMapper.selectOne(
                new LambdaQueryWrapper<Company>().eq(Company::getCompanyType, "subsidiary").last("LIMIT 1"));
        if (company == null) {
            company = companyMapper.selectOne(new LambdaQueryWrapper<Company>().last("LIMIT 1"));
        }
        if (company == null) {
            log.warn("[demo] 未找到公司，无法补齐淮安地图数据");
            return;
        }
        Long companyId = company.getId();

        Project qingjiangpu = ensureProject(companyId, "清江浦智慧产业园",
                "江苏省淮安市清江浦区枚乘东路88号", "119.0452000", "33.5821000");
        Project chuanzhou = ensureProject(companyId, "楚州古城文旅资产包",
                "江苏省淮安市淮安区镇淮楼东路16号", "119.1485000", "33.5062000");
        Project huaiyin = ensureProject(companyId, "淮阴仓储物流园",
                "江苏省淮安市淮阴区北京北路128号", "119.0412000", "33.6358000");
        Project jingkai = ensureProject(companyId, "经开区标准厂房群",
                "江苏省淮安市经济技术开发区富强路66号", "119.1886000", "33.5754000");
        Project hongze = ensureProject(companyId, "洪泽湖畔商业综合体",
                "江苏省淮安市洪泽区东风路58号", "118.8752000", "33.2986000");

        // 给已有 AST-2026-* 资产补坐标（若存在）
        patchAssetGeo("AST-2026-001", qingjiangpu.getId(), "江苏省", "淮安市", "清江浦区",
                "枚乘东路88号1号厂房", "119.0461000", "33.5830000", null);
        patchAssetGeo("AST-2026-002", qingjiangpu.getId(), "江苏省", "淮安市", "清江浦区",
                "枚乘东路88号2号办公楼", "119.0443000", "33.5812000", LocalDateTime.now().minusDays(45));
        patchAssetGeo("AST-2026-003", qingjiangpu.getId(), "江苏省", "淮安市", "清江浦区",
                "枚乘东路商业街3号商铺", "119.0475000", "33.5828000", null);
        patchAssetGeo("AST-2026-004", qingjiangpu.getId(), "江苏省", "淮安市", "清江浦区",
                "枚乘东路88号仓储区", "119.0438000", "33.5805000", null);
        patchAssetGeo("AST-2026-005", qingjiangpu.getId(), "江苏省", "淮安市", "清江浦区",
                "枚乘东路南侧临时地块", "119.0482000", "33.5798000", null);

        seedExtraHuaiAnAssets(companyId, chuanzhou, huaiyin, jingkai, hongze);
        log.info("[demo] 淮安市资产地图坐标补齐完成");
    }

    private void seedExtraHuaiAnAssets(Long companyId, Project chuanzhou, Project huaiyin,
            Project jingkai, Project hongze) {
        ensureAsset(chuanzhou.getId(), "AST-HA-101", "镇淮楼文创商铺", "property", "220.00", "leased",
                companyId, "江苏省", "淮安市", "淮安区", "镇淮楼东路文创街区A栋",
                "119.1492000", "33.5071000", null);
        ensureAsset(chuanzhou.getId(), "AST-HA-102", "河下古镇民宿楼", "property", "680.00", "vacant",
                companyId, "江苏省", "淮安市", "淮安区", "河下古镇竹巷18号",
                "119.0586000", "33.5624000", LocalDateTime.now().minusDays(120));
        ensureAsset(chuanzhou.getId(), "AST-HA-103", "周恩来纪念馆配套停车场地块", "land", "1500.00", "self_use",
                companyId, "江苏省", "淮安市", "淮安区", "淮海北路纪念馆南侧",
                "119.1528000", "33.5085000", null);

        ensureAsset(huaiyin.getId(), "AST-HA-201", "北京北路冷链仓", "property", "3200.00", "leased",
                companyId, "江苏省", "淮安市", "淮阴区", "北京北路物流园1号库",
                "119.0425000", "33.6372000", null);
        ensureAsset(huaiyin.getId(), "AST-HA-202", "王营货场周转地", "land", "5000.00", "occupied",
                companyId, "江苏省", "淮安市", "淮阴区", "王营街道货场路东侧",
                "119.0288000", "33.6485000", null);

        ensureAsset(jingkai.getId(), "AST-HA-301", "富强路A栋标准厂房", "property", "4500.00", "leased",
                companyId, "江苏省", "淮安市", "经济技术开发区", "富强路66号A栋",
                "119.1895000", "33.5762000", null);
        ensureAsset(jingkai.getId(), "AST-HA-302", "富强路B栋标准厂房", "property", "4200.00", "leasing",
                companyId, "江苏省", "淮安市", "经济技术开发区", "富强路66号B栋",
                "119.1912000", "33.5748000", null);
        ensureAsset(jingkai.getId(), "AST-HA-303", "淮安东站临街商铺", "property", "180.00", "vacant",
                companyId, "江苏省", "淮安市", "经济技术开发区", "高铁东站站前广场东侧商铺",
                "119.1928000", "33.5886000", LocalDateTime.now().minusDays(28));

        ensureAsset(hongze.getId(), "AST-HA-401", "东风路临湖商铺", "property", "320.00", "leased",
                companyId, "江苏省", "淮安市", "洪泽区", "东风路临湖商业街12号",
                "118.8768000", "33.2995000", null);
        ensureAsset(hongze.getId(), "AST-HA-402", "老子山旅游服务中心", "property", "960.00", "partial_leased",
                companyId, "江苏省", "淮安市", "洪泽区", "老子山镇景区入口服务中心",
                "118.7125000", "33.1856000", null);
    }

    private Project ensureProject(Long companyId, String name, String address, String lng, String lat) {
        Project existing = projectMapper.selectOne(
                new LambdaQueryWrapper<Project>().eq(Project::getName, name).last("LIMIT 1"));
        if (existing != null) {
            existing.setAddress(address);
            existing.setLongitude(new BigDecimal(lng));
            existing.setLatitude(new BigDecimal(lat));
            projectMapper.updateById(existing);
            return existing;
        }
        return project(companyId, name, address, lng, lat);
    }

    private void ensureAsset(Long projectId, String no, String name, String type, String area,
            String status, Long companyId, String province, String city, String district,
            String address, String lng, String lat, LocalDateTime vacantSince) {
        Asset existing = assetMapper.selectOne(
                new LambdaQueryWrapper<Asset>().eq(Asset::getAssetNo, no).last("LIMIT 1"));
        if (existing != null) {
            patchAssetGeoEntity(existing, projectId, province, city, district, address, lng, lat, vacantSince);
            return;
        }
        asset(projectId, no, name, type, area, status, companyId, companyId,
                province, city, district, address, lng, lat, vacantSince);
    }

    private void patchAssetGeo(String assetNo, Long projectId, String province, String city,
            String district, String address, String lng, String lat, LocalDateTime vacantSince) {
        Asset existing = assetMapper.selectOne(
                new LambdaQueryWrapper<Asset>().eq(Asset::getAssetNo, assetNo).last("LIMIT 1"));
        if (existing == null) {
            return;
        }
        patchAssetGeoEntity(existing, projectId, province, city, district, address, lng, lat, vacantSince);
    }

    private void patchAssetGeoEntity(Asset a, Long projectId, String province, String city,
            String district, String address, String lng, String lat, LocalDateTime vacantSince) {
        a.setProjectId(projectId);
        a.setProvince(province);
        a.setCity(city);
        a.setDistrict(district);
        a.setAddress(address);
        a.setLongitude(new BigDecimal(lng));
        a.setLatitude(new BigDecimal(lat));
        if (vacantSince != null) {
            a.setVacantSince(vacantSince);
            a.setVacantReason("退租");
        }
        assetMapper.updateById(a);
    }

    // ===== 构造辅助方法 =====

    private Company company(String name, Long parentId, String type) {
        Company c = new Company();
        c.setName(name);
        c.setParentId(parentId);
        c.setCompanyType(type);
        c.setStatus(1);
        companyMapper.insert(c);
        return c;
    }

    private Department department(Long companyId, String name) {
        Department d = new Department();
        d.setCompanyId(companyId);
        d.setName(name);
        d.setStatus(1);
        departmentMapper.insert(d);
        return d;
    }

    private User user(String username, String name, String phone, Long companyId, Long deptId,
            String roleCode) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode("admin123"));
        u.setName(name);
        u.setPhone(phone);
        u.setCompanyId(companyId);
        u.setDepartmentId(deptId);
        u.setStatus(1);
        userMapper.insert(u);
        // 绑定角色（roleCode → role.id 由 role 表已有 seed 提供，按 code 查询）
        Long roleId = resolveRoleId(roleCode);
        if (roleId != null) {
            UserRole ur = new UserRole();
            ur.setUserId(u.getId());
            ur.setRoleId(roleId);
            userRoleMapper.insert(ur);
        }
        return u;
    }

    /** 用户端门户账号：绑定 tenant_id，无员工角色 */
    private User tenantUser(String username, String name, String phone, Long tenantId) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode("admin123"));
        u.setName(name);
        u.setPhone(phone);
        u.setTenantId(tenantId);
        u.setStatus(1);
        userMapper.insert(u);
        return u;
    }

    private Long resolveRoleId(String roleCode) {
        // role 表 seed 数据在 V2__init_schema.sql 中，id 顺序固定：
        // 1 super_admin, 2 operator, 3 asset_mgr, 4 finance, 5 leader, 6 maintenance, 7 approver, 8 clerk
        return switch (roleCode) {
            case "super_admin" -> 1L;
            case "operator" -> 2L;
            case "asset_mgr" -> 3L;
            case "finance" -> 4L;
            case "leader" -> 5L;
            case "maintenance" -> 6L;
            case "approver" -> 7L;
            case "clerk" -> 8L;
            default -> null;
        };
    }

    private Project project(Long companyId, String name, String address, String lng, String lat) {
        Project p = new Project();
        p.setCompanyId(companyId);
        p.setName(name);
        p.setAddress(address);
        p.setLongitude(new BigDecimal(lng));
        p.setLatitude(new BigDecimal(lat));
        p.setStatus(1);
        projectMapper.insert(p);
        return p;
    }

    private Asset asset(Long projectId, String no, String name, String type, String area,
            String status, Long propertyCompanyId, Long operatingCompanyId,
            String province, String city, String district, String address,
            String lng, String lat, LocalDateTime vacantSince) {
        Asset a = new Asset();
        a.setProjectId(projectId);
        a.setAssetNo(no);
        a.setName(name);
        a.setAssetType(type);
        a.setArea(new BigDecimal(area));
        a.setSourceType("self");
        a.setOwnershipType("own");
        a.setPropertyCompanyId(propertyCompanyId);
        a.setOperatingCompanyId(operatingCompanyId);
        a.setLeaseControlStatus(status);
        a.setProvince(province);
        a.setCity(city);
        a.setDistrict(district);
        a.setAddress(address);
        a.setLongitude(new BigDecimal(lng));
        a.setLatitude(new BigDecimal(lat));
        if (vacantSince != null) {
            a.setVacantSince(vacantSince);
            a.setVacantReason("退租");
        }
        a.setStructureStatus("active");
        a.setVersion(0);
        assetMapper.insert(a);
        return a;
    }

    private void certificate(Long assetId, String certType, String certNo, String owner) {
        AssetCertificate c = new AssetCertificate();
        c.setAssetId(assetId);
        c.setCertType(certType);
        c.setCertNo(certNo);
        c.setOwnerName(owner);
        c.setMortgageStatus("none");
        certificateMapper.insert(c);
    }

    private void mortgage(Long assetId, String mortgagee, String amount, LocalDate start,
            LocalDate end, String status) {
        Mortgage m = new Mortgage();
        m.setAssetId(assetId);
        m.setMortgagee(mortgagee);
        m.setAmount(new BigDecimal(amount));
        m.setStartDate(start);
        m.setEndDate(end);
        m.setStatus(status);
        mortgageMapper.insert(m);
    }

    private Tenant tenant(String name, String phone, String type, boolean blacklist, int credit) {
        Tenant t = new Tenant();
        t.setName(name);
        t.setPhone(phone);
        t.setTenantType(type);
        t.setBlacklist(blacklist);
        t.setCreditScore(credit);
        t.setStatus(1);
        tenantMapper.insert(t);
        return t;
    }

    private void creditLog(Long tenantId, String eventType, int delta, String remark) {
        TenantCreditLog l = new TenantCreditLog();
        l.setTenantId(tenantId);
        l.setEventType(eventType);
        l.setScoreDelta(delta);
        l.setRemark(remark);
        l.setCreatedAt(LocalDateTime.now());
        creditLogMapper.insert(l);
    }

    private void listing(Long assetId, String rentAmount, boolean negotiable) {
        LeaseListing l = new LeaseListing();
        l.setAssetId(assetId);
        l.setRentAmount(rentAmount == null ? null : new BigDecimal(rentAmount));
        l.setRentNegotiable(negotiable);
        l.setStatus("active");
        l.setPublishedAt(LocalDateTime.now());
        listingMapper.insert(l);
    }

    private TenderAnnouncement announcement(String title, List<Long> assetIds,
            LocalDate deadline, int displayDays) {
        TenderAnnouncement a = new TenderAnnouncement();
        a.setTitle(title);
        a.setAssetIds(assetIds.toString().replace("[", "{").replace("]", "}"));
        a.setRegisterDeadline(deadline.atStartOfDay());
        a.setDisplayPeriodDays(displayDays);
        a.setStatus("open");
        announcementMapper.insert(a);
        return a;
    }

    private void application(Long announcementId, Long tenantId, String auditStatus,
            boolean depositPaid, String depositAmount, boolean refunded, Integer rankNo, String comment) {
        TenderApplication a = new TenderApplication();
        a.setAnnouncementId(announcementId);
        a.setTenantId(tenantId);
        a.setAuditStatus(auditStatus);
        a.setDepositPaid(depositPaid);
        a.setDepositAmount(depositAmount == null ? null : new BigDecimal(depositAmount));
        a.setDepositRefunded(refunded);
        a.setRankNo(rankNo);
        a.setAuditComment(comment);
        applicationMapper.insert(a);
    }

    private Contract contract(Long assetId, Long tenantId, String no, LocalDate start, LocalDate end,
            String rentAmount, String depositAmount, String cycle) {
        Contract c = new Contract();
        c.setContractNo(no);
        c.setAssetId(assetId);
        c.setTenantId(tenantId);
        c.setVersion(1);
        c.setStartDate(start);
        c.setEndDate(end);
        c.setLeaseArea(new BigDecimal("1200.00"));
        c.setRentType("fixed_monthly");
        c.setRentAmount(new BigDecimal(rentAmount));
        c.setDepositAmount(new BigDecimal(depositAmount));
        c.setPrepayAmount(BigDecimal.ZERO);
        c.setPaymentCycle(cycle);
        c.setFreeRentDays(0);
        c.setIncreaseRate(BigDecimal.ZERO);
        c.setGraceDays(0);
        c.setProrationBase("calendar");
        c.setStatus("active");
        c.setPaymentStatus("partial_paid");
        contractMapper.insert(c);
        return c;
    }

    private void plan(Long contractId, int no, LocalDate start, LocalDate end, String amount,
            LocalDate dueDate) {
        PaymentPlan p = new PaymentPlan();
        p.setContractId(contractId);
        p.setPeriodNo(no);
        p.setPeriodStart(start);
        p.setPeriodEnd(end);
        p.setPlannedAmount(new BigDecimal(amount));
        p.setDueDate(dueDate);
        p.setStatus(no <= 8 ? "issued" : "pending");
        planMapper.insert(p);
    }

    private Long bill(Long contractId, Long assetId, Long tenantId, String no, LocalDate start,
            LocalDate end, String amount, BigDecimal paid, String status, int dunningLevel) {
        Bill b = new Bill();
        b.setBillNo(no);
        b.setContractId(contractId);
        b.setAssetId(assetId);
        b.setTenantId(tenantId);
        b.setBillType("rent");
        b.setPeriodStart(start);
        b.setPeriodEnd(end);
        b.setDueDate(start);
        b.setAmount(new BigDecimal(amount));
        b.setPaidAmount(paid);
        b.setLateFeeAmount(BigDecimal.ZERO);
        b.setLateFeePaidAmount(BigDecimal.ZERO);
        b.setStatus(status);
        b.setDunningLevel(dunningLevel);
        b.setSource("system");
        billMapper.insert(b);
        return b.getId();
    }

    private Payment payment(Long contractId, Long tenantId, String no, String amount,
            String method, String channel, String confirmStatus) {
        Payment p = new Payment();
        p.setPaymentNo(no);
        p.setContractId(contractId);
        p.setTenantId(tenantId);
        p.setAmount(new BigDecimal(amount));
        p.setMethod(method);
        p.setChannel(channel);
        p.setConfirmStatus(confirmStatus);
        p.setConfirmedAt("confirmed".equals(confirmStatus) ? LocalDateTime.now() : null);
        p.setPaidAt(LocalDateTime.now());
        p.setSource("system");
        paymentMapper.insert(p);
        return p;
    }

    private void billPayment(Long billId, Long paymentId, String amountType, String amount) {
        BillPayment bp = new BillPayment();
        bp.setBillId(billId);
        bp.setPaymentId(paymentId);
        bp.setAmountType(amountType);
        bp.setAmount(new BigDecimal(amount));
        bp.setAllocatedAt(LocalDateTime.now());
        billPaymentMapper.insert(bp);
    }

    private void prepay(Long contractId, Long tenantId, String amount, String used, String balance,
            String remark) {
        Prepay p = new Prepay();
        p.setContractId(contractId);
        p.setTenantId(tenantId);
        p.setAmount(new BigDecimal(amount));
        p.setUsedAmount(new BigDecimal(used));
        p.setBalance(new BigDecimal(balance));
        p.setRemark(remark);
        prepayMapper.insert(p);
    }

    private void depositTx(Long contractId, String type, String amount, Long refBillId, String remark) {
        DepositTransaction d = new DepositTransaction();
        d.setContractId(contractId);
        d.setType(type);
        d.setAmount(new BigDecimal(amount));
        d.setRefBillId(refBillId);
        d.setRemark(remark);
        d.setCreatedAt(LocalDateTime.now());
        depositTxMapper.insert(d);
    }

    private InvoiceTitle invoiceTitle(Long tenantId, String title, String taxNo) {
        InvoiceTitle t = new InvoiceTitle();
        t.setTenantId(tenantId);
        t.setTitle(title);
        t.setTaxNo(taxNo);
        t.setStatus(1);
        invoiceTitleMapper.insert(t);
        return t;
    }

    private void invoice(String no, Long paymentId, Long titleId, String amount, String taxRate,
            String status) {
        Invoice i = new Invoice();
        i.setInvoiceNo(no);
        i.setPaymentId(paymentId);
        i.setTitleId(titleId);
        i.setAmount(new BigDecimal(amount));
        i.setTaxRate(new BigDecimal(taxRate));
        i.setStatus(status);
        invoiceMapper.insert(i);
    }

    private MaintenanceVendor vendor(String name, String contact, String phone) {
        MaintenanceVendor v = new MaintenanceVendor();
        v.setName(name);
        v.setContact(contact);
        v.setPhone(phone);
        v.setStatus(1);
        vendorMapper.insert(v);
        return v;
    }

    private void repair(Long assetId, String desc, String status, Long vendorId, Long assigneeId) {
        RepairOrder r = new RepairOrder();
        r.setAssetId(assetId);
        r.setReporterName("张三");
        r.setReporterPhone("13800000001");
        r.setDescription(desc);
        r.setStatus(status);
        r.setVendorId(vendorId);
        r.setAssigneeId(assigneeId);
        repairMapper.insert(r);
    }

    private void inspection(Long assetId, Long inspectorId, String result, String hazard) {
        InspectionRecord r = new InspectionRecord();
        r.setAssetId(assetId);
        r.setInspectorId(inspectorId);
        r.setPlanDate(LocalDate.now());
        r.setResult(result);
        r.setHazardDesc(hazard);
        r.setStatus("done");
        inspectionMapper.insert(r);
    }

    private void task(String type, String refNo, Long companyId, Long assigneeId,
            LocalDateTime deadline, String status) {
        Task t = new Task();
        t.setTaskType(type);
        t.setRefNo(refNo);
        t.setCompanyId(companyId);
        t.setAssigneeId(assigneeId);
        t.setDeadline(deadline);
        t.setStatus(status);
        t.setOverdueMinutes(0L);
        taskMapper.insert(t);
    }

    private void alert(Long companyId, String type, int level, String bizType, Long bizId,
            String title, String content, String status, Long assigneeId) {
        AlertRecord a = new AlertRecord();
        a.setCompanyId(companyId);
        a.setAlertType(type);
        a.setLevel(level);
        a.setBizType(bizType);
        a.setBizId(bizId);
        a.setTitle(title);
        a.setContent(content);
        a.setStatus(status);
        a.setAssigneeId(assigneeId);
        a.setCreatedAt(LocalDateTime.now());
        alertRecordMapper.insert(a);
    }

    private void notification(Long userId, String title, String content, String bizType,
            Long bizId, String channel) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setTitle(title);
        n.setContent(content);
        n.setBizType(bizType);
        n.setBizId(bizId);
        n.setChannel(channel);
        n.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(n);
    }

    private void businessPlan(Long companyId, Long projectId, int year, int month) {
        BusinessPlan b = new BusinessPlan();
        b.setCompanyId(companyId);
        b.setProjectId(projectId);
        b.setPlanYear(year);
        b.setPlanMonth(month);
        b.setTargetRentalRate(new BigDecimal("0.95"));
        b.setTargetCollectionRate(new BigDecimal("0.90"));
        b.setTargetIncome(new BigDecimal("1200000.00"));
        b.setTargetVacantArea(new BigDecimal("800.00"));
        b.setVersion(1);
        b.setStatus("active");
        businessPlanMapper.insert(b);
    }

    private void revitalization(Long assetId, String reason, String planType, Long assigneeId,
            LocalDate target, String status) {
        RevitalizationTask r = new RevitalizationTask();
        r.setAssetId(assetId);
        r.setVacantReason(reason);
        r.setPlanType(planType);
        r.setAssigneeId(assigneeId);
        r.setTargetDate(target);
        r.setStatus(status);
        revitalizationMapper.insert(r);
    }

    private void disposal(Long assetId, String type, String reason, String assessed, String book,
            String status) {
        DisposalOrder d = new DisposalOrder();
        d.setAssetId(assetId);
        d.setDisposalType(type);
        d.setReason(reason);
        d.setAssessedValue(new BigDecimal(assessed));
        d.setBookValue(new BigDecimal(book));
        d.setStatus(status);
        disposalMapper.insert(d);
    }

    private void occupation(Long assetId, String reason, String dept, LocalDate start, LocalDate end,
            String status) {
        OccupationOrder o = new OccupationOrder();
        o.setAssetId(assetId);
        o.setReason(reason);
        o.setDepartment(dept);
        o.setStartDate(start);
        o.setEndDate(end);
        o.setStatus(status);
        occupationMapper.insert(o);
    }

    private void selfUse(Long assetId, String dept, String purpose, LocalDate start, LocalDate end,
            String status) {
        SelfUseOrder s = new SelfUseOrder();
        s.setAssetId(assetId);
        s.setDepartment(dept);
        s.setPurpose(purpose);
        s.setStartDate(start);
        s.setEndDate(end);
        s.setStatus(status);
        selfUseMapper.insert(s);
    }

    private void evaluation(Long assetId, String purpose, String institution, String status,
            String resultValue) {
        EvaluationRequest e = new EvaluationRequest();
        e.setAssetId(assetId);
        e.setPurpose(purpose);
        e.setInstitution(institution);
        e.setStatus(status);
        e.setResultValue(new BigDecimal(resultValue));
        evaluationMapper.insert(e);
    }

    private void transfer(Long assetId, Long from, Long to, String type, String status) {
        AssetTransfer t = new AssetTransfer();
        t.setAssetId(assetId);
        t.setFromCompanyId(from);
        t.setToCompanyId(to);
        t.setTransferType(type);
        t.setStatus(status);
        transferMapper.insert(t);
    }

    private void migrationBatch(LocalDate cutover, String status, BigDecimal balance) {
        MigrationBatch m = new MigrationBatch();
        m.setCutoverDate(cutover);
        m.setStatus(status);
        m.setSourceFile("期初台账-20260101.xlsx");
        m.setBalanceResult(balance);
        m.setCreatedAt(LocalDateTime.now());
        m.setLockedAt("locked".equals(status) ? LocalDateTime.now() : null);
        migrationBatchMapper.insert(m);
    }

    private void configVersion(String key, String value, LocalDate effective, int version) {
        ConfigVersion c = new ConfigVersion();
        c.setConfigKey(key);
        c.setConfigValue(value);
        c.setEffectiveDate(effective);
        c.setVersion(version);
        c.setNewValue(value);
        c.setCreatedAt(LocalDateTime.now());
        configVersionMapper.insert(c);
    }

    private void regulation(String type, String period, String status) {
        RegulationReport r = new RegulationReport();
        r.setReportType(type);
        r.setPeriod(period);
        r.setContentJson("{\"assetTotal\": 5}");
        r.setStatus(status);
        r.setCreatedAt(LocalDateTime.now());
        regulationMapper.insert(r);
    }

    private Meter meter(Long assetId, Long contractId, String type, String no) {
        Meter m = new Meter();
        m.setAssetId(assetId);
        m.setContractId(contractId);
        m.setMeterType(type);
        m.setMeterNo(no);
        m.setMultiplier(new BigDecimal("1"));
        m.setStatus(1);
        meterMapper.insert(m);
        return m;
    }

    private void meterReading(Long meterId, String reading, LocalDate date) {
        MeterReading r = new MeterReading();
        r.setMeterId(meterId);
        r.setReading(new BigDecimal(reading));
        r.setReadingDate(date);
        r.setCreatedAt(LocalDateTime.now());
        meterReadingMapper.insert(r);
    }

    private void bankFlow(String no, String amount, String direction, LocalDate date, String summary,
            String matchStatus) {
        BankFlow b = new BankFlow();
        b.setFlowNo(no);
        b.setAmount(new BigDecimal(amount));
        b.setDirection(direction);
        b.setTradeDate(date);
        b.setSummary(summary);
        b.setMatchStatus(matchStatus);
        b.setCreatedAt(LocalDateTime.now());
        bankFlowMapper.insert(b);
    }

    private void voucher(String bizType, Long bizId, String no, String status) {
        FinanceVoucher v = new FinanceVoucher();
        v.setBizType(bizType);
        v.setBizId(bizId);
        v.setVoucherNo(no);
        v.setStatus(status);
        v.setCreatedAt(LocalDateTime.now());
        voucherMapper.insert(v);
    }

    private void fixedAsset(String no, String name, String type, String originalValue, Long companyId) {
        FixedAsset f = new FixedAsset();
        f.setAssetNo(no);
        f.setName(name);
        f.setAssetType(type);
        f.setOriginalValue(new BigDecimal(originalValue));
        f.setCompanyId(companyId);
        f.setStatus("in_use");
        fixedAssetMapper.insert(f);
    }

    private void intangible(String no, String name, String type, String originalValue, LocalDate expiry) {
        IntangibleAsset i = new IntangibleAsset();
        i.setAssetNo(no);
        i.setName(name);
        i.setRightsType(type);
        i.setOriginalValue(new BigDecimal(originalValue));
        i.setExpiryDate(expiry);
        i.setStatus("active");
        intangibleMapper.insert(i);
    }
}
