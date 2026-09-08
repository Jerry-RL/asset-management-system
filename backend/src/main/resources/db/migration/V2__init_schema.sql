-- ============================================================================
-- 资产经营管理系统 — 全量初始化 DDL（对齐 docs/database/数据库设计.md V1.3）
-- 约定：
--   * 主键 BIGSERIAL；金额 NUMERIC(18,2)；审计 created_at/updated_at/created_by/updated_by
--   * 软删除 deleted_at TIMESTAMPTZ NULL（监管类数据禁止物理删除）
--   * 多租户 company_id + RBAC 数据范围隔离
--   * 状态字段为枚举字符串，与 SRS §4.24 状态机一致
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 3.1 组织与权限
-- ---------------------------------------------------------------------------
CREATE TABLE company (
    id           BIGSERIAL PRIMARY KEY,
    parent_id    BIGINT,
    name         VARCHAR(200) NOT NULL,
    company_type VARCHAR(50),
    status       SMALLINT     NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ,
    created_by   BIGINT,
    updated_by   BIGINT
);
CREATE INDEX idx_company_parent ON company (parent_id);

CREATE TABLE department (
    id         BIGSERIAL PRIMARY KEY,
    company_id BIGINT       NOT NULL,
    name       VARCHAR(100) NOT NULL,
    parent_id  BIGINT,
    status     SMALLINT     NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ,
    created_by BIGINT,
    updated_by BIGINT
);
CREATE INDEX idx_department_company ON department (company_id);

-- "user" 为 PG 保留字，使用双引号表名
CREATE TABLE "user" (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(100),
    phone         VARCHAR(20),
    department_id BIGINT,
    company_id    BIGINT,
    status        SMALLINT     NOT NULL DEFAULT 1,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    created_by    BIGINT,
    updated_by    BIGINT
);
CREATE INDEX idx_user_department ON "user" (department_id);
CREATE INDEX idx_user_company ON "user" (company_id);

CREATE TABLE role (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    data_scope  VARCHAR(20)  NOT NULL DEFAULT 'all', -- all/company/dept/project/self
    status      SMALLINT     NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    created_by  BIGINT,
    updated_by  BIGINT
);

CREATE TABLE user_role (
    id      BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL
);
CREATE INDEX idx_user_role_user ON user_role (user_id);
CREATE INDEX idx_user_role_role ON user_role (role_id);

CREATE TABLE menu (
    id         BIGSERIAL PRIMARY KEY,
    parent_id  BIGINT,
    name       VARCHAR(100) NOT NULL,
    code       VARCHAR(100),
    path       VARCHAR(200),
    icon       VARCHAR(100),
    sort       INTEGER      NOT NULL DEFAULT 0,
    menu_type  VARCHAR(20)  NOT NULL DEFAULT 'menu', -- dir/menu/button
    status     SMALLINT     NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_menu_parent ON menu (parent_id);

CREATE TABLE role_permission (
    id          BIGSERIAL PRIMARY KEY,
    role_id     BIGINT      NOT NULL,
    menu_code   VARCHAR(100) NOT NULL,
    action      VARCHAR(50) NOT NULL DEFAULT 'view', -- view/add/edit/delete/export/approve
    data_scope  VARCHAR(20),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_role_permission_role ON role_permission (role_id);

-- ---------------------------------------------------------------------------
-- 3.2 项目与资产
-- ---------------------------------------------------------------------------
CREATE TABLE project (
    id             BIGSERIAL PRIMARY KEY,
    company_id     BIGINT       NOT NULL,
    name           VARCHAR(200) NOT NULL,
    address        VARCHAR(500),
    longitude      NUMERIC(10,7),
    latitude       NUMERIC(10,7),
    status         SMALLINT     NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ,
    created_by     BIGINT,
    updated_by     BIGINT,
    deleted_at     TIMESTAMPTZ
);
CREATE INDEX idx_project_company ON project (company_id);

CREATE TABLE asset (
    id                  BIGSERIAL PRIMARY KEY,
    project_id          BIGINT,
    asset_no            VARCHAR(64) NOT NULL UNIQUE,
    name                VARCHAR(200) NOT NULL,
    asset_type          VARCHAR(20) NOT NULL,            -- property / land
    area                NUMERIC(18,2),
    source_type         VARCHAR(50),
    ownership_type      VARCHAR(50),
    property_company_id BIGINT,
    operating_company_id BIGINT,
    lease_control_status VARCHAR(30) NOT NULL DEFAULT 'vacant',
    base_rent_assessed  NUMERIC(18,2),
    base_rent_floor     NUMERIC(18,2),
    market_ref_rent     NUMERIC(18,2),
    province            VARCHAR(50),
    city                VARCHAR(50),
    district            VARCHAR(50),
    address             VARCHAR(500),
    structure_type      VARCHAR(50),
    usage_type          VARCHAR(50),
    water_meter_no      VARCHAR(50),
    electric_meter_no   VARCHAR(50),
    original_value      NUMERIC(18,2),
    qr_code_url         VARCHAR(500),
    parent_asset_id     BIGINT,
    version             INTEGER     NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    created_by          BIGINT,
    updated_by          BIGINT,
    deleted_at          TIMESTAMPTZ
);
CREATE INDEX idx_asset_project ON asset (project_id);
CREATE INDEX idx_asset_company ON asset (operating_company_id);
CREATE INDEX idx_asset_status ON asset (lease_control_status);
CREATE INDEX idx_asset_parent ON asset (parent_asset_id);

CREATE TABLE asset_certificate (
    id              BIGSERIAL PRIMARY KEY,
    asset_id        BIGINT      NOT NULL,
    cert_type       VARCHAR(50),
    cert_no         VARCHAR(100),
    owner_name      VARCHAR(200),
    register_date   DATE,
    mortgage_status VARCHAR(20) NOT NULL DEFAULT 'none',
    file_id         BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ
);
CREATE INDEX idx_asset_certificate_asset ON asset_certificate (asset_id);

CREATE TABLE asset_valuation (
    id            BIGSERIAL PRIMARY KEY,
    asset_id      BIGINT NOT NULL,
    valuation_value NUMERIC(18,2),
    valuation_date DATE,
    report_file_id BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_asset_valuation_asset ON asset_valuation (asset_id);

CREATE TABLE mortgage (
    id           BIGSERIAL PRIMARY KEY,
    asset_id     BIGINT NOT NULL,
    mortgagee    VARCHAR(200),
    amount       NUMERIC(18,2),
    start_date   DATE,
    end_date     DATE,
    status       VARCHAR(20) NOT NULL DEFAULT 'active', -- active / released
    file_id      BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ
);
CREATE INDEX idx_mortgage_asset ON mortgage (asset_id);

CREATE TABLE lease_control_log (
    id          BIGSERIAL PRIMARY KEY,
    asset_id    BIGINT      NOT NULL,
    from_status VARCHAR(30),
    to_status   VARCHAR(30) NOT NULL,
    biz_type    VARCHAR(30),
    biz_id      BIGINT,
    operator_id BIGINT,
    remark      VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_lease_control_log_asset ON lease_control_log (asset_id);

-- ---------------------------------------------------------------------------
-- 3.3 招租与租户
-- ---------------------------------------------------------------------------
CREATE TABLE tenant (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    phone         VARCHAR(20),
    id_no         VARCHAR(50),
    id_no_hash    CHAR(64),
    tenant_type   VARCHAR(20) NOT NULL DEFAULT 'person', -- person / enterprise
    blacklist     BOOLEAN     NOT NULL DEFAULT FALSE,
    credit_score  INTEGER     NOT NULL DEFAULT 100,
    wechat_openid VARCHAR(64),
    legal_rep     VARCHAR(100),
    status        SMALLINT    NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ
);
CREATE INDEX idx_tenant_id_no ON tenant (id_no_hash);
CREATE INDEX idx_tenant_phone ON tenant (phone);

CREATE TABLE tenant_credit_log (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL,
    event_type  VARCHAR(50),
    score_delta INTEGER,
    remark      VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tenant_credit_log_tenant ON tenant_credit_log (tenant_id);

CREATE TABLE lease_listing (
    id            BIGSERIAL PRIMARY KEY,
    asset_id      BIGINT      NOT NULL,
    rent_amount   NUMERIC(18,2),
    rent_negotiable BOOLEAN   NOT NULL DEFAULT FALSE,
    status        VARCHAR(20) NOT NULL DEFAULT 'active', -- active / closed
    published_at  TIMESTAMPTZ,
    closed_at     TIMESTAMPTZ,
    remark        VARCHAR(500),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ
);
CREATE INDEX idx_lease_listing_asset ON lease_listing (asset_id);
CREATE INDEX idx_lease_listing_status ON lease_listing (status);

CREATE TABLE tender_announcement (
    id                 BIGSERIAL PRIMARY KEY,
    title              VARCHAR(200) NOT NULL,
    asset_ids          BIGINT[],
    register_deadline  TIMESTAMPTZ,
    display_period_days INTEGER,
    status             VARCHAR(20) NOT NULL DEFAULT 'open', -- open / closed / flowed
    result             VARCHAR(50),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ
);

CREATE TABLE tender_application (
    id                BIGSERIAL PRIMARY KEY,
    announcement_id   BIGINT NOT NULL,
    tenant_id         BIGINT NOT NULL,
    audit_status      VARCHAR(20) NOT NULL DEFAULT 'pending', -- pending/approved/rejected
    deposit_paid      BOOLEAN NOT NULL DEFAULT FALSE,
    deposit_amount    NUMERIC(18,2),
    deposit_refunded  BOOLEAN NOT NULL DEFAULT FALSE,
    materials_file_id BIGINT,
    audit_comment     VARCHAR(500),
    rank_no           INTEGER,
    result            VARCHAR(20),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ
);
CREATE INDEX idx_tender_application_ann ON tender_application (announcement_id);
CREATE INDEX idx_tender_application_tenant ON tender_application (tenant_id);

-- ---------------------------------------------------------------------------
-- 3.4 合同与退租
-- ---------------------------------------------------------------------------
CREATE TABLE contract (
    id                  BIGSERIAL PRIMARY KEY,
    contract_no         VARCHAR(64) NOT NULL UNIQUE,
    asset_id            BIGINT NOT NULL,
    tenant_id           BIGINT NOT NULL,
    version             INTEGER NOT NULL DEFAULT 1,
    parent_contract_id  BIGINT,
    start_date          DATE NOT NULL,
    end_date            DATE NOT NULL,
    lease_area          NUMERIC(18,2),
    rent_type           VARCHAR(30) NOT NULL,
    rent_amount         NUMERIC(18,2) NOT NULL,
    deposit_amount      NUMERIC(18,2) NOT NULL DEFAULT 0,
    prepay_amount       NUMERIC(18,2) NOT NULL DEFAULT 0,
    payment_cycle       VARCHAR(20) NOT NULL,
    free_rent_days      INTEGER NOT NULL DEFAULT 0,
    increase_rate       NUMERIC(10,4),
    increase_period     VARCHAR(20),
    grace_days          INTEGER NOT NULL DEFAULT 0,
    proration_base      VARCHAR(20) NOT NULL DEFAULT 'calendar', -- calendar / fixed_30
    contract_type       VARCHAR(50),
    status              VARCHAR(30) NOT NULL DEFAULT 'draft',
    esign_status        VARCHAR(20),
    payment_status      VARCHAR(20),
    remark              VARCHAR(500),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    created_by          BIGINT,
    updated_by          BIGINT
);
CREATE INDEX idx_contract_asset ON contract (asset_id);
CREATE INDEX idx_contract_tenant ON contract (tenant_id);
CREATE INDEX idx_contract_status ON contract (status);
CREATE INDEX idx_contract_end_date ON contract (end_date);
CREATE INDEX idx_contract_parent ON contract (parent_contract_id);

CREATE TABLE contract_version (
    id           BIGSERIAL PRIMARY KEY,
    contract_id  BIGINT      NOT NULL,
    version      INTEGER     NOT NULL,
    change_type  VARCHAR(30),
    before_json  JSONB,
    after_json   JSONB,
    operator_id  BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_contract_version_contract ON contract_version (contract_id);

CREATE TABLE vacate_order (
    id                 BIGSERIAL PRIMARY KEY,
    contract_id        BIGINT NOT NULL,
    status             VARCHAR(30) NOT NULL DEFAULT 'applying',
    reason             VARCHAR(500),
    expected_vacate_date DATE,
    water_reading      NUMERIC(18,2),
    electric_reading   NUMERIC(18,2),
    inspection_remark  VARCHAR(500),
    inspected_by       BIGINT,
    inspected_at       TIMESTAMPTZ,
    settlement_amount  NUMERIC(18,2),
    damage_compensation NUMERIC(18,2) NOT NULL DEFAULT 0,
    deposit_refund     NUMERIC(18,2),
    prepay_refund      NUMERIC(18,2),
    settled_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ
);
CREATE INDEX idx_vacate_order_contract ON vacate_order (contract_id);

CREATE TABLE deposit_transaction (
    id          BIGSERIAL PRIMARY KEY,
    contract_id BIGINT NOT NULL,
    type        VARCHAR(20) NOT NULL, -- collect/hold/deduct/refund
    amount      NUMERIC(18,2) NOT NULL,
    ref_bill_id BIGINT,
    ref_payment_id BIGINT,
    remark      VARCHAR(500),
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_deposit_transaction_contract ON deposit_transaction (contract_id);

-- ---------------------------------------------------------------------------
-- 3.5 计费与收费
-- ---------------------------------------------------------------------------
CREATE TABLE payment_plan (
    id             BIGSERIAL PRIMARY KEY,
    contract_id    BIGINT NOT NULL,
    period_no      INTEGER,
    period_start   DATE NOT NULL,
    period_end     DATE NOT NULL,
    planned_amount NUMERIC(18,2) NOT NULL,
    due_date       DATE,
    status         VARCHAR(20) NOT NULL DEFAULT 'pending',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ
);
CREATE INDEX idx_payment_plan_contract ON payment_plan (contract_id);

CREATE TABLE bill (
    id                    BIGSERIAL PRIMARY KEY,
    bill_no               VARCHAR(64) NOT NULL UNIQUE,
    contract_id           BIGINT NOT NULL,
    plan_id               BIGINT,
    asset_id              BIGINT,
    tenant_id             BIGINT,
    bill_type             VARCHAR(20) NOT NULL DEFAULT 'rent', -- rent/utility/other
    period_start          DATE,
    period_end            DATE,
    due_date              DATE,
    amount                NUMERIC(18,2) NOT NULL DEFAULT 0,   -- 应收本金
    paid_amount           NUMERIC(18,2) NOT NULL DEFAULT 0,   -- 已核销本金
    late_fee_amount       NUMERIC(18,2) NOT NULL DEFAULT 0,   -- 累计应计滞纳金（汇总）
    late_fee_paid_amount  NUMERIC(18,2) NOT NULL DEFAULT 0,   -- 已核销滞纳金
    status                VARCHAR(20) NOT NULL DEFAULT 'pending_issue',
    dunning_level         SMALLINT NOT NULL DEFAULT 0,
    source                VARCHAR(20) NOT NULL DEFAULT 'system', -- system/migration
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ
);
CREATE INDEX idx_bill_contract ON bill (contract_id);
CREATE INDEX idx_bill_status_due ON bill (status, due_date);
CREATE INDEX idx_bill_tenant ON bill (tenant_id);

CREATE TABLE payment (
    id                     BIGSERIAL PRIMARY KEY,
    payment_no             VARCHAR(64) NOT NULL UNIQUE,
    contract_id            BIGINT,
    tenant_id              BIGINT,
    amount                 NUMERIC(18,2) NOT NULL,
    method                 VARCHAR(20),
    channel                VARCHAR(20) NOT NULL DEFAULT 'pc', -- user_mp/worker_mp/pc
    confirm_status         VARCHAR(20) NOT NULL DEFAULT 'confirmed', -- pending/confirmed
    confirmed_at           TIMESTAMPTZ,
    confirmed_by           BIGINT,
    paid_at                TIMESTAMPTZ,
    bank_reconcile_status  VARCHAR(20),
    remark                 VARCHAR(500),
    source                 VARCHAR(20) NOT NULL DEFAULT 'system',
    created_by             BIGINT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ
);
CREATE INDEX idx_payment_contract ON payment (contract_id);
CREATE INDEX idx_payment_channel_status ON payment (channel, confirm_status);

CREATE TABLE bill_payment (
    id           BIGSERIAL PRIMARY KEY,
    bill_id      BIGINT NOT NULL,
    payment_id   BIGINT NOT NULL,
    amount_type  VARCHAR(20) NOT NULL DEFAULT 'principal', -- principal / late_fee
    amount       NUMERIC(18,2) NOT NULL,
    allocated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    allocated_by BIGINT
);
CREATE INDEX idx_bill_payment_bill ON bill_payment (bill_id);
CREATE INDEX idx_bill_payment_payment ON bill_payment (payment_id);

CREATE TABLE late_fee (
    id            BIGSERIAL PRIMARY KEY,
    bill_id       BIGINT NOT NULL,
    rate_snapshot NUMERIC(10,6),
    period_start  DATE,
    period_end    DATE,
    fee_amount    NUMERIC(18,2) NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'accruing', -- accruing/waived/settled
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_late_fee_bill ON late_fee (bill_id);

CREATE TABLE prepay (
    id             BIGSERIAL PRIMARY KEY,
    contract_id    BIGINT NOT NULL,
    tenant_id      BIGINT,
    amount         NUMERIC(18,2) NOT NULL,
    used_amount    NUMERIC(18,2) NOT NULL DEFAULT 0,
    balance        NUMERIC(18,2) NOT NULL,
    remark         VARCHAR(500),
    created_by     BIGINT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_prepay_contract ON prepay (contract_id);

-- ---------------------------------------------------------------------------
-- 3.6 维修与任务
-- ---------------------------------------------------------------------------
CREATE TABLE repair_order (
    id                     BIGSERIAL PRIMARY KEY,
    asset_id               BIGINT NOT NULL,
    reporter_id            BIGINT,
    reporter_name          VARCHAR(100),
    reporter_phone         VARCHAR(20),
    description            TEXT,
    status                 VARCHAR(30) NOT NULL DEFAULT 'pending_review',
    vendor_id              BIGINT,
    assignee_id            BIGINT,
    sla_response_deadline  TIMESTAMPTZ,
    sla_complete_deadline  TIMESTAMPTZ,
    accepted_at            TIMESTAMPTZ,
    completed_at           TIMESTAMPTZ,
    result_remark          VARCHAR(500),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ
);
CREATE INDEX idx_repair_order_asset ON repair_order (asset_id);
CREATE INDEX idx_repair_order_status ON repair_order (status);

CREATE TABLE maintenance_vendor (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    contact     VARCHAR(100),
    phone       VARCHAR(20),
    scope       VARCHAR(500),
    status      SMALLINT NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE inspection_record (
    id          BIGSERIAL PRIMARY KEY,
    asset_id    BIGINT NOT NULL,
    inspector_id BIGINT,
    plan_date   DATE,
    result      VARCHAR(20),
    hazard_desc VARCHAR(1000),
    status      VARCHAR(20) NOT NULL DEFAULT 'pending',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_inspection_record_asset ON inspection_record (asset_id);

CREATE TABLE task (
    id          BIGSERIAL PRIMARY KEY,
    task_type   VARCHAR(50) NOT NULL,
    ref_id      BIGINT,
    ref_no      VARCHAR(64),
    company_id  BIGINT,
    assignee_id BIGINT,
    deadline    TIMESTAMPTZ,
    status      VARCHAR(20) NOT NULL DEFAULT 'pending',
    overdue_minutes BIGINT NOT NULL DEFAULT 0,
    completed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ
);
CREATE INDEX idx_task_assignee ON task (assignee_id);
CREATE INDEX idx_task_type_status ON task (task_type, status);

-- ---------------------------------------------------------------------------
-- 3.7 预警与盘活
-- ---------------------------------------------------------------------------
CREATE TABLE alert_rule (
    id            BIGSERIAL PRIMARY KEY,
    company_id    BIGINT,
    alert_type    VARCHAR(50) NOT NULL,
    sub_type      VARCHAR(50),
    level         SMALLINT NOT NULL DEFAULT 1,
    condition_json JSONB,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ
);
CREATE INDEX idx_alert_rule_type ON alert_rule (alert_type);

CREATE TABLE alert_record (
    id           BIGSERIAL PRIMARY KEY,
    rule_id      BIGINT,
    company_id   BIGINT,
    alert_type   VARCHAR(50),
    sub_type     VARCHAR(50),
    level        SMALLINT NOT NULL DEFAULT 1,
    biz_type     VARCHAR(50),
    biz_id       BIGINT,
    title        VARCHAR(200),
    content      TEXT,
    status       VARCHAR(20) NOT NULL DEFAULT 'pending', -- pending/processing/escalated/closed
    assignee_id  BIGINT,
    handle_remark VARCHAR(500),
    handled_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_alert_record_status ON alert_record (status);
CREATE INDEX idx_alert_record_assignee ON alert_record (assignee_id);

CREATE TABLE revitalization_task (
    id           BIGSERIAL PRIMARY KEY,
    asset_id     BIGINT NOT NULL,
    vacant_reason VARCHAR(50),
    plan_type    VARCHAR(50),
    assignee_id  BIGINT,
    target_date  DATE,
    status       VARCHAR(20) NOT NULL DEFAULT 'pending', -- pending/listing/signed/done
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ
);
CREATE INDEX idx_revitalization_asset ON revitalization_task (asset_id);

-- ---------------------------------------------------------------------------
-- 3.8 固定资产 / 无形资产
-- ---------------------------------------------------------------------------
CREATE TABLE fixed_asset (
    id           BIGSERIAL PRIMARY KEY,
    asset_no     VARCHAR(64) NOT NULL UNIQUE,
    name         VARCHAR(200) NOT NULL,
    asset_type   VARCHAR(50),
    company_id   BIGINT,
    original_value NUMERIC(18,2),
    net_value    NUMERIC(18,2),
    status       VARCHAR(30),
    user_name    VARCHAR(100),
    department_id BIGINT,
    location     VARCHAR(200),
    acquired_at  DATE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fixed_asset_company ON fixed_asset (company_id);

CREATE TABLE intangible_asset (
    id          BIGSERIAL PRIMARY KEY,
    asset_no    VARCHAR(64) NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    rights_type VARCHAR(50),
    original_value NUMERIC(18,2),
    net_value   NUMERIC(18,2),
    amortization_rule VARCHAR(200),
    expiry_date DATE,
    status      VARCHAR(30),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- 3.9 系统
-- ---------------------------------------------------------------------------
CREATE TABLE operation_log (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT,
    username    VARCHAR(64),
    module      VARCHAR(50),
    action      VARCHAR(50),
    ref_id      BIGINT,
    detail_json JSONB,
    ip          VARCHAR(64),
    trace_id    VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_operation_log_user ON operation_log (user_id);
CREATE INDEX idx_operation_log_created ON operation_log (created_at);

CREATE TABLE login_log (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT,
    username   VARCHAR(64),
    ip         VARCHAR(64),
    result     VARCHAR(20),
    fail_reason VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_login_log_created ON login_log (created_at);

CREATE TABLE file_metadata (
    id          BIGSERIAL PRIMARY KEY,
    bucket      VARCHAR(100),
    object_key  VARCHAR(500),
    file_name   VARCHAR(200),
    content_type VARCHAR(100),
    hash_sha256 CHAR(64),
    size        BIGINT,
    biz_type    VARCHAR(30),
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_file_metadata_biz ON file_metadata (biz_type);

CREATE TABLE callback_log (
    id          BIGSERIAL PRIMARY KEY,
    channel     VARCHAR(30),
    event_type  VARCHAR(50),
    request_json JSONB,
    result      VARCHAR(20),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE export_audit (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT,
    module      VARCHAR(50),
    scope_json  JSONB,
    row_count   INTEGER,
    file_hash   CHAR(64),
    watermark   VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_export_audit_created ON export_audit (created_at);

-- ---------------------------------------------------------------------------
-- 3.10 期初迁移
-- ---------------------------------------------------------------------------
CREATE TABLE migration_batch (
    id             BIGSERIAL PRIMARY KEY,
    cutover_date   DATE NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'importing', -- importing/reconciled/locked
    source_file    VARCHAR(500),
    balance_result NUMERIC(18,2),
    created_by     BIGINT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at      TIMESTAMPTZ
);

CREATE TABLE migration_import_log (
    id        BIGSERIAL PRIMARY KEY,
    batch_id  BIGINT NOT NULL,
    row_no    INTEGER,
    biz_type  VARCHAR(30),
    result    VARCHAR(20), -- success / failed
    error_msg VARCHAR(500),
    ref_id    BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_migration_log_batch ON migration_import_log (batch_id);

-- ---------------------------------------------------------------------------
-- 3.11 V2.5 补强表
-- ---------------------------------------------------------------------------
CREATE TABLE asset_transfer (
    id              BIGSERIAL PRIMARY KEY,
    asset_id        BIGINT NOT NULL,
    from_company_id BIGINT,
    to_company_id   BIGINT,
    transfer_type   VARCHAR(20) NOT NULL DEFAULT 'vacant', -- with_contract / vacant
    status          VARCHAR(20) NOT NULL DEFAULT 'draft', -- draft/approving/approved/completed
    effective_date  DATE,
    reason          VARCHAR(500),
    created_by      BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ
);
CREATE INDEX idx_asset_transfer_asset ON asset_transfer (asset_id);

CREATE TABLE config_version (
    id             BIGSERIAL PRIMARY KEY,
    config_key     VARCHAR(100) NOT NULL,
    config_value   VARCHAR(500),
    effective_date DATE NOT NULL,
    version        INTEGER NOT NULL DEFAULT 1,
    operator_id    BIGINT,
    old_value      VARCHAR(500),
    new_value      VARCHAR(500),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_config_key_effective ON config_version (config_key, effective_date);

CREATE TABLE apportion_config (
    id              BIGSERIAL PRIMARY KEY,
    company_id      BIGINT,
    project_id      BIGINT,
    apportion_basis VARCHAR(20) NOT NULL DEFAULT 'area', -- area/meter/head/usage
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE regulation_report (
    id           BIGSERIAL PRIMARY KEY,
    report_type  VARCHAR(50) NOT NULL,
    period       VARCHAR(20),
    content_json JSONB,
    status       VARCHAR(20) NOT NULL DEFAULT 'draft', -- draft/reviewed/submitted
    submitted_at TIMESTAMPTZ,
    created_by   BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_regulation_report_type_period ON regulation_report (report_type, period);

CREATE TABLE meter (
    id          BIGSERIAL PRIMARY KEY,
    asset_id    BIGINT NOT NULL,
    contract_id BIGINT,
    meter_type  VARCHAR(20) NOT NULL, -- water/electric/gas
    meter_no    VARCHAR(50),
    multiplier  NUMERIC(10,4) NOT NULL DEFAULT 1,
    status      SMALLINT NOT NULL DEFAULT 1
);
CREATE INDEX idx_meter_asset ON meter (asset_id);

CREATE TABLE meter_reading (
    id          BIGSERIAL PRIMARY KEY,
    meter_id    BIGINT NOT NULL,
    reading     NUMERIC(18,2) NOT NULL,
    reading_date DATE NOT NULL,
    usage       NUMERIC(18,2),
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_meter_reading_meter ON meter_reading (meter_id);

CREATE TABLE utility_bill (
    id          BIGSERIAL PRIMARY KEY,
    bill_id     BIGINT,
    contract_id BIGINT,
    meter_id    BIGINT,
    usage       NUMERIC(18,2),
    unit_price  NUMERIC(18,4),
    apportion_amount NUMERIC(18,2),
    amount      NUMERIC(18,2) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_utility_bill_contract ON utility_bill (contract_id);

CREATE TABLE bank_flow (
    id          BIGSERIAL PRIMARY KEY,
    flow_no     VARCHAR(100),
    bank_account VARCHAR(100),
    amount      NUMERIC(18,2) NOT NULL,
    direction   VARCHAR(20),
    trade_date  DATE,
    summary     VARCHAR(500),
    match_status VARCHAR(20) NOT NULL DEFAULT 'unmatched', -- matched/unmatched/partial
    payment_id  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_bank_flow_status ON bank_flow (match_status);

CREATE TABLE finance_voucher (
    id          BIGSERIAL PRIMARY KEY,
    biz_type    VARCHAR(50),
    biz_id      BIGINT,
    voucher_no  VARCHAR(100),
    status      VARCHAR(20) NOT NULL DEFAULT 'pending',
    content_json JSONB,
    pushed_at   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_finance_voucher_status ON finance_voucher (status);

CREATE TABLE business_plan (
    id          BIGSERIAL PRIMARY KEY,
    company_id  BIGINT,
    project_id  BIGINT,
    plan_year   INTEGER,
    plan_month  INTEGER,
    target_rental_rate NUMERIC(8,4),
    target_collection_rate NUMERIC(8,4),
    target_income NUMERIC(18,2),
    target_vacant_area NUMERIC(18,2),
    version     INTEGER NOT NULL DEFAULT 1,
    status      VARCHAR(20) NOT NULL DEFAULT 'active',
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ
);
CREATE INDEX idx_business_plan_company ON business_plan (company_id);

CREATE TABLE dunning_record (
    id          BIGSERIAL PRIMARY KEY,
    bill_id     BIGINT,
    contract_id BIGINT,
    tenant_id   BIGINT,
    level       SMALLINT NOT NULL,
    method      VARCHAR(20), -- sms/notice_post/lawyer_letter/legal
    content     TEXT,
    operator_id BIGINT,
    tenant_feedback VARCHAR(500),
    result      VARCHAR(50),
    photo_file_ids BIGINT[],
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dunning_record_bill ON dunning_record (bill_id);
CREATE INDEX idx_dunning_record_contract ON dunning_record (contract_id);

-- ---------------------------------------------------------------------------
-- 8.8 业务闭环单据
-- ---------------------------------------------------------------------------
CREATE TABLE disposal_order (
    id              BIGSERIAL PRIMARY KEY,
    asset_id        BIGINT NOT NULL,
    disposal_type   VARCHAR(20) NOT NULL, -- sale/scrap/transfer
    reason          VARCHAR(500),
    assessed_value  NUMERIC(18,2),
    book_value      NUMERIC(18,2),
    actual_amount   NUMERIC(18,2),
    counterparty    VARCHAR(200),
    status          VARCHAR(20) NOT NULL DEFAULT 'draft', -- draft/approving/rejected/pending_execute/executing/completed
    created_by      BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ
);
CREATE INDEX idx_disposal_order_asset ON disposal_order (asset_id);

CREATE TABLE occupation_order (
    id          BIGSERIAL PRIMARY KEY,
    asset_id    BIGINT NOT NULL,
    reason      VARCHAR(500),
    department  VARCHAR(200),
    start_date  DATE,
    end_date    DATE,
    area        NUMERIC(18,2),
    status      VARCHAR(20) NOT NULL DEFAULT 'draft', -- draft/approving/occupied/released
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ
);
CREATE INDEX idx_occupation_order_asset ON occupation_order (asset_id);

CREATE TABLE self_use_order (
    id          BIGSERIAL PRIMARY KEY,
    asset_id    BIGINT NOT NULL,
    department  VARCHAR(200),
    purpose     VARCHAR(200),
    start_date  DATE,
    end_date    DATE,
    area        NUMERIC(18,2),
    status      VARCHAR(20) NOT NULL DEFAULT 'draft', -- draft/approving/self_use/ended
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ
);
CREATE INDEX idx_self_use_order_asset ON self_use_order (asset_id);

CREATE TABLE refund_order (
    id            BIGSERIAL PRIMARY KEY,
    payment_id    BIGINT NOT NULL,
    amount        NUMERIC(18,2) NOT NULL,
    reason        VARCHAR(500),
    channel       VARCHAR(20),
    third_party_refund_no VARCHAR(100),
    status        VARCHAR(20) NOT NULL DEFAULT 'applying', -- applying/approving/executing/completed/rejected
    created_by    BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ
);
CREATE INDEX idx_refund_order_payment ON refund_order (payment_id);

CREATE TABLE evaluation_request (
    id            BIGSERIAL PRIMARY KEY,
    asset_id      BIGINT,
    project_id    BIGINT,
    purpose       VARCHAR(50), -- lease/disposal/filing
    institution   VARCHAR(200),
    status        VARCHAR(20) NOT NULL DEFAULT 'applying', -- applying/accepted/evaluating/reported
    result_value  NUMERIC(18,2),
    report_file_id BIGINT,
    created_by    BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ
);
CREATE INDEX idx_evaluation_request_asset ON evaluation_request (asset_id);

-- ---------------------------------------------------------------------------
-- 8.9 审批引擎
-- ---------------------------------------------------------------------------
CREATE TABLE approval_flow_def (
    id         BIGSERIAL PRIMARY KEY,
    biz_type   VARCHAR(50) NOT NULL,
    name       VARCHAR(100) NOT NULL,
    definition JSONB NOT NULL,
    version    INTEGER NOT NULL DEFAULT 1,
    enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE approval_instance (
    id           BIGSERIAL PRIMARY KEY,
    flow_def_id  BIGINT,
    biz_type     VARCHAR(50) NOT NULL,
    biz_id       BIGINT NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'pending', -- pending/approved/rejected
    submitted_by BIGINT,
    submitted_at TIMESTAMPTZ,
    current_node VARCHAR(50),
    completed_at TIMESTAMPTZ
);
CREATE INDEX idx_approval_instance_biz ON approval_instance (biz_type, biz_id);

CREATE TABLE approval_task (
    id          BIGSERIAL PRIMARY KEY,
    instance_id BIGINT NOT NULL,
    node_id     VARCHAR(50),
    assignee_id BIGINT,
    status      VARCHAR(20) NOT NULL DEFAULT 'pending', -- pending/done
    comment     TEXT,
    acted_at    TIMESTAMPTZ
);
CREATE INDEX idx_approval_task_assignee_status ON approval_task (assignee_id, status);

-- ---------------------------------------------------------------------------
-- 8.10 通知
-- ---------------------------------------------------------------------------
CREATE TABLE notification (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    title       VARCHAR(200),
    content     TEXT,
    biz_type    VARCHAR(50),
    biz_id      BIGINT,
    channel     VARCHAR(20) NOT NULL DEFAULT 'in_app', -- in_app/sms/mp
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_user_unread ON notification (user_id, read_at);

-- ---------------------------------------------------------------------------
-- 发票
-- ---------------------------------------------------------------------------
CREATE TABLE invoice_title (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  BIGINT NOT NULL,
    title      VARCHAR(200) NOT NULL,
    tax_no     VARCHAR(50),
    address    VARCHAR(500),
    bank       VARCHAR(200),
    account_no VARCHAR(50),
    status     SMALLINT NOT NULL DEFAULT 1
);
CREATE INDEX idx_invoice_title_tenant ON invoice_title (tenant_id);

CREATE TABLE invoice (
    id           BIGSERIAL PRIMARY KEY,
    invoice_no   VARCHAR(100),
    payment_id   BIGINT,
    bill_id      BIGINT,
    title_id     BIGINT,
    amount       NUMERIC(18,2) NOT NULL,
    tax_rate     NUMERIC(8,4),
    tax_amount   NUMERIC(18,2),
    status       VARCHAR(20) NOT NULL DEFAULT 'pending_issue', -- pending_issue/issuing/issued/red_flushing/red_flushed/failed
    third_party_no VARCHAR(100),
    red_flush_ref_id BIGINT,
    created_by   BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ
);
CREATE INDEX idx_invoice_payment ON invoice (payment_id);
CREATE INDEX idx_invoice_status ON invoice (status);

CREATE TABLE invoice_reconcile (
    id          BIGSERIAL PRIMARY KEY,
    invoice_id  BIGINT NOT NULL,
    payment_id  BIGINT NOT NULL,
    bill_id     BIGINT NOT NULL,
    amount      NUMERIC(18,2) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_invoice_reconcile_invoice ON invoice_reconcile (invoice_id);

CREATE TABLE invoice_tax_rate (
    id          BIGSERIAL PRIMARY KEY,
    tax_code    VARCHAR(50),
    tax_name    VARCHAR(100),
    rate        NUMERIC(8,4) NOT NULL,
    effective_date DATE,
    status      SMALLINT NOT NULL DEFAULT 1
);

-- ---------------------------------------------------------------------------
-- 8.x 智能 Agent（P1）
-- ---------------------------------------------------------------------------
CREATE TABLE agent_session (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    company_id  BIGINT,
    title       VARCHAR(200),
    status      VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_run (
    id          BIGSERIAL PRIMARY KEY,
    session_id  BIGINT,
    user_id     BIGINT NOT NULL,
    intent      VARCHAR(50),
    template_id VARCHAR(64),
    status      VARCHAR(20) NOT NULL DEFAULT 'running',
    trace_id    VARCHAR(64),
    error_msg   VARCHAR(500),
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
CREATE INDEX idx_agent_run_session ON agent_run (session_id);

CREATE TABLE agent_tool_call (
    id               BIGSERIAL PRIMARY KEY,
    run_id           BIGINT NOT NULL,
    tool_name        VARCHAR(100),
    request_json     JSONB,
    response_hash    VARCHAR(64),
    response_summary JSONB,
    duration_ms      INTEGER,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_tool_call_run ON agent_tool_call (run_id);

CREATE TABLE agent_report (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT,
    title        VARCHAR(200),
    format       VARCHAR(20),
    status       VARCHAR(20) NOT NULL DEFAULT 'draft',
    preview_path VARCHAR(500),
    file_path    VARCHAR(500),
    verified     BOOLEAN NOT NULL DEFAULT FALSE,
    approved_by  BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_report_run ON agent_report (run_id);

CREATE TABLE agent_report_citation (
    id           BIGSERIAL PRIMARY KEY,
    report_id    BIGINT NOT NULL,
    claim_key    VARCHAR(100),
    claim_value  TEXT,
    tool_call_id BIGINT,
    api_path     VARCHAR(200),
    verified     BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_agent_citation_report ON agent_report_citation (report_id);

CREATE TABLE agent_prompt_template (
    id            BIGSERIAL PRIMARY KEY,
    template_code VARCHAR(64) NOT NULL UNIQUE,
    name          VARCHAR(200),
    definition    JSONB,
    version       INTEGER NOT NULL DEFAULT 1,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_model_audit (
    id                BIGSERIAL PRIMARY KEY,
    run_id            BIGINT NOT NULL,
    model_id          VARCHAR(100),
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    sensitivity       VARCHAR(20),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_model_audit_run ON agent_model_audit (run_id);

-- ============================================================================
-- 初始数据（字典/角色/菜单）
-- ============================================================================
INSERT INTO role (code, name, data_scope) VALUES
    ('super_admin', '系统管理员', 'all'),
    ('operator',    '运营管理员', 'company'),
    ('asset_mgr',   '资产管理员', 'company'),
    ('finance',     '财务人员',   'company'),
    ('leader',      '决策层/领导', 'company'),
    ('maintenance', '维修管理员', 'company'),
    ('approver',    '审批人员',   'company'),
    ('clerk',       '办事员/业务员', 'dept');

-- 预警默认规则示例（租金逾期 L1-L5、合同到期、抵押到期）
INSERT INTO alert_rule (alert_type, sub_type, level, condition_json, enabled) VALUES
    ('rent_overdue', 'remind', 1, '{"days_before": -1}', TRUE),
    ('rent_overdue', 'notice', 2, '{"overdue_days": 7}', TRUE),
    ('rent_overdue', 'lawyer', 3, '{"overdue_days": 30}', TRUE),
    ('rent_overdue', 'legal',  4, '{"overdue_days": 90}', TRUE),
    ('contract_expiry', 'remind', 1, '{"days_before": 30}', TRUE),
    ('mortgage_expiry', 'remind', 1, '{"days_before": 30}', TRUE);

-- 默认审批流程（合同/处置/退款/占用）
INSERT INTO approval_flow_def (biz_type, name, definition, version, enabled) VALUES
    ('contract', '租赁合同审批',
     '{"bizType":"contract","nodes":[{"id":"dept","type":"serial","role":"approver"},{"id":"finance","type":"serial","role":"finance","when":"amount>100000"}]}',
     1, TRUE),
    ('disposal', '资产处置审批',
     '{"bizType":"disposal","nodes":[{"id":"major","type":"serial","role":"leader","flags":["triple_major"]}]}',
     1, TRUE),
    ('refund', '退款审批',
     '{"bizType":"refund","nodes":[{"id":"finance","type":"serial","role":"finance"}]}',
     1, TRUE),
    ('occupation', '临时占用审批',
     '{"bizType":"occupation","nodes":[{"id":"dept","type":"serial","role":"approver"}]}',
     1, TRUE),
    ('self_use', '资产自用审批',
     '{"bizType":"self_use","nodes":[{"id":"dept","type":"serial","role":"approver"}]}',
     1, TRUE);

-- 预置报告模板（FR-AI-007 最低交付）
INSERT INTO agent_prompt_template (template_code, name, definition, version, enabled) VALUES
    ('TPL-OPS-001', '经营月报', '{"sections":["summary","rental_rate","collection","income_trend"],"outputs":["html","pptx","pdf","docx"]}', 1, TRUE),
    ('TPL-OPS-002', '出租率与空置分析', '{"sections":["vacancy_by_project","revitalization"],"outputs":["html","pdf"]}', 1, TRUE),
    ('TPL-OPS-003', '收缴与欠费专项', '{"sections":["receivable","top_arrears","dunning_level"],"outputs":["html","pdf"]}', 1, TRUE),
    ('TPL-OPS-004', '合同到期预警汇总', '{"sections":["expiring_30","expiring_60","expiring_90"],"outputs":["html","pdf"]}', 1, TRUE),
    ('TPL-OPS-005', '资产台账汇总', '{"sections":["scale","category","region"],"outputs":["html","pdf"]}', 1, TRUE);
