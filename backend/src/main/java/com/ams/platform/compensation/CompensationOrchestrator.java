package com.ams.platform.compensation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 补偿编排器：把「含外部副作用的多步操作」编排为可追溯、可补偿的步骤序列。
 *
 * <p>用途与边界（见 [业务闭环编排设计] §2 编排分层）：
 * <ul>
 *   <li>只用于<b>含外部副作用</b>的流程（退款冲正、退租结算、调拨迁移）；</li>
 *   <li>单纯跨上下文但无外部副作用的流程用领域事件即可，不要用本类；</li>
 *   <li>当前仅实现 {@link CompensateKind#ROLLBACK} 与 {@link CompensateKind#MANUAL}
 *       两种策略——「自动补偿（auto）」在出现真实可自动化的补偿动作前不实现，
 *       避免造出无人使用的抽象。</li>
 * </ul>
 *
 * <p><b>失败语义</b>：任一步骤抛出异常时，本类会
 * <ol>
 *   <li>把失败步骤记为 {@code failed}（DB 内步骤）或 {@code manual}（外部副作用步骤）；</li>
 *   <li>按<b>逆序</b>把此前已成功的步骤改判：{@code ROLLBACK → rolled_back}、
 *       {@code MANUAL → manual}（外部效果已发生，必须人工确认）；</li>
 *   <li>原样抛出异常，不吞不包，交由上层与全局异常处理。</li>
 * </ol>
 * 步骤记录写在独立事务（{@link ProcessStepRecorder}），因此外层业务事务回滚后
 * <b>痕迹仍然保留</b>——这正是本机制相对「一个 @Transactional 了事」的价值所在：
 * 事务能回滚本库数据，回滚不了第三方已执行的副作用。
 */
@Service
public class CompensationOrchestrator {

    private final ProcessStepRecorder recorder;

    public CompensationOrchestrator(ProcessStepRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * 一个可补偿步骤。
     *
     * @param stepNo 执行顺序（1 起）
     * @param name   步骤名，用于排障与人工处置
     * @param kind   补偿策略；决定失败后由谁收拾
     * @param action 步骤动作；抛 {@link RuntimeException} 即视为失败
     */
    public record Step(int stepNo, String name, CompensateKind kind, Runnable action) {

        /** DB 内步骤：失败后随外层事务回滚，无需人工。 */
        public static Step rollback(int stepNo, String name, Runnable action) {
            return new Step(stepNo, name, CompensateKind.ROLLBACK, action);
        }

        /** 外部副作用步骤：失败后必须人工处置。 */
        public static Step manual(int stepNo, String name, Runnable action) {
            return new Step(stepNo, name, CompensateKind.MANUAL, action);
        }
    }

    /**
     * 顺序执行步骤；任一步失败则逆序改判已完成步骤并抛出异常。
     *
     * <p>本方法<b>不开启事务</b>：事务边界由调用方决定（DB 步骤依赖外层事务回滚），
     * 步骤记录则由 {@link ProcessStepRecorder} 各自独立提交。
     */
    public void run(String processType, Long bizId, List<Step> steps) {
        List<Step> ordered = new ArrayList<>(steps);
        ordered.sort(Comparator.comparingInt(Step::stepNo));

        List<Executed> succeeded = new ArrayList<>();
        for (Step step : ordered) {
            ProcessStep record = recorder.begin(processType, bizId, step.stepNo(), step.name(), step.kind());
            try {
                step.action().run();
                recorder.markDone(record);
                succeeded.add(new Executed(step, record));
            } catch (RuntimeException ex) {
                recordFailure(record, step, ex);
                unwind(succeeded);
                throw ex;
            }
        }
    }

    /**
     * 已成功步骤的内存配对。
     *
     * <p>补偿策略取自 {@link Step#kind()}（编排时声明的），<b>不取数据库读回的行</b>：
     * 补偿判决必须依据调用方的声明，而非一次可能不完整的读取。
     */
    private record Executed(Step step, ProcessStep record) {
    }

    /** 失败步骤的判决：外部副作用步骤一律交人工，DB 内步骤只记 failed（重试即可）。 */
    private void recordFailure(ProcessStep record, Step step, RuntimeException ex) {
        if (step.kind() == CompensateKind.MANUAL) {
            recorder.markManual(record,
                    "步骤失败，外部副作用可能已发生，需人工确认：" + describe(ex));
        } else {
            recorder.markFailed(record, ex);
        }
    }

    /**
     * 逆序改判已成功步骤。
     *
     * <p>逆序不是形式要求：多步外部副作用时，后发生的应先确认，顺序错会让人工处置
     * 面对「上游说已回滚、下游说已生效」的矛盾状态。
     */
    private void unwind(List<Executed> succeeded) {
        for (int i = succeeded.size() - 1; i >= 0; i--) {
            Executed executed = succeeded.get(i);
            if (executed.step().kind() == CompensateKind.MANUAL) {
                recorder.markManual(executed.record(), "上游步骤失败，本步骤的外部副作用需人工确认");
            } else {
                recorder.markRolledBack(executed.record());
            }
        }
    }

    private String describe(Throwable error) {
        String msg = error.getMessage();
        return error.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}
