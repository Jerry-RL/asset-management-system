import { useEffect, useState } from 'react';
import { api, type PageResult } from '@/lib/api';

interface Repair {
  id: number;
  assetId: number;
  description: string;
  reporterName: string;
  status: string;
}

export function RepairPage() {
  const [list, setList] = useState<Repair[]>([]);

  const load = () => {
    api
      .get<PageResult<Repair>>('/repairs?page=1&pageSize=50')
      .then((d) => setList(d.list ?? []))
      .catch(() => {});
  };

  useEffect(() => {
    load();
  }, []);

  const accept = (id: number) => api.post(`/repairs/${id}/dispatch`, {}).then(load);
  const complete = (id: number) =>
    api.post(`/repairs/${id}/complete`, { resultRemark: '维修完成' }).then(load);

  return (
    <div className="p-4">
      {list.map((r) => (
        <div key={r.id} className="bg-white rounded-xl p-4 mb-3 shadow">
          <div className="flex justify-between">
            <span className="font-semibold">{r.description}</span>
            <span className="text-teal-600 text-xs">{r.status}</span>
          </div>
          <div className="text-xs text-gray-400 mt-1">
            资产ID {r.assetId} · 报修人 {r.reporterName}
          </div>
          <div className="flex gap-2 mt-3">
            <button
              className="bg-teal-600 text-white text-sm rounded-lg px-4 py-1.5"
              onClick={() => accept(r.id)}
            >
              接单/派工
            </button>
            <button
              className="border text-sm rounded-lg px-4 py-1.5"
              onClick={() => complete(r.id)}
            >
              完工
            </button>
          </div>
        </div>
      ))}
      {list.length === 0 && <div className="text-center text-gray-400 py-20">暂无维修工单</div>}
    </div>
  );
}
