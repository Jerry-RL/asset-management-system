-- 淮安市资产地图演示点位（以淮安多区县真实地标为原型）

-- 组织命名对齐淮安原型（仅更新演示种子公司）
UPDATE company SET name = '淮安市国资集团', updated_at = now()
WHERE name = 'XX国资集团';

UPDATE company SET name = '淮安城投资产管理有限公司', updated_at = now()
WHERE name = 'XX城投子公司';

-- 主项目：清江浦智慧产业园
UPDATE project
SET name = '清江浦智慧产业园',
    address = '江苏省淮安市清江浦区枚乘东路88号',
    longitude = 119.0452000,
    latitude = 33.5821000,
    updated_at = now()
WHERE name IN ('XX产业园', '清江浦智慧产业园')
  AND deleted_at IS NULL;

INSERT INTO project (company_id, name, address, longitude, latitude, status)
SELECT c.id, '楚州古城文旅资产包', '江苏省淮安市淮安区镇淮楼东路16号', 119.1485000, 33.5062000, 1
FROM company c
WHERE c.company_type = 'subsidiary'
  AND NOT EXISTS (SELECT 1 FROM project p WHERE p.name = '楚州古城文旅资产包' AND p.deleted_at IS NULL)
LIMIT 1;

INSERT INTO project (company_id, name, address, longitude, latitude, status)
SELECT c.id, '淮阴仓储物流园', '江苏省淮安市淮阴区北京北路128号', 119.0412000, 33.6358000, 1
FROM company c
WHERE c.company_type = 'subsidiary'
  AND NOT EXISTS (SELECT 1 FROM project p WHERE p.name = '淮阴仓储物流园' AND p.deleted_at IS NULL)
LIMIT 1;

INSERT INTO project (company_id, name, address, longitude, latitude, status)
SELECT c.id, '经开区标准厂房群', '江苏省淮安市经济技术开发区富强路66号', 119.1886000, 33.5754000, 1
FROM company c
WHERE c.company_type = 'subsidiary'
  AND NOT EXISTS (SELECT 1 FROM project p WHERE p.name = '经开区标准厂房群' AND p.deleted_at IS NULL)
LIMIT 1;

INSERT INTO project (company_id, name, address, longitude, latitude, status)
SELECT c.id, '洪泽湖畔商业综合体', '江苏省淮安市洪泽区东风路58号', 118.8752000, 33.2986000, 1
FROM company c
WHERE c.company_type = 'subsidiary'
  AND NOT EXISTS (SELECT 1 FROM project p WHERE p.name = '洪泽湖畔商业综合体' AND p.deleted_at IS NULL)
LIMIT 1;

-- 既有演示资产：挂到清江浦并补坐标
UPDATE asset a
SET project_id = p.id,
    province = '江苏省',
    city = '淮安市',
    district = '清江浦区',
    address = CASE a.asset_no
        WHEN 'AST-2026-001' THEN '枚乘东路88号1号厂房'
        WHEN 'AST-2026-002' THEN '枚乘东路88号2号办公楼'
        WHEN 'AST-2026-003' THEN '枚乘东路商业街3号商铺'
        WHEN 'AST-2026-004' THEN '枚乘东路88号仓储区'
        WHEN 'AST-2026-005' THEN '枚乘东路南侧临时地块'
        ELSE a.address
    END,
    longitude = CASE a.asset_no
        WHEN 'AST-2026-001' THEN 119.0461000
        WHEN 'AST-2026-002' THEN 119.0443000
        WHEN 'AST-2026-003' THEN 119.0475000
        WHEN 'AST-2026-004' THEN 119.0438000
        WHEN 'AST-2026-005' THEN 119.0482000
        ELSE a.longitude
    END,
    latitude = CASE a.asset_no
        WHEN 'AST-2026-001' THEN 33.5830000
        WHEN 'AST-2026-002' THEN 33.5812000
        WHEN 'AST-2026-003' THEN 33.5828000
        WHEN 'AST-2026-004' THEN 33.5805000
        WHEN 'AST-2026-005' THEN 33.5798000
        ELSE a.latitude
    END,
    vacant_since = CASE WHEN a.asset_no = 'AST-2026-002' THEN now() - INTERVAL '45 days' ELSE a.vacant_since END,
    vacant_reason = CASE WHEN a.asset_no = 'AST-2026-002' THEN '退租' ELSE a.vacant_reason END,
    updated_at = now()
FROM project p
WHERE p.name = '清江浦智慧产业园'
  AND p.deleted_at IS NULL
  AND a.deleted_at IS NULL
  AND a.asset_no IN ('AST-2026-001', 'AST-2026-002', 'AST-2026-003', 'AST-2026-004', 'AST-2026-005');

-- 淮安多区县补充点位
INSERT INTO asset (
    project_id, asset_no, name, asset_type, area, source_type, ownership_type,
    property_company_id, operating_company_id, lease_control_status,
    province, city, district, address, longitude, latitude,
    vacant_since, vacant_reason, version
)
SELECT p.id, v.asset_no, v.name, v.asset_type, v.area, 'self', 'own',
       c.id, c.id, v.status,
       '江苏省', '淮安市', v.district, v.address, v.lng, v.lat,
       v.vacant_since, v.vacant_reason, 0
FROM company c
CROSS JOIN LATERAL (
    VALUES
        ('楚州古城文旅资产包', 'AST-HA-101', '镇淮楼文创商铺', 'property', 220.00, 'leased',
         '淮安区', '镇淮楼东路文创街区A栋', 119.1492000, 33.5071000, NULL::timestamptz, NULL::varchar),
        ('楚州古城文旅资产包', 'AST-HA-102', '河下古镇民宿楼', 'property', 680.00, 'vacant',
         '淮安区', '河下古镇竹巷18号', 119.0586000, 33.5624000, now() - INTERVAL '120 days', '退租'),
        ('楚州古城文旅资产包', 'AST-HA-103', '周恩来纪念馆配套停车场地块', 'land', 1500.00, 'self_use',
         '淮安区', '淮海北路纪念馆南侧', 119.1528000, 33.5085000, NULL, NULL),
        ('淮阴仓储物流园', 'AST-HA-201', '北京北路冷链仓', 'property', 3200.00, 'leased',
         '淮阴区', '北京北路物流园1号库', 119.0425000, 33.6372000, NULL, NULL),
        ('淮阴仓储物流园', 'AST-HA-202', '王营货场周转地', 'land', 5000.00, 'occupied',
         '淮阴区', '王营街道货场路东侧', 119.0288000, 33.6485000, NULL, NULL),
        ('经开区标准厂房群', 'AST-HA-301', '富强路A栋标准厂房', 'property', 4500.00, 'leased',
         '经济技术开发区', '富强路66号A栋', 119.1895000, 33.5762000, NULL, NULL),
        ('经开区标准厂房群', 'AST-HA-302', '富强路B栋标准厂房', 'property', 4200.00, 'leasing',
         '经济技术开发区', '富强路66号B栋', 119.1912000, 33.5748000, NULL, NULL),
        ('经开区标准厂房群', 'AST-HA-303', '淮安东站临街商铺', 'property', 180.00, 'vacant',
         '经济技术开发区', '高铁东站站前广场东侧商铺', 119.1928000, 33.5886000, now() - INTERVAL '28 days', '退租'),
        ('洪泽湖畔商业综合体', 'AST-HA-401', '东风路临湖商铺', 'property', 320.00, 'leased',
         '洪泽区', '东风路临湖商业街12号', 118.8768000, 33.2995000, NULL, NULL),
        ('洪泽湖畔商业综合体', 'AST-HA-402', '老子山旅游服务中心', 'property', 960.00, 'partial_leased',
         '洪泽区', '老子山镇景区入口服务中心', 118.7125000, 33.1856000, NULL, NULL)
) AS v(project_name, asset_no, name, asset_type, area, status, district, address, lng, lat, vacant_since, vacant_reason)
JOIN project p ON p.name = v.project_name AND p.deleted_at IS NULL
WHERE c.company_type = 'subsidiary'
  AND NOT EXISTS (SELECT 1 FROM asset a WHERE a.asset_no = v.asset_no AND a.deleted_at IS NULL);
