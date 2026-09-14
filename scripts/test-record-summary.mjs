#!/usr/bin/env node
/**
 * 成本 / 评估汇总口径的运行期验证。
 *
 * 运行：node scripts/test-record-summary.mjs
 *
 * 为什么单独验这几个纯函数：合计、取「最新」、过期判定属于**经营口径**，写错不会报错、
 * 只会安静地给出错数字 —— 例如「一条都没录」被算成 `0 万元`（读起来是「成本为零」）、
 * 「最新评估」取成了最早那条。这类偏差在页面上极难看出来。
 *
 * ⚠️ 需要 Node ≥ 22.6（原生类型擦除；≥ 23.6 已默认开启）。CI 的 frontend-docs job 跑的是
 * Node 20，因此本脚本**不进 CI**：定位与 `scripts/check-record-contracts.mjs` 一致，
 * 是「新版本 Node 上就能跑」的本地守卫，正式门禁仍在前端构建与 `mvn -B verify`。
 */
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

const major = Number(process.versions.node.split('.')[0]);
if (major < 22) {
  console.error(`Node ${process.version} 不支持原生类型擦除，请用 Node ≥ 22.6 运行本脚本`);
  process.exit(1);
}

const {
  countCostItems,
  formatValidRange,
  formatWan,
  isEvaluationExpired,
  latestEvaluation,
  sumCostAmountWan,
} = await import(join(ROOT, 'frontend/admin-web/src/lib/recordSummary.ts'));

let passed = 0;
const check = (name, fn) => {
  fn();
  passed++;
  console.log(`  ✓ ${name}`);
};

console.log('成本 / 评估汇总口径');

check('成本合计：一条都没填金额 → 「—」，不是 0（0 与未录是两件事）', () => {
  assert.equal(sumCostAmountWan([{}, { remark: '只写了备注' }]), null);
  assert.equal(formatWan(sumCostAmountWan([])), '—');
  assert.equal(formatWan(sumCostAmountWan(null)), '—');
});

check('成本合计：跳过未填的行，只累加有值的行', () => {
  assert.equal(formatWan(sumCostAmountWan([{ amountWan: 35.5 }, {}, { amountWan: 4.5 }])), '40.00');
});

check('成本合计：真的 0 仍显示 0.00', () => {
  assert.equal(formatWan(sumCostAmountWan([{ amountWan: 0 }])), '0.00');
});

check('成本合计：小数累加不出现 0.30000000000000004', () => {
  assert.equal(formatWan(sumCostAmountWan([{ amountWan: 0.1 }, { amountWan: 0.2 }])), '0.30');
});

check('费用明细条数：跨多条成本记录累加，items 缺失按 0 计', () => {
  assert.equal(
    countCostItems([
      { items: [{ feeName: 'a' }, { feeName: 'b' }] },
      { items: [] },
      {},
      { items: [{ feeName: 'c' }] },
    ]),
    3,
  );
  assert.equal(countCostItems([]), 0);
});

check('最新评估：按评估时间取最近的一条', () => {
  const latest = latestEvaluation([
    { id: 1, evaluateDate: '2025-06-01', institution: '旧机构' },
    { id: 2, evaluateDate: '2026-06-01', institution: '新机构' },
    { id: 3, evaluateDate: '2024-01-01', institution: '更旧机构' },
  ]);
  assert.equal(latest.institution, '新机构');
});

check('最新评估：评估时间相同 → 比有效期起；仍相同 → 后写的胜', () => {
  assert.equal(
    latestEvaluation([
      { id: 7, evaluateDate: '2026-06-01', validFrom: '2026-06-01' },
      { id: 8, evaluateDate: '2026-06-01', validFrom: '2026-07-01' },
    ]).id,
    8,
  );
  assert.equal(
    latestEvaluation([
      { id: 7, evaluateDate: '2026-06-01' },
      { id: 8, evaluateDate: '2026-06-01' },
    ]).id,
    8,
  );
});

check('最新评估：三个日期都空的行排最后（没填时间的算最旧）', () => {
  assert.equal(latestEvaluation([{ id: 9 }, { id: 10, validFrom: '2020-01-01' }]).id, 10);
});

check('最新评估：只有一条 / 空列表的边界', () => {
  assert.equal(latestEvaluation([{ id: 5 }]).id, 5);
  assert.equal(latestEvaluation([]), null);
  assert.equal(latestEvaluation(null), null);
});

check('有效期限展示：单端缺失不留断头区间', () => {
  assert.equal(formatValidRange('2026-06-01', '2027-05-31'), '2026-06-01 ~ 2027-05-31');
  assert.equal(formatValidRange('2026-06-01', undefined), '2026-06-01 ~ —');
  assert.equal(formatValidRange(undefined, '2027-05-31'), '— ~ 2027-05-31');
  assert.equal(formatValidRange(), '—');
});

check('过期判定：止早于今天算过期；止为空不算过期', () => {
  assert.equal(isEvaluationExpired({ validTo: '2026-09-12' }, '2026-09-13'), true);
  assert.equal(isEvaluationExpired({ validTo: '2026-09-13' }, '2026-09-13'), false);
  assert.equal(isEvaluationExpired({ validTo: '2027-05-31' }, '2026-09-13'), false);
  assert.equal(isEvaluationExpired({}, '2026-09-13'), false);
  assert.equal(isEvaluationExpired(null, '2026-09-13'), false);
});

console.log(`\n${passed} 项通过`);
