import { useCallback, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import {
  Alert,
  Button,
  DatePicker,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Table,
  Tabs,
  Tag,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { api } from '@/lib/api';
import { AttachmentField } from '@/components/AttachmentField';
import { ActorField } from '@/components/ActorField';
import { useDictOptions } from '@/lib/dict';
import { usePerm } from '@/lib/perm';
import {
  syncAssetDisposals,
  type CostItem,
  type CostRecord,
  type DisposalRecord,
  type EvaluationInfo,
  type RecordIssue,
  type RecordOwnerType,
  type RecordSheetPayload,
} from '@/lib/recordSheet';

// ============================================================================
// 后续记录五模块（处置 / 接收 / 来源 / 成本 / 评估）。设计 §6.6。
//
// 供资产表单第 3 步、项目表单第 3 步、分区详情页三处复用 —— 因此这里不做
// 「一次保存」的编排（那属于各页面的职责），只负责三块的渲染、校验提示与值同步：
// 记录挂在 `value`/`onChange` 上，由宿主用 saveOwnerSheet 落库。
//
// 处置模块**三种主体共用同一套卡片**（新增 = 追加一张空卡、字段就地编辑、
// 删除按钮在卡片里），差异只有数据来源与「这一张能不能改、能不能推进流程」：
//   资产          → 数据来自 disposal_order（读/写都经宿主的 loadOwnerSheet /
//                    saveOwnerSheet，落点是 PUT /assets/{id}/disposals）；
//                    卡片多一个状态标签、资产专有的扩展字段与流程按钮，
//                    且**只有草稿可以改**（审批中及之后由流程端点推进，见设计 §7.1）；
//   项目 / 分区   → 数据来自 record-sheet 的 disposalRecords 段，纯台账、无状态机。
//
// 落库时机两侧一致：**卡片上的改动先攒在本地，点宿主的「保存」才提交**。
// 唯一的例外是流程按钮（提交审批 / 审批通过 / 执行 / 完成）—— 那是「命令」而不是
// 「编辑」，点即生效，且会先把本地处置改动同步一次再推进，避免出现
// 「按提交的是新金额、库里还是旧金额」这种静默不一致。
//
// 本组件**不使用 `Form.Item` 做布局**：它既可能被放进宿主页面的 <Form> 里
// （资产 / 项目表单），也可能在 Form 之外（分区详情页），而 `Form.Item` 依赖
// Form 上下文。改用下面的 Field，三种宿主下行为一致。
// ============================================================================

/** 处置单状态。取自 DisposalService 实际写入的字面量，不要凭空补状态。 */
const DISPOSAL_STATUS_LABEL: Record<string, string> = {
  draft: '草稿',
  approving: '审批中',
  // 实体注释里列为可能状态，但当前没有任何端点写入它（驳回走的是 ApprovalEngine）：
  // 先给个中文名，免得它真出现时在卡片上显示成裸码
  rejected: '已驳回',
  pending_execute: '待执行',
  executing: '执行中',
  completed: '已完成',
};

const DISPOSAL_STATUS_COLOR: Record<string, string> = {
  draft: 'default',
  approving: 'processing',
  rejected: 'error',
  pending_execute: 'warning',
  executing: 'warning',
  completed: 'success',
};

/** 资产侧可推进的流程动作。台账侧没有状态机，不存在这些动作。 */
type FlowAction = 'submit' | 'approve' | 'execute' | 'complete';

/**
 * 动作 → 动作完成后应有的状态。
 *
 * <p>只用于兜底：{@code /approve} 在后端返回空响应体（{@code ApiResponse<Void>}），
 * 拿不到新状态时按状态机前进一格。字面量取自 {@code DisposalService}。
 */
const STATUS_AFTER_FLOW: Record<FlowAction, string> = {
  submit: 'approving',
  approve: 'pending_execute',
  execute: 'executing',
  complete: 'completed',
};

/** 流程端点返回的处置单实体（只取回填卡片要用的字段，不照搬整张实体）。 */
interface DisposalOrderResult {
  status?: string;
  actualAmount?: number;
  counterparty?: string;
}

/** 「执行处置」弹窗收集的两个字段（后端的 execute 需要它们）。 */
interface ExecuteValues {
  actualAmount?: number;
  counterparty?: string;
}

const emptySheet = (): RecordSheetPayload => ({
  receives: [],
  sourceInfo: null,
  disposalRecords: [],
  costRecords: [],
  evaluations: [],
});

/**
 * 字段外壳：标签 + 必填星号 + 内容。
 *
 * <p>刻意不用 `Form.Item` —— 见文件头注释。必填只是**视觉提示**，真正的强校验在
 * 后端（`xxx_name` 在 `xxx_id` 为空时必填，设计 §4.4），保存失败时由宿主页面
 * 把后端消息透出。
 */
const Field = ({
  label,
  required,
  className,
  children,
}: {
  label: string;
  required?: boolean;
  className?: string;
  children: ReactNode;
}) => (
  <div className={className}>
    <div className="text-xs text-[var(--ams-text-secondary)] mb-0.5">
      {required && <span className="text-red-500 mr-0.5">*</span>}
      {label}
    </div>
    {children}
  </div>
);

/** 有效期限（起止）→ RangePicker 值；任一端为空时仍按区间展示。 */
const toRangeValue = (from?: string, to?: string): [Dayjs | null, Dayjs | null] | null =>
  from || to ? [from ? dayjs(from) : null, to ? dayjs(to) : null] : null;

export interface RecordSheetSectionsProps {
  ownerType: RecordOwnerType;
  ownerId?: number | null;
  /** 分区必须传：用于拼 record-sheet 路径 */
  projectId?: number | null;
  /** 所属公司 / 责任部门，透传给 ActorField 收窄员工搜索范围 */
  companyId?: number | null;
  departmentId?: number | null;
  /** 新增态（还没有 ownerId）：整块禁用并提示先保存主体 */
  disabled?: boolean;
  /** 受控值：由宿主以 value / onChange 绑定（不必是 Form.Item） */
  value?: RecordSheetPayload | null;
  onChange?: (value: RecordSheetPayload) => void;
}

export function RecordSheetSections({
  ownerType,
  ownerId,
  companyId,
  departmentId,
  disabled,
  value,
  onChange,
}: RecordSheetSectionsProps) {
  const can = usePerm();
  const sheet = value ?? emptySheet();
  const readOnly = Boolean(disabled) || ownerId == null;
  const isAsset = ownerType === 'asset';

  const handoverTypeOptions = useDictOptions('handover_type');
  const issueTypeOptions = useDictOptions('issue_type');
  const disposalTypeOptions = useDictOptions('disposal_type');
  const costTypeOptions = useDictOptions('cost_type');

  const patch = useCallback(
    (partial: Partial<RecordSheetPayload>) => onChange?.({ ...sheet, ...partial }),
    [onChange, sheet],
  );

  // ---------------------------------------------------------------------------
  // 处置记录：两侧共用同一套卡片（设计 §6.6）
  // ---------------------------------------------------------------------------
  const canRegisterDisposal = !isAsset || can('operation.disposal', 'create');
  /** 正在推进流程的单子 id：只用来给那一个按钮加 loading，不动整表 */
  const [actionBusyId, setActionBusyId] = useState<number | null>(null);
  /** 「执行」需要补录实际金额与交易对手，弹窗里收集（见 DisposalExecuteModal） */
  const [executeTarget, setExecuteTarget] = useState<DisposalRecord | null>(null);

  /**
   * 资产侧可写判定：无 id 是本次新增的卡片（还没落库），有 id 则**只有草稿**可改。
   *
   * <p>非草稿的单子由流程端点驱动（提交审批 → 审批通过 → 执行 → 完成），
   * 表单里再改字段等于绕过审批，因此卡片对这些单子整体只读。
   */
  const isRecordWritable = (record: DisposalRecord) =>
    !isAsset || record.id == null || record.status === 'draft';

  const updateDisposal = (index: number, next: Partial<DisposalRecord>) => {
    patch({
      disposalRecords: sheet.disposalRecords.map((item, i) =>
        i === index ? { ...item, ...next } : item,
      ),
    });
  };

  /**
   * 推进处置流程（资产侧）。**点按钮先落库再推进**：
   *
   * <p>卡片上的改动是本地暂存的，若直接调流程端点，服务端会用**库里那份旧值**发起审批 ——
   * 而提交后卡片变成非草稿、之后保存不再接受写入，用户刚改的金额就静默丢了。
   * 所以这里先把整份列表同步一次（与点「保存」等价），再用服务端返回的规范结果覆盖本地值。
   *
   * <p>{@code /approve} 返回空响应体，状态按服务端状态机手工前进（同 DisposalService.onApproved）。
   *
   * @return 是否成功：失败时已经弹过错误提示，调用方（执行弹窗）据此决定要不要关窗
   */
  const runFlowAction = async (
    id: number,
    action: FlowAction,
    body?: unknown,
  ): Promise<boolean> => {
    setActionBusyId(id);
    try {
      // 无登记权限时列表是只读的、不可能有改动，跳过同步（否则必然 403）
      const list = canRegisterDisposal
        ? await syncAssetDisposals(ownerId as number, sheet.disposalRecords)
        : sheet.disposalRecords;
      const order = await api.post<DisposalOrderResult>(`/disposals/${id}/${action}`, body);
      // 状态回填必须基于**同步后的**列表：拿本次渲染的旧 sheet 改会把刚同步的结果覆盖掉
      patch({
        disposalRecords: list.map((item) =>
          item.id === id
            ? {
                ...item,
                status: order?.status ?? STATUS_AFTER_FLOW[action],
                // 金额与交易对手以服务端为准（execute 会写入这两个字段）
                amountWan: order?.actualAmount ?? item.amountWan,
                counterparty: order?.counterparty ?? item.counterparty,
              }
            : item,
        ),
      });
      message.success('操作成功');
      return true;
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败');
      return false;
    } finally {
      setActionBusyId(null);
    }
  };

  // ---------------------------------------------------------------------------
  // 接收信息（1:N）+ 遗留问题（子表）
  // ---------------------------------------------------------------------------
  const updateReceive = (index: number, next: Partial<RecordSheetPayload['receives'][number]>) => {
    patch({
      receives: sheet.receives.map((item, i) => (i === index ? { ...item, ...next } : item)),
    });
  };

  const updateIssue = (receiveIndex: number, issueIndex: number, next: Partial<RecordIssue>) => {
    const record = sheet.receives[receiveIndex];
    updateReceive(receiveIndex, {
      issues: record.issues.map((item, i) => (i === issueIndex ? { ...item, ...next } : item)),
    });
  };

  const renderIssues = (receiveIndex: number) => {
    const record = sheet.receives[receiveIndex];
    return (
      <div className="flex flex-col gap-3">
        {record.issues.map((issue, issueIndex) => (
          <div
            key={issue.id ?? `new-${issueIndex}`}
            className="grid gap-3 md:grid-cols-2 p-3 rounded border border-[var(--ams-border)]"
          >
            <Field label="问题类型">
              <Select
                allowClear
                className="w-full"
                disabled={readOnly}
                options={issueTypeOptions.options}
                value={issue.issueType}
                onChange={(next) => updateIssue(receiveIndex, issueIndex, { issueType: next })}
              />
            </Field>
            <Field label="发现人" required>
              <ActorField
                disabled={readOnly}
                companyId={companyId}
                departmentId={departmentId}
                value={
                  issue.discovererId != null || issue.discovererName
                    ? { userId: issue.discovererId, name: issue.discovererName ?? '' }
                    : null
                }
                onChange={(next) =>
                  updateIssue(receiveIndex, issueIndex, {
                    discovererId: next?.userId,
                    discovererName: next?.name,
                  })
                }
              />
            </Field>
            <Field label="问题描述" className="md:col-span-2">
              <Input.TextArea
                rows={2}
                disabled={readOnly}
                value={issue.description ?? ''}
                onChange={(e) =>
                  updateIssue(receiveIndex, issueIndex, { description: e.target.value })
                }
              />
            </Field>
            <Field label="现场文件" className="md:col-span-2">
              <AttachmentField
                bizType="issue"
                value={issue.attachments}
                onChange={(attachments) => updateIssue(receiveIndex, issueIndex, { attachments })}
              />
            </Field>
            <div className="md:col-span-2 flex justify-end">
              <Button
                danger
                size="small"
                icon={<DeleteOutlined />}
                disabled={readOnly}
                onClick={() =>
                  updateReceive(receiveIndex, {
                    issues: record.issues.filter((_, i) => i !== issueIndex),
                  })
                }
              >
                删除该问题
              </Button>
            </div>
          </div>
        ))}
        <Button
          type="dashed"
          icon={<PlusOutlined />}
          disabled={readOnly}
          onClick={() =>
            updateReceive(receiveIndex, { issues: [...record.issues, { attachments: [] }] })
          }
        >
          新增遗留问题
        </Button>
      </div>
    );
  };

  const receiveColumns: ColumnsType<RecordSheetPayload['receives'][number]> = [
    {
      title: '交接类型',
      dataIndex: 'handoverType',
      render: (value: string, _row, index) => (
        <Select
          allowClear
          disabled={readOnly}
          className="min-w-28"
          options={handoverTypeOptions.options}
          value={value}
          onChange={(next) => updateReceive(index, { handoverType: next })}
        />
      ),
    },
    {
      title: '文档名称',
      dataIndex: 'docName',
      render: (value: string, _row, index) => (
        <Input
          disabled={readOnly}
          value={value ?? ''}
          onChange={(e) => updateReceive(index, { docName: e.target.value })}
        />
      ),
    },
    {
      title: '交接人',
      dataIndex: 'handoverUserName',
      render: (_value, row, index) => (
        <ActorField
          disabled={readOnly}
          companyId={companyId}
          departmentId={departmentId}
          value={
            row.handoverUserId != null || row.handoverUserName
              ? { userId: row.handoverUserId, name: row.handoverUserName ?? '' }
              : null
          }
          onChange={(next) =>
            updateReceive(index, {
              handoverUserId: next?.userId,
              handoverUserName: next?.name,
            })
          }
        />
      ),
    },
    {
      title: '交接日期',
      dataIndex: 'handoverDate',
      render: (value: string, _row, index) => (
        <DatePicker
          disabled={readOnly}
          value={value ? dayjs(value) : null}
          onChange={(date) =>
            updateReceive(index, { handoverDate: date ? date.format('YYYY-MM-DD') : undefined })
          }
        />
      ),
    },
    {
      title: '交接文件',
      key: 'attachments',
      render: (_value, row, index) => (
        <AttachmentField
          bizType="receive"
          value={row.attachments}
          onChange={(attachments) => updateReceive(index, { attachments })}
        />
      ),
    },
    {
      title: '操作',
      key: 'actions',
      render: (_value, _row, index) => (
        <Button
          danger
          size="small"
          disabled={readOnly}
          onClick={() => patch({ receives: sheet.receives.filter((_, i) => i !== index) })}
        >
          删除
        </Button>
      ),
    },
  ];

  const renderReceives = () => (
    <div className="flex flex-col gap-3">
      <Table
        rowKey={(row, index) => String(row.id ?? `new-${index}`)}
        dataSource={sheet.receives}
        columns={receiveColumns}
        pagination={false}
        locale={{ emptyText: <Empty description="暂无接收信息" /> }}
        expandable={{
          expandedRowRender: (_row, index) => renderIssues(index),
          rowExpandable: () => true,
        }}
      />
      <Button
        type="dashed"
        icon={<PlusOutlined />}
        disabled={readOnly}
        onClick={() => patch({ receives: [...sheet.receives, { issues: [], attachments: [] }] })}
      >
        新增接收信息
      </Button>
    </div>
  );

  // ---------------------------------------------------------------------------
  // 来源明细（1:1）
  // ---------------------------------------------------------------------------
  const renderSourceInfo = () => (
    <div className="grid gap-3 md:grid-cols-2">
      <Field label="来源人">
        <ActorField
          disabled={readOnly}
          companyId={companyId}
          departmentId={departmentId}
          value={
            sheet.sourceInfo?.sourcePersonId != null || sheet.sourceInfo?.sourcePersonName
              ? {
                  userId: sheet.sourceInfo?.sourcePersonId,
                  name: sheet.sourceInfo?.sourcePersonName ?? '',
                }
              : null
          }
          onChange={(next) =>
            patch({
              sourceInfo: {
                ...(sheet.sourceInfo ?? { attachments: [] }),
                sourcePersonId: next?.userId,
                sourcePersonName: next?.name,
              },
            })
          }
        />
      </Field>
      <Field label="来源单位">
        <Input
          disabled={readOnly}
          value={sheet.sourceInfo?.sourceUnit ?? ''}
          onChange={(e) =>
            patch({
              sourceInfo: {
                ...(sheet.sourceInfo ?? { attachments: [] }),
                sourceUnit: e.target.value,
              },
            })
          }
        />
      </Field>
      <Field label="来源日期">
        <DatePicker
          disabled={readOnly}
          className="w-full"
          value={sheet.sourceInfo?.sourceDate ? dayjs(sheet.sourceInfo.sourceDate) : null}
          onChange={(date) =>
            patch({
              sourceInfo: {
                ...(sheet.sourceInfo ?? { attachments: [] }),
                sourceDate: date ? date.format('YYYY-MM-DD') : undefined,
              },
            })
          }
        />
      </Field>
      <Field label="来源描述" className="md:col-span-2">
        <Input.TextArea
          rows={3}
          disabled={readOnly}
          value={sheet.sourceInfo?.sourceDesc ?? ''}
          onChange={(e) =>
            patch({
              sourceInfo: {
                ...(sheet.sourceInfo ?? { attachments: [] }),
                sourceDesc: e.target.value,
              },
            })
          }
        />
      </Field>
      <Field label="来源附件" className="md:col-span-2">
        <AttachmentField
          bizType="source"
          value={sheet.sourceInfo?.attachments ?? []}
          onChange={(attachments) =>
            patch({ sourceInfo: { ...(sheet.sourceInfo ?? {}), attachments } })
          }
        />
      </Field>
    </div>
  );

  // ---------------------------------------------------------------------------
  // 成本信息（1:N，含费用明细子表）
  // ---------------------------------------------------------------------------
  const updateCost = (index: number, next: Partial<CostRecord>) => {
    patch({
      costRecords: sheet.costRecords.map((item, i) => (i === index ? { ...item, ...next } : item)),
    });
  };

  const updateCostItem = (costIndex: number, itemIndex: number, next: Partial<CostItem>) => {
    const record = sheet.costRecords[costIndex];
    updateCost(costIndex, {
      items: record.items.map((item, i) => (i === itemIndex ? { ...item, ...next } : item)),
    });
  };

  /** 费用明细表：内联编辑 + 就地增删；columns 需要 costIndex，故在函数内构建。 */
  const renderCostItems = (costIndex: number) => {
    const record = sheet.costRecords[costIndex];
    const columns: ColumnsType<CostItem> = [
      {
        title: '费用名称',
        dataIndex: 'feeName',
        render: (value: string, _row, itemIndex) => (
          <Input
            disabled={readOnly}
            value={value ?? ''}
            onChange={(e) => updateCostItem(costIndex, itemIndex, { feeName: e.target.value })}
          />
        ),
      },
      {
        title: '成本类型',
        dataIndex: 'costType',
        render: (value: string, _row, itemIndex) => (
          <Select
            allowClear
            className="min-w-28"
            disabled={readOnly}
            options={costTypeOptions.options}
            value={value}
            onChange={(next) => updateCostItem(costIndex, itemIndex, { costType: next })}
          />
        ),
      },
      {
        title: '金额',
        dataIndex: 'amount',
        render: (value: number, _row, itemIndex) => (
          <InputNumber
            className="w-full"
            min={0}
            precision={2}
            disabled={readOnly}
            value={value}
            onChange={(next) => updateCostItem(costIndex, itemIndex, { amount: next ?? undefined })}
          />
        ),
      },
      {
        title: '备注',
        dataIndex: 'remark',
        render: (value: string, _row, itemIndex) => (
          <Input
            disabled={readOnly}
            value={value ?? ''}
            onChange={(e) => updateCostItem(costIndex, itemIndex, { remark: e.target.value })}
          />
        ),
      },
      {
        title: '操作',
        key: 'actions',
        width: 80,
        render: (_value, _row, itemIndex) => (
          <Button
            danger
            size="small"
            disabled={readOnly}
            onClick={() =>
              updateCost(costIndex, { items: record.items.filter((_, i) => i !== itemIndex) })
            }
          >
            删除
          </Button>
        ),
      },
    ];
    return (
      <div className="flex flex-col gap-2">
        <Table
          rowKey={(row, index) => String(row.id ?? `new-${index}`)}
          dataSource={record.items}
          columns={columns}
          pagination={false}
          size="small"
          locale={{
            emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无费用明细" />,
          }}
        />
        <Button
          type="dashed"
          size="small"
          icon={<PlusOutlined />}
          disabled={readOnly}
          onClick={() => updateCost(costIndex, { items: [...record.items, {}] })}
        >
          新增费用明细
        </Button>
      </div>
    );
  };

  const renderCosts = () => (
    <div className="flex flex-col gap-3">
      {sheet.costRecords.length === 0 && (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无成本信息" />
      )}
      {sheet.costRecords.map((record, index) => (
        <div
          key={record.id ?? `new-${index}`}
          className="grid gap-3 md:grid-cols-2 p-3 rounded border border-[var(--ams-border)]"
        >
          <Field label="成本金额">
            <InputNumber
              className="w-full"
              disabled={readOnly}
              min={0}
              precision={2}
              addonAfter="万元"
              value={record.amountWan}
              onChange={(next) => updateCost(index, { amountWan: next ?? undefined })}
            />
          </Field>
          <Field label="成本日期">
            <DatePicker
              className="w-full"
              disabled={readOnly}
              value={record.costDate ? dayjs(record.costDate) : null}
              onChange={(date) =>
                updateCost(index, { costDate: date ? date.format('YYYY-MM-DD') : undefined })
              }
            />
          </Field>
          <Field label="备注" className="md:col-span-2">
            <Input
              disabled={readOnly}
              value={record.remark ?? ''}
              onChange={(e) => updateCost(index, { remark: e.target.value })}
            />
          </Field>
          <Field label="附件" className="md:col-span-2">
            <AttachmentField
              bizType="cost"
              value={record.attachments}
              onChange={(attachments) => updateCost(index, { attachments })}
            />
          </Field>
          <div className="md:col-span-2">
            <div className="text-xs text-[var(--ams-text-secondary)] mb-1">费用明细</div>
            {renderCostItems(index)}
          </div>
          <div className="md:col-span-2 flex justify-end">
            <Button
              danger
              size="small"
              icon={<DeleteOutlined />}
              disabled={readOnly}
              onClick={() =>
                patch({ costRecords: sheet.costRecords.filter((_, i) => i !== index) })
              }
            >
              删除该成本记录
            </Button>
          </div>
        </div>
      ))}
      <Button
        type="dashed"
        icon={<PlusOutlined />}
        disabled={readOnly}
        onClick={() =>
          patch({ costRecords: [...sheet.costRecords, { items: [], attachments: [] }] })
        }
      >
        新增成本信息
      </Button>
    </div>
  );

  // ---------------------------------------------------------------------------
  // 评估信息（1:N）
  // ---------------------------------------------------------------------------
  const updateEvaluation = (index: number, next: Partial<EvaluationInfo>) => {
    patch({
      evaluations: sheet.evaluations.map((item, i) => (i === index ? { ...item, ...next } : item)),
    });
  };

  const renderEvaluations = () => (
    <div className="flex flex-col gap-3">
      {sheet.evaluations.length === 0 && (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无评估信息" />
      )}
      {sheet.evaluations.map((record, index) => (
        <div
          key={record.id ?? `new-${index}`}
          className="grid gap-3 md:grid-cols-2 p-3 rounded border border-[var(--ams-border)]"
        >
          <Field label="评估机构">
            <Input
              disabled={readOnly}
              value={record.institution ?? ''}
              onChange={(e) => updateEvaluation(index, { institution: e.target.value })}
            />
          </Field>
          <Field label="资产价值">
            <InputNumber
              className="w-full"
              disabled={readOnly}
              min={0}
              precision={2}
              value={record.assetValue}
              onChange={(next) => updateEvaluation(index, { assetValue: next ?? undefined })}
            />
          </Field>
          <Field label="租赁单价">
            <InputNumber
              className="w-full"
              disabled={readOnly}
              min={0}
              precision={2}
              value={record.rentUnitPrice}
              onChange={(next) => updateEvaluation(index, { rentUnitPrice: next ?? undefined })}
            />
          </Field>
          <Field label="租赁价格">
            <InputNumber
              className="w-full"
              disabled={readOnly}
              min={0}
              precision={2}
              value={record.rentPrice}
              onChange={(next) => updateEvaluation(index, { rentPrice: next ?? undefined })}
            />
          </Field>
          <Field label="评估时间">
            <DatePicker
              className="w-full"
              disabled={readOnly}
              value={record.evaluateDate ? dayjs(record.evaluateDate) : null}
              onChange={(date) =>
                updateEvaluation(index, {
                  evaluateDate: date ? date.format('YYYY-MM-DD') : undefined,
                })
              }
            />
          </Field>
          <Field label="评估有效期限">
            <DatePicker.RangePicker
              className="w-full"
              disabled={readOnly}
              value={toRangeValue(record.validFrom, record.validTo)}
              onChange={(range) =>
                updateEvaluation(index, {
                  validFrom: range?.[0] ? range[0].format('YYYY-MM-DD') : undefined,
                  validTo: range?.[1] ? range[1].format('YYYY-MM-DD') : undefined,
                })
              }
            />
          </Field>
          <Field label="附件（PDF / Word）" className="md:col-span-2">
            <AttachmentField
              bizType="evaluation"
              accept=".pdf,.doc,.docx,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
              value={record.attachments}
              onChange={(attachments) => updateEvaluation(index, { attachments })}
            />
          </Field>
          <div className="md:col-span-2 flex justify-end">
            <Button
              danger
              size="small"
              icon={<DeleteOutlined />}
              disabled={readOnly}
              onClick={() =>
                patch({ evaluations: sheet.evaluations.filter((_, i) => i !== index) })
              }
            >
              删除该评估记录
            </Button>
          </div>
        </div>
      ))}
      <Button
        type="dashed"
        icon={<PlusOutlined />}
        disabled={readOnly}
        onClick={() => patch({ evaluations: [...sheet.evaluations, { attachments: [] }] })}
      >
        新增评估信息
      </Button>
    </div>
  );

  // ---------------------------------------------------------------------------
  // 处置记录：同一套卡片供三种主体使用（设计 §6.6）
  // ---------------------------------------------------------------------------
  /**
   * 资产侧的流程按钮：按「当前状态 + 权限码」显隐，后端仍会强校验（设计 §5.3）。
   *
   * <p>只对**已保存**（有 id）的单子出现 —— 还没落库的草稿没有可推进的对象；
   * 卡片上的改动由 {@link runFlowAction} 在推进前先提交一次。
   */
  const renderFlowActions = (record: DisposalRecord) => {
    const id = record.id;
    if (!isAsset || id == null) return null;
    const busy = actionBusyId === id;
    return (
      <>
        {/* 「能编辑资产」不等于「能审批」，所以审批单独用 approve 动作码 */}
        {record.status === 'draft' && can('operation.disposal', 'create') && (
          <Button
            type="link"
            size="small"
            loading={busy}
            onClick={() => void runFlowAction(id, 'submit')}
          >
            提交审批
          </Button>
        )}
        {record.status === 'approving' && can('operation.disposal', 'approve') && (
          <Button
            type="link"
            size="small"
            loading={busy}
            onClick={() => void runFlowAction(id, 'approve')}
          >
            审批通过
          </Button>
        )}
        {(record.status === 'pending_execute' || record.status === 'executing') &&
          can('operation.disposal', 'update') && (
            <Button type="link" size="small" onClick={() => setExecuteTarget(record)}>
              执行
            </Button>
          )}
        {record.status === 'executing' && can('operation.disposal', 'update') && (
          <Button
            type="link"
            size="small"
            loading={busy}
            onClick={() => void runFlowAction(id, 'complete')}
          >
            完成
          </Button>
        )}
      </>
    );
  };

  /**
   * 一张处置卡片。**公共字段的顺序与标签两侧必须一致**
   * （类型 → 人 → 金额 → 日期 → 备注 → 附件），
   * 否则「三处共用一套交互」就退化成了「三处各画一遍」。
   *
   * <p>资产侧多三样东西：状态标签、扩展字段（评估价 / 账面价 / 交易对手 / 处置事由）、
   * 流程按钮。金额字段两侧单位不同（台账万元、资产元），用 {@code addonAfter} 显式画出来。
   */
  const renderDisposalCard = (record: DisposalRecord, index: number) => {
    // 卡片能不能改：宿主禁用 / 新增态 / 无登记权限 / 资产侧非草稿
    const locked = readOnly || !canRegisterDisposal || !isRecordWritable(record);
    return (
      <div
        key={record.id ?? `new-${index}`}
        className="grid gap-3 md:grid-cols-2 p-3 rounded border border-[var(--ams-border)]"
      >
        {isAsset && (
          <div className="md:col-span-2 flex items-center gap-2">
            <Tag color={DISPOSAL_STATUS_COLOR[record.status ?? ''] ?? 'default'}>
              {DISPOSAL_STATUS_LABEL[record.status ?? ''] ?? record.status ?? '草稿'}
            </Tag>
            {record.id == null && (
              <span className="text-xs text-[var(--ams-text-secondary)]">
                尚未落库：点宿主的「保存」后才提交
              </span>
            )}
          </div>
        )}
        <Field label="处置类型">
          <Select
            allowClear
            className="w-full"
            disabled={locked}
            options={disposalTypeOptions.options}
            value={record.disposalType}
            onChange={(next) => updateDisposal(index, { disposalType: next })}
          />
        </Field>
        <Field label="处置人" required>
          <ActorField
            disabled={locked}
            companyId={companyId}
            departmentId={departmentId}
            value={
              record.disposalUserId != null || record.disposalUserName
                ? { userId: record.disposalUserId, name: record.disposalUserName ?? '' }
                : null
            }
            onChange={(next) =>
              updateDisposal(index, {
                disposalUserId: next?.userId,
                disposalUserName: next?.name,
              })
            }
          />
        </Field>
        <Field label="处置金额">
          <InputNumber
            className="w-full"
            disabled={locked}
            min={0}
            precision={2}
            addonAfter={isAsset ? '元' : '万元'}
            value={record.amountWan}
            onChange={(next) => updateDisposal(index, { amountWan: next ?? undefined })}
          />
        </Field>
        <Field label="处置日期">
          <DatePicker
            className="w-full"
            disabled={locked}
            value={record.disposalDate ? dayjs(record.disposalDate) : null}
            onChange={(date) =>
              updateDisposal(index, {
                disposalDate: date ? date.format('YYYY-MM-DD') : undefined,
              })
            }
          />
        </Field>
        <Field label="备注" className="md:col-span-2">
          <Input
            disabled={locked}
            value={record.remark ?? ''}
            onChange={(e) => updateDisposal(index, { remark: e.target.value })}
          />
        </Field>
        <Field label="处置附件" className="md:col-span-2">
          <AttachmentField
            bizType="disposal"
            value={record.attachments}
            onChange={(attachments) => updateDisposal(index, { attachments })}
          />
        </Field>
        {isAsset && (
          <>
            {/* 以下字段台账没有对应业务概念，只在资产卡片上出现 */}
            <div className="md:col-span-2 border-t border-[var(--ams-border)]" />
            <Field label="评估价">
              <InputNumber
                className="w-full"
                disabled={locked}
                min={0}
                precision={2}
                value={record.assessedValue}
                onChange={(next) => updateDisposal(index, { assessedValue: next ?? undefined })}
              />
            </Field>
            <Field label="账面价">
              <InputNumber
                className="w-full"
                disabled={locked}
                min={0}
                precision={2}
                value={record.bookValue}
                onChange={(next) => updateDisposal(index, { bookValue: next ?? undefined })}
              />
            </Field>
            <Field label="交易对手">
              <Input
                disabled={locked}
                value={record.counterparty ?? ''}
                onChange={(e) => updateDisposal(index, { counterparty: e.target.value })}
              />
            </Field>
            <Field label="处置事由" className="md:col-span-2">
              <Input
                disabled={locked}
                value={record.reason ?? ''}
                onChange={(e) => updateDisposal(index, { reason: e.target.value })}
              />
            </Field>
          </>
        )}
        <div className="md:col-span-2 flex justify-end items-center gap-2">
          {renderFlowActions(record)}
          <Button
            danger
            size="small"
            icon={<DeleteOutlined />}
            disabled={locked}
            onClick={() =>
              patch({ disposalRecords: sheet.disposalRecords.filter((_, i) => i !== index) })
            }
          >
            删除该处置记录
          </Button>
        </div>
      </div>
    );
  };

  const renderDisposals = () => (
    <div className="flex flex-col gap-3">
      {sheet.disposalRecords.length === 0 && (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无处置记录" />
      )}
      {sheet.disposalRecords.map((record, index) => renderDisposalCard(record, index))}
      <Button
        type="dashed"
        icon={<PlusOutlined />}
        disabled={readOnly || !canRegisterDisposal}
        onClick={() => patch({ disposalRecords: [...sheet.disposalRecords, { attachments: [] }] })}
      >
        新增处置记录
      </Button>
    </div>
  );

  // ---------------------------------------------------------------------------
  // 渲染
  // ---------------------------------------------------------------------------
  return (
    <div className="flex flex-col gap-4">
      {readOnly && <Alert type="info" showIcon message="保存主体后即可录入后续记录" />}
      <Tabs
        items={[
          {
            key: 'disposal',
            label: '处置记录',
            // 三种主体同一套卡片：资产卡片多状态标签、扩展字段与流程按钮（设计 §6.6）
            children: renderDisposals(),
          },
          { key: 'receive', label: '接收信息', children: renderReceives() },
          { key: 'source', label: '来源明细', children: renderSourceInfo() },
          { key: 'cost', label: '成本信息', children: renderCosts() },
          { key: 'evaluation', label: '评估信息', children: renderEvaluations() },
        ]}
      />

      {isAsset && ownerId != null && (
        <DisposalExecuteModal
          record={executeTarget}
          onClose={() => setExecuteTarget(null)}
          onSubmit={(target, values) =>
            runFlowAction(target.id as number, 'execute', {
              actualAmount: values.actualAmount,
              counterparty: values.counterparty,
            })
          }
        />
      )}
    </div>
  );
}

/**
 * 执行处置（资产侧的流程命令；台账没有这一步，因此不作为「卡片交互」的一致性对象）。
 *
 * <p>后端 {@code execute} 需要 actualAmount 与 counterparty，而卡片在非草稿状态下是只读的
 * （草稿才可改，见 {@code isRecordWritable}），所以这两个值在这里补录。
 * 「先落库再推进」的编排仍在父组件（{@code runFlowAction}）里，本弹窗只负责收集输入。
 */
function DisposalExecuteModal({
  record,
  onClose,
  onSubmit,
}: {
  record: DisposalRecord | null;
  onClose: () => void;
  /** 返回是否成功：失败时**不关窗**，让使用者改完输入直接重试 */
  onSubmit: (record: DisposalRecord, values: ExecuteValues) => Promise<boolean>;
}) {
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (record) {
      form.setFieldsValue({
        actualAmount: record.amountWan,
        counterparty: record.counterparty,
      });
    } else {
      form.resetFields();
    }
  }, [record, form]);

  const handleOk = async () => {
    if (!record || record.id == null) return;
    try {
      const values = (await form.validateFields()) as ExecuteValues;
      setSubmitting(true);
      // 流程失败（如抵押校验不通过）时不关窗：输入还在，改完可直接重试
      if (await onSubmit(record, values)) onClose();
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : '执行失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={record != null}
      title="执行处置"
      okText="确认执行"
      confirmLoading={submitting}
      onOk={() => void handleOk()}
      onCancel={onClose}
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        <Form.Item name="actualAmount" label="实际处置金额">
          <InputNumber className="w-full" min={0} precision={2} />
        </Form.Item>
        <Form.Item name="counterparty" label="交易对手">
          <Input />
        </Form.Item>
      </Form>
    </Modal>
  );
}
