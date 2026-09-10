-- 演示数据补齐：土地类项目 + 土地资产
--
-- 背景：资产台账「项目属性」筛选取自所属项目的 project.type。此前 5 个演示项目
--       全为 property（房产类），导致「项目属性=土地类」及由其级联出的
--       「来源类型=出让 / 行政划拨」三个筛选项恒为 0 条，无法验证级联效果。
--
-- 本迁移：
--   1) 新增 2 个 land（土地类）项目：清江浦产业用地储备项目、金湖临空经济区土地整理项目
--   2) 为两个项目补充分区（分区是资产的必填归属，也是分区面积汇总口径）
--   3) 把原挂在「清江浦智慧产业园」下的 AST-2026-005（临时周转地块）移入储备项目，
--      并把其 source_type 由 allocated_in 改为 administrative_allocation
--      —— 土地类项目下「划入」不在白名单内（白名单为 出让 / 行政划拨 / 其他）。
--      其余 3 幅地块（纪念馆停车场、王营货场周转地、湖畔休闲广场地块）语义上确属
--      各自文旅 / 物流 / 商业项目，是「项目内的配套用地」，保持原地不动。
--   4) 新增 9 幅土地资产，覆盖 出让 / 行政划拨 / 其他 三种来源与自用 / 占用两种租控状态
--
-- 结果：「项目属性=土地类」→ 10 条；级联后「来源类型」出让 4 / 行政划拨 3 / 其他 3。
--
-- 幂等：项目按 name 判重；分区按 (project_id, name) 判重；资产按 asset_no 判重；
--       存量资产迁移带 IS DISTINCT FROM 守卫，重复执行不触碰 updated_at。

-- ============================================================================
-- 1) 新增土地类项目
-- ============================================================================
INSERT INTO project (company_id, name, type, province, city, district, address, longitude, latitude, status)
SELECT c.id, v.name, 'land', '江苏省', '淮安市', v.district, v.address, v.lng, v.lat, 1
FROM (VALUES
    ('清江浦产业用地储备项目',   '清江浦区', '枚乘东路与承德南路交叉口东侧',   119.0267000, 33.5521000),
    ('金湖临空经济区土地整理项目', '金湖县',   '金北街道临空大道1号',           119.0205000, 33.0254000)
) AS v(name, district, address, lng, lat)
JOIN company c ON c.name = '淮安城投资产管理有限公司'
WHERE NOT EXISTS (
    SELECT 1 FROM project p WHERE p.name = v.name AND p.deleted_at IS NULL
);

-- ============================================================================
-- 2) 项目分区
--    清江浦产业用地储备项目：收储A区 / 收储B区 / 储备管理区
--    金湖临空经济区土地整理项目：机场核心区 / 临空物流区 / 商务配套区
-- ============================================================================
INSERT INTO project_zone (project_id, name, code, sort, remark)
SELECT p.id, v.name, v.code, v.sort, v.remark
FROM project p
JOIN (VALUES
    ('清江浦产业用地储备项目',     '收储A区',     'QJP-L-A',  0, '已完成收储、具备出让条件的产业用地'),
    ('清江浦产业用地储备项目',     '收储B区',     'QJP-L-B',  1, '在收储与配套道路、临时利用用地'),
    ('清江浦产业用地储备项目',     '储备管理区', 'QJP-L-M',  2, '储备地块看护与周转管理用房'),

    ('金湖临空经济区土地整理项目', '机场核心区',   'JH-L-CORE', 0, '临空起步区产业用地'),
    ('金湖临空经济区土地整理项目', '临空物流区',   'JH-L-LOGI', 1, '航空物流仓储用地'),
    ('金湖临空经济区土地整理项目', '商务配套区',   'JH-L-BIZ',  2, '临空商务配套与绿化隔离带')
) AS v(project_name, name, code, sort, remark) ON p.name = v.project_name
WHERE p.deleted_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM project_zone z
      WHERE z.project_id = p.id AND z.name = v.name AND z.deleted_at IS NULL
  );

-- ============================================================================
-- 3) 存量土地资产迁移：AST-2026-005 临时周转地块 → 清江浦产业用地储备项目
--    该地块本就是周转储备性质，归入储备项目更贴合业务；同时把来源改为
--    「行政划拨」（土地类项目白名单内），否则会在资产表单中回显为空。
-- ============================================================================
UPDATE asset a
SET project_id               = p.id,
    zone_id                  = z.id,
    source_type              = 'administrative_allocation',
    responsible_department_id = d.id,
    responsible_user_id      = u.id,
    updated_at               = now()
FROM project p
JOIN project_zone z ON z.project_id = p.id AND z.name = '收储A区' AND z.deleted_at IS NULL
JOIN department d   ON d.company_id = p.company_id AND d.name = '工程管理部' AND d.status = 1
JOIN "user" u       ON u.username = 'eng01' AND u.status = 1
WHERE p.name = '清江浦产业用地储备项目'
  AND p.deleted_at IS NULL
  AND a.asset_no = 'AST-2026-005'
  AND a.deleted_at IS NULL
  AND (a.project_id IS DISTINCT FROM p.id
       OR a.zone_id IS DISTINCT FROM z.id
       OR a.source_type IS DISTINCT FROM 'administrative_allocation'
       OR a.responsible_department_id IS DISTINCT FROM d.id
       OR a.responsible_user_id IS DISTINCT FROM u.id);

-- ============================================================================
-- 4) 新增土地资产（9 幅）
--    土地资产口径与存量一致：无楼层 / 无租赁面积 / 无用途房型 / 无水电表，
--    仅在「建筑规划」上区分产业、物流、商务与公共配套用地。
--    资产公司 / 经营公司 / 产权公司统一对齐项目所属公司，保证表单级联可用。
-- ============================================================================
INSERT INTO asset (
    project_id, zone_id, asset_no, name, asset_type, area, lease_area,
    usage_type, building_plan, structure_type, floor_no,
    source_type, ownership_type, asset_nature, partial_lease_status,
    property_company_id, operating_company_id, asset_company_id,
    lease_control_status, base_rent_assessed, base_rent_floor, market_ref_rent,
    province, city, district, address, longitude, latitude,
    original_value, registered_at, responsible_department_id, responsible_user_id,
    structure_status, version
)
SELECT p.id, z.id, v.asset_no, v.name, 'land', v.area, NULL,
       NULL, v.building_plan, NULL, NULL,
       v.source_type, 'self_owned', v.asset_nature, NULL,
       p.company_id, p.company_id, p.company_id,
       v.status, v.rent, v.rent_floor, v.rent_ref,
       '江苏省', '淮安市', v.district, v.address, v.lng, v.lat,
       v.original_value, v.registered_at, d.id, u.id,
       'active', 0
FROM (VALUES
    -- ---- 清江浦产业用地储备项目（工程管理部） ----
    ('清江浦产业用地储备项目', '收储A区', 'AST-QJP-L01', '高教园区东侧储备地块',
     'industrial_building', 'operational', 'land_grant', 'self_use',
     4.50, 4.00, 5.20, 28000.00, 3600.00, DATE '2023-03-10',
     '清江浦区', '枚乘东路高教园区东侧储备地块', 119.0281000, 33.5486000,
     '工程管理部', 'eng01'),
    ('清江浦产业用地储备项目', '收储A区', 'AST-QJP-L02', '开发区南延储备地块',
     'industrial_building', 'operational', 'land_grant', 'self_use',
     4.20, 3.80, 4.90, 34000.00, 4200.00, DATE '2023-09-25',
     '清江浦区', '承德南路南延段西侧储备地块', 119.0235000, 33.5362000,
     '工程管理部', 'eng01'),
    ('清江浦产业用地储备项目', '收储B区', 'AST-QJP-L03', '收储区配套道路用地',
     'public_building', 'public_welfare', 'administrative_allocation', 'self_use',
     1.20, 1.00, 1.60, 8600.00, 560.00, DATE '2022-11-08',
     '清江浦区', '枚乘东路储备区规划支路', 119.0294000, 33.5503000,
     '工程管理部', 'eng01'),
    ('清江浦产业用地储备项目', '收储B区', 'AST-QJP-L04', '储备区临时利用场地',
     'public_building', 'resource', 'other', 'occupied',
     2.80, 2.40, 3.40, 12000.00, 880.00, DATE '2022-06-18',
     '清江浦区', '承德南路东侧储备区临时利用场地', 119.0248000, 33.5401000,
     '工程管理部', 'eng01'),

    -- ---- 金湖临空经济区土地整理项目（投资发展部） ----
    ('金湖临空经济区土地整理项目', '机场核心区', 'AST-JH-L01', '临空经济区起步区地块A',
     'industrial_building', 'operational', 'land_grant', 'self_use',
     3.80, 3.40, 4.40, 46000.00, 5800.00, DATE '2024-01-15',
     '金湖县', '临空大道北侧起步区A地块', 119.0189000, 33.0318000,
     '投资发展部', 'invest01'),
    ('金湖临空经济区土地整理项目', '机场核心区', 'AST-JH-L02', '临空经济区起步区地块B',
     'industrial_building', 'operational', 'land_grant', 'self_use',
     3.60, 3.20, 4.20, 37000.00, 4600.00, DATE '2024-03-22',
     '金湖县', '临空大道北侧起步区B地块', 119.0213000, 33.0294000,
     '投资发展部', 'invest01'),
    ('金湖临空经济区土地整理项目', '临空物流区', 'AST-JH-L03', '临空物流园仓储用地',
     'industrial_building', 'operational', 'administrative_allocation', 'occupied',
     3.20, 2.90, 3.80, 29500.00, 3300.00, DATE '2023-05-30',
     '金湖县', '金北街道临空物流园规划路东侧', 119.0337000, 33.0176000,
     '投资发展部', 'invest01'),
    ('金湖临空经济区土地整理项目', '商务配套区', 'AST-JH-L04', '临空商务配套区地块',
     'commercial_building', 'operational', 'other', 'self_use',
     5.60, 5.00, 6.40, 13400.00, 2600.00, DATE '2024-06-11',
     '金湖县', '临空商务区中央大道南侧', 119.0256000, 33.0341000,
     '投资发展部', 'invest01'),
    ('金湖临空经济区土地整理项目', '商务配套区', 'AST-JH-L05', '机场北部绿化隔离带用地',
     'public_building', 'public_welfare', 'other', 'occupied',
     1.00, 0.90, 1.30, 17800.00, 900.00, DATE '2022-10-20',
     '金湖县', '临空大道北侧绿化隔离带', 119.0162000, 33.0405000,
     '投资发展部', 'invest01')
) AS v(project_name, zone_name, asset_no, name, building_plan, asset_nature,
       source_type, status, rent, rent_floor, rent_ref, area, original_value,
       registered_at, district, address, lng, lat, dept_name, username)
JOIN project p       ON p.name = v.project_name AND p.deleted_at IS NULL
JOIN project_zone z  ON z.project_id = p.id AND z.name = v.zone_name AND z.deleted_at IS NULL
JOIN department d    ON d.company_id = p.company_id AND d.name = v.dept_name AND d.status = 1
JOIN "user" u        ON u.username = v.username AND u.status = 1
WHERE NOT EXISTS (
    SELECT 1 FROM asset a WHERE a.asset_no = v.asset_no AND a.deleted_at IS NULL
);
