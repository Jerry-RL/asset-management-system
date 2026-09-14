-- ============================================================================
-- V55 资产调拨记录（asset_transfer_record）：多个资产改责任部门 / 责任人
--
-- 与既有「资产调拨」（asset_transfer，V2 建表、TransferService）**并存不合并**：
--   - asset_transfer 是**单资产 + 改 operating_company_id**（跨公司调拨），带审批状态；
--   - 本表是**多资产 + 改 responsible_department_id / responsible_user_id**
--     （同一公司内的责任交接），状态机只有 draft → completed。
--   两者改的资产字段完全不同，合并会让「调拨」同时意味着两件事。
--
-- 表名带 record 后缀：`asset_transfer` 已被 V2 占用，而本模块是「资产调拨**记录**」，
-- 一个组织内的责任交接台账。菜单同理挂在 `deed.transferRecord` 而不是复用 `deed.transfer`。
--
-- 三条口径（与 V54 权属流转一致）：
--   1. 无单号列，界面用 `#id`（与 disposal_order / asset_transfer 一致）；
--   2. 无外键约束 —— 归属与状态由应用层断言，避免 FK 让迁移顺序变得脆弱；
--   3. 软删用 `deleted_at TIMESTAMPTZ` + 显式过滤，不用 @TableLogic
--      （全局逻辑删除字段口径是 deleted:0/1，与 deleted_at 范式不一致）。
-- 可重复执行：CREATE ... IF NOT EXISTS + 菜单 ON CONFLICT DO NOTHING。
--
-- 本模块**不需要字典**：所有下拉都取自组织架构（公司 / 部门 / 员工），没有枚举字段。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 主单
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS asset_transfer_record (
    id                 BIGSERIAL PRIMARY KEY,
    -- 所属公司：所选资产必须全部属于这家公司，且生效时**不改**它 ——
    -- 本模块是公司内部的责任交接，公司归属由「权属流转」负责改。
    company_id         BIGINT       NOT NULL,
    -- 前责任部门：**业务留痕**，不做跨资产一致性校验（一张单可以挂来自不同部门的资产），
    -- 每个资产真实的原部门在明细行的 from_department_id 快照里。
    from_department_id BIGINT,
    to_department_id   BIGINT       NOT NULL,
    to_user_id         BIGINT       NOT NULL,
    -- 审批截止时间：本期无审批环节，纯记录字段（不校验、不提醒）。
    approval_deadline  TIMESTAMPTZ,
    reason             VARCHAR(500),
    remark             VARCHAR(500),
    -- draft / completed（本期无审批，故无 approving）
    status             VARCHAR(20)  NOT NULL DEFAULT 'draft',
    effected_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ,
    created_by         BIGINT,
    updated_by         BIGINT,
    deleted_at         TIMESTAMPTZ
);

-- 列表页默认按状态筛选 + id 倒序
CREATE INDEX IF NOT EXISTS idx_asset_transfer_record_status
    ON asset_transfer_record (status, id DESC);
-- 按所属公司筛选（列表页筛选项之一）
CREATE INDEX IF NOT EXISTS idx_asset_transfer_record_company
    ON asset_transfer_record (company_id);

COMMENT ON COLUMN asset_transfer_record.company_id IS '所属公司：资产公司（asset.asset_company_id），生效时不变';
COMMENT ON COLUMN asset_transfer_record.from_department_id IS '前责任部门（业务留痕，真实原值见明细行快照）';
COMMENT ON COLUMN asset_transfer_record.to_department_id IS '新责任部门（生效写入 asset.responsible_department_id）';
COMMENT ON COLUMN asset_transfer_record.to_user_id IS '新责任人（生效写入 asset.responsible_user_id）';
COMMENT ON COLUMN asset_transfer_record.approval_deadline IS '审批截止时间（本期无审批环节，纯记录）';
COMMENT ON COLUMN asset_transfer_record.status IS 'draft/completed';

-- ---------------------------------------------------------------------------
-- 明细：一张单 × 多个资产
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS asset_transfer_record_asset (
    id                 BIGSERIAL PRIMARY KEY,
    record_id          BIGINT NOT NULL,
    asset_id           BIGINT NOT NULL,
    -- 生效/起草时的原值快照：生效后再调拨、部门改名或人员调岗，也能追溯「当时从哪交接来的」。
    -- 不靠 asset 现值反推 —— 那是「读完就变了」的数据。
    from_department_id BIGINT,
    from_user_id       BIGINT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ,
    created_by         BIGINT,
    updated_by         BIGINT
);

-- 同一张单不允许重复挂同一个资产（请求体去重之外的服务端兜底）
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_transfer_record_asset
    ON asset_transfer_record_asset (record_id, asset_id);
-- 反查「某资产被哪些调拨记录改过」：资产档案的时间线段与排查都要用这条
CREATE INDEX IF NOT EXISTS idx_asset_transfer_record_asset_asset
    ON asset_transfer_record_asset (asset_id);

-- ============================================================================
-- 菜单：挂在「资债权证记录」目录（deed）下，sort 46 落在「权属流转」(45) 之后
--
-- 按 code = 'deed' 定位父目录，不依赖目录名 —— V53 已把该目录改名为「资债权证记录」，
-- 两份迁移的先后顺序不影响本语句的正确性。
-- icon 留空：与 V45 / V54 口径一致（图标只给目录，菜单为空时侧栏回退前端 PATH_ICONS）。
-- ============================================================================
INSERT INTO menu (code, name, menu_type, path, icon, sort, parent_id)
SELECT 'deed.transferRecord', '资产调拨记录', 'menu', '/asset-transfer-records', NULL, 46, d.id
FROM menu d WHERE d.code = 'deed'
ON CONFLICT (code) DO NOTHING;

-- view 回填：本菜单在 V45 之后新增，V45 §5.1 的回填只覆盖了当时的菜单行。
-- 不回填则除 super_admin 外所有角色都看不到入口 —— 新功能表现为「没做出来」。
-- 与 V45 §5.1 / V47 / V54 同口径：只回填 view、排除 super_admin、写动作一律不回填
-- （V45 §5.3 把动作级回填列为上线前置人工步骤；误回填的宽权限是静默的且会长期留在库里）。
INSERT INTO role_permission (role_id, menu_id, menu_code, action)
SELECT r.id, m.id, m.code, 'view'
FROM role r
CROSS JOIN menu m
WHERE r.code <> 'super_admin'
  AND m.code = 'deed.transferRecord'
ON CONFLICT DO NOTHING;
