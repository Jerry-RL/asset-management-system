import { useEffect, useState } from 'react';
import { api } from '@/lib/api';

interface Operations {
  assetTotal?: number;
  leasedCount?: number;
  vacantCount?: number;
  vacantArea?: number;
  leasedRate?: number;
  collectionRate?: number;
  receivable?: number;
  received?: number;
  arrears?: number;
  contractTotal?: number;
}

interface Consolidate {
  groupAssetTotal?: number;
  groupLeasedRate?: number;
  groupVacantArea?: number;
}

export function ConsolidatePage() {
  const [data, setData] = useState<Consolidate>({});

  useEffect(() => {
    api
      .get<Consolidate>('/dashboard/consolidate')
      .then(setData)
      .catch(() => {});
  }, []);

  const cards = [
    { label: '集团资产总数', value: data.groupAssetTotal ?? '-' },
    {
      label: '集团出租率',
      value: data.groupLeasedRate != null ? `${(data.groupLeasedRate * 100).toFixed(1)}%` : '-',
    },
    { label: '集团空置面积 (㎡)', value: data.groupVacantArea ?? '-' },
  ];

  return (
    <div>
      <h2 className="text-lg font-semibold mb-4">集团合并看板（FR-DASH-002）</h2>
      <div className="grid grid-cols-3 gap-4">
        {cards.map((c) => (
          <div key={c.label} className="bg-white rounded-lg shadow p-4">
            <div className="text-sm text-gray-500">{c.label}</div>
            <div className="text-2xl font-semibold mt-1">{c.value}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

export function DashboardPage() {
  const [data, setData] = useState<Operations>({});

  useEffect(() => {
    api
      .get<Operations>('/dashboard/operations')
      .then(setData)
      .catch(() => {});
  }, []);

  const cards = [
    { label: '资产总数', value: data.assetTotal ?? '-' },
    { label: '在租宗数', value: data.leasedCount ?? '-' },
    { label: '空置宗数', value: data.vacantCount ?? '-' },
    { label: '空置面积 (㎡)', value: data.vacantArea ?? '-' },
    {
      label: '出租率',
      value: data.leasedRate != null ? `${(data.leasedRate * 100).toFixed(1)}%` : '-',
    },
    {
      label: '收缴率',
      value: data.collectionRate != null ? `${(data.collectionRate * 100).toFixed(1)}%` : '-',
    },
    { label: '应收 (元)', value: data.receivable ?? '-' },
    { label: '实收 (元)', value: data.received ?? '-' },
    { label: '欠费 (元)', value: data.arrears ?? '-' },
    { label: '合同总数', value: data.contractTotal ?? '-' },
  ];

  return (
    <div>
      <h2 className="text-lg font-semibold mb-4">经营看板（FR-DASH-001）</h2>
      <div className="grid grid-cols-5 gap-4">
        {cards.map((c) => (
          <div key={c.label} className="bg-white rounded-lg shadow p-4">
            <div className="text-sm text-gray-500">{c.label}</div>
            <div className="text-2xl font-semibold mt-1">{c.value}</div>
          </div>
        ))}
      </div>
    </div>
  );
}
