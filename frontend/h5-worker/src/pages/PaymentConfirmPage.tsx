import { useEffect, useState } from 'react';
import { api, type PageResult } from '@/lib/api';

interface Payment {
  id: number;
  paymentNo: string;
  amount: number;
  contractId: number;
  method: string;
  confirmStatus: string;
}

export function PaymentConfirmPage() {
  const [list, setList] = useState<Payment[]>([]);

  useEffect(() => {
    api
      .get<PageResult<Payment>>(
        '/payments?confirmStatus=pending&channel=worker_mp&page=1&pageSize=50',
      )
      .then((d) => setList(d.list ?? []))
      .catch(() => {});
  }, []);

  return (
    <div className="p-4">
      <div className="bg-teal-50 text-teal-700 rounded-xl px-4 py-3 text-xs mb-4">
        现场收款「待确认」清单（到账确认由财务在 PC 端办理）
      </div>
      {list.map((p) => (
        <div key={p.id} className="bg-white rounded-xl p-4 mb-3 shadow">
          <div className="flex justify-between">
            <span className="font-semibold">{p.paymentNo}</span>
            <span className="font-bold text-orange-600">¥{p.amount}</span>
          </div>
          <div className="text-xs text-gray-400 mt-1">
            合同ID {p.contractId} · {p.method} · {p.confirmStatus}
          </div>
        </div>
      ))}
      {list.length === 0 && <div className="text-center text-gray-400 py-20">暂无待确认收款</div>}
    </div>
  );
}
