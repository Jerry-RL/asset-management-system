-- ============================================================================
-- V54 权属流转（ownership_transfer）：多资产改产权公司 / 经营公司
--
-- 设计见 docs/superpowers/specs/2026-09-13-ownership-transfer-design.md。
--
-- 与既有「资产调拨」（asset_transfer，单资产、改 operating_company_id、不接审批引擎）
-- 并存不合并：本表是多资产 + 按权属类型选改哪个公司字段 + 附件。
--
-- 三条口径：
--   1. 无单号列，界面用 `#id`（与 disposal_order / asset_transfer 一致）；
--   2. 无外键约束（同上）—— 归属与状态由应用层断言，避免 FK 让迁移顺序变得脆弱；
--   3. 软删用 `deleted_at TIMESTAMPTZ` + 显式过滤，不用 @TableLogic
--      （全局逻辑删除字段口径是 deleted:0/1，与 deleted_at 范式不一致）。
-- 可重复执行：CREATE ... IF NOT EXISTS + 字典/菜单 ON CONFLICT DO NOTHING。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 主单
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ownership_transfer (
    id                BIGSERIAL PRIMARY KEY,
    -- 流转方向：internal 内部流转 / external 外部流转，取字典 transfer_direction。
    -- 由公司树（company.parent_id）判定「目标公司与原公司是否同根」，见 TransferDirectionResolver。
    direction         VARCHAR(20)  NOT NULL,
    -- 权属类型：both 经营权且产权 / property 产权 / operating 经营权，取字典 transfer_scope。
    -- 决定生效时改写 asset 的哪个字段：property→property_company_id、
    -- operating→operating_company_id、both→两个都改。
    transfer_scope    VARCHAR(20)  NOT NULL,
    from_company_id   BIGINT       NOT NULL,
    to_company_id     BIGINT       NOT NULL,
    -- 流转类型：allocate 直接划拨 / purchase 购买流转 / auction 拍卖流转，取字典 transfer_mode。
    transfer_mode     VARCHAR(30)  NOT NULL,
    -- 变更申请人：内员时 applicant_user_id 非空且 applicant_name 是 sys_user.name 快照；
    -- 外部人员时 applicant_user_id 为 NULL、applicant_name 手填。
    applicant_user_id BIGINT,
    applicant_name    VARCHAR(100) NOT NULL,
    -- 审批截止时间：本期无审批环节，纯记录字段（不校验、不提醒）。
    approval_deadline TIMESTAMPTZ,
    -- 金额(万元)，2 位小数；服务端拒绝超过 2 位小数的入参，不依赖 PG 静默四舍五入。
    amount_wan        NUMERIC(18,2),
    reason            VARCHAR(1000),
    -- 生效时生成的交接清单快照（欠费/保证金/预收/在租合同），形态同 asset_transfer.handover_json
    -- 是 TEXT 而不是 JSONB：实体字段是 String，JSONB 会在 insert 时类型不匹配（V4 全仓改过一轮）。
    handover_json     TEXT,
    -- draft / completed（本期无审批，故无 approving）
    status            VARCHAR(20)  NOT NULL DEFAULT 'draft',
    effected_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ,
    created_by        BIGINT,
    updated_by        BIGINT,
    deleted_at        TIMESTAMPTZ
);

-- 列表页默认按状态筛选 + id 倒序
CREATE INDEX IF NOT EXISTS idx_ownership_transfer_status
    ON ownership_transfer (status, id DESC);
-- 按原公司筛选（列表页筛选项之一）
CREATE INDEX IF NOT EXISTS idx_ownership_transfer_from
    ON ownership_transfer (from_company_id);

COMMENT ON COLUMN ownership_transfer.direction IS '流转方向：internal/external，取字典 transfer_direction';
COMMENT ON COLUMN ownership_transfer.transfer_scope IS '权属类型：both/property/operating，取字典 transfer_scope';
COMMENT ON COLUMN ownership_transfer.transfer_mode IS '流转类型：allocate/purchase/auction，取字典 transfer_mode';
COMMENT ON COLUMN ownership_transfer.amount_wan IS '金额(万元)，2 位小数';
COMMENT ON COLUMN ownership_transfer.approval_deadline IS '审批截止时间（本期无审批环节，纯记录）';
COMMENT ON COLUMN ownership_transfer.status IS 'draft/completed';

-- ---------------------------------------------------------------------------
-- 明细：一张单 × 多个资产
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ownership_transfer_asset (
    id                        BIGSERIAL PRIMARY KEY,
    transfer_id               BIGINT NOT NULL,
    asset_id                  BIGINT NOT NULL,
    -- 生效时的原值快照：生效后再流转 / 公司改名，也能追溯「当时从哪家公司转出」。
    -- 不靠 asset 现值反推 —— 那是「读完就变了」的数据。
    from_property_company_id  BIGINT,
    from_operating_company_id BIGINT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ,
    created_by                BIGINT,
    updated_by                BIGINT
);

-- 同一张单不允许重复挂同一个资产（请求体去重之外的服务端兜底）
CREATE UNIQUE INDEX IF NOT EXISTS uk_ownership_transfer_asset
    ON ownership_transfer_asset (transfer_id, asset_id);
-- 反查「某资产被哪些流转单改过」：资产档案的时间线段与排查都要用这条
CREATE INDEX IF NOT EXISTS idx_ownership_transfer_asset_asset
    ON ownership_transfer_asset (asset_id);

-- ---------------------------------------------------------------------------
-- asset 加列：权属状态
--
-- 为什么必须新列：外部流转生效后 lifecycle_status = 'exited'，而处置完成也是 'exited'；
-- 没有这一列就无法在资产上区分「卖掉了」和「转出去了」。
-- 加带 DEFAULT 的 NOT NULL 列在 PG 11+ 是快操作（不重写表）。
-- ---------------------------------------------------------------------------
ALTER TABLE asset ADD COLUMN IF NOT EXISTS ownership_status VARCHAR(20) NOT NULL DEFAULT 'in_group';
COMMENT ON COLUMN asset.ownership_status IS 'in_group 集团内 / transferred_out 已对外转出（外部权属流转生效后置位）';

-- ============================================================================
-- 字典：流转方向 / 权属类型 / 流转类型（挂在「资产管理字典」模块下，沿用 V19/V46/V49 写法）
-- sort 取 16/17/18：已占用 1、2、3、9、10、12、13、14、15
-- ============================================================================
INSERT INTO sys_dict_type (module_id, code, name, sort)
SELECT m.id, v.code, v.name, v.sort
FROM sys_dict_module m
JOIN (VALUES
    ('transfer_direction', '流转方向', 16),
    ('transfer_scope',     '权属类型', 17),
    ('transfer_mode',      '流转类型', 18)
) AS v(code, name, sort) ON TRUE
WHERE m.code = 'asset_management'
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_dict_item (type_id, value, label, sort)
SELECT t.id, v.value, v.label, v.sort
FROM sys_dict_type t
JOIN (VALUES
    ('transfer_direction', 'internal',  '内部流转',     1),
    ('transfer_direction', 'external',  '外部流转',     2),
    ('transfer_scope',     'both',      '经营权且产权', 1),
    ('transfer_scope',     'property',  '产权',         2),
    ('transfer_scope',     'operating', '经营权',       3),
    ('transfer_mode',      'allocate',  '直接划拨',     1),
    ('transfer_mode',      'purchase',  '购买流转',     2),
    ('transfer_mode',      'auction',   '拍卖流转',     3)
) AS v(type_code, value, label, sort) ON v.type_code = t.code
ON CONFLICT (type_id, value) DO NOTHING;

-- ============================================================================
-- 菜单：挂在「资债权证」目录（deed）下，sort 45 落在「评估申请」(40) 之后
--
-- 按 code = 'deed' 定位父目录，不依赖目录名 —— V53 刚把该目录改名为「资债权证记录」，
-- 两份迁移的先后顺序不影响本语句的正确性。
-- icon 留空：与 V45 口径一致（图标只给目录，菜单为空时侧栏回退前端 PATH_ICONS）。
-- ============================================================================
INSERT INTO menu (code, name, menu_type, path, icon, sort, parent_id)
SELECT 'deed.ownershipTransfer', '权属流转', 'menu', '/ownership-transfers', NULL, 45, d.id
FROM menu d WHERE d.code = 'deed'
ON CONFLICT (code) DO NOTHING;

-- view 回填：本菜单在 V45 之后新增，V45 §5.1 的回填只覆盖了当时的菜单行。
-- 不回填则除 super_admin 外所有角色都看不到入口 —— 新功能表现为「没做出来」。
-- 与 V45 §5.1 / V47 同口径：只回填 view、排除 super_admin、写动作一律不回填
-- （V45 §5.3 把动作级回填列为上线前置人工步骤；误回填的宽权限是静默的且会长期留在库里）。
INSERT INTO role_permission (role_id, menu_id, menu_code, action)
SELECT r.id, m.id, m.code, 'view'
FROM role r
CROSS JOIN menu m
WHERE r.code <> 'super_admin'
  AND m.code = 'deed.ownershipTransfer'
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 通知模板：与 V44 的 asset_transferred 同写法（无参数字典表）
-- ============================================================================
INSERT INTO notification_template (code, channel, title_tpl, body_tpl)
SELECT 'ownership_transferred', 'in_app', '资产权属流转已完成',
       '资产 #{{assetId}} 权属已由公司 #{{fromCompanyId}} 流转至公司 #{{toCompanyId}}，方向 {{direction}}，请双方对账'
WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE code = 'ownership_transferred');
