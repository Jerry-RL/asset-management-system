import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, type PageResult } from '@/lib/api';

interface Task {
  id: number;
  taskType: string;
  assigneeId: number;
  deadline: string;
  status: string;
}

const TYPE_ROUTE: Record<string, string> = {
  repair: '/repair',
  dunning: '/dunning',
  dunning_l1: '/dunning',
  dunning_l2: '/dunning',
  dunning_l3: '/dunning',
  dunning_l4: '/dunning',
  dunning_l5: '/dunning',
  contract_approval: '/approval',
  meter: '/meter',
  charge: '/charge',
  vacate: '/vacate',
};

export function TasksPage() {
  const navigate = useNavigate();
  const [scope, setScope] = useState<'all' | 'mine'>('all');
  const [list, setList] = useState<Task[]>([]);

  const load = (s = scope) => {
    api
      .get<PageResult<Task>>(`/tasks?scope=${s}&page=1&pageSize=50`)
      .then((d) => setList(d.list ?? []))
      .catch(() => {});
  };

  useEffect(() => {
    load();
  }, [scope]);

  const complete = (id: number) => {
    api.post(`/tasks/${id}/complete`).then(() => load());
  };

  return (
    <div className="p-4">
      <div className="flex bg-white rounded-xl mb-4 overflow-hidden">
        {(['all', 'mine'] as const).map((s) => (
          <button
            key={s}
            className={`flex-1 py-2.5 text-sm ${scope === s ? 'text-teal-600 font-bold border-b-2 border-teal-600' : 'text-gray-500'}`}
            onClick={() => setScope(s)}
          >
            {s === 'all' ? '所有任务' : '个人任务'}
          </button>
        ))}
      </div>
      {list.map((t) => (
        <div key={t.id} className="bg-white rounded-xl p-4 mb-3 shadow">
          <div className="flex justify-between">
            <span className="font-semibold">{t.taskType}</span>
            <span className="text-teal-600 text-xs">
              {t.status === 'pending' ? '待办' : '已完成'}
            </span>
          </div>
          <div className="text-xs text-gray-400 mt-1">
            经办人 {t.assigneeId} · 截止 {t.deadline}
          </div>
          {t.status === 'pending' && (
            <div className="flex gap-2 mt-3">
              <button
                className="bg-teal-600 text-white text-sm rounded-lg px-4 py-1.5"
                onClick={() => navigate(TYPE_ROUTE[t.taskType] ?? '/')}
              >
                办理
              </button>
              <button
                className="border text-sm rounded-lg px-4 py-1.5"
                onClick={() => complete(t.id)}
              >
                完成
              </button>
            </div>
          )}
        </div>
      ))}
      {list.length === 0 && <div className="text-center text-gray-400 py-20">暂无任务</div>}
    </div>
  );
}
