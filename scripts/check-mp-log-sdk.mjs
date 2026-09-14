#!/usr/bin/env node
/**
 * 端侧日志 SDK 的接线与产物一致性守卫（设计 §11.4 / §17 R3）。
 *
 * 运行：pnpm check:mp-sdk
 *      （= 先 `build:mp` 产出 `packages/log-sdk/dist/log-sdk.mp.js`，再跑本脚本比对）
 *
 * 为什么必须有这道守卫：小程序构建器**不解析 monorepo 路径**，因此两端的
 * `utils/log-sdk.js` 是产物的一份拷贝，且 `dist/` 被 .gitignore 忽略 ——
 * 也就是说**仓库里唯一被提交的 SDK 代码是那两份拷贝**。改了 `packages/log-sdk/src`
 * 却忘了重新拷贝，CI 不会有任何报错，线上会一直跑着旧 SDK：
 *   - 症状是「新加的采集项没数据」，而不是报错 —— 最难自查的一类缺陷；
 *   - 两份拷贝还会各自漂移，导致两端采集能力不一致。
 *
 * 因此这里做两件事，**都硬失败**：
 *   A. 两份拷贝与刚构建出的产物**逐字节一致**（不做「看起来差不多」的判断）；
 *   B. 五端的接线没被改掉（漏了 `X-Trace-Id` 或 `onError` 就静默失去采集能力，
 *      同样不会在编译期报错）。
 *
 * 解析策略：正则 + 数量下限自检 —— 正则一旦失配会得到空集合，而空集合让断言恒真。
 */
import { readFileSync, existsSync, copyFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const FE = join(ROOT, 'frontend');

/**
 * `--fix`：把产物同步到两份拷贝，然后继续做一致性校验。
 *
 * <p>刻意让「同步」与「校验」走同一个脚本：分开写两个脚本，迟早出现
 * 「在 A 脚本里改了产物处理方式，B 脚本没跟上」的漂移，而这两个脚本存在的全部意义
 * 就是消除漂移。同步完**仍然**走一遍比对，等于顺手验证了拷贝动作本身没写错。
 */
const FIX = process.argv.includes('--fix');

const failures = [];
const fail = (m) => failures.push(m);
const read = (p) => readFileSync(p, 'utf8');
const rel = (p) => relative(ROOT, p);

/** 产物路径（由 `build:mp` 生成；dist/ 被 gitignore，故不可能是「仓库里本来就有的」） */
const ARTIFACT = join(FE, 'packages/log-sdk/dist/log-sdk.mp.js');

/** 两个小程序各自需要与产物一致的拷贝 */
const MP_COPIES = [
  join(FE, 'miniprogram-tenant/utils/log-sdk.js'),
  join(FE, 'miniprogram-worker/utils/log-sdk.js'),
];

/** A. 产物一致性 */
if (!existsSync(ARTIFACT)) {
  fail(
    `未找到构建产物 ${rel(ARTIFACT)} —— 请先执行 \`pnpm --filter @ams/log-sdk build:mp\`\n` +
      '         （用 `pnpm check:mp-sdk` 会把构建与比对这个顺序一次做完）',
  );
} else {
  if (FIX) {
    for (const copy of MP_COPIES) {
      copyFileSync(ARTIFACT, copy);
      console.log(`  已同步 ${rel(copy)}`);
    }
  }
  const expected = createHash('sha256').update(readFileSync(ARTIFACT)).digest('hex');
  for (const copy of MP_COPIES) {
    if (!existsSync(copy)) {
      fail(`${rel(copy)} 不存在 —— 小程序端缺少 SDK 产物拷贝`);
      continue;
    }
    const actual = createHash('sha256').update(readFileSync(copy)).digest('hex');
    if (actual !== expected) {
      fail(
        `${rel(copy)} 与产物不一致（拷的是旧版本）\n` +
          `         期望 sha256 ${expected}\n` +
          `         实际 sha256 ${actual}\n` +
          '         → 执行 `pnpm sync:mp-sdk`（= 重新构建产物并覆盖两份拷贝）',
      );
    }
  }
}

// ---------------------------------------------------------------------------
// B. 五端接线
// ---------------------------------------------------------------------------

/** 必须存在且必须包含全部关键字的断言表：`[相对路径, 说明, 关键字[]]` */
const WIRING = [
  // ---- web 三端（走 Vite，直接消费 workspace 源码） ----
  ['frontend/admin-web/src/lib/log.ts', 'admin-web 日志单例', ["appType: 'admin-web'"]],
  ['frontend/admin-web/src/lib/api.ts', 'admin-web API 层', ['X-Trace-Id', 'captureApiFailure']],
  ['frontend/admin-web/src/main.tsx', 'admin-web 入口', ['logger.install()']],
  ['frontend/h5-tenant/src/lib/log.ts', '租户 H5 日志单例', ["appType: 'h5-tenant'"]],
  ['frontend/h5-tenant/src/lib/api.ts', '租户 H5 API 层', ['X-Trace-Id', 'captureApiFailure']],
  ['frontend/h5-tenant/src/main.tsx', '租户 H5 入口', ['logger.install()']],
  ['frontend/h5-worker/src/lib/log.ts', '员工 H5 日志单例', ["appType: 'h5-worker'"]],
  ['frontend/h5-worker/src/lib/api.ts', '员工 H5 API 层', ['X-Trace-Id', 'captureApiFailure']],
  ['frontend/h5-worker/src/main.tsx', '员工 H5 入口', ['logger.install()']],
  // ---- 小程序两端 ----
  ['frontend/miniprogram-tenant/utils/logger.js', '租户小程序日志单例', ["appType: 'tenant-mp'"]],
  [
    'frontend/miniprogram-tenant/utils/api.js',
    '租户小程序 API 层',
    ['X-Trace-Id', 'captureApiFailure'],
  ],
  [
    'frontend/miniprogram-tenant/app.js',
    '租户小程序入口',
    ['onError', 'onUnhandledRejection'],
  ],
  ['frontend/miniprogram-worker/utils/logger.js', '员工小程序日志单例', ["appType: 'worker-mp'"]],
  [
    'frontend/miniprogram-worker/utils/api.js',
    '员工小程序 API 层',
    ['X-Trace-Id', 'captureApiFailure'],
  ],
  [
    'frontend/miniprogram-worker/app.js',
    '员工小程序入口',
    ['onError', 'onUnhandledRejection'],
  ],
];

for (const [path, label, needles] of WIRING) {
  const full = join(ROOT, path);
  if (!existsSync(full)) {
    fail(`${label}：${path} 不存在（五端接线已缺失一端）`);
    continue;
  }
  const src = read(full);
  const missing = needles.filter((needle) => !src.includes(needle));
  if (missing.length) {
    fail(`${label}：${path} 缺少 ${missing.join(' / ')}`);
  }
}

// 数量下限自检：任何一端掉出这张表都说明「有人在加第十六处接线时漏改了本脚本」，
// 或者这段解析被改写坏了 —— 两种都要报错而不是静默放过。
const MIN_WIRING = 15;
if (WIRING.length < MIN_WIRING) {
  fail(`接线断言只有 ${WIRING.length} 条（预期 ≥ ${MIN_WIRING}）—— 守卫表被删改，检查失效`);
}

// ---------------------------------------------------------------------------
// 输出
// ---------------------------------------------------------------------------
console.log('端侧日志 SDK 一致性检查');
console.log(`  产物              : ${existsSync(ARTIFACT) ? rel(ARTIFACT) : '缺失'}`);
console.log(`  小程序拷贝        : ${MP_COPIES.length} 份`);
console.log(`  接线断言          : ${WIRING.length} 条`);

if (failures.length) {
  console.log('\n失败');
  for (const m of failures) console.log(`  - ${m}`);
  console.log(`\n检查未通过：${failures.length} 项失败\n`);
  process.exit(1);
}
console.log('\n检查通过\n');
