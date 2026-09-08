package com.ams.modules.migration.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.billing.mapper.PaymentMapper;
import com.ams.modules.billing.service.PrepayService;
import com.ams.modules.contract.ContractStatus;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.entity.DepositTransaction;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.contract.mapper.DepositTransactionMapper;
import com.ams.modules.dunning.service.DunningService;
import com.ams.modules.lease.entity.Tenant;
import com.ams.modules.lease.mapper.TenantMapper;
import com.ams.modules.migration.entity.MigrationBatch;
import com.ams.modules.migration.entity.MigrationImportLog;
import com.ams.modules.migration.mapper.MigrationBatchMapper;
import com.ams.modules.migration.mapper.MigrationImportLogMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 期初数据迁移与建账（FR-MIG-*）。
 */
@Service
public class MigrationService {

    private final MigrationBatchMapper batchMapper;
    private final MigrationImportLogMapper importLogMapper;
    private final ContractMapper contractMapper;
    private final BillMapper billMapper;
    private final PaymentMapper paymentMapper;
    private final AssetMapper assetMapper;
    private final TenantMapper tenantMapper;
    private final DepositTransactionMapper depositTransactionMapper;
    private final PrepayService prepayService;
    private final DunningService dunningService;
    private final ObjectMapper objectMapper;

    public MigrationService(
            MigrationBatchMapper batchMapper,
            MigrationImportLogMapper importLogMapper,
            ContractMapper contractMapper,
            BillMapper billMapper,
            PaymentMapper paymentMapper,
            AssetMapper assetMapper,
            TenantMapper tenantMapper,
            DepositTransactionMapper depositTransactionMapper,
            PrepayService prepayService,
            DunningService dunningService,
            ObjectMapper objectMapper) {
        this.batchMapper = batchMapper;
        this.importLogMapper = importLogMapper;
        this.contractMapper = contractMapper;
        this.billMapper = billMapper;
        this.paymentMapper = paymentMapper;
        this.assetMapper = assetMapper;
        this.tenantMapper = tenantMapper;
        this.depositTransactionMapper = depositTransactionMapper;
        this.prepayService = prepayService;
        this.dunningService = dunningService;
        this.objectMapper = objectMapper;
    }

    public MigrationBatch createBatch(LocalDate cutoverDate, String sourceFile) {
        MigrationBatch batch = new MigrationBatch();
        batch.setCutoverDate(cutoverDate);
        batch.setSourceFile(sourceFile);
        batch.setStatus("importing");
        batch.setCreatedBy(SecurityUtils.currentUserIdOrNull());
        batch.setCreatedAt(LocalDateTime.now());
        batchMapper.insert(batch);
        return batch;
    }

    public List<MigrationBatch> listBatches() {
        return batchMapper.selectList(
                new LambdaQueryWrapper<MigrationBatch>().orderByDesc(MigrationBatch::getId));
    }

    public List<MigrationImportLog> listLogs(Long batchId) {
        return importLogMapper.selectList(
                new LambdaQueryWrapper<MigrationImportLog>()
                        .eq(batchId != null, MigrationImportLog::getBatchId, batchId)
                        .orderByDesc(MigrationImportLog::getId));
    }

    public MigrationImportLog logImport(Long batchId, Integer rowNo, String bizType,
            String result, String errorMsg, Long refId) {
        MigrationImportLog log = new MigrationImportLog();
        log.setBatchId(batchId);
        log.setRowNo(rowNo);
        log.setBizType(bizType);
        log.setResult(result);
        log.setErrorMsg(errorMsg);
        log.setRefId(refId);
        log.setCreatedAt(LocalDateTime.now());
        importLogMapper.insert(log);
        return log;
    }

    /**
     * 批量导入 JSON 行：bizType = contract|bill|deposit|prepay|tenant_credit
     */
    @Transactional
    public Map<String, Object> importRows(Long batchId, String bizType, List<Map<String, Object>> rows) {
        MigrationBatch batch = requireBatch(batchId);
        if ("locked".equals(batch.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "批次已锁定");
        }
        int ok = 0;
        int fail = 0;
        int rowNo = 0;
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            rowNo++;
            try {
                Long refId = switch (bizType) {
                    case "contract" -> importContract(row);
                    case "bill" -> importBill(row);
                    case "deposit" -> importDeposit(row);
                    case "prepay" -> importPrepay(row);
                    case "tenant_credit" -> importTenantCredit(row);
                    default -> throw new AppException(ErrorCode.BAD_REQUEST, "不支持的 bizType: " + bizType);
                };
                logImport(batchId, rowNo, bizType, "ok", null, refId);
                ok++;
            } catch (Exception e) {
                logImport(batchId, rowNo, bizType, "fail", e.getMessage(), null);
                fail++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);
        result.put("bizType", bizType);
        result.put("success", ok);
        result.put("failed", fail);
        return result;
    }

    @Transactional
    public Map<String, Object> initLeaseControl(Long batchId) {
        requireBatch(batchId);
        List<Contract> active = contractMapper.selectList(
                new LambdaQueryWrapper<Contract>().eq(Contract::getStatus, ContractStatus.ACTIVE));
        int updated = 0;
        for (Contract c : active) {
            if (c.getAssetId() == null) {
                continue;
            }
            Asset asset = assetMapper.selectById(c.getAssetId());
            if (asset == null) {
                continue;
            }
            if (!LeaseControlStatus.LEASED.equals(asset.getLeaseControlStatus())
                    && !LeaseControlStatus.PARTIAL_LEASED.equals(asset.getLeaseControlStatus())) {
                asset.setLeaseControlStatus(LeaseControlStatus.LEASED);
                assetMapper.updateById(asset);
                updated++;
            }
        }
        // 无合同空置
        List<Asset> assets = assetMapper.selectList(null);
        int vacant = 0;
        for (Asset a : assets) {
            long cnt = contractMapper.selectCount(
                    new LambdaQueryWrapper<Contract>()
                            .eq(Contract::getAssetId, a.getId())
                            .in(Contract::getStatus, ContractStatus.ACTIVE, ContractStatus.EXPIRING,
                                    ContractStatus.RENEWABLE));
            if (cnt == 0 && a.getLeaseControlStatus() == null) {
                a.setLeaseControlStatus(LeaseControlStatus.VACANT);
                a.setVacantReason("migration_init");
                assetMapper.updateById(a);
                vacant++;
            }
        }
        Map<String, Object> map = new HashMap<>();
        map.put("leasedUpdated", updated);
        map.put("vacantInited", vacant);
        logImport(batchId, null, "lease_control", "ok", "leased=" + updated + ",vacant=" + vacant, null);
        return map;
    }

    @Transactional
    public int initDunning(Long batchId) {
        requireBatch(batchId);
        List<Bill> bills = billMapper.selectList(
                new LambdaQueryWrapper<Bill>()
                        .eq(Bill::getSource, "migration")
                        .in(Bill::getStatus, BillStatus.UNPAID, BillStatus.PARTIAL_PAID));
        int n = 0;
        LocalDate today = LocalDate.now();
        for (Bill bill : bills) {
            if (bill.getDueDate() == null) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(bill.getDueDate(), today);
            int level = dunningService.levelFor(Math.max(days, 0));
            bill.setDunningLevel(level);
            billMapper.updateById(bill);
            n++;
        }
        logImport(batchId, null, "dunning", "ok", "updated=" + n, null);
        return n;
    }

    @Transactional
    public MigrationBatch reconcile(Long batchId) {
        MigrationBatch batch = requireBatch(batchId);
        BigDecimal totalReceivable = migrationReceivable();
        BigDecimal totalArrears = billArrears();
        BigDecimal totalPaid = paymentPaid();
        BigDecimal balance = totalReceivable.subtract(totalArrears.add(totalPaid));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("receivable", totalReceivable);
        report.put("arrears", totalArrears);
        report.put("paid", totalPaid);
        report.put("balance", balance);
        report.put("formula", "receivable = arrears + paid");
        report.put("balanced", balance.compareTo(BigDecimal.ZERO) == 0);
        report.put("cutoverDate", batch.getCutoverDate());
        report.put("checkedAt", LocalDateTime.now().toString());

        batch.setReceivableAmount(totalReceivable);
        batch.setArrearsAmount(totalArrears);
        batch.setPaidAmount(totalPaid);
        batch.setBalanceResult(balance);
        batch.setStatus("reconciled");
        try {
            batch.setReportJson(objectMapper.writeValueAsString(report));
        } catch (Exception e) {
            batch.setReportJson(report.toString());
        }
        batchMapper.updateById(batch);
        return batch;
    }

    @Transactional
    public MigrationBatch lockCutover(Long batchId) {
        MigrationBatch batch = requireBatch(batchId);
        if (batch.getBalanceResult() == null) {
            throw new AppException(ErrorCode.BUSINESS_ERROR, "请先执行试算平衡");
        }
        if (batch.getBalanceResult().compareTo(BigDecimal.ZERO) != 0) {
            throw new AppException(ErrorCode.BUSINESS_ERROR, "试算不平衡，不可锁定进入试运行");
        }
        batch.setStatus("locked");
        batch.setLockedAt(LocalDateTime.now());
        batchMapper.updateById(batch);
        return batch;
    }

    private Long importContract(Map<String, Object> row) {
        Contract c = new Contract();
        c.setContractNo(str(row, "contractNo", "MIG-" + UUID.randomUUID().toString().substring(0, 8)));
        c.setAssetId(lng(row, "assetId"));
        c.setTenantId(lng(row, "tenantId"));
        c.setStartDate(date(row, "startDate"));
        c.setEndDate(date(row, "endDate"));
        c.setRentAmount(dec(row, "rentAmount"));
        c.setDepositAmount(dec(row, "depositAmount"));
        c.setPrepayAmount(dec(row, "prepayAmount"));
        c.setLeaseArea(dec(row, "leaseArea"));
        c.setRentType(str(row, "rentType", "fixed_monthly"));
        c.setPaymentCycle(str(row, "paymentCycle", "monthly"));
        c.setStatus(ContractStatus.ACTIVE);
        c.setVersion(1);
        c.setPaymentStatus(str(row, "paymentStatus", "unpaid"));
        c.setRemark("source=migration");
        contractMapper.insert(c);
        return c.getId();
    }

    private Long importBill(Map<String, Object> row) {
        Bill bill = new Bill();
        bill.setBillNo(str(row, "billNo", "MIGB" + UUID.randomUUID().toString().substring(0, 8)));
        bill.setContractId(lng(row, "contractId"));
        bill.setAssetId(lng(row, "assetId"));
        bill.setTenantId(lng(row, "tenantId"));
        bill.setBillType(str(row, "billType", "rent"));
        bill.setDueDate(date(row, "dueDate"));
        bill.setPeriodStart(date(row, "periodStart"));
        bill.setPeriodEnd(date(row, "periodEnd"));
        bill.setAmount(dec(row, "amount"));
        bill.setPaidAmount(nz(dec(row, "paidAmount")));
        bill.setReducedAmount(BigDecimal.ZERO);
        bill.setLateFeeAmount(nz(dec(row, "lateFeeAmount")));
        bill.setLateFeePaidAmount(BigDecimal.ZERO);
        BigDecimal unpaid = nz(bill.getAmount()).subtract(nz(bill.getPaidAmount()));
        bill.setStatus(unpaid.compareTo(BigDecimal.ZERO) <= 0 ? BillStatus.PAID
                : (nz(bill.getPaidAmount()).compareTo(BigDecimal.ZERO) > 0 ? BillStatus.PARTIAL_PAID : BillStatus.UNPAID));
        bill.setSource("migration");
        bill.setDunningLevel(0);
        billMapper.insert(bill);
        return bill.getId();
    }

    private Long importDeposit(Map<String, Object> row) {
        DepositTransaction tx = new DepositTransaction();
        tx.setContractId(lng(row, "contractId"));
        tx.setType(str(row, "type", "collect"));
        tx.setAmount(dec(row, "amount"));
        tx.setRemark("source=migration;" + str(row, "remark", ""));
        tx.setCreatedBy(SecurityUtils.currentUserIdOrNull());
        tx.setCreatedAt(LocalDateTime.now());
        depositTransactionMapper.insert(tx);
        Contract c = contractMapper.selectById(tx.getContractId());
        if (c != null && "collect".equals(tx.getType())) {
            c.setDepositAmount(nz(c.getDepositAmount()).add(nz(tx.getAmount())));
            contractMapper.updateById(c);
        }
        return tx.getId();
    }

    private Long importPrepay(Map<String, Object> row) {
        var prepay = prepayService.add(lng(row, "contractId"), lng(row, "tenantId"),
                dec(row, "amount"), "source=migration");
        return prepay == null ? null : prepay.getId();
    }

    private Long importTenantCredit(Map<String, Object> row) {
        Long tenantId = lng(row, "tenantId");
        Tenant t = tenantMapper.selectById(tenantId);
        if (t == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "租户不存在");
        }
        if (row.get("creditScore") != null) {
            t.setCreditScore(Integer.parseInt(row.get("creditScore").toString()));
        }
        if (row.get("blacklist") != null) {
            t.setBlacklist(Boolean.parseBoolean(row.get("blacklist").toString()));
        }
        tenantMapper.updateById(t);
        return tenantId;
    }

    private BigDecimal migrationReceivable() {
        List<Bill> bills = billMapper.selectList(
                new LambdaQueryWrapper<Bill>().eq(Bill::getSource, "migration"));
        return bills.stream().map(b -> nz(b.getAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal billArrears() {
        List<Bill> bills = billMapper.selectList(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getSource, "migration"));
        return bills.stream()
                .map(b -> nz(b.getAmount()).subtract(nz(b.getPaidAmount())).subtract(nz(b.getReducedAmount())))
                .map(v -> v.max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal paymentPaid() {
        // 期初实收：账单已核销金额合计（迁移账单）
        List<Bill> bills = billMapper.selectList(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getSource, "migration"));
        BigDecimal fromBills = bills.stream().map(b -> nz(b.getPaidAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Payment> payments = paymentMapper.selectList(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getSource, "migration"));
        if (payments.isEmpty()) {
            return fromBills;
        }
        return payments.stream().map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private MigrationBatch requireBatch(Long id) {
        MigrationBatch batch = batchMapper.selectById(id);
        if (batch == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "迁移批次不存在");
        }
        return batch;
    }

    private static String str(Map<String, Object> row, String key, String def) {
        Object v = row.get(key);
        return v == null || v.toString().isBlank() ? def : v.toString();
    }

    private static Long lng(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : Long.valueOf(v.toString());
    }

    private static BigDecimal dec(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : new BigDecimal(v.toString());
    }

    private static LocalDate date(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : LocalDate.parse(v.toString());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
