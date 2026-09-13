import { api } from '@/lib/api';
import { normalizeList, type CompanyOption } from '@/lib/org';
import type { RecordAttachment } from '@/lib/recordSheet';

/**
 * 权属流转（与后端 `com.ams.modules.ownership` 逐字段对应）。
 *
 * 把类型与请求都收在 lib 里、页面只消费：列表页与表单页都要用同一套字段名，
 * 各自抄一份会在后端改字段名时只改一半（前端不会因此变红，只在运行时显示空白）。
 */

/** 与后端 `OwnershipTransferAssetView` 对应 */
export interface OwnershipTransferAssetView {
  assetId: number;
  assetNo?: string | null;
  assetName?: string | null;
  projectName?: string | null;
  zoneName?: string | null;
  floorNo?: number | null;
  fromPropertyCompanyId?: number | null;
  fromOperatingCompanyId?: number | null;
}

/** 与后端 `OwnershipTransferView` 对应 */
export interface OwnershipTransferView {
  id: number;
  direction: string;
  transferScope: string;
  fromCompanyId: number;
  fromCompanyName?: string | null;
  toCompanyId: number;
  toCompanyName?: string | null;
  transferMode: string;
  applicantUserId?: number | null;
  applicantName: string;
  approvalDeadline?: string | null;
  amountWan?: number | null;
  reason?: string | null;
  status: string;
  effectedAt?: string | null;
  createdAt?: string | null;
  assetCount: number;
  /** 详情才有值（列表页为 null） */
  assets?: OwnershipTransferAssetView[] | null;
  /** 后端附件形状：读路径带 fileName / url，写路径只认 fileId（见 lib/recordSheet.ts） */
  attachments?: RecordAttachment[] | null;
}

/** 与后端 `TransferAssetOption` 对应 */
export interface TransferAssetOption {
  assetId: number;
  assetNo?: string | null;
  name?: string | null;
  projectName?: string | null;
  zoneName?: string | null;
  floorNo?: number | null;
}

/** 表单提交体（与后端 `OwnershipTransferInput` 对应） */
export interface OwnershipTransferInput {
  direction: string;
  transferScope: string;
  fromCompanyId: number;
  toCompanyId: number;
  transferMode: string;
  applicantUserId?: number | null;
  applicantName: string;
  approvalDeadline?: string | null;
  amountWan?: number | null;
  reason?: string | null;
  assetIds: number[];
  attachments: RecordAttachment[];
}

export interface OwnershipTransferListParams {
  page: number;
  pageSize: number;
  status?: string;
  direction?: string;
  fromCompanyId?: number;
  keyword?: string;
}

export interface AssetOptionParams {
  companyId: number;
  transferScope?: string;
  keyword?: string;
  page?: number;
  pageSize?: number;
}

/**
 * 兼容「直接数组」与「PageResult」两种响应形态。
 *
 * 与 `ResourcePage` / `lib/org.ts` 同口径的兜底：后端某天改成返回数组（或中间网关包装了一层）时，
 * 页面会显示「0 条」而不是抛错 —— 后者会让整页白屏，前者至少能自查。
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

export const ownershipTransferApi = {
  list: async (params: OwnershipTransferListParams) => {
    const raw = await api.get<unknown>(`/ownership-transfers?${toQuery(params)}`);
    return readPage<OwnershipTransferView>(raw);
  },

  detail: (id: number) => api.get<OwnershipTransferView>(`/ownership-transfers/${id}`),

  assetOptions: async (params: AssetOptionParams) => {
    const raw = await api.get<unknown>(`/ownership-transfers/asset-options?${toQuery(params)}`);
    return readPage<TransferAssetOption>(raw);
  },

  create: (input: OwnershipTransferInput) =>
    api.post<OwnershipTransferView>('/ownership-transfers', input),

  update: (id: number, input: OwnershipTransferInput) =>
    api.put<OwnershipTransferView>(`/ownership-transfers/${id}`, input),

  /** 注意是 `api.del`：`api` 上没有 `delete` 方法（`delete` 是 JS 关键字，仓内统一用 `del`）。 */
  remove: (id: number) => api.del<void>(`/ownership-transfers/${id}`),

  /** 生效：不可逆，调用前必须二次确认。 */
  effect: (id: number) => api.post<OwnershipTransferView>(`/ownership-transfers/${id}/effect`),
};

/**
 * 资产显示的公共入参：**下拉项与详情资产行的公共子集**。
 *
 * 为什么要有这个接口：下拉项（`TransferAssetOption`）的名字字段是 `name`，
 * 而详情资产行（`OwnershipTransferAssetView`）是 `assetName` —— 两者其余字段一致。
 * 若把 `assetOptionLabel` 写成只吃其中一种，另一处就必须自己再拼一份 label，
 * 而「两处拼接必然漂移」（同一次流转在两个页面显示成不同的资产名）。
 */
export interface AssetLabelSource {
  assetId: number;
  assetNo?: string | null;
  /** 下拉项的字段名 */
  name?: string | null;
  /** 详情资产行的字段名 */
  assetName?: string | null;
  projectName?: string | null;
  zoneName?: string | null;
  floorNo?: number | null;
}

/**
 * 资产下拉的显示文案：`项目 · 分区 · 楼层 · 资产名称`（需求指定的四段）。
 *
 * 空段跳过而不是留空占位：`- · - · 5层 · 厂房A` 比 `厂房A` 更难读。
 * 名称缺失时回落到资产编号 —— 至少让用户能选中一个可辨认的东西。
 */
export const assetOptionLabel = (option: AssetLabelSource): string => {
  const title = option.name ?? option.assetName ?? null;
  const floor =
    option.floorNo === null || option.floorNo === undefined ? null : `${option.floorNo}层`;
  const segments = [option.projectName, option.zoneName, floor, title].filter(
    (segment): segment is string => Boolean(segment && String(segment).trim()),
  );
  return segments.length > 0 ? segments.join(' · ') : (option.assetNo ?? `资产 #${option.assetId}`);
};

/** 公司名的兜底（公司被删或未加载时不该显示 `undefined`）。 */
export const companyLabel = (name?: string | null, id?: number | null): string =>
  name ?? (id === null || id === undefined ? '-' : `#${id}`);

/**
 * 按公司树推内部 / 外部（与后端 `TransferDirectionResolver` 同一规则：上溯到根，同根为内部）。
 *
 * **只用于给表单预填与提示**，不是判定权：后端会再判一次并不一致时 400。
 * 前端算一遍的价值在于「选完两个公司就知道该选哪个方向」，而不是等提交才报错。
 */
export const suggestDirection = (
  companies: CompanyOption[],
  fromCompanyId?: number | null,
  toCompanyId?: number | null,
): 'internal' | 'external' | undefined => {
  if (!fromCompanyId || !toCompanyId) return undefined;
  const parentById = new Map(companies.map((company) => [company.id, company.parentId ?? null]));
  const rootOf = (id: number): number => {
    let cursor: number = id;
    // 环保护：脏数据（A.parent = B、B.parent = A）不该让页面挂死
    const seen = new Set<number>();
    while (!seen.has(cursor)) {
      seen.add(cursor);
      const parent = parentById.get(cursor);
      if (parent === null || parent === undefined) return cursor;
      cursor = parent;
    }
    return cursor;
  };
  return rootOf(fromCompanyId) === rootOf(toCompanyId) ? 'internal' : 'external';
};
