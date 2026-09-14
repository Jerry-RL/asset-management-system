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
import { ArrowLeftOutlined, IdcardOutlined, SaveOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { AttachmentField, type AttachmentValue } from '@/components/AttachmentField';
import { usePerm } from '@/lib/perm';
import { loadCompanies, type CompanyOption } from '@/lib/org';
import { fromAttachmentValues, toAttachmentValues } from '@/lib/recordSheet';
import { companyLabel } from '@/lib/ownershipTransfer';
import {
  mortgageRecordApi,
  mortgageTargetLabel,
  toAmount,
  toInterestRate,
  type MortgageRecordInput,
  type MortgageTargetOption,
  type MortgageTargetType,
} from '@/lib/mortgageRecord';

const CODE = 'deed.mortgage';

/** 标的远程搜索的防抖窗口：300ms 是「打字停顿」与「每键一次全表 LIKE」之间的折中。 */
const SEARCH_DEBOUNCE_MS = 300;
const TARGET_PAGE_SIZE = 50;

/** 提交给后端的日期格式（后端参数是 LocalDate）。 */
const DATE_FORMAT = 'YYYY-MM-DD';

const TARGET_TYPES: { value: MortgageTargetType; label: string }[] = [
  { value: 'project', label: '项目' },
  { value: 'zone', label: '分区' },
  { value: 'asset', label: '资产' },
];

/** 表单值（与后端入参的差异都在 `toPayload` 里收口） */
interface FormValues {
  companyId: number;
  targetType: MortgageTargetType;
  targetId: number;
  mortgagee: string;
  amount: number;
  interestRate?: number | null;
  bank?: string | null;
  repaymentDate?: Dayjs | null;
  startDate: Dayjs;
  termMonths: number;
  contractNo?: string | null;
  attachments?: AttachmentValue[];
}

/**
 * 抵押记录表单页（新建 / 编辑草稿共用）。
 *
 * 五处刻意的取舍：
 *
 * 1. **「所属公司」是过滤条件，不是被改的字段**：本模块只登记抵押，不改资产的归属。
 *    它同时是标的候选的上级条件（后端校验「标的必须属于所选公司」）。
 * 2. **「项目类型」变了必须清空已选标的**：`project.id` / `project_zone.id` / `asset.id`
 *    是三个独立序列、都从 1 开始 —— 不清空的话「项目 #5」会在切换成资产后静默变成
 *    「资产 #5」，用户看到的是另一个东西，而后端只会说「资产不属于所选公司」。
 * 3. **没有「抵押到期日」输入**：到期日由 起始时间 + 期限（月）推导并由服务端落库。
 *    让用户另填一个日期就会出现两个互相矛盾的数字，而到期预警读的是推导出来的那个。
 *    表单用一行只读回显把推导结果显示出来，避免「填完不知道到期是哪天」。
 * 4. **金额与利率交给 `toAmount` / `toInterestRate` 收敛**：后端 `BigDecimal.scale()`
 *    是按小数位数校验的，JS 浮点会产出 `0.30000000000000004` 这种 scale 17 的值。
 * 5. **换标的 / 换公司先确认再清空**，理由同权属流转与调拨记录的表单。
 */
export function MortgageFormPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const can = usePerm();
  const isEdit = Boolean(id);
  const recordId = isEdit ? Number(id) : null;

  const [form] = Form.useForm<FormValues>();
  const [companies, setCompanies] = useState<CompanyOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [targetOptions, setTargetOptions] = useState<MortgageTargetOption[]>([]);
  const [targetLoading, setTargetLoading] = useState(false);

  const companyId = Form.useWatch('companyId', form);
  const targetType = Form.useWatch('targetType', form);
  const startDate = Form.useWatch('startDate', form);
  const termMonths = Form.useWatch('termMonths', form);

  const requiredAction = isEdit ? 'update' : 'create';
  const allowed = can(CODE, requiredAction);

  useEffect(() => {
    void loadCompanies().then(setCompanies);
  }, []);

  const companyOptions = useMemo(
    () => companies.map((company) => ({ value: company.id, label: company.name })),
    [companies],
  );

  /** 到期日回显：与后端 `startDate.plusMonths(termMonths)` 同一口径。 */
  const derivedEndDate = useMemo(
    () => (startDate && termMonths ? startDate.add(termMonths, 'month').format(DATE_FORMAT) : null),
    [startDate, termMonths],
  );

  // ---------------------------------------------------------------
  // 标的远程搜索
  // ---------------------------------------------------------------

  /**
   * 拉候选标的。`targetType` 与 `companyId` 都必填 ——
   * 后端在没有公司时会 400（分区更是要靠项目推导公司），前端提前拦住并给出提示。
   */
  const fetchTargets = useCallback(
    async (keywordText: string) => {
      if (!companyId || !targetType) {
        setTargetOptions([]);
        return;
      }
      setTargetLoading(true);
      try {
        const data = await mortgageRecordApi.targetOptions({
          targetType,
          companyId,
          keyword: keywordText || undefined,
          page: 1,
          pageSize: TARGET_PAGE_SIZE,
        });
        setTargetOptions(data.list);
      } catch (e) {
        setTargetOptions([]);
        message.error(e instanceof Error ? e.message : '加载抵押标的失败');
      } finally {
        setTargetLoading(false);
      }
    },
    [companyId, targetType],
  );

  // 公司或类型变化：候选集整体变了，必须重新拉一次（只靠搜索框是拉不到的）
  useEffect(() => {
    void fetchTargets('');
  }, [fetchTargets]);

  const debounceRef = useRef<number | undefined>(undefined);
  const handleTargetSearch = (keywordText: string) => {
    window.clearTimeout(debounceRef.current);
    debounceRef.current = window.setTimeout(() => {
      void fetchTargets(keywordText.trim());
    }, SEARCH_DEBOUNCE_MS);
  };

  useEffect(
    () => () => {
      window.clearTimeout(debounceRef.current);
    },
    [],
  );

  /**
   * 上一次的所属公司与项目类型。
   *
   * 用 ref 记旧值而不是在 handler 里读 `form.getFieldValue`：`onValuesChange` 触发时
   * 表单值**已经**是新值，拿它跟 `changed` 里的新值比永远是相等，那个判断永远不成立。
   */
  const companyRef = useRef<number | null>(null);
  const targetTypeRef = useRef<string | null>(null);

  const clearTargetWithConfirm = useCallback(
    (title: string, content: string) => {
      const selected = form.getFieldValue('targetId') as number | undefined;
      if (selected === null || selected === undefined) return;
      Modal.confirm({
        title,
        content,
        okText: '清空并重选',
        cancelText: '保留已选',
        // 保留已选是刻意允许的：标的理论上可能恰好仍然合法（比如分区被移到同一家公司的
        // 另一个项目）。真正的把关在提交与生效时的服务端校验，这里只负责让用户知情。
        onOk: () => form.setFieldValue('targetId', undefined),
      });
    },
    [form],
  );

  /**
   * 换所属公司：候选集变了，已选标的可能已不属于该公司。
   *
   * 先确认再清空，而不是静默清空或静默保留：静默清空会让人以为白选了，
   * 静默保留则会在提交时以一句「标的 不属于所选公司」告终。
   */
  const handleCompanyChange = useCallback(() => {
    const next = (form.getFieldValue('companyId') as number | undefined) ?? null;
    const previous = companyRef.current;
    companyRef.current = next;
    // 首次变化（新建表单刚选公司）没有「已选标的」可言，不必打扰
    if (previous === null || previous === next) return;
    clearTargetWithConfirm('需要重新选择标的', '所属公司已改变，已选标的可能不再属于该公司。');
  }, [form, clearTargetWithConfirm]);

  /**
   * 换项目类型：三种标的的 id 空间互相独立且都从 1 开始。
   *
   * 不清空的话「项目 #5」会在切换成资产后静默变成「资产 #5」—— 用户看到的是完全
   * 另一个东西，而后端只会回一句「资产不属于所选公司」，指向的是错觉。
   */
  const handleTargetTypeChange = useCallback(() => {
    const next = (form.getFieldValue('targetType') as string | undefined) ?? null;
    const previous = targetTypeRef.current;
    targetTypeRef.current = next;
    if (previous === null || previous === next) return;
    clearTargetWithConfirm(
      '需要重新选择标的',
      '项目 / 分区 / 资产是三类不同的对象，已选标的不会再指向原来那一个。',
    );
  }, [form, clearTargetWithConfirm]);

  // ---------------------------------------------------------------
  // 回显（编辑）
  // ---------------------------------------------------------------

  useEffect(() => {
    if (!recordId) return;
    setLoading(true);
    void mortgageRecordApi
      .detail(recordId)
      .then((data) => {
        /**
         * `Partial<FormValues>` 而不是 `FormValues`：回显时几处「后端可能没有值」的字段
         * （金额 / 起始时间 / 期限）在类型上是必填，用 `Partial` 才能诚实地表达
         * 「这里可能填不进去一个值」，而不是塞 `undefined as never` 绕过类型。
         */
        const echo: Partial<FormValues> = {
          companyId: data.companyId ?? undefined,
          targetType: data.targetType as MortgageTargetType,
          targetId: data.targetId,
          mortgagee: data.mortgagee ?? '',
          amount: data.amount === null || data.amount === undefined ? undefined : Number(data.amount),
          interestRate:
            data.interestRate === null || data.interestRate === undefined
              ? null
              : Number(data.interestRate),
          bank: data.bank ?? '',
          repaymentDate: data.repaymentDate ? dayjs(data.repaymentDate) : null,
          startDate: data.startDate ? dayjs(data.startDate) : undefined,
          termMonths: data.termMonths ?? undefined,
          contractNo: data.contractNo ?? '',
          // 后端回显的是 fileName / url，组件值要的是 name / url
          attachments: toAttachmentValues(data.attachments),
        };
        form.setFieldsValue(echo);
        // 回显完成后把「上一次的公司 / 类型」记下：否则用户改它们时会被当成首次变化而跳过确认
        companyRef.current = data.companyId ?? null;
        targetTypeRef.current = data.targetType ?? null;
        // 已选标的在详情里有名字，先并进候选集，
        // 否则 Select 会因为 options 里找不到 id 而只显示一个裸数字
        setTargetOptions([
          {
            targetId: data.targetId,
            name: data.targetName,
            projectName: data.projectName,
            zoneName: data.zoneName,
            floorNo: data.floorNo,
            assetNo: data.assetNo,
          },
        ]);
      })
      .catch((e) => {
        message.error(e instanceof Error ? e.message : '加载抵押记录失败');
        navigate('/mortgages');
      })
      .finally(() => setLoading(false));
  }, [recordId, form, navigate]);

  // ---------------------------------------------------------------
  // 提交
  // ---------------------------------------------------------------

  const toPayload = (values: FormValues): MortgageRecordInput => ({
    companyId: values.companyId,
    targetType: values.targetType,
    targetId: values.targetId,
    mortgagee: values.mortgagee.trim(),
    amount: toAmount(values.amount),
    interestRate: toInterestRate(values.interestRate ?? null),
    bank: values.bank?.trim() || null,
    repaymentDate: values.repaymentDate ? values.repaymentDate.format(DATE_FORMAT) : null,
    startDate: values.startDate.format(DATE_FORMAT),
    termMonths: values.termMonths,
    contractNo: values.contractNo?.trim() || null,
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
          ? await mortgageRecordApi.update(recordId, payload)
          : await mortgageRecordApi.create(payload);
      message.success(isEdit ? '已保存' : '草稿已创建');
      if (goEffect) {
        // 新建后立刻生效：先二次确认，再调生效接口。
        // 名称取自当前候选集（此时一定已经含被选中的那一项），避免只显示一个裸数字。
        const selected = targetOptions.find((option) => option.targetId === payload.targetId);
        Modal.confirm({
          title: '确认立即生效？',
          content: `标的「${mortgageTargetLabel(payload.targetType, {
            targetId: payload.targetId,
            ...selected,
          })}」将进入在押状态，其处置 / 流转 / 调拨都会被拦下；生效后不可撤销。`,
          okText: '确认生效',
          cancelText: '稍后再说',
          onOk: async () => {
            await mortgageRecordApi.effect(saved.id);
            message.success('已生效');
            navigate('/mortgages');
          },
        });
        return;
      }
      navigate('/mortgages');
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
          description={isEdit ? '你没有编辑抵押记录的权限' : '你没有新建抵押记录的权限'}
        />
      </div>
    );
  }

  return (
    <div className="space-y-4 min-w-0 max-w-full">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold text-gray-900 flex items-center gap-2 m-0">
            <IdcardOutlined className="text-[var(--ams-primary)]" />
            {isEdit ? `编辑抵押记录 #${recordId}` : '新建抵押记录'}
          </h1>
          <p className="text-sm text-gray-500 mt-1 mb-0">
            保存为草稿后可在列表中反复修改；生效后标的进入在押状态并被处置 / 流转 / 调拨的前置校验拦截
          </p>
        </div>
        <Space wrap size={[8, 8]}>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/mortgages')}>
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
              // 两个字段都可能改变候选集，各自确认清空
              if ('companyId' in changed) {
                handleCompanyChange();
              }
              if ('targetType' in changed) {
                handleTargetTypeChange();
              }
            }}
          >
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-x-4">
              <Form.Item
                name="companyId"
                label="所属公司"
                rules={[{ required: true, message: '请选择所属公司' }]}
                extra="标的必须归属该公司；本表单不修改资产的归属"
              >
                <Select
                  showSearch
                  optionFilterProp="label"
                  placeholder="请选择"
                  options={companyOptions}
                />
              </Form.Item>

              <Form.Item
                name="targetType"
                label="项目类型"
                rules={[{ required: true, message: '请选择项目类型' }]}
                extra="选择本次抵押的对象类型：项目、项目分区或单个资产"
              >
                <Select placeholder="请选择" options={TARGET_TYPES} />
              </Form.Item>

              <Form.Item
                name="targetId"
                label="抵押标的"
                rules={[{ required: true, message: '请选择抵押标的' }]}
                extra={
                  companyId && targetType
                    ? `候选按「${TARGET_TYPES.find((item) => item.value === targetType)?.label} + 所属公司 = ${companyLabel(
                        companyOptions.find((company) => company.value === companyId)?.label,
                        companyId,
                      )}」过滤，支持搜索`
                    : '请先选择所属公司与项目类型'
                }
              >
                <Select
                  allowClear
                  showSearch
                  // 必须 false：否则 antd 会在本地对 options 做过滤，与远程搜索结果叠加后
                  // 表现为「搜到的项搜不到、没搜到的项一直在」
                  filterOption={false}
                  disabled={!companyId || !targetType}
                  placeholder={!companyId || !targetType ? '请先选择所属公司与项目类型' : '搜索并选择标的'}
                  loading={targetLoading}
                  onSearch={handleTargetSearch}
                  options={targetOptions.map((option) => ({
                    value: option.targetId,
                    label: mortgageTargetLabel(targetType, option),
                  }))}
                  aria-label="抵押标的"
                  notFoundContent={
                    targetLoading ? (
                      <Spin size="small" />
                    ) : (
                      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无可选标的" />
                    )
                  }
                />
              </Form.Item>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-x-4">
              <Form.Item
                name="mortgagee"
                label="抵押公司/人"
                rules={[{ required: true, message: '请填写抵押公司/人' }]}
              >
                <Input maxLength={200} placeholder="如：江苏银行淮安分行" />
              </Form.Item>

              <Form.Item
                name="amount"
                label="抵押金额"
                rules={[{ required: true, message: '请填写抵押金额' }]}
                extra="最多 2 位小数"
              >
                <InputNumber<number>
                  className="!w-full"
                  min={0}
                  precision={2}
                  step={10000}
                  placeholder="如：500000"
                  // 千分位不是装饰：抵押金额动辄六到八位数，不分组时「5000000」与「500000」很容易看错一位
                  formatter={(value) =>
                    value === undefined || value === null
                      ? ''
                      : `${value}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
                  }
                  parser={(value) => (value ? Number(value.replace(/,/g, '')) : 0)}
                />
              </Form.Item>

              <Form.Item
                name="interestRate"
                label="利率"
                rules={[
                  {
                    validator: (_, value) => {
                      if (value === null || value === undefined) return Promise.resolve();
                      if (Number(value) < 0 || Number(value) > 100) {
                        return Promise.reject(new Error('利率应在 0 ~ 100 之间（百分数）'));
                      }
                      return Promise.resolve();
                    },
                  },
                ]}
                extra="按百分数填写（如 4.35 表示 4.35%），最多 4 位小数；可留空"
              >
                <InputNumber className="!w-full" min={0} max={100} precision={4} addonAfter="%" />
              </Form.Item>

              <Form.Item name="bank" label="抵押银行">
                <Input maxLength={200} placeholder="如：江苏银行淮安分行" />
              </Form.Item>

              <Form.Item
                name="startDate"
                label="抵押起始时间"
                rules={[{ required: true, message: '请选择抵押起始时间' }]}
              >
                <DatePicker className="w-full" format="YYYY-MM-DD" />
              </Form.Item>

              <Form.Item
                name="termMonths"
                label="抵押期限（月）"
                rules={[{ required: true, message: '请填写抵押期限' }]}
                extra={
                  derivedEndDate
                    ? `抵押到期日：${derivedEndDate}（由起始时间 + 期限推导）`
                    : '到期日由起始时间 + 期限推导，并作为到期预警的依据'
                }
              >
                <InputNumber className="!w-full" min={1} max={1200} precision={0} addonAfter="月" />
              </Form.Item>

              <Form.Item
                name="repaymentDate"
                label="还款日"
                extra="业务上的到期还款日；与上面推导出的抵押到期日可以不同"
              >
                <DatePicker className="w-full" format="YYYY-MM-DD" />
              </Form.Item>

              <Form.Item
                name="contractNo"
                label="抵押合同编号"
                extra="填写后全局唯一（未删除的记录之间）"
              >
                <Input maxLength={100} placeholder="如：DY-2026-0001" />
              </Form.Item>
            </div>

            <Form.Item
              name="attachments"
              label="附件"
              extra="支持 pdf / 图片 / Office 文档，单个不超过 20MB"
            >
              <AttachmentField bizType="mortgage" maxMB={20} maxCount={10} />
            </Form.Item>
          </Form>
        </Card>
      </Spin>

      {/* 标的与期限的实时回显：表单很长，滚动到按钮时看不到上面选了什么 */}
      <div className="text-xs text-gray-500">
        已选标的 <Tag className="m-0">{targetType ? tagName(targetType) : '-'}</Tag>
        {termMonths ? (
          <>
            抵押期限 <Tag className="m-0">{termMonths} 个月</Tag>
          </>
        ) : null}
      </div>
    </div>
  );
}

const tagName = (targetType: string): string =>
  TARGET_TYPES.find((item) => item.value === targetType)?.label ?? targetType;
