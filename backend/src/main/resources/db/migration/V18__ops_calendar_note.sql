-- 经营日历：运营人员手工提醒（与业务聚合事件并存）
CREATE TABLE IF NOT EXISTS ops_calendar_note (
    id              BIGSERIAL PRIMARY KEY,
    event_date      DATE         NOT NULL,
    title           VARCHAR(200) NOT NULL,
    content         VARCHAR(1000),
    level           INT          NOT NULL DEFAULT 2,
    company_id      BIGINT,
    created_by      BIGINT,
    updated_by      BIGINT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ops_cal_note_date ON ops_calendar_note (event_date);
