import { useEffect, useState } from 'react';
import { api } from '@/lib/api';

interface Notification {
  id: number;
  title: string;
  content: string;
  createdAt: string;
  readAt?: string;
}

export function MessagesPage() {
  const [list, setList] = useState<Notification[]>([]);

  const load = () => {
    api
      .get<Notification[]>('/notifications?unreadOnly=false')
      .then((l) => setList(l ?? []))
      .catch(() => {});
  };

  useEffect(() => {
    load();
  }, []);

  const read = (id: number) => {
    api.post(`/notifications/${id}/read`).then(load);
  };

  return (
    <div className="p-4">
      {list.map((n) => (
        <div
          key={n.id}
          className={`bg-white rounded-xl p-4 mb-3 shadow ${n.readAt ? 'opacity-60' : 'border-l-4 border-blue-500'}`}
          onClick={() => read(n.id)}
        >
          <div className="font-semibold">{n.title}</div>
          <div className="text-sm text-gray-500 mt-1">{n.content}</div>
          <div className="text-xs text-gray-300 mt-1">{n.createdAt}</div>
        </div>
      ))}
      {list.length === 0 && <div className="text-center text-gray-400 py-20">暂无消息</div>}
    </div>
  );
}
