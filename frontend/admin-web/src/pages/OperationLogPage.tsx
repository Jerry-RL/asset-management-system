import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button,
  DatePicker,
  Descriptions,
  Drawer,
  Empty,
  Input,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Tooltip,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { AuditOutlined, CopyOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { Link } from 'react-router-dom';
import { api, type PageResult } from '@/lib/api';
import { useListQuery, useUrlParam, useUrlParams, type UrlParamCodec } from '@/lib/listQuery';

// ============================================================================
// 操作日志（FR-COM-004 / 设计 §6）
//
// 与 /system/app-logs 的区别决定了本页的取舍：
//   - app_log 是**运维排查**（端侧 URL、UA、堆栈），本页是**合规审计**
//     （用户名、IP、接口入参），因此权限点独立（system.operationLog:view）——
//     能看运维日志 ≠ 能看审计数据；
//   - **纯只读，没有清理入口**：用户故事地图明确「操作日志不可删」，NFR-DSEC-016
//     要求保留 ≥3 年。后端也不提供 delete 接口，所以这里没有按钮，也不需要
//     usePermByPath —— 页面级门槛由 AdminLayout 的 <RequirePerm> 按镜像统一把守；
//   - 两个 Tab 各自持有分页/筛选/时间范围（URL 键带前缀），切回来不丢状态；
//   - 首次切到某个 Tab 才发该 Tab 的请求，切回来不重拉。
// ============================================================================

/** 与后端 `OperationLogView` 逐字段对应（`detail` 已在服务端解析为对象） */
interface OperationLogView {
  id: number;
  userId?: number | null;
  username?: string | null;
  module?: string | null;
  action?: string | null;
  /**
   * 被操作对象的 id：URL 里最深的那个 id 型路径变量，或新建时返回实体的 id。
   *
   * `null` 表示推断不出（导入 / 合并 / 清理这类批量操作没有「那个对象」），
   * 后端刻意留空而不是猜一个 —— 地址栏与详情里都不该把它显示成 0。
   */
  refId?: number | null;
  detail?: unknown;
  ip?: string | null;
  traceId?: string | null;
  createdAt?: string | null;
  /** true 成功 / false 失败 / null = V51 之前的存量行（当时未记录成败） */
  success?: boolean | null;
  error?: string | null;
}

/** 与后端 `LoginLogView` 逐字段对应 */
interface LoginLogView {
  id: number;
  userId?: number | null;
  username?: string | null;
  ip?: string | null;
  /** 小写 `success` / `failed`（AuthService 的写入口径，后端等值匹配） */
  result?: string | null;
  /** 语义重载：成功行存**登录方式**，失败行才是失败原因（见 LOGIN_METHOD_LABEL） */
  failReason?: string | null;
  createdAt?: string | null;
}

type TabKey = 'op' | 'login';

/** 缺省 `op`：`useUrlParam` 的 buildWrite 会在值等于缺省时**删除**该键，故地址栏保持干净 */
const DEFAULT_TAB: TabKey = 'op';
const pickTab = (raw: string | undefined): TabKey => (raw === 'login' ? 'login' : 'op');

/**
 * 登录方式文案。
 *
 * <p>`login_log.fail_reason` 是**语义重载**的列：登录成功时 AuthService 往里写登录方式
 * （`wechat` / `wechat_bind` / `wechat_bind_worker`），失败时写失败原因。
 * 这是既有数据模型的问题，改表属独立决策（设计 §1.3），本页如实分列展示。
 */
const LOGIN_METHOD_LABEL: Record<string, string> = {
  wechat: '微信登录',
  wechat_bind: '微信绑定登录（租户端）',
  wechat_bind_worker: '微信绑定登录（员工端）',
};

/** 结果筛选枚举。操作日志是三态（含「未知」），登录日志是二态。 */
const SUCCESS_OPTIONS = [
  { value: '', label: '全部结果' },
  { value: 'true', label: '成功' },
  { value: 'false', label: '失败' },
];
const LOGIN_RESULT_OPTIONS = [
  { value: '', label: '全部结果' },
  { value: 'success', label: '成功' },
  { value: 'failed', label: '失败' },
];

/**
 * `success` 的 URL 值 → 后端布尔参数。
 *
 * <p>白名单之外（手改 URL，如 `?opSuccess=1`）一律当「没选」：原样发给后端只会得到 400，
 * 而筛选框显示「全部」—— 这是最难自查的一类「页面看起来正常但查不出东西」。
 */
const parseSuccess = (raw: string | undefined): boolean | undefined =>
  raw === 'true' ? true : raw === 'false' ? false : undefined;

/** `result` 白名单：只有两个**小写**值（大小写错配即忽略，归一化会掩盖前端传错大小写） */
const pickLoginResult = (raw: string | undefined): string | undefined =>
  raw === 'success' || raw === 'failed' ? raw : undefined;

/**
 * `refId` 白名单：只接受**正整数**。
 *
 * <p>0 / 负数 / 非数字（手改 URL，如 `?opRefId=abc`）一律当「没选」，与后端
 * `AuditLogService` 的口径一致 —— 两端口径不一致时会出现最难自查的一类现象：
 * 筛选框显示已填、列表却是空的。
 */
const pickPositiveInt = (raw: string | undefined): string | undefined => {
  if (!raw || !/^\d+$/.test(raw)) return undefined;
  return Number(raw) > 0 ? raw : undefined;
};

const STAMP_FORMAT = 'YYYY-MM-DDTHH:mm:ss';
const stamp = (value: Dayjs): string => value.format(STAMP_FORMAT);
const stampToParam = (raw: string | null): Dayjs | null => {
  if (!raw) return null;
  const parsed = dayjs(raw, STAMP_FORMAT);
  return parsed.isValid() ? parsed : null;
};

/**
 * 时间格式：**ISO 本地时间，不带时区后缀**。
 *
 * <p>为什么不带 `Z`：后端参数是 `LocalDateTime`，而 `LocalDateTime.from()` 会
 * **静默丢弃** 偏移量（不报错、也不换算）。送 `...Z` 不会 400，但查询窗口会整体偏移几小时 ——
 * 后者比报错更难发现。全仓现有接口（app-logs、ops-calendar）同样只传本地时间。
 */
const STAMP_CODEC: UrlParamCodec<Dayjs | null> = {
  parse: stampToParam,
  serialize: (value) => (value ? stamp(value) : ''),
};

const DEFAULT_RANGE = '24h';
const RANGE_OPTIONS = [
  { value: '1h', label: '近 1 小时' },
  { value: '24h', label: '近 24 小时' },
  { value: '7d', label: '近 7 天' },
  { value: '30d', label: '近 30 天' },
  { value: 'custom', label: '自定义…' },
  { value: 'all', label: '全部时间' },
];
const RANGE_HOURS: Record<string, number> = { '1h': 1, '24h': 24, '7d': 24 * 7, '30d': 24 * 30 };

/**
 * 解析本次查询的时间窗。
 *
 * <p>**每次 `load()` 现算而不是 `useMemo` 出来**：预设的含义是「相对此刻」，
 * 挂载时算一次的话，页面开着两小时后点刷新拿到的还是两小时前的窗口，
 * 而用户看到「近 1 小时」时会以为刷新能拿回刚刚发生的事。
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
  return hours ? { from: stamp(dayjs().subtract(hours, 'hour')) } : {};
};

const formatTime = (value?: string | null): string =>
  value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';

const copyText = async (text: string, label: string) => {
  try {
    await navigator.clipboard.writeText(text);
    message.success(`${label}已复制`);
  } catch {
    message.warning('浏览器拒绝了剪贴板访问，请手动选中复制');
  }
};

/** 格式化 `detail`：无法序列化时退回原始字符串，不抛错 */
const formatDetail = (detail: unknown): string => {
  if (detail == null) return '';
  if (typeof detail === 'string') return detail;
  try {
    return JSON.stringify(detail, null, 2);
  } catch {
    return String(detail);
  }
};

/**
 * 「对象 ID」筛选：只接受**正整数**，且框里显示的始终就是**实际生效**的条件。
 *
 * <p>为什么不直接用 {@link useDraftFilter}：它只在 `committed` 变化时回写草稿，
 * 而「输入 `0` 或非数字 → 归一化成空」这一步可能**不改变** `committed`（本来就是空），
 * 于是草稿会留在框里 —— 表现为「框里写着 0，列表却什么都没筛」，
 * 也就是 §6.2 说的那类最难自查的状态。
 */
const useRefIdFilter = (committed: string, commit: (value: string) => void) => {
  const [draft, setDraft] = useState(committed);
  useEffect(() => {
    setDraft(committed);
  }, [committed]);
  return {
    draft,
    handleChange: (next: string) => {
      // 输入时就过滤掉非数字：非法字符进不到草稿里，也就不存在「提交后被丢弃」这一步
      const digits = next.replace(/\D/g, '');
      setDraft(digits);
      if (digits === '') commit('');
    },
    handleCommit: () => {
      const normalized = pickPositiveInt(draft) ?? '';
      setDraft(normalized);
      commit(normalized);
    },
  };
};

/**
 * 「回车才提交」的文本筛选输入框（与 AppLogPage 同款）。
 *
 * <p>为什么不 `onChange` 就查：`keyword` 在后端是 LIKE，逐字符触发等于把每一次按键
 * 变成一次查询。清空则**立即**提交 —— 用户点 X 时期待的是「马上看全部」。
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

/** 结果列三态。NULL 显示「—」并说明是历史存量行，**不能显示成「失败」**。 */
const ResultTag = ({ success }: { success?: boolean | null }) => {
  if (success === true) {
    return (
      <Tag color="success" className="m-0">
        成功
      </Tag>
    );
  }
  if (success === false) {
    return (
      <Tag color="error" className="m-0">
        失败
      </Tag>
    );
  }
  return (
    <Tooltip title="此记录早于成败字段上线，当时未记录，无法追溯">
      <Tag className="m-0 text-gray-400">—</Tag>
    </Tooltip>
  );
};

/**
 * 时间范围筛选。两个 Tab 外观一致，但落在**各自独立**的 URL 键上
 * （`opRange`/`opFrom`/`opTo` 与 `loginRange`/`loginFrom`/`loginTo`），
 * 因此切 Tab 不会把对方的时间窗覆盖掉。
 */
const TimeRangeFilter = ({
  range,
  from,
  to,
  onRangeChange,
  onCustomChange,
}: {
  range: string;
  from: Dayjs | null;
  to: Dayjs | null;
  onRangeChange: (next: string) => void;
  onCustomChange: (values: [Dayjs | null, Dayjs | null] | null) => void;
}) => (
  <>
    <Select
      className="!w-[150px]"
      value={range}
      options={RANGE_OPTIONS}
      onChange={onRangeChange}
      aria-label="时间范围"
    />
    {range === 'custom' && (
      <DatePicker.RangePicker
        showTime
        allowEmpty={[true, true]}
        value={[from, to]}
        onChange={onCustomChange}
        aria-label="自定义时间范围"
      />
    )}
  </>
);

export function OperationLogPage() {
  const [tab, , tabWrite] = useUrlParam('tab', DEFAULT_TAB);
  const activeTab = pickTab(tab);

  // 两个 Tab 各用带前缀的 useListQuery：URL 键自动拼成 opPage / opKeyword / loginPage / …
  // （listQueryParams.buildKey）。两套键互不覆盖，因此各 Tab 的分页与筛选都能独立还原。
  const opList = useListQuery({
    prefix: 'op',
    filterKeys: ['username', 'module', 'success', 'traceId', 'refId'],
    defaultPageSize: 20,
  });
  const loginList = useListQuery({
    prefix: 'login',
    filterKeys: ['username', 'result', 'ip'],
    defaultPageSize: 20,
  });

  const [opRange, , opRangeWrite] = useUrlParam('opRange', DEFAULT_RANGE);
  const [opFrom, , opFromWrite] = useUrlParam<Dayjs | null>('opFrom', null, STAMP_CODEC);
  const [opTo, , opToWrite] = useUrlParam<Dayjs | null>('opTo', null, STAMP_CODEC);
  const [loginRange, , loginRangeWrite] = useUrlParam('loginRange', DEFAULT_RANGE);
  const [loginFrom, , loginFromWrite] = useUrlParam<Dayjs | null>('loginFrom', null, STAMP_CODEC);
  const [loginTo, , loginToWrite] = useUrlParam<Dayjs | null>('loginTo', null, STAMP_CODEC);
  const writeParams = useUrlParams();

  // 先解构出稳定的原始值：`opList` 每次渲染都是新对象，整个放进 useCallback 依赖
  // 会让 load 每次换新引用，再被 useEffect 捕获 → 无限请求循环
  const {
    page: opPage,
    pageSize: opPageSize,
    keyword: opKeyword,
    filters: opFilters,
    setPage: setOpPage,
    setPageSize: setOpPageSize,
    setKeyword: setOpKeyword,
    setFilter: setOpFilter,
  } = opList;
  const {
    page: loginPage,
    pageSize: loginPageSize,
    filters: loginFilters,
    setPage: setLoginPage,
    setPageSize: setLoginPageSize,
    setFilter: setLoginFilter,
  } = loginList;

  const opUsername = opFilters.username ?? '';
  const opModule = opFilters.module ?? '';
  const opSuccessRaw = opFilters.success ?? '';
  const opTraceId = opFilters.traceId ?? '';
  const opSuccess = parseSuccess(opSuccessRaw);
  const opRefId = pickPositiveInt(opFilters.refId);

  const loginUsername = loginFilters.username ?? '';
  const loginResultRaw = loginFilters.result ?? '';
  const loginIp = loginFilters.ip ?? '';
  const loginResult = pickLoginResult(loginResultRaw);

  const [moduleOptions, setModuleOptions] = useState<string[]>([]);
  const [opRows, setOpRows] = useState<OperationLogView[]>([]);
  const [opTotal, setOpTotal] = useState(0);
  const [opLoading, setOpLoading] = useState(false);
  const [loginRows, setLoginRows] = useState<LoginLogView[]>([]);
  const [loginTotal, setLoginTotal] = useState(0);
  const [loginLoading, setLoginLoading] = useState(false);
  const [detail, setDetail] = useState<OperationLogView | null>(null);

  // 懒加载：首次切到某 Tab 才标记为已访问，之后切回来不重拉（load 的依赖不变则 effect 不触发）
  const [opVisited, setOpVisited] = useState(false);
  const [loginVisited, setLoginVisited] = useState(false);
  useEffect(() => {
    if (activeTab === 'op') setOpVisited(true);
    else setLoginVisited(true);
  }, [activeTab]);

  const loadModules = useCallback(async () => {
    try {
      const data = await api.get<string[]>('/system/operation-logs/modules');
      setModuleOptions(data ?? []);
    } catch {
      // 模块下拉是辅助控件：拿不到就退化成空列表，用户仍可用其它条件查询，
      // 不该因此让整页报错（与 app-logs 的统计条同一取向）
      setModuleOptions([]);
    }
  }, []);

  const loadOp = useCallback(async () => {
    const { from, to } = resolveWindow(opRange, opFrom, opTo);
    const params = new URLSearchParams({
      page: String(opPage),
      pageSize: String(opPageSize),
    });
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    if (opUsername) params.set('username', opUsername);
    if (opModule) params.set('module', opModule);
    // success 缺省时**不传**：后端不过滤，因此 V51 之前 success IS NULL 的存量行照常出现
    if (opSuccess !== undefined) params.set('success', String(opSuccess));
    if (opKeyword) params.set('keyword', opKeyword);
    if (opTraceId) params.set('traceId', opTraceId);
    // refId 是本页最常用的提问方式（「这个对象上发生过什么」）：等值命中 ref_id，
    // 而 ref_id 由后端推断写入，约 90% 的审计行有值
    if (opRefId) params.set('refId', opRefId);

    setOpLoading(true);
    try {
      const data = await api.get<PageResult<OperationLogView>>(
        `/system/operation-logs?${params.toString()}`,
      );
      setOpRows(data?.list ?? []);
      setOpTotal(data?.total ?? 0);
    } catch (e) {
      setOpRows([]);
      setOpTotal(0);
      message.error(e instanceof Error ? e.message : '加载操作日志失败');
    } finally {
      setOpLoading(false);
    }
  }, [
    opPage,
    opPageSize,
    opKeyword,
    opUsername,
    opModule,
    opSuccess,
    opTraceId,
    opRefId,
    opRange,
    opFrom,
    opTo,
  ]);

  const loadLogin = useCallback(async () => {
    const { from, to } = resolveWindow(loginRange, loginFrom, loginTo);
    const params = new URLSearchParams({
      page: String(loginPage),
      pageSize: String(loginPageSize),
    });
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    if (loginUsername) params.set('username', loginUsername);
    if (loginResult) params.set('result', loginResult);
    if (loginIp) params.set('ip', loginIp);

    setLoginLoading(true);
    try {
      const data = await api.get<PageResult<LoginLogView>>(
        `/system/login-logs?${params.toString()}`,
      );
      setLoginRows(data?.list ?? []);
      setLoginTotal(data?.total ?? 0);
    } catch (e) {
      setLoginRows([]);
      setLoginTotal(0);
      message.error(e instanceof Error ? e.message : '加载登录日志失败');
    } finally {
      setLoginLoading(false);
    }
  }, [
    loginPage,
    loginPageSize,
    loginUsername,
    loginResult,
    loginIp,
    loginRange,
    loginFrom,
    loginTo,
  ]);

  useEffect(() => {
    if (opVisited) void loadOp();
  }, [opVisited, loadOp]);

  useEffect(() => {
    if (loginVisited) void loadLogin();
  }, [loginVisited, loadLogin]);

  useEffect(() => {
    // 模块清单只在操作日志 Tab 用得到
    if (opVisited) void loadModules();
  }, [opVisited, loadModules]);

  const handleRefresh = () => {
    if (activeTab === 'op') {
      void loadOp();
      void loadModules();
    } else {
      void loadLogin();
    }
  };

  /**
   * 换 Tab：**一次写完所有键**。
   *
   * <p>react-router 的 `setSearchParams(fn)` 把本次渲染闭包里的 `searchParams` 交给 `fn` 求
   * `prev`，同一事件里连调两次 setter 会互相覆盖（listQuery.ts 顶部已写死）。
   * 这里虽然只改 `tab` 一个键，仍走批量写入以保持单一入口。
   */
  const handleTabChange = (next: string) => {
    writeParams([tabWrite(pickTab(next))]);
  };

  /**
   * 换时间范围：范围值 + 两个边界 + 页码**必须合并成一次写入**。
   *
   * <p>「先 setValue(range) 再 setPage(1)」在 react-router 下会丢掉先写的键。
   */
  const handleOpRangeChange = (next: string) => {
    const writes = [opRangeWrite(next)];
    if (next !== 'custom') {
      // 离开自定义就清边界，否则下次切回自定义会看到上一次的残留区间
      writes.push(opFromWrite(null), opToWrite(null));
    }
    writes.push({ key: 'opPage', raw: null });
    writeParams(writes);
  };
  const handleOpCustomRangeChange = (values: [Dayjs | null, Dayjs | null] | null) => {
    const [start, end] = values ?? [null, null];
    writeParams([
      opRangeWrite('custom'),
      opFromWrite(start ?? null),
      opToWrite(end ?? null),
      { key: 'opPage', raw: null },
    ]);
  };
  const handleLoginRangeChange = (next: string) => {
    const writes = [loginRangeWrite(next)];
    if (next !== 'custom') {
      writes.push(loginFromWrite(null), loginToWrite(null));
    }
    writes.push({ key: 'loginPage', raw: null });
    writeParams(writes);
  };
  const handleLoginCustomRangeChange = (values: [Dayjs | null, Dayjs | null] | null) => {
    const [start, end] = values ?? [null, null];
    writeParams([
      loginRangeWrite('custom'),
      loginFromWrite(start ?? null),
      loginToWrite(end ?? null),
      { key: 'loginPage', raw: null },
    ]);
  };

  const opUsernameInput = useDraftFilter(opUsername, (value) => setOpFilter('username', value));
  const opTraceIdInput = useDraftFilter(opTraceId, (value) => setOpFilter('traceId', value));
  // 传归一化后的 opRefId（而不是 URL 里的原始值）：手改 URL 传进非法值时，
  // 框里显示空 —— 与「该筛选未生效」的事实一致
  const opRefIdInput = useRefIdFilter(opRefId ?? '', (value) => setOpFilter('refId', value));
  const loginUsernameInput = useDraftFilter(loginUsername, (value) =>
    setLoginFilter('username', value),
  );
  const loginIpInput = useDraftFilter(loginIp, (value) => setLoginFilter('ip', value));

  const handleOpenAppLog = (value: string) => {
    // 跨页互链：同一 traceId 在运维日志里能看到端侧报错与后端堆栈，
    // 审计行只回答「谁做了什么」，两者拼起来才是完整链路
    window.open(`/system/app-logs?traceId=${encodeURIComponent(value)}`, '_blank', 'noopener');
  };

  /**
   * 从详情抽屉钻取到「这个对象上发生过什么」。
   *
   * <p>这是 `ref_id` 存在的全部意义：审计最常用的提问方式不是「搜关键字」，
   * 而是「这个资产/项目被人动过什么」。`setFilter` 自带回到第 1 页，
   * 因此一次调用即可（连调 setter 在 react-router 下会互相覆盖，见 listQuery.ts 顶部）。
   */
  const handleDrillByRefId = (refId: number) => {
    setOpFilter('refId', String(refId));
    setDetail(null);
  };

  const opColumns: ColumnsType<OperationLogView> = useMemo(
    () => [
      {
        title: '时间',
        dataIndex: 'createdAt',
        key: 'createdAt',
        width: 170,
        render: (_, row) => (
          <span className="tabular-nums text-gray-600">{formatTime(row.createdAt)}</span>
        ),
      },
      {
        title: '用户',
        dataIndex: 'username',
        key: 'username',
        width: 140,
        render: (_, row) =>
          row.username ? (
            <span>{row.username}</span>
          ) : (
            <Tooltip title="无登录主体的操作（如定时任务）">
              <span className="text-gray-400">系统</span>
            </Tooltip>
          ),
      },
      {
        title: '模块',
        dataIndex: 'module',
        key: 'module',
        width: 130,
        render: (_, row) => row.module || <span className="text-gray-300">-</span>,
      },
      {
        title: '动作',
        dataIndex: 'action',
        key: 'action',
        width: 170,
        render: (_, row) => <span className="font-mono text-xs">{row.action || '-'}</span>,
      },
      {
        title: '结果',
        dataIndex: 'success',
        key: 'success',
        width: 80,
        render: (_, row) => <ResultTag success={row.success} />,
      },
      {
        title: 'IP',
        dataIndex: 'ip',
        key: 'ip',
        width: 130,
        render: (_, row) => <span className="font-mono text-xs">{row.ip || '-'}</span>,
      },
      {
        title: 'TraceId',
        dataIndex: 'traceId',
        key: 'traceId',
        width: 130,
        render: (_, row) =>
          row.traceId ? (
            <Button
              type="link"
              size="small"
              className="p-0 h-auto font-mono text-xs"
              // 阻止冒泡：否则点 TraceId 会同时打开详情抽屉，两个抽屉叠在一起
              onClick={(e) => {
                e.stopPropagation();
                handleOpenAppLog(row.traceId as string);
              }}
            >
              {row.traceId.slice(0, 8)}…
            </Button>
          ) : (
            <span className="text-gray-300">-</span>
          ),
      },
    ],
    [],
  );

  const loginColumns: ColumnsType<LoginLogView> = useMemo(
    () => [
      {
        title: '时间',
        dataIndex: 'createdAt',
        key: 'createdAt',
        width: 170,
        render: (_, row) => (
          <span className="tabular-nums text-gray-600">{formatTime(row.createdAt)}</span>
        ),
      },
      {
        title: '用户名',
        dataIndex: 'username',
        key: 'username',
        width: 160,
        render: (_, row) => row.username || <span className="text-gray-300">-</span>,
      },
      {
        title: '用户 ID',
        dataIndex: 'userId',
        key: 'userId',
        width: 100,
        render: (_, row) =>
          row.userId != null ? (
            <span className="tabular-nums text-gray-500">#{row.userId}</span>
          ) : (
            // 登录失败且账号不存在时没有 userId，这是正常的，不该显示成缺失的 ID
            <span className="text-gray-300">-</span>
          ),
      },
      {
        title: '结果',
        dataIndex: 'result',
        key: 'result',
        width: 90,
        render: (_, row) =>
          row.result === 'success' ? (
            <Tag color="success" className="m-0">
              成功
            </Tag>
          ) : (
            <Tag color="error" className="m-0">
              失败
            </Tag>
          ),
      },
      {
        title: (
          <Tooltip title="该列语义由既有数据模型决定：登录成功时记录登录方式，登录失败时记录失败原因。">
            <span className="border-b border-dashed border-gray-400 cursor-help">原因 / 方式</span>
          </Tooltip>
        ),
        dataIndex: 'failReason',
        key: 'failReason',
        render: (_, row) => {
          if (!row.failReason) return <span className="text-gray-300">-</span>;
          if (row.result === 'success') {
            return (
              <span>
                {LOGIN_METHOD_LABEL[row.failReason] ?? row.failReason}
                <span className="text-gray-400 ml-1 text-xs">（{row.failReason}）</span>
              </span>
            );
          }
          return <span className="text-red-600">{row.failReason}</span>;
        },
      },
      {
        title: 'IP',
        dataIndex: 'ip',
        key: 'ip',
        width: 150,
        render: (_, row) => <span className="font-mono text-xs">{row.ip || '-'}</span>,
      },
    ],
    [],
  );

  const detailJson = detail ? formatDetail(detail.detail) : '';

  return (
    <div className="space-y-4 min-w-0 max-w-full overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold text-gray-900 flex items-center gap-2 m-0">
            <AuditOutlined className="text-[var(--ams-primary)]" />
            操作日志
          </h1>
          <p className="text-sm text-gray-500 mt-1 mb-0">
            谁在什么时间动了哪块数据、谁登录了系统。审计留痕只读，不提供删除
          </p>
        </div>
        <Space wrap size={[8, 8]}>
          <Button
            icon={<ReloadOutlined />}
            onClick={handleRefresh}
            loading={opLoading || loginLoading}
          >
            刷新
          </Button>
        </Space>
      </div>

      <div className="bg-white rounded-xl border border-[var(--ams-border)] p-3 sm:p-4">
        {/* Tabs 只当分段控件用（不挂 children）：两个 Tab 的筛选与表格差异较大，
            自渲染可以让 URL 草稿、懒加载与分页各自独立，不会出现两个面板同时挂载 */}
        <Tabs
          activeKey={activeTab}
          onChange={handleTabChange}
          items={[
            { key: 'op', label: '操作日志' },
            { key: 'login', label: '登录日志' },
          ]}
        />

        {activeTab === 'op' ? (
          <>
            <div className="flex flex-wrap items-center gap-2 mb-3">
              <TimeRangeFilter
                range={opRange}
                from={opFrom}
                to={opTo}
                onRangeChange={handleOpRangeChange}
                onCustomChange={handleOpCustomRangeChange}
              />
              <Input
                className="!w-[160px]"
                allowClear
                placeholder="用户名（回车）"
                value={opUsernameInput.draft}
                onChange={(e) => opUsernameInput.handleChange(e.target.value)}
                onPressEnter={opUsernameInput.handleCommit}
              />
              <Select
                className="!w-[170px]"
                allowClear
                showSearch
                placeholder="全部模块"
                value={opModule || undefined}
                options={moduleOptions.map((value) => ({ value, label: value }))}
                onChange={(value?: string) => setOpFilter('module', value ?? '')}
              />
              <Select
                className="!w-[130px]"
                value={opSuccessRaw}
                options={SUCCESS_OPTIONS}
                onChange={(value: string) => setOpFilter('success', value)}
                aria-label="结果筛选"
              />
              <Input
                className="!w-[250px]"
                allowClear
                placeholder="搜索用户名 / 动作 / 入参（回车）"
                value={opKeyword}
                prefix={<SearchOutlined className="text-gray-300" />}
                onChange={(e) => setOpKeyword(e.target.value)}
              />
              <Input
                className="!w-[240px]"
                allowClear
                placeholder="TraceId 精确查询（回车）"
                value={opTraceIdInput.draft}
                onChange={(e) => opTraceIdInput.handleChange(e.target.value)}
                onPressEnter={opTraceIdInput.handleCommit}
              />
              <Input
                className="!w-[170px]"
                allowClear
                inputMode="numeric"
                placeholder="对象 ID 精确查询（回车）"
                value={opRefIdInput.draft}
                onChange={(e) => opRefIdInput.handleChange(e.target.value)}
                onPressEnter={opRefIdInput.handleCommit}
              />
            </div>

            <div className="ams-table-wrap">
              <Table
                rowKey="id"
                size="small"
                loading={opLoading}
                columns={opColumns}
                dataSource={opRows}
                scroll={{ x: 950 }}
                locale={{
                  emptyText: (
                    <Empty
                      image={Empty.PRESENTED_IMAGE_SIMPLE}
                      description="该条件下暂无操作记录"
                    />
                  ),
                }}
                onRow={(row) => ({
                  // 整行可点开详情：审计字段多，表格塞不下，而排查时几乎总要看到入参
                  onClick: () => setDetail(row),
                  style: { cursor: 'pointer' },
                })}
                pagination={{
                  current: opPage,
                  pageSize: opPageSize,
                  total: opTotal,
                  showSizeChanger: true,
                  pageSizeOptions: ['20', '50', '100'],
                  showTotal: (value) => `共 ${value} 条`,
                  onChange: (nextPage, nextPageSize) => {
                    // pageSize 变化时 useListQuery 会自动回到第 1 页，所以两个值一起交给它，
                    // 不能分开调两次 setter（第二次会覆盖第一次）
                    if (nextPageSize !== opPageSize) setOpPageSize(nextPageSize);
                    else setOpPage(nextPage);
                  },
                }}
              />
            </div>
          </>
        ) : (
          <>
            <div className="flex flex-wrap items-center gap-2 mb-3">
              <TimeRangeFilter
                range={loginRange}
                from={loginFrom}
                to={loginTo}
                onRangeChange={handleLoginRangeChange}
                onCustomChange={handleLoginCustomRangeChange}
              />
              <Input
                className="!w-[160px]"
                allowClear
                placeholder="用户名（回车）"
                value={loginUsernameInput.draft}
                onChange={(e) => loginUsernameInput.handleChange(e.target.value)}
                onPressEnter={loginUsernameInput.handleCommit}
              />
              <Select
                className="!w-[130px]"
                value={loginResultRaw}
                options={LOGIN_RESULT_OPTIONS}
                onChange={(value: string) => setLoginFilter('result', value)}
                aria-label="结果筛选"
              />
              <Input
                className="!w-[180px]"
                allowClear
                placeholder="IP 精确查询（回车）"
                value={loginIpInput.draft}
                onChange={(e) => loginIpInput.handleChange(e.target.value)}
                onPressEnter={loginIpInput.handleCommit}
              />
            </div>

            <div className="ams-table-wrap">
              <Table
                rowKey="id"
                size="small"
                loading={loginLoading}
                columns={loginColumns}
                dataSource={loginRows}
                scroll={{ x: 800 }}
                locale={{
                  emptyText: (
                    <Empty
                      image={Empty.PRESENTED_IMAGE_SIMPLE}
                      description="该条件下暂无登录记录"
                    />
                  ),
                }}
                pagination={{
                  current: loginPage,
                  pageSize: loginPageSize,
                  total: loginTotal,
                  showSizeChanger: true,
                  pageSizeOptions: ['20', '50', '100'],
                  showTotal: (value) => `共 ${value} 条`,
                  onChange: (nextPage, nextPageSize) => {
                    if (nextPageSize !== loginPageSize) setLoginPageSize(nextPageSize);
                    else setLoginPage(nextPage);
                  },
                }}
              />
            </div>
          </>
        )}
      </div>

      {/* 详情抽屉：单条全字段 + detail 格式化 JSON + 失败原因 */}
      <Drawer
        title="操作详情"
        open={!!detail}
        onClose={() => setDetail(null)}
        width={Math.min(680, typeof window !== 'undefined' ? window.innerWidth - 32 : 680)}
        destroyOnHidden
      >
        {detail && (
          <div className="space-y-3">
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="时间">{formatTime(detail.createdAt)}</Descriptions.Item>
              <Descriptions.Item label="用户">
                {detail.username || '系统（无登录主体）'}
                {detail.userId != null && (
                  <span className="text-gray-400 ml-1 text-xs">#{detail.userId}</span>
                )}
              </Descriptions.Item>
              <Descriptions.Item label="模块">{detail.module || '-'}</Descriptions.Item>
              <Descriptions.Item label="动作">
                <span className="font-mono text-xs">{detail.action || '-'}</span>
              </Descriptions.Item>
              <Descriptions.Item label="对象 ID">
                {detail.refId != null ? (
                  <Space size={4}>
                    <span className="font-mono text-xs">#{detail.refId}</span>
                    <Tooltip title="按此对象筛选出它的全部操作记录：同一个资产/项目上「谁在什么时候动过什么」">
                      <Button
                        size="small"
                        type="link"
                        className="p-0 h-auto"
                        onClick={() => handleDrillByRefId(detail.refId as number)}
                      >
                        查它的全部操作
                      </Button>
                    </Tooltip>
                  </Space>
                ) : (
                  <Tooltip title="导入 / 合并 / 清理这类批量操作没有单一对象，后端刻意留空而不是猜一个">
                    <span className="text-gray-400">—</span>
                  </Tooltip>
                )}
              </Descriptions.Item>
              <Descriptions.Item label="结果">
                <ResultTag success={detail.success} />
                {detail.error && <span className="break-all ml-2">{detail.error}</span>}
              </Descriptions.Item>
              <Descriptions.Item label="IP">
                <span className="font-mono text-xs">{detail.ip || '-'}</span>
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
                      <Button
                        size="small"
                        onClick={() => handleOpenAppLog(detail.traceId as string)}
                      >
                        查看运维日志
                      </Button>
                    </>
                  )}
                </Space>
              </Descriptions.Item>
            </Descriptions>

            <div>
              <div className="flex items-center justify-between mb-1">
                <span className="text-sm font-medium text-gray-700">入参（已脱敏）</span>
                {detailJson && (
                  <Button
                    size="small"
                    icon={<CopyOutlined />}
                    onClick={() => void copyText(detailJson, '入参')}
                  >
                    复制
                  </Button>
                )}
              </div>
              {detailJson ? (
                <pre className="m-0 max-h-[420px] overflow-auto rounded bg-gray-50 border border-[var(--ams-border)] p-3 text-xs leading-relaxed">
                  {detailJson}
                </pre>
              ) : (
                <div className="text-xs text-gray-400 py-2">无入参记录</div>
              )}
            </div>

            {detail.success === false && (
              <div className="text-xs text-gray-500 bg-gray-50 border border-[var(--ams-border)] rounded px-2 py-1.5">
                失败的操作<b>同样</b>会被记录：审计关心的不只是「改成了什么」，
                也包括「谁在什么时候尝试过、以及为什么没成功」。
              </div>
            )}

            {detail.traceId && (
              <div className="text-xs text-gray-400">
                想串联端侧报错与后端异常？去
                <Link
                  className="mx-1"
                  to={`/system/app-logs?traceId=${encodeURIComponent(detail.traceId)}`}
                >
                  应用日志
                </Link>
                按同一 TraceId 查全链路。
              </div>
            )}
          </div>
        )}
      </Drawer>
    </div>
  );
}
