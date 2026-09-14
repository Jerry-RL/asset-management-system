-- ============================================================================
-- V56 抵押记录：把既有「资产抵押」扩成「项目 / 分区 / 资产」三级标的的抵押记录
--
-- 为什么是**扩展**既有 `mortgage` 表而不是另建一张 `mortgage_record`：
--   `mortgage` 已经是一处**被依赖的语义**，不是单纯的数据表 ——
--     · `CertificateService.hasActiveMortgage` / `assertNotMortgaged` 是 5 处业务的前置校验
--       （处置、权属流转、拆分合并、调拨）；
--     · 它驱动 `asset_certificate.mortgage_status`（权证「在押 / 无抵押」）；
--     · `listExpiring` 驱动抵押到期预警与运营日历。
--   另建一张表会让「项目被抵押」这件事在以上四处**完全不可见** ——
--   被抵押项目下的资产照样能被处置、被流转，而界面上一切正常。那是静默的正确性漏洞，
--   不是「两个模块并存」。
--
-- 状态机：`draft → active → released`。
--   · 新增的 `draft` 落在**同一个 `status` 列**上，而不是另加一列 `record_status`：
--     `hasActiveMortgage` 与 `listExpiring` 都按 `status = 'active'` 过滤，
--     草稿天然不算在押、不进预警、不动权证状态 —— 不需要改这三处的口径。
--     若另加一列，则三处读点都必须同时改，漏一处就是「草稿被当成在押」或反之。
--   · `active → released` 沿用既有解押审批（`mortgage_release`），本期不改。
--
-- 三级标的的过滤列（本迁移只建列与索引，判定在应用层）：
--   asset   → asset.id（且必须属于 company_id）
--   zone    → project_zone.id（zone 无 company_id，经 project.company_id 判定）
--   project → project.id
-- `asset_id` 保留：它是资产级抵押的历史列，且被多处按 asset_id 反查；
--   资产级抵押下 asset_id 与 target_id 同值（由服务层维持），避免留下两套真相。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 标的：类型 + 标的 id
-- ---------------------------------------------------------------------------

ALTER TABLE mortgage ADD COLUMN IF NOT EXISTS target_type VARCHAR(20);
ALTER TABLE mortgage ADD COLUMN IF NOT EXISTS target_id   BIGINT;

-- 存量行一律是资产抵押（V2 建表时 mortgage.asset_id 是 NOT NULL，只有这一种可能）
UPDATE mortgage SET target_type = 'asset' WHERE target_type IS NULL;
UPDATE mortgage SET target_id   = asset_id  WHERE target_id   IS NULL;

-- target_type 给默认值，让「忘记写」退化成资产抵押而不是 NULL；
-- target_id 刻意**不给默认值也不允许 NULL** —— 没有标的的抵押记录会静默地拦住所有人，
-- 或者静默地拦不住任何人，两种都无法排查。宁可 insert 直接报错。
ALTER TABLE mortgage ALTER COLUMN target_type SET DEFAULT 'asset';
ALTER TABLE mortgage ALTER COLUMN target_type SET NOT NULL;
ALTER TABLE mortgage ALTER COLUMN target_id   SET NOT NULL;

-- 项目 / 分区抵押没有 asset_id，放开 NOT NULL
ALTER TABLE mortgage ALTER COLUMN asset_id DROP NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. 所属公司：列表筛选与「标的必须属于该公司」的校验都靠它
--
-- 由存量行的资产公司回填。刻意**不**设 NOT NULL：`asset.asset_company_id` 本身可空，
-- 回填后仍可能有 NULL；NOT NULL 会让迁移在某些数据集上直接失败，而这类失败发生在
-- 上线时刻。约束改由服务层校验（新记录必填）。
-- ---------------------------------------------------------------------------
ALTER TABLE mortgage ADD COLUMN IF NOT EXISTS company_id BIGINT;
UPDATE mortgage m
   SET company_id = a.asset_company_id
  FROM asset a
 WHERE a.id = m.asset_id
   AND m.company_id IS NULL;

-- ---------------------------------------------------------------------------
-- 3. 表单字段
-- ---------------------------------------------------------------------------
ALTER TABLE mortgage ADD COLUMN IF NOT EXISTS interest_rate   NUMERIC(8,4);
ALTER TABLE mortgage ADD COLUMN IF NOT EXISTS bank            VARCHAR(200);
ALTER TABLE mortgage ADD COLUMN IF NOT EXISTS repayment_date  DATE;
ALTER TABLE mortgage ADD COLUMN IF NOT EXISTS term_months     INTEGER;
ALTER TABLE mortgage ADD COLUMN IF NOT EXISTS contract_no     VARCHAR(100);
-- 草稿可删。既有解押流程只把行标记为 released，从不删行，因此本列对老数据全为 NULL。
ALTER TABLE mortgage ADD COLUMN IF NOT EXISTS deleted_at      TIMESTAMPTZ;

COMMENT ON COLUMN mortgage.target_type IS '标的类型：project / zone / asset';
COMMENT ON COLUMN mortgage.target_id IS '标的 id：project.id / project_zone.id / asset.id';
COMMENT ON COLUMN mortgage.asset_id IS '资产级抵押时的资产 id（与 target_id 同值），历史列，被按资产反查的地方依赖';
COMMENT ON COLUMN mortgage.company_id IS '所属公司（标的归属公司），列表筛选与标的校验用';
COMMENT ON COLUMN mortgage.interest_rate IS '利率（百分数，如 4.3500 表示 4.35%），最多 4 位小数';
COMMENT ON COLUMN mortgage.bank IS '抵押银行';
COMMENT ON COLUMN mortgage.repayment_date IS '还款日（业务到期日）';
COMMENT ON COLUMN mortgage.term_months IS '抵押期限（月），与 start_date 一起推导 end_date';
COMMENT ON COLUMN mortgage.contract_no IS '抵押合同编号';
COMMENT ON COLUMN mortgage.deleted_at IS '软删时间（草稿可删；仅 null 的行可见）';

-- ---------------------------------------------------------------------------
-- 4. 索引
-- ---------------------------------------------------------------------------

-- 列表页按标的类型筛选
CREATE INDEX IF NOT EXISTS idx_mortgage_target ON mortgage (target_type, target_id);
-- 列表页按所属公司筛选
CREATE INDEX IF NOT EXISTS idx_mortgage_company ON mortgage (company_id);
-- 在押校验：按 (status, target_type, target_id) 命中，三级标的都走这一条
CREATE INDEX IF NOT EXISTS idx_mortgage_status_target
    ON mortgage (status, target_type, target_id);
-- 抵押合同编号唯一（部分索引：只约束「已填写且未软删」的行）。
-- 不写成普通唯一索引：存量行与草稿的 contract_no 都是 NULL，普通唯一索引会把
-- 「多行 NULL」也纳入语义（PG 允许，但一旦有人给默认值 '' 就会互撞）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_mortgage_contract_no
    ON mortgage (contract_no)
 WHERE contract_no IS NOT NULL AND deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- 5. 菜单改名：该页从「只读的抵押列表」升级为完整的抵押记录模块
--    （列表 + 新建 / 编辑草稿 + 详情 + 解押入口）。path 不变，避免影响既有书签与镜像。
-- ---------------------------------------------------------------------------
UPDATE menu SET name = '抵押记录' WHERE code = 'deed.mortgage';
