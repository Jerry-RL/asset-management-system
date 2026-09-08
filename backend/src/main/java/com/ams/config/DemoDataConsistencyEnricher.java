package com.ams.config;

import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.AssetCertificate;
import com.ams.modules.asset.entity.LeaseControlLog;
import com.ams.modules.asset.entity.Mortgage;
import com.ams.modules.asset.mapper.AssetCertificateMapper;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.LeaseControlLogMapper;
import com.ams.modules.asset.mapper.MortgageMapper;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.entity.BillPayment;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.billing.mapper.BillPaymentMapper;
import com.ams.modules.billing.mapper.PaymentMapper;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.entity.VacateOrder;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.contract.mapper.VacateOrderMapper;
import com.ams.modules.dunning.entity.DunningRecord;
import com.ams.modules.dunning.mapper.DunningRecordMapper;
import com.ams.modules.lease.entity.LeaseListing;
import com.ams.modules.lease.entity.Tenant;
import com.ams.modules.lease.mapper.LeaseListingMapper;
import com.ams.modules.lease.mapper.TenantMapper;
import com.ams.modules.maintenance.entity.InspectionRecord;
import com.ams.modules.maintenance.entity.RepairOrder;
import com.ams.modules.maintenance.mapper.InspectionRecordMapper;
import com.ams.modules.maintenance.mapper.RepairOrderMapper;
import com.ams.modules.meter.entity.Meter;
import com.ams.modules.meter.mapper.MeterMapper;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.pricing.entity.PaymentPlan;
import com.ams.modules.pricing.mapper.PaymentPlanMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 演示数据一致性补齐（在 DemoDataSeeder 之后执行）。
 *
 * <p>规则：
 * <ul>
 *   <li>租控状态与合同/招租/占用单据对齐</li>
 *   <li>在租资产必须有生效合同与匹配账单</li>
 *   <li>账单本金 = 已收 + 欠费；收款合计 = 核销合计</li>
 *   <li>租控变更写入 lease_control_log；欠费写入催缴记录</li>
 *   <li>补齐用户端/工作端演示账号（手机号、租户门户用户）</li>
 * </ul>
 */
@Component
@Profile("demo")
@Order(200)
public class DemoDataConsistencyEnricher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataConsistencyEnricher.class);

    /** 工作端员工演示手机号（微信绑定用） */
    private static final Map<String, String> WORKER_PHONES = Map.of(
            "admin", "13900000001",
            "operator", "13900000002",
            "assetmgr", "13900000003",
            "finance", "13900000004",
            "leader", "13900000005",
            "maintenance", "13900000006",
            "approver", "13900000007",
            "clerk", "13900000008");

    private final AssetMapper assetMapper;
    private final LeaseControlLogMapper leaseControlLogMapper;
    private final ContractMapper contractMapper;
    private final VacateOrderMapper vacateOrderMapper;
    private final BillMapper billMapper;
    private final PaymentMapper paymentMapper;
    private final BillPaymentMapper billPaymentMapper;
    private final PaymentPlanMapper paymentPlanMapper;
    private final DunningRecordMapper dunningRecordMapper;
    private final TenantMapper tenantMapper;
    private final LeaseListingMapper listingMapper;
    private final AssetCertificateMapper certificateMapper;
    private final MortgageMapper mortgageMapper;
    private final MeterMapper meterMapper;
    private final RepairOrderMapper repairMapper;
    private final InspectionRecordMapper inspectionMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public DemoDataConsistencyEnricher(
            AssetMapper assetMapper,
            LeaseControlLogMapper leaseControlLogMapper,
            ContractMapper contractMapper,
            VacateOrderMapper vacateOrderMapper,
            BillMapper billMapper,
            PaymentMapper paymentMapper,
            BillPaymentMapper billPaymentMapper,
            PaymentPlanMapper paymentPlanMapper,
            DunningRecordMapper dunningRecordMapper,
            TenantMapper tenantMapper,
            LeaseListingMapper listingMapper,
            AssetCertificateMapper certificateMapper,
            MortgageMapper mortgageMapper,
            MeterMapper meterMapper,
            RepairOrderMapper repairMapper,
            InspectionRecordMapper inspectionMapper,
            UserMapper userMapper,
            PasswordEncoder passwordEncoder) {
        this.assetMapper = assetMapper;
        this.leaseControlLogMapper = leaseControlLogMapper;
        this.contractMapper = contractMapper;
        this.vacateOrderMapper = vacateOrderMapper;
        this.billMapper = billMapper;
        this.paymentMapper = paymentMapper;
        this.billPaymentMapper = billPaymentMapper;
        this.paymentPlanMapper = paymentPlanMapper;
        this.dunningRecordMapper = dunningRecordMapper;
        this.tenantMapper = tenantMapper;
        this.listingMapper = listingMapper;
        this.certificateMapper = certificateMapper;
        this.mortgageMapper = mortgageMapper;
        this.meterMapper = meterMapper;
        this.repairMapper = repairMapper;
        this.inspectionMapper = inspectionMapper;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (assetMapper.selectCount(null) == 0) {
            log.info("[demo-enrich] 无资产数据，跳过一致性补齐");
            return;
        }
        log.info("[demo-enrich] 开始补齐跨模块一致演示数据…");
        ensureDemoPortalAccounts();
        ensureAssetStructureStatus();
        ensureLeaseControlHistories();
        ensureSatelliteLeaseChains();
        ensureVacantHistory();
        ensureDunningForArrears();
        ensureListingsForLeasing();
        ensureCertificatesAndMeters();
        ensureOpsRecords();
        assertMoneyConsistency();
        log.info("[demo-enrich] 跨模块一致性补齐完成");
    }

    /**
     * 幂等补齐：工作端员工手机号 + 用户端租户门户账号（密码 admin123）。
     */
    private void ensureDemoPortalAccounts() {
        int phoneFixed = 0;
        for (Map.Entry<String, String> e : WORKER_PHONES.entrySet()) {
            User u = userMapper.selectOne(
                    new LambdaQueryWrapper<User>().eq(User::getUsername, e.getKey()));
            if (u == null) {
                continue;
            }
            if (u.getPhone() == null || u.getPhone().isBlank()) {
                u.setPhone(e.getValue());
                userMapper.updateById(u);
                phoneFixed++;
            }
        }

        // username -> tenant phone
        Map<String, String> portalAccounts = new LinkedHashMap<>();
        portalAccounts.put("tenant", "13800000001");
        portalAccounts.put("tenant_corp", "13800000002");

        int portalCreated = 0;
        for (Map.Entry<String, String> e : portalAccounts.entrySet()) {
            String username = e.getKey();
            String phone = e.getValue();
            User existing = userMapper.selectOne(
                    new LambdaQueryWrapper<User>().eq(User::getUsername, username));
            if (existing != null) {
                if (existing.getTenantId() == null) {
                    Tenant t = tenantMapper.selectOne(
                            new LambdaQueryWrapper<Tenant>().eq(Tenant::getPhone, phone));
                    if (t != null) {
                        existing.setTenantId(t.getId());
                        existing.setPhone(phone);
                        userMapper.updateById(existing);
                    }
                }
                continue;
            }
            Tenant t = tenantMapper.selectOne(
                    new LambdaQueryWrapper<Tenant>().eq(Tenant::getPhone, phone));
            if (t == null) {
                continue;
            }
            User byTenant = userMapper.selectOne(
                    new LambdaQueryWrapper<User>().eq(User::getTenantId, t.getId()));
            if (byTenant != null) {
                // 微信自动创建的 wx_t_* 门户用户，改名/重置为演示账号
                if (byTenant.getUsername() != null && byTenant.getUsername().startsWith("wx_t_")) {
                    byTenant.setUsername(username);
                    byTenant.setPasswordHash(passwordEncoder.encode("admin123"));
                    byTenant.setPhone(phone);
                    byTenant.setName(t.getName());
                    userMapper.updateById(byTenant);
                    portalCreated++;
                }
                continue;
            }
            User u = new User();
            u.setUsername(username);
            u.setPasswordHash(passwordEncoder.encode("admin123"));
            u.setName(t.getName());
            u.setPhone(phone);
            u.setTenantId(t.getId());
            u.setStatus(1);
            userMapper.insert(u);
            portalCreated++;
        }
        log.info("[demo-enrich] 演示账号补齐：员工手机号 {}，门户账号新建 {}", phoneFixed, portalCreated);
    }

    private void ensureAssetStructureStatus() {
        List<Asset> assets = assetMapper.selectList(
                new LambdaQueryWrapper<Asset>().isNull(Asset::getStructureStatus));
        for (Asset a : assets) {
            a.setStructureStatus("active");
            assetMapper.updateById(a);
        }
    }

    /** 为各资产补齐与当前租控状态匹配的变更履历（幂等：按资产已有日志跳过）。 */
    private void ensureLeaseControlHistories() {
        Long operatorId = firstUserId();
        for (Asset asset : assetMapper.selectList(null)) {
            long exists = leaseControlLogMapper.selectCount(
                    new LambdaQueryWrapper<LeaseControlLog>().eq(LeaseControlLog::getAssetId, asset.getId()));
            if (exists > 0) {
                continue;
            }
            String status = asset.getLeaseControlStatus();
            if (status == null) {
                continue;
            }
            LocalDateTime base = LocalDateTime.of(2026, 1, 5, 10, 0);
            switch (status) {
                case "leased" -> {
                    insertLc(asset.getId(), "vacant", "leasing", "listing", null, operatorId,
                            "发布招租", base);
                    insertLc(asset.getId(), "leasing", "leased", "contract", null, operatorId,
                            "合同生效", base.plusDays(20));
                }
                case "partial_leased" -> {
                    insertLc(asset.getId(), "vacant", "leasing", "listing", null, operatorId,
                            "发布招租", base);
                    insertLc(asset.getId(), "leasing", "partial_leased", "contract", null, operatorId,
                            "部分面积签约", base.plusDays(25));
                }
                case "leasing" -> insertLc(asset.getId(), "vacant", "leasing", "listing", null, operatorId,
                        "发布招租", base.plusMonths(7));
                case "vacant" -> {
                    insertLc(asset.getId(), "vacant", "leasing", "listing", null, operatorId,
                            "历史招租", base.minusMonths(8));
                    insertLc(asset.getId(), "leasing", "leased", "contract", null, operatorId,
                            "历史签约", base.minusMonths(7));
                    insertLc(asset.getId(), "leased", "vacating", "vacate", null, operatorId,
                            "发起退租", base.minusMonths(2));
                    insertLc(asset.getId(), "vacating", "vacant", "vacate", null, operatorId,
                            "清场完成空置", base.minusMonths(1));
                }
                case "self_use" -> insertLc(asset.getId(), "vacant", "self_use", "self_use", null, operatorId,
                        "转为自用", base);
                case "occupied" -> insertLc(asset.getId(), "vacant", "occupied", "occupation", null, operatorId,
                        "临时占用", base.plusMonths(7));
                case "vacating" -> {
                    insertLc(asset.getId(), "vacant", "leasing", "listing", null, operatorId,
                            "招租", base);
                    insertLc(asset.getId(), "leasing", "leased", "contract", null, operatorId,
                            "签约", base.plusDays(15));
                    insertLc(asset.getId(), "leased", "vacating", "vacate", null, operatorId,
                            "退租中", LocalDateTime.now().minusDays(3));
                }
                default -> {
                }
            }
        }
    }

    /**
     * 为「在租/部分出租」且尚无合同的资产补齐合同+计划+账单+收款，保证金额勾稽。
     */
    private void ensureSatelliteLeaseChains() {
        Tenant tenant = tenantMapper.selectOne(
                new LambdaQueryWrapper<Tenant>().eq(Tenant::getPhone, "13800000002").last("LIMIT 1"));
        if (tenant == null) {
            tenant = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>().last("LIMIT 1"));
        }
        if (tenant == null) {
            return;
        }

        // assetNo -> monthly rent
        Map<String, String> leasedRents = Map.of(
                "AST-HA-101", "8000.00",
                "AST-HA-201", "25000.00",
                "AST-HA-301", "35000.00",
                "AST-HA-401", "5000.00",
                "AST-HA-402", "12000.00");

        for (Map.Entry<String, String> e : leasedRents.entrySet()) {
            Asset asset = findAsset(e.getKey());
            if (asset == null) {
                continue;
            }
            long contractCnt = contractMapper.selectCount(
                    new LambdaQueryWrapper<Contract>().eq(Contract::getAssetId, asset.getId()));
            if (contractCnt > 0) {
                continue;
            }
            String rent = e.getValue();
            String contractNo = "CT-" + e.getKey().replace("AST-", "");
            int paidMonths = switch (e.getKey()) {
                case "AST-HA-101" -> 3;
                case "AST-HA-201" -> 2;
                case "AST-HA-301" -> 4;
                case "AST-HA-401" -> 6;
                case "AST-HA-402" -> 2;
                default -> 2;
            };
            int issuedMonths = paidMonths + (e.getKey().equals("AST-HA-301") ? 1 : 0);
            seedLeaseChain(asset, tenant.getId(), contractNo, rent, "15000.00",
                    LocalDate.of(2026, 1, 1), issuedMonths, paidMonths);
        }
    }

    /** 空置资产补历史终止合同 + 退租单，与租控履历一致。 */
    private void ensureVacantHistory() {
        Asset vacant = findAsset("AST-2026-002");
        if (vacant == null) {
            return;
        }
        long hist = contractMapper.selectCount(
                new LambdaQueryWrapper<Contract>()
                        .eq(Contract::getAssetId, vacant.getId())
                        .eq(Contract::getContractNo, "CT-2025-002"));
        if (hist > 0) {
            return;
        }
        Tenant tenant = tenantMapper.selectOne(
                new LambdaQueryWrapper<Tenant>().eq(Tenant::getPhone, "13800000001").last("LIMIT 1"));
        if (tenant == null) {
            return;
        }
        Contract c = new Contract();
        c.setContractNo("CT-2025-002");
        c.setAssetId(vacant.getId());
        c.setTenantId(tenant.getId());
        c.setVersion(1);
        c.setStartDate(LocalDate.of(2025, 3, 1));
        c.setEndDate(LocalDate.of(2026, 2, 28));
        c.setLeaseArea(vacant.getArea());
        c.setRentType("fixed_monthly");
        c.setRentAmount(new BigDecimal("9000.00"));
        c.setDepositAmount(new BigDecimal("18000.00"));
        c.setPrepayAmount(BigDecimal.ZERO);
        c.setPaymentCycle("monthly");
        c.setFreeRentDays(0);
        c.setIncreaseRate(BigDecimal.ZERO);
        c.setGraceDays(0);
        c.setProrationBase("calendar");
        c.setStatus("terminated");
        c.setPaymentStatus("paid");
        contractMapper.insert(c);

        VacateOrder v = new VacateOrder();
        v.setContractId(c.getId());
        v.setStatus("completed");
        v.setReason("合同到期不续租");
        v.setExpectedVacateDate(LocalDate.of(2026, 2, 28));
        v.setSettlementAmount(BigDecimal.ZERO);
        v.setDepositRefund(new BigDecimal("18000.00"));
        v.setSettledAt(LocalDateTime.of(2026, 3, 5, 15, 0));
        vacateOrderMapper.insert(v);
    }

    private void ensureDunningForArrears() {
        if (dunningRecordMapper.selectCount(null) > 0) {
            return;
        }
        Long operatorId = firstUserId();
        List<Bill> arrears = billMapper.selectList(
                new LambdaQueryWrapper<Bill>()
                        .in(Bill::getStatus, List.of("unpaid", "partial_paid")));
        for (Bill bill : arrears) {
            DunningRecord d = new DunningRecord();
            d.setBillId(bill.getId());
            d.setContractId(bill.getContractId());
            d.setTenantId(bill.getTenantId());
            d.setLevel(bill.getDunningLevel() != null && bill.getDunningLevel() > 0
                    ? bill.getDunningLevel() : 1);
            d.setMethod("sms");
            d.setContent("账单 " + bill.getBillNo() + " 逾期催缴提醒，请尽快缴费");
            d.setOperatorId(operatorId);
            d.setTenantFeedback("已知悉，本周内缴清");
            d.setResult("pending");
            d.setCreatedAt(LocalDateTime.now().minusDays(2));
            dunningRecordMapper.insert(d);

            if ("unpaid".equals(bill.getStatus())) {
                DunningRecord d2 = new DunningRecord();
                d2.setBillId(bill.getId());
                d2.setContractId(bill.getContractId());
                d2.setTenantId(bill.getTenantId());
                d2.setLevel(2);
                d2.setMethod("notice_post");
                d2.setContent("账单 " + bill.getBillNo() + " 二次催缴（张贴通知）");
                d2.setOperatorId(operatorId);
                d2.setResult("pending");
                d2.setCreatedAt(LocalDateTime.now().minusHours(6));
                dunningRecordMapper.insert(d2);
                if (bill.getDunningLevel() == null || bill.getDunningLevel() < 2) {
                    bill.setDunningLevel(2);
                    billMapper.updateById(bill);
                }
            }
        }
    }

    private void ensureListingsForLeasing() {
        for (Asset asset : assetMapper.selectList(
                new LambdaQueryWrapper<Asset>().eq(Asset::getLeaseControlStatus, "leasing"))) {
            long cnt = listingMapper.selectCount(
                    new LambdaQueryWrapper<LeaseListing>()
                            .eq(LeaseListing::getAssetId, asset.getId())
                            .eq(LeaseListing::getStatus, "active"));
            if (cnt > 0) {
                continue;
            }
            LeaseListing l = new LeaseListing();
            l.setAssetId(asset.getId());
            l.setRentAmount(new BigDecimal("6000.00"));
            l.setRentNegotiable(false);
            l.setStatus("active");
            l.setPublishedAt(LocalDateTime.now().minusDays(10));
            listingMapper.insert(l);
        }
        // 空置资产也保留招租信息（可议价）
        for (Asset asset : assetMapper.selectList(
                new LambdaQueryWrapper<Asset>().eq(Asset::getLeaseControlStatus, "vacant"))) {
            long cnt = listingMapper.selectCount(
                    new LambdaQueryWrapper<LeaseListing>().eq(LeaseListing::getAssetId, asset.getId()));
            if (cnt > 0) {
                continue;
            }
            LeaseListing l = new LeaseListing();
            l.setAssetId(asset.getId());
            l.setRentNegotiable(true);
            l.setStatus("active");
            l.setPublishedAt(LocalDateTime.now().minusDays(5));
            listingMapper.insert(l);
        }
    }

    private void ensureCertificatesAndMeters() {
        for (Asset asset : assetMapper.selectList(null)) {
            long certCnt = certificateMapper.selectCount(
                    new LambdaQueryWrapper<AssetCertificate>().eq(AssetCertificate::getAssetId, asset.getId()));
            if (certCnt == 0) {
                AssetCertificate c = new AssetCertificate();
                c.setAssetId(asset.getId());
                c.setCertType("property".equals(asset.getAssetType()) ? "property_cert" : "land_cert");
                c.setCertNo("CQZ-" + asset.getAssetNo());
                c.setOwnerName("淮安城投资产管理有限公司");
                c.setMortgageStatus("none");
                c.setRegisterDate(LocalDate.of(2024, 6, 1));
                certificateMapper.insert(c);
            }
            if ("property".equals(asset.getAssetType())) {
                long meterCnt = meterMapper.selectCount(
                        new LambdaQueryWrapper<Meter>().eq(Meter::getAssetId, asset.getId()));
                if (meterCnt == 0) {
                    Meter water = new Meter();
                    water.setAssetId(asset.getId());
                    water.setMeterType("water");
                    water.setMeterNo("SB-" + asset.getAssetNo());
                    water.setMultiplier(BigDecimal.ONE);
                    water.setStatus(1);
                    water.setShared(false);
                    meterMapper.insert(water);

                    Meter elec = new Meter();
                    elec.setAssetId(asset.getId());
                    elec.setMeterType("electric");
                    elec.setMeterNo("DB-" + asset.getAssetNo());
                    elec.setMultiplier(BigDecimal.ONE);
                    elec.setStatus(1);
                    elec.setShared(false);
                    meterMapper.insert(elec);
                }
            }
        }
        // 抵押仅挂在招租中的 AST-2026-003（若已有则跳过）
        Asset mortgaged = findAsset("AST-2026-003");
        if (mortgaged != null) {
            long mCnt = mortgageMapper.selectCount(
                    new LambdaQueryWrapper<Mortgage>().eq(Mortgage::getAssetId, mortgaged.getId()));
            if (mCnt == 0) {
                Mortgage m = new Mortgage();
                m.setAssetId(mortgaged.getId());
                m.setMortgagee("江苏银行淮安分行");
                m.setAmount(new BigDecimal("500000.00"));
                m.setStartDate(LocalDate.of(2026, 1, 1));
                m.setEndDate(LocalDate.of(2027, 1, 1));
                m.setStatus("active");
                mortgageMapper.insert(m);
            }
            List<AssetCertificate> certs = certificateMapper.selectList(
                    new LambdaQueryWrapper<AssetCertificate>().eq(AssetCertificate::getAssetId, mortgaged.getId()));
            for (AssetCertificate c : certs) {
                if (!"mortgaged".equals(c.getMortgageStatus())) {
                    c.setMortgageStatus("mortgaged");
                    certificateMapper.updateById(c);
                }
            }
        }
    }

    private void ensureOpsRecords() {
        Long inspectorId = firstUserId();
        Asset leased = findAsset("AST-2026-001");
        if (leased == null) {
            return;
        }
        long repairCnt = repairMapper.selectCount(
                new LambdaQueryWrapper<RepairOrder>().eq(RepairOrder::getAssetId, leased.getId()));
        if (repairCnt == 0) {
            RepairOrder r = new RepairOrder();
            r.setAssetId(leased.getId());
            r.setReporterName("张三");
            r.setReporterPhone("13800000001");
            r.setDescription("卫生间漏水");
            r.setStatus("pending_accept");
            repairMapper.insert(r);
        }
        long inspCnt = inspectionMapper.selectCount(
                new LambdaQueryWrapper<InspectionRecord>().eq(InspectionRecord::getAssetId, leased.getId()));
        if (inspCnt == 0) {
            InspectionRecord i = new InspectionRecord();
            i.setAssetId(leased.getId());
            i.setInspectorId(inspectorId);
            i.setPlanDate(LocalDate.now().minusDays(7));
            i.setResult("正常");
            i.setStatus("done");
            i.setCreatedAt(LocalDateTime.now().minusDays(7));
            inspectionMapper.insert(i);
        }
        // 给几个代表性资产再补巡查
        for (String no : List.of("AST-HA-101", "AST-HA-301", "AST-HA-401")) {
            Asset a = findAsset(no);
            if (a == null) {
                continue;
            }
            long cnt = inspectionMapper.selectCount(
                    new LambdaQueryWrapper<InspectionRecord>().eq(InspectionRecord::getAssetId, a.getId()));
            if (cnt > 0) {
                continue;
            }
            InspectionRecord i = new InspectionRecord();
            i.setAssetId(a.getId());
            i.setInspectorId(inspectorId);
            i.setPlanDate(LocalDate.now().minusDays(3));
            i.setResult("正常");
            i.setStatus("done");
            i.setCreatedAt(LocalDateTime.now().minusDays(3));
            inspectionMapper.insert(i);
        }
    }

    private void seedLeaseChain(
            Asset asset,
            Long tenantId,
            String contractNo,
            String monthlyRent,
            String deposit,
            LocalDate start,
            int issuedMonths,
            int paidMonths) {
        Contract c = new Contract();
        c.setContractNo(contractNo);
        c.setAssetId(asset.getId());
        c.setTenantId(tenantId);
        c.setVersion(1);
        c.setStartDate(start);
        c.setEndDate(start.plusYears(1).minusDays(1));
        c.setLeaseArea(asset.getArea());
        c.setRentType("fixed_monthly");
        c.setRentAmount(new BigDecimal(monthlyRent));
        c.setDepositAmount(new BigDecimal(deposit));
        c.setPrepayAmount(BigDecimal.ZERO);
        c.setPaymentCycle("monthly");
        c.setFreeRentDays(0);
        c.setIncreaseRate(BigDecimal.ZERO);
        c.setGraceDays(0);
        c.setProrationBase("calendar");
        c.setStatus("active");
        c.setPaymentStatus(paidMonths >= issuedMonths ? "paid" : "partial_paid");
        contractMapper.insert(c);

        BigDecimal rent = new BigDecimal(monthlyRent);
        for (int m = 1; m <= 12; m++) {
            LocalDate ps = start.plusMonths(m - 1);
            LocalDate pe = ps.withDayOfMonth(ps.lengthOfMonth());
            PaymentPlan plan = new PaymentPlan();
            plan.setContractId(c.getId());
            plan.setPeriodNo(m);
            plan.setPeriodStart(ps);
            plan.setPeriodEnd(pe);
            plan.setPlannedAmount(rent);
            plan.setDueDate(ps);
            plan.setStatus(m <= issuedMonths ? "issued" : "pending");
            paymentPlanMapper.insert(plan);
        }

        for (int m = 1; m <= issuedMonths; m++) {
            LocalDate ps = start.plusMonths(m - 1);
            LocalDate pe = ps.withDayOfMonth(ps.lengthOfMonth());
            boolean paid = m <= paidMonths;
            Bill b = new Bill();
            b.setBillNo(contractNo.replace("CT-", "ZD") + String.format("%02d", m));
            b.setContractId(c.getId());
            b.setAssetId(asset.getId());
            b.setTenantId(tenantId);
            b.setBillType("rent");
            b.setPeriodStart(ps);
            b.setPeriodEnd(pe);
            b.setDueDate(ps);
            b.setAmount(rent);
            b.setPaidAmount(paid ? rent : BigDecimal.ZERO);
            b.setReducedAmount(BigDecimal.ZERO);
            b.setLateFeeAmount(BigDecimal.ZERO);
            b.setLateFeePaidAmount(BigDecimal.ZERO);
            b.setStatus(paid ? "paid" : "unpaid");
            b.setDunningLevel(paid ? 0 : 1);
            b.setSource("system");
            billMapper.insert(b);

            if (paid) {
                Payment p = new Payment();
                p.setPaymentNo(contractNo.replace("CT-", "SK") + String.format("%02d", m));
                p.setContractId(c.getId());
                p.setTenantId(tenantId);
                p.setAmount(rent);
                p.setMethod("bank_transfer");
                p.setChannel("pc");
                p.setConfirmStatus("confirmed");
                p.setConfirmedAt(ps.plusDays(3).atStartOfDay());
                p.setPaidAt(ps.plusDays(2).atStartOfDay());
                p.setSource("system");
                paymentMapper.insert(p);

                BillPayment bp = new BillPayment();
                bp.setBillId(b.getId());
                bp.setPaymentId(p.getId());
                bp.setAmountType("principal");
                bp.setAmount(rent);
                bp.setAllocatedAt(ps.plusDays(3).atStartOfDay());
                billPaymentMapper.insert(bp);
            }
        }
        log.info("[demo-enrich] 补齐租赁链 {} / {}：出账 {} 期，已缴 {} 期",
                asset.getAssetNo(), contractNo, issuedMonths, paidMonths);
    }

    private void assertMoneyConsistency() {
        List<Bill> bills = billMapper.selectList(null);
        BigDecimal billAmt = BigDecimal.ZERO;
        BigDecimal billPaid = BigDecimal.ZERO;
        for (Bill b : bills) {
            billAmt = billAmt.add(nz(b.getAmount()));
            billPaid = billPaid.add(nz(b.getPaidAmount()));
        }
        BigDecimal paySum = BigDecimal.ZERO;
        for (Payment p : paymentMapper.selectList(null)) {
            paySum = paySum.add(nz(p.getAmount()));
        }
        BigDecimal allocSum = BigDecimal.ZERO;
        for (BillPayment bp : billPaymentMapper.selectList(null)) {
            allocSum = allocSum.add(nz(bp.getAmount()));
        }
        if (paySum.compareTo(allocSum) != 0) {
            log.warn("[demo-enrich] 收款({}) ≠ 核销({})，请检查", paySum, allocSum);
        } else {
            log.info("[demo-enrich] 金额勾稽 OK：账单本金={} 已收={} 收款={} 核销={}",
                    billAmt, billPaid, paySum, allocSum);
        }

        // 在租资产必须有生效合同
        for (Asset a : assetMapper.selectList(
                new LambdaQueryWrapper<Asset>()
                        .in(Asset::getLeaseControlStatus, List.of("leased", "partial_leased")))) {
            long active = contractMapper.selectCount(
                    new LambdaQueryWrapper<Contract>()
                            .eq(Contract::getAssetId, a.getId())
                            .in(Contract::getStatus, List.of("active", "expiring", "renewable")));
            if (active == 0) {
                log.warn("[demo-enrich] 在租资产 {} 缺少生效合同", a.getAssetNo());
            }
        }
    }

    private void insertLc(
            Long assetId, String from, String to, String bizType, Long bizId,
            Long operatorId, String remark, LocalDateTime at) {
        LeaseControlLog logRow = new LeaseControlLog();
        logRow.setAssetId(assetId);
        logRow.setFromStatus(from);
        logRow.setToStatus(to);
        logRow.setBizType(bizType);
        logRow.setBizId(bizId);
        logRow.setOperatorId(operatorId);
        logRow.setRemark(remark);
        logRow.setCreatedAt(at);
        leaseControlLogMapper.insert(logRow);
    }

    private Asset findAsset(String assetNo) {
        return assetMapper.selectOne(
                new LambdaQueryWrapper<Asset>().eq(Asset::getAssetNo, assetNo).last("LIMIT 1"));
    }

    private Long firstUserId() {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>().last("LIMIT 1"));
        return u == null ? null : u.getId();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
