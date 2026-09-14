-- ============================================================================
-- 拆分 / 合并业务演示数据（ADR-0021 / FR-MDM-001~004、FR-OPS-006）
--
-- 目的：为「资产支持部分租赁 → 拆分合并可租单元」提供一组可直接操作的样例，
--      覆盖四种典型状态 —— 可拆、已拆可合、含在租单元不可动、占位单元不可拆。
--
-- 幂等：可重复执行。脚本开头按 AST-DEMO-* / CT-DEMO-* 清理上一批后重建，
--      因此不会累积脏数据，也不会与既有演示数据（AST-HA-* / AST-HZ-* / AST-QJP-*）冲突。
--
-- 执行：
--   docker exec -i -e PGPASSWORD=ams_dev_password ams-postgres \
--     psql -U ams -d ams < scripts/seed-split-merge-demo.sql
--
-- 归属：落在「洪泽湖畔商业综合体 / 湖滨商务区」（与既有商务大厦系列同一分区），
--      公司取该项目所属公司 —— 保证 operator / assetmgr 等公司级数据范围账号也能看到。
--
-- ⚠️ 本脚本刻意**同步物化派生列**（见末尾 §5）。若只插业务行不刷派生列，
--    会亲手制造出 ADR-0021 反复警告的「状态漂移」——那正是本项目要消灭的问题。
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 0) 幂等清理：先删占用（有 EXCLUDE 约束）/ 合同 / 单元，再删资产
--    顺序不能反：占用与合同都引用资产与单元的 id。
-- ---------------------------------------------------------------------------
DELETE FROM asset_occupancy
 WHERE asset_id IN (SELECT id FROM asset WHERE asset_no LIKE 'AST-DEMO-%');
DELETE FROM contract WHERE contract_no LIKE 'CT-DEMO-%';
DELETE FROM asset_unit
 WHERE asset_id IN (SELECT id FROM asset WHERE asset_no LIKE 'AST-DEMO-%');
-- 结构日志两处都要清：
--   ① 本脚本自己写的那条（备注前缀固定）；
--   ② 使用者在这批演示资产上真实操作产生的 unit_split / unit_merge
--      —— 否则重跑后资产 id 会变，日志里留下指向已消失 id 的悬空记录。
DELETE FROM asset_structure_log
 WHERE remark LIKE '【演示数据】%'
    OR source_asset_ids IN (SELECT '[' || id || ']' FROM asset WHERE asset_no LIKE 'AST-DEMO-%');
DELETE FROM asset WHERE asset_no LIKE 'AST-DEMO-%';

-- ---------------------------------------------------------------------------
-- 1) 归属上下文：项目 / 分区 / 公司
--    用临时表存一次，避免在四段插入里重复写同一个子查询。
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE demo_ctx ON COMMIT DROP AS
SELECT p.id AS project_id,
       z.id AS zone_id,
       p.company_id
  FROM project p
  JOIN project_zone z ON z.project_id = p.id AND z.deleted_at IS NULL
 WHERE p.deleted_at IS NULL
   AND z.name = '湖滨商务区'
 ORDER BY z.id
 LIMIT 1;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM demo_ctx) THEN
        RAISE EXCEPTION '演示数据前置不满足：找不到「湖滨商务区」分区（project_zone.deleted_at IS NULL）';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 2) AST-DEMO-01 待分租：1 个整层单元 600 ㎡
--    用途：现场演示「拆分」。拆成 3×200 后单元列表由 1 行变 3 行。
-- ---------------------------------------------------------------------------
WITH new_asset AS (
    INSERT INTO asset (project_id, zone_id, asset_no, name, asset_type,
                       area, lease_area, floor_no, usage_type, asset_nature,
                       partial_lease_status, asset_company_id, operating_company_id,
                       property_company_id, source_type, ownership_type,
                       structure_type, building_plan, base_rent_assessed, base_rent_floor,
                       original_value, structure_status, lifecycle_status, ownership_status,
                       registered_at, province, city, district, address, version)
    SELECT c.project_id, c.zone_id, 'AST-DEMO-01', '演示·沿街商铺整层（可拆分）', 'property',
           600.00, 600.00, 1, 'commercial', 'operational',
           'support', c.company_id, c.company_id,
           c.company_id, 'investment_construction', 'self_owned',
           'shear_wall', 'commercial_building', 380.00, 300.00,
           1200.00, 'active', 'in_book', 'in_group',
           CURRENT_DATE, '江苏省', '淮安市', '洪泽区', '湖滨商务区演示楼 1F 整层', 0
      FROM demo_ctx c
    RETURNING id, asset_no, floor_no
)
INSERT INTO asset_unit (asset_id, unit_no, unit_name, area, rentable_area, base_rent,
                        unit_status, floor_no, sort, remark, version)
SELECT a.id, a.asset_no || '-U1', '整层', 600.00, 600.00, 300.00,
       'vacant', a.floor_no, 1,
       '【演示数据】整层未拆分：可在操作栏「计租单元」中拆成多个铺位', 0
  FROM new_asset a;

-- ---------------------------------------------------------------------------
-- 3) AST-DEMO-02 已拆为 3 间：3 个 200 ㎡ 单元 + 原单元软删
--    用途：演示「合并」；同时展示拆分结果应有的样子（原单元失效、子单元独立）。
--    原单元保留 soft-delete 行，正是「历史身份不丢」的落地形式。
-- ---------------------------------------------------------------------------
WITH new_asset AS (
    INSERT INTO asset (project_id, zone_id, asset_no, name, asset_type,
                       area, lease_area, floor_no, usage_type, asset_nature,
                       partial_lease_status, asset_company_id, operating_company_id,
                       property_company_id, source_type, ownership_type,
                       structure_type, building_plan, base_rent_assessed, base_rent_floor,
                       original_value, structure_status, lifecycle_status, ownership_status,
                       registered_at, province, city, district, address, version)
    SELECT c.project_id, c.zone_id, 'AST-DEMO-02', '演示·商铺已拆为3间（可合并）', 'property',
           600.00, 600.00, 2, 'commercial', 'operational',
           'support', c.company_id, c.company_id,
           c.company_id, 'investment_construction', 'self_owned',
           'shear_wall', 'commercial_building', 380.00, 300.00,
           1200.00, 'active', 'in_book', 'in_group',
           CURRENT_DATE, '江苏省', '淮安市', '洪泽区', '湖滨商务区演示楼 2F 分间', 0
      FROM demo_ctx c
    RETURNING id, asset_no, floor_no
)
INSERT INTO asset_unit (asset_id, unit_no, unit_name, area, rentable_area, base_rent,
                        unit_status, floor_no, sort, remark, version, deleted_at)
SELECT a.id, a.asset_no || '-U1-1', '整层-1', 200.00, 200.00, 100.00,
       'vacant', a.floor_no, 101, '【演示数据】子单元（拆分产出）', 0, NULL::timestamptz
  FROM new_asset a
UNION ALL
SELECT a.id, a.asset_no || '-U1-2', '整层-2', 200.00, 200.00, 100.00,
       'vacant', a.floor_no, 102, '【演示数据】子单元（拆分产出）', 0, NULL::timestamptz
  FROM new_asset a
UNION ALL
SELECT a.id, a.asset_no || '-U1-3', '整层-3', 200.00, 200.00, 100.00,
       'vacant', a.floor_no, 103, '【演示数据】子单元（拆分产出）', 0, NULL::timestamptz
  FROM new_asset a
UNION ALL
-- 原单元：已软删。面积/底价保留原值（拆分前的历史形态），仅因被替换而失效
SELECT a.id, a.asset_no || '-U1', '整层', 600.00, 600.00, 300.00,
       'vacant', a.floor_no, 1, '【演示数据】原单元，已拆分为 3 个子单元', 0, now()
  FROM new_asset a;

-- 结构变更留痕：让「资产台账 → 拆分合并日志」页有据可查（FR-MDM-003）
INSERT INTO asset_structure_log (op_type, source_asset_ids, result_asset_ids,
                                mapping_json, remark, created_at)
SELECT 'unit_split',
       '[' || a.id || ']',
       '[' || a.id || ']',
       '{"sourceUnitIds":["' || a.asset_no || '-U1"],"resultUnitIds":["'
           || a.asset_no || '-U1-1","' || a.asset_no || '-U1-2","'
           || a.asset_no || '-U1-3"],"units":[{"area":200.00},{"area":200.00},{"area":200.00}]}',
       '【演示数据】AST-DEMO-02 整层拆分为 3 间',
       now()
  FROM asset a
 WHERE a.asset_no = 'AST-DEMO-02';

-- ---------------------------------------------------------------------------
-- 4) AST-DEMO-03 部分已出租：3 个 200 ㎡ 单元，其中 1 间在租
--    用途：① 演示派生状态「部分出租」与占用率；② 演示「含在租单元的资产不可再拆」
--    —— 拆分/合并会被后端以「单元存在生效占用」拒绝，这是 EXCLUDE 约束之外的服务层提示。
--
--    占用面积恒等于单元面积（ADR-0019 A1 不变量）：这里 200 = 200。
--    date_to 给具体日期而非 NULL：演示数据不该留下"永不结束"的开放区间。
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE demo03 ON COMMIT DROP AS
WITH new_asset AS (
    INSERT INTO asset (project_id, zone_id, asset_no, name, asset_type,
                       area, lease_area, floor_no, usage_type, asset_nature,
                       partial_lease_status, asset_company_id, operating_company_id,
                       property_company_id, source_type, ownership_type,
                       structure_type, building_plan, base_rent_assessed, base_rent_floor,
                       original_value, structure_status, lifecycle_status, ownership_status,
                       registered_at, province, city, district, address, version)
    SELECT c.project_id, c.zone_id, 'AST-DEMO-03', '演示·部分已出租（含在租单元）', 'property',
           600.00, 600.00, 3, 'commercial', 'operational',
           'support', c.company_id, c.company_id,
           c.company_id, 'investment_construction', 'self_owned',
           'shear_wall', 'commercial_building', 380.00, 300.00,
           1200.00, 'active', 'in_book', 'in_group',
           CURRENT_DATE, '江苏省', '淮安市', '洪泽区', '湖滨商务区演示楼 3F 分间', 0
      FROM demo_ctx c
    RETURNING id, asset_no, floor_no
), units AS (
    INSERT INTO asset_unit (asset_id, unit_no, unit_name, area, rentable_area, base_rent,
                            unit_status, floor_no, sort, remark, version)
    SELECT a.id, a.asset_no || '-U1-' || n.idx, '整层-' || n.idx, 200.00, 200.00, 100.00,
           'vacant', a.floor_no, 100 + n.idx,
           '【演示数据】子单元（拆分产出）', 0
      FROM new_asset a
      CROSS JOIN (VALUES (1), (2), (3)) AS n(idx)
    RETURNING id, asset_id, unit_no
)
SELECT a.id AS asset_id, a.asset_no, u.id AS leased_unit_id, u.unit_no AS leased_unit_no
  FROM new_asset a
  JOIN units u ON u.asset_id = a.id AND u.unit_no = a.asset_no || '-U1-2';

-- 为该资产建一份生效合同（仅占用 1 个单元 → 资产层派生为「部分出租」）
WITH new_contract AS (
INSERT INTO contract (contract_no, asset_id, asset_unit_id, tenant_id, version,
                      start_date, end_date, rent_type, rent_amount, deposit_amount,
                      prepay_amount, payment_cycle, status, remark)
SELECT 'CT-DEMO-003', d.asset_id, d.leased_unit_id, 5, 1,
       DATE '2026-01-01', DATE '2026-12-31', 'fixed_monthly', 8000.00, 24000.00,
       0.00, 'monthly', 'active', '【演示数据】AST-DEMO-03 的 1 间商铺租赁合同'
  FROM demo03 d
    RETURNING id, asset_id, asset_unit_id
)
INSERT INTO asset_occupancy (asset_id, asset_unit_id, occupancy_type, subject_type, subject_id,
                             area, date_from, date_to, biz_status, exclusive, remark)
SELECT c.asset_id, c.asset_unit_id, 'contract', 'contract', c.id,
       200.00, DATE '2026-01-01', DATE '2026-12-31', 'active', true,
       '【演示数据】合同占用 1 间，资产派生为「部分出租」'
  FROM new_contract c;

-- ---------------------------------------------------------------------------
-- 5) AST-DEMO-04 面积待补：1 个 area = 0 的占位单元
--    用途：演示拆分入口的第一道门禁 —— 占位单元一律拒绝拆分
--    （ADR-0021 缺陷 D-15：旧实现对 area = 0 跳过面积守恒校验，可被绕过）。
--    这也是 V39 回填对「面积缺失资产」的既有处理方式，不是脏数据。
-- ---------------------------------------------------------------------------
WITH new_asset AS (
    INSERT INTO asset (project_id, zone_id, asset_no, name, asset_type,
                       area, lease_area, floor_no, usage_type, asset_nature,
                       partial_lease_status, asset_company_id, operating_company_id,
                       property_company_id, source_type, ownership_type,
                       structure_status, lifecycle_status, ownership_status,
                       registered_at, province, city, district, address, version)
    SELECT c.project_id, c.zone_id, 'AST-DEMO-04', '演示·面积待补（不可拆分）', 'property',
           NULL, NULL, 4, 'commercial', 'operational',
           'support', c.company_id, c.company_id,
           c.company_id, 'investment_construction', 'self_owned',
           'active', 'in_book', 'in_group',
           CURRENT_DATE, '江苏省', '淮安市', '洪泽区', '湖滨商务区演示楼 4F（面积未登记）', 0
      FROM demo_ctx c
    RETURNING id, asset_no, floor_no
)
INSERT INTO asset_unit (asset_id, unit_no, unit_name, area, rentable_area, base_rent,
                        unit_status, floor_no, sort, remark, version)
SELECT a.id, a.asset_no || '-U1', '面积待补', 0, NULL, NULL,
       'vacant', a.floor_no, 1,
       '【演示数据】面积待补占位单元：拆分入口会被禁用', 0
  FROM new_asset a;

-- ---------------------------------------------------------------------------
-- 6) 同步物化派生列（等价于 LeaseStatusDeriver.refresh 的两条集合更新）
--    不做这一步，新建资产会立刻出现在 v_asset_lease_status_reconcile 里 ——
--    即「物化列 ≠ 视图」，正是 ADR-0021 D-05 描述的漂移。
-- ---------------------------------------------------------------------------
UPDATE asset_unit u
   SET unit_status = v.derived_status,
       updated_at  = now()
  FROM v_unit_lease_status v
 WHERE v.unit_id = u.id
   AND u.asset_id IN (SELECT id FROM asset WHERE asset_no LIKE 'AST-DEMO-%')
   AND u.deleted_at IS NULL
   AND u.unit_status IS DISTINCT FROM v.derived_status;

UPDATE asset a
   SET lease_control_status = d.derived_status,
       occupancy_ratio      = d.occupancy_ratio,
       updated_at           = now()
  FROM v_asset_lease_status_derived d
 WHERE d.asset_id = a.id
   AND a.asset_no LIKE 'AST-DEMO-%'
   AND (a.lease_control_status IS DISTINCT FROM d.derived_status
        OR a.occupancy_ratio IS DISTINCT FROM d.occupancy_ratio);

COMMIT;

-- ---------------------------------------------------------------------------
-- 7) 结果自检（脚本输出，便于直接确认）
-- ---------------------------------------------------------------------------
\echo ''
\echo '=== 演示资产业务视图 ==='
SELECT a.asset_no,
       left(a.name, 22)        AS name,
       a.area,
       a.lease_area,
       a.lease_control_status   AS 租控,
       a.occupancy_ratio        AS 占用率,
       a.partial_lease_status   AS 部分租赁,
       count(u.id) FILTER (WHERE u.deleted_at IS NULL)                          AS 有效单元,
       string_agg(u.unit_no || '(' || u.area || ')', ', '
                  ORDER BY u.sort) FILTER (WHERE u.deleted_at IS NULL)          AS 单元明细,
       count(u.id) FILTER (WHERE u.deleted_at IS NOT NULL)                      AS 已失效单元
  FROM asset a
  LEFT JOIN asset_unit u ON u.asset_id = a.id
 WHERE a.asset_no LIKE 'AST-DEMO-%'
 GROUP BY a.id
 ORDER BY a.asset_no;

\echo ''
\echo '=== 派生一致性（必须为空：new 出来的资产不得引入状态漂移）==='
SELECT * FROM v_asset_lease_status_reconcile WHERE asset_no LIKE 'AST-DEMO-%';

\echo ''
\echo '=== 单元面积预算（area_missing 为预期：AST-DEMO-04 故意面积待补）==='
SELECT * FROM v_asset_unit_budget WHERE asset_no LIKE 'AST-DEMO-%' ORDER BY asset_no;

\echo ''
\echo '=== 占用与收口（open_occupancies 应为 0：演示占用已给明确 date_to）==='
SELECT a.asset_no, o.occupancy_type, o.biz_status, o.date_from, o.date_to, o.area
  FROM asset_occupancy o JOIN asset a ON a.id = o.asset_id
 WHERE a.asset_no LIKE 'AST-DEMO-%'
 ORDER BY a.asset_no, o.id;
