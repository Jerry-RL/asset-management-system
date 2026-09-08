import { useEffect, useState } from 'react';
import { api, type PageResult } from '@/lib/api';

interface Contract {
  id: number;
  contractNo: string;
  assetId: number;
  tenantId: number;
  startDate: string;
  endDate: string;
  rentAmount: number;
  rentType: string;
  paymentCycle: string;
  paymentStatus: string;
  status: string;
}

export function ContractsPage() {
  const [list, setList] = useState<Contract[]>([]);

  useEffect(() => {
    api
      .get<PageResult<Contract>>('/contracts?page=1&pageSize=50')
      .then((d) => setList(d.list ?? []))
      .catch(() => {});
  }, []);

  return (
    <div className="p-4">
      {list.map((c) => (
        <div key={c.id} className="bg-white rounded-xl p-4 mb-3 shadow">
          <div className="flex justify-between">
            <span className="font-semibold">{c.contractNo}</span>
            <span className="text-blue-600 text-xs">{c.status}</span>
          </div>
          <div className="text-sm text-gray-500 mt-2 space-y-1">
            <div>
              资产ID：{c.assetId} · 租户ID：{c.tenantId}
            </div>
            <div>
              租期：{c.startDate} ~ {c.endDate}
            </div>
            <div>
              租金：¥{c.rentAmount}（{c.rentType} / {c.paymentCycle}）
            </div>
            <div>缴费状态：{c.paymentStatus}</div>
          </div>
          <div className="text-blue-600 text-sm mt-2">查看合同文件 ›</div>
        </div>
      ))}
      {list.length === 0 && <div className="text-center text-gray-400 py-20">暂无合同</div>}
    </div>
  );
}
