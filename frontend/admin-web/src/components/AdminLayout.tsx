import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Dropdown, Avatar, Popover } from 'antd';
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
import { MenuEmpty, MenuFallbackNotice, MenuSkeleton, useMenu, type MenuState } from '@/lib/menu';
import type { MenuGroup, MenuItem } from '@/pages/modules';
import { PermissionSnapshotRefresher, RequirePerm } from '@/lib/perm';
import { PageTabs } from './PageTabs';
import { CompanySwitcher } from './CompanySwitcher';

/** 路径是否匹配菜单项：精确匹配，或详情子路径（如 /contracts/123） */
const pathMatchesItem = (pathname: string, itemPath: string) =>
  itemPath === '/'
    ? pathname === '/'
    : pathname === itemPath || pathname.startsWith(`${itemPath}/`);

/**
 * 最长前缀匹配：避免 /dashboard/consolidate 同时点亮 /dashboard，
 * 同时保留 /assets/:id/dossier 仍高亮「资产台账」。
 */
const findBestMenuMatch = (
  pathname: string,
  groups: MenuGroup[],
): { group: string; path: string } | null => {
  let best: { group: string; path: string } | null = null;
  for (const group of groups) {
    for (const item of group.items) {
      if (!pathMatchesItem(pathname, item.path)) continue;
      if (!best || item.path.length > best.path.length) {
        best = { group: group.title, path: item.path };
      }
    }
  }
  return best;
};

/** 分组展开态：默认全部展开（对齐旧版菜单行为）。 */
const buildOpenState = (groups: MenuGroup[]): Record<string, boolean> => {
  const init: Record<string, boolean> = {};
  for (const g of groups) init[g.title] = true;
  return init;
};

export function AdminLayout() {
  const { user, logout } = useAuth();
  const { scopeVersion } = useCompany();
  const navigate = useNavigate();
  const location = useLocation();
  const menu = useMenu();
  const { groups, status } = menu;
  const [siderCollapsed, setSiderCollapsed] = useState(false);

  /**
   * 展开态按分组标题索引，因此必须跟着分组集合重建：
   * 菜单来自 DB 后标题与静态 `MENU` 不再相同，沿用旧 key 会让所有分组都折叠。
   * 只在分组标题集合真的变化时才重建，避免打断用户的展开/收起操作。
   */
  const groupKey = groups.map((g) => g.title).join('\u0000');
  const [openGroups, setOpenGroups] = useState<Record<string, boolean>>(() =>
    buildOpenState(groups),
  );
  useEffect(() => {
    if (!groups.length) return;
    setOpenGroups((prev) => {
      const seen = new Set(groups.map((g) => g.title));
      // 新分组默认展开；已存在的分组保留用户当前选择
      let changed = false;
      const next: Record<string, boolean> = {};
      for (const g of groups) {
        next[g.title] = g.title in prev ? prev[g.title] : true;
        if (!(g.title in prev)) changed = true;
      }
      for (const key of Object.keys(prev)) {
        if (!seen.has(key)) changed = true;
      }
      return changed ? next : prev;
    });
    // groupKey 是分组标题集合的稳定指纹；groups 本身每次请求都是新数组
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [groupKey]);

  const activeMenu = useMemo(
    () => findBestMenuMatch(location.pathname, groups),
    [location.pathname, groups],
  );
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

  const toggle = useCallback(
    (title: string) => setOpenGroups((c) => ({ ...c, [title]: !c[title] })),
    [],
  );

  return (
    <div className="flex h-full max-h-[100dvh] overflow-hidden bg-[var(--ams-bg)]">
      {/* 权限快照刷新契约（设计 6.2）：路由跳转 / 窗口聚焦时节流重拉 /system/me */}
      <PermissionSnapshotRefresher />
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
              <div className="text-sm font-semibold text-[var(--ams-primary)] truncate">
                资管云平台
              </div>
              <div className="text-[10px] text-[var(--ams-text-secondary)] truncate">
                Asset Management
              </div>
            </div>
          )}
        </div>
        <nav className="flex-1 min-h-0 overflow-y-auto overflow-x-hidden ams-scroll py-2">
          <SidebarBody
            menu={menu}
            collapsed={siderCollapsed}
            openGroups={openGroups}
            activeGroup={activeGroup}
            activePath={activePath}
            onToggle={toggle}
          />
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
            {menu.refreshing && status !== 'fallback' && (
              <span className="text-[10px] text-gray-400 shrink-0" role="status">
                菜单同步中…
              </span>
            )}
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
                <Avatar
                  size="small"
                  icon={<UserOutlined />}
                  className="bg-[var(--ams-primary)] shrink-0"
                />
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
            {/* 页面级权限守卫（设计 6.2）：无 code:view 时在内容区渲染 403，保留侧栏与页签 */}
            <RequirePerm>
              <Outlet />
            </RequirePerm>
          </div>
        </main>
      </div>
    </div>
  );
}

/**
 * 侧栏主体：三态分流。
 *
 * <p>`loading` → 骨架屏；`empty` → 空导航态（**不回退静态菜单**，无权限用户不该看到 24 组入口）；
 * `fallback` → 已按 permissions 过滤的静态菜单 + 降级提示条。
 */
function SidebarBody({
  menu,
  collapsed,
  openGroups,
  activeGroup,
  activePath,
  onToggle,
}: {
  menu: MenuState;
  collapsed: boolean;
  openGroups: Record<string, boolean>;
  activeGroup: string | null;
  activePath: string | null;
  onToggle: (title: string) => void;
}) {
  const { status, groups } = menu;

  if (status === 'loading') return <MenuSkeleton collapsed={collapsed} />;
  if (status === 'empty') return <MenuEmpty collapsed={collapsed} />;
  // 加载失败但 permissions 过滤后一个分组都不剩：等同无可用菜单，不必再显示降级提示
  if (!groups.length) return <MenuEmpty collapsed={collapsed} />;

  // 收起态：只留目录图标，子菜单改为 hover / 聚焦时在右侧浮出（设计 §2–§5）。
  // 放在三态分流之后：骨架屏 / 空态在两种形态下都要先走完。
  if (collapsed) {
    return <CollapsedGroupNav groups={groups} activeGroup={activeGroup} activePath={activePath} />;
  }

  // 走到这里 collapsed 必为 false，因此下面不再保留任何 collapsed 分支（行为与改造前一致）
  return (
    <>
      {status === 'fallback' && <MenuFallbackNotice collapsed={collapsed} />}
      {groups.map((group) => {
        const opened = !!openGroups[group.title];
        return (
          <div key={group.title} className="mb-1">
            <button
              type="button"
              className={cn(
                'w-full flex items-center justify-between gap-1 px-3 py-2 text-xs hover:bg-gray-50 overflow-hidden',
                activeGroup === group.title
                  ? 'text-[var(--ams-primary)] font-semibold'
                  : 'text-[var(--ams-text-secondary)] font-medium',
              )}
              onClick={() => onToggle(group.title)}
              aria-expanded={opened}
              aria-label={`${opened ? '收起' : '展开'}${group.title}`}
            >
              <span className="flex items-center gap-1.5 min-w-0 overflow-hidden">
                <span className="text-sm shrink-0 opacity-70">
                  {group.icon ?? getGroupIcon(group.title)}
                </span>
                <span className="truncate">{group.title}</span>
                <span className="text-[10px] text-gray-400 shrink-0 tabular-nums">
                  {group.items.length}
                </span>
              </span>
              <span className="text-[10px] shrink-0 text-gray-400">
                {opened ? <CaretDownOutlined /> : <CaretRightOutlined />}
              </span>
            </button>
            {opened && (
              <div className="pl-1">
                {group.items.map((item) => (
                  <SidebarItemLink
                    key={`${group.title}-${item.path}-${item.title}`}
                    item={item}
                    active={activePath === item.path}
                  />
                ))}
              </div>
            )}
          </div>
        );
      })}
    </>
  );
}

/**
 * 单个菜单项（叶子）。展开态的列表与收起态的浮层**共用**它，保证两处样式与激活判定一致。
 *
 * <p>只在展开形态下渲染，因此恒带文字；收起态不再内联渲染叶子项（那是改造前的行为，
 * 会把 68 个叶子图标平铺成一长列，且看不出分组）。
 */
function SidebarItemLink({
  item,
  active,
  onNavigate,
}: {
  item: MenuItem;
  active: boolean;
  /** 浮层里点击后要收起浮层；展开态列表不需要（保持既有行为） */
  onNavigate?: () => void;
}) {
  return (
    <NavLink
      to={item.path}
      end
      title={item.title}
      aria-current={active ? 'page' : undefined}
      onClick={onNavigate}
      className={cn(
        'flex items-center gap-2 mx-2 my-0.5 rounded px-3 py-1.5 text-sm transition-colors overflow-hidden',
        active
          ? 'bg-[var(--ams-sidebar-active-bg)] text-[var(--ams-sidebar-active-text)]'
          : 'text-[var(--ams-sidebar-text)] hover:bg-blue-50 hover:text-[var(--ams-primary)]',
      )}
    >
      {/* DB 图标为权威；为空时回退注册表的 path 图标 */}
      <span className="text-base leading-none shrink-0">{item.icon ?? getPathIcon(item.path)}</span>
      <span className="truncate min-w-0">{item.title}</span>
    </NavLink>
  );
}

/**
 * 收起态的目录导航：每目录一个图标，hover / 聚焦时右侧浮出该目录的菜单项（设计 §2–§5）。
 *
 * <p>为什么用 `Popover` 而不是 CSS 绝对定位：外层 `nav` 是
 * `overflow-y-auto overflow-x-hidden`，栏内任何绝对定位的浮层都会被横向裁掉；
 * Popover 走 portal 挂到 body，天然不受裁剪，并由 `autoAdjustOverflow` 处理贴底翻向。
 *
 * <p>为什么受控 `open`：antd 不会自己响应 Esc，而键盘用户必须能关掉浮层。
 */
function CollapsedGroupNav({
  groups,
  activeGroup,
  activePath,
}: {
  groups: MenuGroup[];
  activeGroup: string | null;
  activePath: string | null;
}) {
  /** 同时只允许一个浮层展开；null = 全部关闭 */
  const [openKey, setOpenKey] = useState<string | null>(null);
  /**
   * 各目录浮层的根节点，**按目录标题分开存**。
   *
   * <p>不能共用一个 `useRef`：React 在元素卸载时会把对象 ref 置空，且**不校验当前值是否
   * 仍指向该元素**。A 的浮层在做关闭动画、B 的浮层已挂载时，A 卸载会把 ref 一起清掉，
   * B 的引用就丢了（Esc 的「焦点是否在浮层内」判定随之失效）。一个标题一个槽位最稳。
   */
  const panelsRef = useRef<Record<string, HTMLDivElement | null>>({});
  /** 图标列容器：焦点在这一列里移动不算离开 */
  const railRef = useRef<HTMLDivElement | null>(null);
  /** 本次展开是否由键盘触发：是才把焦点送进浮层，鼠标用户不该被抢焦点 */
  const openedByKeyboard = useRef(false);
  /** 最近一次聚焦过的图标：Esc 关闭后要把焦点还回去 */
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  /**
   * 跳过「由我们自己发起的」那一次 onFocus。
   *
   * <p>Esc 关闭后调 `trigger.focus()` 会把焦点还回图标，但那会**再次触发 onFocus**、
   * 把浮层重新打开 —— 等于 Esc 无效。标记一次跳过即可。
   */
  const skipNextFocusOpen = useRef(false);

  /** 当前展开的那个浮层节点（只有一个是展开的） */
  const openPanel = () => (openKey ? (panelsRef.current[openKey] ?? null) : null);

  // Esc 关闭。监听 window 而不是按钮的 onKeyDown：焦点可能已经在浮层里（portal 出去），
  // 事件不会冒泡回按钮。
  useEffect(() => {
    if (!openKey) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      // 只有焦点确实在浮层里时才需要「还回去」：若焦点本就在图标上，重新 focus() 不会再
      // 触发 onFocus，留下跳过标记反而会吞掉下一次正常的键盘展开。
      const focused = document.activeElement;
      const fromPanel = !!focused && !!openPanel()?.contains(focused);
      const trigger = triggerRef.current;
      setOpenKey(null);
      if (fromPanel && trigger) {
        skipNextFocusOpen.current = true;
        trigger.focus();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
    // openPanel 读的是 openKey + 稳定的 ref，依赖 openKey 即可
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openKey]);

  /**
   * 焦点离开「图标列 + 浮层」这一整体时才关闭。
   *
   * <p>两处 onBlur 共用：浮层是 portal，DOM 上与触发器并不连续，只能靠判断
   * `relatedTarget` 落在哪一侧来决定「是不是真的离开了」。
   */
  const closeIfFocusOutside = (event: React.FocusEvent) => {
    const next = event.relatedTarget as Node | null;
    if (next && (railRef.current?.contains(next) || openPanel()?.contains(next))) return;
    setOpenKey(null);
  };

  return (
    <div
      ref={railRef}
      className="flex flex-col items-center gap-1 py-1"
      onBlur={closeIfFocusOutside}
    >
      {groups.map((group) => {
        const active = activeGroup === group.title;
        return (
          <Popover
            key={group.title}
            open={openKey === group.title}
            onOpenChange={(next) => {
              if (next) {
                setOpenKey(group.title);
                return;
              }
              // 鼠标移开时若焦点仍在浮层或图标列里（键盘用户正在浏览），不要关掉它
              const focused = document.activeElement;
              if (
                focused &&
                (openPanel()?.contains(focused) || railRef.current?.contains(focused))
              ) {
                return;
              }
              setOpenKey((prev) => (prev === group.title ? null : prev));
            }}
            // 只用 hover 触发，键盘的开关由下面的显式 onFocus / Esc 负责。
            // 不能用 antd 的 'focus' trigger：浮层在 portal 里，焦点一移进去触发器就 blur，
            // antd 会立刻把浮层关掉 —— 键盘用户将永远进不到子菜单。
            trigger={['hover']}
            placement="rightTop"
            arrow={false}
            mouseEnterDelay={0.05}
            // 约 150ms 的关闭延迟：从图标斜向移进浮层时不会中途断掉，也防手抖
            mouseLeaveDelay={0.15}
            destroyOnHidden
            // 浮层内容自带内边距，去掉 Popover 默认的（它在 .ant-popover-container 上）
            styles={{ container: { padding: 0 } }}
            afterOpenChange={(next) => {
              if (!next || !openedByKeyboard.current) return;
              openedByKeyboard.current = false;
              // 键盘打开：把焦点送进浮层第一项，否则 Tab 到不了（浮层在 portal 里）
              panelsRef.current[group.title]?.querySelector<HTMLElement>('a, button')?.focus();
            }}
            content={
              <div
                ref={(node) => {
                  panelsRef.current[group.title] = node;
                }}
                onBlur={closeIfFocusOutside}
                className="min-w-[176px] max-w-[260px]"
              >
                {/* 头部：目录标题 + 数量，确认自己在哪个目录 */}
                <div className="flex items-center gap-1.5 px-3 py-2 border-b border-[var(--ams-border)]">
                  <span className="text-sm leading-none shrink-0 opacity-70">
                    {group.icon ?? getGroupIcon(group.title)}
                  </span>
                  <span className="text-xs font-semibold text-[var(--ams-text-secondary)] truncate">
                    {group.title}
                  </span>
                  <span className="ml-auto text-[10px] text-gray-400 shrink-0 tabular-nums">
                    {group.items.length}
                  </span>
                </div>
                {/* 主体：该目录下全部菜单项。点击即跳转并收起浮层 */}
                <div className="py-1 max-h-[60vh] overflow-y-auto ams-scroll">
                  {group.items.map((item) => (
                    <SidebarItemLink
                      key={`${group.title}-${item.path}-${item.title}`}
                      item={item}
                      active={activePath === item.path}
                      onNavigate={() => setOpenKey(null)}
                    />
                  ))}
                </div>
              </div>
            }
          >
            <button
              type="button"
              onFocus={(event) => {
                if (skipNextFocusOpen.current) {
                  skipNextFocusOpen.current = false;
                  return;
                }
                triggerRef.current = event.currentTarget;
                // 只有键盘聚焦（:focus-visible）才把焦点送进浮层；鼠标点按不该抢焦点
                openedByKeyboard.current = event.currentTarget.matches(':focus-visible');
                setOpenKey(group.title);
              }}
              aria-expanded={openKey === group.title}
              aria-label={`${group.title}（${group.items.length} 项）`}
              title={group.title}
              className={cn(
                'relative w-9 h-9 rounded flex items-center justify-center text-base transition-colors',
                active
                  ? 'bg-[var(--ams-sidebar-active-bg)] text-[var(--ams-sidebar-active-text)]'
                  : 'text-[var(--ams-sidebar-text)] hover:bg-blue-50 hover:text-[var(--ams-primary)]',
              )}
            >
              <span className="leading-none">{group.icon ?? getGroupIcon(group.title)}</span>
              {/* 数量徽标：对应展开态分组头上的那个数字 */}
              <span className="absolute top-0 right-0 min-w-[14px] h-[14px] px-[3px] rounded-full bg-gray-200 text-[9px] leading-[14px] text-center text-gray-600 tabular-nums">
                {group.items.length}
              </span>
            </button>
          </Popover>
        );
      })}
    </div>
  );
}
