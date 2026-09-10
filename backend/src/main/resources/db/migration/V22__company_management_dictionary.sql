-- 公司管理字典：公司类型 / 部门类型（系统管理 → 系统字典 → 公司管理字典）
-- 可重复执行：模块/字典按 code 幂等，字典项按 (type_id, value) 幂等。

INSERT INTO sys_dict_module (code, name, sort) VALUES ('company_management', '公司管理字典', 2)
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_dict_type (module_id, code, name, sort)
SELECT m.id, v.code, v.name, v.sort
FROM sys_dict_module m
JOIN (VALUES
    ('company_type',    '公司类型', 1),
    ('department_type', '部门类型', 2)
) AS v(code, name, sort) ON TRUE
WHERE m.code = 'company_management'
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_dict_item (type_id, value, label, sort)
SELECT t.id, v.value, v.label, v.sort
FROM sys_dict_type t
JOIN (VALUES
    ('company_type', 'provincial_sasac',   '省国资委', 1),
    ('company_type', 'public_institution', '事业单位', 2),
    ('company_type', 'state_owned',        '国企',     3),
    ('company_type', 'private_enterprise', '私企',     4),
    ('department_type', 'asset_department', '资产部门', 1)
) AS v(type_code, value, label, sort) ON v.type_code = t.code
ON CONFLICT (type_id, value) DO NOTHING;
