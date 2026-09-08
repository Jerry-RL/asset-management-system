import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '@/lib/api';
import { useAuth } from '@/lib/auth';

export function HomePage() {
  const { user } = useAuth();
  const [unread, setUnread] = useState(0);

  useEffect(() => {
    api
      .get<number>('/notifications/unread-count')
      .then(setUnread)
      .catch(() => {});
  }, []);

  const entries = [
    { to: '/repair', icon: '🔧', label: '报事报修' },
    { to: '/bills', icon: '💰', label: '资产缴费' },
    { to: '/assets', icon: '🏢', label: '资产招租' },
    { to: '/messages', icon: '💬', label: '消息' },
  ];

  return (
    <div>
      <div className="bg-blue-600 text-white px-5 py-8">
        <div className="text-xl font-semibold">您好，{user?.name ?? '租户'}</div>
        <div className="text-white/80 text-sm mt-1">
          欢迎使用资管云{unread > 0 ? ` · ${unread} 条未读消息` : ''}
        </div>
      </div>
      <div className="grid grid-cols-4 gap-2 -mt-6 mx-4 bg-white rounded-xl p-4 shadow">
        {entries.map((e) => (
          <Link key={e.to} to={e.to} className="flex flex-col items-center text-xs">
            <span className="text-2xl">{e.icon}</span>
            <span className="mt-1">{e.label}</span>
          </Link>
        ))}
      </div>
      <div className="mx-4 mt-4 bg-white rounded-xl shadow divide-y">
        <Link to="/contracts" className="flex justify-between px-4 py-3 text-sm">
          我的合同 <span className="text-gray-300">›</span>
        </Link>
        <Link to="/bills" className="flex justify-between px-4 py-3 text-sm">
          账单明细 <span className="text-gray-300">›</span>
        </Link>
        <Link to="/vacate" className="flex justify-between px-4 py-3 text-sm">
          退租申请 <span className="text-gray-300">›</span>
        </Link>
      </div>
    </div>
  );
}
