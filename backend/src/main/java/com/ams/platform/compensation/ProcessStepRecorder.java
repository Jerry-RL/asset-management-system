package com.ams.platform.compensation;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 步骤记录器：所有写入使用 {@link Propagation#REQUIRES_NEW}（独立事务）。
 *
 * <p><b>为什么不与业务同事务</b>：业务事务失败会回滚，若步骤记录也在其中，
 * 则失败痕迹一并消失，补偿与排障无从下手。独立事务保证「业务回滚，痕迹留存」。
 *
 * <p>代价：步骤记录先于业务提交可见（例如状态已是 {@code done} 而业务最终回滚）。
 * 因此外层失败时必须显式把已完成的步骤改判为 {@link ProcessStepStatus#ROLLED_BACK}
 * ——这一步由 {@link CompensationOrchestrator} 统一负责，业务代码不得自行处理。
 */
@Service
public class ProcessStepRecorder {

    private final ProcessStepMapper mapper;

    public ProcessStepRecorder(ProcessStepMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 步骤开始：upsert 一行并置为 running。
     *
     * @return 步骤记录（含 id），供后续状态更新
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProcessStep begin(String processType, Long bizId, int stepNo, String stepName,
            CompensateKind kind) {
        mapper.upsertBegin(processType, bizId, stepNo, stepName,
                ProcessStepStatus.RUNNING, kind == null ? null : kind.code());
        return mapper.findByKey(processType, bizId, stepNo);
    }

    /** 步骤成功。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDone(ProcessStep step) {
        update(step, ProcessStepStatus.DONE, null);
    }

    /** 步骤失败且无外部副作用（可重试）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(ProcessStep step, Throwable error) {
        update(step, ProcessStepStatus.FAILED, describe(error));
    }

    /** 随外层事务回滚，无需人工处置。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRolledBack(ProcessStep step) {
        update(step, ProcessStepStatus.ROLLED_BACK, null);
    }

    /** 需人工处置（外部副作用可能已发生）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markManual(ProcessStep step, String reason) {
        update(step, ProcessStepStatus.MANUAL, reason);
    }

    /** 某流程的全部步骤（按执行顺序）。 */
    public List<ProcessStep> listByBiz(String processType, Long bizId) {
        return mapper.listByBiz(processType, bizId);
    }

    /** 待处置清单（failed / manual）。 */
    public List<ProcessStep> listOpenIssues(int limit) {
        return mapper.listOpenIssues(limit);
    }

    private void update(ProcessStep step, String status, String error) {
        ProcessStep patch = new ProcessStep();
        patch.setId(step.getId());
        patch.setStatus(status);
        if (error != null) {
            patch.setError(error);
        }
        mapper.updateById(patch);
        step.setStatus(status);
        step.setError(error);
    }

    private String describe(Throwable error) {
        if (error == null) {
            return null;
        }
        String msg = error.getMessage();
        return error.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}
