import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';

/**
 * 计租单元（`asset_unit`）行数据——ADR-0019 决策 A1 的「原子计租单位」。
 *
 * <p>`unitStatus` 是后端由占用集合派生的**物化列**（`LeaseStatusDeriver` 写入），
 * 前端只读展示，不得作为"能否拆分/合并"的最终判据：真正的门禁在后端，
 * 依据是占用表实时查询（见 ADR-0021 缺陷 D-04）。
 */
export interface AssetUnit {
  id: number;
  assetId: number;
  /** 资产内唯一：`<资产编号>-U<序号>`，拆分会追加层级（`-2`、`-2-1`） */
  unitNo: string;
  unitName?: string | null;
  /** 单元面积；0 表示"面积待补"的占位单元，不可拆分 */
  area?: number | null;
  /** 可租面积；为空表示等同 area */
  rentableArea?: number | null;
  /** 单元底价；为空则回退 asset.base_rent_floor */
  baseRent?: number | null;
  unitStatus?: string | null;
  floorNo?: number | null;
  sort?: number | null;
  remark?: string | null;
}

/**
 * 计租单元面板的"归属资产"信息。
 *
 * <p>只带展示与预算提示所需的最少字段——**面积预算的权威校验在后端**
 * （INV-1：Σ 单元面积 ≤ 资产可租面积），这里的合计只用于让人在下单前看到差距。
 */
export interface AssetUnitOwner {
  id: number;
  assetNo?: string | null;
  name?: string | null;
  /** 资产租赁面积；为空时面板回退用 `area` 展示预算 */
  leaseArea?: number | null;
  area?: number | null;
}

export const listAssetUnits = (assetId: number): Promise<AssetUnit[]> =>
  api.get<AssetUnit[]>(`/assets/${assetId}/units`);

/**
 * 拆分单元：把一个单元替换为多个更细粒度的单元（原单元软删）。
 *
 * <p>`childAreas` 的长度即拆分个数，合计必须等于原单元面积——前端已做校验，
 * 但后端会再校验一次（且容差更严格），前端校验只用于提前反馈、不作为正确性依赖。
 */
export const splitAssetUnit = (
  unitId: number,
  childAreas: number[],
  remark?: string,
): Promise<AssetUnit[]> =>
  api.post<AssetUnit[]>(`/assets/units/${unitId}/split`, { childAreas, remark });

/** 合并单元：把同一资产下多个空置单元合并为一个（拆分的逆操作，用于撤销误拆）。 */
export const mergeAssetUnits = (unitIds: number[], remark?: string): Promise<AssetUnit> =>
  api.post<AssetUnit>('/assets/units/merge', { unitIds, remark });

/**
 * 列表行 → 计租单元面板的「归属资产」。
 *
 * <p>三处调用方（资产台账的展开行与弹窗、项目分区管理的资产行）共用这一份转换 ——
 * 各写一份必然漂移，而漂移的表现是「某个入口的面板预算显示成 0」这种很难归因的现象。
 *
 * <p>只取展示与预算提示所需的最少字段，**不做任何面积计算**：面积预算
 * （INV-1：Σ 单元面积 ≤ 资产可租面积）是后端职责，前端只负责回显与「差多少」的提示。
 *
 * <p>`leaseArea` 与 `area` 都缺失时留 `null` 而不是 0：0 会被面板当成
 * 「预算为 0」，从而静默隐藏超预算提示；null 才是「不知道预算」的正确表达。
 */
export const toAssetUnitOwner = <T extends object>(
  row: T,
  idField = 'id',
): AssetUnitOwner => {
  // 入参用泛型而不是 `Record<string, unknown>`：`interface AssetRow` 没有索引签名，
  // 直接传它会要求调用方写 `as unknown as Record<...>`（TS 的隐式索引签名规则），
  // 那种双重转型会把真正的类型错误一起吞掉。泛型在函数内收窄一次即可。
  const source = row as Record<string, unknown>;
  const toNumber = (value: unknown): number | null => {
    if (value == null || value === '') return null;
    const num = Number(value);
    return Number.isFinite(num) ? num : null;
  };
  return {
    id: Number(source[idField]),
    assetNo: source.assetNo == null ? null : String(source.assetNo),
    name: source.name == null ? null : String(source.name),
    leaseArea: toNumber(source.leaseArea),
    area: toNumber(source.area),
  };
};

export interface UseAssetUnitsResult {
  units: AssetUnit[];
  loading: boolean;
  /** 加载失败：由调用方在面板内渲染错误态 + 重试，不弹全局 message */
  loadFailed: boolean;
  /** 重新拉取；失败置 loadFailed，不抛异常 */
  reload: () => Promise<void>;
}

/**
 * 某资产下计租单元的读写（列表 + 重拉）。
 *
 * <p>`assetId` 为空时不发请求、直接返回空列表——面板未选资产或调用方无权查看时，
 * 都不该打出一条注定失败/403 的请求（与 `useProjectZones` 同一约定）。
 *
 * <p>加载失败刻意不弹全局 message：本面板只是页面的一部分（弹窗内），
 * 全局弹错会掩盖"只有这一块失败"的事实。
 */
export function useAssetUnits(assetId: number | null | undefined): UseAssetUnitsResult {
  const [units, setUnits] = useState<AssetUnit[]>([]);
  const [loading, setLoading] = useState(assetId != null);
  const [loadFailed, setLoadFailed] = useState(false);

  const reload = useCallback(async () => {
    if (assetId == null) {
      setUnits([]);
      setLoading(false);
      setLoadFailed(false);
      return;
    }
    setLoading(true);
    setLoadFailed(false);
    try {
      const list = await listAssetUnits(assetId);
      setUnits(Array.isArray(list) ? list : []);
    } catch {
      setUnits([]);
      setLoadFailed(true);
    } finally {
      setLoading(false);
    }
  }, [assetId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  return { units, loading, loadFailed, reload };
}
