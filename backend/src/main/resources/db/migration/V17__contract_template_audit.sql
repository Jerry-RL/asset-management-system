-- 合同模板表补齐 BaseEntity 审计列 updated_by
ALTER TABLE contract_template ADD COLUMN IF NOT EXISTS updated_by BIGINT;
