-- ============================================================================
-- V57 资产处置记录（asset_disposal_record）
--
-- 需求两条：
--   ① 新增「资产处置记录」菜单，展示**所有被处置的资产**；
--   ② 项目 / 项目分区 / 资产被处置后，资产不再属于原产权公司。
--
-- 为什么单建一张台账而不是「把 disposal_order / biz_disposal_record 拼起来查」：
--   · 处置对象有三个层级（项目 / 分区 / 资产），而「被处置的资产」是资产的粒度 ——
--     项目 / 分区级处置要**逐资产展开**，展开动作发生在级联那一刻，事后靠 JOIN 反推
--     会因为资产后来被调拨 / 分区调整而错位；
--   · 处置**不可逆**，而作为来源的 `biz_disposal_record` 走 record-sheet 全量 diff、
--     **可以被软删**：台账若不快照处置方式 / 金额 / 日期 / 处置人，删一张单据就会让
--     已发生的处置在界面上变成空白。
--
-- 三条口径（与 V54 权属流转 / V55 资产调拨记录一致）：
--   1. 无单号列，界面用 `#id`；无外键约束，归属与状态由应用层断言；
--   2. 台账自洽：来源单据可能被软删 / 改名，故处置信息一律**快照**进本表；
--   3. 金额单位**不做换算**，由 `amount_unit` 显式标注 —— 资产级 `disposal_order.actual_amount`
--      是元，项目 / 分区级 `biz_disposal_record.amount_wan` 是万元（既有口径，见设计 §4.2）。
-- 可重复执行：CREATE ... IF NOT EXISTS + 菜单 ON CONFLICT DO NOTHING。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 台账：一行 = 一个被处置的资产
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS asset_disposal_record (
    id                        BIGSERIAL PRIMARY KEY,
    asset_id                  BIGINT       NOT NULL,
    -- 处置对象层级：asset / project / zone
    target_type               VARCHAR(20)  NOT NULL,
    -- 处置对象 id：asset.id / project.id / project_zone.id
    target_id                 BIGINT       NOT NULL,
    -- 来源单据：asset 级指向 disposal_order.id
    source_order_id           BIGINT,
    -- 来源单据：project / zone 级指向 biz_disposal_record.id
    source_record_id          BIGINT,
    -- 处置时资产的原权属快照。**必须先快照再清空**，否则追溯不到「从哪家公司处置出去」
    from_property_company_id  BIGINT,
    from_operating_company_id BIGINT,
    disposal_type             VARCHAR(50),
    -- 处置金额 + 单位，两者必须成对：同一列混用元与万元而不标注，界面无法正确展示
    disposal_amount           NUMERIC(18,2),
    amount_unit               VARCHAR(10),
    disposal_date             DATE,
    disposal_user_id          BIGINT,
    disposal_user_name        VARCHAR(100),
    remark                    VARCHAR(500),
    disposed_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ,
    created_by                BIGINT,
    updated_by                BIGINT
);

-- 一个资产只会被处置一次：既是幂等键（重复级联不得产生第二批副作用），
-- 也是并发双写的兜底（两个请求同时级联同一资产时，第二个必然违反本索引而整单回滚）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_disposal_record_asset
    ON asset_disposal_record (asset_id);
-- 列表页：按原产权公司筛选 + id 倒序（最近的处置在最前）
CREATE INDEX IF NOT EXISTS idx_asset_disposal_record_company
    ON asset_disposal_record (from_property_company_id, id DESC);
-- 反查「某项目 / 分区 / 资产的处置带出了哪些资产」
CREATE INDEX IF NOT EXISTS idx_asset_disposal_record_target
    ON asset_disposal_record (target_type, target_id);

COMMENT ON COLUMN asset_disposal_record.target_type IS '处置对象层级：asset / project / zone';
COMMENT ON COLUMN asset_disposal_record.target_id IS '处置对象 id：asset.id / project.id / project_zone.id';
COMMENT ON COLUMN asset_disposal_record.source_order_id IS '资产级来源：disposal_order.id';
COMMENT ON COLUMN asset_disposal_record.source_record_id IS '项目 / 分区级来源：biz_disposal_record.id';
COMMENT ON COLUMN asset_disposal_record.from_property_company_id IS '处置时资产的原产权公司（快照，随后被清空）';
COMMENT ON COLUMN asset_disposal_record.from_operating_company_id IS '处置时资产的原经营公司（快照）';
COMMENT ON COLUMN asset_disposal_record.disposal_amount IS '处置金额，单位见 amount_unit（不做换算）';
COMMENT ON COLUMN asset_disposal_record.amount_unit IS '金额单位：yuan 元 / wan 万元';
COMMENT ON COLUMN asset_disposal_record.disposed_at IS '级联处置时间';

-- ---------------------------------------------------------------------------
-- 回填：V57 之前已完成的**资产级**处置单
--
-- 为什么必须回填：这些资产在本迁移之前就被处置了（`disposal_order.status='completed'`，
-- 且 `lease_control_status` 已被旧代码置 `exited`），但旧规则**没有动产权公司**。
-- 不回填的两个后果都不可接受：
--   · 「资产处置记录」菜单对存量数据是空的 —— 功能表现为「没有历史」；
--   · 模型不一致（已处置的资产仍挂在原产权公司名下），而需求恰恰要消除这一点。
--
-- **只回填资产级，不回填项目 / 分区级的 `biz_disposal_record`**：后者在旧语义下是
-- 纯登记台账（很多是「记录一下」，并不等于要把整个项目从产权公司剥离）。按台账行
-- 反向处置整个项目下的资产，会在上线时刻产生一批**未经复核**的批量「脱离产权公司」，
-- 而那是不可逆的。项目 / 分区级的级联自 V57 起只对**新增**的处置登记生效。
--
-- 幂等：INSERT 走 ON CONFLICT (asset_id) DO NOTHING（唯一索引在同一文件上方已建）；
-- UPDATE 带 `lifecycle_status <> 'exited'` 守卫，重跑是空操作。
-- DISTINCT ON 取每个资产**最新**的一张已完成处置单，避免一资产多单时源集重复。
-- ---------------------------------------------------------------------------
INSERT INTO asset_disposal_record (
    asset_id, target_type, target_id, source_order_id,
    from_property_company_id, from_operating_company_id,
    disposal_type, disposal_amount, amount_unit, disposal_date,
    disposal_user_id, disposal_user_name, remark, disposed_at)
SELECT DISTINCT ON (o.asset_id)
       o.asset_id, 'asset', o.asset_id, o.id,
       a.property_company_id, a.operating_company_id,
       o.disposal_type, o.actual_amount, 'yuan', o.disposal_date,
       o.disposal_user_id, o.disposal_user_name, o.remark,
       COALESCE(o.updated_at, now())
  FROM disposal_order o
  JOIN asset a ON a.id = o.asset_id
 WHERE o.status = 'completed'
   AND a.lifecycle_status <> 'exited'
 ORDER BY o.asset_id, o.id DESC
ON CONFLICT (asset_id) DO NOTHING;

-- 回填后同步资产状态：已处置 = 脱离原产权公司（与运行时级联同一口径）
UPDATE asset a
   SET ownership_status   = 'disposed',
       lifecycle_status   = 'exited',
       property_company_id = NULL
  FROM asset_disposal_record r
 WHERE r.asset_id = a.id
   AND r.source_order_id IS NOT NULL
   AND a.lifecycle_status <> 'exited';

-- ---------------------------------------------------------------------------
-- asset.ownership_status 新增取值 `disposed`
--
-- 列本身是 VARCHAR(20)，无 CHECK 约束（与 V54 建列时同口径），这里只更新注释把第三个
-- 取值写清楚 —— 否则「卖掉了」与「转出去了」在库表注释里看不出区别。
-- ---------------------------------------------------------------------------
COMMENT ON COLUMN asset.ownership_status IS 'in_group 集团内 / transferred_out 已对外转出（外部权属流转生效） / disposed 已处置（处置完成，脱离原产权公司）';

-- ---------------------------------------------------------------------------
-- 菜单：挂在「资产运营」目录（operation）下，sort 15 落在「资产处置」(10) 之后
--
-- 按 code = 'operation' 定位父目录，不依赖目录名。
-- icon 留空：与 V45 / V54 / V55 口径一致（图标只给目录，菜单为空时侧栏回退前端 PATH_ICONS）。
-- ---------------------------------------------------------------------------
INSERT INTO menu (code, name, menu_type, path, icon, sort, parent_id)
SELECT 'operation.disposalRecord', '资产处置记录', 'menu', '/disposal-records', NULL, 15, d.id
FROM menu d WHERE d.code = 'operation'
ON CONFLICT (code) DO NOTHING;

-- view 回填：本菜单在 V45 之后新增，V45 §5.1 的回填只覆盖了当时的菜单行。
-- 不回填则除 super_admin 外所有角色都看不到入口 —— 新功能表现为「没做出来」。
-- 与 V45 §5.1 / V47 / V54 / V55 同口径：只回填 view、排除 super_admin、写动作一律不回填。
INSERT INTO role_permission (role_id, menu_id, menu_code, action)
SELECT r.id, m.id, m.code, 'view'
FROM role r
CROSS JOIN menu m
WHERE r.code <> 'super_admin'
  AND m.code = 'operation.disposalRecord'
ON CONFLICT DO NOTHING;
