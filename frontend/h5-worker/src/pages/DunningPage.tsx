import { useState } from 'react';
import { api } from '@/lib/api';

export function DunningPage() {
  const [billId, setBillId] = useState('');
  const [level, setLevel] = useState(2);
  const [content, setContent] = useState('');

  const submit = async () => {
    if (!billId) return;
    try {
      await api.post('/dunning/records', {
        billId: Number(billId),
        level,
        method: 'notice_post',
        content,
        result: '已张贴',
      });
      window.alert('催缴记录已提交');
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
          placeholder="账单ID"
          value={billId}
          onChange={(e) => setBillId(e.target.value)}
        />
        <div>
          <label className="block text-sm text-gray-500 mb-1">催缴等级</label>
          <div className="flex gap-2">
            {[1, 2, 3, 4, 5].map((l) => (
              <button
                key={l}
                className={`flex-1 py-2 rounded-lg text-sm border ${level === l ? 'bg-teal-600 text-white' : ''}`}
                onClick={() => setLevel(l)}
              >
                L{l}
              </button>
            ))}
          </div>
        </div>
        <textarea
          className="w-full border rounded-lg px-3 py-2"
          rows={3}
          placeholder="张贴/催缴说明"
          value={content}
          onChange={(e) => setContent(e.target.value)}
        />
        <button className="w-full bg-teal-600 text-white rounded-lg py-3" onClick={submit}>
          提交催缴留痕
        </button>
      </div>
    </div>
  );
}
