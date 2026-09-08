import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '@/lib/api';

export function RepairPage() {
  const navigate = useNavigate();
  const [assetId, setAssetId] = useState('');
  const [desc, setDesc] = useState('');
  const [loading, setLoading] = useState(false);

  const submit = async () => {
    if (!assetId || !desc) return;
    setLoading(true);
    try {
      await api.post('/repairs', { assetId: Number(assetId), description: desc });
      navigate('/');
    } catch (e) {
      window.alert(e instanceof Error ? e.message : '报修失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="p-4">
      <div className="bg-white rounded-xl p-4 shadow space-y-4">
        <div>
          <label className="block text-sm text-gray-500 mb-1">绑定资产ID</label>
          <input
            className="w-full border rounded-lg px-3 py-2"
            type="number"
            value={assetId}
            onChange={(e) => setAssetId(e.target.value)}
          />
        </div>
        <div>
          <label className="block text-sm text-gray-500 mb-1">报修说明</label>
          <textarea
            className="w-full border rounded-lg px-3 py-2"
            rows={4}
            value={desc}
            onChange={(e) => setDesc(e.target.value)}
          />
        </div>
        <button
          className="w-full bg-blue-600 text-white rounded-lg py-3 disabled:opacity-50"
          disabled={loading}
          onClick={submit}
        >
          提交报修
        </button>
      </div>
    </div>
  );
}
