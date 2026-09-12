import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Button, Empty, Input } from 'antd';
import { ArrowRightOutlined, SearchOutlined } from '@ant-design/icons';
import { cn } from '@/lib/utils';
import { getPathIcon } from '@/lib/menuIcons';
import { buildHelpManual, type HelpModuleGuide } from '@/lib/helpManual';
import { useMenu } from '@/lib/menu';

const matchGuide = (guide: HelpModuleGuide, q: string) => {
  if (!q) return true;
  const hay = [guide.title, guide.group, guide.purpose, ...guide.capabilities, ...guide.operations]
    .join(' ')
    .toLowerCase();
  return hay.includes(q);
};

export function HelpPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [keyword, setKeyword] = useState('');
  // 手册条目跟随侧边栏当前菜单（DB 决定模块与命名），不再用静态 MENU
  const { groups } = useMenu();
  const manual = useMemo(() => buildHelpManual(groups), [groups]);

  const filtered = useMemo(() => {
    const q = keyword.trim().toLowerCase();
    return manual.filter((g) => matchGuide(g, q));
  }, [keyword, manual]);

  const grouped = useMemo(() => {
    const map = new Map<string, HelpModuleGuide[]>();
    for (const g of filtered) {
      const list = map.get(g.group) ?? [];
      list.push(g);
      map.set(g.group, list);
    }
    return Array.from(map.entries());
  }, [filtered]);

  const selectedPath = searchParams.get('module');
  const selected = useMemo(() => {
    if (selectedPath) {
      const fromFiltered = filtered.find((g) => g.path === selectedPath);
      if (fromFiltered) return fromFiltered;
      if (!keyword.trim()) {
        return manual.find((g) => g.path === selectedPath) ?? filtered[0] ?? manual[0];
      }
    }
    return filtered[0] ?? manual[0];
  }, [filtered, keyword, manual, selectedPath]);

  useEffect(() => {
    if (!selected) return;
    if (selectedPath === selected.path) return;
    setSearchParams({ module: selected.path }, { replace: true });
  }, [selected, selectedPath, setSearchParams]);

  const handleSelect = (path: string) => {
    setSearchParams({ module: path });
  };

  const handleGoModule = () => {
    if (!selected) return;
    navigate(selected.path);
  };

  const handleSearchChange = (value: string) => {
    setKeyword(value);
  };

  return (
    <div className="flex flex-col gap-3 h-[calc(100dvh-8.5rem)] min-h-[420px]">
      <div className="shrink-0">
        <h1 className="text-lg font-semibold text-gray-900 m-0">用户手册</h1>
        <p className="text-sm text-gray-500 mt-1 mb-0">
          按菜单模块说明用途、主要能力与典型操作，可直接跳转到对应功能。
        </p>
      </div>

      <div className="flex-1 min-h-0 grid grid-cols-1 lg:grid-cols-[260px_minmax(0,1fr)] gap-3">
        <aside className="bg-white border border-[var(--ams-border)] rounded-lg flex flex-col min-h-0 overflow-hidden">
          <div className="p-3 border-b border-[var(--ams-border)] shrink-0">
            <Input
              allowClear
              value={keyword}
              onChange={(e) => handleSearchChange(e.target.value)}
              placeholder="搜索模块 / 用途"
              prefix={<SearchOutlined className="text-gray-400" />}
              aria-label="搜索手册模块"
            />
          </div>
          <nav className="flex-1 min-h-0 overflow-y-auto ams-scroll p-2" aria-label="手册目录">
            {grouped.length === 0 ? (
              <Empty className="py-10" description="无匹配模块" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              grouped.map(([group, items]) => (
                <div key={group} className="mb-3">
                  <div className="px-2 py-1 text-xs font-medium text-gray-400">{group}</div>
                  <ul className="list-none m-0 p-0">
                    {items.map((item) => {
                      const active = selected?.path === item.path;
                      return (
                        <li key={item.path}>
                          <button
                            type="button"
                            onClick={() => handleSelect(item.path)}
                            className={cn(
                              'w-full flex items-center gap-2 text-left rounded px-2 py-1.5 text-sm transition-colors',
                              active
                                ? 'bg-[var(--ams-sidebar-active-bg)] text-[var(--ams-sidebar-active-text)]'
                                : 'text-gray-700 hover:bg-blue-50 hover:text-[var(--ams-primary)]',
                            )}
                            aria-current={active ? 'page' : undefined}
                          >
                            <span className="shrink-0 text-base leading-none">{getPathIcon(item.path)}</span>
                            <span className="truncate">{item.title}</span>
                          </button>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              ))
            )}
          </nav>
        </aside>

        <section className="bg-white border border-[var(--ams-border)] rounded-lg min-h-0 overflow-y-auto ams-scroll p-4 sm:p-6">
          {!selected ? (
            <Empty description="请选择模块" />
          ) : (
            <article>
              <div className="flex flex-wrap items-start justify-between gap-3 mb-5">
                <div className="min-w-0 flex items-start gap-3">
                  <span className="w-11 h-11 rounded-lg bg-blue-50 text-[var(--ams-primary)] flex items-center justify-center text-xl shrink-0">
                    {getPathIcon(selected.path)}
                  </span>
                  <div className="min-w-0">
                    <div className="text-xs text-gray-400 mb-0.5">{selected.group}</div>
                    <h2 className="text-xl font-semibold text-gray-900 m-0 truncate">{selected.title}</h2>
                    <div className="text-xs text-gray-400 mt-1 font-mono truncate">{selected.path}</div>
                  </div>
                </div>
                <Button type="primary" icon={<ArrowRightOutlined />} onClick={handleGoModule}>
                  前往模块
                </Button>
              </div>

              <div className="space-y-6">
                <section>
                  <h3 className="text-sm font-semibold text-gray-900 m-0 mb-2">用途</h3>
                  <p className="text-sm text-gray-700 leading-relaxed m-0">{selected.purpose}</p>
                </section>

                <section>
                  <h3 className="text-sm font-semibold text-gray-900 m-0 mb-2">主要能力</h3>
                  <ul className="m-0 pl-5 space-y-1.5">
                    {selected.capabilities.map((c) => (
                      <li key={c} className="text-sm text-gray-700 leading-relaxed">
                        {c}
                      </li>
                    ))}
                  </ul>
                </section>

                <section>
                  <h3 className="text-sm font-semibold text-gray-900 m-0 mb-2">典型操作</h3>
                  <ol className="m-0 pl-5 space-y-1.5">
                    {selected.operations.map((op) => (
                      <li key={op} className="text-sm text-gray-700 leading-relaxed">
                        {op}
                      </li>
                    ))}
                  </ol>
                </section>
              </div>
            </article>
          )}
        </section>
      </div>
    </div>
  );
}
