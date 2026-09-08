import { useState } from 'react';
import { api } from '@/lib/api';

export function ChargePage() {
  const [contractId, setContractId] = useState('');
  const [amount, setAmount] = useState('');
  const [method, setMethod] = useState('cash');

  const submit = async () => {
    if (!contractId || !amount) return;
    try {
      await api.post('/payments/worker/register', {
        contractId: Number(contractId),
        amount: Number(amount),
        method,
      });
      window.alert('收款已登记（待财务确认）');
    } catch (e) {
      window.alert(e instanceof Error ? e.message : '登记失败');
    }
  };

  return (
    <div className="p-4">
      <div className="bg-white rounded-xl p-4 shadow space-y-4">
        <div>
          <label className="block text-sm text-gray-500 mb-1">合同ID</label>
          <input
            className="w-full border rounded-lg px-3 py-2"
            type="number"
            value={contractId}
            onChange={(e) => setContractId(e.target.value)}
          />
        </div>
        <div>
          <label className="block text-sm text-gray-500 mb-1">收款金额（元）</label>
          <input
            className="w-full border rounded-lg px-3 py-2"
            type="number"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
          />
        </div>
        <div>
          <label className="block text-sm text-gray-500 mb-1">收款方式</label>
          <select
            className="w-full border rounded-lg px-3 py-2"
            value={method}
            onChange={(e) => setMethod(e.target.value)}
          >
            <option value="cash">现金</option>
            <option value="wechat">扫码</option>
            <option value="bank_transfer">转账</option>
          </select>
        </div>
        <button className="w-full bg-teal-600 text-white rounded-lg py-3" onClick={submit}>
          登记现场收款
        </button>
        <p className="text-xs text-gray-400">登记后标记「待确认」，财务到账确认后计入实收</p>
      </div>
    </div>
  );
}
