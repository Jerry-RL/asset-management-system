import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { api } from '@/lib/api';

interface Asset {
  id: number;
  assetNo: string;
  name: string;
  assetType: string;
  area?: number;
  leaseControlStatus: string;
  province?: string;
  city?: string;
  district?: string;
  address?: string;
  baseRentAssessed?: number;
}

export function AssetDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [asset, setAsset] = useState<Asset | null>(null);

  useEffect(() => {
    if (id)
      api
        .get<Asset>(`/assets/${id}`)
        .then(setAsset)
        .catch(() => {});
  }, [id]);

  if (!asset) return <div className="p-8 text-center text-gray-400">加载中...</div>;

  const rows: [string, string][] = [
    ['资产编号', asset.assetNo],
    ['类型', asset.assetType === 'property' ? '房产类' : '土地类'],
    ['面积', `${asset.area ?? '-'} ㎡`],
    [
      '坐落',
      `${asset.province ?? ''}${asset.city ?? ''}${asset.district ?? ''}${asset.address ?? ''}`,
    ],
    ['租控状态', asset.leaseControlStatus],
    ['评估租金', asset.baseRentAssessed ? `¥${asset.baseRentAssessed}` : '-'],
  ];

  return (
    <div className="p-4">
      <div className="bg-white rounded-xl p-4 shadow">
        <div className="text-lg font-semibold mb-3">{asset.name}</div>
        {rows.map(([k, v]) => (
          <div key={k} className="flex py-2 border-b border-gray-50 text-sm">
            <span className="w-24 text-gray-400 shrink-0">{k}</span>
            <span className="flex-1">{v}</span>
          </div>
        ))}
      </div>
      {asset.leaseControlStatus === 'leasing' && (
        <button
          className="w-full bg-blue-600 text-white rounded-xl py-3 mt-4"
          onClick={() => navigate('/tender')}
        >
          立即报名
        </button>
      )}
    </div>
  );
}
