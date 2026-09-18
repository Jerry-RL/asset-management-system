-- ============================================================================
-- V59 招租发布审批 + 资产租赁管理
--
-- 需求三条：
--   ① 资产可「发布招租信息」，在「资产租赁管理 → 招租中」产生一条**发布审批**记录；
--      审批通过后小程序端可见；驳回须填写原因；
--   ② 新增「资产租赁管理」菜单，按租控状态分 Tab（全部 / 招租中 / 租赁中 / 自用中 …）；
--   ③ 发布表单字段：资产、封面图、详情列表图、租金类型、年租金、是否推荐、排序、介绍；
--      资产详情与发起人信息仅在查看详情时可见。
--
-- 为什么不是「新建一张招租发布表」：
--   现有 `lease_listing` 已经是「招租标的」的真源，且 V40 / V42 的租控派生视图
--   （`v_unit_lease_status` / `v_asset_lease_status_derived`）正是按
--   `lease_listing.status = 'active' AND asset_unit_id = u.id` 判定 `leasing`。
--   另起一张表会让派生视图看不到新发布的招租，表现为「审批通过了但资产还是空置」。
--   因此本迁移只**扩展** `lease_listing`，并把 `status` 从二值扩为四值。
--
-- status 四值口径（唯一的语义变更）：
--   pending  待审批 —— 发布已提交，租控**不变**（仍是 vacant），小程序不可见
--   active   招租中 —— 审批通过，租控派生为 leasing，小程序可见
--   rejected 已驳回 —— 审批驳回，`reject_reason` 落库，可修改后重新提交
--   closed   已关闭 —— 人工结束招租，租控回落到 vacant
--   注意：`active` / `closed` 两个旧取值语义不变，V2 起的存量数据无需转换。
--
-- 可重复执行：ADD COLUMN IF NOT EXISTS + CREATE INDEX IF NOT EXISTS +
--             INSERT ... WHERE NOT EXISTS / ON CONFLICT DO NOTHING。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 发布表单字段
--
-- 命名沿用「列名 + _file_id / _url」的双列口径（与 `asset.image_file_id` /
-- `asset.image_url` 一致）：`fileId` 是附件真源（可追溯、可清理），`url` 是冗余出参
-- —— `/api/v1/files/object/**` 是白名单公开读，`<img>` 无法携带 Authorization，
-- 没有 `url` 列就只能每次现拼，客户端拿不到可直接渲染的地址。
-- ---------------------------------------------------------------------------
ALTER TABLE lease_listing
    -- 租金类型（复用 RENT_TYPE 字典：fixed_monthly / fixed_yearly / per_area / per_unit）
    ADD COLUMN IF NOT EXISTS rent_type           VARCHAR(30),
    -- 年租金（元）。与旧列 `rent_amount` 的关系见下方注释：本列是**表单主字段**，
    -- 提交时同步写入 `rent_amount`，使既有底价校验与小程序展示零改造。
    ADD COLUMN IF NOT EXISTS annual_rent         NUMERIC(18,2),
    -- 封面图
    ADD COLUMN IF NOT EXISTS cover_image_file_id BIGINT,
    ADD COLUMN IF NOT EXISTS cover_image_url     VARCHAR(500),
    -- 详情列表图：JSON 数组 [{ "fileId": 1, "url": "/api/v1/files/object/..." }]
    -- 用 TEXT 存 JSON 而非 JSONB：本仓 V4 已统一把业务 JSON 列降为 TEXT
    -- （`agent_prompt_template.definition` / `export_audit.scope_json` 等），
    -- 保持同一口径，避免又是 JSONB 又是 TEXT 的两套读写方式。
    ADD COLUMN IF NOT EXISTS detail_images       TEXT,
    -- 是否推荐（小程序端「推荐招租」）
    ADD COLUMN IF NOT EXISTS recommended         BOOLEAN NOT NULL DEFAULT FALSE,
    -- 排序号（越小越前）
    ADD COLUMN IF NOT EXISTS sort_no             INTEGER NOT NULL DEFAULT 0,
    -- 介绍
    ADD COLUMN IF NOT EXISTS intro               TEXT,
    -- 驳回原因（审批驳回时由审批事件回填，仅详情可见）
    ADD COLUMN IF NOT EXISTS reject_reason       VARCHAR(500),
    -- 发起人（sys_user.id）。本表不继承 BaseEntity（无 created_by 自动填充），
    -- 因此显式建列并由服务层写入。
    ADD COLUMN IF NOT EXISTS created_by          BIGINT;

COMMENT ON COLUMN lease_listing.rent_type IS '租金类型：fixed_monthly / fixed_yearly / per_area / per_unit';
COMMENT ON COLUMN lease_listing.annual_rent IS '年租金（元）；提交时同步写入 rent_amount 以兼容既有底价校验与小程序展示';
COMMENT ON COLUMN lease_listing.cover_image_file_id IS '封面图附件（sys_file.id）';
COMMENT ON COLUMN lease_listing.cover_image_url IS '封面图地址（冗余，公开读 /api/v1/files/object/**）';
COMMENT ON COLUMN lease_listing.detail_images IS '详情列表图，JSON 数组 [{fileId,url}]';
COMMENT ON COLUMN lease_listing.recommended IS '是否推荐（小程序端推荐招租）';
COMMENT ON COLUMN lease_listing.sort_no IS '排序号，越小越前';
COMMENT ON COLUMN lease_listing.reject_reason IS '审批驳回原因，仅详情可见';
COMMENT ON COLUMN lease_listing.created_by IS '发起人（sys_user.id）';

-- ---------------------------------------------------------------------------
-- 2. status 语义扩展
--
-- 只改注释，不加 CHECK 约束：存量行与新行共用同一列，加约束会在
-- 「历史值 + 新值」并存期把回滚路径堵死（与 asset.ownership_status 同口径，见 V57 §末尾）。
-- ---------------------------------------------------------------------------
COMMENT ON COLUMN lease_listing.status IS 'pending 待审批 / active 招租中 / rejected 已驳回 / closed 已关闭';

-- ---------------------------------------------------------------------------
-- 3. 小程序端列表索引
--
-- 端上查询固定为 `status = 'active'` + 推荐优先 + 排序号 + 倒序，
-- 三列组合索引让这条查询走一次索引扫描，而不是取回全部招租行再排序。
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_lease_listing_pub_sort
    ON lease_listing (status, recommended DESC, sort_no ASC, id DESC);

-- 列表按资产反查「该资产当前的招租发布」（详情 / 审批状态回显）
CREATE INDEX IF NOT EXISTS idx_lease_listing_asset_status
    ON lease_listing (asset_id, status);

-- ---------------------------------------------------------------------------
-- 4. 招租发布审批流程定义
--
-- bizType = 'lease_listing'，bizId = `lease_listing.id`。
-- 单节点串行（与 V6 的 `asset_audit_variance` 同形），角色 approver。
-- 缺这条定义时 `ApprovalEngine.start` 会走「无流程定义 = 自动通过」分支，
-- 招租就变成免审批直接发布 —— 而需求明确要求「出现一条发布审批数据」。
-- ---------------------------------------------------------------------------
INSERT INTO approval_flow_def (biz_type, name, definition, version, enabled)
SELECT 'lease_listing', '招租发布审批',
       '{"bizType":"lease_listing","nodes":[{"id":"asset_mgr","type":"serial","role":"approver"}]}',
       1, TRUE
WHERE NOT EXISTS (SELECT 1 FROM approval_flow_def WHERE biz_type = 'lease_listing');

-- ---------------------------------------------------------------------------
-- 5. 菜单：资产租赁管理
--
-- 挂在「资产运营」目录（operation）下，sort 25 落在「临时占用」(20) 之后、
-- 「资产自用」(30) 之前 —— 与 SRS §4.8「资产运营：资产租控、资产租赁、资产自用、资产处置」
-- 的排列一致。
--
-- 为什么归「资产运营」而不是「资产招租」：本页列的是**资产**、Tab 是**租控状态**
-- （含租赁中 / 自用中，不只是招租），语义是「运营状态视图」而非「招租单据」。
-- 与 V58 把「资产处置记录」移出本目录的理由互不冲突：那页是记录台账，本页是发起入口。
--
-- icon 留空：与 V45 / V54 / V55 / V57 口径一致（图标只给目录，菜单为空时侧栏回退
-- 前端 PATH_ICONS 的路由图标）。
-- ---------------------------------------------------------------------------
INSERT INTO menu (code, name, menu_type, path, icon, sort, parent_id)
SELECT 'operation.assetLeasing', '资产租赁管理', 'menu', '/asset-leasing', NULL, 25, d.id
FROM menu d WHERE d.code = 'operation'
ON CONFLICT (code) DO NOTHING;

-- view 回填：本菜单在 V45 之后新增，V45 §5.1 的回填只覆盖了当时的菜单行。
-- 不回填则除 super_admin 外所有角色都看不到入口 —— 新功能表现为「没做出来」。
-- 与 V45 §5.1 / V47 / V54 / V55 / V57 同口径：只回填 view、排除 super_admin、写动作一律不回填。
INSERT INTO role_permission (role_id, menu_id, menu_code, action)
SELECT r.id, m.id, m.code, 'view'
FROM role r
CROSS JOIN menu m
WHERE r.code <> 'super_admin'
  AND m.code = 'operation.assetLeasing'
ON CONFLICT DO NOTHING;
