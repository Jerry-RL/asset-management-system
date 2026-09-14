#!/usr/bin/env node
/**
 * 产出小程序用的 CommonJS 单文件 `dist/log-sdk.mp.js`。
 *
 * 为什么要这个脚本：tsc 的输出文件名由源文件名决定（`src/index.ts` → `dist/index.js`），
 * 而两端小程序是按固定名字 `utils/log-sdk.js` 引用产物的。用 `outFile` 不行
 * （它只对 AMD/System 生效），因此这里跑完 tsc 后重命名一次。
 *
 * 用 Node 脚本而不是 `mv`：跨平台（Windows 上开发/CI 也要能跑）。
 */
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const packageRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const tscBin = join(
  packageRoot,
  'node_modules',
  '.bin',
  process.platform === 'win32' ? 'tsc.cmd' : 'tsc',
);

execFileSync(tscBin, ['-p', 'tsconfig.mp.json'], { cwd: packageRoot, stdio: 'inherit' });

const from = join(packageRoot, 'dist', 'index.js');
const to = join(packageRoot, 'dist', 'log-sdk.mp.js');

if (!existsSync(from)) {
  console.error(`[log-sdk] 构建产物缺失：${from}`);
  process.exit(1);
}
mkdirSync(dirname(to), { recursive: true });
renameSync(from, to);

// 产物是 CommonJS，但本包 package.json 有 "type": "module"，于是 Node 会按 ESM 解析
// 这个 .js 文件并报 "exports is not defined"。在 dist 里放一个 type=commonjs 覆盖掉
// —— 这样 `node --test` 能直接加载**构建产物**做断言，而小程序侧不受影响
// （小程序拷贝目录里没有 package.json，天然按 CommonJS 处理）。
writeFileSync(
  join(packageRoot, 'dist', 'package.json'),
  `${JSON.stringify({ type: 'commonjs' }, null, 2)}\n`,
);

console.log(`[log-sdk] 已产出 ${to}`);
