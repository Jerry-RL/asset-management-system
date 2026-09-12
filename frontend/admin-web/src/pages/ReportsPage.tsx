import { useEffect, useMemo, useState } from 'react';
import { Button, Card, InputNumber, Select, Space, Table, Tabs, Tag, message } from 'antd';
import { DownloadOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { api, type PageResult } from '@/lib/api';
import * as L from '@/lib/labels';

type ReportType = 'asset-ledger' | 'lease-ledger' | 'collection' | 'maintenance';

interface ReportData {
  reportType: string;
  total: number;
  rows: Record<string, unknown>[];
  receivable?: number;
  received?: number;
  arrears?: number;
  collectionRate?: number;
}

interface CompanyOption {
  id: number;
  name: string;
}

interface ProjectOption {
  id: number;
  name: string;
  companyId?: number;
}

interface ReportFilters {
  companyId?: number;
  projectId?: number;
  assetType?: string;
  leaseControlStatus?: string;
  status?: string;
  billType?: string;
  contractId?: number;
}

const TABS: { key: ReportType; label: string }[] = [
  { key: 'asset-ledger', label: '资产台账' },
  { key: 'lease-ledger', label: '租赁台账' },
  { key: 'collection', label: '收费台账' },
  { key: 'maintenance', label: '维修台账' },
];

/** 各报表允许的查询参数，避免串台残留条件 */
const FILTER_KEYS: Record<ReportType, (keyof ReportFilters)[]> = {
  'asset-ledger': ['companyId', 'projectId', 'assetType', 'leaseControlStatus'],
  'lease-ledger': ['status'],
  collection: ['contractId', 'status', 'billType'],
  maintenance: ['status'],
};

/** 报表列值枚举映射 */
const VALUE_MAPS: Record<string, Record<string, string>> = {
  assetType: L.ASSET_TYPE,
  leaseControlStatus: L.LEASE_CONTROL_STATUS,
  status: {
    ...L.CONTRACT_STATUS,
    ...L.BILL_STATUS,
    ...L.REPAIR_STATUS,
  },
  billType: L.BILL_TYPE,
};

const toOptions = (map: Record<string, string>) =>
  Object.entries(map).map(([value, label]) => ({ value, label }));

const pickFilters = (type: ReportType, filters: ReportFilters): ReportFilters => {
  const allowed = new Set(FILTER_KEYS[type]);
  const next: ReportFilters = {};
  (Object.keys(filters) as (keyof ReportFilters)[]).forEach((key) => {
    if (!allowed.has(key)) return;
    const v = filters[key];
    if (v != null && v !== '') (next as Record<string, unknown>)[key] = v;
  });
  return next;
};

const buildQuery = (type: ReportType, filters: ReportFilters) => {
  const params = new URLSearchParams();
  Object.entries(pickFilters(type, filters)).forEach(([k, v]) => {
    if (v != null && v !== '') params.set(k, String(v));
  });
  const qs = params.toString();
  return qs ? `?${qs}` : '';
};

export function ReportsPage() {
  const [type, setType] = useState<ReportType>('asset-ledger');
  const [data, setData] = useState<ReportData | null>(null);
  const [loading, setLoading] = useState(false);
  const [filters, setFilters] = useState<ReportFilters>({});
  const [companies, setCompanies] = useState<CompanyOption[]>([]);
  const [projects, setProjects] = useState<ProjectOption[]>([]);

  const loadOptions = async () => {
    try {
      const [companyList, projectPage] = await Promise.all([
        api.get<CompanyOption[]>('/org/companies'),
        api.get<PageResult<ProjectOption> | ProjectOption[]>('/projects?page=1&pageSize=200'),
      ]);
      setCompanies(companyList ?? []);
      const projectList = Array.isArray(projectPage) ? projectPage : (projectPage?.list ?? []);
      setProjects(projectList);
    } catch {
      // 下拉选项失败不阻断报表主流程
    }
  };

  const load = async (t = type, flt = filters) => {
    setLoading(true);
    try {
      const result = await api.get<ReportData>(`/reports/${t}${buildQuery(t, flt)}`);
      setData(result);
    } catch (e) {
      setData(null);
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadOptions();
  }, []);

  // 报表类型或筛选条件变化时自动查询，避免「选了条件却未点查询」导致未生效
  useEffect(() => {
    void load(type, filters);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [type, filters]);

  const projectOptions = useMemo(() => {
    const list =
      filters.companyId != null
        ? projects.filter((p) => p.companyId == null || p.companyId === filters.companyId)
        : projects;
    return list.map((p) => ({ value: p.id, label: `${p.name} (#${p.id})` }));
  }, [projects, filters.companyId]);

  const columns =
    data?.rows?.[0] != null
      ? Object.keys(data.rows[0]).map((key) => ({
          title: L.fieldLabel(key),
          dataIndex: key,
          key,
          ellipsis: true,
          render: (v: unknown) => {
            const map = VALUE_MAPS[key];
            if (map) return L.enumLabel(map, v);
            return v == null || v === '' ? '-' : String(v);
          },
        }))
      : [];

  const handleExport = () => {
    const token = localStorage.getItem('ams.accessToken');
    fetch(`/api/v1/reports/${type}/export${buildQuery(type, filters)}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
      .then((r) => r.blob())
      .then((blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `${TABS.find((t) => t.key === type)?.label ?? type}.csv`;
        a.click();
        URL.revokeObjectURL(url);
      })
      .catch(() => message.error('导出失败'));
  };

  const handleReset = () => {
    setFilters({});
  };

  const handleTabChange = (key: string) => {
    setType(key as ReportType);
    setFilters({});
  };

  const patchFilter = <K extends keyof ReportFilters>(key: K, value: ReportFilters[K] | null) => {
    setFilters((prev) => {
      const next: ReportFilters = { ...prev };
      if (value == null || value === '') {
        delete next[key];
      } else {
        next[key] = value as ReportFilters[K];
      }
      if (key === 'companyId') {
        const companyId = typeof value === 'number' ? value : undefined;
        const stillValid =
          companyId == null ||
          projects.some(
            (p) => p.id === prev.projectId && (p.companyId == null || p.companyId === companyId),
          );
        if (!stillValid) delete next.projectId;
      }
      return next;
    });
  };

  return (
    <div className="p-4 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold m-0">数据报表</h1>
          <p className="text-gray-500 text-sm m-0 mt-1">资产 / 租赁 / 收费 / 维修固定台账</p>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => load()}>
            刷新
          </Button>
          <Button type="primary" icon={<DownloadOutlined />} onClick={handleExport}>
            导出 CSV
          </Button>
        </Space>
      </div>

      <Tabs
        activeKey={type}
        onChange={handleTabChange}
        items={TABS.map((t) => ({ key: t.key, label: t.label }))}
      />

      <Card size="small">
        <div className="flex flex-wrap items-end gap-3">
          {type === 'asset-ledger' && (
            <>
              <div className="min-w-[160px]">
                <div className="text-xs text-gray-500 mb-1">经营公司</div>
                <Select
                  allowClear
                  showSearch
                  optionFilterProp="label"
                  placeholder="全部公司"
                  className="w-full"
                  value={filters.companyId}
                  options={companies.map((c) => ({ value: c.id, label: c.name }))}
                  onChange={(v) => patchFilter('companyId', v ?? null)}
                />
              </div>
              <div className="min-w-[160px]">
                <div className="text-xs text-gray-500 mb-1">项目</div>
                <Select
                  allowClear
                  showSearch
                  optionFilterProp="label"
                  placeholder="全部项目"
                  className="w-full"
                  value={filters.projectId}
                  options={projectOptions}
                  onChange={(v) => patchFilter('projectId', v ?? null)}
                />
              </div>
              <div className="min-w-[140px]">
                <div className="text-xs text-gray-500 mb-1">资产类型</div>
                <Select
                  allowClear
                  placeholder="全部类型"
                  className="w-full"
                  value={filters.assetType}
                  options={toOptions(L.ASSET_TYPE)}
                  onChange={(v) => patchFilter('assetType', v ?? null)}
                />
              </div>
              <div className="min-w-[140px]">
                <div className="text-xs text-gray-500 mb-1">租控状态</div>
                <Select
                  allowClear
                  placeholder="全部状态"
                  className="w-full"
                  value={filters.leaseControlStatus}
                  options={toOptions(L.LEASE_CONTROL_STATUS)}
                  onChange={(v) => patchFilter('leaseControlStatus', v ?? null)}
                />
              </div>
            </>
          )}

          {type === 'lease-ledger' && (
            <div className="min-w-[160px]">
              <div className="text-xs text-gray-500 mb-1">合同状态</div>
              <Select
                allowClear
                placeholder="全部状态"
                className="w-full"
                value={filters.status}
                options={toOptions(L.CONTRACT_STATUS)}
                onChange={(v) => patchFilter('status', v ?? null)}
              />
            </div>
          )}

          {type === 'collection' && (
            <>
              <div className="min-w-[140px]">
                <div className="text-xs text-gray-500 mb-1">合同 ID</div>
                <InputNumber
                  className="w-full"
                  min={1}
                  placeholder="可选"
                  value={filters.contractId}
                  onChange={(v) => patchFilter('contractId', v ?? null)}
                />
              </div>
              <div className="min-w-[140px]">
                <div className="text-xs text-gray-500 mb-1">账单状态</div>
                <Select
                  allowClear
                  placeholder="全部状态"
                  className="w-full"
                  value={filters.status}
                  options={toOptions(L.BILL_STATUS)}
                  onChange={(v) => patchFilter('status', v ?? null)}
                />
              </div>
              <div className="min-w-[140px]">
                <div className="text-xs text-gray-500 mb-1">费用类型</div>
                <Select
                  allowClear
                  placeholder="全部类型"
                  className="w-full"
                  value={filters.billType}
                  options={toOptions(L.BILL_TYPE)}
                  onChange={(v) => patchFilter('billType', v ?? null)}
                />
              </div>
            </>
          )}

          {type === 'maintenance' && (
            <div className="min-w-[160px]">
              <div className="text-xs text-gray-500 mb-1">工单状态</div>
              <Select
                allowClear
                placeholder="全部状态"
                className="w-full"
                value={filters.status}
                options={toOptions(L.REPAIR_STATUS)}
                onChange={(v) => patchFilter('status', v ?? null)}
              />
            </div>
          )}

          <Space>
            <Button type="primary" icon={<SearchOutlined />} onClick={() => load()}>
              查询
            </Button>
            <Button onClick={handleReset}>重置</Button>
          </Space>
        </div>
      </Card>

      {type === 'collection' && data && (
        <div className="flex flex-wrap gap-3">
          <Tag color="blue">应收 {String(data.receivable ?? 0)}</Tag>
          <Tag color="green">实收 {String(data.received ?? 0)}</Tag>
          <Tag color="red">欠费 {String(data.arrears ?? 0)}</Tag>
          <Tag color="purple">收缴率 {String(data.collectionRate ?? 0)}</Tag>
        </div>
      )}

      <Card>
        <div className="text-sm text-gray-500 mb-2">共 {data?.total ?? 0} 条</div>
        <Table
          rowKey={(_, i) => String(i)}
          loading={loading}
          columns={columns}
          dataSource={data?.rows ?? []}
          pagination={{ pageSize: 20 }}
          scroll={{ x: true }}
          size="small"
        />
      </Card>
    </div>
  );
}
