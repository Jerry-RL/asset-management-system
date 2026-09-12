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
  Space,
  Table,
  Tabs,
  Tag,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { api } from '@/lib/api';
import { AttachmentField } from '@/components/AttachmentField';
import { ActorField } from '@/components/ActorField';
import { useDictOptions } from '@/lib/dict';
import type { SelectOption } from '@/lib/dict';
import { usePerm } from '@/lib/perm';
import {
  loadAssetDisposals,
  type DisposalOrderView,
  type RecordIssue,
  type RecordOwnerType,
  type RecordSheetPayload,
} from '@/lib/recordSheet';

// ============================================================================
// 后续记录三模块（处置 / 接收 / 来源）。设计 §6.6。
//
// 供资产表单第 3 步、项目表单第 3 步、分区详情页三处复用 —— 因此这里不做
// 「一次保存」的编排（那属于各页面的职责），只负责三块的渲染、校验提示与值同步：
// 记录挂在 `value`/`onChange` 上，由宿主决定何时用 saveRecordSheet 落库。
//
// 处置模块按 ownerType 分叉：
//   asset          → 只读列表，数据来自 disposal_order（独立端点），并在此走
//                    新建草稿 → 提交审批 → 执行 → 完成的表单内闭环（设计 §2）；
//   project / zone → 可编辑台账，落在 record-sheet 的 disposalRecords 段。
//
// 本组件**不使用 `Form.Item` 做布局**：它既可能被放进宿主页面的 <Form> 里
// （资产 / 项目表单），也可能在 Form 之外（分区详情页），而 `Form.Item` 依赖
// Form 上下文。改用下面的 Field，三种宿主下行为一致。
// ============================================================================

/** 处置单状态。取自 DisposalService 实际写入的字面量，不要凭空补状态。 */
const DISPOSAL_STATUS_LABEL: Record<string, string> = {
  draft: '草稿',
  approving: '审批中',
  pending_execute: '待执行',
  executing: '执行中',
  completed: '已完成',
};

const DISPOSAL_STATUS_COLOR: Record<string, string> = {
  draft: 'default',
  approving: 'processing',
  pending_execute: 'warning',
  executing: 'warning',
  completed: 'success',
};

const emptySheet = (): RecordSheetPayload => ({
  receives: [],
  sourceInfo: null,
  disposalRecords: [],
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

/** 字典码 → 展示名；查不到时回落成原码，避免把未维护的字典项显示成空白。 */
const dictLabel = (options: SelectOption[], value?: string) => {
  if (value == null || value === '') return '-';
  return String(options.find((item) => String(item.value) === String(value))?.label ?? value);
};

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

  const patch = useCallback(
    (partial: Partial<RecordSheetPayload>) => onChange?.({ ...sheet, ...partial }),
    [onChange, sheet],
  );

  // ---------------------------------------------------------------------------
  // 资产侧处置单：只读列表 + 表单内流程（设计 §2「可走完整流程」）
  // ---------------------------------------------------------------------------
  const [disposals, setDisposals] = useState<DisposalOrderView[]>([]);
  const [loadingDisposals, setLoadingDisposals] = useState(false);
  const [disposalsVersion, setDisposalsVersion] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [executeTarget, setExecuteTarget] = useState<DisposalOrderView | null>(null);
  const [actionBusyId, setActionBusyId] = useState<number | null>(null);

  const assetId = isAsset && ownerId != null ? ownerId : null;

  const reloadDisposals = useCallback(() => setDisposalsVersion((v) => v + 1), []);

  useEffect(() => {
    if (assetId == null) {
      setDisposals([]);
      return;
    }
    let cancelled = false;
    setLoadingDisposals(true);
    loadAssetDisposals(assetId)
      .then((rows) => {
        if (!cancelled) setDisposals(rows);
      })
      .catch(() => {
        // 无 asset.ledger:view 或网络异常：展示为空而不是让整页崩掉
        if (!cancelled) setDisposals([]);
      })
      .finally(() => {
        if (!cancelled) setLoadingDisposals(false);
      });
    return () => {
      cancelled = true;
    };
  }, [assetId, disposalsVersion]);

  /** 无请求体的状态流转：提交 / 审批 / 完成 */
  const runFlowAction = async (id: number, action: 'submit' | 'approve' | 'complete') => {
    setActionBusyId(id);
    try {
      await api.post(`/disposals/${id}/${action}`);
      message.success('操作成功');
      reloadDisposals();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败');
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
  // 处置台账（项目 / 分区，可编辑）
  // ---------------------------------------------------------------------------
  const updateDisposal = (
    index: number,
    next: Partial<RecordSheetPayload['disposalRecords'][number]>,
  ) => {
    patch({
      disposalRecords: sheet.disposalRecords.map((item, i) =>
        i === index ? { ...item, ...next } : item,
      ),
    });
  };

  const renderDisposalLedger = () => (
    <div className="flex flex-col gap-3">
      {sheet.disposalRecords.map((record, index) => (
        <div
          key={record.id ?? `new-${index}`}
          className="grid gap-3 md:grid-cols-2 p-3 rounded border border-[var(--ams-border)]"
        >
          <Field label="处置类型">
            <Select
              allowClear
              className="w-full"
              disabled={readOnly}
              options={disposalTypeOptions.options}
              value={record.disposalType}
              onChange={(next) => updateDisposal(index, { disposalType: next })}
            />
          </Field>
          <Field label="处置人" required>
            <ActorField
              disabled={readOnly}
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
          <Field label="处置金额（万元）">
            <InputNumber
              className="w-full"
              disabled={readOnly}
              min={0}
              precision={2}
              value={record.amountWan}
              onChange={(next) => updateDisposal(index, { amountWan: next ?? undefined })}
            />
          </Field>
          <Field label="处置日期">
            <DatePicker
              className="w-full"
              disabled={readOnly}
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
              disabled={readOnly}
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
          <div className="md:col-span-2 flex justify-end">
            <Button
              danger
              size="small"
              icon={<DeleteOutlined />}
              disabled={readOnly}
              onClick={() =>
                patch({ disposalRecords: sheet.disposalRecords.filter((_, i) => i !== index) })
              }
            >
              删除该处置记录
            </Button>
          </div>
        </div>
      ))}
      <Button
        type="dashed"
        icon={<PlusOutlined />}
        disabled={readOnly}
        onClick={() => patch({ disposalRecords: [...sheet.disposalRecords, { attachments: [] }] })}
      >
        新增处置记录
      </Button>
    </div>
  );

  // ---------------------------------------------------------------------------
  // 资产处置单（disposal_order）：只读列表 + 流程按钮
  // ---------------------------------------------------------------------------
  const assetDisposalColumns: ColumnsType<DisposalOrderView> = [
    {
      title: '状态',
      dataIndex: 'status',
      render: (status: string) => (
        <Tag color={DISPOSAL_STATUS_COLOR[status] ?? 'default'}>
          {DISPOSAL_STATUS_LABEL[status] ?? status}
        </Tag>
      ),
    },
    {
      title: '处置类型',
      dataIndex: 'disposalType',
      render: (value: string) => dictLabel(disposalTypeOptions.options, value),
    },
    { title: '处置人', dataIndex: 'disposalUserName' },
    { title: '处置金额', dataIndex: 'amountWan' },
    { title: '处置日期', dataIndex: 'disposalDate' },
    {
      title: '附件',
      key: 'attachments',
      render: (_value, row) => (
        <Space direction="vertical" size={2}>
          {row.attachments.length === 0 && '-'}
          {row.attachments.map((item) => (
            <a key={item.fileId} href={item.url} target="_blank" rel="noreferrer">
              {item.name}
            </a>
          ))}
        </Space>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      render: (_value, row) => {
        const busy = actionBusyId === row.id;
        return (
          <Space>
            {/* 按钮按 operation.disposal:* 显隐，后端仍会强校验（设计 §5.3）：
                「能编辑资产」不等于「能审批」，所以审批单独用 approve 动作码。 */}
            {row.status === 'draft' && can('operation.disposal', 'create') && (
              <Button
                type="link"
                size="small"
                loading={busy}
                onClick={() => void runFlowAction(row.id, 'submit')}
              >
                提交审批
              </Button>
            )}
            {row.status === 'approving' && can('operation.disposal', 'approve') && (
              <Button
                type="link"
                size="small"
                loading={busy}
                onClick={() => void runFlowAction(row.id, 'approve')}
              >
                审批通过
              </Button>
            )}
            {(row.status === 'pending_execute' || row.status === 'executing') &&
              can('operation.disposal', 'update') && (
                <Button type="link" size="small" onClick={() => setExecuteTarget(row)}>
                  执行
                </Button>
              )}
            {row.status === 'executing' && can('operation.disposal', 'update') && (
              <Button
                type="link"
                size="small"
                loading={busy}
                onClick={() => void runFlowAction(row.id, 'complete')}
              >
                完成
              </Button>
            )}
          </Space>
        );
      },
    },
  ];

  const renderAssetDisposals = () => (
    <div className="flex flex-col gap-3">
      <div className="flex justify-end">
        <Button
          type="primary"
          icon={<PlusOutlined />}
          disabled={readOnly || !can('operation.disposal', 'create')}
          onClick={() => setCreateOpen(true)}
        >
          新建处置单
        </Button>
      </div>
      <Table
        rowKey="id"
        loading={loadingDisposals}
        dataSource={disposals}
        columns={assetDisposalColumns}
        pagination={false}
        locale={{ emptyText: <Empty description="暂无处置记录" /> }}
      />
    </div>
  );

  return (
    <div className="flex flex-col gap-4">
      {readOnly && <Alert type="info" showIcon message="保存主体后即可录入后续记录" />}
      <Tabs
        items={[
          {
            key: 'disposal',
            label: '处置记录',
            children: isAsset ? renderAssetDisposals() : renderDisposalLedger(),
          },
          { key: 'receive', label: '接收信息', children: renderReceives() },
          { key: 'source', label: '来源明细', children: renderSourceInfo() },
        ]}
      />

      {isAsset && assetId != null && (
        <>
          <DisposalCreateModal
            open={createOpen}
            assetId={assetId}
            companyId={companyId}
            departmentId={departmentId}
            disposalTypeOptions={disposalTypeOptions.options}
            onClose={() => setCreateOpen(false)}
            onCreated={() => {
              setCreateOpen(false);
              reloadDisposals();
            }}
          />
          <DisposalExecuteModal
            order={executeTarget}
            onClose={() => setExecuteTarget(null)}
            onDone={() => {
              setExecuteTarget(null);
              reloadDisposals();
            }}
          />
        </>
      )}
    </div>
  );
}

/** 新建处置单（draft）。字段 1:1 对应后端的 DisposalCreateRequest，不臆造字段。 */
function DisposalCreateModal({
  open,
  assetId,
  companyId,
  departmentId,
  disposalTypeOptions,
  onClose,
  onCreated,
}: {
  open: boolean;
  assetId: number;
  companyId?: number | null;
  departmentId?: number | null;
  disposalTypeOptions: SelectOption[];
  onClose: () => void;
  onCreated: () => void;
}) {
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [attachments, setAttachments] = useState<{ fileId: number; url: string; name: string }[]>(
    [],
  );

  // 关闭即重置：否则下次打开还留着上一次填了一半的值
  useEffect(() => {
    if (!open) {
      form.resetFields();
      setAttachments([]);
    }
  }, [open, form]);

  const handleOk = async () => {
    try {
      const values = (await form.validateFields()) as Record<string, unknown> & {
        disposalDate?: dayjs.Dayjs;
        disposalUser?: { userId?: number; name: string } | null;
      };
      setSubmitting(true);
      await api.post('/disposals', {
        assetId,
        disposalType: values.disposalType,
        reason: values.reason,
        assessedValue: values.assessedValue,
        bookValue: values.bookValue,
        actualAmount: values.actualAmount,
        counterparty: values.counterparty,
        disposalUserId: values.disposalUser?.userId,
        disposalUserName: values.disposalUser?.name,
        disposalDate: values.disposalDate ? values.disposalDate.format('YYYY-MM-DD') : undefined,
        remark: values.remark,
        attachments: attachments.map((item, index) => ({ fileId: item.fileId, sort: index })),
      });
      message.success('处置单已创建，可在列表中提交审批');
      onCreated();
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : '创建处置单失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title="新建处置单"
      width={720}
      okText="创建草稿"
      confirmLoading={submitting}
      onOk={() => void handleOk()}
      onCancel={onClose}
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        <div className="grid gap-x-3 md:grid-cols-2">
          <Form.Item
            name="disposalType"
            label="处置类型"
            rules={[{ required: true, message: '请选择处置类型' }]}
          >
            <Select options={disposalTypeOptions} />
          </Form.Item>
          <Form.Item
            name="disposalUser"
            label="处置人"
            rules={[
              {
                validator: (_rule, value: { name?: string } | null) =>
                  value?.name
                    ? Promise.resolve()
                    : Promise.reject(new Error('请填写处置人（可搜索员工或直接输入外部人员姓名）')),
              },
            ]}
          >
            <ActorField companyId={companyId} departmentId={departmentId} />
          </Form.Item>
          <Form.Item name="disposalDate" label="处置日期">
            <DatePicker className="w-full" />
          </Form.Item>
          <Form.Item name="counterparty" label="交易对手">
            <Input />
          </Form.Item>
          <Form.Item name="assessedValue" label="评估价">
            <InputNumber className="w-full" min={0} precision={2} />
          </Form.Item>
          <Form.Item name="bookValue" label="账面价">
            <InputNumber className="w-full" min={0} precision={2} />
          </Form.Item>
          <Form.Item name="actualAmount" label="实际金额">
            <InputNumber className="w-full" min={0} precision={2} />
          </Form.Item>
          <Form.Item name="reason" label="处置事由">
            <Input />
          </Form.Item>
          <Form.Item name="remark" label="备注" className="md:col-span-2">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item label="处置附件" className="md:col-span-2">
            <AttachmentField bizType="disposal" value={attachments} onChange={setAttachments} />
          </Form.Item>
        </div>
      </Form>
    </Modal>
  );
}

/** 执行处置：后端 execute 需要 actualAmount 与 counterparty。 */
function DisposalExecuteModal({
  order,
  onClose,
  onDone,
}: {
  order: DisposalOrderView | null;
  onClose: () => void;
  onDone: () => void;
}) {
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (order) {
      form.setFieldsValue({
        actualAmount: order.actualAmount ?? order.amountWan,
      });
    } else {
      form.resetFields();
    }
  }, [order, form]);

  const handleOk = async () => {
    if (!order) return;
    try {
      const values = (await form.validateFields()) as {
        actualAmount?: number;
        counterparty?: string;
      };
      setSubmitting(true);
      await api.post(`/disposals/${order.id}/execute`, {
        actualAmount: values.actualAmount,
        counterparty: values.counterparty,
      });
      message.success('已执行');
      onDone();
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : '执行失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={order != null}
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
