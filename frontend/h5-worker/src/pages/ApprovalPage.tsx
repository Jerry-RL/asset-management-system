import { useEffect, useState } from 'react';
import { api } from '@/lib/api';

interface ApprovalTask {
  id: number;
  instanceId: number;
  nodeId: string;
  status: string;
}

export function ApprovalPage() {
  const [list, setList] = useState<ApprovalTask[]>([]);

  const load = () => {
    api
      .get<ApprovalTask[]>('/approvals/tasks')
      .then((l) => setList(l ?? []))
      .catch(() => {});
  };

  useEffect(() => {
    load();
  }, []);

  const approve = (instanceId: number) =>
    api.post(`/approvals/${instanceId}/approve`, { comment: '同意' }).then(load);
  const reject = (instanceId: number) =>
    api.post(`/approvals/${instanceId}/reject`, { comment: '驳回' }).then(load);

  return (
    <div className="p-4">
      {list.map((t) => (
        <div key={t.id} className="bg-white rounded-xl p-4 mb-3 shadow">
          <div className="font-semibold">审批任务 #{t.id}</div>
          <div className="text-xs text-gray-400 mt-1">
            节点 {t.nodeId} · 状态 {t.status}
          </div>
          <div className="flex gap-2 mt-3">
            <button
              className="bg-teal-600 text-white text-sm rounded-lg px-4 py-1.5"
              onClick={() => approve(t.instanceId)}
            >
              通过
            </button>
            <button
              className="border text-sm rounded-lg px-4 py-1.5"
              onClick={() => reject(t.instanceId)}
            >
              驳回
            </button>
          </div>
        </div>
      ))}
      {list.length === 0 && <div className="text-center text-gray-400 py-20">暂无待审批任务</div>}
    </div>
  );
}
