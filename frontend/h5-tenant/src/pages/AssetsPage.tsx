import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '@/lib/api';

interface Listing {
  id: number;
  assetId: number;
  rentAmount?: number;
  rentNegotiable?: boolean;
  status: string;
}

export function AssetsPage() {
  const [list, setList] = useState<Listing[]>([]);

  useEffect(() => {
    api
      .get<Listing[]>('/lease-listings?status=active')
      .then((l) => setList(l ?? []))
      .catch(() => {});
  }, []);

  return (
    <div className="p-4">
      <Link to="/tender" className="block bg-blue-600 text-white rounded-xl px-4 py-3 mb-4">
        📢 公开招租 · 在线报名 ›
      </Link>
      {list.map((item) => (
        <Link
          key={item.id}
          to={`/assets/${item.assetId}`}
          className="block bg-white rounded-xl p-4 mb-3 shadow"
        >
          <div className="font-semibold">招租资产 #{item.assetId}</div>
          <div className="text-orange-600 font-bold mt-1">
            {item.rentAmount ? `¥${item.rentAmount}/月` : '面议'}
          </div>
          <div className="text-gray-400 text-xs mt-1">
            {item.status === 'active' ? '招租中' : '已结束'}
          </div>
        </Link>
      ))}
      {list.length === 0 && <div className="text-center text-gray-400 py-20">暂无招租资产</div>}
    </div>
  );
}
