import { useCallback, useEffect, useMemo, useState } from 'react';
import { Breadcrumb, Card, Empty, QRCode, Segmented, Space, Spin, Tag, Tooltip, message } from 'antd';
import {
  ArrowLeftOutlined,
  EnvironmentOutlined,
  BankOutlined,
  ReloadOutlined,
  AppstoreOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import { Link, useParams } from 'react-router-dom';
import dayjs from 'dayjs';
import { api } from '@/lib/api';
import { useBackNavigate } from '@/lib/navigation';
import { useDictLabelMaps } from '@/lib/dict';
import { CoverImage } from '@/components/CoverImage';
import {
  ChartLegend,
  DonutChart,
  MiniBarChart,
  RateBar,
  chartColor,
  type BarChartDatum,
} from '@/components/charts';
import {
  ASSET_TYPE,
  LEASE_CONTROL_STATUS,
  PROJECT_STATUS,
  PROJECT_TYPE,
  PROJECT_TYPE_DICT_CODE,
} from '@/lib/labels';

interface Slice {
  value: string | null;
  count: number;
  area: number;
}

interface MonthlyAmount {
  month: string;
  amount: number;
}

interface ZoneSummary {
  id: number | null;
  name: string;
  code?: string | null;
  sort?: number;
  assetCount: number;
  assetArea: number;
  idleCount: number;
  inUseCount: number;
  floorCount: number;
  statusCounts: Record<string, number>;
}

interface Overview {
  project: {
    id: number;
    name: string;
    type?: string;
    status?: number;
    address?: string;
    province?: string;
    city?: string;
    district?: string;
    imageUrl?: string;
    companyId?: number;
    companyName?: string;
    createdAt?: string;
    zoneCount: number;
    floorCount: number;
  };
  metrics: {
    utilizationRate: number;
    totalArea: number;
    assetCount: number;
    idleCount: number;
    inUseCount: number;
    leaseStatusBreakdown: Slice[];
    assetTypeBreakdown: Slice[];
    accumulatedReceived: number;
    yearReceived: number;
    monthlyReceived: MonthlyAmount[];
    leaseRate: number;
    rentedCount: number;
    unrentedCount: number;
    lastMonthCollectRate: number;
    lastMonthReceivable: number;
    lastMonthReceived: number;
    lastMonthLabel: string;
  };
  zones: ZoneSummary[];
}

/** 资产列表行（底部按「分区 → 楼层」分组展示） */
interface AssetRow {
  id: number;
  assetNo: string;
  name: string;
  zoneId?: number;
  floorNo?: number;
  area?: number;
  leaseControlStatus?: string;
  assetType?: string;
  usageType?: string;
  ownershipType?: string;
  buildingPlan?: string;
}

/** 「不详」共用文案：字段缺失时统一展示，避免出现空白单元格 */
const NA = '—';

/** 金额(元) → 万元，保留两位小数 */
const toWan = (yuan: number) =>
  (Number(yuan ?? 0) / 10000).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

/** 数值千分位（面积等） */
const num = (value: unknown, digits = 2) =>
  Number(value ?? 0).toLocaleString('zh-CN', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });

const percent = (value: unknown, digits = 1) => `${Number(value ?? 0).toFixed(digits)}%`;

/** 单个指标卡（参考图里「资产利用率 28.64%」这类只读数值） */
const MetricTile = ({ label, value }: { label: string; value: string }) => (
  <div className="flex-1 min-w-[120px]">
    <div className="text-xs text-gray-500 mb-1">{label}</div>
    <div className="text-lg font-semibold text-gray-800 tabular-nums truncate">{value}</div>
  </div>
);

/** 带图标的一行元信息 */
const MetaLine = ({ icon, children }: { icon: React.ReactNode; children: React.ReactNode }) => (
  <div className="flex items-center gap-2 text-sm text-gray-600">
    <span className="text-gray-400">{icon}</span>
    <span className="truncate">{children}</span>
  </div>
);

const ProjectDetailPage = () => {
  const { id } = useParams<{ id: string }>();
  const goBack = useBackNavigate('/projects');
  const [loading, setLoading] = useState(true);
  const [reloadKey, setReloadKey] = useState(0);
  const [overview, setOverview] = useState<Overview | null>(null);
  const [assets, setAssets] = useState<AssetRow[]>([]);
  const [zoneTab, setZoneTab] = useState<string>('all');
  const [statusFilter, setStatusFilter] = useState<string>('');
  const [assetView, setAssetView] = useState<'floor' | 'list'>('floor');

  // 字典优先、静态 map 兜底：字典未加载完时不至于展示英文枚举值
  const dictMaps = useDictLabelMaps([PROJECT_TYPE_DICT_CODE, 'asset_type']);
  const projectTypeLabel = (value?: string) =>
    dictMaps[PROJECT_TYPE_DICT_CODE]?.[value ?? ''] ?? PROJECT_TYPE[value ?? ''] ?? value ?? NA;
  const assetTypeLabel = (value?: string) =>
    dictMaps.asset_type?.[value ?? ''] ?? ASSET_TYPE[value ?? ''] ?? value ?? NA;
  const leaseStatusLabel = (value?: string | null) =>
    LEASE_CONTROL_STATUS[value ?? ''] ?? value ?? NA;

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const data = await api.get<Overview>(`/projects/${id}/overview`);
      setOverview(data);
      // 资产明细单独取：底部要按「分区 → 楼层」分组，且需覆盖项目全部资产（不受分页限制）
      const page = await api.get<{ list: AssetRow[] }>(
        `/assets?projectId=${id}&page=1&pageSize=500`,
      );
      setAssets(page.list ?? []);
    } catch {
      message.error('加载项目详情失败');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load, reloadKey]);

  const metrics = overview?.metrics;
  const project = overview?.project;

  /** 二维码内容：本页完整地址，扫码即可在手机上打开同一项目详情 */
  const shareUrl = typeof window === 'undefined' ? '' : window.location.href;

  const address = useMemo(() => {
    if (!project) return NA;
    const full = [project.province, project.city, project.district, project.address]
      .filter(Boolean)
      .join('');
    return full || project.address || NA;
  }, [project]);

  /** 租控状态分布（环形图 + 图例），颜色按排序固定，保证各分区一致 */
  const statusSlices = useMemo<Slice[]>(
    () => metrics?.leaseStatusBreakdown ?? [],
    [metrics],
  );

  /** 资产类型分布 */
  const typeSlices = useMemo<Slice[]>(() => metrics?.assetTypeBreakdown ?? [], [metrics]);

  /**
   * 经营状况：按「已出租宗数」占项目宗数的比例推导，
   * 与参考图的「部分出租」语义一致，且完全由出租数据派生，不需要额外字段。
   */
  const operationStatus = useMemo(() => {
    if (!metrics || metrics.assetCount === 0) return '暂无资产';
    if (metrics.rentedCount === 0) return '未出租';
    if (metrics.rentedCount >= metrics.assetCount) return '整体出租';
    return '部分出租';
  }, [metrics]);

  /** 当前选中分区（all 表示全部） */
  const activeZone = useMemo(
    () => (zoneTab === 'all' ? null : overview?.zones.find((z) => String(z.id) === zoneTab) ?? null),
    [overview, zoneTab],
  );

  /** 底部 KPI：随分区切换联动，未选分区时取项目合计 */
  const scopeKpi = useMemo(() => {
    if (!metrics) return { assetCount: 0, assetArea: 0, inUseCount: 0, idleCount: 0 };
    if (!activeZone) {
      return {
        assetCount: metrics.assetCount,
        assetArea: metrics.totalArea,
        inUseCount: metrics.inUseCount,
        idleCount: metrics.idleCount,
      };
    }
    return {
      assetCount: activeZone.assetCount,
      assetArea: activeZone.assetArea,
      inUseCount: activeZone.inUseCount,
      idleCount: activeZone.idleCount,
    };
  }, [metrics, activeZone]);

  /** 图例 chips 的计数：分区选中时用分区口径，否则用项目口径 */
  const legendCounts = useMemo(() => {
    if (activeZone) return activeZone.statusCounts;
    const counts: Record<string, number> = {};
    statusSlices.forEach((s) => {
      if (s.value) counts[s.value] = s.count;
    });
    return counts;
  }, [activeZone, statusSlices]);

  /** 图例/图表的固定顺序与配色，与环形图一致 */
  const statusOrder = useMemo(
    () => statusSlices.map((s) => s.value).filter((v): v is string => !!v),
    [statusSlices],
  );

  /** 底部资产列表：按分区 + 租控状态筛选后再分组 */
  const visibleAssets = useMemo(() => {
    const zoneId = activeZone?.id ?? null;
    return assets.filter((a) => {
      if (activeZone && a.zoneId !== zoneId) return false;
      if (statusFilter && a.leaseControlStatus !== statusFilter) return false;
      return true;
    });
  }, [assets, activeZone, statusFilter]);

  /** 楼层分组：有 floorNo 的按楼层归组，没有的落到「未标注楼层」 */
  const floorGroups = useMemo(() => {
    const groups = new Map<string, AssetRow[]>();
    visibleAssets.forEach((asset) => {
      const key = asset.floorNo == null ? NA : `${asset.floorNo}F`;
      const list = groups.get(key) ?? [];
      list.push(asset);
      groups.set(key, list);
    });
    return [...groups.entries()].sort(([a], [b]) => {
      if (a === NA) return 1;
      if (b === NA) return -1;
      return parseInt(a, 10) - parseInt(b, 10);
    });
  }, [visibleAssets]);

  const monthlyBars = useMemo<BarChartDatum[]>(
    () =>
      (metrics?.monthlyReceived ?? []).map((m) => ({
        // 只展示月份，避免 12 个刻度挤成一条线
        label: m.month.slice(5),
        value: Number(m.amount ?? 0),
      })),
    [metrics],
  );

  if (loading && !overview) {
    return (
      <div className="flex items-center justify-center py-24">
        <Spin size="large" />
      </div>
    );
  }

  if (!overview || !metrics || !project) {
    return <Empty description="项目不存在或无权访问" className="py-24" />;
  }

  const statusLegendItems = statusOrder.map((value, index) => ({
    label: leaseStatusLabel(value),
    value: `${legendCounts[value] ?? 0} 宗`,
    color: chartColor(index),
  }));

  const typeLegendItems = typeSlices.map((slice, index) => ({
    label: assetTypeLabel(slice.value ?? undefined),
    value: `${slice.count} 宗`,
    color: chartColor(index),
  }));

  return (
    <div className="space-y-4">
      {/* 面包屑 + 操作 */}
      <div className="flex items-center justify-between">
        <Space size="middle">
          <span
            className="text-gray-500 cursor-pointer hover:text-blue-600"
            onClick={goBack}
            role="button"
            tabIndex={0}
            onKeyDown={(e) => e.key === 'Enter' && goBack()}
          >
            <ArrowLeftOutlined /> 返回
          </span>
          <Breadcrumb
            items={[
              { title: '资产运营' },
              { title: <span className="cursor-pointer" onClick={goBack}>资产项目</span> },
              { title: '项目详情' },
            ]}
          />
        </Space>
        <Tooltip title="刷新">
          <ReloadOutlined
            className="text-gray-500 cursor-pointer hover:text-blue-600"
            onClick={() => setReloadKey((k) => k + 1)}
          />
        </Tooltip>
      </div>

      {/* 资产信息：封面 + 名称/所属公司/地址 + 关键状态 + 创建时间 */}
      <Card bodyStyle={{ padding: 20 }}>
        <div className="flex gap-5 flex-wrap lg:flex-nowrap">
          <CoverImage
            src={project.imageUrl}
            alt={project.name}
            className="w-full lg:w-[300px] h-[180px] rounded-lg shrink-0"
          />
          <div className="flex-1 min-w-[240px] flex flex-col justify-between gap-3">
            <div className="space-y-2">
              <h2 className="text-xl font-semibold text-gray-800 m-0">{project.name}</h2>
              <MetaLine icon={<BankOutlined />}>{project.companyName ?? NA}</MetaLine>
              <MetaLine icon={<EnvironmentOutlined />}>{address}</MetaLine>
            </div>
            <div className="flex flex-wrap gap-4">
              <MetricTile
                label="资产状态"
                value={PROJECT_STATUS[String(project.status ?? '')] ?? NA}
              />
              <MetricTile label="资产类型" value={projectTypeLabel(project.type)} />
              <MetricTile label="经营状况" value={operationStatus} />
            </div>
          </div>
          <div className="flex flex-col items-end justify-between gap-2 shrink-0">
            <div className="text-xs text-gray-400 text-right">
              创建时间：
              {project.createdAt ? dayjs(project.createdAt).format('YYYY.MM.DD HH:mm:ss') : NA}
            </div>
            {/* 项目二维码：扫码直达本详情页，便于现场巡查时在手机上打开 */}
            <div className="flex flex-col items-center gap-1">
              <QRCode value={shareUrl} size={92} bordered={false} />
              <span className="text-[10px] text-gray-400">扫码查看项目</span>
            </div>
            <div className="text-xs text-gray-400 text-right space-y-1">
              <div>
                分区 <span className="text-gray-700 font-medium tabular-nums">{project.zoneCount}</span> 个
              </div>
              <div>
                楼层 <span className="text-gray-700 font-medium tabular-nums">{project.floorCount}</span> 层
              </div>
            </div>
          </div>
        </div>
      </Card>

      {/* 三张指标卡：资产基本信息 / 资产创收 / 租赁概况 */}
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-4">
        {/* 资产基本信息 */}
        <Card title="资产基本信息" size="small">
          <div className="flex gap-4 mb-4">
            <MetricTile label="资产利用率" value={percent(metrics.utilizationRate)} />
            <MetricTile label="固定资产(㎡)" value={num(metrics.totalArea)} />
          </div>
          <div className="flex items-center gap-3 mb-3">
            <DonutChart
              slices={statusSlices.map((s, i) => ({
                label: leaseStatusLabel(s.value),
                value: s.count,
                color: chartColor(i),
              }))}
              centerValue={String(metrics.assetCount)}
              centerLabel="资产宗数"
            />
            <ChartLegend items={statusLegendItems} />
          </div>
          <div className="flex items-center gap-3">
            <DonutChart
              slices={typeSlices.map((s, i) => ({
                label: assetTypeLabel(s.value ?? undefined),
                value: s.count,
                color: chartColor(i),
              }))}
              size={110}
              thickness={14}
            />
            <ChartLegend items={typeLegendItems} />
          </div>
        </Card>

        {/* 资产创收 */}
        <Card title="资产创收" size="small">
          <div className="flex gap-4 mb-4">
            <MetricTile label="累计实收(万元)" value={toWan(metrics.accumulatedReceived)} />
            <MetricTile label="本年实收(万元)" value={toWan(metrics.yearReceived)} />
          </div>
          <div className="text-xs text-gray-500 mb-2">近一年每月实收(万元)</div>
          <MiniBarChart
            data={monthlyBars}
            formatValue={(v) => `${toWan(v)} 万元`}
          />
        </Card>

        {/* 租赁概况 */}
        <Card title="租赁概况" size="small">
          <div className="flex gap-4 mb-4">
            <MetricTile label="资产出租率" value={percent(metrics.leaseRate, 2)} />
            <MetricTile label="上月收缴率" value={percent(metrics.lastMonthCollectRate, 2)} />
          </div>
          <div className="space-y-4">
            <div>
              <div className="text-xs text-gray-500 mb-1">资产出租率</div>
              <RateBar percent={metrics.leaseRate} />
            </div>
            <div className="flex items-center justify-between text-xs text-gray-500">
              <span>实际出租 / 未出租(宗)</span>
              <span className="tabular-nums text-gray-800">
                {metrics.rentedCount} / {metrics.unrentedCount}
              </span>
            </div>
            <div>
              <div className="text-xs text-gray-500 mb-1">
                上月收缴率（{metrics.lastMonthLabel}）
              </div>
              <RateBar percent={metrics.lastMonthCollectRate} color="#52c41a" />
            </div>
            <div className="flex items-center justify-between text-xs text-gray-500">
              <span>上月应收 / 上月实收(万元)</span>
              <span className="tabular-nums text-gray-800">
                {toWan(metrics.lastMonthReceivable)} / {toWan(metrics.lastMonthReceived)}
              </span>
            </div>
          </div>
        </Card>
      </div>

      {/* 分区 / 楼层 + 资产明细 */}
      <Card
        size="small"
        title={
          <Space size="small">
            <AppstoreOutlined />
            <span>分区与楼层</span>
          </Space>
        }
        extra={
          <Segmented
            size="small"
            value={assetView}
            onChange={(v) => setAssetView(v as 'floor' | 'list')}
            options={[
              { value: 'floor', icon: <AppstoreOutlined />, label: '按楼层' },
              { value: 'list', icon: <UnorderedListOutlined />, label: '列表' },
            ]}
          />
        }
      >
        <div className="flex gap-4 flex-col lg:flex-row">
          {/* 分区导航 */}
          <div className="lg:w-[180px] shrink-0 space-y-1">
            <div className="text-xs text-gray-400 mb-2">项目分区</div>
            {[{ id: null, name: '全部分区', assetCount: metrics.assetCount }, ...overview.zones].map(
              (zone) => {
                const key = zone.id == null ? 'all' : String(zone.id);
                const active = zoneTab === key;
                return (
                  <div
                    key={key}
                    role="button"
                    tabIndex={0}
                    onClick={() => setZoneTab(key)}
                    onKeyDown={(e) => e.key === 'Enter' && setZoneTab(key)}
                    className={`px-3 py-2 rounded cursor-pointer text-sm flex items-center justify-between transition-colors ${
                      active
                        ? 'bg-blue-50 text-blue-600 font-medium'
                        : 'text-gray-600 hover:bg-gray-50'
                    }`}
                  >
                    <span className="truncate">{zone.name}</span>
                    <span className="text-xs text-gray-400 tabular-nums shrink-0 ml-2">
                      {zone.assetCount}
                    </span>
                  </div>
                );
              },
            )}
          </div>

          {/* KPI + 图例 + 资产分组 */}
          <div className="flex-1 min-w-0">
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
              {[
                { label: '资产总数(宗)', value: String(scopeKpi.assetCount), color: '#1677ff' },
                { label: '资产面积(㎡)', value: num(scopeKpi.assetArea), color: '#13c2c2' },
                { label: '在用资产(宗)', value: String(scopeKpi.inUseCount), color: '#52c41a' },
                { label: '闲置资产(宗)', value: String(scopeKpi.idleCount), color: '#faad14' },
              ].map((kpi) => (
                <div
                  key={kpi.label}
                  className="rounded-lg border border-gray-100 bg-gray-50/60 px-3 py-2"
                >
                  <div className="text-xs text-gray-500 mb-1 flex items-center gap-1">
                    <span
                      className="w-1.5 h-1.5 rounded-full"
                      style={{ backgroundColor: kpi.color }}
                    />
                    {kpi.label}
                  </div>
                  <div className="text-base font-semibold text-gray-800 tabular-nums truncate">
                    {kpi.value}
                  </div>
                </div>
              ))}
            </div>

            {/* 租控状态图例 chips：点击即按状态筛选下方资产 */}
            <div className="flex flex-wrap gap-2 mb-4">
              <Tag
                color={statusFilter === '' ? 'blue' : undefined}
                className="cursor-pointer"
                onClick={() => setStatusFilter('')}
              >
                不限
              </Tag>
              {statusOrder.map((value, index) => {
                const active = statusFilter === value;
                const color = chartColor(index);
                return (
                  <Tag
                    key={value}
                    className="cursor-pointer"
                    style={
                      active
                        ? { backgroundColor: color, borderColor: color, color: '#fff' }
                        : { borderColor: color, color }
                    }
                    onClick={() => setStatusFilter(active ? '' : value)}
                  >
                    {leaseStatusLabel(value)}（{legendCounts[value] ?? 0}）
                  </Tag>
                );
              })}
            </div>

            {visibleAssets.length === 0 ? (
              <Empty description="该筛选条件下暂无资产" className="py-10" />
            ) : assetView === 'list' ? (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-left text-xs text-gray-500 border-b border-gray-100">
                      <th className="py-2 pr-4 font-normal">资产编号</th>
                      <th className="py-2 pr-4 font-normal">资产名称</th>
                      <th className="py-2 pr-4 font-normal">分区</th>
                      <th className="py-2 pr-4 font-normal">楼层</th>
                      <th className="py-2 pr-4 font-normal">面积(㎡)</th>
                      <th className="py-2 font-normal">租控状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {visibleAssets.map((asset) => (
                      <tr key={asset.id} className="border-b border-gray-50 last:border-0">
                        <td className="py-2 pr-4 text-gray-500 tabular-nums">{asset.assetNo}</td>
                        <td className="py-2 pr-4 text-gray-800">{asset.name}</td>
                        <td className="py-2 pr-4 text-gray-500">
                          {overview.zones.find((z) => z.id === asset.zoneId)?.name ?? NA}
                        </td>
                        <td className="py-2 pr-4 text-gray-500">
                          {asset.floorNo == null ? NA : `${asset.floorNo}F`}
                        </td>
                        <td className="py-2 pr-4 text-gray-500 tabular-nums">
                          {num(asset.area, 0)}
                        </td>
                        <td className="py-2">
                          <Tag>{leaseStatusLabel(asset.leaseControlStatus)}</Tag>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <div className="space-y-4">
                {floorGroups.map(([floor, list]) => (
                  <div key={floor}>
                    <div className="flex items-center gap-2 mb-2">
                      <span className="text-sm font-medium text-gray-800">{floor}</span>
                      <span className="text-xs text-gray-400 tabular-nums">
                        {num(
                          list.reduce((sum, a) => sum + Number(a.area ?? 0), 0),
                          0,
                        )}{' '}
                        ㎡ · {list.length} 宗
                      </span>
                    </div>
                    <div className="space-y-2">
                      {list.map((asset) => (
                        <div
                          key={asset.id}
                          className="flex items-center gap-3 rounded border border-gray-100 px-3 py-2 hover:border-blue-200 transition-colors"
                        >
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2">
                              <span className="text-sm text-gray-800 truncate">{asset.name}</span>
                              <Tag
                                className="m-0"
                                color={
                                  asset.leaseControlStatus === 'vacant' ||
                                  asset.leaseControlStatus === 'leasing'
                                    ? 'orange'
                                    : 'green'
                                }
                              >
                                {leaseStatusLabel(asset.leaseControlStatus)}
                              </Tag>
                            </div>
                            <div className="text-xs text-gray-400 mt-0.5 truncate">
                              资产编号 {asset.assetNo}
                              {asset.buildingPlan ? ` · ${asset.buildingPlan}` : ''}
                              {asset.ownershipType ? ` · ${asset.ownershipType}` : ''}
                              {asset.area ? ` · ${num(asset.area, 0)} ㎡` : ''}
                            </div>
                          </div>
                          <Link
                            to={`/assets/${asset.id}/dossier`}
                            className="text-xs text-blue-600 hover:text-blue-700 shrink-0"
                          >
                            一物一档
                          </Link>
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </Card>
    </div>
  );
};

export default ProjectDetailPage;
