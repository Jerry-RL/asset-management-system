import { useEffect, useMemo, useState } from 'react';
import {
  Button,
  Dropdown,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Pagination,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  message,
} from 'antd';
import {
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  DownloadOutlined,
  UploadOutlined,
  MoreOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { MenuProps } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';
import { api, type PageResult } from '@/lib/api';
import { confirmDelete } from '@/lib/confirm';
import { AssetQrLabel } from '@/components/AssetQrLabel';
import {
  BILL_STATUS,
  BILL_TYPE,
  BIZ_TYPE,
  CHANNEL,
  CONTRACT_STATUS,
  fieldLabel,
  LEASE_CONTROL_STATUS,
  PAYMENT_CONFIRM,
  PAYMENT_METHOD,
} from '@/lib/labels';
import { getPathIcon } from '@/lib/menuIcons';
import { currentPath } from '@/lib/navigation';

/** 列未配置 map 时的全局枚举兜底，避免表格直接露出英文值 */
const GLOBAL_VALUE_MAPS: Record<string, Record<string, string>> = {
  leaseControlStatus: LEASE_CONTROL_STATUS,
  bizType: BIZ_TYPE,
  confirmStatus: PAYMENT_CONFIRM,
  method: PAYMENT_METHOD,
  channel: CHANNEL,
  billType: BILL_TYPE,
  contractStatus: CONTRACT_STATUS,
  billStatus: BILL_STATUS,
};

export interface FieldConfig {
  name: string;
  label: string;
  type?: 'text' | 'number' | 'date' | 'select' | 'textarea' | 'boolean';
  options?: { value: string | number; label: string }[];
  /** GET 拉取下拉选项（支持 PageResult.list 或数组） */
  optionsPath?: string;
  optionsValueKey?: string;
  optionsLabelKey?: string;
  optionsLabelExtraKey?: string;
  required?: boolean;
}

export interface ColumnConfig {
  key: string;
  label: string;
  map?: Record<string, string>;
  render?: (row: Record<string, unknown>) => React.ReactNode;
}

export interface FilterConfig {
  key: string;
  label: string;
  options?: { value: string; label: string }[];
}

export interface StatConfig {
  label: string;
  value: string | number;
}

export interface TagFilterConfig {
  key: string;
  label: string;
  options: { value: string; label: string }[];
}

/** 行内快捷操作：弹窗填表后提交 */
export interface RowActionConfig {
  key: string;
  label: string;
  title?: string;
  fields: FieldConfig[];
  /** 提交路径，支持 :id 占位 */
  submitPath: string | ((id: number, row: Record<string, unknown>) => string);
  method?: 'post' | 'put';
  visible?: (row: Record<string, unknown>) => boolean;
  defaultValues?: (row: Record<string, unknown>) => Record<string, unknown>;
  /** 提交时把主键 id 写入指定字段（如 assetId） */
  injectIdField?: string;
  /** 不写入主请求 body 的字段（如仅用于后续步骤的 templateId） */
  omitFields?: string[];
  successMessage?: string;
  /** 主请求成功后的跟进行为（如创建合同后再生成 Word） */
  followUp?: {
    when?: (values: Record<string, unknown>, created: Record<string, unknown>) => boolean;
    path: (created: Record<string, unknown>, values: Record<string, unknown>) => string;
    body?: (created: Record<string, unknown>, values: Record<string, unknown>) => unknown;
    method?: 'post' | 'put';
    successMessage?: string;
  };
}

export interface ResourceConfig {
  title: string;
  listPath: string;
  /** 新增提交路径；缺省与 listPath 相同（少数资源新增走独立端点） */
  createPath?: string;
  detailPath?: (id: number) => string;
  /** 跳转到独立详情页（优先于抽屉详情） */
  detailLink?: (id: number) => string;
  /** 独立详情链接按钮文案，默认「档案」 */
  detailLinkLabel?: string;
  /** 下载资产二维码：返回相对 API 路径，如 /assets/1/qrcode */
  qrcodePath?: (id: number) => string;
  create?: boolean;
  update?: boolean;
  deletable?: boolean;
  idField?: string;
  columns: ColumnConfig[];
  fields?: FieldConfig[];
  filters?: FilterConfig[];
  /** 额外固定查询参数 */
  extraParams?: Record<string, string>;
  /** 顶部统计条 */
  stats?: StatConfig[];
  /** 标签式筛选（含「不限」） */
  tagFilters?: TagFilterConfig[];
  /** CSV 导入导出 */
  exportPath?: string;
  importPath?: string;
  /** 行内快捷操作 */
  rowActions?: RowActionConfig[];
}

type Row = Record<string, unknown>;

const renderFormFields = (
  fields: FieldConfig[],
  dynamicOptions?: Record<string, { value: string | number; label: string }[]>,
) =>
  fields.map((f) => {
    const options = dynamicOptions?.[f.name] ?? f.options ?? [];
    return (
      <Form.Item
        key={f.name}
        name={f.name}
        label={f.label}
        rules={f.required ? [{ required: true, message: `请填写${f.label}` }] : undefined}
        valuePropName={f.type === 'boolean' ? 'checked' : 'value'}
      >
        {f.type === 'select' || f.optionsPath ? (
          <Select
            allowClear
            options={options.map((o) => ({ value: o.value, label: o.label }))}
            placeholder="请选择"
            showSearch
            optionFilterProp="label"
          />
        ) : f.type === 'textarea' ? (
          <Input.TextArea rows={3} />
        ) : f.type === 'boolean' ? (
          <Switch />
        ) : f.type === 'number' ? (
          <InputNumber className="w-full" />
        ) : (
          <Input type={f.type === 'date' ? 'date' : 'text'} />
        )}
      </Form.Item>
    );
  });

const loadFieldOptions = async (field: FieldConfig) => {
  if (!field.optionsPath) return field.options ?? [];
  const raw = await api.get<unknown>(field.optionsPath);
  let list: Row[] = [];
  if (Array.isArray(raw)) list = raw as Row[];
  else if (raw && typeof raw === 'object') {
    const obj = raw as { list?: Row[]; records?: Row[] };
    list = obj.list ?? obj.records ?? [];
  }
  const vk = field.optionsValueKey ?? 'id';
  const lk = field.optionsLabelKey ?? 'name';
  const ek = field.optionsLabelExtraKey;
  return list.map((item) => {
    const value = item[vk] as string | number;
    const base = String(item[lk] ?? value);
    const extra = ek && item[ek] != null ? `（${String(item[ek])}）` : '';
    return { value, label: `${base}${extra}` };
  });
};

/** 兼容后端 PageResult 与直接返回 List 两种形态，避免 list 为 undefined 导致白屏。 */
const normalizePage = (raw: unknown, page = 1, pageSize = 10): PageResult<Row> => {
  if (Array.isArray(raw)) {
    const list = raw as Row[];
    return { list, total: list.length, page, pageSize };
  }
  if (raw && typeof raw === 'object') {
    const obj = raw as Partial<PageResult<Row>> & { records?: Row[]; rows?: Row[] };
    const list = obj.list ?? obj.records ?? obj.rows;
    if (Array.isArray(list)) {
      return {
        list,
        total: Number(obj.total ?? list.length),
        page: Number(obj.page ?? page),
        pageSize: Number(obj.pageSize ?? pageSize),
      };
    }
  }
  return { list: [], total: 0, page, pageSize };
};

export function ResourcePage({ config }: { config: ResourceConfig }) {
  const location = useLocation();
  const navigate = useNavigate();
  const [data, setData] = useState<PageResult<Row>>({ list: [], total: 0, page: 1, pageSize: 10 });
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [keyword, setKeyword] = useState('');
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<Row | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [editRow, setEditRow] = useState<Row | null>(null);
  const [actionCtx, setActionCtx] = useState<{ action: RowActionConfig; row: Row } | null>(null);
  const [actionSubmitting, setActionSubmitting] = useState(false);
  const [qrModal, setQrModal] = useState<{
    open: boolean;
    loading: boolean;
    row: Row | null;
    url: string;
  }>({ open: false, loading: false, row: null, url: '' });
  const [form] = Form.useForm();
  const [editForm] = Form.useForm();
  const [actionForm] = Form.useForm();
  const [actionFieldOptions, setActionFieldOptions] = useState<
    Record<string, { value: string | number; label: string }[]>
  >({});
  /** 主表单（新增/编辑）的远程下拉选项，按字段名索引 */
  const [formFieldOptions, setFormFieldOptions] = useState<
    Record<string, { value: string | number; label: string }[]>
  >({});

  const idField = config.idField ?? 'id';
  const rows = data.list ?? [];
  const pageIcon = getPathIcon(location.pathname);

  const load = async (p = page, size = pageSize, kw = keyword, flt = filters) => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ page: String(p), pageSize: String(size) });
      if (kw) params.set('keyword', kw);
      Object.entries(flt).forEach(([k, v]) => {
        if (v) params.set(k, v);
      });
      Object.entries(config.extraParams ?? {}).forEach(([k, v]) => params.set(k, v));
      const sep = config.listPath.includes('?') ? '&' : '?';
      const raw = await api.get<unknown>(`${config.listPath}${sep}${params.toString()}`);
      setData(normalizePage(raw, p, size));
    } catch (e) {
      setData({ list: [], total: 0, page: p, pageSize: size });
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setPage(1);
    setFilters({});
    setKeyword('');
    load(1, pageSize, '', {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [config.listPath]);

  // 主表单远程下拉：字典等 optionsPath 字段在打开表单前预取，避免选项为空
  useEffect(() => {
    const withPath = (config.fields ?? []).filter((f) => f.optionsPath);
    if (withPath.length === 0) {
      setFormFieldOptions({});
      return;
    }
    let cancelled = false;
    const loadOptions = async () => {
      const next: Record<string, { value: string | number; label: string }[]> = {};
      await Promise.all(
        withPath.map(async (f) => {
          try {
            next[f.name] = await loadFieldOptions(f);
          } catch {
            next[f.name] = f.options ?? [];
          }
        }),
      );
      if (!cancelled) setFormFieldOptions(next);
    };
    void loadOptions();
    return () => {
      cancelled = true;
    };
  }, [config]);

  const openDetail = async (row: Row) => {
    const id = row[idField] as number;
    if (id == null) return;
    if (config.detailLink) {
      navigate(config.detailLink(id), { state: { from: currentPath(location) } });
      return;
    }
    if (config.detailPath) {
      try {
        setDetail(await api.get<Row>(config.detailPath(id)));
      } catch {
        setDetail(row);
      }
    } else {
      setDetail(row);
    }
  };

  const handleCreate = async () => {
    try {
      const values = await form.validateFields();
      await api.post(config.createPath ?? config.listPath, values);
      message.success('创建成功');
      setShowCreate(false);
      form.resetFields();
      setPage(1);
      load(1, pageSize, keyword, filters);
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : '创建失败');
    }
  };

  const handleOpenEdit = async (row: Row) => {
    const id = row[idField] as number;
    if (id == null) return;
    setEditRow(row);
    try {
      const detail = config.detailPath ? await api.get<Row>(config.detailPath(id)) : row;
      editForm.setFieldsValue(detail);
    } catch {
      editForm.setFieldsValue(row);
    }
  };

  const handleUpdate = async () => {
    if (!editRow) return;
    const id = editRow[idField] as number;
    try {
      const values = await editForm.validateFields();
      await api.put(`${config.listPath}/${id}`, values);
      message.success('保存成功');
      setEditRow(null);
      editForm.resetFields();
      load(page, pageSize, keyword, filters);
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : '保存失败');
    }
  };

  const handleOpenRowAction = async (action: RowActionConfig, row: Row) => {
    setActionCtx({ action, row });
    actionForm.setFieldsValue(action.defaultValues?.(row) ?? {});
    const nextOptions: Record<string, { value: string | number; label: string }[]> = {};
    await Promise.all(
      action.fields.map(async (f) => {
        if (!f.optionsPath) return;
        try {
          nextOptions[f.name] = await loadFieldOptions(f);
        } catch {
          nextOptions[f.name] = f.options ?? [];
        }
      }),
    );
    setActionFieldOptions(nextOptions);
  };

  const handleSubmitRowAction = async () => {
    if (!actionCtx) return;
    const { action, row } = actionCtx;
    const id = row[idField] as number;
    if (id == null) return;
    setActionSubmitting(true);
    try {
      const values = await actionForm.validateFields();
      const body: Record<string, unknown> = { ...values };
      (action.omitFields ?? []).forEach((k) => {
        delete body[k];
      });
      if (action.injectIdField) body[action.injectIdField] = id;
      const path =
        typeof action.submitPath === 'function'
          ? action.submitPath(id, row)
          : action.submitPath.replace(':id', String(id));
      const created =
        action.method === 'put'
          ? ((await api.put(path, body)) as Row)
          : ((await api.post(path, body)) as Row);

      if (
        action.followUp &&
        (!action.followUp.when || action.followUp.when(values, created ?? {}))
      ) {
        const followPath = action.followUp.path(created ?? {}, values);
        const followBody = action.followUp.body?.(created ?? {}, values) ?? {};
        if (action.followUp.method === 'put') await api.put(followPath, followBody);
        else await api.post(followPath, followBody);
        message.success(action.followUp.successMessage ?? action.successMessage ?? '操作成功');
      } else {
        message.success(action.successMessage ?? '操作成功');
      }
      setActionCtx(null);
      actionForm.resetFields();
      setActionFieldOptions({});
      load(page, pageSize, keyword, filters);
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : '操作失败');
    } finally {
      setActionSubmitting(false);
    }
  };

  const handleDelete = (row: Row) => {
    const id = row[idField] as number;
    if (id == null) return;
    const displayName =
      (typeof row.name === 'string' && row.name) ||
      (typeof row.title === 'string' && row.title) ||
      (typeof row.assetNo === 'string' && row.assetNo) ||
      undefined;
    confirmDelete({
      name: displayName,
      resourceLabel: config.title,
      onOk: async () => {
        try {
          await api.del(`${config.listPath}/${id}`);
          message.success('已删除');
          load(page, pageSize, keyword, filters);
        } catch (e) {
          message.error(e instanceof Error ? e.message : '删除失败');
          throw e;
        }
      },
    });
  };

  const handleShowQrcode = async (row: Row) => {
    if (!config.qrcodePath) return;
    const id = row[idField] as number;
    if (id == null) return;
    setQrModal({ open: true, loading: true, row, url: '' });
    try {
      const pngPath = config.qrcodePath(id);
      const urlPath = pngPath.replace(/\/qrcode$/, '/qrcode-url');
      const data = await api.get<{ qrCodeUrl: string }>(urlPath);
      setQrModal({
        open: true,
        loading: false,
        row,
        url: data.qrCodeUrl || '',
      });
    } catch (e) {
      setQrModal({ open: false, loading: false, row: null, url: '' });
      message.error(e instanceof Error ? e.message : '加载二维码失败');
    }
  };

  const handleDownloadQrcode = async () => {
    if (!config.qrcodePath || !qrModal.row) return;
    const id = qrModal.row[idField] as number;
    if (id == null) return;
    try {
      const name = await api.download(
        config.qrcodePath(id),
        `asset-${String(qrModal.row.assetNo ?? id)}-qrcode.png`,
      );
      message.success(`已下载 ${name}`);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '二维码下载失败');
    }
  };

  const setFilterValue = (key: string, value: string) => {
    const nf = { ...filters, [key]: value };
    setFilters(nf);
    setPage(1);
    load(1, pageSize, keyword, nf);
  };

  const tableColumns: ColumnsType<Row> = useMemo(() => {
    const cols: ColumnsType<Row> = config.columns.map((c) => ({
      title: c.label,
      dataIndex: c.key,
      key: c.key,
      ellipsis: true,
      width: 140,
      render: (_: unknown, row: Row) => {
        if (c.render) return c.render(row);
        const raw = row[c.key];
        if (c.map) return c.map[String(raw)] ?? String(raw ?? '-');
        const globalMap = GLOBAL_VALUE_MAPS[c.key];
        if (globalMap) return globalMap[String(raw)] ?? String(raw ?? '-');
        if (typeof raw === 'boolean') return raw ? '是' : '否';
        return String(raw ?? '-');
      },
    }));
    if (
      config.deletable ||
      config.detailPath ||
      config.detailLink ||
      config.qrcodePath ||
      config.update ||
      (config.rowActions && config.rowActions.length > 0)
    ) {
      const hasMore =
        !!config.qrcodePath ||
        config.deletable ||
        (config.rowActions?.length ?? 0) > 0;
      cols.push({
        title: '操作',
        key: '_actions',
        fixed: 'right',
        width: hasMore ? 220 : config.update ? 160 : 120,
        render: (_: unknown, row: Row) => {
          const moreItems: MenuProps['items'] = [];
          if (config.qrcodePath) {
            moreItems.push({
              key: 'qrcode',
              label: '一产一码',
              onClick: () => void handleShowQrcode(row),
            });
          }
          (config.rowActions ?? []).forEach((action) => {
            if (action.visible && !action.visible(row)) return;
            moreItems.push({
              key: action.key,
              label: action.label,
              onClick: () => {
                void handleOpenRowAction(action, row);
              },
            });
          });
          if (config.deletable) {
            moreItems.push({ type: 'divider' });
            moreItems.push({
              key: 'delete',
              danger: true,
              label: '删除',
              onClick: () => handleDelete(row),
            });
          }
          return (
            <Space size="middle" wrap>
              {(config.detailPath || config.detailLink) && (
                <a
                  onClick={(e) => {
                    e.stopPropagation();
                    openDetail(row);
                  }}
                >
                  {config.detailLink ? config.detailLinkLabel ?? '档案' : '详情'}
                </a>
              )}
              {config.update && (
                <a
                  onClick={(e) => {
                    e.stopPropagation();
                    void handleOpenEdit(row);
                  }}
                >
                  编辑
                </a>
              )}
              {moreItems.length > 0 && (
                <Dropdown menu={{ items: moreItems }} trigger={['click']}>
                  <a
                    onClick={(e) => e.stopPropagation()}
                    aria-label="更多操作"
                  >
                    更多 <MoreOutlined />
                  </a>
                </Dropdown>
              )}
            </Space>
          );
        },
      });
    }
    return cols;
  }, [
    config.columns,
    config.deletable,
    config.detailPath,
    config.detailLink,
    config.detailLinkLabel,
    config.qrcodePath,
    config.update,
    config.rowActions,
  ]);

  const tableScrollX = Math.max(720, (config.columns.length + 1) * 140);

  return (
    <div className="bg-white rounded-lg border border-[var(--ams-border)] p-3 sm:p-4 min-w-0 max-w-full overflow-hidden">
      <div className="flex items-start sm:items-center justify-between mb-4 gap-3 flex-col sm:flex-row min-w-0">
        <h2 className="text-base font-semibold m-0 flex items-center gap-2 min-w-0 max-w-full">
          <span className="inline-flex items-center justify-center w-7 h-7 rounded-md bg-blue-50 text-[var(--ams-primary)] text-sm shrink-0">
            {pageIcon}
          </span>
          <span className="truncate">{config.title}</span>
        </h2>
        <div className="w-full sm:w-auto min-w-0 overflow-x-auto ams-scroll">
          <Space wrap size={[8, 8]} className="!flex">
            {(config.filters ?? []).map((f) => (
              <Select
                key={f.key}
                allowClear
                placeholder={f.label}
                style={{ minWidth: 120, maxWidth: 180 }}
                value={filters[f.key] || undefined}
                options={(f.options ?? []).map((o) => ({ value: o.value, label: o.label }))}
                onChange={(v) => setFilterValue(f.key, v ?? '')}
              />
            ))}
            <Input
              allowClear
              placeholder="搜索关键字"
              prefix={<SearchOutlined className="text-gray-400" />}
              className="!w-[160px] sm:!w-[200px]"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              onPressEnter={() => {
                setPage(1);
                load(1, pageSize, keyword, filters);
              }}
            />
            <Button
              icon={<SearchOutlined />}
              onClick={() => {
                setPage(1);
                load(1, pageSize, keyword, filters);
              }}
            >
              查询
            </Button>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => load(page, pageSize, keyword, filters)}
            />
            <Button
              icon={<UploadOutlined />}
              disabled={!config.importPath}
              title={config.importPath ? '导入 CSV' : '后续开放'}
              onClick={() => {
                if (!config.importPath) return;
                const input = document.createElement('input');
                input.type = 'file';
                input.accept = '.csv,text/csv';
                input.onchange = async () => {
                  const file = input.files?.[0];
                  if (!file || !config.importPath) return;
                  const formData = new FormData();
                  formData.append('file', file);
                  try {
                    const token = localStorage.getItem('ams.accessToken');
                    const resp = await fetch(`/api/v1${config.importPath}`, {
                      method: 'POST',
                      headers: token ? { Authorization: `Bearer ${token}` } : {},
                      body: formData,
                    });
                    const body = await resp.json();
                    if (body.code !== 0) throw new Error(body.message || '导入失败');
                    message.success(`导入成功 ${body.data?.success ?? 0} 条`);
                    load(1, pageSize, keyword, filters);
                  } catch (e) {
                    message.error(e instanceof Error ? e.message : '导入失败');
                  }
                };
                input.click();
              }}
            >
              导入
            </Button>
            <Button
              icon={<DownloadOutlined />}
              disabled={!config.exportPath}
              title={config.exportPath ? '导出 CSV' : '后续开放'}
              onClick={() => {
                if (!config.exportPath) return;
                const token = localStorage.getItem('ams.accessToken');
                fetch(`/api/v1${config.exportPath}`, {
                  headers: token ? { Authorization: `Bearer ${token}` } : {},
                })
                  .then((r) => r.blob())
                  .then((blob) => {
                    const url = URL.createObjectURL(blob);
                    const a = document.createElement('a');
                    a.href = url;
                    a.download = `${config.title}.csv`;
                    a.click();
                    URL.revokeObjectURL(url);
                  })
                  .catch(() => message.error('导出失败'));
              }}
            >
              导出
            </Button>
            {config.create && (
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={() => {
                  form.resetFields();
                  setShowCreate(true);
                }}
              >
                新增
              </Button>
            )}
          </Space>
        </div>
      </div>

      {config.stats && config.stats.length > 0 && (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2 mb-4 pb-3 border-b border-[var(--ams-border)] text-sm overflow-hidden">
          {config.stats.map((s, i) => (
            <div key={s.label} className="flex items-baseline gap-2 min-w-0">
              {i > 0 && <span className="text-[var(--ams-primary)]/40 hidden sm:inline">|</span>}
              <span className="text-gray-500 shrink-0">{s.label}</span>
              <span className="font-semibold text-gray-900 truncate">{s.value}</span>
            </div>
          ))}
        </div>
      )}

      {(config.tagFilters ?? []).map((tf) => (
        <div key={tf.key} className="flex items-start gap-2 sm:gap-3 mb-3 text-sm min-w-0">
          <span className="text-gray-500 shrink-0 pt-0.5 w-16 sm:w-20 truncate" title={tf.label}>
            {tf.label}
          </span>
          <div className="flex flex-wrap gap-2 min-w-0 flex-1">
            <Tag
              color={!filters[tf.key] ? 'blue' : undefined}
              className="cursor-pointer m-0"
              onClick={() => setFilterValue(tf.key, '')}
            >
              不限
            </Tag>
            {tf.options.map((o) => (
              <Tag
                key={o.value}
                color={filters[tf.key] === o.value ? 'blue' : undefined}
                className="cursor-pointer m-0 max-w-[140px] truncate"
                title={o.label}
                onClick={() => setFilterValue(tf.key, o.value)}
              >
                {o.label}
              </Tag>
            ))}
          </div>
        </div>
      ))}

      <div className="ams-table-wrap">
        <Table
          rowKey={(row) => String(row[idField] ?? Math.random())}
          loading={loading}
          columns={tableColumns}
          dataSource={rows}
          pagination={false}
          size="middle"
          scroll={{ x: tableScrollX }}
          onRow={(row) => ({
            onClick: () => openDetail(row),
            className: 'cursor-pointer',
          })}
        />
      </div>

      <div className="flex justify-end mt-4 overflow-x-auto ams-scroll">
        <Pagination
          current={page}
          pageSize={pageSize}
          total={data.total}
          showSizeChanger
          showTotal={(t) => `共 ${t} 条`}
          responsive
          onChange={(p, size) => {
            setPage(p);
            setPageSize(size);
            load(p, size, keyword, filters);
          }}
        />
      </div>

      <Drawer
        title={`${config.title} · 详情`}
        open={!!detail}
        onClose={() => setDetail(null)}
        width={Math.min(480, typeof window !== 'undefined' ? window.innerWidth - 32 : 480)}
        styles={{ body: { overflow: 'auto' } }}
      >
        {detail && (
          <dl className="space-y-0 text-sm m-0">
            {Object.entries(detail).map(([k, v]) => {
              const col = config.columns.find((c) => c.key === k);
              const field = (config.fields ?? []).find((f) => f.name === k);
              const label = col?.label ?? field?.label ?? fieldLabel(k);
              // 跳过无中文映射的纯技术字段，避免详情里直接露出英文 key
              if (!col && !field && label === k && /^[a-z][A-Za-z0-9]*$/.test(k)) {
                return null;
              }
              let display: string;
              if (col?.map) {
                display = col.map[String(v)] ?? String(v ?? '-');
              } else if (GLOBAL_VALUE_MAPS[k]) {
                display = GLOBAL_VALUE_MAPS[k][String(v)] ?? String(v ?? '-');
              } else if (typeof v === 'boolean') {
                display = v ? '是' : '否';
              } else {
                display = String(v ?? '-');
              }
              return (
                <div key={k} className="flex gap-2 border-b border-[var(--ams-border)] py-2 min-w-0">
                  <dt className="w-24 sm:w-32 text-gray-500 shrink-0 truncate" title={label}>
                    {label}
                  </dt>
                  <dd className="flex-1 min-w-0 break-all m-0">{display}</dd>
                </div>
              );
            })}
          </dl>
        )}
      </Drawer>

      <Modal
        title={null}
        open={qrModal.open}
        onCancel={() => setQrModal({ open: false, loading: false, row: null, url: '' })}
        footer={null}
        destroyOnClose
        centered
        width={420}
        styles={{ body: { paddingTop: 20 } }}
      >
        <AssetQrLabel
          variant="modal"
          loading={qrModal.loading}
          asset={{
            assetNo: qrModal.row ? String(qrModal.row.assetNo ?? '') : '',
            name: qrModal.row ? String(qrModal.row.name ?? '') : '',
            assetType: qrModal.row ? String(qrModal.row.assetType ?? '') : undefined,
            area: qrModal.row?.area as number | string | undefined,
            leaseControlStatus: qrModal.row
              ? String(qrModal.row.leaseControlStatus ?? '')
              : undefined,
            address: qrModal.row
              ? [
                  qrModal.row.province,
                  qrModal.row.city,
                  qrModal.row.district,
                  qrModal.row.address,
                ]
                  .filter(Boolean)
                  .map(String)
                  .join('')
              : undefined,
            qrUrl: qrModal.url,
          }}
          onDownload={qrModal.url ? () => void handleDownloadQrcode() : undefined}
        />
        <div className="mt-4 flex justify-end">
          <Button
            onClick={() => setQrModal({ open: false, loading: false, row: null, url: '' })}
          >
            关闭
          </Button>
        </div>
      </Modal>

      <Modal
        title={`新增${config.title}`}
        open={showCreate}
        onCancel={() => {
          setShowCreate(false);
          form.resetFields();
        }}
        onOk={handleCreate}
        destroyOnClose
        width={Math.min(520, typeof window !== 'undefined' ? window.innerWidth - 32 : 520)}
        styles={{ body: { maxHeight: '60vh', overflowY: 'auto' } }}
      >
        <Form form={form} layout="vertical" className="mt-2">
          {renderFormFields(config.fields ?? [], formFieldOptions)}
        </Form>
      </Modal>

      <Modal
        title={`编辑${config.title}`}
        open={!!editRow}
        onCancel={() => {
          setEditRow(null);
          editForm.resetFields();
        }}
        onOk={() => void handleUpdate()}
        destroyOnClose
        width={Math.min(560, typeof window !== 'undefined' ? window.innerWidth - 32 : 560)}
        styles={{ body: { maxHeight: '60vh', overflowY: 'auto' } }}
      >
        <Form form={editForm} layout="vertical" className="mt-2">
          {renderFormFields(config.fields ?? [], formFieldOptions)}
        </Form>
      </Modal>

      <Modal
        title={actionCtx?.action.title ?? actionCtx?.action.label ?? '快捷操作'}
        open={!!actionCtx}
        onCancel={() => {
          setActionCtx(null);
          actionForm.resetFields();
          setActionFieldOptions({});
        }}
        onOk={() => void handleSubmitRowAction()}
        confirmLoading={actionSubmitting}
        destroyOnClose
        centered
        width={Math.min(520, typeof window !== 'undefined' ? window.innerWidth - 32 : 520)}
        styles={{ body: { maxHeight: '60vh', overflowY: 'auto' } }}
      >
        {actionCtx && (
          <Form form={actionForm} layout="vertical" className="mt-2">
            {renderFormFields(actionCtx.action.fields, actionFieldOptions)}
          </Form>
        )}
      </Modal>
    </div>
  );
}
