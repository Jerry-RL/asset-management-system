-- ============================================================================
-- V50 分区楼层（project_zone_floor）
--
-- 背景：楼层此前只作为 asset.floor_no 这个**整数属性**存在，没有任何「楼层」实体：
--   项目详情页按 floor_no 分组展示、资产表单手填楼层号。于是「分区下有哪些楼层」
--   这个结构性问题没有落点 —— 没有楼层的分区与「有 3 个楼层但只录了 2 个」的分区
--   在库里长得一模一样，楼层也无法单独维护（改名 / 查看本层统计 / 先建层再放资产）。
--
-- 本迁移把楼层升格为分区的**从属实体**：
--   project_zone_floor 归属 zone_id（不是 project_id）：楼层是分区的内部结构，与分区同生命周期。
--   表名沿用 project_zone 的前缀，与实体 ProjectZoneFloor 一一对应（MyBatis-Plus @TableName）。
--   资产的关联方式**不变**（仍靠 asset.zone_id + asset.floor_no 双字段定位），
--   因此本迁移不改 asset 表、不动任何存量归属 —— 新增的只是「楼层清单」这一层元数据。
--
-- 刻意不设面积 / 排序列：
--   面积与资产宗数一律取该层资产汇总（与 project_zone.asset_area 的既有口径一致，
--   见 AssetService#fillZoneAssetStats），避免「人工维护的面积」与「资产实际面积」两套数字；
--   楼层顺序按 floor_no 升序即物理楼层顺序，另设 sort 只会制造两个相互矛盾的排序来源。
--
-- 纯 expand：只建表 / 建索引 / 给本表回填，不改任何既有表、不插菜单 ——
-- 楼层复用 asset.project:* 权限码，因此没有配套的授权语句。
--
-- 可重复执行：建表用 IF NOT EXISTS，回填靠部分唯一索引 + ON CONFLICT DO NOTHING。
-- ============================================================================

CREATE TABLE IF NOT EXISTS project_zone_floor (
    id          BIGSERIAL PRIMARY KEY,
    zone_id     BIGINT       NOT NULL,
    floor_no    INTEGER      NOT NULL,          -- 楼层号，负数表示地下层（B1 = -1）
    name        VARCHAR(100),                   -- 展示名，为空时前端回落为「3F」
    remark      VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    created_by  BIGINT,
    updated_by  BIGINT,
    deleted_at  TIMESTAMPTZ
);

-- 部分唯一索引：同一分区内楼层号唯一，但只约束「未软删」的行 —— 软删后允许重新录入。
-- 这也是回填语句的幂等锚点（ON CONFLICT DO NOTHING 涵盖部分索引冲突）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_project_zone_floor_zone_no
    ON project_zone_floor (zone_id, floor_no) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_project_zone_floor_zone
    ON project_zone_floor (zone_id, floor_no);

COMMENT ON TABLE  project_zone_floor IS '分区楼层：分区的从属结构，资产仍以 zone_id + floor_no 关联';
COMMENT ON COLUMN project_zone_floor.floor_no IS '楼层号，负数表示地下层';
COMMENT ON COLUMN project_zone_floor.name IS '楼层展示名，为空时回落为「<floor_no>F」';

-- ============================================================================
-- 回填：把存量资产已经用到的楼层号补成楼层记录
--
-- 不做这一步，升级后每个分区的楼层 Tab 都是空的 —— 而资产其实带着 floor_no，
-- 表现为「明明有 20 层的资产，楼层栏里一个都没有」，且用户只能靠手工逐个补录。
-- 只回填「有资产在用」的楼层号：空楼层没有资产就没有回填依据，由用户按需新增。
-- ============================================================================
INSERT INTO project_zone_floor (zone_id, floor_no, name)
SELECT a.zone_id,
       a.floor_no,
       a.floor_no || 'F'
FROM asset a
WHERE a.zone_id IS NOT NULL
  AND a.floor_no IS NOT NULL
  AND a.deleted_at IS NULL
GROUP BY a.zone_id, a.floor_no
ON CONFLICT DO NOTHING;
