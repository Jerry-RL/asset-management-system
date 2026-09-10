-- 演示数据自洽：新增资产的租控状态与合同口径对齐
--   背景：V30 新增资产中有 9 条标记为「在租(leased)」或「部分出租(partial_leased)」，
--        但 DemoDataConsistencyEnricher.ensureSatelliteLeaseChains 只为固定清单的资产生成
--        租约链路（合同+付款计划+账单+收款），因此这 9 条会触发
--        「[demo-enrich] 在租资产 XXX 缺少生效合同」告警，且合同/报表模块不含这些资产。
--   处理：由于这些资产尚无合同，按真实业务口径将其调整为「招租中」或「空置」，
--        并清理按旧状态生成的租控履历，交由 enricher 在下次启动时按新状态重建，
--        使「资产台账 ↔ 租控履历 ↔ 招租信息」自洽。
--   幂等：状态更新带 IS DISTINCT FROM 判断；履历清理针对特定错误终态与特定资产。

-- ============================================================================
-- 1) 无合同且拟继续招租 → 招租中(leasing)
--    保留其「vacant → leasing 发布招租」履历，仅清理错误的签约履历（见第 3 步）。
-- ============================================================================
UPDATE asset a
SET lease_control_status = 'leasing',
    partial_lease_status = NULL,        -- 非部分出租，清空部分租赁状态
    updated_at = now()
WHERE a.deleted_at IS NULL
  AND a.asset_no IN (
      'AST-QJP-011',    -- 智慧研发中心
      'AST-QJP-033',    -- 园区人才公寓
      'AST-HA-105',     -- 纪念馆游客服务中心
      'AST-HA-304',     -- A栋辅房
      'AST-HA-406'      -- 老子山温泉度假村
  )
  AND (a.lease_control_status IS DISTINCT FROM 'leasing'
       OR a.partial_lease_status IS NOT NULL);

-- ============================================================================
-- 2) 无合同且已退出经营 → 空置(vacant)，并补齐空置起始与原因
-- ============================================================================
UPDATE asset a
SET lease_control_status = 'vacant',
    partial_lease_status = NULL,
    vacant_reason        = COALESCE(a.vacant_reason, v.reason),
    vacant_since         = COALESCE(a.vacant_since, now() - make_interval(days => v.days)),
    updated_at           = now()
FROM (VALUES
    ('AST-QJP-031', '退租',       45),   -- 园区食堂商业配套
    ('AST-HA-203',  '退租',       75),   -- 恒温冷库2号
    ('AST-HA-403',  '退租',       30),   -- 临湖餐饮街2号楼
    ('AST-HA-405',  '新交付待租', 20)    -- 湖景公寓
) AS v(asset_no, reason, days)
WHERE a.deleted_at IS NULL
  AND a.asset_no = v.asset_no
  AND a.lease_control_status IS DISTINCT FROM 'vacant';

-- ============================================================================
-- 3) 清理与旧状态（leased / partial_leased）匹配的错误租控履历
--    3.1 删除「合同生效 / 部分面积签约」履历（这些资产并无合同）
-- ============================================================================
DELETE FROM lease_control_log l
USING asset a
WHERE l.asset_id = a.id
  AND a.asset_no IN (
      'AST-QJP-011', 'AST-QJP-031', 'AST-QJP-033', 'AST-HA-105', 'AST-HA-203',
      'AST-HA-304', 'AST-HA-403', 'AST-HA-405', 'AST-HA-406'
  )
  AND l.to_status IN ('leased', 'partial_leased');

-- 3.2 转为空置的资产：清空履历，由 enricher 按
--     「历史招租 → 历史签约 → 发起退租 → 清场完成空置」全链路重建
DELETE FROM lease_control_log l
USING asset a
WHERE l.asset_id = a.id
  AND a.asset_no IN ('AST-QJP-031', 'AST-HA-203', 'AST-HA-403', 'AST-HA-405');
