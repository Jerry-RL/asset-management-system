import { useCallback } from 'react';
import { useAuth } from './auth';
import type { LoginUser } from './api';

/**
 * 权限判定（设计 4.1 / 6.2）。
 *
 * <p>判定码固定为 `menuCode:action`，**精确匹配**，不做前缀匹配 ——
 * 旧实现按 `code + ':'` 前缀放行，会让「只有 `asset.ledger:create`」的账号
 * 通过 `asset.ledger:view` 的判定。`super_admin` 恒真（与后端 6.1 的口径一致）。
 *
 * <p>注意这是**前端体验层**判定：数据来自登录时写入的权限快照，
 * 只用于隐藏按钮/菜单，不作为安全边界。真正的边界在后端 `@RequiresPerm` 拦截器。
 */
export function hasPerm(user: LoginUser | null | undefined, permission: string): boolean {
  if (!user) return false;
  if (user.superAdmin) return true;
  return (user.permissions ?? []).includes(permission);
}

/** 组件内使用：返回 `(permission) => boolean`。 */
export function usePerm(): (permission: string) => boolean {
  const { user } = useAuth();
  return useCallback((permission: string) => hasPerm(user, permission), [user]);
}
