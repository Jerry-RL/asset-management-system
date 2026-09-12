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
  '/project-zones',
  '/reports',
  '/approvals',
  '/payments/pending-confirm',
  '/dunning/auto',
  '/intelligence/reports',
  '/contract-templates',
  '/system/dict',
  '/system/menus',
  '/system/roles',
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
 * <p><strong>`titleByPath` 不是注册来源</strong>：静态 `MENU` 覆盖了全部 65 个 path，
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

export const isRegisteredRoute = (path: string): boolean => Boolean(path) && path in ROUTE_REGISTRY;

/** 标题：注册表兜底（DB 的 name 优先，仅在缺失时才用这里）。 */
export const routeTitle = (path: string): string => ROUTE_REGISTRY[path]?.title ?? path;
