-- 组织架构图谱：公司树（母公司 → 子公司）+ 公司下部门 + 部门下员工
-- 可重复执行：均使用 IF NOT EXISTS。

ALTER TABLE company ADD COLUMN IF NOT EXISTS short_name VARCHAR(100);
ALTER TABLE company ADD COLUMN IF NOT EXISTS address    VARCHAR(300);
ALTER TABLE company ADD COLUMN IF NOT EXISTS phone      VARCHAR(30);
ALTER TABLE company ADD COLUMN IF NOT EXISTS sort       INT NOT NULL DEFAULT 0;

ALTER TABLE department ADD COLUMN IF NOT EXISTS type      VARCHAR(50);
ALTER TABLE department ADD COLUMN IF NOT EXISTS leader_id BIGINT;
ALTER TABLE department ADD COLUMN IF NOT EXISTS sort      INT NOT NULL DEFAULT 0;
ALTER TABLE department ADD COLUMN IF NOT EXISTS remark    VARCHAR(500);

CREATE INDEX IF NOT EXISTS idx_company_sort ON company (sort, id);
CREATE INDEX IF NOT EXISTS idx_department_parent ON department (parent_id);
CREATE INDEX IF NOT EXISTS idx_department_sort ON department (sort, id);

-- ============================================================================
-- 演示数据补齐（幂等：仅填充空值，不覆盖人工维护）
-- ============================================================================
UPDATE company SET short_name = '市国资集团',
                   address    = '江苏省淮安市清江浦区翔宇中道88号',
                   phone      = '0517-83000000',
                   updated_at = now()
WHERE parent_id IS NULL AND short_name IS NULL;

UPDATE company SET short_name = '城投资管',
                   address    = '江苏省淮安市清江浦区枚乘东路88号',
                   phone      = '0517-83100000',
                   updated_at = now()
WHERE parent_id IS NOT NULL AND short_name IS NULL;

-- 部门类型取「系统字典 → 部门类型」（asset_department / department_type）
UPDATE department SET type = 'asset_department', updated_at = now()
WHERE name LIKE '%资产%' AND type IS NULL;
