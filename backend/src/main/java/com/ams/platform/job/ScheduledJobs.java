package com.ams.platform.job;

import com.ams.modules.billing.service.BillService;
import com.ams.modules.contract.ContractStatus;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.dunning.service.DunningService;
import com.ams.modules.dunning.service.LateFeeService;
import com.ams.modules.task.service.TaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时任务（DSD §4.9）：
 *  - billing-generate：每日出账
 *  - dunning-scan：催缴升级（每日 02:00）
 *  - late-fee：滞纳金计息（每日 03:00）
 *  - contract-expiry：合同到期状态扫描（每日 04:00）
 *  - task-timeout：待办超时监控（每 30 分钟）
 *
 * 生产可替换为 JobRunr 持久化任务（ADR-0018）；此处以 Spring @Scheduled 实现等价逻辑。
 */
@Component
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final BillService billService;
    private final DunningService dunningService;
    private final LateFeeService lateFeeService;
    private final TaskService taskService;
    private final ContractMapper contractMapper;

    public ScheduledJobs(
            BillService billService,
            DunningService dunningService,
            LateFeeService lateFeeService,
            TaskService taskService,
            ContractMapper contractMapper) {
        this.billService = billService;
        this.dunningService = dunningService;
        this.lateFeeService = lateFeeService;
        this.taskService = taskService;
        this.contractMapper = contractMapper;
    }

    /** 每日 01:00 出账。 */
    @Scheduled(cron = "0 0 1 * * *")
    public void billingGenerate() {
        int issued = billService.issueBills();
        log.info("billing-generate issued {} bills", issued);
    }

    /** 每日 02:00 催缴升级扫描。 */
    @Scheduled(cron = "0 0 2 * * *")
    public void dunningScan() {
        int updated = dunningService.scanOverdue();
        log.info("dunning-scan updated {} bills", updated);
    }

    /** 每日 03:00 滞纳金计息。 */
    @Scheduled(cron = "0 0 3 * * *")
    public void lateFeeAccrual() {
        int accrued = lateFeeService.accrueLateFees(new BigDecimal("0.0005"));
        log.info("late-fee accrued {} bills", accrued);
    }

    /** 每日 04:00 合同到期状态扫描（§4.24.9）。 */
    @Scheduled(cron = "0 0 4 * * *")
    public void contractExpiryScan() {
        LocalDate today = LocalDate.now();
        // 到期前 30 天 → 需续签（提醒）
        List<Contract> expiring = contractMapper.selectList(
                new LambdaQueryWrapper<Contract>()
                        .eq(Contract::getStatus, ContractStatus.ACTIVE)
                        .le(Contract::getEndDate, today.plusDays(30))
                        .gt(Contract::getEndDate, today));
        for (Contract contract : expiring) {
            contract.setStatus(ContractStatus.RENEWABLE);
            contractMapper.updateById(contract);
        }
        // 已逾期且未续签未退租 → 已到期（挂账处理，生成预警）
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

    /** 每 30 分钟：待办超时监控（FR-TASK-002）。 */
    @Scheduled(fixedDelay = 30 * 60 * 1000)
    public void taskTimeoutMonitor() {
        int overdue = taskService.markOverdue();
        if (overdue > 0) {
            log.info("task-timeout: {} tasks overdue", overdue);
        }
    }
}
