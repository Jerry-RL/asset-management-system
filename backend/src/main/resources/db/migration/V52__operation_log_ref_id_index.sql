-- ============================================================================
-- 操作日志按「被操作对象」查询的索引。
--
-- 背景：ref_id 是 V2 建表时就有的列，但切面从未写入过，一直恒为 NULL，因此也从未有查询用到它。
-- 现在由 AuditRefIdResolver 推断写入（URL 里最深的 id 型路径变量，或新建时返回实体的 id），
-- 约 90% 的审计行会带值 —— 剩下约 10% 是导入 / 合并 / 清理这类批量操作，它们没有
-- 「那个对象」可言，切面刻意留 NULL。
--
-- 支持的查询形状（AuditLogService.query 在 refId 非空时生成）：
--     WHERE ref_id = ? [AND created_at >= ? AND created_at <= ?] ...
--     ORDER BY created_at DESC, id DESC
--     LIMIT ? OFFSET ?
--
-- 复合顺序「等值列 → 排序列」是有意的：只建 ref_id 单列索引的话，
-- 命中后仍要按 created_at 重排（翻页越深开销越大）；把排序列一起放进索引才能直接顺着取。
-- id 也进索引，因为排序用的是 created_at DESC, id DESC（同秒多条靠 id 兜底，否则翻页会漏行/重行）。
--
-- 本迁移是纯 expand：只加一条索引，不碰任何既有列、不插菜单、不回填权限。
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_operation_log_ref_created
    ON operation_log (ref_id, created_at DESC, id DESC);

COMMENT ON INDEX idx_operation_log_ref_created IS
    '审计页「按被操作对象查询」用；NULL 的 ref_id（批量操作）不进此索引，而它们本来也不会按 ref_id 查';
