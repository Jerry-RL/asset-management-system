import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Button,
  Card,
  DatePicker,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Spin,
  Tag,
  message,
} from 'antd';
import { ArrowLeftOutlined, SaveOutlined, SwapOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { ActorField, type ActorValue } from '@/components/ActorField';
import { AttachmentField, type AttachmentValue } from '@/components/AttachmentField';
import { usePerm } from '@/lib/perm';
import { loadCompanies, type CompanyOption } from '@/lib/org';
import { TRANSFER_DIRECTION, TRANSFER_MODE, TRANSFER_SCOPE } from '@/lib/labels';
import { fromAttachmentValues, toAttachmentValues } from '@/lib/recordSheet';
import {
  assetOptionLabel,
  companyLabel,
  ownershipTransferApi,
  suggestDirection,
  type OwnershipTransferInput,
  type TransferAssetOption,
} from '@/lib/ownershipTransfer';

const CODE = 'deed.ownershipTransfer';

/** 资产远程搜索的防抖窗口：300ms 是「打字停顿」与「每键一次全表 LIKE」之间的折中。 */
const SEARCH_DEBOUNCE_MS = 300;
const ASSET_PAGE_SIZE = 50;

/** 提交给后端的本地时间格式（后端参数是 LocalDateTime，不带时区后缀）。 */
const STAMP_FORMAT = 'YYYY-MM-DDTHH:mm:ss';

/** 表单值（与后端入参的差异都在 `toPayload` 里收口） */
interface FormValues {
  direction: string;
  transferScope: string;
  fromCompanyId: number;
  toCompanyId: number;
  transferMode: string;
  applicant?: ActorValue | null;
  approvalDeadline?: Dayjs | null;
  amountWan?: number | null;
  reason?: string | null;
  assetIds?: number[];
  attachments?: AttachmentValue[];
}

/**
 * 权属流转表单页（新建 / 编辑草稿共用）。
 *
 * 三处刻意的取舍：
 *
 * 1. **「审批截止时间」带说明文字**：本期没有审批环节，这一栏只是业务留痕。
 *    不说清楚会让人以为「填了就会有人来审批」，然后一直等；
 * 2. **换原公司 / 换权属类型时先确认再清空已选资产**：候选集变了，之前选的资产
 *    在新条件下可能已经不属于这家公司 —— 留着不放会在提交时才报 400，
 *    而静默清空会让人以为「白选了」；
 * 3. **方向自动预填但可改**：与后端同一套公司树判定（同根为内部）；不一致时给出
 *    提示而不是禁止提交 —— 前端算错时不该把功能锁死，后端仍会给出明确报错。
 */
export function OwnershipTransferFormPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const can = usePerm();
  const isEdit = Boolean(id);
  const transferId = isEdit ? Number(id) : null;

  const [form] = Form.useForm<FormValues>();
  const [companies, setCompanies] = useState<CompanyOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [assetOptions, setAssetOptions] = useState<TransferAssetOption[]>([]);
  const [assetLoading, setAssetLoading] = useState(false);

  const fromCompanyId = Form.useWatch('fromCompanyId', form);
  const toCompanyId = Form.useWatch('toCompanyId', form);
  const transferScope = Form.useWatch('transferScope', form);
  const direction = Form.useWatch('direction', form);
  const assetIds = Form.useWatch('assetIds', form);

  const requiredAction = isEdit ? 'update' : 'create';
  const allowed = can(CODE, requiredAction);

  useEffect(() => {
    void loadCompanies().then(setCompanies);
  }, []);

  const companyOptions = useMemo(
    () => companies.map((company) => ({ value: company.id, label: company.name })),
    [companies],
  );

  const suggested = suggestDirection(companies, fromCompanyId, toCompanyId);

  // ---------------------------------------------------------------
  // 资产远程搜索
  // ---------------------------------------------------------------

  /**
   * 拉候选资产。`companyId` 必填 —— 后端在没有公司时会 400，前端提前拦住并给出提示。
   *
   * `transferScope` 决定按产权公司还是经营公司过滤（与后端同一规则）：
   * 选「经营权」时列出的应该是「经营公司 = 原公司」的资产，而不是产权公司。
   */
  const fetchAssets = useCallback(
    async (keywordText: string) => {
      if (!fromCompanyId) {
        setAssetOptions([]);
        return;
      }
      setAssetLoading(true);
      try {
        const data = await ownershipTransferApi.assetOptions({
          companyId: fromCompanyId,
          transferScope,
          keyword: keywordText || undefined,
          page: 1,
          pageSize: ASSET_PAGE_SIZE,
        });
        setAssetOptions(data.list);
      } catch (e) {
        setAssetOptions([]);
        message.error(e instanceof Error ? e.message : '加载资产失败');
      } finally {
        setAssetLoading(false);
      }
    },
    [fromCompanyId, transferScope],
  );

  // 公司 / 权属类型变化：候选集整体变了，必须重新拉一次（只靠搜索框是拉不到的）
  useEffect(() => {
    void fetchAssets('');
  }, [fetchAssets]);

  const debounceRef = useRef<number | undefined>(undefined);
  const handleAssetSearch = (keywordText: string) => {
    window.clearTimeout(debounceRef.current);
    debounceRef.current = window.setTimeout(() => {
      void fetchAssets(keywordText.trim());
    }, SEARCH_DEBOUNCE_MS);
  };

  useEffect(
    () => () => {
      window.clearTimeout(debounceRef.current);
    },
    [],
  );

  /**
   * 上一次的「候选集身份」（原公司 + 权属类型）。
   *
   * 用 ref 记旧值而不是在 handler 里读 `form.getFieldValue`：`onValuesChange` 触发时
   * 表单值**已经**是新值，拿它跟 `changed` 里的新值比永远是相等，那个判断永远不成立。
   */
  const scopeKeyRef = useRef<string | null>(null);

  /**
   * 换原公司 / 换权属类型：候选集变了，已选资产可能已不再合法。
   *
   * 先确认再清空，而不是静默清空或静默保留：静默清空会让人以为白选了，
   * 静默保留则会在提交时以一句「资产不属于所选原公司」告终。
   */
  const handleScopeKeyChange = useCallback(() => {
    const next = `${form.getFieldValue('fromCompanyId') ?? ''}|${
      form.getFieldValue('transferScope') ?? ''
    }`;
    const previous = scopeKeyRef.current;
    scopeKeyRef.current = next;
    // 首次变化（新建表单刚选公司）没有「已选资产」可言，不必打扰
    if (previous === null || previous === next) return;

    const selected = (form.getFieldValue('assetIds') as number[] | undefined) ?? [];
    if (selected.length === 0) return;

    Modal.confirm({
      title: '需要重新选择资产',
      content: `原公司或权属类型已改变，已选的 ${selected.length} 个资产可能不再属于该范围。`,
      okText: '清空并重选',
      cancelText: '保留已选',
      onOk: () => form.setFieldValue('assetIds', []),
    });
  }, [form]);

  // ---------------------------------------------------------------
  // 回显（编辑）
  // ---------------------------------------------------------------

  useEffect(() => {
    if (!transferId) return;
    setLoading(true);
    void ownershipTransferApi
      .detail(transferId)
      .then((data) => {
        form.setFieldsValue({
          direction: data.direction,
          transferScope: data.transferScope,
          fromCompanyId: data.fromCompanyId,
          toCompanyId: data.toCompanyId,
          transferMode: data.transferMode,
          applicant: data.applicantUserId
            ? { userId: data.applicantUserId, name: data.applicantName }
            : { name: data.applicantName },
          approvalDeadline: data.approvalDeadline ? dayjs(data.approvalDeadline) : null,
          amountWan:
            data.amountWan === null || data.amountWan === undefined ? null : Number(data.amountWan),
          reason: data.reason ?? '',
          assetIds: data.assets?.map((asset) => asset.assetId) ?? [],
          // 后端回显的是 fileName / url，组件值要的是 name / url
          attachments: toAttachmentValues(data.attachments),
        });
        // 回显完成后把「候选集身份」记下：否则用户改原公司时会被当成首次变化而跳过确认
        scopeKeyRef.current = `${data.fromCompanyId ?? ''}|${data.transferScope ?? ''}`;
        // 已选资产在详情里有名字（项目 / 分区 / 楼层），先并进候选集，
        // 否则 Select 会因为 options 里找不到 id 而只显示一个裸数字
        setAssetOptions(
          (data.assets ?? []).map((asset) => ({
            assetId: asset.assetId,
            assetNo: asset.assetNo,
            name: asset.assetName,
            projectName: asset.projectName,
            zoneName: asset.zoneName,
            floorNo: asset.floorNo,
          })),
        );
      })
      .catch((e) => {
        message.error(e instanceof Error ? e.message : '加载权属流转单失败');
        navigate('/ownership-transfers');
      })
      .finally(() => setLoading(false));
  }, [transferId, form, navigate]);

  // ---------------------------------------------------------------
  // 提交
  // ---------------------------------------------------------------

  const toPayload = (values: FormValues): OwnershipTransferInput => {
    const applicant = values.applicant ?? null;
    return {
      direction: values.direction,
      transferScope: values.transferScope,
      fromCompanyId: values.fromCompanyId,
      toCompanyId: values.toCompanyId,
      transferMode: values.transferMode,
      // 内员带 userId（后端用它回查姓名覆盖快照）；外部人员只带姓名
      applicantUserId: applicant?.userId ?? null,
      applicantName: applicant?.name?.trim() ?? '',
      // 只传本地时间，不带时区后缀：后端参数是 LocalDateTime
      approvalDeadline: values.approvalDeadline
        ? values.approvalDeadline.format(STAMP_FORMAT)
        : null,
      amountWan: values.amountWan ?? null,
      reason: values.reason ?? null,
      assetIds: values.assetIds ?? [],
      // 写路径只提交 fileId 与顺序，name / url 由服务端回填
      attachments: fromAttachmentValues(values.attachments),
    };
  };

  const handleSubmit = async (goEffect: boolean) => {
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return; // 校验失败时 antd 已把错误标在字段上
    }
    setSaving(true);
    try {
      const payload = toPayload(values);
      const saved =
        isEdit && transferId
          ? await ownershipTransferApi.update(transferId, payload)
          : await ownershipTransferApi.create(payload);
      message.success(isEdit ? '已保存' : '草稿已创建');
      if (goEffect) {
        // 新建后立刻生效：先二次确认，再调生效接口
        Modal.confirm({
          title: '确认立即生效？',
          content: `将把 ${payload.assetIds.length} 个资产的「${
            TRANSFER_SCOPE[payload.transferScope] ?? payload.transferScope
          }」改写为「${companyLabel(
            companyOptions.find((company) => company.value === payload.toCompanyId)?.label,
            payload.toCompanyId,
          )}」。生效后不可撤销。`,
          okText: '确认生效',
          cancelText: '稍后再说',
          onOk: async () => {
            await ownershipTransferApi.effect(saved.id);
            message.success('已生效');
            navigate('/ownership-transfers');
          },
        });
        return;
      }
      navigate('/ownership-transfers');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  if (!allowed) {
    return (
      <div className="p-6">
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={isEdit ? '你没有编辑权属流转单的权限' : '你没有新建权属流转单的权限'}
        />
      </div>
    );
  }

  return (
    <div className="space-y-4 min-w-0 max-w-full">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold text-gray-900 flex items-center gap-2 m-0">
            <SwapOutlined className="text-[var(--ams-primary)]" />
            {isEdit ? `编辑权属流转单 #${transferId}` : '新建权属流转单'}
          </h1>
          <p className="text-sm text-gray-500 mt-1 mb-0">
            保存为草稿后可在列表中反复修改；生效会立即改写所选资产的产权 / 经营公司
          </p>
        </div>
        <Space wrap size={[8, 8]}>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/ownership-transfers')}>
            返回列表
          </Button>
          <Button icon={<SaveOutlined />} loading={saving} onClick={() => void handleSubmit(false)}>
            保存草稿
          </Button>
          <Button type="primary" loading={saving} onClick={() => void handleSubmit(true)}>
            保存并生效
          </Button>
        </Space>
      </div>

      <Spin spinning={loading}>
        <Card className="rounded-xl" styles={{ body: { padding: 20 } }}>
          <Form
            form={form}
            layout="vertical"
            initialValues={{ transferScope: 'property', direction: 'internal' }}
            onValuesChange={(changed) => {
              // 换原公司 / 换权属类型 -> 提示清空已选资产
              if ('fromCompanyId' in changed || 'transferScope' in changed) {
                handleScopeKeyChange();
              }
            }}
          >
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-x-4">
              <Form.Item
                name="fromCompanyId"
                label="原产权公司"
                rules={[{ required: true, message: '请选择原产权公司' }]}
              >
                <Select
                  showSearch
                  optionFilterProp="label"
                  placeholder="请选择"
                  options={companyOptions}
                />
              </Form.Item>

              <Form.Item
                name="toCompanyId"
                label="新公司"
                rules={[
                  { required: true, message: '请选择新公司' },
                  ({ getFieldValue }) => ({
                    validator: (_, value: number | undefined) =>
                      value === undefined || value !== getFieldValue('fromCompanyId')
                        ? Promise.resolve()
                        : Promise.reject(new Error('新公司不能与原公司相同')),
                  }),
                ]}
              >
                <Select
                  showSearch
                  optionFilterProp="label"
                  placeholder="请选择"
                  options={companyOptions}
                />
              </Form.Item>

              <Form.Item
                name="direction"
                label="流转方向"
                rules={[{ required: true, message: '请选择流转方向' }]}
                extra={
                  suggested && direction && suggested !== direction ? (
                    <span className="text-amber-600">
                      按公司树应为「{TRANSFER_DIRECTION[suggested]}」，请核对
                    </span>
                  ) : (
                    '内部 = 原公司与新公司属同一集团'
                  )
                }
              >
                <Select
                  options={Object.entries(TRANSFER_DIRECTION).map(([value, label]) => ({
                    value,
                    label,
                  }))}
                />
              </Form.Item>

              <Form.Item
                name="transferScope"
                label="权属类型"
                rules={[{ required: true, message: '请选择权属类型' }]}
                extra="决定改写资产的哪个公司字段：产权公司 / 经营公司 / 两者"
              >
                <Select
                  options={Object.entries(TRANSFER_SCOPE).map(([value, label]) => ({
                    value,
                    label,
                  }))}
                />
              </Form.Item>

              <Form.Item
                name="transferMode"
                label="流转类型"
                rules={[{ required: true, message: '请选择流转类型' }]}
              >
                <Select
                  options={Object.entries(TRANSFER_MODE).map(([value, label]) => ({
                    value,
                    label,
                  }))}
                />
              </Form.Item>

              <Form.Item
                name="applicant"
                label="变更申请人"
                rules={[
                  {
                    validator: (_, value: ActorValue | null | undefined) =>
                      value && value.name && value.name.trim()
                        ? Promise.resolve()
                        : Promise.reject(new Error('请选择内部员工或填写外部人员姓名')),
                  },
                ]}
              >
                <ActorField
                  companyId={fromCompanyId ?? null}
                  placeholder="选择内部员工或输入外部人员姓名"
                />
              </Form.Item>

              <Form.Item
                name="approvalDeadline"
                label="审批截止时间"
                extra="本期不做审批流转，此栏仅作业务留痕"
              >
                <DatePicker showTime format="YYYY-MM-DD HH:mm" className="w-full" />
              </Form.Item>

              <Form.Item name="amountWan" label="金额(万元)">
                {/* precision=2：输入框不接受 3 位小数（后端会 400，不该在前端就能打出来） */}
                <InputNumber className="w-full" min={0} precision={2} placeholder="最多 2 位小数" />
              </Form.Item>
            </div>

            <Form.Item
              name="assetIds"
              label="资产列表"
              rules={[{ required: true, message: '请至少选择 1 个资产' }]}
              extra={
                fromCompanyId
                  ? `候选资产按「${
                      transferScope === 'operating' ? '经营公司' : '产权公司'
                    } = ${companyLabel(
                      companyOptions.find((company) => company.value === fromCompanyId)?.label,
                      fromCompanyId,
                    )}」过滤，支持按名称 / 编号搜索`
                  : '请先选择原产权公司，再选择资产'
              }
            >
              <Select
                mode="multiple"
                allowClear
                showSearch
                // 必须 false：否则 antd 会在本地对 options 做过滤，与远程搜索结果叠加后
                // 表现为「搜到的项搜不到、没搜到的项一直在」
                filterOption={false}
                disabled={!fromCompanyId}
                placeholder={fromCompanyId ? '搜索并选择资产' : '请先选择原产权公司'}
                loading={assetLoading}
                onSearch={handleAssetSearch}
                options={assetOptions.map((option) => ({
                  value: option.assetId,
                  label: assetOptionLabel(option),
                }))}
                // 已选项加角标提示数量：选项多时（几十个）需要一个概览
                maxTagCount="responsive"
                aria-label="资产列表"
                notFoundContent={
                  assetLoading ? (
                    <Spin size="small" />
                  ) : (
                    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无可选资产" />
                  )
                }
              />
            </Form.Item>

            <Form.Item name="reason" label="流转原因">
              <Input.TextArea
                rows={3}
                maxLength={1000}
                showCount
                placeholder="请说明本次流转的原因"
              />
            </Form.Item>

            <Form.Item
              name="attachments"
              label="附件"
              extra="支持 pdf / 图片 / Office 文档，单个不超过 20MB"
            >
              <AttachmentField bizType="ownership_transfer" maxMB={20} maxCount={10} />
            </Form.Item>
          </Form>
        </Card>
      </Spin>

      {/* 已选资产数的实时回显：表单很长，滚动到按钮时看不到上面选了什么 */}
      <div className="text-xs text-gray-500">
        已选资产 <Tag className="m-0">{(assetIds ?? []).length}</Tag>
      </div>
    </div>
  );
}
