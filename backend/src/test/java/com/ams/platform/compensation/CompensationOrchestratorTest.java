package com.ams.platform.compensation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 补偿编排测试（[业务闭环编排设计] §3.1、评审 P0-7）。
 *
 * <p>验证的核心命题：<b>事务能回滚本库数据，回滚不了第三方已执行的副作用</b>。
 * 因此失败时必须按策略区分——DB 内步骤记 {@code rolled_back}（无需人工），
 * 外部副作用步骤记 {@code manual}（必须人工），且顺序为逆序。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompensationOrchestratorTest {

    private static final String PROCESS = "refund";
    private static final long BIZ_ID = 99L;

    @Mock
    private ProcessStepRecorder recorder;

    @InjectMocks
    private CompensationOrchestrator orchestrator;

    @Test
    @DisplayName("全部步骤成功：逐步 markDone，无任何改判")
    void allStepsSucceed() {
        stubBegin();

        orchestrator.run(PROCESS, BIZ_ID, List.of(
                step(1, CompensateKind.ROLLBACK, () -> { }),
                step(2, CompensateKind.MANUAL, () -> { }),
                step(3, CompensateKind.ROLLBACK, () -> { })));

        verify(recorder, times(3)).markDone(any(ProcessStep.class));
        verify(recorder, never()).markFailed(any(), any());
        verify(recorder, never()).markRolledBack(any());
        verify(recorder, never()).markManual(any(), any());
    }

    @Test
    @DisplayName("DB 内步骤失败：失败步记 failed，前序步骤逆序记 rolled_back")
    void rollbackStepFailureUnwindsToRolledBack() {
        stubBegin();

        assertThatThrownBy(() -> orchestrator.run(PROCESS, BIZ_ID, List.of(
                step(1, CompensateKind.ROLLBACK, () -> { }),
                step(2, CompensateKind.ROLLBACK, () -> { }),
                step(3, CompensateKind.ROLLBACK, () -> {
                    throw new IllegalStateException("凭证库写入失败");
                }))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("凭证库写入失败");

        // 失败步自身：DB 内 → failed（可重试）
        verify(recorder).markFailed(argId(3L), any());
        // 前序步骤：随外层事务回滚 → rolled_back，无需人工
        verify(recorder).markRolledBack(argId(2L));
        verify(recorder).markRolledBack(argId(1L));
    }

    @Test
    @DisplayName("外部副作用步骤失败：失败步记 manual（不假装可回滚）")
    void manualStepFailureIsRecordedAsManual() {
        stubBegin();

        assertThatThrownBy(() -> orchestrator.run(PROCESS, BIZ_ID, List.of(
                step(1, CompensateKind.ROLLBACK, () -> { }),
                step(2, CompensateKind.MANUAL, () -> {
                    throw new IllegalStateException("第三方红冲超时");
                }),
                step(3, CompensateKind.ROLLBACK, () -> { }))))
                .isInstanceOf(IllegalStateException.class);

        // 失败步是外部副作用 → 必须人工确认
        verify(recorder).markManual(argId(2L), org.mockito.ArgumentMatchers.contains("第三方红冲超时"));
        // 前序 DB 内步骤 → 回滚
        verify(recorder).markRolledBack(argId(1L));
        // 后续步骤未执行
        verify(recorder, never()).markDone(argId(3L));
    }

    @Test
    @DisplayName("已成功的外部副作用步骤在上游失败时改为 manual：外部效果已发生，必须人工确认")
    void succeededManualStepIsEscalatedWhenLaterStepFails() {
        stubBegin();

        assertThatThrownBy(() -> orchestrator.run(PROCESS, BIZ_ID, List.of(
                step(1, CompensateKind.ROLLBACK, () -> { }),
                step(2, CompensateKind.MANUAL, () -> { }),
                step(3, CompensateKind.ROLLBACK, () -> {
                    throw new IllegalStateException("凭证写入失败");
                }))))
                .isInstanceOf(IllegalStateException.class);

        verify(recorder).markRolledBack(argId(1L));
        // 关键：发票已让第三方红冲，事务回滚不回来 → manual
        verify(recorder).markManual(argId(2L), org.mockito.ArgumentMatchers.contains("外部副作用"));
        verify(recorder).markFailed(argId(3L), any());
    }

    @Test
    @DisplayName("步骤按 stepNo 排序执行，与传入顺序无关")
    void stepsAreExecutedInDeclaredOrder() {
        stubBegin();
        StringBuilder executed = new StringBuilder();

        orchestrator.run(PROCESS, BIZ_ID, List.of(
                step(3, CompensateKind.ROLLBACK, () -> executed.append("c")),
                step(1, CompensateKind.ROLLBACK, () -> executed.append("a")),
                step(2, CompensateKind.ROLLBACK, () -> executed.append("b"))));

        assertThat(executed.toString()).isEqualTo("abc");
    }

    @Test
    @DisplayName("步骤记录写入失败时不静默：异常向外传播")
    void recorderFailurePropagates() {
        when(recorder.begin(any(), anyLong(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("process_step 不可写"));

        assertThatThrownBy(() -> orchestrator.run(PROCESS, BIZ_ID,
                List.of(step(1, CompensateKind.ROLLBACK, () -> { }))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("process_step 不可写");
    }

    private CompensationOrchestrator.Step step(int no, CompensateKind kind, Runnable action) {
        return new CompensationOrchestrator.Step(no, "step_" + no, kind, action);
    }

    /** begin 返回 id = stepNo 的记录，便于断言改判发生在哪一步。 */
    private void stubBegin() {
        when(recorder.begin(any(), anyLong(), anyInt(), any(), any())).thenAnswer(invocation -> {
            int stepNo = invocation.getArgument(2);
            CompensateKind kind = invocation.getArgument(4);
            ProcessStep record = new ProcessStep();
            record.setId((long) stepNo);
            record.setProcessType(invocation.getArgument(0));
            record.setBizId(invocation.getArgument(1));
            record.setStepNo(stepNo);
            record.setStepName(invocation.getArgument(3));
            record.setStatus(ProcessStepStatus.RUNNING);
            record.setCompensateKind(kind == null ? null : kind.code());
            return record;
        });
    }

    private ProcessStep argId(long id) {
        return org.mockito.ArgumentMatchers.argThat(step -> step != null && Long.valueOf(id).equals(step.getId()));
    }
}
