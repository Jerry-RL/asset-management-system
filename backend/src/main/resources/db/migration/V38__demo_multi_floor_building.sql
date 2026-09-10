-- 演示数据补齐：项目详情页「分区 → 楼层 → 资产」多楼层样例
--
-- 背景：项目详情页底部按「分区 → 楼层」分组展示资产，此前的演示资产几乎全部落在 1F/2F，
--       楼层分组退化成一层，看不出多楼层结构。这里为「洪泽湖畔商业综合体 / 湖滨商务区」
--       补一栋多楼层商务大厦（1F/2F/20F/23F/24F/25F），让楼层分组与面积筛选有真实分布。
--
-- 幂等：按 asset_no 判重，重复执行不会重复插入；不改动任何存量资产 id 与其账单/合同关联。
-- 口径：资产公司 / 经营公司 / 产权公司统一对齐项目所属公司，与 V30 保持一致。

WITH proj_owner AS (
    SELECT * FROM (VALUES
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
SELECT p.id, z.id, v.asset_no, v.name, 'property', v.area, v.lease_area, NULL,
       v.usage_type, 'office_building', 'shear_wall', v.floor_no,
       'investment_construction', 'self_owned', 'operational', v.partial_lease_status,
       p.company_id, p.company_id, p.company_id,
       v.status, v.base_rent_assessed, v.base_rent_floor, v.market_ref_rent,
       '江苏省', '淮安市', '洪泽区', v.address, v.lng, v.lat,
       v.original_value, v.registered_at, d.id, u.id,
       'SB-' || v.asset_no, 'DB-' || v.asset_no, 0
FROM (VALUES
    -- 一栋多楼层商务大厦：低区商业 + 高区办公，覆盖 在租/招租中/空置/部分出租
    ('AST-HZ-T01', '商务大厦1F大堂商业',  1, 620.00, 600.00, 'commercial', NULL::varchar,
     'leased',        118.00, 108.00, 132.00, '湖滨大道18号商务大厦1层',
     118.8779000, 33.3006000, 1450.00, DATE '2021-11-08'),
    ('AST-HZ-T02', '商务大厦2F餐饮层',    2, 710.00, 680.00, 'commercial', NULL,
     'leasing',       106.00,  97.00, 119.00, '湖滨大道18号商务大厦2层',
     118.8779600, 33.3006600, 1580.00, DATE '2021-11-08'),
    ('AST-HZ-T20', '商务大厦20层办公',   20, 680.00, 660.00, 'office', NULL,
     'leased',         58.00,  52.00,  66.00, '湖滨大道18号商务大厦20层',
     118.8780400, 33.3007200, 1820.00, DATE '2021-12-20'),
    ('AST-HZ-T23', '商务大厦23层办公',   23, 680.00, NULL,   'office', NULL,
     'vacant',         61.00,  55.00,  69.00, '湖滨大道18号商务大厦23层',
     118.8781800, 33.3008200, 1840.00, DATE '2022-01-15'),
    ('AST-HZ-T24', '商务大厦24层办公',   24, 680.00, 640.00, 'office', NULL,
     'leasing',        64.00,  58.00,  72.00, '湖滨大道18号商务大厦24层',
     118.8782600, 33.3008800, 1880.00, DATE '2022-01-15'),
    ('AST-HZ-T25', '商务大厦25层办公',   25, 700.00, 520.00, 'office', 'support',
     'partial_leased', 67.00,  60.00,  76.00, '湖滨大道18号商务大厦25层',
     118.8783400, 33.3009400, 1960.00, DATE '2022-02-28')
) AS v(asset_no, name, floor_no, area, lease_area, usage_type, partial_lease_status,
       status, base_rent_assessed, base_rent_floor, market_ref_rent,
       address, lng, lat, original_value, registered_at)
JOIN project p ON p.name = '洪泽湖畔商业综合体' AND p.deleted_at IS NULL
LEFT JOIN project_zone z
       ON z.project_id = p.id AND z.name = '湖滨商务区' AND z.deleted_at IS NULL
LEFT JOIN proj_owner o ON o.project_name = p.name
LEFT JOIN department d ON d.company_id = p.company_id AND d.name = o.dept_name AND d.status = 1
LEFT JOIN "user" u ON u.username = o.username AND u.status = 1
WHERE NOT EXISTS (
    SELECT 1 FROM asset a WHERE a.asset_no = v.asset_no AND a.deleted_at IS NULL
);

-- 空置资产的空置起始 / 原因（幂等：仅填空值，供详情页「闲置」口径与后续招租使用）
UPDATE asset SET vacant_since = now() - INTERVAL '45 days', vacant_reason = '新交付', updated_at = now()
WHERE asset_no = 'AST-HZ-T23' AND deleted_at IS NULL AND vacant_since IS NULL;
