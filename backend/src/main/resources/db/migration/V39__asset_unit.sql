-- ============================================================================
-- V39 资产计租单元（asset_unit）：资产 → 单元 → 合同 三层模型（第一步：扩展 + 回填）
--   决策依据：docs/adr/0019-asset-unit-and-occupancy-model.md（决策 A：A1 原子单元）
--   单元 = 计租最小原子单位（房间 / 铺位）；部分出租 / 部分占用通过「拆单元」表达，
--   不由 asset_unit.area 与 contract.lease_area 的差额表达。
--   本迁移只「加表 + 加列 + 回填」，不删除任何既有列，可与现有应用共存（expand 阶段）。
--   幂等：CREATE ... IF NOT EXISTS / ADD COLUMN IF NOT EXISTS + NOT EXISTS 守卫。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 单元表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS asset_unit (
    id            BIGSERIAL PRIMARY KEY,
    asset_id      BIGINT        NOT NULL,
    unit_no       VARCHAR(64)   NOT NULL,
    unit_name     VARCHAR(200),
    -- 单元面积；0 表示「面积待补」，不参与面积统计（见 v_asset_unit_budget）
    area          NUMERIC(18,2) NOT NULL DEFAULT 0,
    -- 单元可租面积；为空表示等同 area
    rentable_area NUMERIC(18,2),
    -- 单元底价；为空则回退 asset.base_rent_floor
    base_rent     NUMERIC(18,2),
    -- 物化派生列：vacant/leasing/leased/self_use/occupied/disposing/exited
    -- 仅由 LeaseStatusDeriver 经 v_unit_lease_status 写入，禁止业务代码直改
    unit_status   VARCHAR(30)   NOT NULL DEFAULT 'vacant',
    floor_no      INTEGER,
    sort          INTEGER       NOT NULL DEFAULT 0,
    remark        VARCHAR(500),
    -- 乐观锁（对齐 asset.version 口径）
    version       INTEGER       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    created_by    BIGINT,
    updated_by    BIGINT,
    deleted_at    TIMESTAMPTZ,
    CONSTRAINT ck_asset_unit_area CHECK (area >= 0),
    CONSTRAINT ck_asset_unit_rentable CHECK (rentable_area IS NULL OR rentable_area >= 0)
);

-- 软删除下的唯一性：同一资产内单元编号唯一（对齐 asset_no 的 partial unique 范式）
CREATE UNIQUE INDEX IF NOT EXISTS uq_asset_unit_no
    ON asset_unit (asset_id, unit_no) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_asset_unit_status
    ON asset_unit (unit_status) WHERE deleted_at IS NULL;

COMMENT ON COLUMN asset_unit.area IS '单元面积；0 表示面积待补（占位单元）';
COMMENT ON COLUMN asset_unit.unit_status IS '物化派生列，由 LeaseStatusDeriver 从占用集合派生';
COMMENT ON COLUMN asset_unit.deleted_at IS '软删除时间；仅由单元拆分/合并写入';

-- ---------------------------------------------------------------------------
-- 2) 关联列：合同 / 招租 / 组合租赁明细 指向单元
--    保留既有 asset_id 冗余列（报表、RBAC 数据范围、看板均依赖），
--    由 asset_unit.asset_id 保证一致，避免全链路 JOIN 改造。
-- ---------------------------------------------------------------------------
ALTER TABLE contract          ADD COLUMN IF NOT EXISTS asset_unit_id BIGINT;
ALTER TABLE lease_listing     ADD COLUMN IF NOT EXISTS asset_unit_id BIGINT;
ALTER TABLE lease_bundle_item ADD COLUMN IF NOT EXISTS asset_unit_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_contract_unit        ON contract (asset_unit_id);
CREATE INDEX IF NOT EXISTS idx_lease_listing_unit   ON lease_listing (asset_unit_id);
CREATE INDEX IF NOT EXISTS idx_lease_bundle_item_unit ON lease_bundle_item (asset_unit_id);

COMMENT ON COLUMN contract.asset_unit_id IS '计租单元（asset_unit.id）；非终态合同必须非空（收敛阶段加约束）';
COMMENT ON COLUMN lease_listing.asset_unit_id IS '招租标的单元；是「意向」而非占用，不参与区间互斥';

-- ---------------------------------------------------------------------------
-- 3) 回填：在租/续租/到期合同 → 单元，并在同一语句内回填 contract.asset_unit_id
--    使用 data-modifying CTE（INSERT ... RETURNING 供外层 UPDATE 使用），
--    保证「建单元」与「关联合同」的映射确定，不依赖自增 id 顺序。
--    守卫 asset_unit_id IS NULL → 重跑不会重复建单元。
-- ---------------------------------------------------------------------------
WITH src AS (
    SELECT c.id            AS contract_id,
           c.asset_id,
           a.asset_no,
           a.floor_no,
           COALESCE(c.lease_area, a.lease_area, a.area, 0) AS unit_area,
           ROW_NUMBER() OVER (PARTITION BY c.asset_id ORDER BY c.id) AS rn
      FROM contract c
      JOIN asset a ON a.id = c.asset_id AND a.deleted_at IS NULL
     WHERE c.asset_unit_id IS NULL
       AND c.status IN ('active', 'expiring', 'renewable')
), ins AS (
    INSERT INTO asset_unit (asset_id, unit_no, unit_name, area, rentable_area,
                            unit_status, floor_no, sort, remark)
    SELECT s.asset_id,
           s.asset_no || '-U' || s.rn,
           '单元' || s.rn,
           s.unit_area,
           s.unit_area,
           'leased',
           s.floor_no,
           s.rn,
           'V39 回填：按在租合同拆分'
      FROM src s
    RETURNING id, asset_id, unit_no
)
UPDATE contract c
   SET asset_unit_id = i.id
  FROM ins i
  JOIN src s
    ON s.asset_id = i.asset_id
   AND s.asset_no || '-U' || s.rn = i.unit_no
 WHERE c.id = s.contract_id;

-- ---------------------------------------------------------------------------
-- 4) 剩余面积 → 空置单元（承载未出租面积，使「部分出租」可由单元集合派生）
--    自然幂等：首次执行后剩余面积为 0，重跑不再插入。
-- ---------------------------------------------------------------------------
INSERT INTO asset_unit (asset_id, unit_no, unit_name, area, rentable_area,
                        unit_status, floor_no, sort, remark)
SELECT a.id,
       a.asset_no || '-U' || (COALESCE(s.cnt, 0) + 1),
       '剩余可租面积',
       ROUND(COALESCE(a.lease_area, a.area, 0) - COALESCE(s.area, 0), 2),
       ROUND(COALESCE(a.lease_area, a.area, 0) - COALESCE(s.area, 0), 2),
       'vacant',
       a.floor_no,
       COALESCE(s.cnt, 0) + 1,
       'V39 回填：剩余面积'
  FROM asset a
  LEFT JOIN (
        SELECT asset_id, COUNT(*) AS cnt, SUM(area) AS area
          FROM asset_unit
         WHERE deleted_at IS NULL
         GROUP BY asset_id
       ) s ON s.asset_id = a.id
 WHERE a.deleted_at IS NULL
   AND ROUND(COALESCE(a.lease_area, a.area, 0) - COALESCE(s.area, 0), 2) > 0.005;

-- ---------------------------------------------------------------------------
-- 5) 兜底：面积缺失且无合同的资产，仍建一个「面积待补」单元
--    目的：保证「每个资产至少一个单元」不变量，使 asset_occupancy.asset_unit_id 可 NOT NULL。
--    area = 0 表示待补；待补清单见 v_asset_unit_budget / v_asset_without_unit。
-- ---------------------------------------------------------------------------
INSERT INTO asset_unit (asset_id, unit_no, unit_name, area, rentable_area,
                        unit_status, floor_no, sort, remark)
SELECT a.id,
       a.asset_no || '-U1',
       '面积待补',
       0,
       NULL,
       CASE WHEN a.lease_control_status IN ('leased', 'partial_leased') THEN 'leased'
            WHEN a.lease_control_status IN ('self_use', 'occupied', 'disposing') THEN a.lease_control_status
            ELSE 'vacant' END,
       a.floor_no,
       1,
       'V39 回填：面积缺失占位'
  FROM asset a
 WHERE a.deleted_at IS NULL
   AND NOT EXISTS (
        SELECT 1 FROM asset_unit u
         WHERE u.asset_id = a.id AND u.deleted_at IS NULL
       );

-- ---------------------------------------------------------------------------
-- 6) 空置单元继承资产的非租赁状态（自用 / 占用 / 处置）
--    在租状态不在此继承：租赁占用由 V40 的 asset_occupancy(contract) 表达。
-- ---------------------------------------------------------------------------
UPDATE asset_unit u
   SET unit_status = a.lease_control_status,
       remark      = COALESCE(u.remark, 'V39 回填：继承资产状态')
  FROM asset a
 WHERE u.asset_id = a.id
   AND u.deleted_at IS NULL
   AND u.unit_status = 'vacant'
   AND a.lease_control_status IN ('self_use', 'occupied', 'disposing');

-- ---------------------------------------------------------------------------
-- 7) 招租发布 → 单元（每个发布挂到该资产第一个可租单元）
--    无「可租单元」的发布不关联，由 v_asset_unit_budget 暴露异常。
-- ---------------------------------------------------------------------------
UPDATE lease_listing l
   SET asset_unit_id = u.id
  FROM asset_unit u
 WHERE u.asset_id = l.asset_id
   AND l.asset_unit_id IS NULL
   AND u.deleted_at IS NULL
   AND u.unit_status IN ('vacant', 'leasing')
   AND u.id = (
        SELECT MIN(u2.id)
          FROM asset_unit u2
         WHERE u2.asset_id = l.asset_id
           AND u2.deleted_at IS NULL
           AND u2.unit_status IN ('vacant', 'leasing')
       );

-- ---------------------------------------------------------------------------
-- 8) 组合租赁明细 → 单元（借道合同，不依赖 id 顺序）
-- ---------------------------------------------------------------------------
UPDATE lease_bundle_item i
   SET asset_unit_id = c.asset_unit_id
  FROM contract c
 WHERE c.id = i.contract_id
   AND i.asset_unit_id IS NULL
   AND c.asset_unit_id IS NOT NULL;
