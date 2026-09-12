import { useCallback, useEffect, useRef, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { useAuth } from './auth';
import type { LoginUser } from './api';
import { codeForPath } from './pathToCode';
import { ForbiddenNotice } from '@/components/ForbiddenNotice';

/**
 * 固定动作词表，与后端 `PermissionAction` **逐字对齐**。
 *
 * <p>写成联合类型而不是 `string`，是为了让「写错动作名」变成编译错误：
 * 后端词表是闭集，前端传 `'remove'` 之类的自由字符串只会静默判定为无权，
 * 表现为按钮消失，排查成本远高于编译报错。
 */
export type PermAction =
  'view' | 'create' | 'update' | 'delete' | 'export' | 'import' | 'approve' | 'audit' | 'assign';

/**
 * 权限判定（设计 4.1 / 6.2）：`menuCode:action` **精确匹配**。
 *
 * <p>旧实现按 `code + ':'` 前缀放行，会让「只有 `asset.ledger:create`」的账号
 * 通过 `asset.ledger:view` 的判定。`super_admin` 恒真（与后端 6.1 口径一致）。
 *
 * <p>**空 `menuCode` 一律判否**，不在这里做「无法判定就放行」：那会把
 * 「注册表漏登某个 path」变成静默越权。需要放行的调用方用 {@link canByPath}，
 * 让「放行」出现在调用点上而不是藏在判定函数里。
 *
 * <p>注意这是**前端体验层**判定：数据来自登录时写入的权限快照，只用于隐藏按钮/菜单，
 * 不作为安全边界。真正的边界在后端 `@RequiresPerm` 拦截器。
 */
export function can(
  user: LoginUser | null | undefined,
  menuCode: string | null | undefined,
  action: PermAction,
): boolean {
  if (!user) return false;
  if (user.superAdmin) return true;
  if (!menuCode) return false;
  return (user.permissions ?? []).includes(`${menuCode}:${action}`);
}

/**
 * 按 `menuCode:action` 字符串判定（配置里声明权限时用）。
 *
 * <p>解析用 `lastIndexOf`：`menuCode` 本身含点号（`asset.ledger`），
 * 用 `indexOf` 会把第一个点当成动作分隔符，判成 `asset` + `ledger:update`。
 */
export function hasPerm(user: LoginUser | null | undefined, permission: string): boolean {
  const idx = permission.lastIndexOf(':');
  if (idx <= 0) return false;
  return can(user, permission.slice(0, idx), permission.slice(idx + 1) as PermAction);
}

/**
 * 按**当前路由 path** 判定：先经 `PATH_TO_CODE` 镜像解析出 `menuCode`。
 *
 * <p>镜像里没有该 path 时**放行** —— 这通常意味着它不是菜单项（`/assets/:assetId/dossier`、
 * `/projects/:id/edit` 这类钻取路由），按权限隐藏它们上面的按钮会变成与权限无关的功能缺失。
 * 安全边界仍在后端：已接入接口一律 `@RequiresPerm`，未接入的由 `strict-perm` 兜底。
 */
export function canByPath(
  user: LoginUser | null | undefined,
  path: string,
  action: PermAction,
): boolean {
  const code = codeForPath(path);
  if (!code) return true;
  return can(user, code, action);
}

/** 组件内使用：返回 `can(menuCode, action)`（设计 6.2 的签名）。 */
export function usePerm(): (menuCode: string, action: PermAction) => boolean {
  const { user } = useAuth();
  return useCallback((menuCode: string, action: PermAction) => can(user, menuCode, action), [user]);
}

/** 组件内使用：返回按当前路由 path 判定的 `(action) => boolean`。 */
export function usePermByPath(): (action: PermAction) => boolean {
  const { user } = useAuth();
  const location = useLocation();
  return useCallback(
    (action: PermAction) => canByPath(user, location.pathname, action),
    [user, location.pathname],
  );
}

type GuardProps = {
  fallback?: ReactNode;
  children: ReactNode;
} & (
  | { perm: string; code?: never; action?: never }
  | { perm?: never; code: string; action: PermAction }
);

/**
 * 按钮级权限包裹（设计 6.2）：无权时不渲染 `children`，可直接嵌进工具栏/操作列。
 *
 * <p>`perm` / `code + action` 二者必居其一，由联合类型在编译期保证 ——
 * 两者都不给时组件无法判断，静默隐藏整块 UI 的故障现场极难定位。
 */
export function PermissionGuard({ perm, code, action, fallback = null, children }: GuardProps) {
  const { user } = useAuth();
  const allowed = perm ? hasPerm(user, perm) : can(user, code, action as PermAction);
  return <>{allowed ? children : fallback}</>;
}

/** 快照刷新节流窗口（设计 6.2）：聚焦/跳转可能高频触发，不做节流会打出请求风暴。 */
export const REFRESH_MIN_INTERVAL_MS = 30_000;

/**
 * 权限快照刷新契约（设计 6.2）：**其他已登录会话**在下次路由跳转或窗口获得焦点时重新拉取
 * `/system/me`。保存权限的当前管理员由角色权限页保存成功后显式刷新，不依赖本组件。
 *
 * <p>不做轮询、不做推送。`refreshUser` 发现快照无变化时不写入 state（见 `auth.tsx`），
 * 因此这里没有变化就不会级联触发菜单树重拉。
 *
 * <p>节流窗口见 {@link REFRESH_MIN_INTERVAL_MS}：设计只写了「跳转或聚焦时刷新」，
 * 未限定频率，而窗口聚焦在实际使用中一次切换就是一次事件；不节流会退化成
 * 「每次 alt-tab 两个请求」，且失败时还会连续叠加。
 */
export function PermissionSnapshotRefresher() {
  const { token, refreshUser } = useAuth();
  const location = useLocation();
  const lastRef = useRef(0);

  const maybeRefresh = useCallback(() => {
    if (!token) return;
    const now = Date.now();
    if (now - lastRef.current < REFRESH_MIN_INTERVAL_MS) return;
    lastRef.current = now;
    // 刷新失败不打断页面：菜单侧已有降级态，权限快照保持旧值比整页报错更可用
    refreshUser().catch((err: unknown) => {
      console.warn('[perm] 权限快照刷新失败，本次沿用旧快照：', err);
    });
  }, [token, refreshUser]);

  useEffect(() => {
    maybeRefresh();
  }, [location.pathname, maybeRefresh]);

  useEffect(() => {
    const onFocus = () => maybeRefresh();
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [maybeRefresh]);

  return null;
}

/**
 * 页面级路由守卫（设计 6.2）：无 `code:view` 渲染 403，而不是让页面自己请求一堆接口拿 403。
 *
 * <p>`code` 可省略，此时从当前 path 经镜像推导；推导不出（非菜单路由）则不做页面级判定。
 *
 * <p>未取到 `user` 时放行：`token` 存在但快照缺失属于本地会话损坏，此时整站 403
 * 会让人以为是权限配错，而真实边界在后端（会正常 401/403 并把用户送回登录页）。
 */
export function RequirePerm({ code, children }: { code?: string; children: ReactNode }) {
  const { user } = useAuth();
  const location = useLocation();
  const menuCode = code ?? codeForPath(location.pathname);
  if (!menuCode || !user) return <>{children}</>;
  if (can(user, menuCode, 'view')) return <>{children}</>;
  return <ForbiddenNotice menuCode={menuCode} />;
}
