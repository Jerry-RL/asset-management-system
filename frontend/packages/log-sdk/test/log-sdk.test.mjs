/**
 * `@ams/log-sdk` 纯逻辑断言（设计 §16.2）。
 *
 * 直接加载 **构建产物** `dist/log-sdk.mp.js` 而不是 source：
 * 产物是真正进小程序的那份代码，测它才等于测了线上；而且 `pnpm test` 会先跑一次
 * `build:mp`，所以「改了 src 但忘了重新构建」会立刻暴露。
 *
 * 不进 CI —— 沿用 progress.md 对「纯逻辑脚本不进 CI」的既有裁决。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const sdk = require('../dist/log-sdk.mp.js');

/** 造一个假的小程序宿主：捕获 wx.request 的调用参数。 */
function withMiniProgramHost() {
  const calls = [];
  globalThis.wx = {
    request(options) {
      calls.push(options);
    },
  };
  return {
    calls,
    cleanup() {
      delete globalThis.wx;
    },
  };
}

const makeLogger = (options = {}) =>
  sdk.createLogger({
    endpoint: 'https://api.example.com/api/v1/public/app-logs',
    appType: 'tenant-mp',
    ...options,
  });

// ---------------------------------------------------------------------------
// newTraceId
// ---------------------------------------------------------------------------

test('newTraceId 是 32 位 hex（与后端 UUID.replace 同格式）', () => {
  const logger = makeLogger();
  for (let i = 0; i < 50; i += 1) {
    assert.match(logger.newTraceId(), /^[0-9a-f]{32}$/);
  }
});

test('newTraceId 每次不同（否则链路会串成一条）', () => {
  const logger = makeLogger();
  const seen = new Set();
  for (let i = 0; i < 200; i += 1) seen.add(logger.newTraceId());
  assert.equal(seen.size, 200);
});

// ---------------------------------------------------------------------------
// 上报体字段
// ---------------------------------------------------------------------------

test('captureError 上报 source=js，并带上 message 与 stack', () => {
  const host = withMiniProgramHost();
  try {
    makeLogger().captureError(new TypeError('x is not a function'));

    assert.equal(host.calls.length, 1);
    const body = host.calls[0].data;
    assert.equal(body.source, 'js');
    assert.equal(body.level, 'ERROR');
    assert.equal(body.appType, 'tenant-mp');
    assert.match(body.message, /TypeError: x is not a function/);
    assert.ok(body.extra.stack, '堆栈必须上报：服务端要按「栈前 200 字符」算指纹');
    assert.ok(body.occurredAt, 'occurredAt 必须存在');
    assert.equal(host.calls[0].method, 'POST');
  } finally {
    host.cleanup();
  }
});

test('captureRejection 上报 source=promise', () => {
  const host = withMiniProgramHost();
  try {
    makeLogger().captureRejection(new Error('boom'));

    assert.equal(host.calls.length, 1);
    assert.equal(host.calls[0].data.source, 'promise');
  } finally {
    host.cleanup();
  }
});

test('非 Error 的抛出物也能得到可读 message（不出现 [object Object]）', () => {
  const host = withMiniProgramHost();
  try {
    makeLogger().captureError({ code: 42 });

    assert.match(host.calls[0].data.message, /"code":42/);
  } finally {
    host.cleanup();
  }
});

// ---------------------------------------------------------------------------
// API 失败的 traceId —— 全链路的关键
// ---------------------------------------------------------------------------

test('captureApiFailure 用请求自己的 traceId 落库（这是全链路能串起来的前提）', () => {
  const host = withMiniProgramHost();
  try {
    makeLogger().captureApiFailure({
      traceId: '3f2a1c9b8e7d6f5a4b3c2d1e0f9a8b7c',
      method: 'GET',
      path: '/api/v1/bills',
      status: 500,
      message: '服务器内部错误',
    });

    const body = host.calls[0].data;
    assert.equal(body.source, 'api');
    assert.equal(
      body.traceId,
      '3f2a1c9b8e7d6f5a4b3c2d1e0f9a8b7c',
      '若这里换成了新生成的 traceId，端侧行与后端行会落在不同链路下，查询页永远只看到半条链路',
    );
    assert.match(body.message, /GET \/api\/v1\/bills 失败：500/);
  } finally {
    host.cleanup();
  }
});

test('captureApiFailure 缺 traceId 时退化为新生成（不丢弃这条日志）', () => {
  const host = withMiniProgramHost();
  try {
    makeLogger().captureApiFailure({
      method: 'POST',
      path: '/api/v1/x',
      error: new Error('network'),
    });

    assert.match(host.calls[0].data.traceId, /^[0-9a-f]{32}$/);
  } finally {
    host.cleanup();
  }
});

// ---------------------------------------------------------------------------
// 去重
//
// 注意：这几条刻意用字符串而不是 `new Error()` 作为抛出物。指纹含「栈前 200 字符」，
// 而在测试源码里三行各写一次 `new Error('x')` 会让调用点行号不同 → 栈不同 → 指纹不同
// → 去重不生效。用字符串时栈为空，指纹只由 source|appType|message 决定，断言才是确定的。
// 「同 message 不同栈不去重」是刻意的行为，见最后一条用例。
// ---------------------------------------------------------------------------

test('同指纹在窗口内只上报第一条，并累加 suppressed', () => {
  const host = withMiniProgramHost();
  let clock = 1_000_000;
  try {
    const logger = makeLogger({ now: () => clock, dedupeWindowMs: 5000 });

    logger.captureError('render loop');
    logger.captureError('render loop');
    logger.captureError('render loop');

    assert.equal(host.calls.length, 1, '窗口内只应发第一条');
    assert.equal(host.calls[0].data.extra?.suppressed, undefined, '第一条不带 suppressed');
  } finally {
    host.cleanup();
  }
});

test('窗口过后再次上报：把抑制数带出并清零', () => {
  const host = withMiniProgramHost();
  let clock = 1_000_000;
  try {
    const logger = makeLogger({ now: () => clock, dedupeWindowMs: 5000 });

    logger.captureError('render loop');
    for (let i = 0; i < 30; i += 1) logger.captureError('render loop');
    assert.equal(host.calls.length, 1);

    clock += 5001;
    logger.captureError('render loop');

    assert.equal(host.calls.length, 2);
    assert.equal(host.calls[1].data.extra.suppressed, 30, '抑制数必须在下次带出');
    assert.equal(host.calls[0].data.extra?.suppressed, undefined);
  } finally {
    host.cleanup();
  }
});

test('窗口内不同错误各自独立（新缺陷不会被旧缺陷的窗口压掉）', () => {
  const host = withMiniProgramHost();
  try {
    const logger = makeLogger({ now: () => 1_000_000 });

    logger.captureError('error A');
    logger.captureError('error B');

    assert.equal(host.calls.length, 2);
  } finally {
    host.cleanup();
  }
});

test('同 message 但栈不同 => 不去重（否则两个不同缺陷会被并成一个）', () => {
  const host = withMiniProgramHost();
  try {
    const logger = makeLogger({ now: () => 1_000_000 });
    const sameMessage = 'TypeError: x is not a function';

    const stackA = `Error: ${sameMessage}\n    at renderA (a.js:1:1)`;
    const stackB = `Error: ${sameMessage}\n    at renderB (b.js:9:9)`;
    const withStack = (stack) => Object.assign(new Error(sameMessage), { stack });

    logger.captureError(withStack(stackA));
    logger.captureError(withStack(stackB));

    assert.equal(host.calls.length, 2, '栈不同 = 不同缺陷，必须分别上报');
  } finally {
    host.cleanup();
  }
});

test('去重表有上限：大量不同错误不会无限占用内存', () => {
  const host = withMiniProgramHost();
  try {
    const logger = makeLogger({ now: () => 1_000_000 });
    for (let i = 0; i < 1000; i += 1) {
      logger.captureError(new Error(`error-${i}`));
    }
    // 每条都不同 => 全部上报；关键是这个过程不崩、不卡（无上限的 Map 会持续增长）
    assert.equal(host.calls.length, 1000);
  } finally {
    host.cleanup();
  }
});

// ---------------------------------------------------------------------------
// 截断
// ---------------------------------------------------------------------------

test('超长 message 被截断到 2000', () => {
  const host = withMiniProgramHost();
  try {
    makeLogger().captureError(new Error('x'.repeat(5000)));

    assert.equal(host.calls[0].data.message.length, 2000);
  } finally {
    host.cleanup();
  }
});

test('超大 extra 整块丢弃并标记 truncated（保留半个 JSON 只会误导）', () => {
  const host = withMiniProgramHost();
  try {
    makeLogger().captureError(new Error('boom'), { big: 'x'.repeat(20 * 1024) });

    assert.deepEqual(host.calls[0].data.extra, { truncated: true });
  } finally {
    host.cleanup();
  }
});

test('extra 按 UTF-8 字节数判定上限（中文不该被低估 3 倍）', () => {
  const host = withMiniProgramHost();
  try {
    // 7000 个中文 = 21000 字节 > 16KB，但字符数只有 7000 —— 按字符算会漏判
    makeLogger().captureError(new Error('boom'), { zh: '中'.repeat(7000) });

    assert.deepEqual(host.calls[0].data.extra, { truncated: true });
  } finally {
    host.cleanup();
  }
});

test('无法序列化的 extra 不导致丢日志', () => {
  const host = withMiniProgramHost();
  try {
    const circular = {};
    circular.self = circular;
    makeLogger().captureError(new Error('boom'), { circular });

    assert.equal(host.calls.length, 1);
    assert.deepEqual(host.calls[0].data.extra, { unserializable: true });
  } finally {
    host.cleanup();
  }
});

// ---------------------------------------------------------------------------
// debug 模式
// ---------------------------------------------------------------------------

test('debug=true 时不发任何请求（本地开发不往库里灌数据）', () => {
  const host = withMiniProgramHost();
  const originalError = console.error;
  console.error = () => undefined;
  try {
    const logger = makeLogger({ debug: true });
    logger.captureError(new Error('boom'));
    logger.captureApiFailure({ method: 'GET', path: '/api/v1/x', status: 500 });

    assert.equal(host.calls.length, 0);
  } finally {
    console.error = originalError;
    host.cleanup();
  }
});

// ---------------------------------------------------------------------------
// 指纹（仅客户端去重用）
// ---------------------------------------------------------------------------

test('clientFingerprint 同输入同结果，任一段不同则不同', () => {
  const base = sdk.clientFingerprint('js', 'tenant-mp', 'm', 'stack');
  assert.equal(sdk.clientFingerprint('js', 'tenant-mp', 'm', 'stack'), base);
  assert.notEqual(sdk.clientFingerprint('promise', 'tenant-mp', 'm', 'stack'), base);
  assert.notEqual(sdk.clientFingerprint('js', 'h5-tenant', 'm', 'stack'), base);
  assert.notEqual(sdk.clientFingerprint('js', 'tenant-mp', 'm2', 'stack'), base);
  assert.notEqual(sdk.clientFingerprint('js', 'tenant-mp', 'm', 'other'), base);
});

test('指纹只看栈头 200 字符：栈尾的易变内容不影响去重', () => {
  const head = 'at render (app.js:1:1)';
  const a = sdk.clientFingerprint('js', 'tenant-mp', 'm', head + 'x'.repeat(300) + 'AAA');
  const b = sdk.clientFingerprint('js', 'tenant-mp', 'm', head + 'x'.repeat(300) + 'BBB');
  assert.equal(a, b);
});
