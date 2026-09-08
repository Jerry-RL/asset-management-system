import { useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { useAuth } from '@/lib/auth';

interface Announcement {
  id: number;
  title: string;
  registerDeadline: string;
  displayPeriodDays: number;
}

export function TenderPage() {
  const { user } = useAuth();
  const [list, setList] = useState<Announcement[]>([]);

  useEffect(() => {
    api
      .get<Announcement[]>('/tender/announcements?status=open')
      .then((l) => setList(l ?? []))
      .catch(() => {});
  }, []);

  const apply = async (id: number) => {
    if (!user?.tenantId) {
      window.alert('请先使用租户账号登录（演示：tenant / admin123）');
      return;
    }
    try {
      await api.post(`/tender/announcements/${id}/applications`, { tenantId: user.tenantId });
      window.alert('报名成功');
    } catch (e) {
      window.alert(e instanceof Error ? e.message : '报名失败');
    }
  };

  return (
    <div className="p-4">
      {list.map((a) => (
        <div key={a.id} className="bg-white rounded-xl p-4 mb-3 shadow">
          <div className="font-semibold">{a.title}</div>
          <div className="text-xs text-gray-400 mt-1">报名截止：{a.registerDeadline}</div>
          <div className="text-xs text-gray-400">公示期：{a.displayPeriodDays} 天</div>
          <button
            className="mt-3 bg-blue-600 text-white text-sm rounded-lg px-4 py-1.5"
            onClick={() => apply(a.id)}
          >
            报名
          </button>
        </div>
      ))}
      {list.length === 0 && <div className="text-center text-gray-400 py-20">暂无招租公告</div>}
    </div>
  );
}
