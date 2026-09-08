package com.ams.modules.dunning.service;

import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.dunning.entity.DunningRecord;
import com.ams.modules.dunning.mapper.DunningRecordMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 催缴升级（FR-DUN-ESC-*，SRS §4.23.6）。
 * 逾期天数 → L1-L5 等级；记录催缴过程；未回款升级并更新信用档案。
 */
@Service
public class DunningService {

    private final BillMapper billMapper;
    private final DunningRecordMapper recordMapper;

    public DunningService(BillMapper billMapper, DunningRecordMapper recordMapper) {
        this.billMapper = billMapper;
        this.recordMapper = recordMapper;
    }

    /**
     * 催缴扫描任务：按逾期天数映射等级并更新账单 dunning_level。
     */
    @Transactional
    public int scanOverdue() {
        List<Bill> bills = billMapper.selectList(
                new LambdaQueryWrapper<Bill>()
                        .in(Bill::getStatus, BillStatus.UNPAID, BillStatus.PARTIAL_PAID)
                        .lt(Bill::getDueDate, LocalDate.now()));
        int updated = 0;
        for (Bill bill : bills) {
            long days = ChronoUnit.DAYS.between(bill.getDueDate(), LocalDate.now());
            int level = levelFor(days);
            if (bill.getDunningLevel() == null || bill.getDunningLevel() < level) {
                bill.setDunningLevel(level);
                billMapper.updateById(bill);
                updated++;
            }
        }
        return updated;
    }

    /** 记录催缴动作（FR-DUN-001）：方式、时间、经办人、反馈、回款结果。 */
    public DunningRecord record(Long billId, Integer level, String method, String content,
            String tenantFeedback, String result) {
        Bill bill = billMapper.selectById(billId);
        DunningRecord record = new DunningRecord();
        record.setBillId(billId);
        record.setContractId(bill == null ? null : bill.getContractId());
        record.setTenantId(bill == null ? null : bill.getTenantId());
        record.setLevel(level);
        record.setMethod(method);
        record.setContent(content);
        record.setOperatorId(SecurityUtils.currentUserIdOrNull());
        record.setTenantFeedback(tenantFeedback);
        record.setResult(result);
        recordMapper.insert(record);

        if (bill != null && level != null) {
            bill.setDunningLevel(level);
            billMapper.updateById(bill);
        }
        return record;
    }

    public List<DunningRecord> listByContract(Long contractId) {
        return recordMapper.selectList(
                new LambdaQueryWrapper<DunningRecord>()
                        .eq(contractId != null, DunningRecord::getContractId, contractId)
                        .orderByDesc(DunningRecord::getId));
    }

    /** 逾期天数 → 催缴等级（可配置，默认见 SRS §4.23.6）。 */
    public int levelFor(long overdueDays) {
        if (overdueDays >= 90) {
            return 4; // L4 法务
        }
        if (overdueDays >= 30) {
            return 3; // L3 律师函
        }
        if (overdueDays >= 7) {
            return 2; // L2 催缴单
        }
        if (overdueDays >= 0) {
            return 1; // L1 提醒
        }
        return 0;
    }
}
