package com.ams.platform.job;

import com.ams.modules.alert.service.AlertService;
import com.ams.modules.billing.service.BillService;
import com.ams.modules.contract.ContractStatus;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.dunning.service.DunningService;
import com.ams.modules.dunning.service.LateFeeService;
import com.ams.modules.intangible.service.IntangibleAssetService;
import com.ams.modules.maintenance.service.RepairService;
import com.ams.modules.meter.service.UtilityBillingService;
import com.ams.modules.occupation.service.OccupationService;
import com.ams.modules.plan.service.BusinessPlanService;
import com.ams.modules.revitalization.service.RevitalizationService;
import com.ams.modules.selfuse.service.SelfUseService;
import com.ams.modules.task.service.TaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时任务：出账、催缴、滞纳金、合同到期、预警、占用/自用、报修SLA、无形摊销、待办超时。
 */
@Component
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final BillService billService;
    private final DunningService dunningService;
    private final LateFeeService lateFeeService;
    private final TaskService taskService;
    private final ContractMapper contractMapper;
    private final AlertService alertService;
    private final RevitalizationService revitalizationService;
    private final UtilityBillingService utilityBillingService;
    private final BusinessPlanService businessPlanService;
    private final OccupationService occupationService;
    private final SelfUseService selfUseService;
    private final RepairService repairService;
    private final IntangibleAssetService intangibleAssetService;

    public ScheduledJobs(
            BillService billService,
            DunningService dunningService,
            LateFeeService lateFeeService,
            TaskService taskService,
            ContractMapper contractMapper,
            AlertService alertService,
            RevitalizationService revitalizationService,
            UtilityBillingService utilityBillingService,
            BusinessPlanService businessPlanService,
            OccupationService occupationService,
            SelfUseService selfUseService,
            RepairService repairService,
            IntangibleAssetService intangibleAssetService) {
        this.billService = billService;
        this.dunningService = dunningService;
        this.lateFeeService = lateFeeService;
        this.taskService = taskService;
        this.contractMapper = contractMapper;
        this.alertService = alertService;
        this.revitalizationService = revitalizationService;
        this.utilityBillingService = utilityBillingService;
        this.businessPlanService = businessPlanService;
        this.occupationService = occupationService;
        this.selfUseService = selfUseService;
        this.repairService = repairService;
        this.intangibleAssetService = intangibleAssetService;
    }

    @Scheduled(cron = "0 0 1 * * *")
    public void billingGenerate() {
        int issued = billService.issueBills();
        log.info("billing-generate issued {} bills", issued);
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void dunningScan() {
        var result = dunningService.runAutoDunning();
        log.info("dunning-auto scanned={}, upgraded={}, tasks={}, sms={}, preDue={}",
                result.getScanned(), result.getUpgraded(), result.getTasksCreated(),
                result.getSmsSent(), result.getPreDueReminded());
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void lateFeeAccrual() {
        int accrued = lateFeeService.accrueLateFeesFromConfig();
        log.info("late-fee accrued {} bills", accrued);
    }

    @Scheduled(cron = "0 0 4 * * *")
    public void contractExpiryScan() {
        LocalDate today = LocalDate.now();
        List<Contract> expiring = contractMapper.selectList(
                new LambdaQueryWrapper<Contract>()
                        .eq(Contract::getStatus, ContractStatus.ACTIVE)
                        .le(Contract::getEndDate, today.plusDays(30))
                        .gt(Contract::getEndDate, today));
        for (Contract contract : expiring) {
            contract.setStatus(ContractStatus.RENEWABLE);
            contractMapper.updateById(contract);
        }
        List<Contract> overdue = contractMapper.selectList(
                new LambdaQueryWrapper<Contract>()
                        .in(Contract::getStatus, ContractStatus.ACTIVE, ContractStatus.RENEWABLE,
                                ContractStatus.EXPIRING)
                        .lt(Contract::getEndDate, today));
        for (Contract contract : overdue) {
            contract.setStatus(ContractStatus.EXPIRED);
            contractMapper.updateById(contract);
        }
        log.info("contract-expiry scan: {} renewable, {} expired", expiring.size(), overdue.size());
    }

    /** 每日 04:30 预警自动触发 → 任务中心。 */
    @Scheduled(cron = "0 30 4 * * *")
    public void alertScan() {
        int n = alertService.scanAndTrigger();
        log.info("alert-scan triggered {} alerts", n);
    }

    /** 每日 04:45 占用/自用到期预警。 */
    @Scheduled(cron = "0 45 4 * * *")
    public void occupationSelfUseExpiry() {
        int occ = occupationService.scanExpiry(7);
        int self = selfUseService.scanExpiry(7);
        log.info("occ/self-use expiry: occupation={}, selfUse={}", occ, self);
    }

    /** 每日 05:00 空置超期督办。 */
    @Scheduled(cron = "0 0 5 * * *")
    public void vacantEscalate() {
        int n = revitalizationService.escalateLongVacant();
        log.info("vacant-escalate {} assets", n);
    }

    /** 每日 06:00 水电出账。 */
    @Scheduled(cron = "0 0 6 * * *")
    public void utilityIssue() {
        int n = utilityBillingService.issuePendingUtilityBills();
        log.info("utility-issue {} bills", n);
    }

    /** 每日 07:00 经营计划偏差扫描 → 督办任务。 */
    @Scheduled(cron = "0 0 7 * * *")
    public void planDeviationScan() {
        int n = businessPlanService.scanDeviations().size();
        log.info("plan-deviation-scan {} plans", n);
    }

    @Scheduled(fixedDelay = 30 * 60 * 1000)
    public void taskTimeoutMonitor() {
        int[] r = taskService.monitorTimeout();
        if (r[0] + r[1] + r[2] > 0) {
            log.info("task-timeout: reminded={}, overdue={}, escalated={}", r[0], r[1], r[2]);
        }
    }

    /** 每小时报修 SLA 扫描。 */
    @Scheduled(cron = "0 15 * * * *")
    public void repairSlaScan() {
        int n = repairService.scanSlaBreaches();
        if (n > 0) {
            log.info("repair-sla breached {}", n);
        }
    }

    /** 每月 1 日无形摊销。 */
    @Scheduled(cron = "0 0 8 1 * *")
    public void intangibleAmortize() {
        int n = intangibleAssetService.amortizeMonthly();
        log.info("intangible-amortize {}", n);
    }

    /** 每日无形到期预警。 */
    @Scheduled(cron = "0 20 5 * * *")
    public void intangibleExpiry() {
        int n = intangibleAssetService.scanExpiry(90);
        log.info("intangible-expiry {}", n);
    }
}
