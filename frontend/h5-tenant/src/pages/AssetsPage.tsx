import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '@/lib/api';

/** 招租发布（`/lease-listings?status=active`，字段见 V59）。 */
interface Listing {
  id: number;
  assetId: number;
  assetNo?: string;
  assetName?: string;
  coverImageUrl?: string;
  /** 年租金（元/年）。旧字段 `rentAmount` 是月均额，端上不再使用 */
  annualRent?: number;
  rentNegotiable?: boolean;
  recommended?: boolean;
  intro?: string;
  status: string;
}

const rentLabel = (item: Listing): string =>
  item.annualRent ? `¥${item.annualRent.toLocaleString('zh-CN')}/年` : '面议';

const titleOf = (item: Listing): string =>
  item.assetName || item.assetNo || `招租资产 #${item.assetId}`;

export function AssetsPage() {
  const [list, setList] = useState<Listing[]>([]);

  useEffect(() => {
    api
      // 只有审批通过的招租才是 active；待审批 / 已驳回 / 已关闭不可见
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
          className="flex bg-white rounded-xl mb-3 shadow overflow-hidden"
        >
          {item.coverImageUrl && (
            <img
              src={item.coverImageUrl}
              alt={titleOf(item)}
              className="w-28 h-24 object-cover shrink-0 bg-slate-100"
            />
          )}
          <div className="flex-1 p-3 min-w-0">
            <div className="flex items-center">
              <span className="font-semibold truncate">{titleOf(item)}</span>
              {item.recommended && (
                <span className="ml-2 shrink-0 rounded px-1.5 text-xs text-amber-700 bg-amber-100">
                  推荐
                </span>
              )}
            </div>
            <div className="text-orange-600 font-bold mt-1">{rentLabel(item)}</div>
            {item.intro && (
              <div className="text-gray-400 text-xs mt-1 line-clamp-2">{item.intro}</div>
            )}
          </div>
        </Link>
      ))}
      {list.length === 0 && <div className="text-center text-gray-400 py-20">暂无招租资产</div>}
    </div>
  );
}
