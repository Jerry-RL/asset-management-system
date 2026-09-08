import { useEffect, useMemo, useState } from 'react';
import {
  Button,
  Card,
  Drawer,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  message,
} from 'antd';
import {
  DownloadOutlined,
  EyeOutlined,
  FileWordOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { api, type PageResult } from '@/lib/api';

interface SlotDef {
  key: string;
  label: string;
  hint?: string;
  placeholder?: string;
}

interface ContractTemplate {
  id: number;
  templateCode: string;
  name: string;
  contractType?: string;
  description?: string;
  contentHtml?: string;
  slotsJson?: string;
  version?: number;
  enabled?: boolean;
  updatedAt?: string;
}

interface PreviewPayload {
  title?: string;
  contentHtml?: string;
  previewHtml?: string;
  slots?: Record<string, string>;
  usedSlots?: string[];
  docFileId?: number;
  downloadPath?: string;
  fileName?: string;
}

const TYPE_OPTIONS = [
  { value: 'lease', label: '租赁合同' },
  { value: 'transfer', label: '转租/变更' },
  { value: 'vacate', label: '退租结算' },
  { value: 'other', label: '其他' },
];

export function ContractTemplatesPage() {
  const [list, setList] = useState<ContractTemplate[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [slots, setSlots] = useState<SlotDef[]>([]);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<ContractTemplate | null>(null);
  const [previewHtml, setPreviewHtml] = useState('');
  const [previewOpen, setPreviewOpen] = useState(false);
  const [form] = Form.useForm();

  const load = async (p = page, size = pageSize, kw = keyword) => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ page: String(p), pageSize: String(size) });
      if (kw) params.set('keyword', kw);
      const data = await api.get<PageResult<ContractTemplate>>(`/contract-templates?${params}`);
      setList(data?.list ?? []);
      setTotal(Number(data?.total ?? 0));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    api
      .get<SlotDef[]>('/contract-templates/slots')
      .then(setSlots)
      .catch(() => setSlots([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const openCreate = () => {
    setEditing(null);
    form.setFieldsValue({
      templateCode: '',
      name: '',
      contractType: 'lease',
      description: '',
      enabled: true,
      contentHtml: `<p>合同编号：{{contractNo}}</p>\n<p>承租方：{{tenantName}}</p>\n<p>资产：{{assetName}}（{{assetAddress}}）</p>\n<p>租期：{{startDate}} 至 {{endDate}}</p>\n<p>租金：{{rentAmount}} 元 / {{paymentCycleLabel}}</p>`,
    });
    setEditorOpen(true);
  };

  const openEdit = async (row: ContractTemplate) => {
    try {
      const detail = await api.get<ContractTemplate>(`/contract-templates/${row.id}`);
      setEditing(detail);
      form.setFieldsValue({
        templateCode: detail.templateCode,
        name: detail.name,
        contractType: detail.contractType ?? 'lease',
        description: detail.description,
        enabled: detail.enabled !== false,
        contentHtml: detail.contentHtml,
      });
      setEditorOpen(true);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载模板失败');
    }
  };

  const handleSave = async () => {
    try {
      const values = await form.validateFields();
      if (editing?.id) {
        await api.put(`/contract-templates/${editing.id}`, {
          name: values.name,
          contractType: values.contractType,
          description: values.description,
          enabled: values.enabled,
          contentHtml: values.contentHtml,
        });
        message.success('模板已更新');
      } else {
        await api.post('/contract-templates', values);
        message.success('模板已创建');
      }
      setEditorOpen(false);
      await load();
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : '保存失败');
    }
  };

  const handlePreview = async (row: ContractTemplate) => {
    try {
      const data = await api.post<PreviewPayload>(`/contract-templates/${row.id}/preview`, {
        sample: true,
      });
      setPreviewHtml(data?.previewHtml ?? data?.contentHtml ?? '');
      setPreviewOpen(true);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '预览失败');
    }
  };

  const handleExport = async (row: ContractTemplate) => {
    try {
      const data = await api.post<PreviewPayload>(`/contract-templates/${row.id}/export`, {
        sample: true,
      });
      if (data?.docFileId) {
        await api.download(`/files/${data.docFileId}/download`, data.fileName ?? `${row.templateCode}.docx`);
        message.success('已导出 Word');
      } else {
        message.warning('未返回文件');
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : '导出失败');
    }
  };

  const insertSlot = (key: string) => {
    const current = String(form.getFieldValue('contentHtml') ?? '');
    form.setFieldValue('contentHtml', `${current}{{${key}}}`);
  };

  const slotTags = useMemo(
    () =>
      slots.map((s) => (
        <Tag
          key={s.key}
          color="blue"
          className="cursor-pointer mb-1"
          onClick={() => insertSlot(s.key)}
          title={s.hint || s.label}
        >
          {s.placeholder ?? `{{${s.key}}}`} · {s.label}
        </Tag>
      )),
    [slots],
  );

  return (
    <div className="p-4 space-y-4">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div>
          <h1 className="text-xl font-semibold m-0 flex items-center gap-2">
            <FileWordOutlined />
            合同模板
          </h1>
          <p className="text-gray-500 text-sm m-0 mt-1">
            使用 {'{{插槽}}'} 快速生成 Word 合同，支持在线预览与导出
          </p>
        </div>
        <Space wrap>
          <Input.Search
            allowClear
            placeholder="搜索编码/名称"
            style={{ width: 220 }}
            onSearch={(v) => {
              setKeyword(v);
              setPage(1);
              load(1, pageSize, v);
            }}
          />
          <Button icon={<ReloadOutlined />} onClick={() => load()} aria-label="刷新">
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建模板
          </Button>
        </Space>
      </div>

      <Card size="small">
        <Table
          rowKey="id"
          loading={loading}
          dataSource={list}
          pagination={{
            current: page,
            pageSize,
            total,
            showSizeChanger: true,
            onChange: (p, size) => {
              setPage(p);
              setPageSize(size);
              load(p, size, keyword);
            },
          }}
          columns={[
            { title: '编码', dataIndex: 'templateCode', width: 160 },
            { title: '名称', dataIndex: 'name', ellipsis: true },
            {
              title: '类型',
              dataIndex: 'contractType',
              width: 110,
              render: (v: string) => TYPE_OPTIONS.find((o) => o.value === v)?.label ?? v ?? '-',
            },
            { title: '版本', dataIndex: 'version', width: 70 },
            {
              title: '状态',
              dataIndex: 'enabled',
              width: 90,
              render: (v: boolean) => (v === false ? <Tag>停用</Tag> : <Tag color="green">启用</Tag>),
            },
            {
              title: '操作',
              key: 'actions',
              width: 260,
              render: (_: unknown, row: ContractTemplate) => (
                <Space wrap>
                  <Button size="small" icon={<EyeOutlined />} onClick={() => handlePreview(row)}>
                    预览
                  </Button>
                  <Button size="small" icon={<DownloadOutlined />} onClick={() => handleExport(row)}>
                    导出 Word
                  </Button>
                  <Button size="small" type="link" onClick={() => openEdit(row)}>
                    编辑
                  </Button>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Drawer
        title={editing ? `编辑模板 · ${editing.name}` : '新建合同模板'}
        width={Math.min(920, typeof window !== 'undefined' ? window.innerWidth - 24 : 920)}
        open={editorOpen}
        onClose={() => setEditorOpen(false)}
        destroyOnClose
        extra={
          <Button type="primary" icon={<SaveOutlined />} onClick={handleSave}>
            保存
          </Button>
        }
      >
        <Form form={form} layout="vertical">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-x-4">
            <Form.Item
              name="templateCode"
              label="模板编码"
              rules={[{ required: true, message: '必填' }]}
            >
              <Input disabled={!!editing} placeholder="如 LEASE_STANDARD_V1" />
            </Form.Item>
            <Form.Item name="name" label="模板名称" rules={[{ required: true, message: '必填' }]}>
              <Input placeholder="如 标准房屋租赁合同" />
            </Form.Item>
            <Form.Item name="contractType" label="合同类型">
              <Select options={TYPE_OPTIONS} />
            </Form.Item>
            <Form.Item name="enabled" label="启用" valuePropName="checked">
              <Switch />
            </Form.Item>
          </div>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={2} />
          </Form.Item>
          <div className="mb-2">
            <div className="text-sm text-gray-600 mb-1">点击插入数据插槽</div>
            <div className="flex flex-wrap gap-1 max-h-28 overflow-auto ams-scroll border border-dashed border-gray-200 rounded p-2">
              {slotTags}
            </div>
          </div>
          <Form.Item
            name="contentHtml"
            label="模板正文（HTML，插槽 {{field}}）"
            rules={[{ required: true, message: '必填' }]}
          >
            <Input.TextArea rows={16} className="font-mono text-xs" />
          </Form.Item>
        </Form>
      </Drawer>

      <Modal
        title="Word 版在线预览"
        open={previewOpen}
        onCancel={() => setPreviewOpen(false)}
        width={880}
        footer={null}
        destroyOnClose
      >
        <iframe
          title="合同模板预览"
          srcDoc={previewHtml}
          className="w-full border-0 bg-gray-100 rounded"
          style={{ height: '70vh' }}
        />
      </Modal>
    </div>
  );
}
