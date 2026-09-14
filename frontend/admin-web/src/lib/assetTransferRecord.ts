import { api } from '@/lib/api';
import { normalizeList } from '@/lib/org';
import type { RecordAttachment } from '@/lib/recordSheet';

/**
 * 资产调拨记录（与后端 `com.ams.modules.transferrecord` 逐字段对应）。
 *
 * 把类型与请求都收在 lib 里、页面只消费：列表页与表单页都要用同一套字段名，
 * 各自抄一份会在后端改字段名时只改一半（前端不会因此变红，只在运行时显示空白）。
 *
 * 与 {@link ./ownershipTransfer} 的区别只在**改的是哪几个字段**：那个改产权 / 经营公司
 * （资产归谁持有），本模块改责任部门 / 责任人（公司内部谁负责）。两者刻意不共用类型 ——
 * 共用会让「多改一个字段」变成同时改动两个模块的表单。
 */

/** 与后端 `AssetTransferRecordAssetView` 对应 */
export interface AssetTransferRecordAssetView {
  assetId: number;
  assetNo?: string | null;
  assetName?: string | null;
  projectName?: string | null;
  zoneName?: string | null;
  floorNo?: number | null;
  /** 生效那一刻的原责任部门 / 原责任人（快照值，不是资产现值） */
  fromDepartmentId?: number | null;
  fromDepartmentName?: string | null;
  fromUserId?: number | null;
  fromUserName?: string | null;
}

/** 与后端 `AssetTransferRecordView` 对应 */
export interface AssetTransferRecordView {
  id: number;
  companyId: number;
  companyName?: string | null;
  fromDepartmentId?: number | null;
  fromDepartmentName?: string | null;
  toDepartmentId: number;
  toDepartmentName?: string | null;
  toUserId: number;
  toUserName?: string | null;
  approvalDeadline?: string | null;
  reason?: string | null;
  remark?: string | null;
  status: string;
  effectedAt?: string | null;
  createdAt?: string | null;
  assetCount: number;
  /** 详情才有值（列表页为 null） */
  assets?: AssetTransferRecordAssetView[] | null;
  /** 后端附件形状：读路径带 fileName / url，写路径只认 fileId（见 lib/recordSheet.ts） */
  attachments?: RecordAttachment[] | null;
}

/** 与后端 `TransferRecordAssetOption` 对应 */
export interface TransferRecordAssetOption {
  assetId: number;
  assetNo?: string | null;
  name?: string | null;
  projectName?: string | null;
  zoneName?: string | null;
  floorNo?: number | null;
}

/** 表单提交体（与后端 `AssetTransferRecordInput` 对应） */
export interface AssetTransferRecordInput {
  companyId: number;
  fromDepartmentId?: number | null;
  toDepartmentId: number;
  toUserId: number;
  approvalDeadline?: string | null;
  reason?: string | null;
  remark?: string | null;
  assetIds: number[];
  attachments: RecordAttachment[];
}

export interface AssetTransferRecordListParams {
  page: number;
  pageSize: number;
  status?: string;
  companyId?: number;
  keyword?: string;
}

export interface TransferRecordAssetOptionParams {
  companyId: number;
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

export const assetTransferRecordApi = {
  list: async (params: AssetTransferRecordListParams) => {
    const raw = await api.get<unknown>(`/asset-transfer-records?${toQuery(params)}`);
    return readPage<AssetTransferRecordView>(raw);
  },

  detail: (id: number) => api.get<AssetTransferRecordView>(`/asset-transfer-records/${id}`),

  assetOptions: async (params: TransferRecordAssetOptionParams) => {
    const raw = await api.get<unknown>(`/asset-transfer-records/asset-options?${toQuery(params)}`);
    return readPage<TransferRecordAssetOption>(raw);
  },

  create: (input: AssetTransferRecordInput) =>
    api.post<AssetTransferRecordView>('/asset-transfer-records', input),

  update: (id: number, input: AssetTransferRecordInput) =>
    api.put<AssetTransferRecordView>(`/asset-transfer-records/${id}`, input),

  /** 注意是 `api.del`：`api` 上没有 `delete` 方法（`delete` 是 JS 关键字，仓内统一用 `del`）。 */
  remove: (id: number) => api.del<void>(`/asset-transfer-records/${id}`),

  /** 生效：不可逆，调用前必须二次确认。 */
  effect: (id: number) =>
    api.post<AssetTransferRecordView>(`/asset-transfer-records/${id}/effect`),
};

/** 部门名的兜底（部门被删或未加载时不该显示 `undefined`）。 */
export const departmentLabel = (name?: string | null, id?: number | null): string =>
  name ?? (id === null || id === undefined ? '-' : `#${id}`);

/** 人员名的兜底（同上）。 */
export const userLabel = (name?: string | null, id?: number | null): string =>
  name ?? (id === null || id === undefined ? '-' : `#${id}`);
