import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '@/lib/auth';

export function MyPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const items = [
    { to: '/contracts', label: '我的合同' },
    { to: '/bills', label: '账单明细' },
    { to: '/repair', label: '报事报修' },
    { to: '/vacate', label: '退租申请' },
    { to: '/messages', label: '消息中心' },
  ];

  return (
    <div>
      <div className="bg-blue-600 text-white px-5 py-10">
        <div className="text-xl font-semibold">{user?.name ?? '未实名'}</div>
        <div className="text-white/70 text-sm mt-1">{user?.username ?? ''}</div>
      </div>
      <div className="mx-4 -mt-6 bg-white rounded-xl shadow divide-y">
        {items.map((i) => (
          <Link key={i.to} to={i.to} className="flex justify-between px-4 py-3 text-sm">
            {i.label} <span className="text-gray-300">›</span>
          </Link>
        ))}
      </div>
      <button
        className="block mx-4 mt-6 w-[calc(100%-2rem)] bg-white text-orange-600 rounded-xl py-3"
        onClick={handleLogout}
      >
        退出登录
      </button>
    </div>
  );
}
