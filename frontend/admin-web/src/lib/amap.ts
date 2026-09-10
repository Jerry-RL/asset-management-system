import { api } from '@/lib/api';

/**
 * 高德地图共享能力：脚本加载（单例，资产地图渲染用）+ 地址地理编码（走后端接口）。
 */

/** 高德 JS API 中本系统用到的成员（第三方库，仅收敛用到的部分） */
export interface AMapMap {
  destroy: () => void;
  add: (overlay: unknown) => void;
  setFitView: (overlay: unknown) => void;
}

export interface AMapMarker {
  on: (event: string, handler: () => void) => void;
}

export interface AMapNamespace {
  Map: new (container: HTMLElement, options?: Record<string, unknown>) => AMapMap;
  Marker: new (options?: Record<string, unknown>) => AMapMarker;
  Pixel: new (x: number, y: number) => unknown;
}

declare global {
  interface Window {
    AMap?: AMapNamespace;
    _AMapSecurityConfig?: { securityJsCode?: string };
  }
}

export interface MapConfig {
  provider: 'amap' | 'canvas';
  amapKey?: string;
  amapSecurityCode?: string;
}

/** 读取地图配置（高德 Key 未配置时 provider 为 canvas，前端回退平面图） */
export const getMapConfig = () => api.get<MapConfig>('/map/config');

/** 脚本加载单例：并发调用共享同一次加载，避免重复插入 script */
let amapLoading: Promise<void> | null = null;

/** 加载高德 JS API（仅地图渲染需要） */
export const loadAmap = (key: string, securityCode?: string): Promise<void> => {
  if (typeof window === 'undefined') return Promise.reject(new Error('当前环境不支持高德地图'));
  if (window.AMap) return Promise.resolve();
  if (amapLoading) return amapLoading;

  amapLoading = new Promise<void>((resolve, reject) => {
    if (securityCode) {
      window._AMapSecurityConfig = { securityJsCode: securityCode };
    }
    const script = document.createElement('script');
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`;
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => {
      amapLoading = null; // 允许失败后重试
      reject(new Error('高德地图脚本加载失败'));
    };
    document.head.appendChild(script);
  });
  return amapLoading;
};

export interface GeocodeResult {
  longitude: number;
  latitude: number;
  /** 高德返回的标准化地址，便于人工核对 */
  formattedAddress?: string;
  province?: string;
  city?: string;
  district?: string;
  /** 匹配精度，如「门牌号」「道路」「区县」 */
  level?: string;
}

/**
 * 地址 → 经纬度。
 * 统一走后端地理编码接口（GET /map/geocode），Key 与签名逻辑收敛在服务端，
 * PC / H5 / 小程序可复用同一能力。
 * @param city 城市限定词（城市名称最稳妥），提升匹配精度
 */
export const geocodeAddress = (opts: { address: string; city?: string }) => {
  const params = new URLSearchParams({ address: opts.address });
  if (opts.city) params.set('city', opts.city);
  return api.get<GeocodeResult>(`/map/geocode?${params.toString()}`);
};
