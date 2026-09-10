-- 补齐资产缺失的水/电表记录，使「资产台账 ↔ 计量」口径完整
--   背景：DemoDataConsistencyEnricher.ensureCertificatesAndMeters 仅在
--        「该资产一条表记录都没有」时才补建，若资产已有水表（或已有电表）则会漏补另一只；
--        AST-2026-001 即只有电表、无 water 记录，且表号为早期口径 DB-0001，
--        与 V30 写入的 asset.water_meter_no / electric_meter_no（SB-/DB- + 资产编号）不一致。
--   处理：1) 表号统一为 SB-/DB- + 资产编号；2) 按 (asset_id, meter_type) 补齐缺失的水表与电表。
--   幂等：表号更新带 IS DISTINCT FROM 判断；补建带 NOT EXISTS 判重。

-- ============================================================================
-- 1) 统一表号口径：SB-（水）/ DB-（电） + 资产编号
-- ============================================================================
UPDATE meter m
SET meter_no = CASE m.meter_type WHEN 'water' THEN 'SB-' ELSE 'DB-' END || a.asset_no
FROM asset a
WHERE m.asset_id = a.id
  AND a.deleted_at IS NULL
  AND m.meter_type IN ('water', 'electric')
  AND m.meter_no IS DISTINCT FROM
      (CASE m.meter_type WHEN 'water' THEN 'SB-' ELSE 'DB-' END || a.asset_no);

-- ============================================================================
-- 2) 补齐缺失的水表（房产类资产应有水表）
-- ============================================================================
INSERT INTO meter (asset_id, meter_type, meter_no, multiplier, status, shared)
SELECT a.id, 'water', 'SB-' || a.asset_no, 1, 1, false
FROM asset a
WHERE a.deleted_at IS NULL
  AND a.asset_type = 'property'
  AND NOT EXISTS (
      SELECT 1 FROM meter m WHERE m.asset_id = a.id AND m.meter_type = 'water'
  );

-- ============================================================================
-- 3) 补齐缺失的电表
-- ============================================================================
INSERT INTO meter (asset_id, meter_type, meter_no, multiplier, status, shared)
SELECT a.id, 'electric', 'DB-' || a.asset_no, 1, 1, false
FROM asset a
WHERE a.deleted_at IS NULL
  AND a.asset_type = 'property'
  AND NOT EXISTS (
      SELECT 1 FROM meter m WHERE m.asset_id = a.id AND m.meter_type = 'electric'
  );
