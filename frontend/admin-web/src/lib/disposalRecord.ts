import { api } from '@/lib/api';
import { normalizeList } from '@/lib/org';

/**
 * 资产处置记录（与后端 `com.ams.modules.disposal` 的 V57 部分逐字段对应）。
 *
 * <p>这一页是**只读台账**：一行 = 一个被处置的资产。写入发生在别处 ——
 * 资产级走「资产处置」的完成动作，项目 / 分区级走后续记录里的处置段，两者由后端级联。
 * 因此这里刻意不提供 create / update / delete 请求。
 */

/** 与后端 `AssetDisposalRecordView` 对应 */
export interface AssetDisposalRecordView {
  id: number;
  assetId: number;
  assetNo?: string | null;
  assetName?: string | null;
  projectName?: string | null;
  zoneName?: string | null;
  floorNo?: number | null;
  /** 处置对象层级：asset / project / zone */
  targetType: string;
  targetId: number;
  /** 资产级来源：disposal_order.id */
  sourceOrderId?: number | null;
  /** 项目 / 分区级来源：biz_disposal_record.id */
  sourceRecordId?: number | null;
  /** 处置时资产的原产权公司（资产上已被清空，这里是快照） */
  fromPropertyCompanyId?: number | null;
  fromPropertyCompanyName?: string | null;
  fromOperatingCompanyId?: number | null;
  fromOperatingCompanyName?: string | null;
  disposalType?: string | null;
  disposalAmount?: number | null;
  /** yuan 元 / wan 万元 —— 与 disposalAmount 成对读，后端不做换算 */
  amountUnit?: string | null;
  disposalDate?: string | null;
  disposalUserId?: number | null;
  disposalUserName?: string | null;
  remark?: string | null;
  disposedAt?: string | null;
}

export interface AssetDisposalRecordListParams {
  page: number;
  pageSize: number;
  /** 原产权公司（台账快照列） */
  fromCompanyId?: number;
  targetType?: string;
  disposalType?: string;
  keyword?: string;
}

/**
 * 兼容「直接数组」与「PageResult」两种响应形态。
 *
 * 与 `ResourcePage` / `lib/assetTransferRecord.ts` 同口径的兜底：后端某天改成返回数组
 * （或中间网关包装了一层）时，页面会显示「0 条」而不是抛错。
 */
const readPage = <T>(raw: unknown): { list: T[]; total: number } => {
  const list = normalizeList<T>(raw);
  const total =
    raw && typeof raw === 'object' && typeof (raw as { total?: number }).total === 'number'
      ? (raw as { total: number }).total
      : list.length;
  return { list, total };
};

const toQuery = (params: object): string => {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  return search.toString();
};

export const assetDisposalRecordApi = {
  list: async (params: AssetDisposalRecordListParams) => {
    const raw = await api.get<unknown>(`/disposal-records?${toQuery(params)}`);
    return readPage<AssetDisposalRecordView>(raw);
  },

  detail: (id: number) => api.get<AssetDisposalRecordView>(`/disposal-records/${id}`),
};

/** 处置对象层级的中文名（与后端 `AssetDisposalRecordService` 的常量逐字对应）。 */
export const DISPOSAL_TARGET_TYPE: Record<string, string> = {
  asset: '资产',
  project: '项目',
  zone: '项目分区',
};

/** 金额单位的中文名。 */
export const DISPOSAL_AMOUNT_UNIT: Record<string, string> = {
  yuan: '元',
  wan: '万元',
};

/**
 * 金额 + 单位。两者**必须一起展示**：资产级是元、项目/分区级是万元，后端不做换算，
 * 只显示数字会让同一列里 120.5 到底是「120.5 元」还是「120.5 万元」无从判断。
 */
export const disposalAmountLabel = (
  amount?: number | null,
  unit?: string | null,
): string => {
  if (amount === null || amount === undefined) return '-';
  const suffix = unit ? DISPOSAL_AMOUNT_UNIT[unit] ?? unit : '';
  return suffix ? `${amount} ${suffix}` : String(amount);
};

/** 处置对象的中文名：资产行直接展示层级；层级 + `#id` 用于详情回显来源。 */
export const disposalTargetLabel = (targetType?: string | null, targetId?: number | null): string => {
  const name = targetType ? DISPOSAL_TARGET_TYPE[targetType] ?? targetType : null;
  if (!name) return targetId == null ? '-' : `#${targetId}`;
  return targetId == null ? name : `${name} #${targetId}`;
};
