import { useEffect, useState } from 'react';
import { api } from '@/lib/api';

interface VacateOrder {
  id: number;
  contractId: number;
  reason: string;
  status: string;
}

export function VacatePage() {
  const [list, setList] = useState<VacateOrder[]>([]);

  const load = () => {
    api
      .get<VacateOrder[]>('/vacate-orders')
      .then((l) => setList(l ?? []))
      .catch(() => {});
  };

  useEffect(() => {
    load();
  }, []);

  const inspect = (id: number) => {
    const remark = window.prompt('水电气读数/设施清点说明');
    if (remark === null) return;
    api
      .post(`/vacate-orders/${id}/inspection`, {
        waterReading: 0,
        electricReading: 0,
        remark,
      })
      .then(() => {
        window.alert('验收已提交');
        load();
      });
  };

  return (
    <div className="p-4">
      {list.map((o) => (
        <div key={o.id} className="bg-white rounded-xl p-4 mb-3 shadow">
          <div className="flex justify-between">
            <span className="font-semibold">退租单 #{o.id}</span>
            <span className="text-teal-600 text-xs">{o.status}</span>
          </div>
          <div className="text-xs text-gray-400 mt-1">
            合同ID {o.contractId} · {o.reason}
          </div>
          {o.status === 'applying' && (
            <button
              className="mt-3 bg-teal-600 text-white text-sm rounded-lg px-4 py-1.5"
              onClick={() => inspect(o.id)}
            >
              提交清场验收
            </button>
          )}
        </div>
      ))}
      {list.length === 0 && <div className="text-center text-gray-400 py-20">暂无清场任务</div>}
    </div>
  );
}
