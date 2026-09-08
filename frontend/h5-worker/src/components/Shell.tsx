import { NavLink, Outlet } from 'react-router-dom';
import { cn } from '@/lib/utils';

const TABS = [
  { path: '/', label: '任务中心', icon: '📋' },
  { path: '/asset-query', label: '资产查询', icon: '🔍' },
  { path: '/my', label: '我的', icon: '👤' },
];

export function Shell() {
  return (
    <div className="min-h-screen bg-gray-50 pb-16">
      <Outlet />
      <nav className="fixed bottom-0 left-0 right-0 bg-white border-t flex z-50">
        {TABS.map((t) => (
          <NavLink
            key={t.path}
            to={t.path}
            end={t.path === '/'}
            className={({ isActive }) =>
              cn(
                'flex-1 flex flex-col items-center py-2 text-xs',
                isActive ? 'text-teal-600' : 'text-gray-400',
              )
            }
          >
            <span className="text-xl">{t.icon}</span>
            <span>{t.label}</span>
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
