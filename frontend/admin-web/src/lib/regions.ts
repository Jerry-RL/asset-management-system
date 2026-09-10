import type { RegionOption } from '@/data/chinaRegions';

export type { RegionOption };

/**
 * 中国省市区级联数据（真实行政区划，来自 src/data/chinaRegions.ts）。
 * 数据体积较大，使用方按需动态 import，避免打进首屏主包。
 */
export const loadRegions = async (): Promise<RegionOption[]> => {
  const mod = await import('@/data/chinaRegions');
  return mod.CHINA_REGIONS;
};

/**
 * 名称 → 级联路径：把 project 上的 province/city/district 反解为 Cascader 的 value。
 * 任一环节在数据集中找不到时，只回填能匹配上的前缀，避免级联控件显示非法值。
 */
export const regionPathOf = (
  regions: RegionOption[],
  province?: string | null,
  city?: string | null,
  district?: string | null,
): string[] | undefined => {
  if (!province) return undefined;
  const provinceNode = regions.find((p) => p.value === province);
  if (!provinceNode) return undefined;
  const path = [provinceNode.value];
  if (!city) return path;
  const cityNode = provinceNode.children?.find((c) => c.value === city);
  if (!cityNode) return path;
  path.push(cityNode.value);
  if (district && cityNode.children?.some((d) => d.value === district)) {
    path.push(district);
  }
  return path;
};

/** 级联路径 → 落库字段：缺级时补空，保证三个字段口径稳定 */
export const regionFieldsOf = (
  path?: (string | number)[] | null,
): { province?: string; city?: string; district?: string } => {
  const [province, city, district] = (path ?? []).map((v) => String(v));
  return {
    province: province || undefined,
    city: city || undefined,
    district: district || undefined,
  };
};

/** 直辖市/省直辖等场景下，第二级并非真实城市名，用于地址解析时需回退到省级 */
const NON_CITY_LEVEL_NAMES = new Set([
  '市辖区',
  '县',
  '省直辖县级行政区划',
  '自治区直辖县级行政区划',
]);

/**
 * 级联路径 → 地址解析用的城市限定词。
 * 高德地理编码的 city 参数用「城市名称」最稳妥；直辖市落到「市辖区」时回退为省级名称。
 */
export const regionCityHintOf = (path?: (string | number)[] | null): string | undefined => {
  const values = (path ?? []).map((v) => String(v));
  if (values.length === 0) return undefined;
  const province = values[0];
  const city = values[1];
  if (!city || NON_CITY_LEVEL_NAMES.has(city)) return province;
  return city;
};
