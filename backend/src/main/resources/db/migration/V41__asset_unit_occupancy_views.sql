-- ============================================================================
-- V41 诊断与口径视图（只读）：单元预算一致性、缺失关联、占用时间轴
--   决策依据：docs/adr/0019-asset-unit-and-occupancy-model.md
--   本迁移为纯只读视图，不改任何业务数据，可在 V40 之后单独上线。
--   用途：
--     1) 单元面积预算核对（INV-1：Σ 单元面积 ≤ 资产面积）
--     2) 迁移遗漏清单（资产无单元 / 合同无单元 / 招租无单元）
--     3) 资产档案时间轴（AssetDossier.occupancies 的数据源）
--   幂等：CREATE OR REPLACE VIEW。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 单元面积预算核对（INV-1）
--    check_result：ok / area_missing / unit_area_exceeds_asset / no_unit
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_asset_unit_budget AS
WITH u AS (
    SELECT v.asset_id,
           COUNT(*)                                                          AS unit_count,
           SUM(au.area)                                                      AS unit_area,
           COUNT(*) FILTER (WHERE v.derived_status IN ('vacant', 'leasing'))  AS free_unit_count,
           SUM(v.occupied_area)                                              AS occupied_area
      FROM v_unit_lease_status v
      JOIN asset_unit au ON au.id = v.unit_id
     GROUP BY v.asset_id
)
SELECT a.id                        AS asset_id,
       a.asset_no                  AS asset_no,
       a.name                      AS name,
       COALESCE(a.area, 0)         AS asset_area,
       COALESCE(a.lease_area, 0)   AS asset_lease_area,
       COALESCE(u.unit_count, 0)   AS unit_count,
       COALESCE(u.unit_area, 0)    AS unit_area,
       ROUND(COALESCE(u.unit_area, 0) - COALESCE(a.area, 0), 2) AS unit_area_diff,
       COALESCE(u.free_unit_count, 0) AS free_unit_count,
       COALESCE(u.occupied_area, 0)   AS occupied_area,
       CASE
           WHEN COALESCE(u.unit_count, 0) = 0 THEN 'no_unit'
           WHEN COALESCE(u.unit_area, 0) = 0   THEN 'area_missing'
           WHEN ROUND(COALESCE(u.unit_area, 0) - COALESCE(a.area, 0), 2) > 0.005
               THEN 'unit_area_exceeds_asset'
           ELSE 'ok'
       END AS check_result
  FROM asset a
  LEFT JOIN u ON u.asset_id = a.id
 WHERE a.deleted_at IS NULL;

COMMENT ON VIEW v_asset_unit_budget IS '单元面积预算核对（INV-1）；check_result <> ok 需在收敛前处理';

-- ---------------------------------------------------------------------------
-- 2) 缺失关联清单
-- ---------------------------------------------------------------------------

-- 2.1 资产无单元（V39 不变量被破坏，通常是 ApplicationRunner 演示数据在 Flyway 之后写入）
CREATE OR REPLACE VIEW v_asset_without_unit AS
SELECT a.id                   AS asset_id,
       a.asset_no             AS asset_no,
       a.name                 AS name,
       a.asset_type           AS asset_type,
       a.area                 AS area,
       a.lease_control_status AS lease_control_status
  FROM asset a
 WHERE a.deleted_at IS NULL
   AND NOT EXISTS (
        SELECT 1 FROM asset_unit u
         WHERE u.asset_id = a.id AND u.deleted_at IS NULL
       );

-- 2.2 非终态合同无单元（收敛阶段将加 CHECK 约束，届时必须为空）
CREATE OR REPLACE VIEW v_contract_without_unit AS
SELECT c.id          AS contract_id,
       c.contract_no AS contract_no,
       c.asset_id    AS asset_id,
       c.status      AS status,
       c.lease_area  AS lease_area
  FROM contract c
 WHERE c.status NOT IN ('voided', 'terminated', 'expired')
   AND c.asset_unit_id IS NULL;

-- 2.3 生效招租无单元（无法被 v_unit_lease_status 识别为 leasing）
CREATE OR REPLACE VIEW v_listing_without_unit AS
SELECT l.id       AS listing_id,
       l.asset_id AS asset_id,
       l.status   AS status
  FROM lease_listing l
 WHERE l.status = 'active'
   AND l.asset_unit_id IS NULL;

-- 2.4 存量「单据面积 ≠ 单元面积」清单
--     旧模型把「整资产占为 occupied/self_use」，单据上的 area 只是描述性字段
--     （服务层从未据此校验，见 OccupationService.create 的 assertVacant）。
--     回填按 A1 采信单元面积，此处保留原始差异，供后续按业务需要拆单元。
CREATE OR REPLACE VIEW v_legacy_area_mismatch AS
SELECT 'self_use_order' AS subject_type, o.id AS subject_id, o.asset_id,
       u.unit_no, o.area AS legacy_area, u.area AS unit_area, o.status
  FROM self_use_order o
  JOIN asset_unit u ON u.id = (
        SELECT MIN(u2.id) FROM asset_unit u2
         WHERE u2.asset_id = o.asset_id AND u2.deleted_at IS NULL
       )
 WHERE o.status = 'self_use' AND o.area IS NOT NULL
   AND u.area > 0 AND ABS(o.area - u.area) > 0.005
UNION ALL
SELECT 'occupation_order', o.id, o.asset_id,
       u.unit_no, o.area, u.area, o.status
  FROM occupation_order o
  JOIN asset_unit u ON u.id = (
        SELECT MIN(u2.id) FROM asset_unit u2
         WHERE u2.asset_id = o.asset_id AND u2.deleted_at IS NULL
       )
 WHERE o.status = 'occupied' AND o.area IS NOT NULL
   AND u.area > 0 AND ABS(o.area - u.area) > 0.005;

COMMENT ON VIEW v_legacy_area_mismatch IS
    '存量单据面积与单元面积差异清单；回填按 A1 采信单元面积，差异在此保留可查';

-- ---------------------------------------------------------------------------
-- 3) 占用时间轴（资产档案 AssetDossier 数据源）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_unit_occupancy_timeline AS
SELECT o.id             AS occupancy_id,
       o.asset_id       AS asset_id,
       o.asset_unit_id  AS asset_unit_id,
       u.unit_no        AS unit_no,
       o.occupancy_type AS occupancy_type,
       o.subject_type   AS subject_type,
       o.subject_id     AS subject_id,
       o.area           AS area,
       o.date_from      AS date_from,
       o.date_to        AS date_to,
       o.biz_status     AS biz_status,
       o.released_at    AS released_at,
       o.remark         AS remark,
       (o.date_to IS NULL OR o.date_to >= CURRENT_DATE) AS is_open
  FROM asset_occupancy o
  JOIN asset_unit u ON u.id = o.asset_unit_id
 WHERE u.deleted_at IS NULL;

COMMENT ON VIEW v_unit_occupancy_timeline IS '单元占用时间轴；is_open=true 表示区间未收口';
