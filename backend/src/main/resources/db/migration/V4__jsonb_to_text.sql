-- ============================================================================
-- JSONB → TEXT 对齐：MyBatis-Plus 实体中对应字段均为 String，
-- 存 JSON 字符串，改用 TEXT 避免 insert 时 varchar/jsonb 类型不匹配。
-- ============================================================================

ALTER TABLE contract_version    ALTER COLUMN before_json TYPE TEXT USING before_json::text;
ALTER TABLE contract_version    ALTER COLUMN after_json TYPE TEXT USING after_json::text;
ALTER TABLE alert_rule          ALTER COLUMN condition_json TYPE TEXT USING condition_json::text;
ALTER TABLE operation_log       ALTER COLUMN detail_json TYPE TEXT USING detail_json::text;
ALTER TABLE callback_log        ALTER COLUMN request_json TYPE TEXT USING request_json::text;
ALTER TABLE export_audit        ALTER COLUMN scope_json TYPE TEXT USING scope_json::text;
ALTER TABLE regulation_report   ALTER COLUMN content_json TYPE TEXT USING content_json::text;
ALTER TABLE finance_voucher     ALTER COLUMN content_json TYPE TEXT USING content_json::text;
ALTER TABLE approval_flow_def   ALTER COLUMN definition TYPE TEXT USING definition::text;
ALTER TABLE agent_tool_call     ALTER COLUMN request_json TYPE TEXT USING request_json::text;
ALTER TABLE agent_tool_call     ALTER COLUMN response_summary TYPE TEXT USING response_summary::text;
ALTER TABLE agent_prompt_template ALTER COLUMN definition TYPE TEXT USING definition::text;
