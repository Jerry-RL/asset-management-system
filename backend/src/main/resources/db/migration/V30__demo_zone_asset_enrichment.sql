-- 演示数据补齐：项目管理分区 + 基于项目的资产数据
--   1) asset_type 字典补齐 property / land（存量资产口径，避免编辑页下拉回显为空）
--   2) 为 5 个演示项目补充多元分区（每个分区都有资产归属，保证分区面积可汇总）
--   3) 存量资产归位到具体分区
--   4) 存量资产补齐「资产表单」新增字段（面积/原值/租金/责任部门/水电表号等）
--   5) 新增 20 条演示资产，覆盖多种业态、租控状态与建筑属性
-- 幂等：分区/资产按业务键（project_id+name / asset_no）判重；字段补齐为确定性赋值。

-- ============================================================================
-- 1) 资产类型字典：补齐存量口径 property / land
--    资产表单「资产类型」下拉取自 asset_type 字典，而存量资产为 property/land，
--    字典缺这两项会导致编辑页无法回显，故补齐（新增项排在其后）。
-- ============================================================================
INSERT INTO sys_dict_item (type_id, value, label, sort, status)
SELECT t.id, v.value, v.label, v.sort, 1
FROM sys_dict_type t
JOIN (VALUES
    ('property', '房产类', 11),
    ('land',     '土地类', 12)
) AS v(value, label, sort) ON TRUE
WHERE t.code = 'asset_type'
ON CONFLICT (type_id, value) DO NOTHING;

-- ============================================================================
-- 2) 项目分区：补充多元分区
--    清江浦智慧产业园：A区(已存在) / B区研发办公 / C区仓储物流 / D区生活配套
--    楚州古城文旅资产包：镇淮楼街区 / 河下古镇片区 / 纪念馆片区 / 里运河文化长廊
--    淮阴仓储物流园：冷链仓储区 / 通用仓储区 / 货车周转区
--    经开区标准厂房群：富强路A栋 / 富强路B栋 / 站前商业区 / 配套服务区
--    洪泽湖畔商业综合体：临湖商业街 / 湖滨商务区 / 老子山旅游区 / 湖畔休闲广场
-- ============================================================================
INSERT INTO project_zone (project_id, name, code, sort, remark)
SELECT p.id, v.name, v.code, v.sort, v.remark
FROM project p
JOIN (VALUES
    ('清江浦智慧产业园', 'B区', 'QJP-B', 1, '研发办公区：研发中心与总部办公'),
    ('清江浦智慧产业园', 'C区', 'QJP-C', 2, '仓储物流区：智能仓储与分拨配送'),
    ('清江浦智慧产业园', 'D区', 'QJP-D', 3, '生活配套区：食堂、宿舍与人才公寓'),

    ('楚州古城文旅资产包', '镇淮楼街区', 'CZ-ZHL', 0, '古城核心商业街区'),
    ('楚州古城文旅资产包', '河下古镇片区', 'CZ-HX', 1, '古镇民宿与非遗工坊聚集区'),
    ('楚州古城文旅资产包', '纪念馆片区', 'CZ-JNG', 2, '纪念馆配套服务与停车区'),
    ('楚州古城文旅资产包', '里运河文化长廊', 'CZ-LYH', 3, '里运河沿岸文旅商业带'),

    ('淮阴仓储物流园', '冷链仓储区', 'HY-LL', 0, '恒温冷库与冷链周转'),
    ('淮阴仓储物流园', '通用仓储区', 'HY-TY', 1, '通用平库与电商云仓'),
    ('淮阴仓储物流园', '货车周转区', 'HY-ZZ', 2, '货运车辆停放与维修保障'),

    ('经开区标准厂房群', '富强路A栋', 'JKQ-FQA', 0, 'A栋标准厂房及辅房'),
    ('经开区标准厂房群', '富强路B栋', 'JKQ-FQB', 1, 'B栋标准厂房及辅房'),
    ('经开区标准厂房群', '站前商业区', 'JKQ-ZQ', 2, '高铁东站站前商业与商务'),
    ('经开区标准厂房群', '配套服务区', 'JKQ-PT', 3, '园区配套服务与生活支持'),

    ('洪泽湖畔商业综合体', '临湖商业街', 'HZ-LH', 0, '临湖餐饮与零售商业街'),
    ('洪泽湖畔商业综合体', '湖滨商务区', 'HZ-HB', 1, '湖滨写字楼与湖景公寓'),
    ('洪泽湖畔商业综合体', '老子山旅游区', 'HZ-LZS', 2, '老子山温泉度假与旅游服务'),
    ('洪泽湖畔商业综合体', '湖畔休闲广场', 'HZ-GC', 3, '湖畔休闲广场与景观用地')
) AS v(project_name, name, code, sort, remark) ON p.name = v.project_name
WHERE p.deleted_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM project_zone z
      WHERE z.project_id = p.id AND z.name = v.name AND z.deleted_at IS NULL
  );

-- 已存在的 A区 补备注，保持分区说明口径一致
UPDATE project_zone z
SET remark = '综合产业区：厂房、办公与临时周转用地', updated_at = now()
FROM project p
WHERE z.project_id = p.id
  AND p.name = '清江浦智慧产业园'
  AND z.name = 'A区'
  AND z.remark IS NULL;

-- ============================================================================
-- 3) 存量资产归位到具体分区
--    清江浦 A区 原有 5 条按业态拆分到 B/C/D 区（面积口径不变，仅调整归属）
-- ============================================================================
UPDATE asset a
SET zone_id = z.id, updated_at = now()
FROM project_zone z
WHERE a.project_id = z.project_id
  AND a.deleted_at IS NULL
  AND z.deleted_at IS NULL
  AND z.name = CASE a.asset_no
        WHEN 'AST-2026-002' THEN 'B区'   -- 2号办公楼 → 研发办公区
        WHEN 'AST-2026-003' THEN 'D区'   -- 3号商铺   → 生活配套区
        WHEN 'AST-2026-004' THEN 'C区'   -- 仓储用房   → 仓储物流区
        WHEN 'AST-2026-005' THEN 'C区'   -- 临时周转地 → 仓储物流区
        WHEN 'AST-HA-101'   THEN '镇淮楼街区'
        WHEN 'AST-HA-102'   THEN '河下古镇片区'
        WHEN 'AST-HA-103'   THEN '纪念馆片区'
        WHEN 'AST-HA-201'   THEN '冷链仓储区'
        WHEN 'AST-HA-202'   THEN '货车周转区'
        WHEN 'AST-HA-301'   THEN '富强路A栋'
        WHEN 'AST-HA-302'   THEN '富强路B栋'
        WHEN 'AST-HA-303'   THEN '站前商业区'
        WHEN 'AST-HA-401'   THEN '临湖商业街'
        WHEN 'AST-HA-402'   THEN '老子山旅游区'
        ELSE NULL
      END
  AND a.zone_id IS DISTINCT FROM z.id;

-- ============================================================================
-- 4) 存量资产补齐「资产表单」字段
--    4.1 责任部门 / 责任人：按项目片区划分归口
-- ============================================================================
UPDATE asset a
SET responsible_department_id = d.id,
    responsible_user_id = u.id,
    updated_at = now()
FROM project p
CROSS JOIN (VALUES
    ('清江浦智慧产业园',     '资产一组',   'asset01'),
    ('楚州古城文旅资产包',   '资产二组',   'asset02'),
    ('淮阴仓储物流园',       '资产二组',   'asset02'),
    ('经开区标准厂房群',     '资产一组',   'asset01'),
    ('洪泽湖畔商业综合体',   '运营管理部', 'ops01')
) AS m(project_name, dept_name, username)
JOIN department d ON d.company_id = p.company_id AND d.name = m.dept_name AND d.status = 1
JOIN "user" u     ON u.username = m.username AND u.status = 1
WHERE a.project_id = p.id
  AND p.name = m.project_name
  AND p.deleted_at IS NULL
  AND a.deleted_at IS NULL
  AND (a.responsible_department_id IS DISTINCT FROM d.id
       OR a.responsible_user_id IS DISTINCT FROM u.id);

-- 4.2 面积 / 原值 / 租金 / 建筑属性 / 资产性质
UPDATE asset a
SET lease_area         = COALESCE(a.lease_area, v.lease_area),
    original_value     = COALESCE(a.original_value, v.original_value),
    base_rent_assessed = COALESCE(a.base_rent_assessed, v.base_rent_assessed),
    base_rent_floor    = COALESCE(a.base_rent_floor, v.base_rent_floor),
    market_ref_rent    = COALESCE(a.market_ref_rent, v.market_ref_rent),
    registered_at      = COALESCE(a.registered_at, v.registered_at),
    floor_no           = COALESCE(a.floor_no, v.floor_no),
    usage_type         = COALESCE(a.usage_type, v.usage_type),
    house_type         = COALESCE(a.house_type, v.house_type),
    building_plan      = COALESCE(a.building_plan, v.building_plan),
    structure_type     = COALESCE(a.structure_type, v.structure_type),
    asset_nature       = COALESCE(a.asset_nature, v.asset_nature),
    partial_lease_status = COALESCE(a.partial_lease_status, v.partial_lease_status),
    updated_at         = now()
FROM (VALUES
    ('AST-2026-001', 1150.00, 2400.00, 26.00, 22.00, 30.00, DATE '2022-03-15', 1, NULL::varchar, NULL::varchar, 'industrial_building', 'steel_concrete', 'operational', NULL::varchar),
    ('AST-2026-002',  760.00, 1800.00, 45.00, 40.00, 52.00, DATE '2022-03-15', 2, 'office', NULL::varchar, 'office_building', 'frame', 'operational', NULL),
    ('AST-2026-003',  140.00,  420.00, 88.00, 80.00, 96.00, DATE '2022-05-20', 1, 'commercial', NULL::varchar, 'commercial_building', 'frame', 'operational', NULL),
    ('AST-2026-004',  480.00,  900.00, 20.00, 18.00, 24.00, DATE '2022-03-15', 1, NULL, NULL, 'industrial_building', 'steel_concrete', 'self_owned', NULL),
    ('AST-2026-005', NULL,     600.00,  4.00,  3.50,  5.00, DATE '2022-09-01', NULL, NULL, NULL, 'public_building', NULL, 'resource', NULL),
    ('AST-HA-101',    210.00,  520.00, 120.00, 110.00, 135.00, DATE '2021-06-18', 1, 'commercial', NULL, 'commercial_building', 'brick_wood', 'operational', NULL),
    ('AST-HA-102',    640.00,  980.00, 65.00, 58.00, 72.00, DATE '2021-08-30', 1, 'residential', 'two_bed_one_living', 'residential_building', 'brick_wood', 'operational', NULL),
    ('AST-HA-103', NULL,       800.00,  3.00,  2.50,  4.00, DATE '2021-12-01', NULL, NULL, NULL, 'public_building', NULL, 'public_welfare', NULL),
    ('AST-HA-201',   3100.00, 5200.00, 45.00, 40.00, 52.00, DATE '2022-04-12', 1, NULL, NULL, 'industrial_building', 'steel_concrete', 'operational', NULL),
    ('AST-HA-202', NULL,     1200.00,  2.50,  2.00,  3.00, DATE '2022-07-25', NULL, NULL, NULL, 'public_building', NULL, 'resource', NULL),
    ('AST-HA-301',   4300.00, 6800.00, 22.00, 19.00, 26.00, DATE '2022-10-08', 1, NULL, NULL, 'industrial_building', 'frame', 'operational', NULL),
    ('AST-HA-302',   4000.00, 6400.00, 22.00, 19.00, 26.00, DATE '2022-10-08', 1, NULL, NULL, 'industrial_building', 'frame', 'operational', NULL),
    ('AST-HA-303',    170.00,  460.00, 150.00, 138.00, 168.00, DATE '2023-06-20', 1, 'commercial', NULL, 'commercial_building', 'frame', 'operational', NULL),
    ('AST-HA-401',    300.00,  780.00, 110.00, 100.00, 125.00, DATE '2022-09-15', 1, 'commercial', NULL, 'commercial_building', 'frame', 'operational', NULL),
    ('AST-HA-402',    920.00, 2600.00, 38.00, 34.00, 44.00, DATE '2022-01-10', 1, 'commercial', NULL, 'public_building', 'mixed', 'operational', 'support')
) AS v(asset_no, lease_area, original_value, base_rent_assessed, base_rent_floor,
       market_ref_rent, registered_at, floor_no, usage_type, house_type,
       building_plan, structure_type, asset_nature, partial_lease_status)
WHERE a.asset_no = v.asset_no
  AND a.deleted_at IS NULL
  -- 仅在尚未补齐时执行，保证重复运行不再触碰已维护的数据（含 updated_at）
  AND (a.asset_nature IS NULL OR a.original_value IS NULL);

-- 4.3 资产来源 / 资产权属：对齐字典口径
--     存量数据为 self / own（不在 asset_source、asset_ownership 字典中），
--     会对齐为字典值，保证资产表单下拉可回显。
UPDATE asset a
SET source_type = CASE
        WHEN a.asset_type = 'land' THEN 'allocated_in'          -- 划入
        WHEN a.asset_no IN ('AST-2026-004', 'AST-HA-206') THEN 'self_funded_construction'
        ELSE 'investment_construction'                          -- 投资建设
    END,
    ownership_type = 'self_owned',                              -- 自有资产
    updated_at = now()
WHERE a.deleted_at IS NULL
  AND (a.source_type IN ('self', 'own') OR a.source_type IS NULL
       OR a.ownership_type IN ('self', 'own') OR a.ownership_type IS NULL);

-- 4.4 水表号 / 电表号：与 DemoDataConsistencyEnricher 生成的 meter.meter_no 口径一致
UPDATE asset a
SET water_meter_no    = COALESCE(a.water_meter_no, 'SB-' || a.asset_no),
    electric_meter_no = COALESCE(a.electric_meter_no, 'DB-' || a.asset_no),
    updated_at = now()
WHERE a.deleted_at IS NULL
  AND a.asset_type = 'property'
  AND (a.water_meter_no IS NULL OR a.electric_meter_no IS NULL);

-- ============================================================================
-- 5) 新增演示资产（20 条）
--    覆盖厂房 / 办公 / 商业 / 住宅 / 仓储 / 文旅 / 土地等多种业态，
--    并覆盖 leased / leasing / vacant / self_use / partial_leased 全部租控状态。
--    资产公司 / 经营公司 / 产权公司统一对齐项目所属公司，保证表单级联可用。
-- ============================================================================
WITH proj_owner AS (
    SELECT * FROM (VALUES
        ('清江浦智慧产业园',   '资产一组',   'asset01'),
        ('楚州古城文旅资产包', '资产二组',   'asset02'),
        ('淮阴仓储物流园',     '资产二组',   'asset02'),
        ('经开区标准厂房群',   '资产一组',   'asset01'),
        ('洪泽湖畔商业综合体', '运营管理部', 'ops01')
    ) AS t(project_name, dept_name, username)
)
INSERT INTO asset (
    project_id, zone_id, asset_no, name, asset_type, area, lease_area, house_type,
    usage_type, building_plan, structure_type, floor_no,
    source_type, ownership_type, asset_nature, partial_lease_status,
    property_company_id, operating_company_id, asset_company_id,
    lease_control_status, base_rent_assessed, base_rent_floor, market_ref_rent,
    province, city, district, address, longitude, latitude,
    original_value, registered_at, responsible_department_id, responsible_user_id,
    water_meter_no, electric_meter_no, version
)
SELECT p.id, z.id, v.asset_no, v.name, v.asset_type, v.area, v.lease_area, v.house_type,
       v.usage_type, v.building_plan, v.structure_type, v.floor_no,
       v.source_type, v.ownership_type, v.asset_nature, v.partial_lease_status,
       p.company_id, p.company_id, p.company_id,
       v.status, v.base_rent_assessed, v.base_rent_floor, v.market_ref_rent,
       '江苏省', '淮安市', v.district, v.address, v.lng, v.lat,
       v.original_value, v.registered_at, d.id, u.id,
       CASE WHEN v.asset_type = 'property' THEN 'SB-' || v.asset_no END,
       CASE WHEN v.asset_type = 'property' THEN 'DB-' || v.asset_no END,
       0
FROM (VALUES
    -- ---- 清江浦智慧产业园 ----
    ('清江浦智慧产业园', 'B区', 'AST-QJP-011', '智慧研发中心', 'property',
     2800.00, 2600.00, NULL::varchar, 'office', 'office_building', 'frame', 1,
     'investment_construction', 'self_owned', 'operational', NULL::varchar, 'leased',
     96.00, 88.00, 105.00, '清江浦区', '枚乘东路88号B区研发楼', 119.0459800, 33.5826600,
     8600.00, DATE '2023-06-15'),
    ('清江浦智慧产业园', 'C区', 'AST-QJP-021', '智能立体仓库', 'property',
     3600.00, 3400.00, NULL, NULL, 'industrial_building', 'steel_concrete', 1,
     'investment_construction', 'self_owned', 'operational', NULL, 'leasing',
     32.00, 28.00, 36.00, '清江浦区', '枚乘东路88号C区1号库', 119.0441200, 33.5803400,
     5200.00, DATE '2023-09-01'),
    ('清江浦智慧产业园', 'D区', 'AST-QJP-031', '园区食堂商业配套', 'property',
     900.00, 820.00, NULL, 'commercial', 'commercial_building', 'frame', 1,
     'self_funded_construction', 'self_owned', 'operational', NULL, 'leased',
     78.00, 72.00, 86.00, '清江浦区', '枚乘东路88号D区综合楼一层', 119.0465200, 33.5812000,
     1500.00, DATE '2022-11-20'),
    ('清江浦智慧产业园', 'D区', 'AST-QJP-032', '员工宿舍楼', 'property',
     2200.00, 2200.00, 'two_bed_one_living', 'residential', 'residential_building', 'brick_concrete', 1,
     'self_funded_construction', 'self_owned', 'public_welfare', NULL, 'self_use',
     28.00, 25.00, 32.00, '清江浦区', '枚乘东路88号D区2号楼', 119.0471000, 33.5810400,
     3200.00, DATE '2022-05-10'),
    ('清江浦智慧产业园', 'D区', 'AST-QJP-033', '园区人才公寓', 'property',
     3100.00, 2980.00, 'one_bed_one_living', 'residential', 'residential_building', 'shear_wall', 1,
     'self_funded_construction', 'self_owned', 'public_welfare', 'support', 'partial_leased',
     42.00, 38.00, 48.00, '清江浦区', '枚乘东路88号D区3号楼', 119.0476800, 33.5808600,
     4800.00, DATE '2024-03-18'),

    -- ---- 楚州古城文旅资产包 ----
    ('楚州古城文旅资产包', '河下古镇片区', 'AST-HA-104', '河下古镇非遗工坊', 'property',
     460.00, 420.00, NULL, 'commercial', 'commercial_building', 'brick_wood', 1,
     'transferred', 'self_owned', 'operational', NULL, 'vacant',
     68.00, 62.00, 75.00, '淮安区', '河下古镇竹巷22号', 119.0589000, 33.5628000,
     680.00, DATE '2021-09-25'),
    ('楚州古城文旅资产包', '纪念馆片区', 'AST-HA-105', '纪念馆游客服务中心', 'property',
     780.00, 700.00, NULL, 'commercial', 'public_building', 'frame', 1,
     'allocated_in', 'self_owned', 'public_welfare', NULL, 'leased',
     52.00, 46.00, 58.00, '淮安区', '淮海北路纪念馆南侧服务中心', 119.1532000, 33.5089000,
     1250.00, DATE '2022-03-30'),
    ('楚州古城文旅资产包', '里运河文化长廊', 'AST-HA-106', '里运河文化长廊沿街铺', 'property',
     350.00, 320.00, NULL, 'commercial', 'commercial_building', 'brick_concrete', 1,
     'transferred', 'self_owned', 'operational', NULL, 'leasing',
     96.00, 88.00, 108.00, '淮安区', '里运河文化长廊东段12号', 119.1456000, 33.5123000,
     520.00, DATE '2023-04-12'),

    -- ---- 淮阴仓储物流园 ----
    ('淮阴仓储物流园', '冷链仓储区', 'AST-HA-203', '恒温冷库2号', 'property',
     2600.00, 2500.00, NULL, NULL, 'industrial_building', 'steel_concrete', 1,
     'investment_construction', 'self_owned', 'operational', NULL, 'leased',
     48.00, 43.00, 54.00, '淮阴区', '北京北路物流园2号库', 119.0429000, 33.6378000,
     4100.00, DATE '2023-02-08'),
    ('淮阴仓储物流园', '通用仓储区', 'AST-HA-204', '通用平库3号', 'property',
     3800.00, 3600.00, NULL, NULL, 'industrial_building', 'steel_concrete', 1,
     'investment_construction', 'self_owned', 'operational', NULL, 'vacant',
     26.00, 23.00, 30.00, '淮阴区', '北京北路物流园3号库', 119.0436000, 33.6384000,
     3800.00, DATE '2022-08-16'),
    ('淮阴仓储物流园', '通用仓储区', 'AST-HA-205', '电商云仓分拨中心', 'property',
     2900.00, 2750.00, NULL, NULL, 'industrial_building', 'frame', 1,
     'self_funded_construction', 'self_owned', 'operational', NULL, 'leasing',
     35.00, 31.00, 40.00, '淮阴区', '北京北路物流园4号库', 119.0442000, 33.6391000,
     3200.00, DATE '2024-01-22'),
    ('淮阴仓储物流园', '货车周转区', 'AST-HA-206', '货车维修车间', 'property',
     620.00, 600.00, NULL, NULL, 'industrial_building', 'steel_concrete', 1,
     'self_funded_construction', 'self_owned', 'self_owned', NULL, 'self_use',
     18.00, 16.00, 21.00, '淮阴区', '王营街道货场路西侧维修车间', 119.0294000, 33.6491000,
     480.00, DATE '2022-06-05'),

    -- ---- 经开区标准厂房群 ----
    ('经开区标准厂房群', '富强路A栋', 'AST-HA-304', 'A栋辅房', 'property',
     320.00, 300.00, NULL, NULL, 'industrial_building', 'frame', 1,
     'investment_construction', 'self_owned', 'operational', NULL, 'leased',
     24.00, 21.00, 27.00, '经济技术开发区', '富强路66号A栋辅房', 119.1901000, 33.5766000,
     420.00, DATE '2023-05-20'),
    ('经开区标准厂房群', '站前商业区', 'AST-HA-306', '站前商务写字楼', 'property',
     5200.00, 4900.00, NULL, 'office', 'office_building', 'shear_wall', 1,
     'investment_construction', 'self_owned', 'operational', NULL, 'leasing',
     68.00, 60.00, 76.00, '经济技术开发区', '高铁东站站前广场商务楼', 119.1934000, 33.5879000,
     9800.00, DATE '2024-02-14'),
    ('经开区标准厂房群', '配套服务区', 'AST-HA-307', '园区配套服务中心', 'property',
     1100.00, 1000.00, NULL, 'commercial', 'complex_building', 'frame', 1,
     'allocated_in', 'self_owned', 'public_welfare', NULL, 'self_use',
     36.00, 32.00, 41.00, '经济技术开发区', '富强路66号配套服务中心', 119.1879000, 33.5742000,
     2100.00, DATE '2023-10-09'),

    -- ---- 洪泽湖畔商业综合体 ----
    ('洪泽湖畔商业综合体', '临湖商业街', 'AST-HA-403', '临湖餐饮街2号楼', 'property',
     680.00, 640.00, NULL, 'commercial', 'commercial_building', 'frame', 2,
     'investment_construction', 'self_owned', 'operational', NULL, 'leased',
     105.00, 96.00, 118.00, '洪泽区', '东风路临湖商业街2号楼', 118.8772000, 33.2999000,
     1150.00, DATE '2022-07-01'),
    ('洪泽湖畔商业综合体', '湖滨商务区', 'AST-HA-404', '湖滨写字楼', 'property',
     2400.00, 2250.00, NULL, 'office', 'office_building', 'steel_concrete', 1,
     'investment_construction', 'self_owned', 'operational', NULL, 'leasing',
     62.00, 55.00, 70.00, '洪泽区', '湖滨大道18号写字楼', 118.8785000, 33.3012000,
     4600.00, DATE '2023-08-28'),
    ('洪泽湖畔商业综合体', '湖滨商务区', 'AST-HA-405', '湖景公寓', 'property',
     1800.00, 1720.00, 'three_bed_two_living', 'residential', 'residential_building', 'shear_wall', 1,
     'investment_construction', 'self_owned', 'operational', 'support', 'partial_leased',
     58.00, 52.00, 65.00, '洪泽区', '湖滨大道20号湖景公寓', 118.8791000, 33.3018000,
     3600.00, DATE '2024-04-06'),
    ('洪泽湖畔商业综合体', '老子山旅游区', 'AST-HA-406', '老子山温泉度假村', 'property',
     5600.00, 5200.00, NULL, 'commercial', 'public_building', 'mixed', 1,
     'investment_construction', 'self_owned', 'operational', NULL, 'leased',
     72.00, 64.00, 82.00, '洪泽区', '老子山镇温泉路1号', 118.7131000, 33.1862000,
     12800.00, DATE '2021-12-15'),
    ('洪泽湖畔商业综合体', '湖畔休闲广场', 'AST-HA-407', '湖畔休闲广场地块', 'land',
     3300.00, NULL, NULL, NULL, 'public_building', NULL, NULL,
     'allocated_in', 'self_owned', 'resource', NULL, 'self_use',
     3.00, 2.50, 3.50, '洪泽区', '东风路南侧湖畔广场地块', 118.8763000, 33.2978000,
     900.00, DATE '2022-04-18')
) AS v(project_name, zone_name, asset_no, name, asset_type, area, lease_area, house_type,
       usage_type, building_plan, structure_type, floor_no,
       source_type, ownership_type, asset_nature, partial_lease_status, status,
       base_rent_assessed, base_rent_floor, market_ref_rent,
       district, address, lng, lat, original_value, registered_at)
JOIN project p ON p.name = v.project_name AND p.deleted_at IS NULL
LEFT JOIN project_zone z ON z.project_id = p.id AND z.name = v.zone_name AND z.deleted_at IS NULL
LEFT JOIN proj_owner o ON o.project_name = p.name
LEFT JOIN department d ON d.company_id = p.company_id AND d.name = o.dept_name AND d.status = 1
LEFT JOIN "user" u ON u.username = o.username AND u.status = 1
WHERE NOT EXISTS (
    SELECT 1 FROM asset a WHERE a.asset_no = v.asset_no AND a.deleted_at IS NULL
);

-- 新增空置资产的空置起始 / 原因（幂等：仅填空值）
UPDATE asset SET vacant_since = now() - INTERVAL '90 days', vacant_reason = '退租', updated_at = now()
WHERE asset_no = 'AST-HA-104' AND deleted_at IS NULL AND vacant_since IS NULL;

UPDATE asset SET vacant_since = now() - INTERVAL '60 days', vacant_reason = '退租', updated_at = now()
WHERE asset_no = 'AST-HA-204' AND deleted_at IS NULL AND vacant_since IS NULL;
