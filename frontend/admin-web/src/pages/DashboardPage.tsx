import { useEffect, useState } from 'react';
import { ArrowRightOutlined, DashboardOutlined, FundOutlined } from '@ant-design/icons';
import { api } from '@/lib/api';
import { LEASE_CONTROL_STATUS, enumLabel } from '@/lib/labels';
import { DASHBOARD_ICONS } from '@/lib/menuIcons';
import { TableActions } from '@/components/TableActions';

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

interface CompanyRow {
  companyId?: number;
  companyName?: string;
  assetTotal?: number;
  leasedRate?: number;
  vacantArea?: number;
  arrears?: number;
  collectionRate?: number;
}

function StatCard({
  label,
  value,
  highlight,
  icon,
}: {
  label: string;
  value: string | number;
  highlight?: boolean;
  icon?: React.ReactNode;
}) {
  return (
    <div
      className={
        highlight
          ? 'rounded-lg bg-gradient-to-br from-[#e6f4ff] to-white border border-blue-100 p-3 sm:p-4 relative overflow-hidden min-w-0'
          : 'bg-white rounded-lg border border-[var(--ams-border)] p-3 sm:p-4 relative overflow-hidden min-w-0'
      }
    >
      <div className="flex items-start justify-between gap-2 min-w-0">
        <div className="min-w-0 overflow-hidden">
          <div className="text-sm text-gray-500 truncate" title={label}>
            {label}
          </div>
          <div
            className={
              highlight
                ? 'text-xl sm:text-2xl font-semibold mt-1 text-[var(--ams-primary)] truncate'
                : 'text-xl sm:text-2xl font-semibold mt-1 text-gray-900 truncate'
            }
            title={String(value)}
          >
            {value}
          </div>
        </div>
        {icon && (
          <span
            className={
              highlight
                ? 'w-9 h-9 sm:w-10 sm:h-10 rounded-full bg-[var(--ams-primary)]/10 text-[var(--ams-primary)] flex items-center justify-center text-base sm:text-lg shrink-0'
                : 'w-9 h-9 sm:w-10 sm:h-10 rounded-full bg-gray-100 text-gray-400 flex items-center justify-center text-base sm:text-lg shrink-0'
            }
          >
            {icon}
          </span>
        )}
      </div>
    </div>
  );
}

export function ConsolidatePage() {
  const [data, setData] = useState<Consolidate & { byCompany?: CompanyRow[] }>({});
  const [drill, setDrill] = useState<{ level?: string; items?: Record<string, unknown>[] } | null>(null);
  const [companyId, setCompanyId] = useState<number | null>(null);

  useEffect(() => {
    api
      .get<Consolidate & { byCompany?: CompanyRow[] }>('/dashboard/consolidate')
      .then(setData)
      .catch(() => {});
  }, []);

  const handleDrillCompany = async (id: number) => {
    setCompanyId(id);
    try {
      const d = await api.get<{ level: string; items: Record<string, unknown>[] }>(
        `/dashboard/consolidate/drill?companyId=${id}`,
      );
      setDrill(d);
    } catch {
      setDrill(null);
    }
  };

  const handleDrillProject = async (projectId: number | null) => {
    if (companyId == null) return;
    try {
      const q =
        projectId == null
          ? `/dashboard/consolidate/drill?companyId=${companyId}`
          : `/dashboard/consolidate/drill?companyId=${companyId}&projectId=${projectId}`;
      const d = await api.get<{ level: string; items: Record<string, unknown>[] }>(q);
      setDrill(d);
    } catch {
      setDrill(null);
    }
  };

  const cards = [
    { label: '集团资产总数', value: data.groupAssetTotal ?? '-' },
    {
      label: '集团出租率',
      value: data.groupLeasedRate != null ? `${(data.groupLeasedRate * 100).toFixed(1)}%` : '-',
    },
    { label: '集团空置面积 (㎡)', value: data.groupVacantArea ?? '-' },
  ];

  return (
    <div className="space-y-4">
      <h2 className="text-base font-semibold m-0 flex items-center gap-2">
        <span className="inline-flex items-center justify-center w-7 h-7 rounded-md bg-blue-50 text-[var(--ams-primary)]">
          <FundOutlined />
        </span>
        集团合并看板
      </h2>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {cards.map((c, i) => (
          <StatCard
            key={c.label}
            label={c.label}
            value={c.value}
            highlight={i === 0}
            icon={DASHBOARD_ICONS[c.label]}
          />
        ))}
      </div>

      <div className="bg-white rounded-lg border border-[var(--ams-border)] p-3 overflow-x-auto">
        <h3 className="text-sm font-medium mb-2 m-0">分子公司指标</h3>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-gray-500 border-b">
              <th className="py-2 pr-2">公司</th>
              <th className="py-2 pr-2">资产数</th>
              <th className="py-2 pr-2">出租率</th>
              <th className="py-2 pr-2">空置面积</th>
              <th className="py-2 pr-2">欠费</th>
              <th className="py-2 pr-2">收缴率</th>
              <th className="py-2">操作</th>
            </tr>
          </thead>
          <tbody>
            {(data.byCompany ?? []).map((row) => (
              <tr key={row.companyId} className="border-b border-gray-50">
                <td className="py-2 pr-2">{row.companyName ?? row.companyId}</td>
                <td className="py-2 pr-2">{row.assetTotal ?? '-'}</td>
                <td className="py-2 pr-2">
                  {row.leasedRate != null ? `${(Number(row.leasedRate) * 100).toFixed(1)}%` : '-'}
                </td>
                <td className="py-2 pr-2">{row.vacantArea ?? '-'}</td>
                <td className="py-2 pr-2">{row.arrears ?? '-'}</td>
                <td className="py-2 pr-2">
                  {row.collectionRate != null
                    ? `${(Number(row.collectionRate) * 100).toFixed(1)}%`
                    : '-'}
                </td>
                <td className="py-2">
                  <TableActions
                    actions={[
                      {
                        key: 'drill',
                        label: '下钻',
                        icon: <ArrowRightOutlined />,
                        onClick: () => handleDrillCompany(Number(row.companyId)),
                      },
                    ]}
                  />
                </td>
              </tr>
            ))}
            {(data.byCompany ?? []).length === 0 && (
              <tr>
                <td colSpan={7} className="py-6 text-center text-gray-400">
                  暂无分子公司数据
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {drill && (
        <div className="bg-white rounded-lg border border-[var(--ams-border)] p-3 overflow-x-auto">
          <h3 className="text-sm font-medium mb-2 m-0">
            下钻明细（{drill.level === 'company' ? '项目' : '资产'}）
          </h3>
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-gray-500 border-b">
                {drill.level === 'company' ? (
                  <>
                    <th className="py-2">项目</th>
                    <th className="py-2">资产数</th>
                    <th className="py-2">出租率</th>
                    <th className="py-2">操作</th>
                  </>
                ) : (
                  <>
                    <th className="py-2">资产编号</th>
                    <th className="py-2">名称</th>
                    <th className="py-2">面积</th>
                    <th className="py-2">租控状态</th>
                  </>
                )}
              </tr>
            </thead>
            <tbody>
              {(drill.items ?? []).map((row, idx) => (
                <tr key={idx} className="border-b border-gray-50">
                  {drill.level === 'company' ? (
                    <>
                      <td className="py-2">{String(row.projectName ?? '-')}</td>
                      <td className="py-2">{String(row.assetTotal ?? '-')}</td>
                      <td className="py-2">
                        {row.leasedRate != null
                          ? `${(Number(row.leasedRate) * 100).toFixed(1)}%`
                          : '-'}
                      </td>
                      <td className="py-2">
                        <TableActions
                          actions={[
                            {
                              key: 'project',
                              label: '查看资产',
                              icon: <ArrowRightOutlined />,
                              onClick: () =>
                                handleDrillProject(
                                  row.projectId == null ? null : Number(row.projectId),
                                ),
                            },
                          ]}
                        />
                      </td>
                    </>
                  ) : (
                    <>
                      <td className="py-2">{String(row.assetNo ?? '-')}</td>
                      <td className="py-2">{String(row.name ?? '-')}</td>
                      <td className="py-2">{String(row.area ?? '-')}</td>
                      <td className="py-2">{enumLabel(LEASE_CONTROL_STATUS, row.leaseControlStatus)}</td>
                    </>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
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
    { label: '资产总数', value: data.assetTotal ?? '-', highlight: true },
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
    <div className="space-y-4 min-w-0 max-w-full overflow-hidden">
      <h2 className="text-base font-semibold m-0 flex items-center gap-2 min-w-0">
        <span className="inline-flex items-center justify-center w-7 h-7 rounded-md bg-blue-50 text-[var(--ams-primary)] shrink-0">
          <DashboardOutlined />
        </span>
        <span className="truncate">经营看板</span>
      </h2>
      <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-5 gap-3 sm:gap-4">
        {cards.map((c) => (
          <StatCard
            key={c.label}
            label={c.label}
            value={c.value}
            highlight={c.highlight}
            icon={DASHBOARD_ICONS[c.label]}
          />
        ))}
      </div>
    </div>
  );
}
