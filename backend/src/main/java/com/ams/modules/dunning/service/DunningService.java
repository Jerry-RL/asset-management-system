package com.ams.modules.dunning.service;

import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.config.service.ConfigVersionService;
import com.ams.modules.dunning.dto.DunningAutoJobResult;
import com.ams.modules.dunning.dto.DunningQueueItem;
import com.ams.modules.dunning.entity.DunningRecord;
import com.ams.modules.dunning.mapper.DunningRecordMapper;
import com.ams.modules.lease.entity.Tenant;
import com.ams.modules.lease.mapper.TenantMapper;
import com.ams.modules.lease.service.TenantService;
import com.ams.modules.notification.service.NotificationService;
import com.ams.modules.task.entity.Task;
import com.ams.modules.task.service.TaskService;
import com.ams.platform.integration.sms.SmsAdapter;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 催缴升级（FR-DUN-ESC-*，SRS §4.23.6）。
 * 逾期天数 → L1-L5；到期前提醒；升级自动建任务中心待办；短信触达；信用分联动。
 */
@Service
public class DunningService {

    private final BillMapper billMapper;
    private final DunningRecordMapper recordMapper;
    private final TenantMapper tenantMapper;
    private final SmsAdapter smsAdapter;
    private final TaskService taskService;
    private final TenantService tenantService;
    private final ConfigVersionService configVersionService;
    private final NotificationService notificationService;

    public DunningService(
            BillMapper billMapper,
            DunningRecordMapper recordMapper,
            TenantMapper tenantMapper,
            SmsAdapter smsAdapter,
            TaskService taskService,
            TenantService tenantService,
            ConfigVersionService configVersionService,
            NotificationService notificationService) {
        this.billMapper = billMapper;
        this.recordMapper = recordMapper;
        this.tenantMapper = tenantMapper;
        this.smsAdapter = smsAdapter;
        this.taskService = taskService;
        this.tenantService = tenantService;
        this.configVersionService = configVersionService;
        this.notificationService = notificationService;
    }

    /**
     * 自动化催缴扫描：到期前提醒 + 逾期升级，并为各等级生成任务中心待办。
     */
    @Transactional
    public DunningAutoJobResult runAutoDunning() {
        DunningAutoJobResult result = new DunningAutoJobResult();
        LocalDate today = LocalDate.now();
        int preDueDays = cfgInt("dunning.l1_pre_due_days", 3);

        List<Bill> openBills = billMapper.selectList(
                new LambdaQueryWrapper<Bill>()
                        .in(Bill::getStatus, BillStatus.UNPAID, BillStatus.PARTIAL_PAID)
                        .isNotNull(Bill::getDueDate));
        result.setScanned(openBills.size());

        for (Bill bill : openBills) {
            LocalDate due = bill.getDueDate();
            if (due == null) {
                continue;
            }
            // 到期前 N 天 → L1 提醒（尚未逾期）
            if (!due.isBefore(today) && !due.isAfter(today.plusDays(preDueDays))) {
                Integer prev = bill.getDunningLevel();
                if (prev == null || prev < 1) {
                    bill.setDunningLevel(1);
                    billMapper.updateById(bill);
                    onLevelUpgraded(bill, 0, 1, 0, result, true);
                    result.setPreDueReminded(result.getPreDueReminded() + 1);
                    result.setUpgraded(result.getUpgraded() + 1);
                }
                continue;
            }
            if (!due.isBefore(today)) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(due, today);
            int level = levelFor(days);
            Integer prev = bill.getDunningLevel();
            int from = prev == null ? 0 : prev;
            if (from < level) {
                bill.setDunningLevel(level);
                billMapper.updateById(bill);
                onLevelUpgraded(bill, from, level, days, result, false);
                result.setUpgraded(result.getUpgraded() + 1);
            } else if (from >= 1) {
                // 等级未变：补齐缺失的自动化任务（幂等）
                if (ensureDunningTask(bill, from)) {
                    result.setTasksCreated(result.getTasksCreated() + 1);
                }
            }
        }

        if (result.getUpgraded() > 0 || result.getTasksCreated() > 0) {
            result.getHighlights().add(String.format(
                    "升级 %d 笔，新建任务 %d，短信 %d，到期前提醒 %d",
                    result.getUpgraded(),
                    result.getTasksCreated(),
                    result.getSmsSent(),
                    result.getPreDueReminded()));
        }
        return result;
    }

    /** 兼容定时任务旧调用。 */
    @Transactional
    public int scanOverdue() {
        return runAutoDunning().getUpgraded();
    }

    /** 催缴工作队列：欠费 + 即将到期。 */
    public List<DunningQueueItem> queue(Integer minLevel) {
        LocalDate today = LocalDate.now();
        int preDueDays = cfgInt("dunning.l1_pre_due_days", 3);
        List<Bill> bills = billMapper.selectList(
                new LambdaQueryWrapper<Bill>()
                        .in(Bill::getStatus, BillStatus.UNPAID, BillStatus.PARTIAL_PAID)
                        .isNotNull(Bill::getDueDate)
                        .le(Bill::getDueDate, today.plusDays(preDueDays))
                        .orderByAsc(Bill::getDueDate));
        List<DunningQueueItem> items = new ArrayList<>();
        for (Bill b : bills) {
            long overdueDays = b.getDueDate().isBefore(today)
                    ? ChronoUnit.DAYS.between(b.getDueDate(), today)
                    : 0;
            int suggested = b.getDueDate().isBefore(today)
                    ? levelFor(overdueDays)
                    : 1;
            int current = b.getDunningLevel() == null ? 0 : b.getDunningLevel();
            if (minLevel != null && Math.max(current, suggested) < minLevel) {
                continue;
            }
            BigDecimal paid = b.getPaidAmount() == null ? BigDecimal.ZERO : b.getPaidAmount();
            BigDecimal reduced = b.getReducedAmount() == null ? BigDecimal.ZERO : b.getReducedAmount();
            BigDecimal arrears = b.getAmount() == null
                    ? BigDecimal.ZERO
                    : b.getAmount().subtract(paid).subtract(reduced);
            items.add(DunningQueueItem.builder()
                    .billId(b.getId())
                    .billNo(b.getBillNo())
                    .contractId(b.getContractId())
                    .tenantId(b.getTenantId())
                    .assetId(b.getAssetId())
                    .dueDate(b.getDueDate())
                    .overdueDays(overdueDays)
                    .amount(b.getAmount())
                    .paidAmount(paid)
                    .arrears(arrears)
                    .status(b.getStatus())
                    .currentLevel(current)
                    .suggestedLevel(suggested)
                    .phase(b.getDueDate().isBefore(today) ? "overdue" : "pre_due")
                    .build());
        }
        items.sort(Comparator
                .comparingInt(DunningQueueItem::getSuggestedLevel).reversed()
                .thenComparing(DunningQueueItem::getDueDate));
        return items;
    }

    /** 自动化催缴产生的待办任务。 */
    public List<Task> listAutoTasks() {
        return taskService.listPendingByTypePrefix("dunning_");
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

    /**
     * 逾期天数 → 催缴等级（SRS §4.23.6，阈值可配置）。
     * L1 提醒 / L2 催缴单 / L3 律师函 / L4 法务 / L5 清退处置。
     */
    public int levelFor(long overdueDays) {
        int l5 = cfgInt("dunning.l5_days", 180);
        int l4 = cfgInt("dunning.l4_days", 90);
        int l3 = cfgInt("dunning.l3_days", 30);
        int l2 = cfgInt("dunning.l2_days", 7);
        if (overdueDays >= l5) {
            return 5;
        }
        if (overdueDays >= l4) {
            return 4;
        }
        if (overdueDays >= l3) {
            return 3;
        }
        if (overdueDays >= l2) {
            return 2;
        }
        if (overdueDays >= 0) {
            return 1;
        }
        return 0;
    }

    private void onLevelUpgraded(
            Bill bill, int fromLevel, int toLevel, long overdueDays,
            DunningAutoJobResult result, boolean preDue) {
        String content = preDue
                ? String.format("账单即将到期催缴提醒 L1，账单 %s，到期日 %s，金额=%s",
                nullToDash(bill.getBillNo()), bill.getDueDate(), bill.getAmount())
                : String.format("账单逾期催缴升级 L%d→L%d，逾期%d天，账单 %s，金额=%s",
                fromLevel, toLevel, overdueDays, nullToDash(bill.getBillNo()), bill.getAmount());
        record(bill.getId(), toLevel, channelFor(toLevel), content, null,
                preDue ? "pre_due_remind" : "auto_escalated");

        completeLowerLevelTasks(bill.getId(), toLevel);

        if (ensureDunningTask(bill, toLevel)) {
            result.setTasksCreated(result.getTasksCreated() + 1);
        }

        if (toLevel >= 1 && toLevel <= 3) {
            if (sendSms(bill, content)) {
                result.setSmsSent(result.getSmsSent() + 1);
            }
        }
        adjustCreditOnEscalate(bill, fromLevel, toLevel);
        notifyAssignees(bill, toLevel, content);
    }

    private void completeLowerLevelTasks(Long billId, int toLevel) {
        for (int lv = 1; lv < toLevel; lv++) {
            List<Task> pending = taskService.listPendingByRef(taskType(lv), billId);
            for (Task t : pending) {
                taskService.complete(t.getId());
            }
        }
    }

    /** @return true if a new task was created */
    private boolean ensureDunningTask(Bill bill, int level) {
        String type = taskType(level);
        List<Task> pending = taskService.listPendingByRef(type, bill.getId());
        if (!pending.isEmpty()) {
            return false;
        }
        Task task = new Task();
        task.setTaskType(type);
        task.setRefId(bill.getId());
        task.setRefNo(refTitle(level, bill));
        task.setDeadline(LocalDateTime.now().plusDays(deadlineDays(level)));
        task.setStatus("pending");
        taskService.create(task);
        return true;
    }

    private void notifyAssignees(Bill bill, int level, String content) {
        try {
            notificationService.sendByTemplate(
                    "dunning_auto",
                    null,
                    Map.of(
                            "billId", String.valueOf(bill.getId()),
                            "billNo", nullToDash(bill.getBillNo()),
                            "level", "L" + level,
                            "content", content),
                    "dunning",
                    bill.getId());
        } catch (Exception ignored) {
            // 通知失败不阻断催缴
        }
    }

    private void adjustCreditOnEscalate(Bill bill, int fromLevel, int toLevel) {
        if (bill.getTenantId() == null || toLevel <= fromLevel) {
            return;
        }
        int delta = switch (toLevel) {
            case 3 -> -5;
            case 4 -> -10;
            case 5 -> -15;
            default -> toLevel >= 2 ? -2 : 0;
        };
        if (delta == 0) {
            return;
        }
        try {
            tenantService.adjustCredit(bill.getTenantId(), delta, "dunning_L" + toLevel,
                    "催缴升级扣减信用分");
            if (toLevel >= 5) {
                Tenant t = tenantMapper.selectById(bill.getTenantId());
                if (t != null && (t.getCreditScore() == null || t.getCreditScore() < 40)) {
                    tenantService.setBlacklist(bill.getTenantId(), true);
                }
            }
        } catch (Exception ignored) {
            // 不阻断催缴主流程
        }
    }

    private String channelFor(int level) {
        return switch (level) {
            case 1 -> "sms";
            case 2 -> "notice_post";
            case 3 -> "lawyer_letter";
            case 4 -> "legal";
            case 5 -> "eviction";
            default -> "system";
        };
    }

    private String taskType(int level) {
        return "dunning_l" + level;
    }

    private String refTitle(int level, Bill bill) {
        String no = nullToDash(bill.getBillNo());
        return switch (level) {
            case 1 -> "L1到期提醒 · " + no;
            case 2 -> "L2催缴单张贴 · " + no;
            case 3 -> "L3律师函 · " + no;
            case 4 -> "L4法务督办 · " + no;
            case 5 -> "L5清退督办 · " + no;
            default -> "催缴 · " + no;
        };
    }

    private int deadlineDays(int level) {
        return switch (level) {
            case 1 -> cfgInt("dunning.task_deadline_l1", 1);
            case 2 -> cfgInt("dunning.task_deadline_l2", 2);
            case 3 -> cfgInt("dunning.task_deadline_l3", 5);
            case 4 -> cfgInt("dunning.task_deadline_l4", 7);
            case 5 -> cfgInt("dunning.task_deadline_l5", 3);
            default -> 3;
        };
    }

    private boolean sendSms(Bill bill, String content) {
        if (bill.getTenantId() == null) {
            return false;
        }
        Tenant tenant = tenantMapper.selectById(bill.getTenantId());
        if (tenant == null || tenant.getPhone() == null || tenant.getPhone().isBlank()) {
            return false;
        }
        smsAdapter.send(tenant.getPhone(), content);
        return true;
    }

    private int cfgInt(String key, int def) {
        try {
            return Integer.parseInt(configVersionService.getValue(key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
