import { api } from '@/lib/api';
import type { AttachmentValue } from '@/components/AttachmentField';

// ============================================================================
// 后续记录（处置 / 接收 / 来源 / 成本 / 评估）的聚合读写。
// 契约见设计 docs/superpowers/specs/2026-09-12-record-forms-design.md §5.2 与
// 后端 RecordSheetController / RecordSheetService。
//
// 一张表单 = 一个聚合：GET 读一次、PUT 写一次（服务端做**全量 diff**）。因此本文件
// 不提供「增删单条记录」的接口 —— 单条增删一律通过提交整张 sheet 完成，
// 未出现在请求体里的记录与附件会被服务端软删。
//
// 附件在**读**路径上带 fileName / url，在**写**路径上只认 fileId 与顺序；
// 两个方向都以 fileId 为唯一身份，所以「回显 → 原样提交」是幂等的。
// ============================================================================

export type RecordOwnerType = 'asset' | 'project' | 'zone';

/** 后端附件形状：fileName / url 只在读路径回填 */
export interface RecordAttachment {
  fileId: number;
  sort?: number;
  fileName?: string;
  url?: string;
}

export interface RecordIssue {
  id?: number;
  issueType?: string;
  description?: string;
  discovererId?: number;
  /** 姓名快照：discovererId 为空时由前端手填，必填 */
  discovererName?: string;
  attachments: AttachmentValue[];
}

export interface ReceiveRecord {
  id?: number;
  handoverType?: string;
  docName?: string;
  handoverUserId?: number;
  /** 姓名快照：handoverUserId 为空时由前端手填，必填 */
  handoverUserName?: string;
  /** yyyy-MM-dd */
  handoverDate?: string;
  remark?: string;
  issues: RecordIssue[];
  attachments: AttachmentValue[];
}

export interface SourceInfo {
  id?: number;
  sourcePersonId?: number;
  /** 姓名快照；来源人**可为空**（来源信息常只知来源单位而不知具体人） */
  sourcePersonName?: string;
  sourceUnit?: string;
  /** yyyy-MM-dd */
  sourceDate?: string;
  sourceDesc?: string;
  attachments: AttachmentValue[];
}

/**
 * 处置记录的**统一卡片形状**（设计 §6.6）。
 *
 * <p>项目 / 分区来自 `biz_disposal_record`（台账，不接审批），资产来自 `disposal_order`
 * （带状态机、驱动 `lifecycle_status`）—— 两个数据源，但同一个组件要能吃：
 *
 * <ul>
 *   <li><strong>公共字段</strong>（顺序即卡片上的呈现顺序：类型 → 人 → 金额 → 日期 → 备注 → 附件）
 *       两侧同名同义，卡片只按 `ownerType` 决定「能不能编辑」；</li>
 *   <li>带「资产侧」注释的字段只在 `ownerType='asset'` 时渲染（台账没有这些业务概念）。</li>
 * </ul>
 *
 * <p>写入路径**不共用**：台账随 record-sheet 一次提交（全量 diff），资产的改动由
 * {@link syncAssetDisposals} 打到 `PUT /assets/{id}/disposals`。落库时机一致——
 * 都是「改动攒在本地、点宿主的保存按钮才提交」。
 */
export interface DisposalRecord {
  id?: number;

  // ---- 公共字段 ----
  disposalType?: string;
  disposalUserId?: number;
  /** 姓名快照：disposalUserId 为空时由前端手填，必填 */
  disposalUserName?: string;
  /**
   * 处置金额。**两侧单位不同且不做换算**：
   * 台账是 `biz_disposal_record.amount_wan`（万元），资产是 `disposal_order.actual_amount` 原值（元）。
   * 卡片上用 `addonAfter` 把单位显式画出来，避免同一个标签误导录入。
   */
  amountWan?: number;
  /** yyyy-MM-dd */
  disposalDate?: string;
  remark?: string;
  attachments: AttachmentValue[];

  // ---- 资产侧专有（台账侧恒为 undefined）----
  /** draft / approving / rejected / pending_execute / executing / completed。非草稿一律只读 */
  status?: string;
  reason?: string;
  assessedValue?: number;
  bookValue?: number;
  counterparty?: string;
}

/** 原始处置单回显（`GET /assets/{id}/disposals`）：字段名与 {@link DisposalRecord} 一致，只差附件形状 */
type RawDisposalOrder = Omit<DisposalRecord, 'attachments'> & { attachments?: RecordAttachment[] };

/** 费用明细（成本信息的子项）：费用名称 / 成本类型 / 金额 / 备注，无独立附件。 */
export interface CostItem {
  id?: number;
  feeName?: string;
  /** 取值见 sys_dict_type.code = cost_type。 */
  costType?: string;
  amount?: number;
  remark?: string;
}

/**
 * 成本信息（后续记录扩展，1:N）。三种主体（资产 / 项目 / 分区）共用。
 *
 * <p>全量提交语义：`items` 与 `attachments` 中未出现的行由服务端软删，
 * 因此编辑后必须提交完整列表（同接收信息的遗留问题）。
 */
export interface CostRecord {
  id?: number;
  /** 成本金额，单位**万元**。 */
  amountWan?: number;
  /** yyyy-MM-dd */
  costDate?: string;
  remark?: string;
  items: CostItem[];
  attachments: AttachmentValue[];
}

/**
 * 评估信息（后续记录扩展，1:N）。有效期限以起止两个日期表达（闭区间）。
 */
export interface EvaluationInfo {
  id?: number;
  /** 评估机构（自由文本，多为外部单位）。 */
  institution?: string;
  assetValue?: number;
  rentUnitPrice?: number;
  rentPrice?: number;
  /** yyyy-MM-dd */
  evaluateDate?: string;
  /** 评估有效期限起（含），yyyy-MM-dd */
  validFrom?: string;
  /** 评估有效期限止（含），yyyy-MM-dd */
  validTo?: string;
  attachments: AttachmentValue[];
}

export interface RecordSheet {
  receives: ReceiveRecord[];
  sourceInfo: SourceInfo | null;
  disposalRecords: DisposalRecord[];
  costRecords: CostRecord[];
  evaluations: EvaluationInfo[];
}

/** 写请求体：与读视图同形 —— 资产侧处置单列表走独立的 /assets/{id}/disposals，不在本类型里 */
export type RecordSheetPayload = RecordSheet;

/**
 * 记录路由。分区必须带上 projectId —— 后端把「分区不属于该项目」交给路由形态排斥，
 * 而不是靠请求体再传一次 projectId。
 */
export const recordSheetPath = (
  ownerType: RecordOwnerType,
  ownerId: number,
  projectId?: number,
): string => {
  if (ownerType === 'asset') return `/assets/${ownerId}/record-sheet`;
  if (ownerType === 'project') return `/projects/${ownerId}/record-sheet`;
  if (projectId == null) {
    throw new Error('分区记录路由必须提供 projectId');
  }
  return `/projects/${projectId}/zones/${ownerId}/record-sheet`;
};

/** 后端附件 → 组件值。name 只用于展示，缺失时退化成 `附件<id>`。 */
export const toAttachmentValues = (refs?: RecordAttachment[] | null): AttachmentValue[] =>
  (refs ?? []).map((ref) => ({
    fileId: ref.fileId,
    url: ref.url ?? '',
    name: ref.fileName ?? `附件${ref.fileId}`,
  }));

/** 组件值 → 请求体：只提交 fileId 与顺序，name/url 由服务端回填 */
export const fromAttachmentValues = (values?: AttachmentValue[] | null): RecordAttachment[] =>
  (values ?? []).map((item, index) => ({ fileId: item.fileId, sort: index }));

/** 服务端 JSON 里的附件一律是 RecordAttachment 形状，这里只做一次归一 */
type RawSheet = {
  receives?: (ReceiveRecord & { attachments?: RecordAttachment[] })[];
  sourceInfo?: (SourceInfo & { attachments?: RecordAttachment[] }) | null;
  disposalRecords?: (DisposalRecord & { attachments?: RecordAttachment[] })[];
  costRecords?: (CostRecord & { attachments?: RecordAttachment[] })[];
  evaluations?: (EvaluationInfo & { attachments?: RecordAttachment[] })[];
} | null;

const normalizeSheet = (raw: RawSheet | undefined): RecordSheet => ({
  receives: (raw?.receives ?? []).map((record) => ({
    ...record,
    issues: (record.issues ?? []).map((issue) => ({
      ...issue,
      attachments: toAttachmentValues(issue.attachments as unknown as RecordAttachment[]),
    })),
    attachments: toAttachmentValues(record.attachments as unknown as RecordAttachment[]),
  })),
  sourceInfo: raw?.sourceInfo
    ? {
        ...raw.sourceInfo,
        attachments: toAttachmentValues(
          raw.sourceInfo.attachments as unknown as RecordAttachment[],
        ),
      }
    : null,
  // record-sheet 的处置段只服务项目 / 分区（服务端对 asset 忽略该段）；
  // 资产的处置单由 loadOwnerSheet 从 /assets/{id}/disposals 合并进来
  disposalRecords: (raw?.disposalRecords ?? []).map((record) => ({
    ...record,
    attachments: toAttachmentValues(record.attachments as unknown as RecordAttachment[]),
  })),
  costRecords: (raw?.costRecords ?? []).map((record) => ({
    ...record,
    items: record.items ?? [],
    attachments: toAttachmentValues(record.attachments as unknown as RecordAttachment[]),
  })),
  evaluations: (raw?.evaluations ?? []).map((record) => ({
    ...record,
    attachments: toAttachmentValues(record.attachments as unknown as RecordAttachment[]),
  })),
});

export const loadRecordSheet = (path: string): Promise<RecordSheet> =>
  api.get<RawSheet>(path).then(normalizeSheet);

/**
 * 资产处置单列表（含附件回显）。
 *
 * <p>走独立的 `GET /assets/{id}/disposals`：资产的处置段不出现在 record-sheet 的读视图里
 * （服务端忽略 `disposalRecords` 段，见设计 §5.2）。返回 {@link DisposalRecord}
 * 以便与台账共用同一个卡片组件。
 *
 * <p>**必须经 `toAttachmentValues` 归一**：服务端回显的是 `fileName`/`url`，
 * 而组件值用的是 `name` —— 直接用会让附件名渲染成 `undefined`、下载链接也拼不出来。
 */
export const loadAssetDisposals = (assetId: number): Promise<DisposalRecord[]> =>
  api
    .get<RawDisposalOrder[]>(`/assets/${assetId}/disposals`)
    .then((rows) => (rows ?? []).map(normalizeDisposal));

const normalizeDisposal = (row: RawDisposalOrder): DisposalRecord => ({
  ...row,
  attachments: toAttachmentValues(row.attachments),
});

/**
 * 读某个主体的后续记录。**宿主一律经它取数**，不要直接调 {@link loadRecordSheet}：
 * 资产的处置单在另一张表上、另一个端点上，漏合并的表现是「面板永远是空的」而不是报错。
 */
export const loadOwnerSheet = async (
  ownerType: RecordOwnerType,
  ownerId: number,
  projectId?: number,
): Promise<RecordSheet> => {
  const sheet = await loadRecordSheet(recordSheetPath(ownerType, ownerId, projectId));
  if (ownerType !== 'asset') return sheet;
  return { ...sheet, disposalRecords: await loadAssetDisposals(ownerId) };
};

/** 处置卡片 → 资产侧同步请求体。**不提交 status**：状态由流程端点驱动，客户端无权直造 */
const toDisposalOrderInput = (record: DisposalRecord) => ({
  id: record.id,
  disposalType: record.disposalType,
  disposalUserId: record.disposalUserId,
  disposalUserName: record.disposalUserName,
  // 资产侧这一个键就是 disposal_order.actual_amount 原值（不做万元换算）
  actualAmount: record.amountWan,
  disposalDate: record.disposalDate,
  remark: record.remark,
  reason: record.reason,
  assessedValue: record.assessedValue,
  bookValue: record.bookValue,
  counterparty: record.counterparty,
  attachments: fromAttachmentValues(record.attachments),
});

/**
 * 资产处置单全量同步：整份列表一次提交，服务端按 id 做增量 diff
 * （有 id 改草稿、无 id 新增成草稿、未提交的草稿删掉；非草稿原样不动）。
 *
 * <p>返回**服务端规范结果**：新增的卡片在这里才拿到 id 与附件名，宿主必须回写本地值，
 * 否则下一次保存会把同一张新卡片再提交一遍。也正因为它会回填 id，重复保存是幂等的。
 *
 * <p>权限：服务端要求 `operation.disposal:create`，调用方（{@link saveOwnerSheet}）负责
 * 在无该权限时跳过 —— 无权限时卡片是只读的，列表不可能变。
 */
export const syncAssetDisposals = (
  assetId: number,
  records: DisposalRecord[],
): Promise<DisposalRecord[]> =>
  api
    .put<RawDisposalOrder[]>(`/assets/${assetId}/disposals`, {
      records: records.map(toDisposalOrderInput),
    })
    .then((rows) => (rows ?? []).map(normalizeDisposal));

export interface SaveOwnerSheetOptions {
  /** 分区必传：用于拼 record-sheet 路径 */
  projectId?: number;
  /**
   * 是否可以同步资产侧处置单（等价于持有 `operation.disposal:create`）。
   *
   * <p>没有该权限时面板上的处置卡片是只读的，列表不可能发生变化；此时**必须跳过**这次请求 ——
   * 否则「只改了接收信息」的保存也会撞上 403，宿主会报出「后续记录保存失败」，
   * 而实际上数据已经存好了，使用者会白重试一遍。
   */
  canSyncDisposals?: boolean;
}

/**
 * 保存某个主体的后续记录 —— 三处宿主（资产表单 / 项目表单 / 分区详情 / 列表页的就地编辑弹窗）
 * 统一经它落库，不要在宿主里各写一遍编排。
 *
 * <p>内部按主体分叉，但**对外语义一致**：一次调用 = 一次「保存后续记录」，
 * 返回服务端规范结果供宿主覆盖本地值（子记录 id、附件名、处置单状态都在这里回填）。
 */
export const saveOwnerSheet = async (
  ownerType: RecordOwnerType,
  ownerId: number,
  sheet: RecordSheetPayload,
  options: SaveOwnerSheetOptions = {},
): Promise<RecordSheet> => {
  if (ownerType !== 'asset') {
    return saveRecordSheet(recordSheetPath(ownerType, ownerId, options.projectId), sheet);
  }
  // 资产的处置段在服务端被忽略（design §5.2 / 验收第 4 条），显式清空再提交：
  // 让请求体如实反映「这一段不经 record-sheet」，避免出现「看起来提交了、其实没写」的错觉
  const saved = await saveRecordSheet(recordSheetPath('asset', ownerId), {
    ...sheet,
    disposalRecords: [],
  });
  if (options.canSyncDisposals === false) {
    // 无登记权限：处置列表只读，直接沿用本地值（服务端也没被写）
    return { ...saved, disposalRecords: sheet.disposalRecords };
  }
  return { ...saved, disposalRecords: await syncAssetDisposals(ownerId, sheet.disposalRecords) };
};

/** 全量提交：未出现的记录与附件由服务端软删，因此必须传完整列表。 */
export const saveRecordSheet = (path: string, sheet: RecordSheetPayload): Promise<RecordSheet> =>
  api
    .put<RawSheet>(path, {
      receives: sheet.receives.map((record) => ({
        ...record,
        // 归属（owner_type / owner_id / receive_id / biz_type）由服务端赋值，这里只传业务字段
        attachments: fromAttachmentValues(record.attachments),
        issues: record.issues.map((issue) => ({
          ...issue,
          attachments: fromAttachmentValues(issue.attachments),
        })),
      })),
      sourceInfo: sheet.sourceInfo
        ? { ...sheet.sourceInfo, attachments: fromAttachmentValues(sheet.sourceInfo.attachments) }
        : null,
      disposalRecords: sheet.disposalRecords.map((record) => ({
        ...record,
        attachments: fromAttachmentValues(record.attachments),
      })),
      costRecords: sheet.costRecords.map((record) => ({
        ...record,
        // 费用明细无附件：items 原样提交，cost_id 由服务端赋值
        items: record.items,
        attachments: fromAttachmentValues(record.attachments),
      })),
      evaluations: sheet.evaluations.map((record) => ({
        ...record,
        attachments: fromAttachmentValues(record.attachments),
      })),
    })
    .then(normalizeSheet);
