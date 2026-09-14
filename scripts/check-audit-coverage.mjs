#!/usr/bin/env node
/**
 * 审计覆盖守卫（设计 §9）。
 *
 * 运行：node scripts/check-audit-coverage.mjs
 *
 * 「每个写接口都要留痕」是一次性扫一遍挡不住的约定：新加一个 `@PostMapping` 却忘了
 * `@Audited`，页面上完全看不出来 —— 接口照常工作，只是那一类操作从此在审计台账里不存在，
 * 而审计的价值恰恰来自「完整」。因此把这条约定变成 exit 1。
 *
 * 五道检查，任一失败即退出码 1：
 *
 *  1. 每个写接口（`@Post/Put/Delete/PatchMapping`，含 `@RequestMapping(method = POST)` 写法）
 *     必须带 `@Audited(` 或 `@AuditedExempt(`。**只有这两种出口**：没有「默认豁免」，
 *     否则「漏加注解」和「故意不记」在源码上长得一模一样；
 *  2. 同一方法不得同时出现 `@Audited(` 与 `@AuditedExempt(` —— 自相矛盾的声明，
 *     评审时无法判断到底记不记；
 *  3. `@AuditedExempt("理由")` 的理由必须是字符串字面量、非空、长度 ≥ 8。
 *     `@AuditedExempt("TODO")` 不算理由 —— 它会让豁免在几个月后变成没人知道为什么存在的例外；
 *  4. 每个 `@AuditedExempt(` 都必须落在某个写接口上。接口被删/改名后残留的「幽灵豁免」
 *     本身无害，但它会让下一次评审看到一个指向不存在接口的说明，进而怀疑整份清单是否可信；
 *  5. 数量下限自检：正则在当前源码上失配会得到空集合，而空集合让所有断言恒真
 *     （本仓静态守卫的通用要求）。**豁免数量刻意不写死**：它是会随业务变化的真实数量，
 *     钉成常量只会让「新增一个只读预览接口」变成必须改脚本的伪失败；
 *     豁免清单的**完整性**由第 4 项而不是计数来保证。
 *
 * 输出逐条列出豁免清单及理由，便于评审时一眼扫过。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const JAVA_ROOT = join(ROOT, 'backend/src/main/java');

const failures = [];
const fail = (m) => failures.push(m);

const read = (p) => readFileSync(p, 'utf8');
const rel = (p) => relative(ROOT, p);

const walk = (dir, exts, acc = []) => {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, exts, acc);
    else if (exts.some((e) => p.endsWith(e))) acc.push(p);
  }
  return acc;
};

/** 数量下限自检：低于下限说明正则在当前源码上失配，必须报错而不是静默通过。 */
const expectAtLeast = (label, got, min) => {
  if (got < min) fail(`${label} 只解析出 ${got} 条（预期 ≥ ${min}）—— 解析失配，守卫失效`);
};

/**
 * 把注释（`//`、`/* *​/`）替换成等长空白，**保持下标不变**。
 *
 * <p>为什么必须等长：后面要按「写接口代码片段」的起止下标判断注解归属，
 * 直接删掉注释会让所有下标错位。同理，屏蔽注释时也要跟踪字符串 ——
 * 否则注释里的一句 `"` 会把后续整段源码误判成字符串。
 *
 * <p>字符串字面量本身**不**屏蔽：豁免理由就在里面，必须读得到。
 */
const maskComments = (src) => {
  const out = src.split('');
  const n = src.length;
  let i = 0;
  while (i < n) {
    const c = src[i];
    const next = src[i + 1];
    if (c === '/' && next === '/') {
      while (i < n && src[i] !== '\n') out[i++] = ' ';
    } else if (c === '/' && next === '*') {
      out[i++] = ' ';
      out[i++] = ' ';
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) {
        if (src[i] !== '\n') out[i] = ' ';
        i++;
      }
      if (i < n) {
        out[i++] = ' ';
        out[i++] = ' ';
      }
    } else if (c === '"' || c === "'") {
      i++;
      while (i < n) {
        if (src[i] === '\\') {
          i += 2;
          continue;
        }
        if (src[i] === c) {
          i++;
          break;
        }
        i++;
      }
    } else {
      i++;
    }
  }
  return out.join('');
};

/**
 * 从一个写接口注解的起下标，找到方法签名结束的位置。
 *
 * <p>括号深度为 0 处遇到 `{` 即方法体开始；遇到 `;` 则是抽象/接口方法声明。
 * 不跟踪深度就会把 `@PostMapping(path = "/x", consumes = "…")` 里那个 `(` 之后的内容、
 * 或 `Map<String, Object>` 之后的任意位置当成签名结束，切出错误的片段。
 */
const findSignatureEnd = (src, start) => {
  let depth = 0;
  for (let i = start; i < src.length; i++) {
    const c = src[i];
    if (c === '(') depth++;
    else if (c === ')') depth--;
    else if (depth === 0 && (c === '{' || c === ';')) return i;
  }
  return src.length;
};

/**
 * 从代码片段里取方法名，用于失败信息定位。
 *
 * <p>取「括号深度 0 处、后接 `(` 的**最后**一个标识符」= 方法名。
 * 之前的注解（`@PostMapping("/x")`、`@Audited(module = "a")`）也在深度 0 处带括号，
 * 但它们都在方法名之前，会被后者覆盖掉。
 */
const methodLabel = (segment) => {
  let depth = 0;
  let last = null;
  for (let i = 0; i < segment.length; i++) {
    const c = segment[i];
    if (c === '(' && depth === 0) {
      const m = /([A-Za-z_$][\w$]*)\s*$/.exec(segment.slice(0, i));
      if (m) last = m[1];
      depth++;
    } else if (c === '(') depth++;
    else if (c === ')') depth--;
  }
  return last ?? '(未识别的方法)';
};

const WRITE_MAPPING_RE =
  /@(PostMapping|PutMapping|DeleteMapping|PatchMapping)\b|@RequestMapping\s*\([^)]*RequestMethod\.(POST|PUT|DELETE|PATCH)\b/g;

const AUDITED_RE = /@Audited\s*\(/;
const EXEMPT_RE = /@AuditedExempt\s*\(/;
/** 豁免理由必须是字符串字面量；`@AuditedExempt(SOME_CONST)` 一律视为不合规 */
const EXEMPT_REASON_RE = /@AuditedExempt\s*\(\s*"((?:[^"\\]|\\.)*)"\s*\)/;

const MIN_REASON_LENGTH = 8;

const writeEndpoints = [];
const exemptions = [];

for (const file of walk(JAVA_ROOT, ['.java'])) {
  // 注解自身的定义文件不是端点
  if (file.endsWith('Audited.java') || file.endsWith('AuditedExempt.java')) continue;

  const src = maskComments(read(file));
  if (!src.includes('Mapping(')) continue;

  let exemptionsInFile = 0;
  let consumedExemptions = 0;

  for (const m of src.matchAll(WRITE_MAPPING_RE)) {
    const start = m.index;
    const segment = src.slice(start, findSignatureEnd(src, start));
    const label = `${rel(file)} :: ${methodLabel(segment)}`;

    const hasAudited = AUDITED_RE.test(segment);
    const hasExempt = EXEMPT_RE.test(segment);
    // 只要这个写接口片段里出现了 @AuditedExempt，就算被消费掉了 —— 与后面走哪条
    // 失败分支无关。否则「同时带两个注解」「理由过短」这类情况会被幽灵检查再报一次，
    // 给出一个与真实问题无关的误导性失败信息
    if (hasExempt) {
      consumedExemptions++;
    }

    if (!hasAudited && !hasExempt) {
      fail(
        `${label} 既无 @Audited 也无 @AuditedExempt —— ` +
          `该操作今后不会出现在审计台账里，而页面上完全看不出来`,
      );
      continue;
    }
    if (hasAudited && hasExempt) {
      fail(`${label} 同时带 @Audited 与 @AuditedExempt —— 自相矛盾的声明，评审无法判断记不记`);
      continue;
    }
    if (hasExempt) {
      const reason = EXEMPT_REASON_RE.exec(segment);
      if (!reason) {
        fail(
          `${label} 的 @AuditedExempt 理由不是字符串字面量 —— ` +
            `用常量/变量时评审者读代码时看不到理由，豁免就失去意义`,
        );
        continue;
      }
      const text = reason[1].trim();
      if (text.length < MIN_REASON_LENGTH) {
        fail(
          `${label} 的 @AuditedExempt 理由过短（"${text}"）—— ` +
            `至少 ${MIN_REASON_LENGTH} 字，说清为什么可以不记`,
        );
        continue;
      }
      exemptions.push({ label, reason: text });
    }

    writeEndpoints.push({ label, audited: hasAudited });
  }

  // 4. 幽灵豁免：文件里每个 @AuditedExempt( 都必须被某个写接口片段消费掉
  for (const _ of src.matchAll(/@AuditedExempt\s*\(/g)) exemptionsInFile++;
  if (exemptionsInFile > consumedExemptions) {
    fail(
      `${rel(file)} 有 ${exemptionsInFile} 处 @AuditedExempt，但只有 ${consumedExemptions} 处落在写接口上 —— ` +
        `残留的幽灵豁免会让下一次评审怀疑整份清单是否可信`,
    );
  }
}

const auditedCount = writeEndpoints.filter((e) => e.audited).length;

// ---------------------------------------------------------------------------
// 5. 数量下限自检
// ---------------------------------------------------------------------------
expectAtLeast('写接口', writeEndpoints.length, 190);
expectAtLeast('@Audited 写接口', auditedCount, 190);
expectAtLeast('@AuditedExempt 写接口', exemptions.length, 1);

// ---------------------------------------------------------------------------
// 输出
// ---------------------------------------------------------------------------
console.log('审计覆盖检查');
console.log(`  写接口 : ${writeEndpoints.length} 个`);
console.log(`  已审计 : ${auditedCount} 个`);
console.log(`  已豁免 : ${exemptions.length} 个`);

if (exemptions.length) {
  console.log('\n豁免清单（请逐条确认理由仍然成立）');
  for (const e of exemptions) {
    console.log(`  - ${e.label}`);
    console.log(`      理由：${e.reason}`);
  }
}

if (failures.length) {
  console.log('\n失败');
  for (const m of failures) console.log(`  - ${m}`);
  console.log(`\n检查未通过：${failures.length} 项失败\n`);
  process.exit(1);
}
console.log('\n检查通过：所有写接口都已明确「记录」或「豁免」\n');
