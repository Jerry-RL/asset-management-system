-- 项目管理（FR-AST-001）：新增项目分两步走
--   第一步 基本信息：名称、详细地址、公司、省市区、类型、状态、图片、经纬度
--   第二步 项目分区配置：一个项目可配置多个分区
-- 可重复执行：均使用 IF NOT EXISTS。

-- ---- 第一步：项目基础信息扩展 ----
ALTER TABLE project ADD COLUMN IF NOT EXISTS province      VARCHAR(50);
ALTER TABLE project ADD COLUMN IF NOT EXISTS city          VARCHAR(50);
ALTER TABLE project ADD COLUMN IF NOT EXISTS district      VARCHAR(50);
ALTER TABLE project ADD COLUMN IF NOT EXISTS type          VARCHAR(50);
ALTER TABLE project ADD COLUMN IF NOT EXISTS image_url     VARCHAR(500);
ALTER TABLE project ADD COLUMN IF NOT EXISTS image_file_id BIGINT;

-- ---- 第二步：项目分区 ----
CREATE TABLE IF NOT EXISTS project_zone (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT       NOT NULL,
    name        VARCHAR(100) NOT NULL,
    code        VARCHAR(64),
    area        NUMERIC(18,2),
    usage_type  VARCHAR(50),
    sort        INTEGER      NOT NULL DEFAULT 0,
    remark      VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    created_by  BIGINT,
    updated_by  BIGINT,
    deleted_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_project_zone_project ON project_zone (project_id, sort, id);

-- ============================================================================
-- 演示数据补齐（幂等：仅填充空值，不覆盖人工维护）
-- ============================================================================
UPDATE project SET province = '江苏省', city = '淮安市', district = '清江浦区',
                   type = 'park', updated_at = now()
WHERE name = '清江浦智慧产业园' AND province IS NULL;

UPDATE project SET province = '江苏省', city = '淮安市', district = '淮安区',
                   type = 'building', updated_at = now()
WHERE name = '楚州古城文旅资产包' AND province IS NULL;

UPDATE project SET province = '江苏省', city = '淮安市', district = '淮阴区',
                   type = 'park', updated_at = now()
WHERE name = '淮阴仓储物流园' AND province IS NULL;

UPDATE project SET province = '江苏省', city = '淮安市', district = '淮安经济技术开发区',
                   type = 'park', updated_at = now()
WHERE name = '经开区标准厂房群' AND province IS NULL;

UPDATE project SET province = '江苏省', city = '淮安市', district = '洪泽区',
                   type = 'building', updated_at = now()
WHERE name = '洪泽湖畔商业综合体' AND province IS NULL;

-- 为演示项目补一个默认分区（幂等）
INSERT INTO project_zone (project_id, name, code, area, usage_type, sort)
SELECT p.id, 'A区', 'A', 5000.00, '综合', 0
FROM project p
WHERE p.name = '清江浦智慧产业园'
  AND p.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM project_zone z WHERE z.project_id = p.id AND z.deleted_at IS NULL);
