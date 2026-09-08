import { useEffect, useState } from 'react';
import { api, type PageResult } from '@/lib/api';

interface Contract {
  id: number;
  contractNo: string;
}

export function VacatePage() {
  const [contracts, setContracts] = useState<Contract[]>([]);
  const [contractId, setContractId] = useState('');
  const [reason, setReason] = useState('');
  const [date, setDate] = useState('');

  useEffect(() => {
    api
      .get<PageResult<Contract>>('/contracts?page=1&pageSize=50')
      .then((d) => setContracts(d.list ?? []))
      .catch(() => {});
  }, []);

  const submit = async () => {
    if (!contractId) return;
    try {
      await api.post(`/contracts/${contractId}/vacate`, { reason, expectedVacateDate: date });
      window.alert('退租申请已提交');
    } catch (e) {
      window.alert(e instanceof Error ? e.message : '提交失败');
    }
  };

  return (
    <div className="p-4">
      <div className="bg-white rounded-xl p-4 shadow space-y-4">
        <div>
          <label className="block text-sm text-gray-500 mb-1">选择合同</label>
          <select
            className="w-full border rounded-lg px-3 py-2"
            value={contractId}
            onChange={(e) => setContractId(e.target.value)}
          >
            <option value="">请选择合同</option>
            {contracts.map((c) => (
              <option key={c.id} value={c.id}>
                {c.contractNo}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="block text-sm text-gray-500 mb-1">退租原因</label>
          <textarea
            className="w-full border rounded-lg px-3 py-2"
            rows={3}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </div>
        <div>
          <label className="block text-sm text-gray-500 mb-1">期望退租日期</label>
          <input
            type="date"
            className="w-full border rounded-lg px-3 py-2"
            value={date}
            onChange={(e) => setDate(e.target.value)}
          />
        </div>
        <button className="w-full bg-blue-600 text-white rounded-lg py-3" onClick={submit}>
          提交退租申请
        </button>
      </div>
    </div>
  );
}
