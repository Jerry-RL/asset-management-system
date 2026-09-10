-- 项目分区面积口径调整 + 资产归属分区
--   1) 资产增加「所属分区」zone_id（SRS §4.5.2 资产列表含「分区」字段）
--   2) 分区面积不再手工维护，改为「该分区下资产面积合计」，故删除 project_zone.area
-- 可重复执行：均使用 IF NOT EXISTS / IF EXISTS。

-- ---- 1) 资产 → 分区 ----
ALTER TABLE asset ADD COLUMN IF NOT EXISTS zone_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_asset_zone ON asset (zone_id);
COMMENT ON COLUMN asset.zone_id IS '所属项目分区（project_zone.id），分区面积按此字段汇总';

-- ---- 2) 分区面积改由资产统计得出 ----
ALTER TABLE project_zone DROP COLUMN IF EXISTS area;

-- ============================================================================
-- 演示数据（幂等）：把 V24 建立的分区挂到该项目下的资产
-- ============================================================================
UPDATE asset a
   SET zone_id = z.id
  FROM project_zone z,
       project p
 WHERE a.project_id = z.project_id
   AND p.id = z.project_id
   AND p.name = '清江浦智慧产业园'
   AND z.name = 'A区'
   AND a.deleted_at IS NULL
   AND a.zone_id IS NULL;
