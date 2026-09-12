import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Button,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Pagination,
  Segmented,
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
  EditOutlined,
  DeleteOutlined,
  FileTextOutlined,
  QrcodeOutlined,
  AppstoreOutlined,
  BarsOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { FormInstance } from 'antd/es/form';
import { useLocation, useNavigate } from 'react-router-dom';
import { api, type PageResult } from '@/lib/api';
import { confirmDelete } from '@/lib/confirm';
import { useDictLabelMaps, useDictOptions } from '@/lib/dict';
import { usePermByPath, type PermAction } from '@/lib/perm';
import { AssetQrLabel } from '@/components/AssetQrLabel';
import { CoverImage } from '@/components/CoverImage';
import { TableActions, actionsColumnWidth, type TableActionItem } from '@/components/TableActions';
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
import { useListQuery, useUrlParam } from '@/lib/listQuery';

/**
 * 行操作的可见性规则（纯函数）。
 *
 * <p>抽出来的直接原因：表格**单元格**在服务端渲染下根本不产生 HTML（没有数据行就没有单元格），
 * 用渲染断言去验它只会**假通过** —— 把规则变成纯函数才能真正断言。
 *
 * <p>`hasColumn` 决定「操作」列是否出现：全部行操作都无权且没有详情入口时整列消失，
 * 而不是留一个每行都空白的操作列。
 */
export function resolveRowActions(
  config: Pick<
    ResourceConfig,
    'update' | 'deletable' | 'rowActions' | 'detailPath' | 'detailLink' | 'qrcodePath'
  >,
  canDo: (action: PermAction) => boolean,
): {
  showEdit: boolean;
  showDelete: boolean;
  rowActions: RowActionConfig[];
  hasColumn: boolean;
} {
  const showEdit = Boolean(config.update) && canDo('update');
  const showDelete = Boolean(config.deletable) && canDo('delete');
  const rowActions = (config.rowActions ?? []).filter((a) => !a.perm || canDo(a.perm));
  const hasDetail = Boolean(config.detailPath || config.detailLink);
  return {
    showEdit,
    showDelete,
    rowActions,
    hasColumn:
      showEdit || showDelete || rowActions.length > 0 || hasDetail || Boolean(config.qrcodePath),
  };
}

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
  /**
   * 依赖字段名：该字段值变化时重新拉取 optionsPath。
   * optionsPath 内可用 `{字段名}` 占位，如 `/projects/{projectId}/zones`（项目 → 分区联动）。
   */
  optionsDependsOn?: string;
  /** 下拉多选（值以数组提交），如角色分配 */
  multiple?: boolean;
  /** 仅新增时展示（编辑时隐藏），如初始密码 */
  createOnly?: boolean;
  /** 详情抽屉中隐藏（列表已用名称列展示时避免重复露出原始 ID） */
  hideInDetail?: boolean;
  required?: boolean;
}

export interface ColumnConfig {
  key: string;
  label: string;
  map?: Record<string, string>;
  /**
   * 系统字典编码：列值按「系统字典 → 资产管理字典」的字典项 value → label 动态映射，
   * 字典维护后列表/详情自动同步。优先级高于静态 map（静态 map 仅作加载中兜底）。
   */
  dictCode?: string;
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

/**
 * 顶部统计条数据源。
 *
 * <p>配置 {@code statsPath} 后统计条改为实时拉取（与列表共用同一套筛选条件），
 * 由 {@code statsFormat} 把响应映射为展示项 —— 避免把聚合数字硬编码在配置里。
 * 未配置时沿用静态 {@link ResourceConfig.stats}。
 */
export interface StatSourceConfig {
  path: string;
  format: (data: Record<string, unknown>) => StatConfig[];
}

/** 卡片视图的单个指标 */
export interface CardMetricConfig {
  /** 取数字段 */
  key: string;
  label: string;
  /** 单位后缀，如 ㎡ */
  suffix?: string;
  /** 小数位（默认按整数展示） */
  fractionDigits?: number;
}

/**
 * 卡片视图配置。
 *
 * <p>仅在列表页配置了本项时才出现「列表 / 卡片」切换；卡片字段全部由行数据派生，
 * 不引入额外的请求。图片为空时回落为占位块，避免出现破图。
 */
export interface CardViewConfig {
  /** 主图字段，默认 imageUrl */
  imageKey?: string;
  /** 标题字段，默认 name */
  titleKey?: string;
  /** 角标字段（如状态），需同时给出 badgeMap */
  badgeKey?: string;
  /** 角标取值 → 文案 */
  badgeMap?: Record<string, string>;
  /** 地址拼接字段，默认 ['province','city','district','address'] */
  addressKeys?: string[];
  /** 指标区：资产数 / 面积 / 盘活宗数 / 闲置总数 等 */
  metrics: CardMetricConfig[];
}

/**
 * 列表模式下的可展开行（设计 §5.1）。
 *
 * <p>只在**列表模式**透传给 antd Table：卡片模式没有「行」的概念，展开入口无处安放，
 * 因此配置了本项也不会让卡片模式出现展开按钮。
 *
 * <p>`render` 由调用方提供，`ResourcePage` 不认识被展开内容的语义 —— 分区、明细、日志
 * 都可以挂上来，组件本身不引入任何业务耦合。
 */
export interface ExpandableConfig {
  /** 展开行内容；`row` 为当前列表行 */
  render: (row: Row) => React.ReactNode;
  /** 可选：某行是否可展开（不配置则所有行可展开） */
  rowExpandable?: (row: Row) => boolean;
}

export interface TagFilterConfig {
  key: string;
  label: string;
  /** 静态选项；配置 dictCode 时忽略 */
  options?: { value: string; label: string }[];
  /**
   * 选项取自「系统管理 → 系统字典 → 资产管理字典」的字典项（value / label），
   * 字典维护后筛选项自动同步，无需改代码。
   */
  dictCode?: string;
  /**
   * 级联父级筛选键：本项的字典选项按「同级筛选 filters[父级键] 的取值」联动过滤，
   * 父级未选时展示该字典全量项（后端白名单语义，不限制）。
   */
  cascadeParentKey?: string;
  /** 级联引用的父字典编码，默认「项目属性」 */
  cascadeParentCode?: string;
}

/** 行内快捷操作：弹窗填表后提交 */
export interface RowActionConfig {
  key: string;
  label: string;
  title?: string;
  /**
   * 该动作要求的操作级权限（设计 6.2）。
   *
   * <p>取值为固定动作词表；判定码由**当前路由 path** 经镜像解析出的 `menuCode` 拼成，
   * 不需要在这里写完整 `code:action` —— 同一份 `RESOURCES` 配置可能被不同 path 复用，
   * 写死 code 会在复用时判定错误。未声明时不判定（保持既有行为）。
   */
  perm?: PermAction;
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
  /** 新增跳转到独立页面（优先于弹窗表单，如项目两步走） */
  createLink?: string;
  /** 编辑跳转到独立页面（优先于弹窗表单，如项目两步走） */
  editLink?: (id: number) => string;
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
  /** 顶部统计条（静态文案；需要实时聚合时改用 statsSource） */
  stats?: StatConfig[];
  /** 顶部统计条（实时拉取，与列表同筛选条件；配置后覆盖 stats） */
  statsSource?: StatSourceConfig;
  /** 卡片视图配置（配置后出现「列表 / 卡片」切换） */
  card?: CardViewConfig;
  /** 列表模式下的可展开行（仅列表模式生效；卡片模式忽略） */
  expandable?: ExpandableConfig;
  /** 标签式筛选（含「不限」） */
  tagFilters?: TagFilterConfig[];
  /** CSV 导入导出 */
  exportPath?: string;
  importPath?: string;
  /** 行内快捷操作 */
  rowActions?: RowActionConfig[];
}

type Row = Record<string, unknown>;

type FieldOption = { value: string | number; label: string };

type FieldMode = 'create' | 'edit' | 'action';

/**
 * 标签式筛选项。
 *
 * <p>选项来源：
 * <ul>
 *   <li>配置 {@code dictCode}：取自系统字典，字典维护后自动同步；
 *   <li>配置 {@code cascadeParentKey}：进一步按同级父筛选项的取值联动过滤
 *       （如「项目属性 → 资产来源」）。
 * </ul>
 * 抽成独立组件是为了让字典 Hook 的调用次数不随配置变化（Hooks 规则）。
 */
function TagFilterRow({
  config,
  filters,
  onSelect,
}: {
  config: TagFilterConfig;
  filters: Record<string, string>;
  onSelect: (key: string, value: string) => void;
}) {
  const cascadeParentValue = config.cascadeParentKey ? filters[config.cascadeParentKey] : undefined;
  const dict = useDictOptions(
    config.dictCode,
    config.cascadeParentKey
      ? {
          parentCode: config.cascadeParentCode ?? 'project_property',
          parentValue: cascadeParentValue,
        }
      : undefined,
  );
  // 字典未加载完成时先用静态 options 兜底，避免筛选项「闪空」
  const options = config.dictCode
    ? dict.options.length > 0
      ? dict.options
      : (config.options ?? [])
    : (config.options ?? []);
  const selected = filters[config.key];

  return (
    <div className="flex items-start gap-2 sm:gap-3 mb-3 text-sm min-w-0">
      <span className="text-gray-500 shrink-0 pt-0.5 w-16 sm:w-20 truncate" title={config.label}>
        {config.label}
      </span>
      <div className="flex flex-wrap gap-2 min-w-0 flex-1">
        <Tag
          color={!selected ? 'blue' : undefined}
          className="cursor-pointer m-0"
          onClick={() => onSelect(config.key, '')}
        >
          不限
        </Tag>
        {options.map((o) => (
          <Tag
            key={o.value}
            color={selected === o.value ? 'blue' : undefined}
            className="cursor-pointer m-0 max-w-[140px] truncate"
            title={o.label}
            onClick={() => onSelect(config.key, String(o.value))}
          >
            {o.label}
          </Tag>
        ))}
      </div>
    </div>
  );
}

/** 下拉控件（含远程 optionsPath 字段，加载中显示 loading） */
const renderFieldControl = (
  field: FieldConfig,
  options: FieldOption[] = [],
  optionsLoading = false,
) => {
  if (field.type === 'select' || field.optionsPath) {
    return (
      <Select
        allowClear
        loading={optionsLoading}
        mode={field.multiple ? 'multiple' : undefined}
        options={options}
        placeholder="请选择"
        showSearch
        optionFilterProp="label"
      />
    );
  }
  if (field.type === 'textarea') return <Input.TextArea rows={3} />;
  if (field.type === 'boolean') return <Switch />;
  if (field.type === 'number') return <InputNumber className="w-full" />;
  return <Input type={field.type === 'date' ? 'date' : 'text'} />;
};

const fieldRules = (field: FieldConfig) =>
  field.required ? [{ required: true, message: `请填写${field.label}` }] : undefined;

/** 把远程返回体（数组 / PageResult）按字段配置映射为下拉选项 */
const toFieldOptions = (raw: unknown, field: FieldConfig): FieldOption[] => {
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

/**
 * optionsPath 支持 `{字段名}` 占位。
 * 占位字段未填时返回 null，表示「暂不拉取」，避免拼出 /projects//zones 这类无效路径。
 */
const resolveOptionsPath = (template: string, values: Record<string, unknown>): string | null => {
  let missing = false;
  const path = template.replace(/\{(\w+)\}/g, (_match, key: string) => {
    const value = values[key];
    if (value === undefined || value === null || value === '') {
      missing = true;
      return '';
    }
    return encodeURIComponent(String(value));
  });
  return missing ? null : path;
};

/**
 * 表单字段：远程下拉由字段自身按需拉取（挂载时 + 依赖字段变化时），
 * 免去打开表单前的统一预取。
 */
function ResourceFormField({ field, form }: { field: FieldConfig; form: FormInstance }) {
  // 无依赖字段时挂一个不存在的名字，保证 useWatch 调用顺序稳定
  const dependentValue = Form.useWatch(field.optionsDependsOn ?? '__ams_none__', form);
  const [options, setOptions] = useState<FieldOption[]>(field.options ?? []);
  const [optionsLoading, setOptionsLoading] = useState(false);
  const previousDependentRef = useRef<unknown>(undefined);

  useEffect(() => {
    if (!field.optionsPath) {
      setOptions(field.options ?? []);
      return;
    }
    const path = resolveOptionsPath(field.optionsPath, form.getFieldsValue());
    if (!path) {
      setOptions([]);
      return;
    }
    let cancelled = false;
    setOptionsLoading(true);
    api
      .get<unknown>(path)
      .then((raw) => {
        if (!cancelled) setOptions(toFieldOptions(raw, field));
      })
      .catch(() => {
        if (!cancelled) setOptions(field.options ?? []);
      })
      .finally(() => {
        if (!cancelled) setOptionsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [field, form, dependentValue]);

  useEffect(() => {
    if (!field.optionsDependsOn) return;
    const previous = previousDependentRef.current;
    previousDependentRef.current = dependentValue;
    // 首次（含编辑回填）不清空；仅依赖字段真的换值后清掉已选项，避免残留上一项目的分区
    if (previous !== undefined && previous !== dependentValue) {
      form.setFieldValue(field.name, undefined);
    }
  }, [field, form, dependentValue]);

  return (
    <Form.Item
      name={field.name}
      label={field.label}
      rules={fieldRules(field)}
      valuePropName={field.type === 'boolean' ? 'checked' : 'value'}
    >
      {renderFieldControl(field, options, optionsLoading)}
    </Form.Item>
  );
}

const renderFormFields = (
  fields: FieldConfig[],
  dynamicOptions?: Record<string, FieldOption[]>,
  mode: FieldMode = 'create',
  form?: FormInstance,
) =>
  fields
    // 仅在编辑时隐藏「仅新增」字段（如初始密码）
    .filter((f) => !(mode === 'edit' && f.createOnly))
    .map((f) => {
      // 新增/编辑表单：字段自行拉取远程选项（支持依赖联动）
      if (form) {
        return <ResourceFormField key={f.name} field={f} form={form} />;
      }
      const options = dynamicOptions?.[f.name] ?? f.options ?? [];
      return (
        <Form.Item
          key={f.name}
          name={f.name}
          label={f.label}
          rules={fieldRules(f)}
          valuePropName={f.type === 'boolean' ? 'checked' : 'value'}
        >
          {renderFieldControl(f, options)}
        </Form.Item>
      );
    });

const loadFieldOptions = async (field: FieldConfig) => {
  if (!field.optionsPath) return field.options ?? [];
  return toFieldOptions(await api.get<unknown>(field.optionsPath), field);
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

/**
 * 卡片视图：图片 + 名称 + 指标组 + 地址。
 *
 * <p>字段全部取自列表行数据（后端已在项目列表里聚合好资产统计），因此切换视图不产生额外请求；
 * 图片缺失时回落为占位块，避免破图影响观感。
 */
function ResourceCardGrid({
  rows,
  config,
  idField,
  onOpen,
  onEdit,
}: {
  rows: Row[];
  config: CardViewConfig;
  idField: string;
  onOpen: (row: Row) => void;
  onEdit?: (row: Row) => void;
}) {
  const imageKey = config.imageKey ?? 'imageUrl';
  const titleKey = config.titleKey ?? 'name';
  const addressKeys = config.addressKeys ?? ['province', 'city', 'district', 'address'];

  return (
    <div className="grid gap-3 grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
      {rows.map((row) => {
        const imageUrl = row[imageKey];
        const title = String(row[titleKey] ?? '-');
        const address = addressKeys
          .map((k) => row[k])
          .filter((v) => v != null && v !== '')
          .map(String)
          .join('');
        const badge = config.badgeKey ? config.badgeMap?.[String(row[config.badgeKey])] : undefined;
        return (
          <div
            key={String(row[idField] ?? Math.random())}
            role="button"
            tabIndex={0}
            aria-label={title}
            onClick={() => onOpen(row)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                onOpen(row);
              }
            }}
            className="group border border-[var(--ams-border)] rounded-lg overflow-hidden bg-white hover:shadow-md hover:border-[var(--ams-primary)]/40 transition-all cursor-pointer flex flex-col min-w-0"
          >
            <div className="relative aspect-[16/10] bg-gray-50 overflow-hidden shrink-0">
              {/* 统一封面组件：无图或图片加载失败都回落到占位图，不会出现破图 */}
              <CoverImage
                src={typeof imageUrl === 'string' ? imageUrl : undefined}
                alt={title}
                className="w-full h-full"
                imgClassName="w-full h-full object-cover group-hover:scale-[1.03] transition-transform duration-300"
              />
              {badge && (
                <span className="absolute top-2 left-2 px-2 py-0.5 rounded text-xs bg-white/90 text-[var(--ams-primary)] font-medium shadow-sm">
                  {badge}
                </span>
              )}
            </div>

            <div className="p-3 flex flex-col gap-2 min-w-0 flex-1">
              <div className="font-medium text-gray-900 truncate" title={title}>
                {title}
              </div>

              <div className="grid grid-cols-2 gap-x-3 gap-y-2">
                {config.metrics.map((m) => {
                  const raw = row[m.key];
                  const num = raw == null || raw === '' ? null : Number(raw);
                  const display =
                    num != null && Number.isFinite(num) ? num.toFixed(m.fractionDigits ?? 0) : '0';
                  return (
                    <div key={m.key} className="flex flex-col min-w-0">
                      <span className="text-[11px] text-gray-500 truncate" title={m.label}>
                        {m.label}
                      </span>
                      <span className="text-sm font-semibold text-gray-900 tabular-nums truncate">
                        {display}
                        {m.suffix && (
                          <span className="text-[11px] font-normal text-gray-400 ml-0.5">
                            {m.suffix}
                          </span>
                        )}
                      </span>
                    </div>
                  );
                })}
              </div>

              <div
                className="text-xs text-gray-500 flex items-start gap-1 min-w-0 mt-auto pt-1 border-t border-[var(--ams-border)]"
                title={address || '-'}
              >
                <span className="shrink-0 text-gray-400">地址</span>
                <span className="truncate">{address || '-'}</span>
              </div>
            </div>

            {onEdit && (
              <div className="px-3 pb-3 flex justify-end">
                <Button
                  size="small"
                  type="link"
                  icon={<EditOutlined />}
                  onClick={(e) => {
                    e.stopPropagation();
                    onEdit(row);
                  }}
                >
                  编辑
                </Button>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

export function ResourcePage({ config }: { config: ResourceConfig }) {
  const location = useLocation();
  const navigate = useNavigate();
  /**
   * 操作级权限（设计 6.2）：判定码由当前路由 path 经 `PATH_TO_CODE` 镜像解析，
   * 因此**全站 `ResourcePage` 无需逐页配置**即可与后端 `@RequiresPerm` 对齐。
   *
   * <p>镜像里没有该 path 时 `canByPath` 放行（钻取路由等非菜单路径），
   * 与「未注册路由不打权限标记」的口径一致。
   */
  const canDo = usePermByPath();
  const canCreate = canDo('create');
  const canUpdate = canDo('update');
  const canExport = canDo('export');
  const canImport = canDo('import');
  const [data, setData] = useState<PageResult<Row>>({ list: [], total: 0, page: 1, pageSize: 10 });

  /**
   * 分页 / 关键字 / 筛选以 **URL 为唯一真相**（设计 §5.2）。
   *
   * <p>这是「跳详情或编辑再返回能回到跳转前那一页」的前提：那是路由变化，本组件会整页
   * 卸载，只存在组件 state 里的页码与筛选无法幸存。
   */
  const filterKeys = useMemo(
    () => [...(config.filters ?? []), ...(config.tagFilters ?? [])].map((f) => f.key),
    [config.filters, config.tagFilters],
  );
  const {
    page,
    pageSize,
    keyword,
    filters,
    patch: patchListQuery,
    setPage,
    setKeyword,
    setFilters,
  } = useListQuery({ filterKeys });

  /** 关键字输入框草稿：只有回车/点「查询」才写进 URL，避免每敲一个字都发请求（设计 §8） */
  const [kwDraft, setKwDraft] = useState(keyword);
  useEffect(() => setKwDraft(keyword), [keyword]);

  /** 重拉信号：条件没变时（刷新、查询同一个关键字）靠它触发一次请求 */
  const [reloadToken, setReloadToken] = useState(0);

  /** 请求序号：快速翻页时丢弃过期响应，避免旧响应覆盖新数据（设计 §8） */
  const seqRef = useRef(0);

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
  /** 列表 / 卡片视图；仅在配置了 config.card 时可用。进 URL 以便返回后还原，但不参与拉取 */
  const [viewModeParam, setViewMode] = useUrlParam('view', 'list');
  const viewMode: 'list' | 'card' = viewModeParam === 'card' ? 'card' : 'list';
  /** 实时统计条（config.statsSource 时生效） */
  const [dynamicStats, setDynamicStats] = useState<StatConfig[]>([]);

  const idField = config.idField ?? 'id';
  const rows = data.list ?? [];
  const pageIcon = getPathIcon(location.pathname);

  // 列上声明的字典编码去重后统一拉取，返回 { 字典编码: { value: label } }
  const columnDictMaps = useDictLabelMaps(
    useMemo(
      () => [...new Set(config.columns.map((c) => c.dictCode).filter(Boolean) as string[])],
      [config.columns],
    ),
  );

  /**
   * 列值回显优先级：字典映射 > 静态 map > 全局枚举兜底。
   * 返回 null 表示无可映射文案，由调用方按原始值处理（布尔/数组/空值）。
   */
  const resolveColumnLabel = useCallback(
    (column: ColumnConfig, value: unknown): string | null => {
      const key = value == null || value === '' ? '' : String(value);
      if (!key) return null;
      const dictMap = column.dictCode ? columnDictMaps[column.dictCode] : undefined;
      if (dictMap?.[key] != null) return dictMap[key];
      if (column.map?.[key] != null) return column.map[key];
      const globalMap = GLOBAL_VALUE_MAPS[column.key];
      if (globalMap?.[key] != null) return globalMap[key];
      return null;
    },
    [columnDictMaps],
  );

  const load = async (p = page, size = pageSize, kw = keyword, flt = filters) => {
    // 序号守卫：每次请求自增，只有「最后一次」的响应可以写状态。
    // 快速翻页会并发多个请求，先发后到的旧响应会把新数据覆盖回上一页的内容。
    const seq = ++seqRef.current;
    const stale = () => seq !== seqRef.current;
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
      if (stale()) return;
      setData(normalizePage(raw, p, size));
    } catch (e) {
      if (stale()) return;
      setData({ list: [], total: 0, page: p, pageSize: size });
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      // 过期请求不得清掉后发请求的 loading
      if (!stale()) setLoading(false);
    }
    // 统计条与列表同筛选条件（但不受分页影响），故单独拉取；失败时静默保留上一次结果
    if (config.statsSource && !stale()) {
      const statParams = new URLSearchParams();
      if (kw) statParams.set('keyword', kw);
      Object.entries(flt).forEach(([k, v]) => {
        if (v) statParams.set(k, v);
      });
      Object.entries(config.extraParams ?? {}).forEach(([k, v]) => statParams.set(k, v));
      const ssep = config.statsSource.path.includes('?') ? '&' : '?';
      try {
        const statsRaw = await api.get<Record<string, unknown>>(
          `${config.statsSource.path}${ssep}${statParams.toString()}`,
        );
        setDynamicStats(config.statsSource.format(statsRaw ?? {}));
      } catch {
        setDynamicStats([]);
      }
    }
  };

  /**
   * 条件变化 → 按 **URL 里的条件** 拉取（设计 §5.3）。
   *
   * <p>取代了原来的「改 state + 显式调 load()」：那样条件只活在组件 state 里，
   * 浏览器前进/后退或外部链接改了 URL 也不会重新拉取，表格会停在旧数据上。
   * （列表内翻页用 `replace`、不产生历史条目，故这条路径主要来自深链与前进/后退进列表 —— 设计 §4.2。）
   *
   * <p>原「按 listPath 重置」的 effect 已删除：路由切换后 URL 本身不带 query，
   * 读出来就是第 1 页 + 无筛选，无需再显式重置。
   *
   * <p>`config` 必须留在依赖里 —— 切换资源（`/assets` → `/projects`）时组件是**复用**
   * 而非重新挂载，而两者 URL 都没有 query，只靠 page/keyword/filters 不会变化，
   * 漏掉 config 就永远不会重新拉取。
   */
  useEffect(() => {
    void load(page, pageSize, keyword, filters);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, pageSize, keyword, filters, reloadToken, config]);

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
      // 回到第 1 页 + 重拉：两次 setState 会被 React 批处理，effect 只跑一次
      setPage(1);
      setReloadToken((token) => token + 1);
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
      setReloadToken((token) => token + 1);
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
      setReloadToken((token) => token + 1);
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
          setReloadToken((token) => token + 1);
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

  /** 查询：关键字写入 URL（页码自动归 1）；关键字没变时靠重拉信号触发一次请求 */
  const handleSearch = () => {
    if (kwDraft === keyword) setReloadToken((token) => token + 1);
    else setKeyword(kwDraft);
  };

  const setFilterValue = (key: string, value: string) => {
    if (filters[key] === value) return; // 重复点击同一标签不重复请求
    const nf: Record<string, string> = { ...filters, [key]: value };
    // 级联子项：父级取值变化后清空，避免残留与父级不匹配的选项（如换成土地类还留着「投资建设」）
    (config.tagFilters ?? []).forEach((tf) => {
      if (tf.cascadeParentKey === key) nf[tf.key] = '';
    });
    // setFilters 内部把页码归 1（设计 §5.2），不需要再显式 setPage(1)
    setFilters(nf);
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
        const mapped = resolveColumnLabel(c, raw);
        if (mapped != null) return mapped;
        if (typeof raw === 'boolean') return raw ? '是' : '否';
        return String(raw ?? '-');
      },
    }));
    // 操作列的存在性也要按权限算：无权时整列消失，而不是留一个只有「详情」的空操作列
    const { showEdit, showDelete, rowActions, hasColumn } = resolveRowActions(config, canDo);
    if (hasColumn) {
      const hasMore = !!config.qrcodePath || showDelete || rowActions.length > 0;
      cols.push({
        title: '操作',
        key: '_actions',
        fixed: 'right',
        // 宽度与文案同源推导；hasMore / showEdit 必须复用下方渲染的同一份判定
        width: actionsColumnWidth(
          [
            config.detailPath || config.detailLink
              ? config.detailLink
                ? (config.detailLinkLabel ?? '档案')
                : '详情'
              : null,
            showEdit ? '编辑' : null,
          ].filter((v): v is string => v != null),
          { hasMore },
        ),
        render: (_: unknown, row: Row) => {
          const actions: TableActionItem[] = [];
          if (config.detailPath || config.detailLink) {
            actions.push({
              key: 'detail',
              label: config.detailLink ? (config.detailLinkLabel ?? '档案') : '详情',
              icon: <FileTextOutlined />,
              onClick: () => void openDetail(row),
            });
          }
          if (showEdit) {
            actions.push({
              key: 'edit',
              label: '编辑',
              icon: <EditOutlined />,
              onClick: () => {
                const rowId = row[idField] as number;
                if (config.editLink && rowId != null) {
                  navigate(config.editLink(rowId), {
                    state: { from: currentPath(location) },
                  });
                  return;
                }
                void handleOpenEdit(row);
              },
            });
          }
          const more: TableActionItem[] = [];
          if (config.qrcodePath) {
            more.push({
              key: 'qrcode',
              label: '一产一码',
              icon: <QrcodeOutlined />,
              onClick: () => void handleShowQrcode(row),
            });
          }
          (rowActions ?? []).forEach((action) => {
            if (action.visible && !action.visible(row)) return;
            more.push({
              key: action.key,
              label: action.label,
              onClick: () => void handleOpenRowAction(action, row),
            });
          });
          if (showDelete) {
            more.push({
              key: 'delete',
              label: '删除',
              icon: <DeleteOutlined />,
              danger: true,
              dividerBefore: true,
              onClick: () => handleDelete(row),
            });
          }
          return <TableActions actions={actions} more={more} max={2} />;
        },
      });
    }
    return cols;
  }, [
    canDo,
    canUpdate,
    config.columns,
    config.deletable,
    config.detailPath,
    config.detailLink,
    config.detailLinkLabel,
    config.qrcodePath,
    config.update,
    config.editLink,
    config.rowActions,
    // 字典加载完成后需重算列，否则类型等字段会停留在原始值
    resolveColumnLabel,
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
              value={kwDraft}
              onChange={(e) => setKwDraft(e.target.value)}
              onPressEnter={handleSearch}
            />
            <Button icon={<SearchOutlined />} onClick={handleSearch}>
              查询
            </Button>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => setReloadToken((token) => token + 1)}
              aria-label="刷新列表"
            />
            {config.card && (
              <Segmented
                size="small"
                value={viewMode}
                onChange={(v) => setViewMode(v as 'list' | 'card')}
                options={[
                  { value: 'list', icon: <BarsOutlined />, title: '列表模式' },
                  { value: 'card', icon: <AppstoreOutlined />, title: '卡片模式' },
                ]}
                aria-label="切换展示模式"
              />
            )}
            {/* 导入/导出按权限显隐（设计 6.2）：未配置时保留原有的「后续开放」禁用态，
                已配置但无权时才整块隐藏 —— 后者若也保留禁用态，用户会以为是功能没做好 */}
            {(!config.importPath || canImport) && (
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
                      // 回到第 1 页 + 重拉：两次 setState 会被 React 批处理，effect 只跑一次
                      setPage(1);
                      setReloadToken((token) => token + 1);
                    } catch (e) {
                      message.error(e instanceof Error ? e.message : '导入失败');
                    }
                  };
                  input.click();
                }}
              >
                导入
              </Button>
            )}
            {(!config.exportPath || canExport) && (
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
            )}
            {config.create && canCreate && (
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={() => {
                  if (config.createLink) {
                    navigate(config.createLink, { state: { from: currentPath(location) } });
                    return;
                  }
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

      {(config.statsSource ? dynamicStats : (config.stats ?? [])).length > 0 && (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2 mb-4 pb-3 border-b border-[var(--ams-border)] text-sm overflow-hidden">
          {(config.statsSource ? dynamicStats : (config.stats ?? [])).map((s, i) => (
            <div key={s.label} className="flex items-baseline gap-2 min-w-0">
              {i > 0 && <span className="text-[var(--ams-primary)]/40 hidden sm:inline">|</span>}
              <span className="text-gray-500 shrink-0">{s.label}</span>
              <span className="font-semibold text-gray-900 truncate">{s.value}</span>
            </div>
          ))}
        </div>
      )}

      {(config.tagFilters ?? []).map((tf) => (
        <TagFilterRow key={tf.key} config={tf} filters={filters} onSelect={setFilterValue} />
      ))}

      {config.card && viewMode === 'card' ? (
        <>
          {loading && <div className="py-6 text-center text-sm text-gray-500">加载中…</div>}
          {!loading && rows.length === 0 && (
            <div className="py-16 text-center text-sm text-gray-400">暂无数据</div>
          )}
          {!loading && rows.length > 0 && (
            <ResourceCardGrid
              rows={rows}
              config={config.card}
              idField={idField}
              onOpen={openDetail}
              onEdit={
                config.update && canUpdate
                  ? (row) => {
                      if (config.editLink) {
                        navigate(config.editLink(Number(row[idField])), {
                          state: { from: currentPath(location) },
                        });
                        return;
                      }
                      void handleOpenEdit(row);
                    }
                  : undefined
              }
            />
          )}
        </>
      ) : (
        <div className="ams-table-wrap">
          <Table
            rowKey={(row) => String(row[idField] ?? Math.random())}
            loading={loading}
            columns={tableColumns}
            dataSource={rows}
            pagination={false}
            size="middle"
            scroll={{ x: tableScrollX }}
            // 展开行仅列表模式支持；未配置 expandable 时保持原行为（不渲染展开列）
            expandable={
              config.expandable
                ? {
                    expandedRowRender: (row) => config.expandable!.render(row),
                    rowExpandable: config.expandable!.rowExpandable,
                  }
                : undefined
            }
            onRow={(row) => ({
              onClick: () => openDetail(row),
              className: 'cursor-pointer',
            })}
          />
        </div>
      )}

      <div className="flex justify-end mt-4 overflow-x-auto ams-scroll">
        <Pagination
          current={page}
          pageSize={pageSize}
          total={data.total}
          showSizeChanger
          showTotal={(t) => `共 ${t} 条`}
          responsive
          // 一次写入两个参数：分两次 patch 会先按旧 pageSize 拉一次、再补拉一次
          onChange={(p, size) => patchListQuery({ page: p, pageSize: size })}
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
              if (field?.hideInDetail) return null;
              const label = col?.label ?? field?.label ?? fieldLabel(k);
              // 跳过无中文映射的纯技术字段，避免详情里直接露出英文 key
              if (!col && !field && label === k && /^[a-z][A-Za-z0-9]*$/.test(k)) {
                return null;
              }
              let display: string;
              const mapped = col ? resolveColumnLabel(col, v) : null;
              if (col?.render) {
                display = String(col.render(detail as Row) ?? '-');
              } else if (mapped != null) {
                display = mapped;
              } else if (typeof v === 'boolean') {
                display = v ? '是' : '否';
              } else if (Array.isArray(v)) {
                display = v.length > 0 ? v.map(String).join('、') : '-';
              } else {
                display = String(v ?? '-');
              }
              return (
                <div
                  key={k}
                  className="flex gap-2 border-b border-[var(--ams-border)] py-2 min-w-0"
                >
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
              ? [qrModal.row.province, qrModal.row.city, qrModal.row.district, qrModal.row.address]
                  .filter(Boolean)
                  .map(String)
                  .join('')
              : undefined,
            qrUrl: qrModal.url,
          }}
          onDownload={qrModal.url ? () => void handleDownloadQrcode() : undefined}
        />
        <div className="mt-4 flex justify-end">
          <Button onClick={() => setQrModal({ open: false, loading: false, row: null, url: '' })}>
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
          {renderFormFields(config.fields ?? [], undefined, 'create', form)}
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
          {renderFormFields(config.fields ?? [], undefined, 'edit', editForm)}
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
            {renderFormFields(actionCtx.action.fields, actionFieldOptions, 'action')}
          </Form>
        )}
      </Modal>
    </div>
  );
}
