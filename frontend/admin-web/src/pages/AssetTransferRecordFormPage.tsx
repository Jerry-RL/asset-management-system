import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Button, Card, DatePicker, Empty, Form, Input, Modal, Select, Space, Spin, Tag, message } from 'antd';
import { ArrowLeftOutlined, SaveOutlined, SwapRightOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { AttachmentField, type AttachmentValue } from '@/components/AttachmentField';
import { usePerm } from '@/lib/perm';
import { loadCompanies, type CompanyOption } from '@/lib/org';
import { useRemoteOptions } from '@/lib/dict';
import { fromAttachmentValues, toAttachmentValues } from '@/lib/recordSheet';
import { assetOptionLabel } from '@/lib/assetLabel';
import { companyLabel } from '@/lib/ownershipTransfer';
import {
  assetTransferRecordApi,
  departmentLabel,
  userLabel,
  type AssetTransferRecordInput,
  type TransferRecordAssetOption,
} from '@/lib/assetTransferRecord';

const CODE = 'deed.transferRecord';

/** 资产远程搜索的防抖窗口：300ms 是「打字停顿」与「每键一次全表 LIKE」之间的折中。 */
const SEARCH_DEBOUNCE_MS = 300;
const ASSET_PAGE_SIZE = 50;

/** 提交给后端的本地时间格式（后端参数是 LocalDateTime，不带时区后缀）。 */
const STAMP_FORMAT = 'YYYY-MM-DDTHH:mm:ss';

/** 表单值（与后端入参的差异都在 `toPayload` 里收口） */
interface FormValues {
  companyId: number;
  fromDepartmentId?: number | null;
  toDepartmentId: number;
  toUserId: number;
  approvalDeadline?: Dayjs | null;
  reason?: string | null;
  remark?: string | null;
  assetIds?: number[];
  attachments?: AttachmentValue[];
}

/**
 * 资产调拨记录表单页（新建 / 编辑草稿共用）。
 *
 * 四处刻意的取舍：
 *
 * 1. **「所属公司」是过滤条件，不是被改的字段**：本模块只改责任部门 / 责任人，
 *    改公司归属是权属流转的职责。表单里把它当级联上级（部门 / 资产都由它决定），
 *    并在提交前不做任何「新公司」的输入；
 * 2. **前责任部门可不填**：一张单可以挂来自不同部门的资产（把几个人名下的资产集中
 *    交给一个部门托管），强制填一个值反而是错的；每个资产真实的原部门由明细快照承载，
 *    所以这里给它写了说明文字；
 * 3. **「审批截止时间」带说明文字**：本期没有审批环节，这一栏只是业务留痕。
 *    不说清楚会让人以为「填了就会有人来审批」，然后一直等；
 * 4. **换所属公司时先确认再清空已选资产与部门**：候选集变了，之前选的资产 / 部门
 *    在新公司下已经不存在 —— 留着不放会在提交时才报 400，而静默清空会让人以为「白选了」。
 */
export function AssetTransferRecordFormPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const can = usePerm();
  const isEdit = Boolean(id);
  const recordId = isEdit ? Number(id) : null;

  const [form] = Form.useForm<FormValues>();
  const [companies, setCompanies] = useState<CompanyOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [assetOptions, setAssetOptions] = useState<TransferRecordAssetOption[]>([]);
  const [assetLoading, setAssetLoading] = useState(false);

  const companyId = Form.useWatch('companyId', form);
  const toDepartmentId = Form.useWatch('toDepartmentId', form);
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

  /**
   * 部门下拉：按所属公司联动（与资产表单里「责任部门」的取数口径完全一致 ——
   * 两个界面必须按同一家公司过滤，否则同一批部门在一个界面选得到、在另一个界面选不到）。
   */
  const departmentOptions = useRemoteOptions(
    companyId ? `/org/departments?companyId=${companyId}` : null,
  );

  /** 新责任人下拉：按新责任部门联动（后端会校验责任人与部门的一致性）。 */
  const userOptions = useRemoteOptions(
    toDepartmentId ? `/system/users?departmentId=${toDepartmentId}&page=1&pageSize=200` : null,
  );

  // ---------------------------------------------------------------
  // 资产远程搜索
  // ---------------------------------------------------------------

  /**
   * 拉候选资产。`companyId` 必填 —— 后端在没有公司时会 400，前端提前拦住并给出提示。
   *
   * 候选口径是「所属公司 = 所选公司」，与资产台账上的所属公司是同一个字段。
   */
  const fetchAssets = useCallback(
    async (keywordText: string) => {
      if (!companyId) {
        setAssetOptions([]);
        return;
      }
      setAssetLoading(true);
      try {
        const data = await assetTransferRecordApi.assetOptions({
          companyId,
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
    [companyId],
  );

  // 所属公司变化：候选集整体变了，必须重新拉一次（只靠搜索框是拉不到的）
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
   * 上一次的所属公司。
   *
   * 用 ref 记旧值而不是在 handler 里读 `form.getFieldValue`：`onValuesChange` 触发时
   * 表单值**已经**是新值，拿它跟 `changed` 里的新值比永远是相等，那个判断永远不成立。
   */
  const companyRef = useRef<number | null>(null);

  /**
   * 换所属公司：候选集变了，已选资产 / 部门 / 责任人都可能已不合法。
   *
   * 先确认再清空，而不是静默清空或静默保留：静默清空会让人以为白选了，
   * 静默保留则会在提交时以一句「资产不属于所选公司」告终。
   */
  const handleCompanyChange = useCallback(() => {
    const next = (form.getFieldValue('companyId') as number | undefined) ?? null;
    const previous = companyRef.current;
    companyRef.current = next;
    // 首次变化（新建表单刚选公司）没有「已选资产」可言，不必打扰
    if (previous === null || previous === next) return;

    const selected = (form.getFieldValue('assetIds') as number[] | undefined) ?? [];
    if (selected.length === 0) return;

    Modal.confirm({
      title: '需要重新选择资产',
      content: `所属公司已改变，已选的 ${selected.length} 个资产不再属于该公司。`,
      okText: '清空并重选',
      cancelText: '保留已选',
      onOk: () => form.setFieldValue('assetIds', []),
    });
  }, [form]);

  // ---------------------------------------------------------------
  // 回显（编辑）
  // ---------------------------------------------------------------

  useEffect(() => {
    if (!recordId) return;
    setLoading(true);
    void assetTransferRecordApi
      .detail(recordId)
      .then((data) => {
        form.setFieldsValue({
          companyId: data.companyId,
          fromDepartmentId: data.fromDepartmentId ?? null,
          toDepartmentId: data.toDepartmentId,
          toUserId: data.toUserId,
          approvalDeadline: data.approvalDeadline ? dayjs(data.approvalDeadline) : null,
          reason: data.reason ?? '',
          remark: data.remark ?? '',
          assetIds: data.assets?.map((asset) => asset.assetId) ?? [],
          // 后端回显的是 fileName / url，组件值要的是 name / url
          attachments: toAttachmentValues(data.attachments),
        });
        // 回显完成后把「上一次的公司」记下：否则用户改公司时会被当成首次变化而跳过确认
        companyRef.current = data.companyId ?? null;
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
        message.error(e instanceof Error ? e.message : '加载资产调拨记录失败');
        navigate('/asset-transfer-records');
      })
      .finally(() => setLoading(false));
  }, [recordId, form, navigate]);

  // ---------------------------------------------------------------
  // 提交
  // ---------------------------------------------------------------

  const toPayload = (values: FormValues): AssetTransferRecordInput => ({
    companyId: values.companyId,
    fromDepartmentId: values.fromDepartmentId ?? null,
    toDepartmentId: values.toDepartmentId,
    toUserId: values.toUserId,
    // 只传本地时间，不带时区后缀：后端参数是 LocalDateTime
    approvalDeadline: values.approvalDeadline
      ? values.approvalDeadline.format(STAMP_FORMAT)
      : null,
    reason: values.reason ?? null,
    remark: values.remark ?? null,
    assetIds: values.assetIds ?? [],
    // 写路径只提交 fileId 与顺序，name / url 由服务端回填
    attachments: fromAttachmentValues(values.attachments),
  });

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
        isEdit && recordId
          ? await assetTransferRecordApi.update(recordId, payload)
          : await assetTransferRecordApi.create(payload);
      message.success(isEdit ? '已保存' : '草稿已创建');
      if (goEffect) {
        // 新建后立刻生效：先二次确认，再调生效接口
        Modal.confirm({
          title: '确认立即生效？',
          content: `将把 ${payload.assetIds.length} 个资产的责任部门与责任人改写为「${departmentLabel(
            departmentOptions.options.find(
              (option) => Number(option.value) === payload.toDepartmentId,
            )?.label,
            payload.toDepartmentId,
          )} / ${userLabel(
            userOptions.options.find((option) => Number(option.value) === payload.toUserId)?.label,
            payload.toUserId,
          )}」。生效后不可撤销。`,
          okText: '确认生效',
          cancelText: '稍后再说',
          onOk: async () => {
            await assetTransferRecordApi.effect(saved.id);
            message.success('已生效');
            navigate('/asset-transfer-records');
          },
        });
        return;
      }
      navigate('/asset-transfer-records');
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
          description={isEdit ? '你没有编辑资产调拨记录的权限' : '你没有新建资产调拨记录的权限'}
        />
      </div>
    );
  }

  return (
    <div className="space-y-4 min-w-0 max-w-full">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold text-gray-900 flex items-center gap-2 m-0">
            <SwapRightOutlined className="text-[var(--ams-primary)]" />
            {isEdit ? `编辑资产调拨记录 #${recordId}` : '新建资产调拨记录'}
          </h1>
          <p className="text-sm text-gray-500 mt-1 mb-0">
            保存为草稿后可在列表中反复修改；生效会立即改写所选资产的责任部门与责任人
          </p>
        </div>
        <Space wrap size={[8, 8]}>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/asset-transfer-records')}>
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
            onValuesChange={(changed) => {
              // 换所属公司 -> 提示清空已选资产
              if ('companyId' in changed) {
                handleCompanyChange();
              }
            }}
          >
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-x-4">
              <Form.Item
                name="companyId"
                label="所属公司"
                rules={[{ required: true, message: '请选择所属公司' }]}
                extra="只改责任部门 / 责任人；资产的公司归属由「权属流转」负责"
              >
                <Select
                  showSearch
                  optionFilterProp="label"
                  placeholder="请选择"
                  options={companyOptions}
                />
              </Form.Item>

              <Form.Item
                name="fromDepartmentId"
                label="前责任部门"
                extra="可留空：多资产可能来自不同部门，实际原值以资产明细为准"
              >
                <Select
                  allowClear
                  showSearch
                  optionFilterProp="label"
                  disabled={!companyId}
                  placeholder={companyId ? '请选择' : '请先选择所属公司'}
                  loading={departmentOptions.loading}
                  options={departmentOptions.options}
                />
              </Form.Item>

              <Form.Item
                name="toDepartmentId"
                label="新责任部门"
                rules={[{ required: true, message: '请选择新责任部门' }]}
                extra="生效后写入每个资产的「责任部门」"
              >
                <Select
                  showSearch
                  optionFilterProp="label"
                  disabled={!companyId}
                  placeholder={companyId ? '请选择' : '请先选择所属公司'}
                  loading={departmentOptions.loading}
                  options={departmentOptions.options}
                />
              </Form.Item>

              <Form.Item
                name="toUserId"
                label="新责任人"
                rules={[{ required: true, message: '请选择新责任人' }]}
                extra="候选为「新责任部门」下的员工；生效后写入每个资产的「责任人」"
              >
                <Select
                  showSearch
                  optionFilterProp="label"
                  disabled={!toDepartmentId}
                  placeholder={toDepartmentId ? '请选择' : '请先选择新责任部门'}
                  loading={userOptions.loading}
                  options={userOptions.options}
                />
              </Form.Item>

              <Form.Item
                name="approvalDeadline"
                label="审批截止时间"
                extra="本期不做审批流转，此栏仅作业务留痕"
              >
                <DatePicker showTime format="YYYY-MM-DD HH:mm" className="w-full" />
              </Form.Item>
            </div>

            <Form.Item
              name="assetIds"
              label="资产选择"
              rules={[{ required: true, message: '请至少选择 1 个资产' }]}
              extra={
                companyId
                  ? `候选资产按「所属公司 = ${companyLabel(
                      companyOptions.find((company) => company.value === companyId)?.label,
                      companyId,
                    )}」过滤，支持按名称 / 编号搜索`
                  : '请先选择所属公司，再选择资产'
              }
            >
              <Select
                mode="multiple"
                allowClear
                showSearch
                // 必须 false：否则 antd 会在本地对 options 做过滤，与远程搜索结果叠加后
                // 表现为「搜到的项搜不到、没搜到的项一直在」
                filterOption={false}
                disabled={!companyId}
                placeholder={companyId ? '搜索并选择资产' : '请先选择所属公司'}
                loading={assetLoading}
                onSearch={handleAssetSearch}
                options={assetOptions.map((option) => ({
                  value: option.assetId,
                  label: assetOptionLabel(option),
                }))}
                // 已选项加角标提示数量：选项多时（几十个）需要一个概览
                maxTagCount="responsive"
                aria-label="资产选择"
                notFoundContent={
                  assetLoading ? (
                    <Spin size="small" />
                  ) : (
                    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无可选资产" />
                  )
                }
              />
            </Form.Item>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-x-4">
              <Form.Item name="reason" label="原因">
                <Input.TextArea
                  rows={3}
                  maxLength={500}
                  showCount
                  placeholder="请说明本次调拨的原因（如岗位调整、部门重组）"
                />
              </Form.Item>
              <Form.Item name="remark" label="备注">
                <Input.TextArea
                  rows={3}
                  maxLength={500}
                  showCount
                  placeholder="其他需要说明的信息（如交接安排）"
                />
              </Form.Item>
            </div>

            <Form.Item
              name="attachments"
              label="附件"
              extra="支持 pdf / 图片 / Office 文档，单个不超过 20MB"
            >
              <AttachmentField bizType="asset_transfer_record" maxMB={20} maxCount={10} />
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
