-- ============================================================================
-- V40 资产占用事件（asset_occupancy）：占用生效层 + 回填 + 区间互斥约束
--   决策依据：docs/adr/0019-asset-unit-and-occupancy-model.md（决策 C）
--   设计要点：
--     1) 单据层（contract / occupation_order / self_use_order / disposal_order）与生效层分离：
--        单据可多个、可驳回；生效层只存「已生效的占用区间」，由它保证互斥。
--     2) 四种占用类型本质相同（谁在何时占用哪块面积），统一为一张表。
--     3) vacating 不是独立状态，而是 biz_status；leasing 是意向，不入本表。
--     4) 同一单元同一时点最多一个生效占用 —— 由 EXCLUDE 约束硬保证，不依赖服务层校验。
--   依赖：V39__asset_unit.sql（asset_unit + contract.asset_unit_id）
--   幂等：CREATE ... IF NOT EXISTS + NOT EXISTS 守卫；EXCLUDE 约束先判存在再加。
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------------------
-- 1) 资产生命周期与占用率派生列
--    lifecycle_status：「已退出」是资产生命周期终态，不是一种占用状态（从 9 态中归还）。
--    occupancy_ratio：占用面积 / 单元面积合计，由 LeaseStatusDeriver 写入。
-- ---------------------------------------------------------------------------
ALTER TABLE asset ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'in_book';
ALTER TABLE asset ADD COLUMN IF NOT EXISTS occupancy_ratio  NUMERIC(5,2);

UPDATE asset
   SET lifecycle_status = 'exited'
 WHERE lease_control_status = 'exited'
   AND lifecycle_status <> 'exited';

COMMENT ON COLUMN asset.lifecycle_status IS 'in_book / exited；处置完成置 exited，不再用租控状态表达';
COMMENT ON COLUMN asset.occupancy_ratio IS '占用率（%）＝ 生效占用面积 / 单元面积合计；由 LeaseStatusDeriver 派生';

-- ---------------------------------------------------------------------------
-- 2) 占用生效层
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS asset_occupancy (
    id             BIGSERIAL PRIMARY KEY,
    asset_id       BIGINT        NOT NULL,
    -- 占用粒度：单元。NOT NULL 由 V39「每个资产至少一个单元」不变量保证。
    asset_unit_id  BIGINT        NOT NULL,
    occupancy_type VARCHAR(30)   NOT NULL,
    -- 来源单据类型与 ID（用于反查与联动释放）
    subject_type   VARCHAR(40)   NOT NULL,
    subject_id     BIGINT        NOT NULL,
    -- 占用面积；0 表示单元面积待补（不参与面积统计）
    area           NUMERIC(18,2) NOT NULL DEFAULT 0,
    date_from      DATE          NOT NULL,
    -- 为空表示无固定期限（在租合同）
    date_to        DATE,
    -- 单据自身状态：active / vacating（仅 contract 使用）
    biz_status     VARCHAR(20)   NOT NULL DEFAULT 'active',
    -- false 的占用不参与互斥（预留给未来的非排他记录，如巡查临时进入）
    exclusive      BOOLEAN       NOT NULL DEFAULT true,
    released_at    TIMESTAMPTZ,
    remark         VARCHAR(500),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ,
    created_by     BIGINT,
    updated_by     BIGINT,
    CONSTRAINT ck_occupancy_type
        CHECK (occupancy_type IN ('contract', 'self_use', 'occupation', 'disposal')),
    CONSTRAINT ck_occupancy_area CHECK (area >= 0),
    -- 保证 daterange(date_from, date_to, '[)') 可安全求值（下界必须小于上界）
    CONSTRAINT ck_occupancy_range CHECK (date_to IS NULL OR date_to > date_from)
);

CREATE INDEX IF NOT EXISTS idx_occupancy_unit    ON asset_occupancy (asset_unit_id, date_from);
CREATE INDEX IF NOT EXISTS idx_occupancy_asset   ON asset_occupancy (asset_id, occupancy_type);
CREATE INDEX IF NOT EXISTS idx_occupancy_subject ON asset_occupancy (subject_type, subject_id);

COMMENT ON TABLE asset_occupancy IS '占用生效层：单据审批通过时写入，区间收口代替删除';
COMMENT ON COLUMN asset_occupancy.area IS '占用面积；原子单元模型下恒等于 asset_unit.area';
COMMENT ON COLUMN asset_occupancy.biz_status IS 'active / vacating；退租中占用仍存在（租户未搬走）';

-- ---------------------------------------------------------------------------
-- 3) 回填：单据 → 占用
--    优先级 contract > self_use > occupation > disposal（先插入者胜），
--    每步用「区间重叠守卫」跳过冲突行，使存量脏数据不会阻塞后续 EXCLUDE 约束建立。
--    被跳过的冲突行由 v_asset_lease_status_reconcile 暴露，供人工核对。
-- ---------------------------------------------------------------------------

-- 3.1 在租/续租/到期合同 → contract 占用；已有未完成退租单的标记 vacating
INSERT INTO asset_occupancy (asset_id, asset_unit_id, occupancy_type, subject_type, subject_id,
                             area, date_from, date_to, biz_status, remark)
SELECT c.asset_id,
       c.asset_unit_id,
       'contract',
       'contract',
       c.id,
       COALESCE(c.lease_area, u.area, 0),
       c.start_date,
       c.end_date,
       CASE WHEN EXISTS (
                SELECT 1 FROM vacate_order v
                 WHERE v.contract_id = c.id AND v.status <> 'completed'
            ) THEN 'vacating' ELSE 'active' END,
       'V40 回填：在租合同'
  FROM contract c
  JOIN asset_unit u ON u.id = c.asset_unit_id AND u.deleted_at IS NULL
 WHERE c.status IN ('active', 'expiring', 'renewable')
   AND c.end_date > c.start_date
   AND NOT EXISTS (
        SELECT 1 FROM asset_occupancy x
         WHERE x.subject_type = 'contract' AND x.subject_id = c.id
       );

-- 3.2 自用生效中 → self_use 占用
INSERT INTO asset_occupancy (asset_id, asset_unit_id, occupancy_type, subject_type, subject_id,
                             area, date_from, date_to, biz_status, remark)
SELECT o.asset_id,
       u.id,
       'self_use',
       'self_use_order',
       o.id,
       -- A1 原子单元：占用面积恒等于单元面积。
       -- 存量单据的 area 是「整资产级状态」时代的描述性字段（服务层从未据此校验，
       -- 见 OccupationService.create 的 assertVacant），故不采信；差异保留在
       -- v_legacy_area_mismatch 供后续按需拆单元。
       u.area,
       COALESCE(o.start_date, o.created_at::date, CURRENT_DATE),
       o.end_date,
       'active',
       'V40 回填：自用单'
  FROM self_use_order o
  JOIN LATERAL (
        SELECT u2.id, u2.area
          FROM asset_unit u2
         WHERE u2.asset_id = o.asset_id AND u2.deleted_at IS NULL
         ORDER BY u2.id
         LIMIT 1
       ) u ON TRUE
 WHERE o.status = 'self_use'
   AND (o.end_date IS NULL OR o.end_date > COALESCE(o.start_date, o.created_at::date, CURRENT_DATE))
   AND NOT EXISTS (
        SELECT 1 FROM asset_occupancy x
         WHERE x.subject_type = 'self_use_order' AND x.subject_id = o.id
       )
   AND NOT EXISTS (
        SELECT 1 FROM asset_occupancy x
         WHERE x.asset_unit_id = u.id
           AND daterange(x.date_from, x.date_to, '[)')
            && daterange(COALESCE(o.start_date, o.created_at::date, CURRENT_DATE), o.end_date, '[)')
       );

-- 3.3 临时占用生效中 → occupation 占用
INSERT INTO asset_occupancy (asset_id, asset_unit_id, occupancy_type, subject_type, subject_id,
                             area, date_from, date_to, biz_status, remark)
SELECT o.asset_id,
       u.id,
       'occupation',
       'occupation_order',
       o.id,
       -- 同 self_use：采信单元面积，保持 A1 不变量（占用面积 == 单元面积）
       u.area,
       COALESCE(o.start_date, o.created_at::date, CURRENT_DATE),
       o.end_date,
       'active',
       'V40 回填：占用单'
  FROM occupation_order o
  JOIN LATERAL (
        SELECT u2.id, u2.area
          FROM asset_unit u2
         WHERE u2.asset_id = o.asset_id AND u2.deleted_at IS NULL
         ORDER BY u2.id
         LIMIT 1
       ) u ON TRUE
 WHERE o.status = 'occupied'
   AND (o.end_date IS NULL OR o.end_date > COALESCE(o.start_date, o.created_at::date, CURRENT_DATE))
   AND NOT EXISTS (
        SELECT 1 FROM asset_occupancy x
         WHERE x.subject_type = 'occupation_order' AND x.subject_id = o.id
       )
   AND NOT EXISTS (
        SELECT 1 FROM asset_occupancy x
         WHERE x.asset_unit_id = u.id
           AND daterange(x.date_from, x.date_to, '[)')
            && daterange(COALESCE(o.start_date, o.created_at::date, CURRENT_DATE), o.end_date, '[)')
       );

-- 3.4 处置进行中 → disposal 占用（无期限：处置完成才收口）
INSERT INTO asset_occupancy (asset_id, asset_unit_id, occupancy_type, subject_type, subject_id,
                             area, date_from, date_to, biz_status, remark)
SELECT o.asset_id,
       u.id,
       'disposal',
       'disposal_order',
       o.id,
       u.area,
       COALESCE(o.created_at::date, CURRENT_DATE),
       NULL,
       'active',
       'V40 回填：处置单'
  FROM disposal_order o
  JOIN LATERAL (
        SELECT u2.id, u2.area
          FROM asset_unit u2
         WHERE u2.asset_id = o.asset_id AND u2.deleted_at IS NULL
         ORDER BY u2.id
         LIMIT 1
       ) u ON TRUE
 WHERE o.status IN ('pending_execute', 'executing')
   AND NOT EXISTS (
        SELECT 1 FROM asset_occupancy x
         WHERE x.subject_type = 'disposal_order' AND x.subject_id = o.id
       )
   AND NOT EXISTS (
        SELECT 1 FROM asset_occupancy x
         WHERE x.asset_unit_id = u.id
           AND daterange(x.date_from, x.date_to, '[)')
            && daterange(COALESCE(o.created_at::date, CURRENT_DATE), NULL, '[)')
       );

-- ---------------------------------------------------------------------------
-- 3.5 派生视图：状态的单一真源（Java 侧 LeaseStatusDeriver 只读视图，不再复制派生逻辑）
--     注意：视图纯由「生效占用 + 招租意向」派生，不读 unit_status / lease_control_status，
--     因此不存在「派生列污染真源」的循环。
-- ---------------------------------------------------------------------------

-- 单元层派生状态
CREATE OR REPLACE VIEW v_unit_lease_status AS
SELECT u.id            AS unit_id,
       u.asset_id      AS asset_id,
       CASE
           WHEN EXISTS (SELECT 1 FROM asset_occupancy o
                         WHERE o.asset_unit_id = u.id
                           AND o.occupancy_type = 'disposal'
                           AND CURRENT_DATE <@ daterange(o.date_from, o.date_to, '[)'))
               THEN 'disposing'
           WHEN EXISTS (SELECT 1 FROM asset_occupancy o
                         WHERE o.asset_unit_id = u.id
                           AND o.occupancy_type = 'self_use'
                           AND CURRENT_DATE <@ daterange(o.date_from, o.date_to, '[)'))
               THEN 'self_use'
           WHEN EXISTS (SELECT 1 FROM asset_occupancy o
                         WHERE o.asset_unit_id = u.id
                           AND o.occupancy_type = 'occupation'
                           AND CURRENT_DATE <@ daterange(o.date_from, o.date_to, '[)'))
               THEN 'occupied'
           WHEN EXISTS (SELECT 1 FROM asset_occupancy o
                         WHERE o.asset_unit_id = u.id
                           AND o.occupancy_type = 'contract'
                           AND CURRENT_DATE <@ daterange(o.date_from, o.date_to, '[)'))
               THEN 'leased'
           WHEN EXISTS (SELECT 1 FROM lease_listing l
                         WHERE l.asset_unit_id = u.id AND l.status = 'active')
               THEN 'leasing'
           ELSE 'vacant'
       END AS derived_status,
       COALESCE((SELECT SUM(o.area) FROM asset_occupancy o
                  WHERE o.asset_unit_id = u.id
                    AND CURRENT_DATE <@ daterange(o.date_from, o.date_to, '[)')), 0) AS occupied_area
  FROM asset_unit u
 WHERE u.deleted_at IS NULL;

-- 资产层派生状态（对齐 9 值，兼容现有筛选与统计）
CREATE OR REPLACE VIEW v_asset_lease_status_derived AS
WITH unit_agg AS (
    SELECT v.asset_id,
           COUNT(*)                                                         AS unit_count,
           COUNT(*) FILTER (WHERE v.derived_status = 'leasing')              AS leasing_count,
           COUNT(*) FILTER (WHERE v.derived_status IN ('vacant', 'leasing'))  AS free_like_count,
           MAX(CASE v.derived_status
                   WHEN 'disposing' THEN 4
                   WHEN 'occupied'  THEN 3
                   WHEN 'self_use'  THEN 2
                   WHEN 'leased'    THEN 1
                   ELSE 0 END)                                              AS max_rank,
           SUM(u.area)                                                       AS unit_area,
           SUM(v.occupied_area)                                              AS occupied_area
      FROM v_unit_lease_status v
      JOIN asset_unit u ON u.id = v.unit_id
     GROUP BY v.asset_id
)
SELECT a.id                   AS asset_id,
       a.asset_no             AS asset_no,
       a.lease_control_status AS stored_status,
       a.lifecycle_status     AS lifecycle_status,
       CASE
           WHEN a.lifecycle_status = 'exited' THEN 'exited'
           WHEN g.asset_id IS NULL OR g.free_like_count = g.unit_count
               THEN CASE WHEN COALESCE(g.leasing_count, 0) > 0 THEN 'leasing' ELSE 'vacant' END
           WHEN g.free_like_count = 0
               THEN CASE g.max_rank
                        WHEN 4 THEN 'disposing'
                        WHEN 3 THEN 'occupied'
                        WHEN 2 THEN 'self_use'
                        WHEN 1 THEN 'leased'
                        ELSE 'vacant' END
           ELSE 'partial_leased'
       END AS derived_status,
       CASE WHEN COALESCE(g.unit_area, 0) > 0
            THEN ROUND(COALESCE(g.occupied_area, 0) / g.unit_area * 100, 2)
            ELSE NULL END AS occupancy_ratio
  FROM asset a
  LEFT JOIN unit_agg g ON g.asset_id = a.id
 WHERE a.deleted_at IS NULL;

-- 派生一致性差异（物化列 vs 视图）—— 切换读路径前必须为空
CREATE OR REPLACE VIEW v_asset_lease_status_reconcile AS
SELECT asset_id,
       asset_no,
       stored_status,
       derived_status,
       lifecycle_status,
       occupancy_ratio
  FROM v_asset_lease_status_derived
 WHERE stored_status IS DISTINCT FROM derived_status;

COMMENT ON VIEW v_unit_lease_status IS '单元层派生租控状态：占用集合 + 招租意向的单一真源';
COMMENT ON VIEW v_asset_lease_status_derived IS '资产层派生租控状态（9 值），兼容既有筛选与统计口径';
COMMENT ON VIEW v_asset_lease_status_reconcile IS '物化派生列与视图不一致清单；为空才能切换读路径';

-- ---------------------------------------------------------------------------
-- 4) 区间互斥硬约束
--    同一单元、同一时点、最多一个生效占用。EXCLUDE 对任何写入口生效
--    （含 ImportExportService / MigrationService 等绕过服务层的路径）。
--    若此处失败，说明回填守卫未能覆盖的存量冲突仍存在，
--    请先执行：SELECT * FROM v_asset_lease_status_reconcile; 并清理后再上线。
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ex_occupancy_unit_no_overlap'
    ) THEN
        ALTER TABLE asset_occupancy
            ADD CONSTRAINT ex_occupancy_unit_no_overlap
            EXCLUDE USING gist (
                asset_unit_id WITH =,
                daterange(date_from, date_to, '[)') WITH &&
            ) WHERE (exclusive);
    END IF;
END $$;
