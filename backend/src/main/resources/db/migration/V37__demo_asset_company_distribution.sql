-- 演示数据补齐：把部分资产/项目下放到子公司，让「全局公司切换」可见差异
--
-- 背景：此前 7 个项目、44 条资产的所属公司全部是「淮安城投资产管理有限公司」(company 2)，
--       顶栏切到任何子公司都得到 0 条，无法验证公司切换是否生效。
--
-- 本迁移把「楚州古城文旅资产包」及其分区的资产下放到其实际运营主体
-- 「楚州古城文旅运营有限公司」(company 8，文旅集团 7 的子公司)，并把部分
-- 商业/物业类资产下放到「淮安城投商业运营有限公司」(company 3) 与
-- 「淮安城投物业服务有限公司」(company 4) —— 两者均为 company 2 的子公司。
--
-- 影响面（刻意选择「只下放、不换根」）：
--   - 不设置请求头 / 切换前的视图（company 2 子树 = {2,3,4}）总览数字保持不变，
--     仪表盘、报表口径不受影响；
--   - 切到 company 3 / 4 可见对应子集；切到 company 8 可见文旅资产包；
--   - company 8 属于文旅集团(7)，不在 company 2 子树内，故对普通账号（数据范围 company）
--     不可见 —— 这正是「子账号只能看本司及下级」的预期表现。
--
-- 幂等：仅当下放目标与当前值不同才更新（IS DISTINCT FROM）；重复执行不再写库。

-- ============================================================================
-- 1) 项目下放：楚州古城文旅资产包 → 楚州古城文旅运营有限公司
-- ============================================================================
UPDATE project p
SET company_id = c.id, updated_at = now()
FROM company c
WHERE c.name = '楚州古城文旅运营有限公司'
  AND p.name = '楚州古城文旅资产包'
  AND p.deleted_at IS NULL
  AND p.company_id IS DISTINCT FROM c.id;

-- 项目下放后，其分区下的资产同步下放（资产公司 / 经营公司 / 产权公司三者口径一致）
UPDATE asset a
SET asset_company_id     = p.company_id,
    operating_company_id = p.company_id,
    property_company_id  = p.company_id,
    updated_at           = now()
FROM project p
WHERE a.project_id = p.id
  AND a.deleted_at IS NULL
  AND p.deleted_at IS NULL
  AND p.name = '楚州古城文旅资产包'
  AND (a.asset_company_id IS DISTINCT FROM p.company_id
       OR a.operating_company_id IS DISTINCT FROM p.company_id
       OR a.property_company_id IS DISTINCT FROM p.company_id);

-- ============================================================================
-- 2) 商业与物业资产下放
--    3 商业运营：购物中心 / 商业街 / 站前商业 / 临湖餐饮等经营性商业
--    4 物业服务：写字楼 / 公寓 / 宿舍等持有型物业
--    仅动 company 2 名下的资产，避免影响已下放给子公司 8 的数据。
-- ============================================================================
WITH target AS (
    SELECT * FROM (VALUES
        ('AST-2026-003',      '淮安城投商业运营有限公司'),
        ('AST-HA-101',        '淮安城投商业运营有限公司'),
        ('AST-HA-303',        '淮安城投商业运营有限公司'),
        ('AST-HA-403',        '淮安城投商业运营有限公司'),
        ('AST-HA-406',        '淮安城投商业运营有限公司'),
        ('AST-QJP-031',       '淮安城投商业运营有限公司'),
        ('AST-2026-002',      '淮安城投物业服务有限公司'),
        ('AST-HA-102',        '淮安城投物业服务有限公司'),
        ('AST-HA-306',        '淮安城投物业服务有限公司'),
        ('AST-HA-404',        '淮安城投物业服务有限公司'),
        ('AST-HA-405',        '淮安城投物业服务有限公司'),
        ('AST-QJP-032',       '淮安城投物业服务有限公司'),
        ('AST-QJP-033',       '淮安城投物业服务有限公司')
    ) AS t(asset_no, company_name)
)
UPDATE asset a
SET asset_company_id     = c.id,
    operating_company_id = c.id,
    updated_at           = now()
FROM target t
JOIN company c ON c.name = t.company_name
WHERE a.asset_no = t.asset_no
  AND a.deleted_at IS NULL
  -- 只下放仍归属在母公司名下的资产（产权公司体现场外权属，此处保持不变）
  AND a.operating_company_id = (SELECT id FROM company WHERE name = '淮安城投资产管理有限公司')
  AND a.operating_company_id IS DISTINCT FROM c.id;
