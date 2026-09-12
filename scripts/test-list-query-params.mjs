#!/usr/bin/env node
/**
 * 列表 URL 参数纯逻辑的行为断言（设计 §5.1 / §5.2、§8）。
 * 运行：pnpm test:list-query（在 frontend/ 下）或 node scripts/test-list-query-params.mjs
 *
 * 为什么是「Node 原生断言」而不是 vitest：本仓前端没有测试框架，而这段逻辑
 * （正整数兜底 / 缺省值不写 / 合并式写入 / 筛选整体替换）正是最容易写歪、也最容易
 * 回归的部分。抽成零依赖纯模块后，用 Node 的类型剥离直接跑断言，不引入任何依赖。
 *
 * 注意：类型剥离需要 Node ≥ 22.6（本机 24）；CI 跑 Node 20，故本脚本**不进 CI**。
 */
import { strict as assert } from 'node:assert';
import {
  applyListPatch,
  findReservedFilterKeys,
  readListQuery,
  readPositiveInt,
} from '../frontend/admin-web/src/lib/listQueryParams.ts';

let passed = 0;
const check = (name, fn) => {
  fn();
  passed += 1;
};

/* ---------- readPositiveInt：非法页码一律回落，绝不产生非法请求 ---------- */
check('正整数原样返回', () => assert.equal(readPositiveInt('3', 1), 3));
check('小数回落（否则会请求 page=1.5 → 后端 400）', () =>
  assert.equal(readPositiveInt('1.5', 1), 1));
check('科学计数法回落', () => assert.equal(readPositiveInt('1e3', 1), 1));
check('十六进制回落', () => assert.equal(readPositiveInt('0x10', 1), 1));
check('0 回落', () => assert.equal(readPositiveInt('0', 1), 1));
check('负数回落', () => assert.equal(readPositiveInt('-1', 1), 1));
check('非数字回落', () => assert.equal(readPositiveInt('abc', 1), 1));
check('空串回落', () => assert.equal(readPositiveInt('', 1), 1));
check('缺失回落', () => assert.equal(readPositiveInt(null, 10), 10));
check('大数保留', () => assert.equal(readPositiveInt('999', 10), 999));

/* ---------- readListQuery：默认值 / 筛选 / 前缀 ---------- */
check('空 URL 得到默认状态', () =>
  assert.deepEqual(readListQuery(new URLSearchParams('')), {
    page: 1,
    pageSize: 10,
    keyword: '',
    filters: {},
  }));

check('完整 URL 被正确读出', () =>
  assert.deepEqual(
    readListQuery(new URLSearchParams('page=3&pageSize=20&keyword=%E7%94%B2&status=leased'), {
      filterKeys: ['status'],
    }),
    { page: 3, pageSize: 20, keyword: '甲', filters: { status: 'leased' } },
  ));

check('空筛选值视为未筛选', () =>
  assert.deepEqual(readListQuery(new URLSearchParams('status='), { filterKeys: ['status'] }).filters, {}));

check('前缀隔离：assetPage 只被 prefix=asset 读到', () => {
  const params = new URLSearchParams('assetPage=2&assetKeyword=x&page=9');
  assert.deepEqual(readListQuery(params, { prefix: 'asset' }), {
    page: 2,
    pageSize: 10,
    keyword: 'x',
    filters: {},
  });
  assert.equal(readListQuery(params, { prefix: '' }).page, 9);
});

check('未声明的参数不被解释（深链参数不得被当筛选发送）', () =>
  assert.deepEqual(readListQuery(new URLSearchParams('other=x'), { filterKeys: ['status'] }).filters, {}));

/* ---------- applyListPatch：合并 / 缺省值不写 / 不修改入参 ---------- */
check('翻页只改 page，其余参数原样保留', () => {
  const input = new URLSearchParams('status=leased&keyword=%E7%94%B2&projectId=7');
  const out = applyListPatch(input, { page: 3 }, { filterKeys: ['status'] });
  assert.equal(out.get('page'), '3');
  assert.equal(out.get('status'), 'leased');
  assert.equal(out.get('keyword'), '甲');
  assert.equal(out.get('projectId'), '7');
});

check('入参不被修改（纯函数）', () => {
  const input = new URLSearchParams('page=3');
  applyListPatch(input, { page: 5 });
  assert.equal(input.get('page'), '3');
});

check('page=1 不写进 URL', () =>
  assert.equal(applyListPatch(new URLSearchParams('page=3'), { page: 1 }).get('page'), null));

check('pageSize 等于缺省值时不写', () => {
  assert.equal(
    applyListPatch(new URLSearchParams(''), { pageSize: 10 }, { defaultPageSize: 10 }).get('pageSize'),
    null,
  );
  assert.equal(
    applyListPatch(new URLSearchParams(''), { pageSize: 50 }, { defaultPageSize: 10 }).get('pageSize'),
    '50',
  );
});

check('空关键字从 URL 删除', () =>
  assert.equal(applyListPatch(new URLSearchParams('keyword=x'), { keyword: '' }).get('keyword'), null));

check('筛选视为完整目标状态：未出现即清空', () => {
  const out = applyListPatch(
    new URLSearchParams('status=leased&contractId=12'),
    { filters: { status: 'vacant' } },
    { filterKeys: ['status', 'contractId'] },
  );
  assert.equal(out.get('status'), 'vacant');
  assert.equal(out.get('contractId'), null);
});

check('未声明的筛选键不被 patch 触及', () => {
  const out = applyListPatch(
    new URLSearchParams('other=1'),
    { filters: { status: 'leased' } },
    { filterKeys: ['status'] },
  );
  assert.equal(out.get('other'), '1');
});

check('resetPage 删掉 page（配合筛选/查询归 1）', () =>
  assert.equal(
    applyListPatch(new URLSearchParams('page=5'), {}, { resetPage: true }).get('page'),
    null,
  ));

check('显式 page 优先于 resetPage', () =>
  assert.equal(
    applyListPatch(new URLSearchParams(''), { page: 4 }, { resetPage: true }).get('page'),
    '4',
  ));

check('前缀写入互不干扰', () => {
  const out = applyListPatch(
    new URLSearchParams('page=2&assetPage=4'),
    { page: 3 },
    { prefix: 'asset' },
  );
  assert.equal(out.get('assetPage'), '3');
  assert.equal(out.get('page'), '2');
});

/* ---------- findReservedFilterKeys：筛选键撞保留字要能被发现 ---------- */
check('未撞保留字返回空', () => assert.deepEqual(findReservedFilterKeys(['status', 'city']), []));
check('撞保留字被列出', () =>
  assert.deepEqual(findReservedFilterKeys(['keyword', 'status', 'page']), ['keyword', 'page']));

console.log(`listQueryParams: ${passed} 项断言全部通过`);
