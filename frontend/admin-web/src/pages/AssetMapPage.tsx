import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Card, Empty, Select, Space, Tag, message } from 'antd';
import { api } from '@/lib/api';
import { loadAmap, type AMapMap, type MapConfig } from '@/lib/amap';
import { LEASE_CONTROL_STATUS, enumLabel } from '@/lib/labels';
import { currentPath } from '@/lib/navigation';

interface MapPoint {
  assetId: number;
  assetNo: string;
  name: string;
  leaseControlStatus: string;
  area?: number;
  address?: string;
  longitude: number;
  latitude: number;
  vacantDays?: number;
  city?: string;
  province?: string;
}

/** Ant Design Tag 色名（详情区） */
const STATUS_TAG_COLOR: Record<string, string> = {
  vacant: 'orange',
  leased: 'green',
  leasing: 'blue',
  self_use: 'purple',
  occupied: 'gold',
  vacating: 'red',
  partial_leased: 'cyan',
  disposing: 'magenta',
  exited: 'default',
};

/** 地图标记实色（平面图 + 高德自定义点） */
const STATUS_MARKER_HEX: Record<string, string> = {
  vacant: '#fa8c16',
  leased: '#52c41a',
  leasing: '#1677ff',
  self_use: '#722ed1',
  occupied: '#d48806',
  vacating: '#f5222d',
  partial_leased: '#13c2c2',
  disposing: '#eb2f96',
  exited: '#8c8c8c',
};

const DEFAULT_MARKER_HEX = '#8c8c8c';

const markerColor = (status?: string) =>
  (status && STATUS_MARKER_HEX[status]) || DEFAULT_MARKER_HEX;

const STATUS_LEGEND = Object.keys(LEASE_CONTROL_STATUS).map((key) => ({
  key,
  label: LEASE_CONTROL_STATUS[key],
  color: markerColor(key),
}));

const buildMarkerContent = (p: MapPoint, active: boolean) => {
  const color = markerColor(p.leaseControlStatus);
  const size = active ? 18 : 14;
  const ring = active ? 4 : 2;
  return `
    <div style="
      width:${size}px;height:${size}px;border-radius:50%;
      background:${color};border:${ring}px solid #fff;
      box-shadow:0 0 0 1px ${color}55, 0 2px 6px rgba(0,0,0,.28);
      transform:translate(-50%,-50%);cursor:pointer;
    " title="${p.name}（${enumLabel(LEASE_CONTROL_STATUS, p.leaseControlStatus)}）"></div>
  `;
};

export function AssetMapPage() {
  const location = useLocation();
  const [points, setPoints] = useState<MapPoint[]>([]);
  const [config, setConfig] = useState<MapConfig>({ provider: 'canvas' });
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [city, setCity] = useState<string>();
  const [statusFilter, setStatusFilter] = useState<string>();
  const mapRef = useRef<HTMLDivElement | null>(null);
  const amapRef = useRef<AMapMap | null>(null);

  useEffect(() => {
    Promise.all([
      api.get<MapPoint[]>('/map/points'),
      api.get<MapConfig>('/map/config').catch(() => ({ provider: 'canvas' as const })),
    ])
      .then(([pts, cfg]) => {
        setPoints(pts);
        setConfig(cfg || { provider: 'canvas' });
      })
      .catch((e) => message.error(e instanceof Error ? e.message : '加载地图点位失败'));
  }, []);

  const cities = useMemo(() => {
    const set = new Set(points.map((p) => p.city).filter(Boolean) as string[]);
    return Array.from(set);
  }, [points]);

  const filtered = useMemo(() => {
    let list = points;
    if (city) list = list.filter((p) => p.city === city);
    if (statusFilter) list = list.filter((p) => p.leaseControlStatus === statusFilter);
    return list;
  }, [points, city, statusFilter]);

  const selected = filtered.find((p) => p.assetId === selectedId) ?? filtered[0];

  useEffect(() => {
    if (config.provider !== 'amap' || !config.amapKey || !mapRef.current || filtered.length === 0) {
      return;
    }
    let cancelled = false;
    loadAmap(config.amapKey, config.amapSecurityCode)
      .then(() => {
        const AMap = window.AMap;
        if (cancelled || !mapRef.current || !AMap) return;
        if (amapRef.current) {
          amapRef.current.destroy();
          amapRef.current = null;
        }
        const map = new AMap.Map(mapRef.current, {
          zoom: 12,
          viewMode: '2D',
        });
        const markers = filtered.map((p) => {
          const marker = new AMap.Marker({
            position: [Number(p.longitude), Number(p.latitude)],
            title: `${p.name}（${enumLabel(LEASE_CONTROL_STATUS, p.leaseControlStatus)}）`,
            content: buildMarkerContent(p, false),
            offset: new AMap.Pixel(0, 0),
          });
          marker.on('click', () => setSelectedId(p.assetId));
          return marker;
        });
        map.add(markers);
        map.setFitView(markers);
        amapRef.current = map;
      })
      .catch((e) =>
        message.warning(e instanceof Error ? e.message : '高德地图不可用，已回退平面图'),
      );
    return () => {
      cancelled = true;
      if (amapRef.current) {
        amapRef.current.destroy();
        amapRef.current = null;
      }
    };
  }, [config, filtered]);

  const bounds = useMemo(() => {
    if (filtered.length === 0) return null;
    const lngs = filtered.map((p) => Number(p.longitude));
    const lats = filtered.map((p) => Number(p.latitude));
    return {
      minLng: Math.min(...lngs),
      maxLng: Math.max(...lngs),
      minLat: Math.min(...lats),
      maxLat: Math.max(...lats),
    };
  }, [filtered]);

  const toPos = (p: MapPoint) => {
    if (!bounds) return { left: '50%', top: '50%' };
    const spanLng = Math.max(bounds.maxLng - bounds.minLng, 0.01);
    const spanLat = Math.max(bounds.maxLat - bounds.minLat, 0.01);
    const x = ((Number(p.longitude) - bounds.minLng) / spanLng) * 86 + 7;
    const y = (1 - (Number(p.latitude) - bounds.minLat) / spanLat) * 80 + 10;
    return { left: `${x}%`, top: `${y}%` };
  };

  const useAmap = config.provider === 'amap' && !!config.amapKey;

  const legendItems = useMemo(() => {
    const present = new Set(points.map((p) => p.leaseControlStatus).filter(Boolean));
    const items = STATUS_LEGEND.filter((s) => present.has(s.key));
    return items.length > 0 ? items : STATUS_LEGEND;
  }, [points]);

  return (
    <div className="p-4 space-y-4">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div>
          <h1 className="text-xl font-semibold m-0">资产地图</h1>
          <p className="text-gray-500 text-sm m-0 mt-1">
            {useAmap ? '高德地图模式（配置 AMAP_KEY）' : '平面点位图（未配置高德 Key 时默认）'}
            ，点位颜色表示租控状态
          </p>
        </div>
        <Space wrap>
          <Select
            allowClear
            placeholder="按城市筛选"
            className="!w-40"
            options={cities.map((c) => ({ value: c, label: c }))}
            value={city}
            onChange={setCity}
          />
          <Select
            allowClear
            placeholder="按状态筛选"
            className="!w-40"
            options={STATUS_LEGEND.map((s) => ({
              value: s.key,
              label: (
                <span className="inline-flex items-center gap-2">
                  <span
                    className="inline-block w-2.5 h-2.5 rounded-full shrink-0"
                    style={{ background: s.color }}
                    aria-hidden
                  />
                  {s.label}
                </span>
              ),
            }))}
            value={statusFilter}
            onChange={setStatusFilter}
          />
          <Tag>{filtered.length} 个点位</Tag>
          <Tag color={useAmap ? 'blue' : 'default'}>{useAmap ? '高德地图' : '平面示意图'}</Tag>
        </Space>
      </div>

      {filtered.length === 0 ? (
        <Empty description="暂无坐标点位，请在资产或项目上维护经纬度" />
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          <Card className="lg:col-span-2 !overflow-hidden" styles={{ body: { padding: 0 } }}>
            {useAmap ? (
              <div className="relative">
                <div
                  ref={mapRef}
                  className="h-[520px] w-full"
                  role="img"
                  aria-label="高德资产地图"
                />
                <MapLegend items={legendItems} />
              </div>
            ) : (
              <div
                className="relative h-[520px] bg-[linear-gradient(180deg,#e8f4ff_0%,#f7fafc_55%,#eef2f7_100%)]"
                role="img"
                aria-label="资产地图平面"
              >
                <div className="absolute inset-6 border border-dashed border-sky-300/70 rounded-xl" />
                {filtered.map((p) => {
                  const pos = toPos(p);
                  const active = selected?.assetId === p.assetId;
                  const color = markerColor(p.leaseControlStatus);
                  return (
                    <button
                      key={p.assetId}
                      type="button"
                      className={`absolute -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white transition ${
                        active ? 'w-4 h-4 shadow-lg scale-125 z-10' : 'w-3 h-3 z-[1]'
                      }`}
                      style={{
                        left: pos.left,
                        top: pos.top,
                        background: color,
                        boxShadow: active
                          ? `0 0 0 3px ${color}55, 0 2px 8px rgba(0,0,0,.25)`
                          : `0 1px 4px rgba(0,0,0,.2)`,
                      }}
                      aria-label={`${p.name}，${enumLabel(LEASE_CONTROL_STATUS, p.leaseControlStatus)}`}
                      title={`${p.name}（${enumLabel(LEASE_CONTROL_STATUS, p.leaseControlStatus)}）`}
                      onClick={() => setSelectedId(p.assetId)}
                    />
                  );
                })}
                <MapLegend items={legendItems} />
              </div>
            )}
          </Card>
          <Card title="点位详情">
            {selected ? (
              <div className="space-y-2 text-sm">
                <div className="text-lg font-medium">{selected.name}</div>
                <div className="text-gray-500">{selected.assetNo}</div>
                <Tag color={STATUS_TAG_COLOR[selected.leaseControlStatus] || 'default'}>
                  <span className="inline-flex items-center gap-1.5">
                    <span
                      className="inline-block w-2 h-2 rounded-full"
                      style={{ background: markerColor(selected.leaseControlStatus) }}
                      aria-hidden
                    />
                    {enumLabel(LEASE_CONTROL_STATUS, selected.leaseControlStatus)}
                  </span>
                </Tag>
                <div>面积：{selected.area ?? '-'} ㎡</div>
                <div>地址：{selected.address || '-'}</div>
                <div>
                  坐标：{selected.longitude}, {selected.latitude}
                </div>
                {selected.vacantDays != null && <div>空置天数：{selected.vacantDays}</div>}
                <Space>
                  <Link
                    className="text-[#1677ff]"
                    to={`/assets/${selected.assetId}/dossier`}
                    state={{ from: currentPath(location) }}
                  >
                    查看资产档案
                  </Link>
                  <Link className="text-gray-500" to="/assets">
                    资产台账
                  </Link>
                </Space>
              </div>
            ) : (
              <Empty />
            )}
          </Card>
        </div>
      )}
    </div>
  );
}

const MapLegend = ({ items }: { items: { key: string; label: string; color: string }[] }) => (
  <div
    className="absolute left-3 bottom-3 z-20 rounded-lg bg-white/95 border border-slate-200 shadow-sm px-3 py-2 max-w-[min(100%,420px)]"
    aria-label="租控状态图例"
  >
    <div className="text-xs text-gray-500 mb-1.5 font-medium">租控状态</div>
    <div className="flex flex-wrap gap-x-3 gap-y-1.5">
      {items.map((item) => (
        <div key={item.key} className="inline-flex items-center gap-1.5 text-xs text-gray-700">
          <span
            className="inline-block w-2.5 h-2.5 rounded-full shrink-0 border border-white shadow-sm"
            style={{ background: item.color }}
            aria-hidden
          />
          {item.label}
        </div>
      ))}
    </div>
  </div>
);
