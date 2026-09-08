import { useEffect, useState } from 'react';
import { api } from '@/lib/api';

interface Meter {
  id: number;
  meterNo: string;
  meterType: string;
}

export function MeterPage() {
  const [meters, setMeters] = useState<Meter[]>([]);
  const [meterId, setMeterId] = useState('');
  const [reading, setReading] = useState('');

  useEffect(() => {
    api
      .get<Meter[]>('/meters')
      .then((l) => setMeters(l ?? []))
      .catch(() => {});
  }, []);

  const submit = async () => {
    if (!meterId || !reading) return;
    try {
      await api.post(`/meters/${meterId}/readings`, {
        reading: Number(reading),
        readingDate: new Date().toISOString().slice(0, 10),
      });
      window.alert('抄表成功');
      setReading('');
    } catch (e) {
      window.alert(e instanceof Error ? e.message : '抄表失败');
    }
  };

  return (
    <div className="p-4">
      <div className="bg-white rounded-xl p-4 shadow space-y-4">
        <div>
          <label className="block text-sm text-gray-500 mb-1">选择表计</label>
          <select
            className="w-full border rounded-lg px-3 py-2"
            value={meterId}
            onChange={(e) => setMeterId(e.target.value)}
          >
            <option value="">请选择表计</option>
            {meters.map((m) => (
              <option key={m.id} value={m.id}>
                {m.meterNo}（{m.meterType}）
              </option>
            ))}
          </select>
        </div>
        <input
          className="w-full border rounded-lg px-3 py-2"
          type="number"
          placeholder="本次读数"
          value={reading}
          onChange={(e) => setReading(e.target.value)}
        />
        <button className="w-full bg-teal-600 text-white rounded-lg py-3" onClick={submit}>
          提交抄表
        </button>
      </div>
    </div>
  );
}
