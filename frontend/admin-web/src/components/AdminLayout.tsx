import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useEffect, useMemo, useState } from 'react';
import { Dropdown, Avatar } from 'antd';
import {
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined,
  LogoutOutlined,
  DownOutlined,
  CaretDownOutlined,
  CaretRightOutlined,
  BellOutlined,
  QuestionCircleOutlined,
} from '@ant-design/icons';
import { useAuth } from '@/lib/auth';
import { useCompany } from '@/lib/company';
import { cn } from '@/lib/utils';
import { getGroupIcon, getPathIcon } from '@/lib/menuIcons';
import { MENU } from '@/pages/modules';
import { PageTabs } from './PageTabs';
import { CompanySwitcher } from './CompanySwitcher';

/** 默认展开：全部分组（对齐旧版菜单行为）；当前路由所在分组也会自动展开 */
const DEFAULT_OPEN = new Set(MENU.map((group) => group.title));

/** 路径是否匹配菜单项：精确匹配，或详情子路径（如 /contracts/123） */
const pathMatchesItem = (pathname: string, itemPath: string) =>
  itemPath === '/'
    ? pathname === '/'
    : pathname === itemPath || pathname.startsWith(`${itemPath}/`);

/**
 * 最长前缀匹配：避免 /dashboard/consolidate 同时点亮 /dashboard，
 * 同时保留 /assets/:id/dossier 仍高亮「资产台账」。
 */
const findBestMenuMatch = (pathname: string): { group: string; path: string } | null => {
  let best: { group: string; path: string } | null = null;
  for (const group of MENU) {
    for (const item of group.items) {
      if (!pathMatchesItem(pathname, item.path)) continue;
      if (!best || item.path.length > best.path.length) {
        best = { group: group.title, path: item.path };
      }
    }
  }
  return best;
};

export function AdminLayout() {
  const { user, logout } = useAuth();
  const { scopeVersion } = useCompany();
  const navigate = useNavigate();
  const location = useLocation();
  const [openGroups, setOpenGroups] = useState<Record<string, boolean>>(() => {
    const init: Record<string, boolean> = {};
    for (const g of MENU) init[g.title] = DEFAULT_OPEN.has(g.title);
    return init;
  });
  const [siderCollapsed, setSiderCollapsed] = useState(false);

  const activeMenu = useMemo(() => findBestMenuMatch(location.pathname), [location.pathname]);
  const activeGroup = activeMenu?.group ?? null;
  const activePath = activeMenu?.path ?? null;

  useEffect(() => {
    if (!activeGroup) return;
    setOpenGroups((prev) => (prev[activeGroup] ? prev : { ...prev, [activeGroup]: true }));
  }, [activeGroup]);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const toggle = (title: string) =>
    setOpenGroups((c) => ({ ...c, [title]: !c[title] }));

  return (
    <div className="flex h-full max-h-[100dvh] overflow-hidden bg-[var(--ams-bg)]">
      <aside
        className={cn(
          'bg-[var(--ams-sidebar-bg)] border-r border-[var(--ams-border)] flex flex-col shrink-0 transition-all min-h-0',
          siderCollapsed ? 'w-16' : 'w-56',
        )}
      >
        <div className="h-14 flex items-center gap-2 px-3 border-b border-[var(--ams-border)] shrink-0 overflow-hidden">
          <div className="w-8 h-8 rounded bg-[var(--ams-primary)] flex items-center justify-center text-white text-sm font-bold shrink-0">
            资
          </div>
          {!siderCollapsed && (
            <div className="min-w-0 overflow-hidden">
              <div className="text-sm font-semibold text-[var(--ams-primary)] truncate">资管云平台</div>
              <div className="text-[10px] text-[var(--ams-text-secondary)] truncate">
                Asset Management
              </div>
            </div>
          )}
        </div>
        <nav className="flex-1 min-h-0 overflow-y-auto overflow-x-hidden ams-scroll py-2">
          {MENU.map((group) => {
            const opened = !!openGroups[group.title];
            return (
              <div key={group.title} className="mb-1">
                {!siderCollapsed && (
                  <button
                    type="button"
                    className={cn(
                      'w-full flex items-center justify-between gap-1 px-3 py-2 text-xs hover:bg-gray-50 overflow-hidden',
                      activeGroup === group.title
                        ? 'text-[var(--ams-primary)] font-semibold'
                        : 'text-[var(--ams-text-secondary)] font-medium',
                    )}
                    onClick={() => toggle(group.title)}
                    aria-expanded={opened}
                    aria-label={`${opened ? '收起' : '展开'}${group.title}`}
                  >
                    <span className="flex items-center gap-1.5 min-w-0 overflow-hidden">
                      <span className="text-sm shrink-0 opacity-70">{getGroupIcon(group.title)}</span>
                      <span className="truncate">{group.title}</span>
                      <span className="text-[10px] text-gray-400 shrink-0 tabular-nums">
                        {group.items.length}
                      </span>
                    </span>
                    <span className="text-[10px] shrink-0 text-gray-400">
                      {opened ? <CaretDownOutlined /> : <CaretRightOutlined />}
                    </span>
                  </button>
                )}
                {(siderCollapsed || opened) && (
                  <div className={siderCollapsed ? '' : 'pl-1'}>
                    {group.items.map((item) => {
                      const isActive = activePath === item.path;
                      return (
                        <NavLink
                          key={`${group.title}-${item.path}-${item.title}`}
                          to={item.path}
                          end
                          title={item.title}
                          aria-current={isActive ? 'page' : undefined}
                          className={cn(
                            'flex items-center gap-2 mx-2 my-0.5 rounded px-3 py-1.5 text-sm transition-colors overflow-hidden',
                            isActive
                              ? 'bg-[var(--ams-sidebar-active-bg)] text-[var(--ams-sidebar-active-text)]'
                              : 'text-[var(--ams-sidebar-text)] hover:bg-blue-50 hover:text-[var(--ams-primary)]',
                            siderCollapsed && 'justify-center px-2',
                          )}
                        >
                          <span className="text-base leading-none shrink-0">{getPathIcon(item.path)}</span>
                          {!siderCollapsed && <span className="truncate min-w-0">{item.title}</span>}
                        </NavLink>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })}
        </nav>
      </aside>

      <div className="flex-1 flex flex-col min-w-0 min-h-0 overflow-hidden">
        <header className="h-14 bg-white border-b border-[var(--ams-border)] flex items-center justify-between gap-3 px-4 shrink-0 overflow-hidden">
          <div className="flex items-center gap-3 min-w-0 overflow-hidden">
            <button
              type="button"
              className="text-gray-500 hover:text-[var(--ams-primary)] text-lg shrink-0"
              onClick={() => setSiderCollapsed((v) => !v)}
              aria-label={siderCollapsed ? '展开侧栏' : '收起侧栏'}
            >
              {siderCollapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            </button>
            <span className="text-sm text-gray-500 truncate hidden sm:inline">
              资管云平台 · 管理后台
            </span>
            <span className="hidden sm:block h-4 w-px bg-[var(--ams-border)] shrink-0" />
            <CompanySwitcher />
          </div>
          <div className="flex items-center gap-3 sm:gap-4 shrink-0">
            <button
              type="button"
              className="text-gray-400 hover:text-[var(--ams-primary)] text-base inline-flex"
              aria-label="帮助"
              title="用户手册"
              onClick={() => navigate('/help')}
            >
              <QuestionCircleOutlined />
            </button>
            <button
              type="button"
              className="text-gray-400 hover:text-[var(--ams-primary)] text-base relative"
              aria-label="消息"
              title="消息"
              onClick={() => navigate('/notifications')}
            >
              <BellOutlined />
            </button>
            <Dropdown
              menu={{
                items: [
                  {
                    key: 'logout',
                    icon: <LogoutOutlined />,
                    label: '退出登录',
                    onClick: handleLogout,
                  },
                ],
              }}
            >
              <button
                type="button"
                className="flex items-center gap-2 text-sm text-gray-600 max-w-[140px] overflow-hidden"
              >
                <Avatar size="small" icon={<UserOutlined />} className="bg-[var(--ams-primary)] shrink-0" />
                <span className="truncate hidden md:inline">{user?.name ?? '用户'}</span>
                <DownOutlined className="text-[10px] text-gray-400 shrink-0" />
              </button>
            </Dropdown>
          </div>
        </header>

        <PageTabs />

        {/* 公司切换后重挂载当前路由（key 变化），使各页面按新数据范围重新拉取 */}
        <main className="flex-1 min-h-0 overflow-auto ams-scroll p-3 sm:p-4">
          <div className="min-w-0 max-w-full" key={scopeVersion}>
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  );
}
