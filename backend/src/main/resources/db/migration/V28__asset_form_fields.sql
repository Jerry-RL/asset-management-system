-- 资产新增/修改表单字段扩展
--   产权公司 / 资产公司（组织架构）、资产坐落、分区楼层、租赁面积、登记入库时间、
--   责任部门 / 责任人、资产图片、部分租赁状态、资产性质、建筑规划
-- 同时补充「建筑规划」字典，并把「资产户型」字典更名为「资产房型」以对齐业务口径。
-- 可重复执行：列用 IF NOT EXISTS，字典按 code / (type_id, value) 幂等。

-- ============================================================================
-- 资产：表单新增字段
-- ============================================================================
ALTER TABLE asset ADD COLUMN IF NOT EXISTS asset_company_id          BIGINT;
ALTER TABLE asset ADD COLUMN IF NOT EXISTS partial_lease_status      VARCHAR(50);
ALTER TABLE asset ADD COLUMN IF NOT EXISTS asset_nature              VARCHAR(50);
ALTER TABLE asset ADD COLUMN IF NOT EXISTS building_plan             VARCHAR(50);
ALTER TABLE asset ADD COLUMN IF NOT EXISTS floor_no                  INTEGER;
ALTER TABLE asset ADD COLUMN IF NOT EXISTS lease_area                NUMERIC(18, 2);
ALTER TABLE asset ADD COLUMN IF NOT EXISTS registered_at             DATE;
ALTER TABLE asset ADD COLUMN IF NOT EXISTS responsible_department_id BIGINT;
ALTER TABLE asset ADD COLUMN IF NOT EXISTS responsible_user_id       BIGINT;
ALTER TABLE asset ADD COLUMN IF NOT EXISTS image_url                 VARCHAR(500);
ALTER TABLE asset ADD COLUMN IF NOT EXISTS image_file_id             BIGINT;

COMMENT ON COLUMN asset.asset_company_id IS '资产公司（org_company.id），项目/责任部门按此级联';
COMMENT ON COLUMN asset.partial_lease_status IS '部分租赁状态，取值见 sys_dict_type.code = partial_lease_status';
COMMENT ON COLUMN asset.asset_nature IS '资产性质，取值见 sys_dict_type.code = asset_nature';
COMMENT ON COLUMN asset.building_plan IS '建筑规划，取值见 sys_dict_type.code = building_plan';
COMMENT ON COLUMN asset.floor_no IS '分区楼层';
COMMENT ON COLUMN asset.lease_area IS '租赁面积(㎡)';
COMMENT ON COLUMN asset.registered_at IS '登记入库时间';
COMMENT ON COLUMN asset.responsible_department_id IS '责任部门（org_department.id），取自资产公司下属部门';
COMMENT ON COLUMN asset.responsible_user_id IS '责任人（sys_user.id），取自责任部门下员工';
COMMENT ON COLUMN asset.image_url IS '资产图片地址';
COMMENT ON COLUMN asset.image_file_id IS '资产图片附件 ID（sys_file.id）';
COMMENT ON COLUMN asset.original_value IS '原值(万元)';

CREATE INDEX IF NOT EXISTS idx_asset_asset_company ON asset (asset_company_id);
CREATE INDEX IF NOT EXISTS idx_asset_responsible_dept ON asset (responsible_department_id);
CREATE INDEX IF NOT EXISTS idx_asset_responsible_user ON asset (responsible_user_id);

-- 存量数据回填：老数据只有「经营公司」，资产公司按经营公司补齐，
-- 保证数据范围隔离 / 经营看板 / 监管报送口径与新表单一致。
UPDATE asset
SET asset_company_id = operating_company_id
WHERE asset_company_id IS NULL
  AND operating_company_id IS NOT NULL;

-- ============================================================================
-- 字典：建筑规划（挂在「资产管理字典」模块下）
-- ============================================================================
INSERT INTO sys_dict_type (module_id, code, name, sort)
SELECT m.id, v.code, v.name, v.sort
FROM sys_dict_module m
JOIN (VALUES ('building_plan', '建筑规划', 11)) AS v(code, name, sort) ON TRUE
WHERE m.code = 'asset_management'
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_dict_item (type_id, value, label, sort)
SELECT t.id, v.value, v.label, v.sort
FROM sys_dict_type t
JOIN (VALUES
    ('building_plan', 'residential_building', '住宅建筑', 1),
    ('building_plan', 'commercial_building',  '商业建筑', 2),
    ('building_plan', 'office_building',      '办公建筑', 3),
    ('building_plan', 'industrial_building',  '工业建筑', 4),
    ('building_plan', 'public_building',      '公共建筑', 5),
    ('building_plan', 'complex_building',     '综合建筑', 6)
) AS v(type_code, value, label, sort) ON v.type_code = t.code
ON CONFLICT (type_id, value) DO NOTHING;

-- 业务口径统一为「资产房型」
UPDATE sys_dict_type SET name = '资产房型' WHERE code = 'asset_house_type' AND name <> '资产房型';
