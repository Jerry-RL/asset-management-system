import { useNavigate } from 'react-router-dom';
import { useAuth } from '@/lib/auth';

export function MyPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const items = [
    { to: '/charge', label: '资产收费' },
    { to: '/inspect', label: '资产巡检' },
    { to: '/repair', label: '资产维修' },
    { to: '/dunning', label: '资产催租' },
    { to: '/approval', label: '合同审批' },
    { to: '/vacate', label: '清场验收' },
    { to: '/meter', label: '抄表' },
    { to: '/payment-confirm', label: '现场收款到账' },
  ];

  return (
    <div>
      <div className="bg-teal-600 text-white px-5 py-10">
        <div className="text-xl font-semibold">{user?.name ?? '外勤人员'}</div>
        <div className="text-white/70 text-sm mt-1">工作端 · 一线运维</div>
      </div>
      <div className="grid grid-cols-3 gap-2 mx-4 -mt-6 bg-white rounded-xl p-4 shadow">
        {items.map((i) => (
          <button key={i.to} className="text-xs py-3 text-center" onClick={() => navigate(i.to)}>
            {i.label}
          </button>
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
