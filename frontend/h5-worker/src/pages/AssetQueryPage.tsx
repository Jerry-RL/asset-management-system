import { useState } from 'react';
import { api, type PageResult } from '@/lib/api';

interface Asset {
  id: number;
  assetNo: string;
  name: string;
  area?: number;
  leaseControlStatus: string;
  province?: string;
  city?: string;
  district?: string;
  address?: string;
}

export function AssetQueryPage() {
  const [keyword, setKeyword] = useState('');
  const [list, setList] = useState<Asset[]>([]);

  const search = () => {
    api
      .get<PageResult<Asset>>(`/assets?keyword=${encodeURIComponent(keyword)}&page=1&pageSize=50`)
      .then((d) => setList(d.list ?? []))
      .catch(() => {});
  };

  return (
    <div className="p-4">
      <div className="flex gap-2 mb-4">
        <input
          className="flex-1 border rounded-lg px-3 py-2"
          placeholder="搜索资产编号/名称"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && search()}
        />
        <button className="bg-teal-600 text-white rounded-lg px-4" onClick={search}>
          搜索
        </button>
      </div>
      {list.map((a) => (
        <div key={a.id} className="bg-white rounded-xl p-4 mb-3 shadow">
          <div className="font-semibold">{a.name}</div>
          <div className="text-xs text-gray-400 mt-1 space-y-0.5">
            <div>编号：{a.assetNo}</div>
            <div>
              面积：{a.area ?? '-'} ㎡ · 状态：{a.leaseControlStatus}
            </div>
            <div>
              坐落：{a.province ?? ''}
              {a.city ?? ''}
              {a.district ?? ''}
              {a.address ?? ''}
            </div>
          </div>
        </div>
      ))}
      {list.length === 0 && (
        <div className="text-center text-gray-400 py-20">输入关键字查询资产</div>
      )}
    </div>
  );
}
