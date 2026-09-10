-- 项目分区：去除「用途」字段
-- 依据：分区用途与「资产用途」重复，实际维护口径以资产为准，分区不再保留该字段。
-- 可重复执行：IF EXISTS。

ALTER TABLE project_zone DROP COLUMN IF EXISTS usage_type;
