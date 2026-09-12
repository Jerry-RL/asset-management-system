import { api } from '@/lib/api';
import type { AttachmentValue } from '@/components/AttachmentField';

// ============================================================================
// 后续记录（处置 / 接收 / 来源）的聚合读写。
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

/** 项目 / 分区侧的处置台账记录（biz_disposal_record），不走审批、不影响生命周期 */
export interface DisposalRecord {
  id?: number;
  disposalType?: string;
  disposalUserId?: number;
  /** 姓名快照：disposalUserId 为空时由前端手填，必填 */
  disposalUserName?: string;
  /** 万元。后端字段名即 amountWan，两者都不做单位换算 */
  amountWan?: number;
  /** yyyy-MM-dd */
  disposalDate?: string;
  remark?: string;
  attachments: AttachmentValue[];
}

/**
 * 资产侧处置单（disposal_order）：**只读**回显，由 `GET /assets/{id}/disposals` 提供，
 * 不参与 record-sheet 的读写。它与 {@link DisposalRecord} 是两种不同的事物：
 * 前者带审批状态机并驱动资产生命周期，后者是项目 / 分区的纯台账。
 */
export interface DisposalOrderView {
  id: number;
  disposalType?: string;
  disposalUserId?: number;
  disposalUserName?: string;
  amountWan?: number;
  /** 与 amountWan 同源（disposal_order.actual_amount），不做万元换算 */
  actualAmount?: number;
  /** yyyy-MM-dd */
  disposalDate?: string;
  remark?: string;
  status?: string;
  attachments: AttachmentValue[];
}

export interface RecordSheet {
  receives: ReceiveRecord[];
  sourceInfo: SourceInfo | null;
  disposalRecords: DisposalRecord[];
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
  // 资产的 disposalRecords 恒为空（服务端忽略该段），有值时一定是项目 / 分区
  disposalRecords: (raw?.disposalRecords ?? []).map((record) => ({
    ...record,
    attachments: toAttachmentValues(record.attachments as unknown as RecordAttachment[]),
  })),
});

export const loadRecordSheet = (path: string): Promise<RecordSheet> =>
  api.get<RawSheet>(path).then(normalizeSheet);

/**
 * 资产处置单列表（只读，含附件回显）。
 *
 * <p>走独立的 `GET /assets/{id}/disposals`，不参与 record-sheet 读写。
 * **必须经 `toAttachmentValues` 归一**：服务端回显的是 `fileName`/`url`，
 * 而组件值用的是 `name` —— 直接用会让附件名渲染成 `undefined`、下载链接也拼不出来。
 */
export const loadAssetDisposals = (assetId: number): Promise<DisposalOrderView[]> =>
  api
    .get<(Omit<DisposalOrderView, 'attachments'> & { attachments?: RecordAttachment[] })[]>(
      `/assets/${assetId}/disposals`,
    )
    .then((rows) =>
      (rows ?? []).map((row) => ({ ...row, attachments: toAttachmentValues(row.attachments) })),
    );

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
    })
    .then(normalizeSheet);
