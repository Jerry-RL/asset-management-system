import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useState } from 'react';
import { useAuth } from '@/lib/auth';
import { cn } from '@/lib/utils';
import { MENU } from '@/pages/modules';

export function AdminLayout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const toggle = (title: string) => setCollapsed((c) => ({ ...c, [title]: !c[title] }));

  return (
    <div className="flex h-screen bg-gray-50">
      <aside className="w-60 bg-slate-900 text-white flex flex-col shrink-0">
        <div className="h-14 flex items-center px-4 text-base font-semibold border-b border-slate-700">
          资产经营管理系统
        </div>
        <nav className="flex-1 overflow-auto py-2">
          {MENU.map((group) => (
            <div key={group.title} className="mb-1">
              <button
                className="w-full flex items-center justify-between px-4 py-2 text-sm text-slate-300 hover:bg-slate-700"
                onClick={() => toggle(group.title)}
              >
                <span className="font-medium">{group.title}</span>
                <span className="text-xs">{collapsed[group.title] ? '+' : '−'}</span>
              </button>
              {!collapsed[group.title] && (
                <div className="pl-2">
                  {group.items.map((item) => (
                    <NavLink
                      key={item.path}
                      to={item.path}
                      end={item.path === '/'}
                      className={({ isActive }) =>
                        cn(
                          'block px-4 py-1.5 text-sm hover:bg-slate-700',
                          isActive ? 'bg-slate-700 text-white' : 'text-slate-400',
                        )
                      }
                    >
                      {item.title}
                    </NavLink>
                  ))}
                </div>
              )}
            </div>
          ))}
        </nav>
        <div className="p-4 border-t border-slate-700 text-sm">
          <div className="text-slate-300">{user?.name}</div>
          <div className="text-slate-500 text-xs mt-1">{user?.roles?.join('、')}</div>
          <button onClick={handleLogout} className="mt-2 text-slate-400 hover:text-white text-xs">
            退出登录
          </button>
        </div>
      </aside>
      <main className="flex-1 overflow-auto">
        <header className="h-14 bg-white border-b flex items-center px-6 text-sm text-gray-500">
          资产经营管理系统 · 管理后台
        </header>
        <div className="p-6">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
