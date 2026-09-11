-- ============================================================================
-- V43 流程步骤与补偿记录（process_step）
--   决策依据：docs/design/业务闭环编排设计.md §3.1
--   评审依据：docs/design/领域划分与限界上下文评审报告.md P0-7
--
--   背景：退款冲正由三步组成（退回核销 → 红冲发票 → 生成冲销凭证），其中「红冲发票」
--        会在事务内调用第三方电子发票平台（InvoiceService.redFlush → invoiceAdapter.redFlush）。
--        第三方成功后若后续步骤失败，事务回滚会让 DB 显示「未红冲」而第三方已红冲 ——
--        外部副作用无法随事务回滚，必须留下痕迹并交人工处置。
--
--   因此本表写入一律使用 REQUIRES_NEW（独立事务），保证：
--     1) 失败时痕迹不被外层回滚；
--     2) 重试时同一 (process_type, biz_id, step_no) 复用同一行（begin 为 upsert）。
--
--   状态语义（与 docs/design/业务闭环编排设计.md §3.1 对齐）：
--     running     执行中
--     done        已成功
--     rolled_back 随外层事务回滚，无需人工（仅对 DB 内步骤）
--     failed      步骤失败，且无外部副作用，重试即可
--     manual      需人工处置（外部副作用可能已发生，或补偿动作不可自动执行）
--
--   补偿策略（compensate_kind）：
--     rollback  DB 内步骤，回滚即还原
--     manual    外部副作用步骤，只能人工处置（如发票重新开具）
--     （auto 预留：出现可自动补偿的动作时再启用，当前不写入）
--
--   幂等：CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS / 约束先判存在。
-- ============================================================================

CREATE TABLE IF NOT EXISTS process_step (
    id              BIGSERIAL PRIMARY KEY,
    -- 流程类型：vacate / refund / transfer（当前仅 refund 接入）
    process_type    VARCHAR(40) NOT NULL,
    -- 业务单据 ID
    biz_id          BIGINT      NOT NULL,
    -- 执行顺序（1 起，逆序补偿依据）
    step_no         INTEGER     NOT NULL,
    -- 步骤名：reverse_allocation / red_flush_invoice / create_voucher
    step_name       VARCHAR(60) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    -- 补偿策略：rollback / manual；NULL 表示未声明
    compensate_kind VARCHAR(20),
    -- 补偿或处置所需入参快照（JSON 文本，避免与 V4 的 jsonb→text 约定冲突）
    -- 预留：当前由 biz_id 反查业务单据即可定位，未写入；启用自动补偿时再落值
    payload         TEXT,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    created_by      BIGINT,
    updated_by      BIGINT,
    CONSTRAINT ck_process_step_status
        CHECK (status IN ('running', 'done', 'rolled_back', 'failed', 'manual')),
    CONSTRAINT ck_process_step_kind
        CHECK (compensate_kind IS NULL OR compensate_kind IN ('rollback', 'manual'))
);

-- 同一步骤只有一行：重试复用，保证 begin 可 upsert
CREATE UNIQUE INDEX IF NOT EXISTS uq_process_step
    ON process_step (process_type, biz_id, step_no);

-- 待处置清单（failed / manual）——运维与待办入口
CREATE INDEX IF NOT EXISTS idx_process_step_open
    ON process_step (status) WHERE status IN ('failed', 'manual');

COMMENT ON TABLE process_step IS
    '流程步骤与补偿记录（退款/退租/调拨等含外部副作用的编排）；写入使用 REQUIRES_NEW，失败痕迹不被外层回滚';
COMMENT ON COLUMN process_step.compensate_kind IS
    'rollback=DB 内步骤回滚即还原；manual=外部副作用步骤只能人工处置';
COMMENT ON COLUMN process_step.status IS
    'running/done/rolled_back/failed/manual；rolled_back 表示随外层事务回滚、无需人工';
