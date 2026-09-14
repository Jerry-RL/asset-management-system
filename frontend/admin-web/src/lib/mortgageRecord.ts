import { api } from '@/lib/api';
import { normalizeList } from '@/lib/org';
import { assetOptionLabel } from '@/lib/assetLabel';
import type { RecordAttachment } from '@/lib/recordSheet';

/**
 * 抵押记录（与后端 `com.ams.modules.mortgage` + `com.ams.modules.asset.entity.Mortgage` 逐字段对应）。
 *
 * 与权属流转 / 资产调拨记录的**结构差异**是标的：那两个模块一张单挂多个资产，
 * 本模块一张单只有**一个标的**，且标的可能是项目 / 分区 / 资产三选一。
 * 因此这里没有 `assetIds` 数组，只有 `targetType` + `targetId`。
 *
 * `targetType` 用字面量联合类型而不是 `string`：三种取值决定的展示拼接与候选查询
 * 完全不同的分支，写成 `string` 会让「少写一个 case」变成静默的空白单元格。
 */

export type MortgageTargetType = 'project' | 'zone' | 'asset';

/** 与后端 `MortgageRecordView` 对应 */
export interface MortgageRecordView {
  id: number;
  companyId?: number | null;
  companyName?: string | null;
  targetType: MortgageTargetType | string;
  targetId: number;
  /** 标的自身的名字：项目名 / 分区名 / 资产名 */
  targetName?: string | null;
  /** 分区 / 资产标的的上级项目名 */
  projectName?: string | null;
  /** 资产标的的所属分区名 */
  zoneName?: string | null;
  floorNo?: number | null;
  assetNo?: string | null;
  mortgagee?: string | null;
  /** 后端是 BigDecimal：JSON 里可能是数字也可能是字符串，展示前统一走 formatAmount */
  amount?: number | string | null;
  interestRate?: number | string | null;
  bank?: string | null;
  repaymentDate?: string | null;
  startDate?: string | null;
  /** 由 起始时间 + 期限（月）推导，只读 */
  endDate?: string | null;
  termMonths?: number | null;
  contractNo?: string | null;
  status: string;
  releaseStatus?: string | null;
  releaseRemark?: string | null;
  releasedAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  /** 详情才有值（列表页为 null）：列表页每行查一次附件就是 N+1 */
  attachments?: RecordAttachment[] | null;
}

/** 与后端 `MortgageTargetOption` 对应 */
export interface MortgageTargetOption {
  targetId: number;
  name?: string | null;
  projectName?: string | null;
  zoneName?: string | null;
  floorNo?: number | null;
  assetNo?: string | null;
}

/** 表单提交体（与后端 `MortgageRecordInput` 对应） */
export interface MortgageRecordInput {
  companyId: number;
  targetType: MortgageTargetType;
  targetId: number;
  mortgagee: string;
  /** 已按后端 `NUMERIC(18,2)` 收敛到 2 位小数 */
  amount: number;
  /** 已按后端 `NUMERIC(8,4)` 收敛到 4 位小数；不填则 null */
  interestRate?: number | null;
  bank?: string | null;
  repaymentDate?: string | null;
  startDate: string;
  termMonths: number;
  contractNo?: string | null;
  attachments: RecordAttachment[];
}

export interface MortgageRecordListParams {
  page: number;
  pageSize: number;
  status?: string;
  targetType?: string;
  companyId?: number;
  keyword?: string;
}

export interface MortgageTargetOptionParams {
  targetType: MortgageTargetType;
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

export const mortgageRecordApi = {
  list: async (params: MortgageRecordListParams) => {
    const raw = await api.get<unknown>(`/mortgages?${toQuery(params)}`);
    return readPage<MortgageRecordView>(raw);
  },

  detail: (id: number) => api.get<MortgageRecordView>(`/mortgages/${id}`),

  /** 标的选题项：按「项目类型 + 所属公司」联动过滤 */
  targetOptions: async (params: MortgageTargetOptionParams) => {
    const raw = await api.get<unknown>(`/mortgages/target-options?${toQuery(params)}`);
    return readPage<MortgageTargetOption>(raw);
  },

  create: (input: MortgageRecordInput) => api.post<MortgageRecordView>('/mortgages', input),

  update: (id: number, input: MortgageRecordInput) =>
    api.put<MortgageRecordView>(`/mortgages/${id}`, input),

  /** 注意是 `api.del`：`api` 上没有 `delete` 方法（`delete` 是 JS 关键字，仓内统一用 `del`）。 */
  remove: (id: number) => api.del<void>(`/mortgages/${id}`),

  /**
   * 生效：草稿 → 在押。
   *
   * 生效后这条记录开始拦截处置 / 流转 / 调拨（`assertNotMortgaged`），
   * 且不可撤回 —— 要解除只能走解押审批，调用前必须二次确认。
   */
  effect: (id: number) => api.post<MortgageRecordView>(`/mortgages/${id}/effect`),
};

/** 标的类型的展示名（与后端 `Mortgage.TARGET_*` 三取值一一对应）。 */
export const mortgageTargetTypeLabel = (targetType?: string | null): string =>
  MORTGAGE_TARGET_TYPE_LABELS[String(targetType)] ?? '-';

const MORTGAGE_TARGET_TYPE_LABELS: Record<string, string> = {
  project: '项目',
  zone: '分区',
  asset: '资产',
};

/** 抵押标的的展示文案：三级各拼各的段，前端是唯一拼接点（后端只回原料）。 */
export const mortgageTargetLabel = (
  targetType: string | null | undefined,
  target: {
    targetId?: number | null;
    name?: string | null;
    projectName?: string | null;
    zoneName?: string | null;
    floorNo?: number | null;
    assetNo?: string | null;
  },
): string => {
  if (targetType === 'project') {
    return target.name ?? fallbackTargetLabel(target.targetId);
  }
  if (targetType === 'zone') {
    const segments = [target.projectName, target.name].filter(
      (segment): segment is string => Boolean(segment && String(segment).trim()),
    );
    return segments.length > 0 ? segments.join(' · ') : fallbackTargetLabel(target.targetId);
  }
  // 资产级直接复用资产文案（`项目 · 分区 · 楼层 · 名称`），不再抄一份 ——
  // 抄一份的话「同一个资产在抵押页与调拨页显示成不同名字」是迟早的事
  return assetOptionLabel({
    assetId: target.targetId ?? 0,
    assetNo: target.assetNo,
    name: target.name,
    projectName: target.projectName,
    zoneName: target.zoneName,
    floorNo: target.floorNo,
  });
};

/** 名称缺失时的兜底：至少让用户看到一个可辨认的引用，而不是空白。 */
const fallbackTargetLabel = (targetId?: number | null): string =>
  targetId === null || targetId === undefined ? '-' : `#${targetId}`;

/**
 * 表单提交前的数值收敛。
 *
 * 后端的 `BigDecimal.scale()` 校验是**按小数位数**判定的，而 JS 的浮点运算会产生
 * `0.30000000000000004` 这种 scale 17 的值 —— 直接 `Number` 传过去会被判成
 * 「最多 2 位小数」而拒掉，用户看到的是一句莫名其妙的报错。
 * 统一走 `toFixed` 收敛，让「界面能输入的值」与「后端能接受的值」是同一个集合。
 */
export const toAmount = (value: number): number => Number(value.toFixed(2));
export const toInterestRate = (value: number | null | undefined): number | null =>
  value === null || value === undefined ? null : Number(value.toFixed(4));
