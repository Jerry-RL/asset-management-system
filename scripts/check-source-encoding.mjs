#!/usr/bin/env node
/**
 * 源文件编码守卫：**禁止 UTF-8 BOM**。
 *
 * 运行：node scripts/check-source-encoding.mjs
 *
 * 为什么需要一条单独的检查：`javac` 见到文件开头的 BOM（3 字节 `EF BB BF`）会报
 * `非法字符: '\ufeff'` 并**让整个 backend 编译失败** —— 不是某一个类，是整个模块。
 * 而这个错误在任何编辑器里都看不见（BOM 是零宽字符），只有真正编译时才炸；
 * 本仓已经发生过两次（V57 的 `DisposalRecordController`、V60 的 `AssetOperatorService`），
 * 两次都是「本地某次带 BOM 的保存」造成的，且事后排查成本远高于这条检查。
 *
 * 判据只有一条：**文件头不得出现 BOM**。UTF-8 无 BOM 是本仓的统一约定
 * （`.editorconfig` 已声明 charset = utf-8，但编辑器不会在保存时主动去掉已有 BOM）。
 *
 * 覆盖面：后端源码 + 迁移 SQL + 前端源码 + 脚本。刻意**不扫** `target/`、`node_modules/`、
 * `dist/`：那些是构建产物或三方代码，改了也没意义。
 *
 * 数量下限自检：目录遍历失配会得到空集合，而空集合让断言恒真 —— 与仓内其他守卫同规矩。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

/** BOM 的字节序列（不要写成字符串 '\ufeff' —— 读成 Buffer 后比对的是字节）。 */
const BOM = [0xef, 0xbb, 0xbf];

/** 需要检查的扩展名。二进制资源（图片 / 字体 / jar）不在其中。 */
const EXTENSIONS = ['.java', '.sql', '.ts', '.tsx', '.js', '.mjs', '.json', '.yml', '.yaml', '.xml'];

/** 扫描根目录；构建产物与三方依赖必须排除，否则会报一堆改不动的第三方文件。 */
const SCAN_ROOTS = ['backend/src', 'frontend', 'scripts', 'docs', '.github'];
const SKIP_DIRS = new Set(['node_modules', 'target', 'dist', 'build', '.git', 'coverage']);

const walk = (dir, acc = []) => {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    // 目录不存在（例如某天删掉了 docs）：跳过而不是报错 —— 那不是编码问题
    return acc;
  }
  for (const name of entries) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) {
      if (SKIP_DIRS.has(name)) continue;
      walk(p, acc);
    } else if (EXTENSIONS.some((ext) => p.endsWith(ext))) {
      acc.push(p);
    }
  }
  return acc;
};

const hasBom = (path) => {
  const head = readFileSync(path).subarray(0, 3);
  return head.length === 3 && head[0] === BOM[0] && head[1] === BOM[1] && head[2] === BOM[2];
};

const files = SCAN_ROOTS.flatMap((root) => walk(join(ROOT, root)));
const offenders = files.filter(hasBom);

console.log('源文件编码检查（禁止 UTF-8 BOM）');
console.log(`  扫描文件：${files.length} 个（根目录：${SCAN_ROOTS.join('、')}）`);

if (files.length < 200) {
  console.error(
    `\n失败\n  ✗ 只解析出 ${files.length} 个文件（预期 ≥ 200）—— 路径或扩展名失配，守卫失效\n`,
  );
  process.exit(1);
}

if (offenders.length > 0) {
  console.error('\n失败');
  for (const path of offenders) {
    console.error(
      `  ✗ ${relative(ROOT, path).replace(/\\/g, '/')} 带 UTF-8 BOM —— ` +
        'javac 会报「非法字符: \'\\ufeff\'」并让整个 backend 编译失败',
    );
  }
  console.error(
    `\n${offenders.length} 个文件需要去掉 BOM（用编辑器「以 UTF-8 无 BOM 保存」即可）\n`,
  );
  process.exit(1);
}

console.log('\n检查通过\n');
