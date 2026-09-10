-- 系统字典：模块 → 字典 → 字典项（系统管理 → 系统字典）
-- 支撑「分模块左右布局：左侧模块、右侧字典 Tab、字典项可单独新增」的自定义字典维护。

CREATE TABLE IF NOT EXISTS sys_dict_module (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    sort        INT          NOT NULL DEFAULT 0,
    status      INT          NOT NULL DEFAULT 1,
    remark      VARCHAR(500),
    created_by  BIGINT,
    updated_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_sys_dict_module_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS sys_dict_type (
    id          BIGSERIAL PRIMARY KEY,
    module_id   BIGINT       NOT NULL,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    sort        INT          NOT NULL DEFAULT 0,
    status      INT          NOT NULL DEFAULT 1,
    remark      VARCHAR(500),
    created_by  BIGINT,
    updated_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_sys_dict_type_code UNIQUE (code)
);

CREATE INDEX IF NOT EXISTS idx_sys_dict_type_module ON sys_dict_type (module_id);

CREATE TABLE IF NOT EXISTS sys_dict_item (
    id          BIGSERIAL PRIMARY KEY,
    type_id     BIGINT       NOT NULL,
    value       VARCHAR(64)  NOT NULL,
    label       VARCHAR(200) NOT NULL,
    sort        INT          NOT NULL DEFAULT 0,
    status      INT          NOT NULL DEFAULT 1,
    remark      VARCHAR(500),
    created_by  BIGINT,
    updated_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_sys_dict_item_type_value UNIQUE (type_id, value)
);

CREATE INDEX IF NOT EXISTS idx_sys_dict_item_type ON sys_dict_item (type_id);

-- ============================================================================
-- 种子数据：资产管理字典
-- ============================================================================
INSERT INTO sys_dict_module (code, name, sort) VALUES ('asset_management', '资产管理字典', 1)
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_dict_type (module_id, code, name, sort)
SELECT m.id, v.code, v.name, v.sort
FROM sys_dict_module m
JOIN (VALUES
    ('project_property',    '项目属性',     1),
    ('asset_type',          '资产类型',     2),
    ('partial_lease_status','部分租赁状态', 3),
    ('asset_nature',        '资产性质',     4),
    ('asset_source',        '资产来源',     5),
    ('asset_ownership',     '资产权属',     6),
    ('building_structure',  '建筑结构',     7),
    ('responsible_department', '责任部门',  8)
) AS v(code, name, sort) ON TRUE
WHERE m.code = 'asset_management'
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_dict_item (type_id, value, label, sort)
SELECT t.id, v.value, v.label, v.sort
FROM sys_dict_type t
JOIN (VALUES
    -- 1. 项目属性
    ('project_property', 'house', '房产类', 1),
    ('project_property', 'land', '土地类', 2),
    -- 2. 资产类型
    ('asset_type', 'low_rent_housing', '廉租房', 1),
    ('asset_type', 'public_rental_housing', '公租房', 2),
    ('asset_type', 'affordable_housing', '经济适用房', 3),
    ('asset_type', 'commercial_housing', '商品房', 4),
    ('asset_type', 'housing_reform', '房改房', 5),
    ('asset_type', 'resettlement_housing', '安置房', 6),
    ('asset_type', 'self_use_asset', '自用资产', 7),
    ('asset_type', 'factory_building', '厂房', 8),
    ('asset_type', 'gymnasium', '体育馆', 9),
    ('asset_type', 'residential', '住宅', 10),
    -- 3. 部分租赁状态
    ('partial_lease_status', 'support', '支持', 1),
    ('partial_lease_status', 'not_support', '不支持', 2),
    -- 4. 资产性质
    ('asset_nature', 'operational', '经营性', 1),
    ('asset_nature', 'public_welfare', '公益性', 2),
    ('asset_nature', 'financial', '金融性', 3),
    ('asset_nature', 'resource', '资源性', 4),
    ('asset_nature', 'self_owned', '自有', 5),
    -- 5. 资产来源
    ('asset_source', 'investment_construction', '投资建设', 1),
    ('asset_source', 'acquisition_reserve', '收储', 2),
    ('asset_source', 'transferred', '移交资产', 3),
    ('asset_source', 'allocated_in', '划入', 4),
    ('asset_source', 'leased_in', '租入', 5),
    ('asset_source', 'self_funded_construction', '自筹建设', 6),
    ('asset_source', 'entrusted', '托管', 7),
    ('asset_source', 'other', '其他', 8),
    -- 6. 资产权属
    ('asset_ownership', 'joint_operation', '联营资产', 1),
    ('asset_ownership', 'entrusted_operation', '委托经营资产', 2),
    ('asset_ownership', 'self_owned', '自有资产', 3),
    ('asset_ownership', 'custodial', '代管资产', 4),
    ('asset_ownership', 'transferred', '移交资产', 5),
    ('asset_ownership', 'other', '其他', 6),
    -- 7. 建筑结构
    ('building_structure', 'brick_wood', '砖木结构', 1),
    ('building_structure', 'steel_concrete', '钢混结构', 2),
    ('building_structure', 'shear_wall', '剪力墙结构', 3),
    ('building_structure', 'brick_concrete', '砖混结构', 4),
    ('building_structure', 'frame', '框架结构', 5),
    ('building_structure', 'circular_single_suspension', '圆形单层悬索结构', 6),
    ('building_structure', 'gas', '气体结构', 7),
    ('building_structure', 'tube', '简体结构', 8),
    ('building_structure', 'mixed', '混合结构', 9),
    ('building_structure', 'arch', '拱结构', 10),
    ('building_structure', 'circular_double_suspension', '圆形双层寻索结构', 11),
    ('building_structure', 'space_frame', '网架结构', 12),
    ('building_structure', 'mo_structure', '摸结构', 13),
    ('building_structure', 'space_thin_wall', '空间暴毙结构', 14),
    ('building_structure', 'flat_slab', '无梁楼盖结构', 15),
    ('building_structure', 'shell', '壳体结构', 16),
    ('building_structure', 'orthogonal_cable_net', '双向正交索网结构', 17),
    ('building_structure', 'truss', '衍架结构', 18),
    ('building_structure', 'longitudinal_wall_bearing', '纵墙承重', 19),
    -- 8. 责任部门
    ('responsible_department', 'asset_department', '资产部门', 1)
) AS v(type_code, value, label, sort) ON v.type_code = t.code
ON CONFLICT (type_id, value) DO NOTHING;
