import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { CloseOutlined, LeftOutlined, RightOutlined } from '@ant-design/icons';
import { Dropdown } from 'antd';
import type { MenuProps } from 'antd';
import { cn } from '@/lib/utils';
import { getPathIcon } from '@/lib/menuIcons';
import { MENU } from '@/pages/modules';

export interface TabItem {
  path: string;
  title: string;
}

const STORAGE_KEY = 'ams.pageTabs';
const HOME: TabItem = { path: '/', title: '应用中心' };
const SCROLL_STEP = 180;

const titleByPath = (): Record<string, string> => {
  const map: Record<string, string> = { '/': '应用中心', '/help': '用户手册' };
  for (const group of MENU) {
    for (const item of group.items) {
      map[item.path] = item.title;
    }
  }
  return map;
};

const loadTabs = (): TabItem[] => {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return [HOME];
    const parsed = JSON.parse(raw) as TabItem[];
    if (!Array.isArray(parsed) || parsed.length === 0) return [HOME];
    const hasHome = parsed.some((t) => t.path === '/');
    return hasHome ? parsed : [HOME, ...parsed];
  } catch {
    return [HOME];
  }
};

const ensureHome = (list: TabItem[]): TabItem[] => {
  if (list.some((t) => t.path === '/')) return list.length ? list : [HOME];
  return [HOME, ...list];
};

export function PageTabs() {
  const location = useLocation();
  const navigate = useNavigate();
  const titles = useMemo(() => titleByPath(), []);
  const [tabs, setTabs] = useState<TabItem[]>(loadTabs);
  const scrollerRef = useRef<HTMLDivElement>(null);
  const activeRef = useRef<HTMLButtonElement>(null);
  const [canLeft, setCanLeft] = useState(false);
  const [canRight, setCanRight] = useState(false);

  const updateOverflow = useCallback(() => {
    const el = scrollerRef.current;
    if (!el) return;
    const { scrollLeft, scrollWidth, clientWidth } = el;
    setCanLeft(scrollLeft > 1);
    setCanRight(scrollLeft + clientWidth < scrollWidth - 1);
  }, []);

  const persist = useCallback((next: TabItem[]) => {
    setTabs(next);
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  }, []);

  const applyTabs = useCallback(
    (next: TabItem[], preferPath?: string) => {
      const normalized = ensureHome(next);
      persist(normalized);
      if (!normalized.some((t) => t.path === location.pathname)) {
        const preferred = preferPath
          ? normalized.find((t) => t.path === preferPath)
          : undefined;
        navigate((preferred ?? normalized[normalized.length - 1] ?? HOME).path);
      }
    },
    [location.pathname, navigate, persist],
  );

  useEffect(() => {
    const path = location.pathname;
    const title = titles[path] ?? path;
    setTabs((prev) => {
      if (prev.some((t) => t.path === path)) return prev;
      const next = [...prev, { path, title }];
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
      return next;
    });
  }, [location.pathname, titles]);

  const scrollActiveIntoView = useCallback(() => {
    const scroller = scrollerRef.current;
    const active = activeRef.current;
    if (!scroller || !active) return;
    const left = active.offsetLeft;
    const right = left + active.offsetWidth;
    const viewLeft = scroller.scrollLeft;
    const viewRight = viewLeft + scroller.clientWidth;
    const pad = 8;
    if (left < viewLeft + pad) {
      scroller.scrollTo({ left: Math.max(0, left - pad), behavior: 'smooth' });
    } else if (right > viewRight - pad) {
      scroller.scrollTo({ left: right - scroller.clientWidth + pad, behavior: 'smooth' });
    }
    requestAnimationFrame(updateOverflow);
  }, [updateOverflow]);

  useLayoutEffect(() => {
    updateOverflow();
    scrollActiveIntoView();
  }, [tabs, location.pathname, updateOverflow, scrollActiveIntoView]);

  useEffect(() => {
    const el = scrollerRef.current;
    if (!el) return;
    const onScroll = () => updateOverflow();
    el.addEventListener('scroll', onScroll, { passive: true });
    const ro = new ResizeObserver(() => updateOverflow());
    ro.observe(el);
    window.addEventListener('resize', updateOverflow);
    return () => {
      el.removeEventListener('scroll', onScroll);
      ro.disconnect();
      window.removeEventListener('resize', updateOverflow);
    };
  }, [updateOverflow]);

  const handleScrollBy = (dir: -1 | 1) => {
    const el = scrollerRef.current;
    if (!el) return;
    el.scrollBy({ left: dir * SCROLL_STEP, behavior: 'smooth' });
  };

  const handleWheel = (e: React.WheelEvent) => {
    const el = scrollerRef.current;
    if (!el) return;
    if (Math.abs(e.deltaY) > Math.abs(e.deltaX) && el.scrollWidth > el.clientWidth) {
      e.preventDefault();
      el.scrollLeft += e.deltaY;
      updateOverflow();
    }
  };

  const handleClose = (e: React.MouseEvent, path: string) => {
    e.stopPropagation();
    if (path === '/') return;
    const idx = tabs.findIndex((t) => t.path === path);
    if (idx < 0) return;
    const next = tabs.filter((t) => t.path !== path);
    const fallback = next[Math.max(0, idx - 1)] ?? HOME;
    applyTabs(next.length ? next : [HOME], fallback.path);
  };

  const handleCloseOthers = (path: string) => {
    const keep = tabs.filter((t) => t.path === '/' || t.path === path);
    applyTabs(keep, path);
  };

  const handleCloseLeft = (path: string) => {
    const idx = tabs.findIndex((t) => t.path === path);
    if (idx <= 0) return;
    const left = tabs.slice(0, idx).filter((t) => t.path === '/');
    const right = tabs.slice(idx);
    applyTabs([...left, ...right], path);
  };

  const handleCloseRight = (path: string) => {
    const idx = tabs.findIndex((t) => t.path === path);
    if (idx < 0 || idx >= tabs.length - 1) return;
    applyTabs(tabs.slice(0, idx + 1), path);
  };

  const getContextMenu = (tab: TabItem): MenuProps => {
    const idx = tabs.findIndex((t) => t.path === tab.path);
    const hasClosableOthers = tabs.some((t) => t.path !== '/' && t.path !== tab.path);
    const hasClosableLeft = tabs.slice(0, idx).some((t) => t.path !== '/');
    const hasRight = idx >= 0 && idx < tabs.length - 1;

    return {
      items: [
        {
          key: 'close-others',
          label: '关闭其他',
          disabled: !hasClosableOthers,
        },
        {
          key: 'close-left',
          label: '关闭左侧所有',
          disabled: !hasClosableLeft,
        },
        {
          key: 'close-right',
          label: '关闭右侧所有',
          disabled: !hasRight,
        },
      ],
      onClick: ({ key, domEvent }) => {
        domEvent.stopPropagation();
        if (key === 'close-others') handleCloseOthers(tab.path);
        if (key === 'close-left') handleCloseLeft(tab.path);
        if (key === 'close-right') handleCloseRight(tab.path);
      },
    };
  };

  const overflow = canLeft || canRight;

  return (
    <div className="relative h-10 bg-white border-b border-[var(--ams-border)] shrink-0 min-w-0 flex items-stretch">
      {overflow && (
        <button
          type="button"
          aria-label="向左滚动页签"
          disabled={!canLeft}
          onClick={() => handleScrollBy(-1)}
          className={cn(
            'w-8 shrink-0 flex items-center justify-center border-r border-[var(--ams-border)] text-gray-500 z-10 bg-white',
            canLeft ? 'hover:text-[var(--ams-primary)] hover:bg-blue-50' : 'opacity-30 cursor-not-allowed',
          )}
        >
          <LeftOutlined className="text-xs" />
        </button>
      )}

      <div className="relative flex-1 min-w-0 overflow-hidden">
        {canLeft && (
          <div className="pointer-events-none absolute left-0 top-0 bottom-0 w-6 z-[1] bg-gradient-to-r from-white to-transparent" />
        )}
        {canRight && (
          <div className="pointer-events-none absolute right-0 top-0 bottom-0 w-6 z-[1] bg-gradient-to-l from-white to-transparent" />
        )}

        <div
          ref={scrollerRef}
          onWheel={handleWheel}
          className="h-full flex items-end gap-1 px-2 overflow-x-auto overflow-y-hidden ams-tabs-scroll-hidden"
        >
          {tabs.map((tab) => {
            const active = location.pathname === tab.path;
            return (
              <Dropdown key={tab.path} menu={getContextMenu(tab)} trigger={['contextMenu']}>
                <button
                  ref={active ? activeRef : undefined}
                  type="button"
                  title={tab.title}
                  onClick={() => navigate(tab.path)}
                  className={cn(
                    'group relative flex items-center gap-1.5 h-8 pl-2.5 pr-1.5 text-sm rounded-t border border-b-0 shrink-0',
                    'max-w-[9.5rem] sm:max-w-[11rem]',
                    active
                      ? 'bg-[var(--ams-bg)] text-[var(--ams-primary)] border-[var(--ams-border)] font-medium'
                      : 'bg-transparent text-gray-500 border-transparent hover:text-[var(--ams-primary)] hover:bg-gray-50',
                  )}
                >
                  <span className="text-xs leading-none opacity-80 shrink-0">
                    {getPathIcon(tab.path)}
                  </span>
                  <span className="truncate min-w-0 flex-1 text-left leading-none">{tab.title}</span>
                  {tab.path !== '/' ? (
                    <span
                      role="button"
                      tabIndex={0}
                      aria-label={`关闭 ${tab.title}`}
                      className={cn(
                        'inline-flex items-center justify-center w-4 h-4 rounded-sm shrink-0',
                        'opacity-0 group-hover:opacity-70 hover:!opacity-100 hover:bg-black/5',
                        active && 'opacity-50',
                      )}
                      onClick={(e) => handleClose(e, tab.path)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          e.preventDefault();
                          handleClose(e as unknown as React.MouseEvent, tab.path);
                        }
                      }}
                    >
                      <CloseOutlined className="text-[10px]" />
                    </span>
                  ) : (
                    <span className="w-1 shrink-0" />
                  )}
                  {active && (
                    <span className="absolute left-1 right-1 bottom-0 h-0.5 rounded-full bg-[var(--ams-primary)]" />
                  )}
                </button>
              </Dropdown>
            );
          })}
        </div>
      </div>

      {overflow && (
        <button
          type="button"
          aria-label="向右滚动页签"
          disabled={!canRight}
          onClick={() => handleScrollBy(1)}
          className={cn(
            'w-8 shrink-0 flex items-center justify-center border-l border-[var(--ams-border)] text-gray-500 z-10 bg-white',
            canRight ? 'hover:text-[var(--ams-primary)] hover:bg-blue-50' : 'opacity-30 cursor-not-allowed',
          )}
        >
          <RightOutlined className="text-xs" />
        </button>
      )}
    </div>
  );
}
