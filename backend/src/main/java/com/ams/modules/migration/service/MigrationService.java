package com.ams.modules.migration.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.billing.mapper.PaymentMapper;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.migration.entity.MigrationBatch;
import com.ams.modules.migration.entity.MigrationImportLog;
import com.ams.modules.migration.mapper.MigrationBatchMapper;
import com.ams.modules.migration.mapper.MigrationImportLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 期初数据迁移与建账（FR-MIG-*，SRS §4.23.28）。
 * 统一基准日、staging 导入、试算平衡（期初应收 = 期初欠费 + 期初实收）、锁定进入试运行。
 */
@Service
public class MigrationService {

    private final MigrationBatchMapper batchMapper;
    private final MigrationImportLogMapper importLogMapper;
    private final ContractMapper contractMapper;
    private final BillMapper billMapper;
    private final PaymentMapper paymentMapper;

    public MigrationService(
            MigrationBatchMapper batchMapper,
            MigrationImportLogMapper importLogMapper,
            ContractMapper contractMapper,
            BillMapper billMapper,
            PaymentMapper paymentMapper) {
        this.batchMapper = batchMapper;
        this.importLogMapper = importLogMapper;
        this.contractMapper = contractMapper;
        this.billMapper = billMapper;
        this.paymentMapper = paymentMapper;
    }

    /** 创建迁移批次（基准日）。 */
    public MigrationBatch createBatch(LocalDate cutoverDate, String sourceFile) {
        MigrationBatch batch = new MigrationBatch();
        batch.setCutoverDate(cutoverDate);
        batch.setSourceFile(sourceFile);
        batch.setStatus("importing");
        batch.setCreatedBy(com.ams.platform.security.SecurityUtils.currentUserIdOrNull());
        batch.setCreatedAt(LocalDateTime.now());
        batchMapper.insert(batch);
        return batch;
    }

    public List<MigrationBatch> listBatches() {
        return batchMapper.selectList(
                new LambdaQueryWrapper<MigrationBatch>().orderByDesc(MigrationBatch::getId));
    }

    /** 记录导入日志。 */
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
     * 试算平衡（FR-MIG-008）：期初合同应收合计 = 期初欠费合计 + 期初实收合计。
     */
    @Transactional
    public MigrationBatch reconcile(Long batchId) {
        MigrationBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        BigDecimal totalReceivable = contractReceivable();
        BigDecimal totalArrears = billArrears();
        BigDecimal totalPaid = paymentPaid();
        BigDecimal balance = totalReceivable.subtract(totalArrears.add(totalPaid));

        batch.setBalanceResult(balance);
        batch.setStatus("reconciled");
        batchMapper.updateById(batch);
        return batch;
    }

    /** 试算平衡通过后锁定基准日进入试运行。 */
    @Transactional
    public MigrationBatch lockCutover(Long batchId) {
        MigrationBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        if (batch.getBalanceResult() != null && batch.getBalanceResult().compareTo(BigDecimal.ZERO) != 0) {
            throw new AppException(ErrorCode.BUSINESS_ERROR, "试算不平衡，不可锁定进入试运行");
        }
        batch.setStatus("locked");
        batch.setLockedAt(LocalDateTime.now());
        batchMapper.updateById(batch);
        return batch;
    }

    private BigDecimal contractReceivable() {
        List<Contract> contracts = contractMapper.selectList(null);
        return contracts.stream()
                .map(c -> c.getRentAmount() == null ? BigDecimal.ZERO : c.getRentAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal billArrears() {
        List<Bill> bills = billMapper.selectList(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getSource, "migration"));
        return bills.stream()
                .map(b -> b.getAmount().subtract(b.getPaidAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal paymentPaid() {
        List<Payment> payments = paymentMapper.selectList(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getSource, "migration"));
        return payments.stream().map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
