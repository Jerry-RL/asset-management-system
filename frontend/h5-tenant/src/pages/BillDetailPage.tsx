import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { api } from '@/lib/api';

interface Bill {
  id: number;
  billNo: string;
  billType: string;
  amount: number;
  paidAmount: number;
  lateFeeAmount: number;
  periodStart: string;
  periodEnd: string;
  dueDate: string;
  status: string;
}

export function BillDetailPage() {
  const { id } = useParams();
  const [bill, setBill] = useState<Bill | null>(null);
  const [paying, setPaying] = useState(false);

  useEffect(() => {
    if (id)
      api
        .get<Bill>(`/billing/bills/${id}`)
        .then(setBill)
        .catch(() => {});
  }, [id]);

  const pay = () => {
    setPaying(true);
    api
      .post('/billing/payments/wechat', { billIds: [Number(id)] })
      .catch(() => {})
      .finally(() => setPaying(false));
  };

  if (!bill) return <div className="p-8 text-center text-gray-400">加载中...</div>;

  return (
    <div className="p-4">
      <div className="bg-white rounded-xl p-6 shadow text-center">
        <div className="text-4xl font-bold text-orange-600">¥{bill.amount}</div>
        <div className="text-sm text-gray-500 mt-4 space-y-2 text-left">
          <div>账单编号：{bill.billNo}</div>
          <div>
            账期：{bill.periodStart} ~ {bill.periodEnd}
          </div>
          <div>到期日：{bill.dueDate}</div>
          <div>已核销：¥{bill.paidAmount}</div>
          {bill.lateFeeAmount > 0 && <div>滞纳金：¥{bill.lateFeeAmount}</div>}
        </div>
      </div>
      {bill.status !== 'paid' && (
        <button
          className="w-full bg-blue-600 text-white rounded-xl py-3 mt-4 disabled:opacity-50"
          disabled={paying}
          onClick={pay}
        >
          立即缴费
        </button>
      )}
    </div>
  );
}
