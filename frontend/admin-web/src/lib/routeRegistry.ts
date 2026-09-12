import { MENU, RESOURCES } from '@/pages/modules';

/**
 * 路由注册表（设计 3.4）：前端「路由 → 组件」的编译期真相。
 *
 * <p>职责边界：**DB 决定「显示哪些、顺序、命名」，注册表决定「页面是否存在、怎么渲染」**。
 * 侧边栏只渲染注册表里存在的 path —— DB 里的脏数据（写错路径、页面还没发布）会被忽略并记日志，
 * 而不是渲染成点进去 404 的入口。菜单管理页用 {@link isRegisteredRoute} 给未注册路径打告警标记。
 *
 * <p>新增页面仍需在前端注册表变更并随版本发布：DB 无法凭空让一个未注册的页面可访问。
 */

/**
 * 拥有独立页面组件（非 `ResourcePage` 渲染）的侧栏可见路由。
 *
 * <p><strong>必须与 `App.tsx` 的 `<Route>` 保持一致</strong>：这里少一条，该菜单就不会出现在
 * 侧边栏；多一条，则会出现点进去落回首页的入口。`ResourcePage` 渲染的路由**不在此列**，
 * 它们由 `RESOURCES` 自动登记。
 *
 * <p>仅列出侧栏可达的页面：`/assets/:assetId/dossier`、`/projects/:id` 这类详情路由是
 * 从列表页钻取进入的，不是菜单项，故不登记（否则它们会赢得注册表校验、让告警标记失效）。
 */
export const STANDALONE_ROUTES: string[] = [
  '/',
  '/help',
  '/dashboard',
  '/dashboard/consolidate',
  '/ops-calendar',
  '/asset-map',
  '/reports',
  '/approvals',
  '/payments/pending-confirm',
  '/dunning/auto',
  '/intelligence/reports',
  '/contract-templates',
  '/system/dict',
  '/system/menus',
  '/org/structure',
];

export interface RouteMeta {
  path: string;
  /** 标题兜底：DB 的 `menu.name` 为空或该节点只在静态菜单里时才用 */
  title: string;
}

/**
 * 标题兜底表：由静态 `MENU` 生成。
 *
 * <p>静态 `MENU` 里 `/tenants`、`/dashboard/consolidate` 各出现两次（分属两个分组），
 * 建表时**以首次出现为准**并跳过重复 —— DB 菜单表每个 path 只有一行，兜底表也必须一一对应，
 * 否则同一个 path 在两个分组下会显示两个不同标题。
 */
const titleByPath: Record<string, string> = (() => {
  const map: Record<string, string> = { '/help': '用户手册' };
  for (const group of MENU) {
    for (const item of group.items) {
      if (!(item.path in map)) map[item.path] = item.title;
    }
  }
  return map;
})();

/**
 * 路由注册表：`ResourcePage` 路由 + 独立页面路由。
 *
 * <p><strong>`titleByPath` 不是注册来源</strong>：静态 `MENU` 覆盖了全部 64 个 path，
 * 若把它算作「已注册」，`isRegisteredRoute` 会恒为真，菜单管理页的「未在路由注册表中」
 * 告警就永远不会触发。它只提供标题兜底。
 */
export const ROUTE_REGISTRY: Record<string, RouteMeta> = (() => {
  const registry: Record<string, RouteMeta> = {};
  const register = (path: string) => {
    const normalized = path.startsWith('/') ? path : `/${path}`;
    registry[normalized] = { path: normalized, title: titleByPath[normalized] ?? normalized };
  };
  for (const path of Object.keys(RESOURCES)) register(path);
  for (const path of STANDALONE_ROUTES) register(path);
  return registry;
})();

/** 所有已登记路由（排序后，便于比对）。 */
export const REGISTERED_ROUTES: string[] = Object.keys(ROUTE_REGISTRY).sort();

export const isRegisteredRoute = (path: string): boolean =>
  Boolean(path) && path in ROUTE_REGISTRY;

/** 标题：注册表兜底（DB 的 name 优先，仅在缺失时才用这里）。 */
export const routeTitle = (path: string): string => ROUTE_REGISTRY[path]?.title ?? path;

/**
 * `path → menuCode` 镜像（**由 `V45__menu_tree_and_role_data_scope.sql` 生成，勿手改**）。
 *
 * <p>存在的唯一理由：`/system/menus` 请求失败时要「回退静态菜单并**按 permissions 过滤**」
 * （验收第 2 条）。静态 `MENU` 只有 path / title，没有权限码，而过滤必须用
 * `code:view` 判定 —— 没有这张表就只能把整个静态菜单原样显示给无权限账号。
 *
 * <p><strong>它是一份镜像，因此必须防漂移</strong>：{@link checkCodeMappingDrift} 在
 * `/system/menus` 成功加载后逐条比对，不一致时在控制台告警并列出差异。
 * 漂移的后果被限制在「接口失败 + 首屏无缓存」这一条罕见路径上：
 * 缺条目 → 该菜单在降级态不显示（安全侧）；多条目 → 点进去 403（后端仍会拦）。
 */
export const PATH_TO_CODE: Record<string, string> = {
  // ---- 首页与工作台（home / icon=app） ----
  '/': 'home.center',
  '/ops-calendar': 'home.calendar',
  '/dashboard': 'home.dashboard',
  '/dashboard/consolidate': 'home.consolidate',
  // ---- 经营分析（analysis / icon=chart） ----
  '/business-plans': 'analysis.plan',
  '/reports': 'analysis.report',
  // ---- 风险管控（risk / icon=alert） ----
  '/alerts/rules': 'risk.rule',
  '/alerts/records': 'risk.record',
  // ---- 资产台账（asset / icon=ledger） ----
  '/projects': 'asset.project',
  '/assets': 'asset.ledger',
  '/assets/structure-logs': 'asset.structureLog',
  '/asset-map': 'asset.map',
  // ---- 资债权证（deed / icon=certificate） ----
  '/certificates': 'deed.certificate',
  '/mortgages': 'deed.mortgage',
  '/asset-transfers': 'deed.transfer',
  '/evaluations': 'deed.evaluation',
  // ---- 资产招租（lease / icon=listing） ----
  '/lease-listings': 'lease.listing',
  '/tender/announcements': 'lease.tender',
  // ---- 资产运营（operation / icon=operation） ----
  '/disposals': 'operation.disposal',
  '/occupations': 'operation.occupation',
  '/self-uses': 'operation.selfUse',
  '/asset-audits': 'operation.audit',
  // ---- 合同管理（contract / icon=contract） ----
  '/contracts': 'contract.ledger',
  '/vacate-orders': 'contract.vacate',
  '/lease-bundles': 'contract.bundle',
  '/contract-templates': 'contract.template',
  // ---- 定价与计费（billing / icon=bill） ----
  '/billing/bills': 'billing.bill',
  '/meters': 'billing.meter',
  '/apportion-configs': 'billing.apportion',
  '/adjustments/fee-reliefs': 'billing.relief',
  '/adjustments/rent-adjusts': 'billing.rentAdjust',
  // ---- 收费与发票（finance / icon=payment） ----
  '/payments': 'finance.payment',
  '/payments/pending-confirm': 'finance.paymentConfirm',
  '/refunds': 'finance.refund',
  '/invoices': 'finance.invoice',
  '/invoice-tax-rates': 'finance.taxRate',
  '/finance/bank-flows': 'finance.bankFlow',
  '/finance/vouchers': 'finance.voucher',
  // ---- 履约催缴（dunning / icon=dunning） ----
  '/dunning/records': 'dunning.record',
  '/dunning/auto': 'dunning.auto',
  // ---- 巡检维修（maintenance / icon=repair） ----
  '/repairs': 'maintenance.repair',
  '/vendors': 'maintenance.vendor',
  '/inspections': 'maintenance.inspection',
  // ---- 任务中心（task / icon=task） ----
  '/tasks': 'task.manage',
  '/approvals': 'task.approval',
  // ---- 空置盘活（revitalize / icon=revitalize） ----
  '/revitalization': 'revitalize.task',
  // ---- 合规监管（compliance / icon=report） ----
  '/regulation/reports': 'compliance.report',
  // ---- 消息待办（message / icon=notify） ----
  '/notifications': 'message.notify',
  // ---- 固定资产（fixedasset / icon=fixedasset） ----
  '/fixed-assets': 'fixedasset.ledger',
  '/fixed-assets/inventories': 'fixedasset.inventory',
  // ---- 无形资产（intangible / icon=intangible） ----
  '/intangible-assets': 'intangible.ledger',
  // ---- 组织架构（org / icon=org） ----
  '/org/structure': 'org.structure',
  '/org/companies': 'org.company',
  '/org/departments': 'org.department',
  '/system/users': 'org.user',
  // ---- 运营管理（ops / icon=tenant） ----
  '/tenants': 'ops.tenant',
  // ---- 系统配置（config / icon=config） ----
  '/config/versions': 'config.version',
  // ---- 系统管理（system / icon=setting） ----
  '/system/roles': 'system.role',
  '/system/menus': 'system.menu',
  '/system/dict': 'system.dict',
  // ---- 智能中心（intelligence / icon=ai） ----
  '/intelligence/templates': 'intelligence.template',
  '/intelligence/reports': 'intelligence.report',
  '/intelligence/sessions': 'intelligence.session',
  // ---- 期初迁移（migration / icon=migration） ----
  '/migrations/batches': 'migration.batch',
};

export const codeForPath = (path: string): string | undefined => PATH_TO_CODE[path];

/** 该 path 是否被授予了 `code:view`（静态降级态专用判定）。 */
export const isPathViewable = (path: string, permissions: readonly string[]): boolean => {
  const code = PATH_TO_CODE[path];
  // 镜像缺失时保守放行：不显示菜单比显示一个可能 403 的入口更难排查，且后端仍会拦截
  if (!code) return true;
  return permissions.includes(`${code}:view`);
};

/**
 * 校验 `PATH_TO_CODE` 镜像与接口返回的 `code → path` 是否一致。
 *
 * <p>只在开发环境告警，不抛错：镜像漂移只影响「接口失败时的降级菜单」，
 * 让整个页面因为这个次要路径挂掉是不划算的。但它必须**可见**，否则会静默过期。
 *
 * @param apiPairs 接口菜单树里收集到的 `[code, path]`
 */
export function checkCodeMappingDrift(apiPairs: Array<[string, string]>): void {
  if (import.meta.env.PROD) return;
  const apiByPath = new Map(apiPairs);
  const missing: string[] = [];
  const mismatched: string[] = [];
  for (const [path, code] of apiByPath) {
    const mirrored = PATH_TO_CODE[path];
    if (mirrored === undefined) missing.push(`${path} → ${code}`);
    else if (mirrored !== code) mismatched.push(`${path}: 镜像=${mirrored} 接口=${code}`);
  }
  const extra = Object.keys(PATH_TO_CODE).filter((path) => !apiByPath.has(path));
  if (!missing.length && !mismatched.length && !extra.length) return;
  console.warn(
    '[routeRegistry] PATH_TO_CODE 镜像与接口菜单不一致，请同步 V45 种子后重新生成：',
    { 镜像缺失: missing, 编码不一致: mismatched, 多余条目: extra },
  );
}
