import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, type PageResult } from '@/lib/api';

interface Bill {
  id: number;
  billNo: string;
  amount: number;
  paidAmount: number;
  dueDate: string;
  status: string;
}

const STATUS: Record<string, string> = { paid: '已缴', partial_paid: '部分缴', unpaid: '待缴' };

export function BillsPage() {
  const [list, setList] = useState<Bill[]>([]);

  useEffect(() => {
    api
      .get<PageResult<Bill>>('/billing/bills?page=1&pageSize=50')
      .then((d) => setList(d.list ?? []))
      .catch(() => {});
  }, []);

  return (
    <div className="p-4">
      {list.map((b) => (
        <Link
          key={b.id}
          to={`/bills/${b.id}`}
          className="block bg-white rounded-xl p-4 mb-3 shadow"
        >
          <div className="flex justify-between">
            <span className="text-sm text-gray-600">{b.billNo}</span>
            <span className="font-bold text-orange-600">¥{b.amount}</span>
          </div>
          <div className="text-xs text-gray-400 mt-1">
            到期 {b.dueDate} · 已缴 ¥{b.paidAmount}
          </div>
          <span
            className={`inline-block text-xs px-2 py-0.5 rounded mt-2 ${
              b.status === 'paid' ? 'bg-green-100 text-green-700' : 'bg-orange-100 text-orange-600'
            }`}
          >
            {STATUS[b.status] ?? b.status}
          </span>
        </Link>
      ))}
      {list.length === 0 && <div className="text-center text-gray-400 py-20">暂无账单</div>}
    </div>
  );
}
