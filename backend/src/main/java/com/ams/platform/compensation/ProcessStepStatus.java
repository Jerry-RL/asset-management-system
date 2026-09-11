package com.ams.platform.compensation;

import java.util.Set;

/**
 * 流程步骤状态（{@code process_step.status}）。
 *
 * <p>语义见 {@code V43__process_step.sql}；核心区分是「谁负责收拾」：
 * <ul>
 *   <li>{@link #ROLLED_BACK} —— DB 内步骤，外层事务回滚即还原，<b>无需人工</b>；</li>
 *   <li>{@link #FAILED} —— 步骤失败且无外部副作用，<b>重试即可</b>；</li>
 *   <li>{@link #MANUAL} —— 外部副作用可能已发生（如第三方已红冲发票），<b>只能人工处置</b>。</li>
 * </ul>
 */
public final class ProcessStepStatus {

    private ProcessStepStatus() {
    }

    /** 执行中。 */
    public static final String RUNNING = "running";
    /** 已成功。 */
    public static final String DONE = "done";
    /** 随外层事务回滚，无需人工处置。 */
    public static final String ROLLED_BACK = "rolled_back";
    /** 步骤失败且无外部副作用，可重试。 */
    public static final String FAILED = "failed";
    /** 需人工处置（外部副作用可能已发生，或补偿不可自动执行）。 */
    public static final String MANUAL = "manual";

    /** 需人工/重试处置的终态集合——待办与运维清单的查询条件。 */
    public static final Set<String> OPEN_ISSUES = Set.of(FAILED, MANUAL);
}
