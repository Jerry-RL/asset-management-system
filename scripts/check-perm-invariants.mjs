#!/usr/bin/env node
/**
 * 权限与路由一致性守卫（设计 §7 的自动化落点）。
 *
 * 运行：node scripts/check-perm-invariants.mjs
 *
 * 四道检查，**前三道失败即退出码 1**（会静默破坏功能的类型），第四道只告警：
 *
 *  1. `perm` 声明格式：每个声明必须形如 `menuCode:action`。
 *  2. 跨仓不变量：前端声明 ⊆ 后端 `@RequiresPerm` 集合。
 *     违反的两种表现都是「按钮静默消失」或「点了 403」，且都不会在编译期报错：
 *       - 声明了后端未强制的码 → 若该接口没接入拦截，按钮对所有人消失（功能损失）；
 *       - 码写错 / 后端改了注解 → 按钮消失或点了 403。
 *  2b. 后端测试夹具里的权限码 ⊆ 后端 `@RequiresPerm` 集合（同源的第 2 条）。
 *     夹具断言「角色装配结果包含 X」，X 却由夹具自己写死，编造一个不存在的码照样全绿，
 *     还会给出「该角色确实有这个权限」的错误印象（实测曾编出 21 个）。
 *  3. 镜像完整性：`RESOURCES` / `STANDALONE_ROUTES` 里的路由必须都在 `PATH_TO_CODE` 内。
 *     缺一条 → `canByPath` 判定恒真，**那一页的按钮门槛静默失效**（不报错、不越权，
 *     只是「本该隐藏的按钮仍然显示」，最难发现）。
 *  4. 路由双写一致性（设计 §11.2 已登记的缺口）：只告警，因为 `App.tsx` 里的钻取路由
 *     （`/projects/:id`）本就不该进注册表，无法自动区分「漏登记」与「本就不登记」。
 *
 * 解析策略：正则 + 深度跟踪，**不依赖构建**（无 TS/JSX 编译），因此任何解析都要带
 * 「数量下限」自检 —— 正则一旦失配就会得到空集合，而空集合会让所有断言恒真。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SRC = join(ROOT, 'frontend/admin-web/src');

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

/** 数量下限自检：低于下限说明正则在当前源码上失配，必须报错而不是静默通过。 */
const expectAtLeast = (label, got, min) => {
  if (got < min) fail(`${label} 只解析出 ${got} 条（预期 ≥ ${min}）—— 解析失配，守卫失效`);
};

// ---------------------------------------------------------------------------
// 后端：@RequiresPerm 真正强制的 menu:action
// ---------------------------------------------------------------------------
const backendFiles = walk(join(ROOT, 'backend/src/main/java'), ['.java']).filter(
  (f) => !f.includes('/platform/security/PermissionRegistry.java'),
);
const enforced = new Set();
for (const f of backendFiles) {
  for (const m of read(f).matchAll(/@RequiresPerm\(\s*"([^"]+)"\s*\)/g)) enforced.add(m[1]);
}
expectAtLeast('后端 @RequiresPerm', enforced.size, 40);

// ---------------------------------------------------------------------------
// 前端：所有声明 perm 的位置
// ---------------------------------------------------------------------------
const declared = [];
const addDeclared = (file, code, form) => declared.push({ file: rel(file), code, form });
for (const f of walk(SRC, ['.tsx', '.ts'])) {
  const s = read(f);
  // 对象字面量形式（TableActions / RowActionConfig）
  for (const m of s.matchAll(/\bperm:\s*'([^']+)'/g)) addDeclared(f, m[1], 'perm:');
  // JSX 属性字符串形式
  for (const m of s.matchAll(/\bperm="([^"]+)"/g)) addDeclared(f, m[1], 'perm=');
  // 三元形式：两个分支各是一个独立声明，不能拼在一起
  for (const m of s.matchAll(/\bperm=\{\s*[^}]*?'([^']+)'\s*:\s*'([^']+)'\s*\}/g)) {
    addDeclared(f, m[1], 'perm={?:}');
    addDeclared(f, m[2], 'perm={?:}');
  }
}
expectAtLeast('前端 perm 声明', declared.length, 5);

// 1. 格式
for (const d of declared) {
  const i = d.code.lastIndexOf(':');
  if (i <= 0 || i === d.code.length - 1) {
    fail(`${d.file} 的 perm 声明 ${JSON.stringify(d.code)} 不是 menuCode:action 形式`);
  }
}
// 2. 跨仓不变量
for (const d of declared) {
  if (!enforced.has(d.code)) {
    fail(
      `${d.file} 声明的 ${d.code} 在后端 @RequiresPerm 中不存在 —— ` +
        `若该接口未接入拦截，声明会让按钮对除超管外所有人消失（功能损失）`,
    );
  }
}

// ---------------------------------------------------------------------------
// 后端测试夹具里的权限码：同样必须 ⊆ @RequiresPerm
// ---------------------------------------------------------------------------
// 夹具断言「角色装配出的权限码包含 X」，而 X 是夹具自己写死的 —— 一旦编造一个后端
// 并不强制的码，用例照样全绿，却给出「该角色确实有这个权限」的错误印象（实测曾
// 编出 21 个不存在的码）。与第 2 条同源，故一并硬失败。
const fixtureSrc = read(join(ROOT, 'backend/src/test/java/com/ams/support/RbacFixtures.java'));
const fixtureBlock = fixtureSrc.slice(
  fixtureSrc.indexOf('// ---- 写权限授予'),
  fixtureSrc.indexOf('// ---- 数据范围排除'),
);
if (fixtureBlock === '') fail('RbacFixtures 里未找到「写权限授予」区块（结构已变？）');
const fixtureCodes = new Set([...fixtureBlock.matchAll(/"([a-z][A-Za-z0-9_.]*:[a-z_]+)"/g)].map((m) => m[1]));
expectAtLeast('测试夹具权限码', fixtureCodes.size, 20);
for (const code of fixtureCodes) {
  if (!enforced.has(code)) {
    fail(`测试夹具 RbacFixtures 里的 ${code} 在后端 @RequiresPerm 中不存在 —— 编造的码会让用例静默失效`);
  }
}

// ---------------------------------------------------------------------------
// 路由：PATH_TO_CODE 镜像 / RESOURCES / STANDALONE_ROUTES / App.tsx
// ---------------------------------------------------------------------------
const registrySrc = read(join(SRC, 'lib/routeRegistry.ts'));

const pathToCode = new Map();
{
  const start = registrySrc.indexOf('export const PATH_TO_CODE');
  if (start < 0) fail('未找到 PATH_TO_CODE（routeRegistry.ts 结构已变？）');
  const body = registrySrc.slice(start, registrySrc.indexOf('\n};', start));
  for (const m of body.matchAll(/^\s*'([^']+)':\s*'([^']+)',\s*$/gm)) pathToCode.set(m[1], m[2]);
}
expectAtLeast('PATH_TO_CODE 条目', pathToCode.size, 50);

const standalone = [];
{
  const start = registrySrc.indexOf('export const STANDALONE_ROUTES');
  const body = registrySrc.slice(start, registrySrc.indexOf('];', start));
  for (const m of body.matchAll(/'([^']+)'/g)) standalone.push(m[1]);
}
expectAtLeast('STANDALONE_ROUTES', standalone.length, 10);

/** RESOURCES 的顶层键：按 2 空格缩进匹配（嵌套键缩进 ≥ 4），区间由起始行到列 0 的 `};` 界定。 */
const resourceKeys = [];
{
  const src = read(join(SRC, 'pages/modules.tsx'));
  const start = src.indexOf('export const RESOURCES');
  if (start < 0) fail('未找到 RESOURCES（modules.tsx 结构已变？）');
  // 不用「花括号深度」界定区间：配置里含箭头函数体与模板串，深度会在中途归零而截断
  // （实测只解析出 19/49 条，正是本条自检抓到的）。
  const rest = src.slice(start);
  const endIdx = rest.search(/\n\};\s*$/m);
  const body = endIdx < 0 ? rest : rest.slice(0, endIdx);
  for (const m of body.matchAll(/^ {2}(?:([A-Za-z_$][\w$]*)|'([^']+)'):\s*\{/gm)) {
    resourceKeys.push(m[1] ?? m[2]);
  }
}
// 含斜杠的键必须加引号（`'lease-listings'`、`'/billing/bills'`），只匹配标识符会漏掉 30/49 条
expectAtLeast('RESOURCES 顶层键', resourceKeys.length, 40);

const appSrc = read(join(SRC, 'App.tsx'));
const appRoutes = [];
for (const m of appSrc.matchAll(/<Route\s+path="([^"]+)"/g)) appRoutes.push(m[1]);
expectAtLeast('App.tsx 静态路由', appRoutes.length, 15);

/** path → 页面组件名（仅静态路由；RESOURCES 是动态 map，跳过）。 */
const pathToComponent = new Map();
for (const m of appSrc.matchAll(/<Route\s+path="([^"]+)"\s+element=\{<(\w+)/g)) {
  pathToComponent.set(m[1], m[2]);
}
/** 该页面是否用 usePermByPath 判定：只有这类页面「不在镜像里」才真的会让门槛静默失效。 */
const componentUsesByPath = (name) => {
  const hit = walk(SRC, ['.tsx', '.ts']).find(
    (f) => new RegExp(`export function ${name}\\b`).test(read(f)) || new RegExp(`export const ${name}\\b`).test(read(f)),
  );
  return hit ? /usePermByPath\(\)/.test(read(hit)) : null;
};

const normalize = (p) => (p.startsWith('/') ? p : `/${p}`);
// App.tsx 里的路由是相对父级 '/' 的，故统一补前导斜杠；'index' 表示父路由本身
const appPaths = new Set(appRoutes.map((p) => (p === 'index' ? '/' : normalize(p))));

// 3. 镜像完整性
//    RESOURCES 的路由全部由 ResourcePage 渲染，而 ResourcePage 一律用 by-path 判定，
//    因此缺一条 = 那一页的按钮门槛静默失效 → 硬失败。
for (const k of resourceKeys) {
  if (!pathToCode.has(normalize(k))) {
    fail(`RESOURCES 的 ${normalize(k)} 不在 PATH_TO_CODE 镜像里 —— 该页按钮门槛会静默失效`);
  }
}
//    独立页面只在**确实用了 by-path 判定**时才硬失败：`/help` 这类非菜单页本就没有
//    menu 行（镜像由 V45 生成，只含菜单路径），把它一并判死会给出无法修复的失败。
for (const p of standalone) {
  const path = normalize(p);
  if (pathToCode.has(path)) continue;
  const comp = pathToComponent.get(p) ?? pathToComponent.get(p.replace(/^\//, ''));
  const uses = comp ? componentUsesByPath(comp) : null;
  if (uses === true) {
    fail(`${path} 不在 PATH_TO_CODE 镜像里，但 ${comp} 使用了 usePermByPath —— 该页门槛会静默失效`);
  } else {
    warn(
      `${path}（${comp ?? '动态/未知组件'}）不在 PATH_TO_CODE 镜像里` +
        `${uses === false ? '（该组件未用 by-path 判定，仅提示）' : '（无法判定是否使用 by-path，请人工确认）'}`,
    );
  }
}

// 4. 双写一致性（告警：无法自动区分钻取路由与漏登记）
for (const p of standalone) {
  if (!appPaths.has(normalize(p))) {
    fail(`STANDALONE_ROUTES 的 ${normalize(p)} 在 App.tsx 里没有对应 <Route> —— 侧栏会出现点进去落回首页的入口`);
  }
}
const known = new Set([...standalone.map(normalize), ...resourceKeys.map(normalize)]);
const registered = [...known];
const candidates = [...appPaths].filter(
  (p) =>
    !known.has(p) &&
    !p.includes(':') &&
    p !== '*' &&
    p !== '/*' &&
    p !== '/login' && // 登录页在 AdminLayout 之外，本就不是菜单项
    // 已登记列表路由下的静态子页（/assets/create、/projects/create 等）是从列表钻取的表单页，
    // 不是菜单项；用「前缀属于某个已登记路由」把它们排除，避免告警被噪声淹没
    !registered.some((r) => r !== '/' && p.startsWith(`${r}/`)),
);
if (candidates.length) {
  warn(
    `App.tsx 里这些静态路由既不在 STANDALONE_ROUTES 也不在 RESOURCES：${candidates.sort().join(', ')}\n` +
      `          → 若是侧栏可达的独立页面则漏登记（DB 菜单行会被当脏数据忽略）；若是钻取/详情页可忽略`,
  );
}

// 镜像多余条目：DB 里有、前端未登记，属正常（会被忽略并 console.warn），仅提示
const extra = [...pathToCode.keys()].filter((p) => !known.has(p) && !p.includes(':'));
if (extra.length) {
  infos.push(`镜像里前端未登记的路由 ${extra.length} 个（DB 有、前端未注册，会被忽略）：${extra.sort().join(', ')}`);
}

// ---------------------------------------------------------------------------
// 输出
// ---------------------------------------------------------------------------
const line = (label, list) => {
  if (!list.length) return;
  console.log(`\n${label}`);
  for (const m of list) console.log(`  - ${m}`);
};

console.log('权限与路由一致性检查');
console.log(`  后端 @RequiresPerm : ${enforced.size} 个`);
console.log(`  前端 perm 声明     : ${declared.length} 处（去重 ${new Set(declared.map((d) => d.code)).size} 个码）`);
console.log(`  测试夹具权限码     : ${fixtureCodes.size} 个`);
console.log(`  PATH_TO_CODE       : ${pathToCode.size} 条`);
console.log(`  RESOURCES          : ${resourceKeys.length} 个`);
console.log(`  STANDALONE_ROUTES  : ${standalone.length} 条`);
console.log(`  App.tsx 静态路由   : ${appPaths.size} 条`);

line('提示', infos);
line('告警', warnings);
line('失败', failures);

if (failures.length) {
  console.log(`\n检查未通过：${failures.length} 项失败\n`);
  process.exit(1);
}
console.log(`\n检查通过${warnings.length ? `（${warnings.length} 条告警需人工确认）` : ''}\n`);
