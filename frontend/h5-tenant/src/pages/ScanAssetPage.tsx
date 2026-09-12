import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';

interface ScanListing {
  id: number;
  rentAmount?: number;
  rentNegotiable?: boolean;
  status?: string;
  remark?: string;
  publishedAt?: string;
}

interface ScanAsset {
  id: number;
  assetNo: string;
  name: string;
  assetType?: string;
  area?: number;
  leaseControlStatus?: string;
  province?: string;
  city?: string;
  district?: string;
  address?: string;
  baseRentAssessed?: number;
  usageType?: string;
  structureType?: string;
  listing?: ScanListing | null;
}

const STATUS_LABEL: Record<string, string> = {
  vacant: '空置',
  leasing: '招租中',
  leased: '在租',
  partial_leased: '部分出租',
  self_use: '自用',
  occupied: '占用',
  vacating: '退租中',
  disposing: '处置中',
  exited: '已退出',
};

const STATUS_TONE: Record<string, string> = {
  vacant: 'bg-emerald-50 text-emerald-700',
  leasing: 'bg-sky-50 text-sky-700',
  leased: 'bg-blue-50 text-blue-700',
  partial_leased: 'bg-indigo-50 text-indigo-700',
  self_use: 'bg-amber-50 text-amber-700',
  occupied: 'bg-orange-50 text-orange-700',
  vacating: 'bg-rose-50 text-rose-700',
  disposing: 'bg-stone-100 text-stone-600',
  exited: 'bg-gray-100 text-gray-500',
};

/** 公开扫码页：免登录拉取 /public/assets/{id}/scan */
export function ScanAssetPage() {
  const { id } = useParams();
  const [asset, setAsset] = useState<ScanAsset | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    setError('');
    fetch(`/api/v1/public/assets/${id}/scan`)
      .then(async (resp) => {
        const body = (await resp.json()) as {
          code: number;
          message?: string;
          data: ScanAsset;
        };
        if (body.code !== 0) {
          throw new Error(body.message || '加载失败');
        }
        setAsset(body.data);
      })
      .catch((e: unknown) => {
        setAsset(null);
        setError(e instanceof Error ? e.message : '加载失败');
      })
      .finally(() => setLoading(false));
  }, [id]);

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-[#f5f7fa] text-gray-400">
        加载中…
      </div>
    );
  }

  if (error || !asset) {
    return (
      <div className="min-h-screen flex items-center justify-center p-6 text-center bg-[#f5f7fa] text-gray-500">
        {error || '资产不存在'}
      </div>
    );
  }

  const address =
    [asset.province, asset.city, asset.district, asset.address].filter(Boolean).join('') || '—';
  const statusKey = asset.leaseControlStatus ?? '';
  const status = STATUS_LABEL[statusKey] ?? (statusKey || '—');
  const statusTone = STATUS_TONE[statusKey] ?? 'bg-gray-100 text-gray-600';
  const listing = asset.listing;

  const rows: [string, string][] = [
    ['资产编号', asset.assetNo],
    ['类型', asset.assetType === 'land' ? '土地类' : '房产类'],
    ['面积', asset.area != null ? `${asset.area} ㎡` : '—'],
    ['坐落', address],
    ['评估租金', asset.baseRentAssessed != null ? `¥${asset.baseRentAssessed}` : '—'],
  ];
  if (asset.usageType) rows.push(['用途', asset.usageType]);
  if (asset.structureType) rows.push(['结构', asset.structureType]);

  return (
    <div className="min-h-screen bg-[#f5f7fa] pb-12">
      <header className="relative overflow-hidden bg-[#0f6b63] text-white px-5 pt-11 pb-14">
        <div
          className="pointer-events-none absolute -right-8 -top-10 h-40 w-40 rounded-full bg-white/10"
          aria-hidden
        />
        <div
          className="pointer-events-none absolute right-16 bottom-0 h-24 w-24 rounded-full bg-white/5"
          aria-hidden
        />
        <div className="relative">
          <div className="inline-flex items-center gap-1.5 rounded-full bg-white/15 px-2.5 py-0.5 text-[11px] tracking-wide mb-3">
            一产一码 · 资产扫码查询
          </div>
          <h1 className="text-[22px] font-semibold m-0 leading-snug tracking-tight">
            {asset.name}
          </h1>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <span
              className={`inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium ${statusTone}`}
            >
              {status}
            </span>
            <span className="text-white/75 text-xs">{asset.assetNo}</span>
          </div>
        </div>
      </header>

      <main className="px-4 -mt-8 space-y-3 relative z-10">
        {listing && (
          <section className="rounded-2xl bg-white shadow-sm border border-black/[0.04] p-4">
            <div className="flex items-start justify-between gap-3">
              <div>
                <div className="text-xs text-teal-700/80 mb-1">招租信息</div>
                <div className="text-2xl font-semibold text-[#0f6b63] tracking-tight">
                  {listing.rentAmount != null ? `¥${listing.rentAmount}` : '面议'}
                  <span className="text-sm font-normal text-gray-400 ml-1">
                    {listing.rentNegotiable ? '可议' : ''}
                  </span>
                </div>
              </div>
              <span className="shrink-0 rounded-lg bg-teal-50 text-teal-700 text-xs px-2 py-1">
                可咨询
              </span>
            </div>
            {listing.remark ? (
              <p className="mt-3 mb-0 text-sm text-gray-600 leading-relaxed">{listing.remark}</p>
            ) : null}
          </section>
        )}

        <section className="rounded-2xl bg-white shadow-sm border border-black/[0.04] overflow-hidden">
          <div className="px-4 py-3 border-b border-gray-50 text-sm font-medium text-gray-800">
            资产概况
          </div>
          <div className="px-4">
            {rows.map(([k, v], i) => (
              <div
                key={k}
                className={`flex gap-3 py-3 text-sm ${i < rows.length - 1 ? 'border-b border-gray-50' : ''}`}
              >
                <span className="w-[4.5rem] shrink-0 text-gray-400">{k}</span>
                <span className="flex-1 min-w-0 break-words text-gray-800">{v}</span>
              </div>
            ))}
          </div>
        </section>

        {!listing && asset.leaseControlStatus === 'leasing' && (
          <p className="text-center text-sm text-gray-400 pt-4">招租详情更新中，请稍后查看</p>
        )}

        <p className="text-center text-[11px] text-gray-300 pt-2">资产经营管理系统 · 一产一码</p>
      </main>
    </div>
  );
}
