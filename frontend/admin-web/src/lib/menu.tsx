import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { AppstoreOutlined } from '@ant-design/icons';
import { api } from './api';
import { useAuth } from './auth';
import { getIconByName } from './menuIcons';
import { MENU, type MenuGroup, type MenuItem } from '@/pages/modules';
import {
  checkCodeMappingDrift,
  isPathViewable,
  isRegisteredRoute,
  routeTitle,
} from './routeRegistry';

/**
 * 侧边栏菜单数据源（设计 3.4）。
 *
 * <p>**DB 决定「显示哪些、顺序、命名」，前端注册表决定「是否存在、怎么渲染」**：
 * 顺序与命名直接取接口返回的树，图标以 `menu.icon` 为权威（经 `ICON_BY_NAME` 解析），
 * 只在 `menu.icon` 为空时才回退注册表的 path 图标。
 */

/** 后端 `MenuNode`。 */
export interface ApiMenuNode {
  id: number;
  parentId: number | null;
  name: string;
  code: string | null;
  path: string | null;
  icon: string | null;
  sort: number | null;
  menuType: string;
  status: number | null;
  children?: ApiMenuNode[];
}

/**
 * 侧边栏状态。
 *
 * <p>「空树」与「请求失败」是**两件事**：前者是「该用户确实没有任何页面权限」的合法结果，
 * 必须渲染空导航态；后者才回退静态菜单。混同二者会把无权限用户放进 24 组 / 66 个入口里，
 * 点进去全是 403。
 */
export type MenuStatus = 'loading' | 'ready' | 'empty' | 'fallback';

export interface MenuState {
  status: MenuStatus;
  /** 侧边栏分组（已按注册表裁剪、按 DB 顺序）。 */
  groups: MenuGroup[];
  /** 降级原因（仅 status === 'fallback' 时有值），用于顶栏提示与日志。 */
  fallbackReason: string | null;
  /** 重新拉取（保存权限后、窗口聚焦、路由跳转时调用）。 */
  reload: () => Promise<void>;
  /** 菜单是否正在后台刷新（保留旧数据，仅用于轻量提示）。 */
  refreshing: boolean;
}

const MenuContext = createContext<MenuState | null>(null);

/** 侧栏数据是否已就绪到可以渲染（loading 时画骨架）。 */
export const isMenuRenderable = (status: MenuStatus): boolean =>
  status === 'ready' || status === 'empty' || status === 'fallback';

/** DB 树 → 侧边栏分组；未注册路径在此处被丢弃并记日志。 */
function toGroups(tree: ApiMenuNode[]): MenuGroup[] {
  const groups: MenuGroup[] = [];
  const unregistered: string[] = [];
  for (const dir of tree) {
    if (dir.menuType !== 'dir') continue;
    if (dir.status === 0) continue; // 停用目录 = 隐藏整组（后端已过滤，双保险）
    const items: MenuItem[] = [];
    for (const node of dir.children ?? []) {
      if (node.menuType !== 'menu') continue;
      if (node.status === 0) continue;
      const path = node.path;
      if (!path) continue;
      // DB 里存在但注册表缺失 → 忽略并记日志，避免脏数据渲染出点进去 404 的入口
      if (!isRegisteredRoute(path)) {
        unregistered.push(`${dir.name} / ${node.name} → ${path}`);
        continue;
      }
      items.push({
        path,
        title: node.name || routeTitle(path),
        icon: getIconByName(node.icon),
        code: node.code ?? undefined,
      });
    }
    if (!items.length) continue; // 全部子项不可见时整组不显示
    groups.push({
      title: dir.name,
      icon: getIconByName(dir.icon),
      items,
    });
  }
  if (unregistered.length) {
    console.warn('[menu] 以下 DB 菜单未在前端路由注册表中，已忽略：', unregistered);
  }
  return groups;
}

/**
 * 接口失败时的降级菜单：静态 `MENU` 按 `permissions` 过滤（验收第 2 条）。
 *
 * <p>不填 `icon`：静态 `MENU` 本就没有 DB 图标，留空即让渲染处回退到注册表图标 ——
 * 图标优先级规则只在渲染处实现一次。
 */
function fallbackGroups(permissions: readonly string[]): MenuGroup[] {
  return MENU.map((group) => ({
    title: group.title,
    items: group.items
      .filter((item) => isPathViewable(item.path, permissions))
      .map((item) => ({ path: item.path, title: item.title })),
  })).filter((group) => group.items.length > 0);
}

/** 从接口树里收集 `code → path`，供镜像漂移校验使用。 */
function collectCodePairs(tree: ApiMenuNode[]): Array<[string, string]> {
  const pairs: Array<[string, string]> = [];
  for (const dir of tree) {
    for (const node of dir.children ?? []) {
      if (node.code && node.path) pairs.push([node.code, node.path]);
    }
  }
  return pairs;
}

export function MenuProvider({ children }: { children: React.ReactNode }) {
  const { user, token } = useAuth();
  const [status, setStatus] = useState<MenuStatus>('loading');
  const [groups, setGroups] = useState<MenuGroup[]>([]);
  const [fallbackReason, setFallbackReason] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  /** 首次加载 vs 后台刷新：刷新时保留旧数据，避免侧栏闪骨架 */
  const loadedRef = useRef(false);
  /** 并发保护：窗口聚焦 + 路由跳转可能同时触发，只认最后一次请求的结果 */
  const requestIdRef = useRef(0);

  const load = useCallback(async () => {
    if (!token) return;
    const requestId = ++requestIdRef.current;
    if (loadedRef.current) setRefreshing(true);
    try {
      const tree = await api.get<ApiMenuNode[]>('/system/menus');
      if (requestId !== requestIdRef.current) return;
      checkCodeMappingDrift(collectCodePairs(tree));
      const next = toGroups(tree);
      setGroups(next);
      // 成功但为空 = 用户确实没有任何页面权限：渲染空导航态，绝不回退静态菜单
      setStatus(next.length ? 'ready' : 'empty');
      setFallbackReason(null);
      loadedRef.current = true;
    } catch (err) {
      if (requestId !== requestIdRef.current) return;
      const reason = err instanceof Error ? err.message : String(err);
      const permissions = user?.permissions ?? [];
      setGroups(fallbackGroups(permissions));
      setStatus('fallback');
      setFallbackReason(reason);
      // 记录降级：菜单内容不再来自 DB，排查「菜单不对」时必须能看到这一点
      console.warn('[menu] /system/menus 加载失败，已回退静态菜单并按 permissions 过滤：', reason);
    } finally {
      if (requestId === requestIdRef.current) setRefreshing(false);
    }
  }, [token, user?.permissions]);

  // 登录态变化（含首次登录）立即拉取
  useEffect(() => {
    if (!token) {
      loadedRef.current = false;
      setStatus('loading');
      setGroups([]);
      setFallbackReason(null);
      return;
    }
    void load();
  }, [token, load]);

  /**
   * 权限快照刷新契约（设计 6.2）：登录时取得后不会自动变化，必须主动刷新。
   *
   * <p>「保存权限的当前管理员」由角色权限页保存成功后显式调 {@link MenuState.reload}；
   * 「其他已登录会话」在此处按**下次路由跳转**或**窗口获得焦点**刷新。不做轮询、不做推送。
   */
  useEffect(() => {
    if (!token) return;
    const onFocus = () => void load();
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [token, load]);

  const value = useMemo<MenuState>(
    () => ({ status, groups, fallbackReason, reload: load, refreshing }),
    [status, groups, fallbackReason, load, refreshing],
  );
  return <MenuContext.Provider value={value}>{children}</MenuContext.Provider>;
}

export function useMenu(): MenuState {
  const ctx = useContext(MenuContext);
  if (!ctx) throw new Error('useMenu 必须在 MenuProvider 内使用');
  return ctx;
}

/** 侧栏骨架（请求进行中） */
export function MenuSkeleton({ collapsed }: { collapsed: boolean }) {
  if (collapsed) {
    return (
      <div className="flex flex-col items-center gap-2 py-2" aria-hidden>
        {Array.from({ length: 6 }).map((_, i) => (
          <div key={i} className="w-7 h-7 rounded bg-gray-100 animate-pulse" />
        ))}
      </div>
    );
  }
  return (
    <div className="px-3 py-2 space-y-3" aria-hidden>
      {Array.from({ length: 4 }).map((_, g) => (
        <div key={g} className="space-y-1.5">
          <div className="h-3 w-24 rounded bg-gray-100 animate-pulse" />
          <div className="h-3 w-32 rounded bg-gray-100/80 animate-pulse" />
          <div className="h-3 w-28 rounded bg-gray-100/80 animate-pulse" />
        </div>
      ))}
    </div>
  );
}

/** 空导航态（接口成功但该用户无任何页面权限） */
export function MenuEmpty({ collapsed }: { collapsed: boolean }) {
  if (collapsed) {
    return (
      <div className="flex justify-center py-4 text-gray-300" title="暂无可用菜单">
        <AppstoreOutlined />
      </div>
    );
  }
  return (
    <div className="px-4 py-6 text-center">
      <div className="text-2xl text-gray-300 mb-2">
        <AppstoreOutlined />
      </div>
      <div className="text-xs text-[var(--ams-text-secondary)] leading-5">
        暂无可用菜单
        <br />
        请联系管理员分配页面权限
      </div>
    </div>
  );
}

/** 降级提示条（接口失败、回退静态菜单时显示） */
export function MenuFallbackNotice({ collapsed }: { collapsed: boolean }) {
  if (collapsed) return null;
  return (
    <div
      role="status"
      className="mx-2 mb-2 rounded border border-amber-200 bg-amber-50 px-2 py-1.5 text-[10px] leading-4 text-amber-700"
      title="菜单来自本地静态配置，可能与服务端不一致"
    >
      菜单加载失败，当前展示本地备用菜单
    </div>
  );
}
