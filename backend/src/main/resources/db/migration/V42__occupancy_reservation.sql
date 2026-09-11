-- ============================================================================
-- V42 占用预留态（reserving）：派生视图排除预留 + 预留孤儿诊断
--   决策依据：docs/adr/0020-bounded-contexts-and-aggregate-boundaries.md 决策 E
--   评审依据：docs/design/领域划分与限界上下文评审报告.md P0-6
--
--   背景：占用写入时机前移到「提交审批」（biz_status='reserving'），使审批期也进入
--        EXCLUDE 判定，修复「两个 approving 合同同时签同一单元」的并发超租。
--        代价是 asset_occupancy 会包含未批准的行，因此：
--          ① 派生租控状态（展示口径）必须排除 reserving；
--          ② 可用性判定（OccupancyPort.isAvailable）仍然包含 reserving。
--        只上预留态不上本迁移，会出现「审批中即显示在租」的回归。
--
--   本迁移语义变更（皆为向后兼容的展示口径修正）：
--     1) v_unit_lease_status：derived_status / occupied_area 排除 reserving；追加 reserved_area
--     2) v_asset_lease_status_derived：追加 reserved_area（其余口径不变，随 1 自动生效）
--     3) 新增 v_occupancy_reserving_orphan：预留行与来源单据状态不一致的诊断清单
--
--   刻意不改：EXCLUDE 约束的 WHERE (exclusive)（预留行 exclusive=true 天然参与互斥）、
--            ck_occupancy_type（预留是 biz_status，不是新占用类型）——这是决策 E 成本低的关键。
--
--   依赖：V39（asset_unit）、V40（asset_occupancy + 派生视图）、V41（诊断视图）
--   幂等：CREATE OR REPLACE VIEW（新增列仅可追加在末尾）+ COMMENT
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 单元层派生状态：排除预留行（展示口径），并单独暴露预留面积
--    列名/顺序/类型与 V40 完全一致，reserved_area 追加在末尾（PG 允许 OR REPLACE 追加列）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_unit_lease_status AS
SELECT u.id            AS unit_id,
       u.asset_id      AS asset_id,
       CASE
           WHEN EXISTS (SELECT 1 FROM asset_occupancy o
                         WHERE o.asset_unit_id = u.id
                           AND o.occupancy_type = 'disposal'
                           AND o.biz_status <> 'reserving'
                           AND CURRENT_DATE <@ daterange(o.date_from, o.date_to, '[)'))
               THEN 'disposing'
           WHEN EXISTS (SELECT 1 FROM asset_occupancy o
                         WHERE o.asset_unit_id = u.id
                           AND o.occupancy_type = 'self_use'
                           AND o.biz_status <> 'reserving'
                           AND CURRENT_DATE <@ daterange(o.date_from, o.date_to, '[)'))
               THEN 'self_use'
           WHEN EXISTS (SELECT 1 FROM asset_occupancy o
                         WHERE o.asset_unit_id = u.id
                           AND o.occupancy_type = 'occupation'
                           AND o.biz_status <> 'reserving'
                           AND CURRENT_DATE <@ daterange(o.date_from, o.date_to, '[)'))
               THEN 'occupied'
           WHEN EXISTS (SELECT 1 FROM asset_occupancy o
                         WHERE o.asset_unit_id = u.id
                           AND o.occupancy_type = 'contract'
                           AND o.biz_status <> 'reserving'
                           AND CURRENT_DATE <@ daterange(o.date_from, o.date_to, '[)'))
               THEN 'leased'
           WHEN EXISTS (SELECT 1 FROM lease_listing l
                         WHERE l.asset_unit_id = u.id AND l.status = 'active')
               THEN 'leasing'
           ELSE 'vacant'
       END AS derived_status,
       COALESCE((SELECT SUM(o.area) FROM asset_occupancy o
                  WHERE o.asset_unit_id = u.id
                    AND o.biz_status <> 'reserving'
                    AND CURRENT_DATE <@ daterange(o.date_from, o.date_to, '[)')), 0) AS occupied_area,
       -- 预留面积：审批中占用的排他面积，不计入 occupied_area / occupancy_ratio，
       -- 但可用于「有预留」标识与超租排查
       COALESCE((SELECT SUM(o.area) FROM asset_occupancy o
                  WHERE o.asset_unit_id = u.id
                    AND o.biz_status = 'reserving'
                    AND CURRENT_DATE <@ daterange(o.date_from, o.date_to, '[)')), 0) AS reserved_area
  FROM asset_unit u
 WHERE u.deleted_at IS NULL;

COMMENT ON VIEW v_unit_lease_status IS
    '单元层派生租控状态（展示口径，排除 reserving）；reserved_area 单列审批中预留面积；可用性口径见 OccupancyPort.isAvailable';

-- ---------------------------------------------------------------------------
-- 2) 资产层派生状态：口径随 1 自动生效（free_like_count / max_rank / occupied_area 均来自 1），
--    仅追加 reserved_area 供列表与报表标识「有预留」
-- ---------------------------------------------------------------------------
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
           SUM(v.occupied_area)                                              AS occupied_area,
           SUM(v.reserved_area)                                              AS reserved_area
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
            ELSE NULL END AS occupancy_ratio,
       COALESCE(g.reserved_area, 0) AS reserved_area
  FROM asset a
  LEFT JOIN unit_agg g ON g.asset_id = a.id
 WHERE a.deleted_at IS NULL;

COMMENT ON VIEW v_asset_lease_status_derived IS
    '资产层派生租控状态（9 值，兼容既有筛选）；reserved_area 为审批中预留面积合计';

-- v_asset_lease_status_reconcile 依赖 v_asset_lease_status_derived 的具名列，
-- 追加列不影响其定义，无需重建（PG 在查询时解析视图依赖）。

-- ---------------------------------------------------------------------------
-- 3) 预留孤儿诊断：reserving 且未收口，但来源单据已不在审批中 —— 需人工处置或批量清理
--    正常情况应始终为空；非空说明存在「预留已泄漏」（驳回未回调、进程中断、老数据直写）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_occupancy_reserving_orphan AS
SELECT o.id            AS occupancy_id,
       o.asset_id      AS asset_id,
       o.asset_unit_id AS asset_unit_id,
       o.subject_type  AS subject_type,
       o.subject_id    AS subject_id,
       o.date_from     AS date_from,
       o.created_at    AS created_at,
       CASE
           WHEN o.subject_type = 'contract'
                AND NOT EXISTS (SELECT 1 FROM contract d
                                 WHERE d.id = o.subject_id AND d.status = 'approving')
               THEN 'subject_not_approving'
           WHEN o.subject_type = 'occupation_order'
                AND NOT EXISTS (SELECT 1 FROM occupation_order d
                                 WHERE d.id = o.subject_id AND d.status = 'approving')
               THEN 'subject_not_approving'
           WHEN o.subject_type = 'self_use_order'
                AND NOT EXISTS (SELECT 1 FROM self_use_order d
                                 WHERE d.id = o.subject_id AND d.status = 'approving')
               THEN 'subject_not_approving'
           WHEN o.subject_type = 'disposal_order'
                AND NOT EXISTS (SELECT 1 FROM disposal_order d
                                 WHERE d.id = o.subject_id AND d.status = 'approving')
               THEN 'subject_not_approving'
           WHEN o.subject_type NOT IN ('contract', 'occupation_order',
                                       'self_use_order', 'disposal_order')
               THEN 'unknown_subject_type'
           ELSE 'ok'
       END AS check_result
  FROM asset_occupancy o
 WHERE o.biz_status = 'reserving'
   AND o.date_to IS NULL;

COMMENT ON VIEW v_occupancy_reserving_orphan IS
    '预留孤儿清单（INV-8）：check_result <> ok 的行需由占用域处置——正常应始终为空';

COMMENT ON COLUMN asset_occupancy.biz_status IS
    'active / vacating（退租中，占用仍有效）/ reserving（审批中预留，排他，派生状态排除、可用性包含）';
