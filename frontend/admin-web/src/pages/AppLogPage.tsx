import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button,
  DatePicker,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  ClearOutlined,
  CopyOutlined,
  FileSearchOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { api, type PageResult } from '@/lib/api';
import {
  useListQuery,
  useUrlParam,
  useUrlParams,
  type UrlParamCodec,
  type UrlParamWrite,
} from '@/lib/listQuery';
import { usePermByPath } from '@/lib/perm';

// ============================================================================
// 应用日志（设计 §12）
//
// 排查页而非管理页，因此取舍与常规 CRUD 页不同：
//   - 一切筛选状态进 URL：现场常常是「别人发来一条带 traceId 的链接」，刷新/分享/后退
//     都必须还原到同一视图，否则每次排查都要重新输一遍条件；
//   - 只读；唯一的写操作是「清理」，且带独立权限点与二次确认（日志是事后追查的唯一线索，
//     误删不可恢复）；
//   - 不做聚合图表：低流量下统计条足够，图表会让人误以为有历史趋势可看，
//     而清理策略决定了这里本来就只有最近 N 天。
// ============================================================================

/** 与后端 `AppLogView` 逐字段对应（`extra` 已在服务端解析为对象） */
interface AppLogView {
  id: number;
  traceId?: string | null;
  level: string;
  appType: string;
  source: string;
  fingerprint?: string | null;
  message: string;
  extra?: unknown;
  ua?: string | null;
  url?: string | null;
  userId?: number | null;
  clientIp?: string | null;
  occurredAt?: string | null;
  createdAt?: string | null;
}

/** 与后端 `AppLogStats` 逐字段对应 */
interface AppLogStats {
  windowHours: number;
  total: number;
  byLevel: { key: string; count: number }[];
  byAppType: { key: string; count: number }[];
  topFingerprint: {
    fingerprint: string;
    count: number;
    level: string;
    appType: string;
    message: string;
  }[];
}

/**
 * 封闭词表的前端副本（与后端枚举同步）。
 *
 * <p>为什么要有副本：手改 URL 传入 `?level=xxx` 时不能把它原样发给后端 ——
 * 那会让筛选框显示「全部」却查不出任何东西（后端只是等值匹配，不报错），
 * 是最难自查的一类「页面看起来正常但结果为空」。白名单之外的值一律忽略。
 */
const LEVELS = ['ERROR', 'WARN', 'INFO'] as const;
const APP_TYPES = [
  'admin-web',
  'h5-tenant',
  'h5-worker',
  'tenant-mp',
  'worker-mp',
  'backend',
] as const;
const SOURCES = ['js', 'promise', 'api', 'backend'] as const;

const LEVEL_LABEL: Record<string, string> = { ERROR: '错误', WARN: '警告', INFO: '信息' };
const LEVEL_COLOR: Record<string, string> = {
  ERROR: 'error',
  WARN: 'warning',
  INFO: 'processing',
};
const APP_TYPE_LABEL: Record<string, string> = {
  'admin-web': '管理后台',
  'h5-tenant': '租户 H5',
  'h5-worker': '员工 H5',
  'tenant-mp': '租户小程序',
  'worker-mp': '员工小程序',
  backend: '后端',
};
const SOURCE_LABEL: Record<string, string> = {
  js: 'JS 运行时',
  promise: 'Promise 未捕获',
  api: 'API 失败',
  backend: '后端异常',
};

const LEVEL_OPTIONS = LEVELS.map((value) => ({
  value,
  label: `${LEVEL_LABEL[value]}（${value}）`,
}));
const APP_TYPE_OPTIONS = APP_TYPES.map((value) => ({
  value,
  label: `${APP_TYPE_LABEL[value]}（${value}）`,
}));
const SOURCE_OPTIONS = SOURCES.map((value) => ({
  value,
  label: `${SOURCE_LABEL[value]}（${value}）`,
}));

/**
 * `before` 与实际请求参数的统一时间格式：**ISO 本地时间，不带时区后缀**。
 *
 * <p>为什么不带 `Z`：后端参数是 `LocalDateTime`（`@DateTimeFormat(iso = ISO.DATE_TIME)`），
 * 送一个 `...Z` 过去会解析失败 → 400，表现为「选完时间范围就报错」。全仓现有接口
 * （如 `/ops-calendar`）同样只传本地时间，这里保持同一口径。
 *
 * <p>代价是隐含了「端侧与服务端同一时区」这一前提（本项目为国内单时区部署）。
 * 跨时区使用时会整体偏移，届时需要改成带偏移量的时间并同步改后端类型。
 */
const STAMP_FORMAT = 'YYYY-MM-DDTHH:mm:ss';
const stamp = (value: Dayjs): string => value.format(STAMP_FORMAT);
const stampToParam = (raw: string | null): Dayjs | null => {
  if (!raw) return null;
  const parsed = dayjs(raw, STAMP_FORMAT);
  return parsed.isValid() ? parsed : null;
};

/** 时间范围预设。默认近 24 小时：与统计条同窗口，进来第一眼就能对上。 */
const DEFAULT_RANGE = '24h';
const RANGE_OPTIONS: { value: string; label: string }[] = [
  { value: '1h', label: '近 1 小时' },
  { value: '24h', label: '近 24 小时' },
  { value: '7d', label: '近 7 天' },
  { value: '30d', label: '近 30 天' },
  { value: 'custom', label: '自定义…' },
  { value: 'all', label: '全部时间' },
];
/** 预设 → 回溯小时数（`custom` / `all` 不走这张表） */
const RANGE_HOURS: Record<string, number> = { '1h': 1, '24h': 24, '7d': 24 * 7, '30d': 24 * 30 };

/**
 * 解析出本次查询要用的时间窗。
 *
 * <p>**刻意在每次 `load()` 时现算而不是 `useMemo` 出来**：预设的含义是「相对此刻」，
 * 若在挂载时算一次，页面开着两小时后点「刷新」拿到的还是两小时前那个窗口，
 * 而用户看到「近 1 小时」时会以为刷新能拿回刚刚发生的错误。
 *
 * <p>`to` 只在自定义时下发：预设的右边界就是「现在」（后端不下发即不设上界），
 * 端侧与服务端的时钟差会被算进窗口，反而更宽容。
 */
const resolveWindow = (
  range: string,
  customFrom: Dayjs | null,
  customTo: Dayjs | null,
): { from?: string; to?: string } => {
  if (range === 'all') return {};
  if (range === 'custom') {
    return {
      from: customFrom ? stamp(customFrom) : undefined,
      to: customTo ? stamp(customTo) : undefined,
    };
  }
  const hours = RANGE_HOURS[range];
  if (!hours) return {};
  return { from: stamp(dayjs().subtract(hours, 'hour')) };
};

/**
 * 白名单之外的筛选值一律当「没选」。
 *
 * <p>对应设计 §15「查询页手改 URL 传入非法 level → 忽略该筛选（不传后端）」。
 */
const pickEnum = <T extends readonly string[]>(
  raw: string | undefined,
  allowed: T,
): string | undefined => (raw && (allowed as readonly string[]).includes(raw) ? raw : undefined);

/** `from` / `to` 的 URL 编解码；空值一律落成「删参数」 */
const STAMP_CODEC: UrlParamCodec<Dayjs | null> = {
  parse: stampToParam,
  serialize: (value) => (value ? stamp(value) : ''),
};

const formatTime = (value?: string | null): string =>
  value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';

/**
 * 端侧时钟偏差阈值（设计 §12）：超过就提示「端侧时钟可能不准」。
 *
 * <p>`occurred_at` 由端侧自报（可伪造、也可能是设备时间错），`created_at` 是服务端落库时间。
 * 两者对不上时，**时间线要以 `created_at` 为准**，否则会把人引到错误的时间点上去找日志。
 */
const CLOCK_SKEW_SECONDS = 60;

const clockSkewSeconds = (row: AppLogView): number | null => {
  if (!row.occurredAt || !row.createdAt) return null;
  const occurred = dayjs(row.occurredAt);
  const created = dayjs(row.createdAt);
  if (!occurred.isValid() || !created.isValid()) return null;
  return Math.abs(occurred.diff(created, 'second'));
};

/** 复制到剪贴板。失败要让用户看到，而不是静默什么都没发生（他以为复制成功了）。 */
const copyText = async (text: string, label: string) => {
  try {
    await navigator.clipboard.writeText(text);
    message.success(`${label}已复制`);
  } catch {
    message.warning('浏览器拒绝了剪贴板访问，请手动选中复制');
  }
};

/** 格式化 `extra`：无法序列化时退回原始字符串，不抛错 */
const formatExtra = (extra: unknown): string => {
  if (extra == null) return '';
  if (typeof extra === 'string') return extra;
  try {
    return JSON.stringify(extra, null, 2);
  } catch {
    return String(extra);
  }
};

/**
 * 「回车才提交」的文本筛选输入框。
 *
 * <p>为什么不直接 `onChange` 查：`keyword` 在后端是 LIKE 全表扫，逐字符触发等于把
 * 每一次按键都变成一次全表查询，日志量大时这是最容易被自己打满的一处。
 *
 * <p>为什么清空要**立即**提交：用户点输入框的 X 时期待的是「马上看全部」，
 * 再要求他按一次回车是反直觉的，而且很容易被误判成「清了但没生效」。
 *
 * <p>为什么要有 `draft` 而不是直接用 URL 里的值：URL 是提交后的状态，
 * 输入过程中它还没变；不做受控草稿的话，输入框会在每次重渲染时被服务端值覆盖回去。
 * 反向同步（URL 变了就刷新草稿）负责处理后端跳转、分享链接与浏览器前进后退。
 */
const useDraftFilter = (committed: string, commit: (value: string) => void) => {
  const [draft, setDraft] = useState(committed);
  useEffect(() => {
    setDraft(committed);
  }, [committed]);
  return {
    draft,
    handleChange: (next: string) => {
      setDraft(next);
      if (next === '') commit('');
    },
    handleCommit: () => commit(draft.trim()),
  };
};

export function AppLogPage() {
  // 路由 /system/app-logs 在 PATH_TO_CODE 镜像里，故按当前路由派生判定（同 SystemDictionaryPage）
  const canDo = usePermByPath();

  const list = useListQuery({
    // 不含 keyword：它是 useListQuery 的保留键（page / pageSize / keyword），
    // 混进 filterKeys 会让两者互相覆盖（lib/listQueryParams.ts 的 RESERVED_LIST_KEYS）。
    filterKeys: ['level', 'appType', 'source', 'traceId'],
    defaultPageSize: 20,
  });
  // 先解构出稳定的原始值：`list` 每次渲染都是新对象，把它整个放进 useCallback 依赖
  // 会让 load 每次都换新引用，再被 useEffect 捕获 → 无限请求循环
  const { page, pageSize, keyword, filters, setPage, setPageSize, setKeyword, setFilter } = list;

  const level = pickEnum(filters.level, LEVELS);
  const appType = pickEnum(filters.appType, APP_TYPES);
  const source = pickEnum(filters.source, SOURCES);
  const traceId = filters.traceId ?? '';

  const [range, , rangeWrite] = useUrlParam('range', DEFAULT_RANGE);
  const [customFrom, , fromWrite] = useUrlParam<Dayjs | null>('from', null, STAMP_CODEC);
  const [customTo, , toWrite] = useUrlParam<Dayjs | null>('to', null, STAMP_CODEC);
  const writeParams = useUrlParams();

  const [rows, setRows] = useState<AppLogView[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [stats, setStats] = useState<AppLogStats | null>(null);
  const [statsLoading, setStatsLoading] = useState(false);

  const [detail, setDetail] = useState<AppLogView | null>(null);
  const [traceOpen, setTraceOpen] = useState(false);
  const [traceRows, setTraceRows] = useState<AppLogView[]>([]);
  const [traceLoading, setTraceLoading] = useState(false);

  const [purgeOpen, setPurgeOpen] = useState(false);
  const [purging, setPurging] = useState(false);
  const [purgeForm] = Form.useForm();

  const loadStats = useCallback(async () => {
    setStatsLoading(true);
    try {
      setStats(await api.get<AppLogStats>('/system/app-logs/stats'));
    } catch {
      // 统计失败不该拦住列表 —— 它是辅助信息，列表才是主体
      setStats(null);
    } finally {
      setStatsLoading(false);
    }
  }, []);

  const load = useCallback(async () => {
    const { from, to } = resolveWindow(range, customFrom, customTo);
    const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
    if (keyword) params.set('keyword', keyword);
    if (level) params.set('level', level);
    if (appType) params.set('appType', appType);
    if (source) params.set('source', source);
    if (traceId) params.set('traceId', traceId);
    if (from) params.set('from', from);
    if (to) params.set('to', to);

    setLoading(true);
    try {
      const data = await api.get<PageResult<AppLogView>>(`/system/app-logs?${params.toString()}`);
      setRows(data?.list ?? []);
      setTotal(data?.total ?? 0);
    } catch (e) {
      setRows([]);
      setTotal(0);
      message.error(e instanceof Error ? e.message : '加载应用日志失败');
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, keyword, level, appType, source, traceId, range, customFrom, customTo]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadStats();
  }, [loadStats]);

  const handleRefresh = () => {
    void load();
    void loadStats();
  };

  /**
   * 换时间范围：**一次 `setSearchParams` 写完所有键**。
   *
   * <p>为什么不能「先 setValue(range) 再 setPage(1)」：react-router 是把本次渲染闭包里的
   * `searchParams` 交给更新函数求 `prev`，两次调用从同一份旧参数出发，后一次覆盖前一次，
   * 先写的 `range` 就丢了（lib/listQuery.ts 顶部已把这条写得明明白白）。
   *
   * <p>`page` 键名与 `useListQuery` 无前缀时的键名一致 —— 这里手动重置页码而不走
   * `list.patch`，正是为了合并进同一次写入。
   */
  const RESET_PAGE: UrlParamWrite = { key: 'page', raw: null };

  const handleRangeChange = (next: string) => {
    const writes: UrlParamWrite[] = [rangeWrite(next)];
    if (next !== 'custom') {
      // 离开自定义就把边界清掉，否则下次再切回自定义会看到上一次的残留区间
      writes.push(fromWrite(null), toWrite(null));
    }
    writes.push(RESET_PAGE);
    writeParams(writes);
  };

  const handleCustomRangeChange = (values: [Dayjs | null, Dayjs | null] | null) => {
    const [start, end] = values ?? [null, null];
    writeParams([rangeWrite('custom'), fromWrite(start ?? null), toWrite(end ?? null), RESET_PAGE]);
  };

  const openTrace = useCallback(async (value: string) => {
    setTraceOpen(true);
    setTraceLoading(true);
    setTraceRows([]);
    try {
      setTraceRows(
        await api.get<AppLogView[]>(`/system/app-logs/trace/${encodeURIComponent(value)}`),
      );
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载链路失败');
    } finally {
      setTraceLoading(false);
    }
  }, []);

  const keywordInput = useDraftFilter(keyword, setKeyword);
  const traceIdInput = useDraftFilter(traceId, (value) => setFilter('traceId', value));

  /**
   * 清理：先在弹窗里填时间边界（`before` 必填），再二次确认。
   *
   * <p>两次确认不是仪式感：`before` 一旦填成未来时间，这条请求就是「清空全表」，
   * 而日志是事后追查的唯一线索、删掉之后无法证明当时发生了什么。
   */
  const handlePurge = async () => {
    let values: { before?: Dayjs } | undefined;
    try {
      values = await purgeForm.validateFields();
    } catch {
      return;
    }
    const before = values?.before;
    if (!before) return;

    const appTypeFilter = list.filters.appType;
    Modal.confirm({
      title: '确认清理日志？',
      content: (
        <div className="text-sm">
          <div>
            将删除 <b>{formatTime(stamp(before))}</b> 之前创建的全部日志
            {pickEnum(appTypeFilter, APP_TYPES)
              ? `（仅 ${APP_TYPE_LABEL[appTypeFilter ?? '']}）`
              : ''}
            。
          </div>
          <div className="text-red-500 mt-1">删除不可恢复，请确认已不再需要这些记录。</div>
        </div>
      ),
      okText: '确认清理',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        setPurging(true);
        try {
          const body: Record<string, string> = { before: stamp(before) };
          const appTypeValue = pickEnum(appTypeFilter, APP_TYPES);
          if (appTypeValue) body.appType = appTypeValue;
          const data = await api.post<{ deleted: number }>('/system/app-logs/purge', body);
          message.success(`已清理 ${data?.deleted ?? 0} 条日志`);
          setPurgeOpen(false);
          purgeForm.resetFields();
          handleRefresh();
        } catch (e) {
          message.error(e instanceof Error ? e.message : '清理失败');
          throw e;
        } finally {
          setPurging(false);
        }
      },
    });
  };

  const countOf = (items: { key: string; count: number }[] | undefined, key: string): number =>
    items?.find((item) => item.key === key)?.count ?? 0;

  const columns: ColumnsType<AppLogView> = useMemo(
    () => [
      {
        title: '时间',
        dataIndex: 'createdAt',
        key: 'createdAt',
        width: 170,
        // 显示 created_at（服务端时间）而不是 occurred_at：端侧时钟不可信，
        // 排查时的时间线必须只有一个基准（设计与 §12 一致）
        render: (_, row) => (
          <span className="tabular-nums text-gray-600">{formatTime(row.createdAt)}</span>
        ),
      },
      {
        title: '级别',
        dataIndex: 'level',
        key: 'level',
        width: 90,
        render: (_, row) => (
          <Tag color={LEVEL_COLOR[row.level] ?? 'default'} className="m-0">
            {LEVEL_LABEL[row.level] ?? row.level}
          </Tag>
        ),
      },
      {
        title: '来源端',
        dataIndex: 'appType',
        key: 'appType',
        width: 120,
        render: (_, row) => APP_TYPE_LABEL[row.appType] ?? row.appType,
      },
      {
        title: '错误类型',
        dataIndex: 'source',
        key: 'source',
        width: 120,
        render: (_, row) => SOURCE_LABEL[row.source] ?? row.source,
      },
      {
        title: '信息',
        dataIndex: 'message',
        key: 'message',
        ellipsis: true,
        render: (_, row) => (
          <Tooltip title={row.message} placement="topLeft">
            <span className="text-gray-800">{row.message}</span>
          </Tooltip>
        ),
      },
      {
        title: '页面',
        dataIndex: 'url',
        key: 'url',
        width: 200,
        ellipsis: true,
        render: (_, row) =>
          row.url ? (
            <Tooltip title={row.url} placement="topLeft">
              <span className="text-xs text-gray-500">{row.url}</span>
            </Tooltip>
          ) : (
            <span className="text-gray-300">-</span>
          ),
      },
      {
        title: 'TraceId',
        dataIndex: 'traceId',
        key: 'traceId',
        width: 140,
        render: (_, row) =>
          row.traceId ? (
            <Button
              type="link"
              size="small"
              className="p-0 h-auto font-mono text-xs"
              // 阻止冒泡：否则点 traceId 会同时打开详情抽屉，两个抽屉叠在一起
              onClick={(e) => {
                e.stopPropagation();
                void openTrace(row.traceId as string);
              }}
            >
              {row.traceId.slice(0, 8)}…
            </Button>
          ) : (
            <span className="text-gray-300">-</span>
          ),
      },
    ],
    [openTrace],
  );

  const skew = detail ? clockSkewSeconds(detail) : null;
  const detailExtra = detail ? formatExtra(detail.extra) : '';

  return (
    <div className="space-y-4 min-w-0 max-w-full overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold text-gray-900 flex items-center gap-2 m-0">
            <FileSearchOutlined className="text-[var(--ams-primary)]" />
            应用日志
          </h1>
          <p className="text-sm text-gray-500 mt-1 mb-0">
            端侧 JS 错误 / Promise 未捕获 / API 失败与后端未捕获异常，按 TraceId 串起全链路
          </p>
        </div>
        <Space wrap size={[8, 8]}>
          <Button icon={<ReloadOutlined />} onClick={handleRefresh} loading={loading}>
            刷新
          </Button>
          {canDo('delete') && (
            <Button
              danger
              icon={<ClearOutlined />}
              onClick={() => {
                purgeForm.setFieldsValue({
                  before: dayjs().subtract(30, 'day').startOf('day'),
                });
                setPurgeOpen(true);
              }}
            >
              清理…
            </Button>
          )}
        </Space>
      </div>

      {/* 统计条：近 24h 概览。刻意与列表筛选解耦 —— 它回答「现在整体怎么样」，
          列表回答「我要找的那条在哪」，跟着筛选变会让人误读为「只剩这么多」。 */}
      <div className="bg-white rounded-xl border border-[var(--ams-border)] px-4 py-3">
        <Space size={[16, 8]} wrap>
          <span className="text-xs text-gray-400">近 {stats?.windowHours ?? 24} 小时</span>
          <span className="text-sm text-gray-600">
            错误 <b className="text-red-500 tabular-nums">{countOf(stats?.byLevel, 'ERROR')}</b>
          </span>
          <span className="text-sm text-gray-600">
            警告 <b className="text-amber-500 tabular-nums">{countOf(stats?.byLevel, 'WARN')}</b>
          </span>
          <span className="text-sm text-gray-600">
            涉及端 <b className="tabular-nums">{stats?.byAppType?.length ?? 0}</b>
          </span>
          <span className="text-sm text-gray-600">
            合计 <b className="tabular-nums">{stats?.total ?? 0}</b>
          </span>
          {statsLoading && <span className="text-xs text-gray-400">统计加载中…</span>}
        </Space>
      </div>

      <div className="bg-white rounded-xl border border-[var(--ams-border)] p-3 sm:p-4">
        <div className="flex flex-wrap items-center gap-2 mb-3">
          <Select
            className="!w-[150px]"
            value={range}
            options={RANGE_OPTIONS}
            onChange={handleRangeChange}
            aria-label="时间范围"
          />
          {range === 'custom' && (
            <DatePicker.RangePicker
              showTime
              allowEmpty={[true, true]}
              value={[customFrom, customTo]}
              onChange={handleCustomRangeChange}
              aria-label="自定义时间范围"
            />
          )}
          <Select
            className="!w-[160px]"
            allowClear
            placeholder="全部级别"
            value={level}
            options={LEVEL_OPTIONS}
            onChange={(value?: string) => setFilter('level', value ?? '')}
          />
          <Select
            className="!w-[190px]"
            allowClear
            placeholder="全部来源端"
            value={appType}
            options={APP_TYPE_OPTIONS}
            onChange={(value?: string) => setFilter('appType', value ?? '')}
          />
          <Select
            className="!w-[190px]"
            allowClear
            placeholder="全部错误类型"
            value={source}
            options={SOURCE_OPTIONS}
            onChange={(value?: string) => setFilter('source', value ?? '')}
          />
          <Input
            className="!w-[240px]"
            allowClear
            placeholder="搜索信息 / URL（回车）"
            value={keywordInput.draft}
            prefix={<SearchOutlined className="text-gray-300" />}
            onChange={(e) => keywordInput.handleChange(e.target.value)}
            onPressEnter={keywordInput.handleCommit}
          />
          <Input
            className="!w-[300px]"
            allowClear
            placeholder="TraceId 精确查询（回车）"
            value={traceIdInput.draft}
            onChange={(e) => traceIdInput.handleChange(e.target.value)}
            onPressEnter={traceIdInput.handleCommit}
          />
        </div>

        <div className="ams-table-wrap">
          <Table
            rowKey="id"
            size="small"
            loading={loading}
            columns={columns}
            dataSource={rows}
            scroll={{ x: 1080 }}
            locale={{
              emptyText: (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="该条件下暂无日志（好消息：这通常意味着没有错误）"
                />
              ),
            }}
            onRow={(row) => ({
              // 整行可点开详情：日志的字段很多，表格塞不下，而排查时几乎总要看到 extra
              onClick: () => setDetail(row),
              style: { cursor: 'pointer' },
            })}
            pagination={{
              current: page,
              pageSize,
              total,
              showSizeChanger: true,
              pageSizeOptions: ['20', '50', '100'],
              showTotal: (value) => `共 ${value} 条`,
              onChange: (nextPage, nextPageSize) => {
                // pageSize 变化时 useListQuery 会自动回到第 1 页（patch 里 resetPage），
                // 所以这里只需把两个值一起交给它，不能分开调两次 setter
                if (nextPageSize !== pageSize) setPageSize(nextPageSize);
                else setPage(nextPage);
              },
            }}
          />
        </div>
      </div>

      {/* 详情抽屉：单条全字段 + extra 格式化 JSON */}
      <Drawer
        title="日志详情"
        open={!!detail}
        onClose={() => setDetail(null)}
        width={Math.min(720, typeof window !== 'undefined' ? window.innerWidth - 32 : 720)}
        destroyOnHidden
      >
        {detail && (
          <div className="space-y-3">
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="时间（服务端）">
                {formatTime(detail.createdAt)}
              </Descriptions.Item>
              <Descriptions.Item label="时间（端侧自报）">
                {formatTime(detail.occurredAt)}
                {skew !== null && skew > CLOCK_SKEW_SECONDS && (
                  <span className="text-amber-600 ml-2">
                    ⚠ 与落库时间相差 {skew}s，端侧时钟可能不准，请以服务端时间为准
                  </span>
                )}
              </Descriptions.Item>
              <Descriptions.Item label="级别">
                <Tag color={LEVEL_COLOR[detail.level] ?? 'default'} className="m-0">
                  {LEVEL_LABEL[detail.level] ?? detail.level}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="来源端">
                {APP_TYPE_LABEL[detail.appType] ?? detail.appType}
              </Descriptions.Item>
              <Descriptions.Item label="错误类型">
                {SOURCE_LABEL[detail.source] ?? detail.source}
              </Descriptions.Item>
              <Descriptions.Item label="信息">
                <span className="break-all">{detail.message}</span>
              </Descriptions.Item>
              <Descriptions.Item label="页面">
                <span className="break-all">{detail.url || '-'}</span>
              </Descriptions.Item>
              <Descriptions.Item label="UA">
                <span className="break-all text-xs">{detail.ua || '-'}</span>
              </Descriptions.Item>
              <Descriptions.Item label="用户 ID">{detail.userId ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="客户端 IP">{detail.clientIp || '-'}</Descriptions.Item>
              <Descriptions.Item label="指纹">
                <Space>
                  <span className="font-mono text-xs break-all">{detail.fingerprint || '-'}</span>
                  {detail.fingerprint && (
                    <Button
                      size="small"
                      type="text"
                      icon={<CopyOutlined />}
                      aria-label="复制指纹"
                      onClick={() => void copyText(detail.fingerprint as string, '指纹')}
                    />
                  )}
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label="TraceId">
                <Space>
                  <span className="font-mono text-xs break-all">{detail.traceId || '-'}</span>
                  {detail.traceId && (
                    <>
                      <Button
                        size="small"
                        type="text"
                        icon={<CopyOutlined />}
                        aria-label="复制 TraceId"
                        onClick={() => void copyText(detail.traceId as string, 'TraceId')}
                      />
                      <Button size="small" onClick={() => void openTrace(detail.traceId as string)}>
                        查看链路
                      </Button>
                    </>
                  )}
                </Space>
              </Descriptions.Item>
            </Descriptions>

            <div>
              <div className="flex items-center justify-between mb-1">
                <span className="text-sm font-medium text-gray-700">extra</span>
                {detailExtra && (
                  <Button
                    size="small"
                    icon={<CopyOutlined />}
                    onClick={() => void copyText(detailExtra, 'extra')}
                  >
                    复制
                  </Button>
                )}
              </div>
              {detailExtra ? (
                <pre className="m-0 max-h-[420px] overflow-auto rounded bg-gray-50 border border-[var(--ams-border)] p-3 text-xs leading-relaxed">
                  {detailExtra}
                </pre>
              ) : (
                <div className="text-xs text-gray-400 py-2">无附加信息</div>
              )}
            </div>
          </div>
        )}
      </Drawer>

      {/* 链路抽屉：同一 traceId 下的全部记录，按发生时间升序 —— 端侧 API 失败与后端异常
          会并排出现，这正是「一次查询看全链路」的落点 */}
      <Drawer
        title="链路详情"
        open={traceOpen}
        onClose={() => setTraceOpen(false)}
        width={Math.min(760, typeof window !== 'undefined' ? window.innerWidth - 32 : 760)}
        destroyOnHidden
      >
        {traceRows.length === 0 && !traceLoading ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该 TraceId 下暂无记录" />
        ) : (
          <div className="space-y-3">
            {traceRows.map((row) => {
              const rowExtra = formatExtra(row.extra);
              const rowSkew = clockSkewSeconds(row);
              return (
                <div
                  key={row.id}
                  className="rounded-lg border border-[var(--ams-border)] p-3 cursor-pointer hover:bg-gray-50"
                  onClick={() => setDetail(row)}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="tabular-nums text-xs text-gray-500">
                      {formatTime(row.createdAt)}
                    </span>
                    <Tag color={LEVEL_COLOR[row.level] ?? 'default'} className="m-0">
                      {LEVEL_LABEL[row.level] ?? row.level}
                    </Tag>
                    <Tag className="m-0">{APP_TYPE_LABEL[row.appType] ?? row.appType}</Tag>
                    <span className="text-xs text-gray-400">
                      {SOURCE_LABEL[row.source] ?? row.source}
                    </span>
                    {rowSkew !== null && rowSkew > CLOCK_SKEW_SECONDS && (
                      <span className="text-xs text-amber-600">⚠ 端侧时钟可能不准</span>
                    )}
                  </div>
                  <div className="text-sm text-gray-800 mt-1.5 break-all">{row.message}</div>
                  {row.url && <div className="text-xs text-gray-400 mt-1 break-all">{row.url}</div>}
                  {rowExtra && (
                    <pre className="m-0 mt-2 max-h-[180px] overflow-auto rounded bg-gray-50 border border-[var(--ams-border)] p-2 text-[11px] leading-relaxed">
                      {rowExtra}
                    </pre>
                  )}
                </div>
              );
            })}
            {traceLoading && <div className="text-center text-xs text-gray-400 py-4">加载中…</div>}
          </div>
        )}
      </Drawer>

      {/* 清理弹窗：`before` 必填（后端也硬校验），提交后再走一次 Modal.confirm */}
      <Modal
        title="清理应用日志"
        open={purgeOpen}
        onOk={() => void handlePurge()}
        onCancel={() => setPurgeOpen(false)}
        confirmLoading={purging}
        okText="下一步"
        okButtonProps={{ danger: true }}
        destroyOnHidden
        centered
        width={520}
      >
        <Form form={purgeForm} layout="vertical" className="mt-2">
          <Form.Item
            name="before"
            label="保留此时间之后的日志"
            rules={[{ required: true, message: '请选择清理时间边界（必填，防止误清全表）' }]}
            extra="严格删除该时间之前创建的日志；请用「时间（服务端）」核对边界"
          >
            <DatePicker showTime format="YYYY-MM-DD HH:mm:ss" className="w-full" />
          </Form.Item>
          {pickEnum(list.filters.appType, APP_TYPES) && (
            <div className="text-xs text-amber-600 bg-amber-50 border border-amber-100 rounded px-2 py-1.5">
              当前列表按「{APP_TYPE_LABEL[list.filters.appType ?? '']}」筛选，清理会只作用于该端。
            </div>
          )}
        </Form>
      </Modal>
    </div>
  );
}
