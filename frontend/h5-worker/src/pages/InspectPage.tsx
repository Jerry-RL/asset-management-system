import { useState } from 'react';
import { api } from '@/lib/api';

export function InspectPage() {
  const [assetId, setAssetId] = useState('');
  const [result, setResult] = useState('');
  const [hazard, setHazard] = useState('');

  const submit = async () => {
    if (!assetId) return;
    try {
      await api.post('/inspections', {
        assetId: Number(assetId),
        result,
        hazardDesc: hazard,
        planDate: new Date().toISOString().slice(0, 10),
      });
      window.alert('巡检已提交');
      setAssetId('');
      setResult('');
      setHazard('');
    } catch (e) {
      window.alert(e instanceof Error ? e.message : '提交失败');
    }
  };

  return (
    <div className="p-4">
      <div className="bg-white rounded-xl p-4 shadow space-y-4">
        <input
          className="w-full border rounded-lg px-3 py-2"
          type="number"
          placeholder="资产ID"
          value={assetId}
          onChange={(e) => setAssetId(e.target.value)}
        />
        <input
          className="w-full border rounded-lg px-3 py-2"
          placeholder="巡检结果（正常/异常）"
          value={result}
          onChange={(e) => setResult(e.target.value)}
        />
        <textarea
          className="w-full border rounded-lg px-3 py-2"
          rows={3}
          placeholder="隐患描述（可转报修工单）"
          value={hazard}
          onChange={(e) => setHazard(e.target.value)}
        />
        <button className="w-full bg-teal-600 text-white rounded-lg py-3" onClick={submit}>
          提交巡检
        </button>
      </div>
    </div>
  );
}
