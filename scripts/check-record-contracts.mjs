#!/usr/bin/env node
/**
 * 后续记录（record-sheet）静态契约守卫 —— 在**没有 JVM** 的环境下替代一部分 `mvn verify`。
 *
 * 运行：node scripts/check-record-contracts.mjs
 *
 * 背景：本仓库的正常校验路径是 `mvn -B verify`，但后端测试的**唯一**运行前提是 JDK。
 * 在只有 Node 的机器上（本机、以及 issue 里报的那种「前端机」）就出现了一个盲区：
 * 新增了后续记录段，编译不通过 / 迁移与实体列对不上 / 前端提交了服务端不认的段，
 * 这些都要等 CI 的 backend job 才炸。本脚本用「正则 + 深度跟踪」把这些**编译期错误**
 * 前移到 node 一条命令，与 `check-perm-invariants.mjs` 同一套路数（不依赖构建）。
 *
 * 八道检查，**全部失败即退出码 1**：
 *
 *  1. 迁移 ↔ 实体：V49 的每一列都能在实体字段里找到（snake_case ↔ camelCase），
 *     反之实体字段也必须有列。缺一列 → 运行期 `Unknown column`；多一列 → 写入被静默丢弃。
 *  2. `@TableName` 必须与迁移里的表名一致（不一致时 MyBatis 会去查一张不存在的表）。
 *  3. `*MigrationContractTest` 的列契约 ↔ 迁移实际列：把 JDK 侧的断言在 JS 里再跑一遍
 *     （含 V47/V48 那种逐条 `contains("列名")` 的风格），改坏任一侧都会在这里变红。
 *  4. `RecordSheetService` 里的 `getXxx` / `setXxx` 调用必须能在对应 DTO / 实体上找到字段。
 *     Lombok 的存取器是编译期生成的，**拼错字段名只有 javac 会报** —— 这一步把它前移。
 *  5. 后端聚合段 `RecordSheetRequest` / `RecordSheetView` 的字段必须与前端 `RecordSheet`
 *     接口逐一对应。多一段 → 前端提交的段被服务端忽略，表现为「填了保存不上」（不报错）；
 *     少一段 → 前端读回 undefined，表单直接崩。
 *  6. `AttachmentOwner` 常量引用必须都声明过（新增附件宿主时最容易漏的一步）。
 *  7. 结构不变量：V49 不得改既有表 / 插菜单；record-sheet 端点仍为 6 个。
 *  8. 测试夹具跟得上 `RecordSheetService` 构造器实参个数（javac 会报的那类错）。
 *
 * 每一步都有「数量下限自检」：正则在当前源码上失配会直接报错，而不是让断言恒真。
 *
 * ⚠️ 本脚本**不能**替代 `mvn verify`：它只覆盖「解析得出来」的契约，
 * 不跑 Flyway、不跑 Spring 上下文、不校验运行时 SQL 语义。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const BACKEND_MAIN = join(ROOT, 'backend/src/main/java');
const BACKEND_TEST = join(ROOT, 'backend/src/test/java');
const MIGRATION_DIR = join(ROOT, 'backend/src/main/resources/db/migration');
const MIGRATION = join(MIGRATION_DIR, 'V49__record_cost_evaluation.sql');
const FRONTEND_RECORD_SHEET = join(ROOT, 'frontend/admin-web/src/lib/recordSheet.ts');

const failures = [];
const warnings = [];
const infos = [];
const fail = (m) => failures.push(m);
const warn = (m) => warnings.push(m);

const read = (p) => readFileSync(p, 'utf8');
const walk = (dir, exts, acc = []) => {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, exts, acc);
    else if (exts.some((e) => p.endsWith(e))) acc.push(p);
  }
  return acc;
};
const rel = (p) => relative(ROOT, p);

/**
 * 把注释与字符串字面量替换成等长空格：**长度不变**，因此偏移量与原文件一一对应。
 * 不做这一步，Javadoc 里的 `{@code ...}` 会被当成真实花括号，方法切分会错位。
 */
const scrub = (src) => {
  const out = src.split('');
  const blank = (from, to) => {
    for (let i = from; i < to; i++) if (out[i] !== '\n') out[i] = ' ';
  };
  for (let i = 0; i < src.length; i++) {
    const two = src.slice(i, i + 2);
    if (two === '//') {
      const end = src.indexOf('\n', i);
      blank(i, end === -1 ? src.length : end);
      i = end === -1 ? src.length : end;
    } else if (two === '/*') {
      const end = src.indexOf('*/', i + 2);
      const stop = end === -1 ? src.length : end + 2;
      blank(i, stop);
      i = stop - 1;
    } else if (src[i] === '"' || src[i] === "'") {
      const quote = src[i];
      let j = i + 1;
      while (j < src.length) {
        if (src[j] === '\\') j += 2;
        else if (src[j] === quote) break;
        else j++;
      }
      blank(i, Math.min(j + 1, src.length));
      i = j;
    }
  }
  return out.join('');
};

/** 数量下限自检：正则失配会得到空集合，而空集合让所有断言恒真 —— 必须报错而不是静默通过。 */
const expectAtLeast = (label, got, min) => {
  if (got < min) fail(`${label} 只解析出 ${got} 条（预期 ≥ ${min}）—— 解析失配，守卫失效`);
};

// ---------------------------------------------------------------------------
// Java 结构解析（类 / 字段 / 方法块）
// ---------------------------------------------------------------------------
const IDENTIFIER_LINE = /^ {4}(?:@\w+\s+)*(?:private|public|protected)\s+(?:static\s+|final\s+|transient\s+|volatile\s+)*([\w.<>,\[\]\s]+?)\s+([A-Za-z_]\w*)\s*(?:=|;)/gm;

const simpleType = (type) => type.replace(/<.*/, '').replace(/\[\]/g, '').trim();

/** 切出类的顶层块（方法 / static 块），用于把变量解析限定在**方法作用域**内。 */
const topLevelBlocks = (body) => {
  const blocks = [];
  let depth = 0;
  let blockStart = -1;
  let sigStart = 0;
  for (let i = 0; i < body.length; i++) {
    const c = body[i];
    if (c === '{') {
      if (depth === 0) blockStart = i;
      depth++;
    } else if (c === '}') {
      depth--;
      if (depth === 0) {
        blocks.push({ sig: body.slice(sigStart, blockStart).trim(), body: body.slice(blockStart + 1, i) });
        sigStart = i + 1;
      }
    } else if (c === ';' && depth === 0) {
      sigStart = i + 1;
    }
  }
  return blocks;
};

const parseJavaFile = (path) => {
  const raw = read(path);
  const src = scrub(raw);
  const classMatch = src.match(/\b(class|enum|interface)\s+(\w+)/);
  if (!classMatch) return null;
  const [, kind, name] = classMatch;
  // @TableName 的取值是字符串字面量，scrub 之后会被抹掉 —— 这一处必须读原始文本
  const typeName = raw.match(/@TableName\("([^"]+)"\)/)?.[1] ?? null;
  const fields = new Map();
  for (const m of src.matchAll(IDENTIFIER_LINE)) {
    const [, type, fieldName] = m;
    // 排除构造器 / 方法（`private` 开头且下一 token 是 `(`）与常量：字段声明必须可作类型解析
    if (type.trim().endsWith(')')) continue;
    if (!fields.has(fieldName)) fields.set(fieldName, simpleType(type));
  }

  // 类体：从 class 声明的 `{` 到配对的 `}`，方法块在类体内部逐层切
  const classBrace = src.indexOf('{', classMatch.index);
  let depth = 0;
  let classEnd = src.length;
  for (let i = classBrace; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) {
        classEnd = i;
        break;
      }
    }
  }
  const classBody = src.slice(classBrace + 1, classEnd);
  const methods = topLevelBlocks(classBody).filter(
    (b) => b.sig.includes('(') && !/\b(class|enum|interface|if|for|while|switch|try|catch|finally|synchronized)\b/.test(b.sig),
  );
  return { name, kind, typeName, fields, methods, file: path, classBody };
};

const javaSources = walk(BACKEND_MAIN, ['.java']);
const classes = [];
for (const p of javaSources) {
  const parsed = parseJavaFile(p);
  if (parsed) classes.push(parsed);
}
const classByName = new Map(classes.map((c) => [c.name, c]));
expectAtLeast('后端 Java 类', classes.length, 200);
expectAtLeast('类字段', [...classByName.values()].reduce((n, c) => n + c.fields.size, 0), 500);

const AUDIT_FIELDS = new Set(['createdAt', 'updatedAt', 'createdBy', 'updatedBy']);

/**
 * 方法作用域内的变量 → 简单类型。
 * 逐个方法独立解析：`target` / `input` 这类名字在同一个类里被反复复用（类型各不相同），
 * 合并成一张全局表必然误判。
 */
const localTypesOf = (method) => {
  const types = new Map();
  const parenStart = method.sig.indexOf('(');
  const parenEnd = method.sig.lastIndexOf(')');
  if (parenStart !== -1 && parenEnd > parenStart) {
    for (const param of method.sig.slice(parenStart + 1, parenEnd).split(',')) {
      const m = param.trim().match(/^(?:final\s+)?([\w.<>,\[\]\s]+?)\s+(\w+)$/);
      if (m) types.set(m[2], simpleType(m[1]));
    }
  }
  for (const m of method.body.matchAll(/(?:^|\n)\s*(?:final\s+)?([A-Z]\w*(?:<[^>]*>)?)\s+(\w+)\s*=/g)) {
    types.set(m[2], simpleType(m[1]));
  }
  for (const m of method.body.matchAll(/\bfor\s*\(\s*(?:final\s+)?([\w.<>,\[\]\s]+?)\s+(\w+)\s*:/g)) {
    types.set(m[2], simpleType(m[1]));
  }
  return types;
};

const decapitalize = (s) => s.charAt(0).toLowerCase() + s.slice(1);

// ---------------------------------------------------------------------------
// 1 + 2：迁移 ↔ 实体列一致
// ---------------------------------------------------------------------------
const snake = (s) => s.replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`);
const migration = read(MIGRATION);
const tableBody = (table) => {
  const start = migration.indexOf(`CREATE TABLE IF NOT EXISTS ${table}`);
  if (start === -1) return null;
  const end = migration.indexOf('\n);', start);
  return end === -1 ? null : migration.slice(start, end);
};
const columnsOf = (body) => [...body.matchAll(/^\s{4}(\w+)\s+[A-Z]/gm)].map((m) => m[1]);

const TABLES = ['biz_cost_record', 'biz_cost_item', 'biz_evaluation_info'];
const entityOfTable = new Map(
  classes.filter((c) => c.typeName && TABLES.includes(c.typeName)).map((c) => [c.typeName, c]),
);
expectAtLeast('V49 表 ↔ 实体映射', entityOfTable.size, TABLES.length);

for (const table of TABLES) {
  const body = tableBody(table);
  if (!body) {
    fail(`V49 缺少建表语句：${table}`);
    continue;
  }
  const entity = entityOfTable.get(table);
  if (!entity) {
    fail(`没有实体的 @TableName 指向 ${table}`);
    continue;
  }
  const columns = new Set(columnsOf(body));
  expectAtLeast(`${table} 列`, columns.size, 9);
  const declared = [...entity.fields.keys()].filter((f) => !AUDIT_FIELDS.has(f));
  for (const field of declared) {
    if (!columns.has(snake(field))) {
      fail(`${table}：实体字段 ${field} 没有对应列 ${snake(field)}（写入会 Unknown column）`);
    }
  }
  for (const column of columns) {
    const field = declared.find((f) => snake(f) === column);
    // 审计列在 BaseEntity 上，不参与比对；deleted_at 为软删列
    if (!field && !['created_at', 'updated_at', 'created_by', 'updated_by', 'deleted_at'].includes(column)) {
      fail(`${table}：列 ${column} 在实体 ${entity.name} 上没有字段（写入会被丢弃）`);
    }
  }
  for (const audit of ['created_at', 'updated_at', 'created_by', 'updated_by', 'deleted_at']) {
    if (!columns.has(audit)) fail(`${table}：缺少 ${audit} 列（软删 / 审计无处落值）`);
  }
}

// ---------------------------------------------------------------------------
// 1b：JUnit 迁移契约测试的期望 ↔ 迁移实际列
//
// `*MigrationContractTest` 是 JDK 侧对迁移的结构化断言（列名 + 声明逐字钉死）。
// 这里把**同一套断言**用 JS 跑一遍：期望与迁移任一侧被改坏，都能在没有 JVM 的机器上变红，
// 而不是等 CI 的 backend job。两侧同时改错的概率极低，但「只改一侧」是最常见的合并事故。
// ---------------------------------------------------------------------------
const columnRegex = (column, declaration) => {
  const type = declaration
    .trim()
    .split(/\s+/)
    .map((token) => token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
    .join('\\s+');
  return new RegExp(`^\\s*${column}\\s+${type}(?=\\s|,|;|$)`, 'm');
};

const contractTests = walk(BACKEND_TEST, ['.java']).filter((p) => /MigrationContractTest\.java$/.test(p));
expectAtLeast('迁移契约测试', contractTests.length, 2);

for (const testPath of contractTests) {
  const test = read(testPath);
  const migrationPath = test.match(/"(\/db\/migration\/V\d+__[\w]+\.sql)"/)?.[1];
  if (!migrationPath) {
    warn(`${rel(testPath)}：没有解析到迁移路径，跳过契约对照`);
    continue;
  }
  const sql = read(join(ROOT, 'backend/src/main/resources', migrationPath));

  // 按「表名 → 紧随其后的列期望」顺序切分：与测试文件里 EXPECTED_COLUMNS 的书写顺序一致
  const parts = test.split(/"(\w+)",\s*List\.of\(/);
  let specCount = 0;
  let tableCount = 0;
  for (let i = 1; i < parts.length; i += 2) {
    const table = parts[i];
    const specs = [...parts[i + 1].matchAll(/"([a-z_]+:[A-Z][^"]*)"/g)].map((m) => m[1]);
    if (specs.length === 0) continue;
    tableCount++;
    const start = sql.indexOf(`CREATE TABLE IF NOT EXISTS ${table}`);
    if (start === -1) {
      fail(`${rel(testPath)}：契约测试断言了 ${table}，但 ${migrationPath} 里没有这张表`);
      continue;
    }
    const end = sql.indexOf('\n);', start);
    const body = sql.slice(start, end === -1 ? sql.length : end);
    for (const spec of specs) {
      const sep = spec.indexOf(':');
      const column = spec.slice(0, sep);
      const declaration = spec.slice(sep + 1);
      specCount++;
      if (!columnRegex(column, declaration).test(body)) {
        fail(`${migrationPath}：${table} 不满足契约断言 ${column} ${declaration}（${rel(testPath)}）`);
      }
    }
  }
  if (specCount === 0) {
    // V47/V48 用的是逐条 `contains("列名")`，没有「列名:声明」的规格串，由第 3 项覆盖
    warn(`${rel(testPath)}：不是「列名:声明」风格，跳过列契约对照（改由第 3 项的字面量断言覆盖）`);
  } else {
    expectAtLeast(`${rel(testPath)} 的列契约`, specCount, 30);
    infos.push(`${rel(testPath)}：${tableCount} 张表 / ${specCount} 条列契约与迁移一致`);
  }
}

// ---------------------------------------------------------------------------
// 3：把契约测试里的**字面量断言**在 JS 里重跑（V47/V48 用的是 `contains("列名")` 风格）
// 只有 V46/V49 用「列名:声明」的规格串，其余契约测试是逐条 `contains`。
// 两类都要能在无 JVM 的机器上变红，因此这里把 `contains` / `doesNotContain` 的字符串参数
// 直接拿去比对迁移文本 —— 与 Java 侧做的**是同一个断言**，不引入第二套口径。
// 含变量拼接（`"'" + CODE + "'"`）或过短的字面量跳过：无法静态求值。
// ---------------------------------------------------------------------------
let literalChecks = 0;
/** 去掉 `--` 行注释：`doesNotContain` 要判的是**真实 DDL**，注释里提到关键词不算。 */
const stripSqlComments = (sql) =>
  sql
    .split('\n')
    .map((line) => line.replace(/--.*$/, ''))
    .join('\n');

for (const testPath of contractTests) {
  const test = read(testPath);
  const migrationPath = test.match(/"(\/db\/migration\/V\d+__[\w]+\.sql)"/)?.[1];
  if (!migrationPath) continue;
  const sql = read(join(ROOT, 'backend/src/main/resources', migrationPath));
  const sqlNoComments = stripSqlComments(sql);

  for (const m of test.matchAll(/\.(doesNotContain|contains)\(/g)) {
    const negate = m[1] === 'doesNotContain';
    const open = m.index + m[0].length - 1;
    let depth = 0;
    let close = open;
    for (let i = open; i < test.length; i++) {
      if (test[i] === '(') depth++;
      else if (test[i] === ')') {
        depth--;
        if (depth === 0) {
          close = i;
          break;
        }
      }
    }
    const args = test.slice(open + 1, close);
    for (const lit of args.matchAll(/"([^"\\]+)"/g)) {
      const value = lit[1];
      if (value.length < 4 || value.includes("'") || value.trim() !== value) continue;
      literalChecks++;
      if (negate && sqlNoComments.includes(value)) {
        fail(`${rel(testPath)}：迁移 ${migrationPath} 里出现了不该出现的 ${JSON.stringify(value)}`);
      }
      if (!negate && !sql.includes(value)) {
        fail(`${rel(testPath)}：迁移 ${migrationPath} 里找不到契约断言的 ${JSON.stringify(value)}`);
      }
    }
  }
}
expectAtLeast('契约测试字面量断言', literalChecks, 30);
infos.push(`契约测试字面量断言重跑：${literalChecks} 条`);

// ---------------------------------------------------------------------------
// 3：Service 里的存取器调用 ↔ DTO / 实体字段
// ---------------------------------------------------------------------------
const service = classByName.get('RecordSheetService');
if (!service) {
  fail('找不到 RecordSheetService —— 解析失配或文件被移动');
} else {
  let checkedCalls = 0;
  for (const method of service.methods) {
    const types = localTypesOf(method);
    for (const m of method.body.matchAll(/\b(\w+)\.(get|set)([A-Z]\w*)\s*\(/g)) {
      const [, variable, , suffix] = m;
      const type = types.get(variable);
      if (!type) continue;
      const target = classByName.get(type);
      if (!target || target.kind !== 'class') continue;
      checkedCalls++;
      const field = decapitalize(suffix);
      if (!target.fields.has(field) && !AUDIT_FIELDS.has(field)) {
        fail(
          `${rel(service.file)}：${type}.${m[2]}${suffix}() 没有对应字段（${target.fields.size} 个字段里找不到 ${field}）`,
        );
      }
    }
  }
  expectAtLeast('Service 存取器调用', checkedCalls, 60);
  infos.push(`RecordSheetService 存取器调用：${checkedCalls} 处`);
}

// ---------------------------------------------------------------------------
// 4：后端聚合段 ↔ 前端 RecordSheet 接口
// ---------------------------------------------------------------------------
const FRONTEND_SKIP = new Set(['projectId', 'canSyncDisposals']);
const interfaceFields = (source, interfaceName) => {
  const start = source.indexOf(`export interface ${interfaceName} {`);
  if (start === -1) return null;
  const end = source.indexOf('\n}', start);
  const body = source.slice(start, end);
  return new Set([...body.matchAll(/^\s{2}(\w+)\??\s*:/gm)].map((m) => m[1]));
};

const ts = read(FRONTEND_RECORD_SHEET);
const frontendFields = interfaceFields(ts, 'RecordSheet');
if (!frontendFields) {
  fail('frontend/admin-web/src/lib/recordSheet.ts 里找不到 `export interface RecordSheet`');
  infos.push('跳过第 4 项（前后端段一致性）');
} else {
  for (const dtoName of ['RecordSheetRequest', 'RecordSheetView']) {
    const dto = classByName.get(dtoName);
    if (!dto) {
      fail(`找不到 DTO ${dtoName}`);
      continue;
    }
    const backendFields = new Set([...dto.fields.keys()].filter((f) => !FRONTEND_SKIP.has(f)));
    const onlyBackend = [...backendFields].filter((f) => !frontendFields.has(f));
    const onlyFrontend = [...frontendFields].filter((f) => !backendFields.has(f));
    if (onlyBackend.length) {
      fail(`${dtoName} 有前端未声明的段：${onlyBackend.join('、')}（前端读回 undefined）`);
    }
    if (onlyFrontend.length) {
      fail(`前端 RecordSheet 有后端不认的段：${onlyFrontend.join('、')}（提交后被静默忽略）`);
    }
  }
  infos.push(`聚合段：${[...frontendFields].join('、')}`);
}

// ---------------------------------------------------------------------------
// 5：AttachmentOwner 常量引用都能解析
// ---------------------------------------------------------------------------
const attachmentOwner = classByName.get('AttachmentOwner');
if (!attachmentOwner) {
  fail('找不到 AttachmentOwner 枚举');
} else {
  const constants = new Set(
    [...attachmentOwner.classBody.matchAll(/^\s{4}([A-Z][A-Z0-9_]*)\(/gm)].map((m) => m[1]),
  );
  expectAtLeast('AttachmentOwner 常量', constants.size, 6);
  let references = 0;
  for (const p of javaSources) {
    for (const m of read(p).matchAll(/AttachmentOwner\.([A-Z][A-Z0-9_]*)/g)) {
      references++;
      if (!constants.has(m[1])) fail(`${rel(p)} 引用了未声明的 AttachmentOwner.${m[1]}`);
    }
  }
  expectAtLeast('AttachmentOwner 引用', references, 6);
}

// ---------------------------------------------------------------------------
// 6：结构不变量
// ---------------------------------------------------------------------------
if (/ALTER\s+TABLE/i.test(migration)) {
  fail('V49 不得修改既有表（本期只新增三张 + 一个字典）');
}
if (/INSERT\s+INTO\s+(menu|sys_role_menu)/i.test(migration)) {
  fail('V49 不得新增菜单（沿用既有菜单码）');
}
const dictTypes = [...migration.matchAll(/\('(\w+)',\s*'[^']*',\s*\d+\)/g)].map((m) => m[1]);
expectAtLeast('V49 字典类型', dictTypes.length, 1);

const controller = classByName.get('RecordSheetController');
if (!controller) {
  fail('找不到 RecordSheetController');
} else {
  const endpoints = [...controller.classBody.matchAll(/@(?:Get|Put|Post|Delete)Mapping\(/g)];
  if (endpoints.length !== 6) {
    fail(`record-sheet 端点应为 6 个（3 主体 × 读/写），实际 ${endpoints.length} 个`);
  }
}

/** 前端 `useDictOptions('x')` 的字典码应当在某个迁移里落过种子（缺失 → 下拉永远为空）。 */
const seededDicts = new Set();
for (const p of walk(MIGRATION_DIR, ['.sql'])) {
  for (const m of read(p).matchAll(/^\s*\('(\w+)',\s*'[^']*',\s*\d+\)/gm)) seededDicts.add(m[1]);
}
const usedDicts = new Set(
  [...read(join(ROOT, 'frontend/admin-web/src/components/RecordSheetSections.tsx')).matchAll(
    /useDictOptions\('(\w+)'\)/g,
  )].map((m) => m[1]),
);
for (const code of usedDicts) {
  if (!seededDicts.has(code)) warn(`字典 ${code} 在任何迁移里都没有种子（下拉会是空）`);
}
infos.push(`后续记录用到的字典：${[...usedDicts].join('、')}`);

// ---------------------------------------------------------------------------
// 测试夹具跟得上构造器（javac 会报 arity 不匹配，这里前移）
// ---------------------------------------------------------------------------
for (const testPath of walk(BACKEND_TEST, ['.java'])) {
  const src = scrub(read(testPath));
  for (const m of src.matchAll(/new\s+(RecordSheetService)\s*\(/g)) {
    const open = m.index + m[0].length - 1;
    let depth = 0;
    let close = open;
    for (let i = open; i < src.length; i++) {
      if (src[i] === '(') depth++;
      else if (src[i] === ')') {
        depth--;
        if (depth === 0) {
          close = i;
          break;
        }
      }
    }
    const args = src
      .slice(open + 1, close)
      .split(',')
      .map((a) => a.trim())
      .filter(Boolean);
    if (!service) {
      warn(`${rel(testPath)}：找不到 RecordSheetService，跳过构造器实参校验`);
      break;
    }
    if (args.length !== service.fields.size) {
      fail(
        `${rel(testPath)}：new RecordSheetService(...) 传了 ${args.length} 个实参，` +
          `而构造器需要 ${service.fields.size} 个（${[...service.fields.keys()].join(', ')}）`,
      );
    }
  }
}

// ---------------------------------------------------------------------------
// 结果
// ---------------------------------------------------------------------------
console.log('后续记录（record-sheet）静态契约检查');
for (const line of infos) console.log(`  · ${line}`);
console.log(`  检查文件：${rel(MIGRATION)}、${rel(FRONTEND_RECORD_SHEET)}、RecordSheetService/Cost*/Evaluation*`);

if (warnings.length) {
  console.log('\n告警');
  for (const w of warnings) console.log(`  - ${w}`);
}

if (failures.length) {
  console.error('\n失败');
  for (const f of failures) console.error(`  ✗ ${f}`);
  console.error(`\n${failures.length} 项检查未通过`);
  process.exit(1);
}
console.log('\n检查通过');
