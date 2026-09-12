import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Dropdown } from 'antd';
import type { MenuProps } from 'antd';
import { MoreOutlined } from '@ant-design/icons';
import { cn } from '@/lib/utils';
import { useAuth } from '@/lib/auth';
import { hasPerm } from '@/lib/perm';

/** 操作列中的单个操作 */
export interface TableActionItem {
  key: string;
  label: string;
  /** 链接左侧图标，建议使用 @ant-design/icons 的 12-14px 图标 */
  icon?: ReactNode;
  /**
   * 该操作要求的权限，`menuCode:action` 完整判定码（设计 6.2）。
   *
   * <p>这里的调用方是独立页面，没有 `ResourcePage` 那种「由当前路由派生 menuCode」的上下文，
   * 因此必须写全码。声明后由 `TableActions` 统一剔除无权项，不必每个调用点自己算 `hidden`。
   * **未声明 = 不判定**（保持既有行为）：自动推导动作类型做不到 —— 同一张表里的
   * 操作可能是 view/export/approve 中的任意一种，猜错会把可用按钮藏掉。
   */
  perm?: string;
  /** 危险操作（删除/驳回等）使用红色 */
  danger?: boolean;
  disabled?: boolean;
  hidden?: boolean;
  /** 路由跳转；设置后渲染为 react-router 的 Link，否则渲染为 button */
  to?: string;
  /** 在下拉菜单中与上一项之间插入分割线 */
  dividerBefore?: boolean;
  onClick?: () => void;
}

export interface TableActionsProps {
  /** 优先内联展示的操作，超出 max 的自动并入「更多」 */
  actions?: TableActionItem[];
  /** 始终收进「更多」下拉的操作 */
  more?: TableActionItem[];
  /** 内联展示的最大数量，默认 2 */
  max?: number;
  className?: string;
}

const menuItemOf = (action: TableActionItem): NonNullable<MenuProps['items']>[number] => ({
  key: action.key,
  label: action.label,
  icon: action.icon,
  danger: action.danger,
  disabled: action.disabled,
  onClick: action.onClick,
});

/** 按需在菜单项之间插入分割线 */
const menuItemsOf = (actions: TableActionItem[]): MenuProps['items'] => {
  const items: MenuProps['items'] = [];
  actions.forEach((action, index) => {
    if (action.dividerBefore && index > 0) items.push({ type: 'divider' });
    items.push(menuItemOf(action));
  });
  return items;
};

const linkClassOf = (action: TableActionItem) =>
  cn(
    'ams-action-link',
    action.danger && 'is-danger',
    action.disabled && 'is-disabled',
  );

/**
 * 统一「操作」列：内联文字链接（含图标），超出部分与次要操作收进「更多」下拉。
 * 点击默认阻止冒泡，避免触发行点击（如打开详情抽屉）。
 */
export const TableActions = ({ actions = [], more = [], max = 2, className }: TableActionsProps) => {
  const { user } = useAuth();
  // 声明了 perm 的项在此处统一剔除（设计 6.2）：调用点只需声明，不必各自算 hidden
  const permitted = (a: TableActionItem) => !a.perm || hasPerm(user, a.perm);
  const visible = actions.filter((a) => !a.hidden && permitted(a));
  const inline = visible.slice(0, max);
  const moreItems = menuItemsOf([
    ...visible.slice(max),
    ...more.filter((a) => !a.hidden && permitted(a)),
  ]);

  if (inline.length === 0 && (moreItems?.length ?? 0) === 0) return null;

  return (
    <span className={cn('ams-actions', className)} onClick={(e) => e.stopPropagation()}>
      {inline.map((action) =>
        action.to ? (
          <Link
            key={action.key}
            to={action.to}
            className={linkClassOf(action)}
            aria-label={action.label}
            tabIndex={0}
          >
            {action.icon}
            {action.label}
          </Link>
        ) : (
          <button
            key={action.key}
            type="button"
            className={linkClassOf(action)}
            aria-label={action.label}
            disabled={action.disabled}
            onClick={(e) => {
              e.stopPropagation();
              action.onClick?.();
            }}
          >
            {action.icon}
            {action.label}
          </button>
        ),
      )}
      {(moreItems?.length ?? 0) > 0 && (
        <Dropdown menu={{ items: moreItems }} trigger={['click']} placement="bottomRight">
          <button
            type="button"
            className="ams-action-link"
            aria-label="更多操作"
            onClick={(e) => e.stopPropagation()}
          >
            更多
            <MoreOutlined />
          </button>
        </Dropdown>
      )}
    </span>
  );
};
