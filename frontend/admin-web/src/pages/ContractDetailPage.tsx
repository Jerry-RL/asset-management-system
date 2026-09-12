import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  Button,
  Card,
  Col,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
  Space,
  Tag,
  message,
} from 'antd';
import {
  ArrowLeftOutlined,
  DownloadOutlined,
  EyeOutlined,
  FileWordOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { api, type PageResult } from '@/lib/api';
import { useBackNavigate } from '@/lib/navigation';
import { CONTRACT_STATUS, PAYMENT_CYCLE, RENT_TYPE, enumLabel } from '@/lib/labels';

interface Contract {
  id: number;
  contractNo?: string;
  assetId?: number;
  tenantId?: number;
  startDate?: string;
  endDate?: string;
  rentType?: string;
  rentAmount?: number;
  depositAmount?: number;
  leaseArea?: number;
  paymentCycle?: string;
  status?: string;
  templateId?: number;
  docFileId?: number;
  remark?: string;
}

interface ContractTemplate {
  id: number;
  name: string;
  templateCode: string;
  enabled?: boolean;
}

interface SlotField {
  key: string;
  label: string;
  hint?: string;
  placeholder?: string;
}

interface PreviewPayload {
  previewHtml?: string;
  contentHtml?: string;
  generated?: boolean;
  docFileId?: number;
  templateId?: number;
  fileName?: string;
  slots?: Record<string, string>;
  fields?: SlotField[];
  templateName?: string;
}

const DATE_KEYS = new Set(['startDate', 'endDate', 'signDate']);
const NUMBER_KEYS = new Set(['rentAmount', 'depositAmount', 'leaseArea', 'assetArea']);
const TEXTAREA_KEYS = new Set(['remark', 'assetAddress']);

export function ContractDetailPage() {
  const { contractId } = useParams();
  const goBack = useBackNavigate('/contracts');
  const id = Number(contractId);

  const [contract, setContract] = useState<Contract | null>(null);
  const [templates, setTemplates] = useState<ContractTemplate[]>([]);
  const [templateId, setTemplateId] = useState<number | undefined>();
  const [previewHtml, setPreviewHtml] = useState('');
  const [loading, setLoading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [genOpen, setGenOpen] = useState(false);
  const [preparing, setPreparing] = useState(false);
  const [previewing, setPreviewing] = useState(false);
  const [fields, setFields] = useState<SlotField[]>([]);
  const [draftHtml, setDraftHtml] = useState('');
  const [form] = Form.useForm();

  const load = async () => {
    if (!Number.isFinite(id)) return;
    setLoading(true);
    try {
      const [c, tPage] = await Promise.all([
        api.get<Contract>(`/contracts/${id}`),
        api.get<PageResult<ContractTemplate>>(
          '/contract-templates?page=1&pageSize=50&enabled=true',
        ),
      ]);
      setContract(c);
      const list = tPage?.list ?? [];
      setTemplates(list);
      setTemplateId(c.templateId ?? list[0]?.id);
      const preview = await api.get<PreviewPayload>(`/contracts/${id}/document/preview`);
      setPreviewHtml(preview?.previewHtml ?? preview?.contentHtml ?? '');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  const slotsToFormValues = (slots: Record<string, string> = {}) => {
    const values: Record<string, unknown> = { ...slots };
    NUMBER_KEYS.forEach((key) => {
      const raw = slots[key];
      values[key] = raw === undefined || raw === '' ? undefined : Number(raw);
    });
    return values;
  };

  const formValuesToSlots = (values: Record<string, unknown>) => {
    const slots: Record<string, string> = {};
    Object.entries(values).forEach(([key, value]) => {
      if (value == null || value === '') {
        slots[key] = '';
        return;
      }
      slots[key] = String(value);
    });
    return slots;
  };

  const loadPrepare = async (tid?: number) => {
    if (!Number.isFinite(id)) return;
    setPreparing(true);
    try {
      const params = tid ? `?templateId=${tid}` : '';
      const data = await api.get<PreviewPayload>(`/contracts/${id}/document/prepare${params}`);
      setFields(data?.fields ?? []);
      form.setFieldsValue(slotsToFormValues(data?.slots ?? {}));
      setDraftHtml(data?.previewHtml ?? data?.contentHtml ?? '');
      if (data?.templateId) setTemplateId(data.templateId);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载生成选项失败');
    } finally {
      setPreparing(false);
    }
  };

  const handleOpenGenerate = async () => {
    if (!templateId) {
      message.warning('请先选择合同模板');
      return;
    }
    setGenOpen(true);
    await loadPrepare(templateId);
  };

  const handleTemplateChangeInModal = async (tid: number) => {
    setTemplateId(tid);
    await loadPrepare(tid);
  };

  const handleDraftPreview = async () => {
    if (!Number.isFinite(id)) return;
    setPreviewing(true);
    try {
      const values = await form.validateFields();
      const data = await api.post<PreviewPayload>(`/contracts/${id}/document/preview`, {
        templateId,
        slots: formValuesToSlots(values),
      });
      setDraftHtml(data?.previewHtml ?? data?.contentHtml ?? '');
      message.success('草稿预览已更新');
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : '预览失败');
    } finally {
      setPreviewing(false);
    }
  };

  const handleConfirmGenerate = async () => {
    if (!Number.isFinite(id)) return;
    setGenerating(true);
    try {
      const values = await form.validateFields();
      const data = await api.post<PreviewPayload>(`/contracts/${id}/document/generate`, {
        templateId,
        slots: formValuesToSlots(values),
      });
      setPreviewHtml(data?.previewHtml ?? data?.contentHtml ?? '');
      setGenOpen(false);
      message.success('已按填写选项生成 Word 合同');
      await load();
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : '生成失败');
    } finally {
      setGenerating(false);
    }
  };

  const handleExport = async () => {
    if (!Number.isFinite(id)) return;
    try {
      const name = await api.download(
        `/contracts/${id}/document/export`,
        `${contract?.contractNo ?? `contract-${id}`}.docx`,
      );
      message.success(`已导出 ${name}`);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '导出失败');
    }
  };

  const fieldNodes = useMemo(
    () =>
      fields.map((f) => {
        if (DATE_KEYS.has(f.key)) {
          return (
            <Col xs={24} sm={12} key={f.key}>
              <Form.Item name={f.key} label={f.label} tooltip={f.hint || undefined}>
                <Input type="date" allowClear />
              </Form.Item>
            </Col>
          );
        }
        if (NUMBER_KEYS.has(f.key)) {
          return (
            <Col xs={24} sm={12} key={f.key}>
              <Form.Item name={f.key} label={f.label} tooltip={f.hint || undefined}>
                <InputNumber className="w-full" precision={2} />
              </Form.Item>
            </Col>
          );
        }
        if (TEXTAREA_KEYS.has(f.key)) {
          return (
            <Col span={24} key={f.key}>
              <Form.Item name={f.key} label={f.label} tooltip={f.hint || undefined}>
                <Input.TextArea rows={3} placeholder={f.hint} />
              </Form.Item>
            </Col>
          );
        }
        return (
          <Col xs={24} sm={12} key={f.key}>
            <Form.Item name={f.key} label={f.label} tooltip={f.hint || undefined}>
              <Input placeholder={f.hint || f.placeholder} allowClear />
            </Form.Item>
          </Col>
        );
      }),
    [fields],
  );

  if (!Number.isFinite(id)) {
    return (
      <div className="p-4">
        <Button icon={<ArrowLeftOutlined />} onClick={goBack} aria-label="返回原页面">
          返回
        </Button>
        <p className="mt-4 text-gray-500">无效的合同 ID</p>
      </div>
    );
  }

  return (
    <div className="p-4 space-y-4">
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <div>
          <Space className="mb-2">
            <Button icon={<ArrowLeftOutlined />} onClick={goBack} aria-label="返回原页面">
              返回
            </Button>
            <Link to="/contract-templates">合同模板</Link>
          </Space>
          <h1 className="text-xl font-semibold m-0 flex items-center gap-2">
            <FileWordOutlined />
            {contract?.contractNo ?? `合同 #${id}`}
          </h1>
          <p className="text-gray-500 text-sm m-0 mt-1">
            选择模板 → 填写插槽选项 → 生成 Word 并预览导出
          </p>
        </div>
        <Space wrap>
          <Select
            style={{ minWidth: 220 }}
            placeholder="选择合同模板"
            value={templateId}
            options={templates.map((t) => ({
              value: t.id,
              label: `${t.name}（${t.templateCode}）`,
            }))}
            onChange={setTemplateId}
          />
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            刷新
          </Button>
          <Button
            type="primary"
            icon={<ThunderboltOutlined />}
            onClick={handleOpenGenerate}
            aria-label="填写选项并生成合同"
          >
            生成合同
          </Button>
          <Button icon={<DownloadOutlined />} onClick={handleExport} aria-label="导出 Word">
            导出 Word
          </Button>
        </Space>
      </div>

      <Card size="small" title="合同信息" loading={loading}>
        {contract && (
          <Descriptions column={{ xs: 1, sm: 2, md: 3 }} size="small">
            <Descriptions.Item label="合同编号">{contract.contractNo ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="状态">
              <Tag>{enumLabel(CONTRACT_STATUS, contract.status)}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="资产 ID">{contract.assetId ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="租户 ID">{contract.tenantId ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="租期">
              {contract.startDate ?? '-'} ~ {contract.endDate ?? '-'}
            </Descriptions.Item>
            <Descriptions.Item label="租金类型">
              {enumLabel(RENT_TYPE, contract.rentType)}
            </Descriptions.Item>
            <Descriptions.Item label="周期租金">{contract.rentAmount ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="保证金">{contract.depositAmount ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="缴费周期">
              {enumLabel(PAYMENT_CYCLE, contract.paymentCycle)}
            </Descriptions.Item>
            <Descriptions.Item label="租赁面积">{contract.leaseArea ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="模板 ID">{contract.templateId ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="Word 文件 ID">{contract.docFileId ?? '-'}</Descriptions.Item>
          </Descriptions>
        )}
      </Card>

      <Card
        size="small"
        title={
          <span className="inline-flex items-center gap-2">
            <EyeOutlined />
            Word 版在线预览
          </span>
        }
      >
        {previewHtml ? (
          <iframe
            title="合同 Word 预览"
            srcDoc={previewHtml}
            className="w-full border border-gray-200 rounded bg-gray-100"
            style={{ height: '72vh' }}
          />
        ) : (
          <div className="text-gray-500 py-16 text-center">
            暂无预览内容，请点击「生成合同」填写选项后生成
          </div>
        )}
      </Card>

      <Modal
        title="填写合同生成选项"
        open={genOpen}
        onCancel={() => setGenOpen(false)}
        width={Math.min(1080, typeof window !== 'undefined' ? window.innerWidth - 24 : 1080)}
        destroyOnClose
        footer={
          <Space wrap>
            <Button onClick={() => setGenOpen(false)}>取消</Button>
            <Button icon={<EyeOutlined />} loading={previewing} onClick={handleDraftPreview}>
              预览草稿
            </Button>
            <Button
              type="primary"
              icon={<ThunderboltOutlined />}
              loading={generating}
              onClick={handleConfirmGenerate}
            >
              确认生成
            </Button>
          </Space>
        }
      >
        <div className="mb-3">
          <div className="text-sm text-gray-500 mb-1">合同模板</div>
          <Select
            className="w-full"
            value={templateId}
            loading={preparing}
            options={templates.map((t) => ({
              value: t.id,
              label: `${t.name}（${t.templateCode}）`,
            }))}
            onChange={handleTemplateChangeInModal}
          />
        </div>
        <Form form={form} layout="vertical" disabled={preparing}>
          <Row gutter={[12, 0]}>{fieldNodes}</Row>
        </Form>
        {draftHtml ? (
          <div className="mt-2">
            <div className="text-sm text-gray-500 mb-1">草稿预览</div>
            <iframe
              title="生成草稿预览"
              srcDoc={draftHtml}
              className="w-full border border-gray-200 rounded bg-gray-50"
              style={{ height: 360 }}
            />
          </div>
        ) : null}
      </Modal>
    </div>
  );
}
