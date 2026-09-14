import type { CostRecord, EvaluationInfo } from '@/lib/recordSheet';

// ============================================================================
// 成本 / 评估汇总的**纯函数**（无 React / 无 antd 依赖）。
//
// 与组件分开的理由有两个：
//   1. 汇总口径（合计、取「最新」）是业务规则，不该埋在 JSX 里 —— 资产 / 项目页将来
//      复用同一份口径时直接引这里，不会各自算一遍、各自算出不同结果；
//   2. 这里能用 `node lib/recordSummary.ts` 直接跑断言（Node 原生类型擦除），
//      不必为了验一个求和而起前端构建。
//
// `import type` 是刻意的：类型在运行期被完全擦除，因此本文件不依赖 `@/` 别名解析。
// ============================================================================

/** 万元金额展示：缺失一律「—」，绝不把「没填」渲染成 0（0 与未录是两件事）。 */
export const formatWan = (value?: number | null): string =>
  value == null
    ? '—'
    : value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** 有效期限：起止任一缺失时用「—」顶替，避免出现 `~ 2027-05-31` 这种断头区间。 */
export const formatValidRange = (from?: string, to?: string): string =>
  !from && !to ? '—' : `${from || '—'} ~ ${to || '—'}`;

/**
 * 成本金额合计（万元）。
 *
 * <p>**一条都没填金额时返回 null 而不是 0**：`0 万元` 会被读成「成本为零」，
 * 而实际含义是「还没录」，两者在经营口径上不能混。
 */
export const sumCostAmountWan = (records?: CostRecord[] | null): number | null => {
  const amounts = (records ?? [])
    .map((record) => record.amountWan)
    .filter((amount): amount is number => amount != null);
  if (amounts.length === 0) return null;
  return amounts.reduce((total, amount) => total + amount, 0);
};

/** 费用明细总条数（跨多条成本记录累加）。 */
export const countCostItems = (records?: CostRecord[] | null): number =>
  (records ?? []).reduce((total, record) => total + (record.items?.length ?? 0), 0);

/**
 * 最新一次评估。
 *
 * <p>排序口径：评估时间 → 有效期起 → 有效期止 → id（后写的胜）。ISO 日期串按字典序
 * 比较即时间序，因此不做日期解析；三个日期全空的行排在最后（`''` 最小），
 * 符合「没填时间的算最旧」。
 */
export const latestEvaluation = (evaluations?: EvaluationInfo[] | null): EvaluationInfo | null => {
  const rows = evaluations ?? [];
  if (rows.length === 0) return null;
  const sortKey = (row: EvaluationInfo) => row.evaluateDate ?? row.validFrom ?? row.validTo ?? '';
  return [...rows].sort((a, b) => {
    const byDate = sortKey(b).localeCompare(sortKey(a));
    if (byDate !== 0) return byDate;
    return (b.id ?? 0) - (a.id ?? 0);
  })[0];
};

/** 评估是否已过有效期（有效期止早于今天）。有效期止为空的评估**不算过期**。 */
export const isEvaluationExpired = (
  evaluation?: EvaluationInfo | null,
  today?: string,
): boolean => {
  const validTo = evaluation?.validTo;
  if (!validTo) return false;
  const now = today ?? new Date().toISOString().slice(0, 10);
  return validTo < now;
};
