-- 字典关联：编辑某一字典时，可挂接「另一字典下的某几个字典值」，用于关联查询。
-- 语义为描述性关联（不参与提交校验）；按字典 ID 关联，字典编码/字典值改名无需同步。
DROP TABLE IF EXISTS sys_dict_cascade;

CREATE TABLE IF NOT EXISTS sys_dict_relation (
    id             BIGSERIAL PRIMARY KEY,
    source_type_id BIGINT    NOT NULL,
    target_type_id BIGINT    NOT NULL,
    target_item_id BIGINT    NOT NULL,
    sort           INT       NOT NULL DEFAULT 0,
    status         INT       NOT NULL DEFAULT 1,
    remark         VARCHAR(500),
    created_by     BIGINT,
    updated_by     BIGINT,
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_sys_dict_relation UNIQUE (source_type_id, target_item_id)
);

CREATE INDEX IF NOT EXISTS idx_sys_dict_relation_source
    ON sys_dict_relation (source_type_id, target_type_id);
CREATE INDEX IF NOT EXISTS idx_sys_dict_relation_target_item
    ON sys_dict_relation (target_item_id);
