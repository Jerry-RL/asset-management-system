import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  applyListPatch,
  findReservedFilterKeys,
  readListQuery,
  type ListQueryOptions,
  type ListQueryPatch,
} from '@/lib/listQueryParams';

/**
 * 列表的分页 / 关键字 / 筛选存进 URL，并把它作为**唯一真相**（设计 §5.2）。
 *
 * <p>为什么必须落在 URL：列表页跳到详情/表单是**路由变化**，列表组件会整页卸载，
 * 只存在组件 state 里的页码与筛选无法幸存，返回后必然回到第 1 页、丢掉全部筛选。
 * 附带收益：可刷新保持、链接可分享、浏览器前进后退可用。
 *
 * <p>写入用 `setSearchParams` 的**函数式更新**，让「保留其它参数」的逻辑集中在一处。
 * 但要注意它**不会**让同一事件里的两次写入依次叠加：react-router 是把本次渲染闭包里的
 * `searchParams` 交给更新函数求 `prev`，两次调用都从同一份旧参数出发，**后一次覆盖前一次**。
 * 需要在一次交互里改多个键时，必须合并成**一次** `patch`（或参见 {@link useUrlParams}
 * 的批量写入），不要连调两个 setter（设计 §5.1）。
 */
export function useListQuery(options: ListQueryOptions = {}) {
  const { prefix = '', defaultPageSize = 10, filterKeys = [] } = options;
  const [searchParams, setSearchParams] = useSearchParams();

  // filterKeys 数组每次渲染都是新引用，用它的序列化值做依赖；否则 useMemo 每次都失效、
  // filters 换新对象，调用方的 useEffect 会被无限触发。
  const filterKeysKey = filterKeys.join(',');
  const resolvedFilterKeys = useMemo(
    () => (filterKeysKey ? filterKeysKey.split(',') : []),
    [filterKeysKey],
  );

  // 用 toString() 而不是 searchParams 对象做依赖：react-router 每次导航都会给出新的
  // URLSearchParams 实例，直接依赖它会让「参数其实没变」的重渲染也算作变化。
  const search = searchParams.toString();

  const state = useMemo(
    () =>
      readListQuery(new URLSearchParams(search), {
        prefix,
        defaultPageSize,
        filterKeys: resolvedFilterKeys,
      }),
    [search, prefix, defaultPageSize, resolvedFilterKeys],
  );

  // 只依赖筛选值本身：同一 URL 上的其它参数（view、zoneId 等）变化不该让 filters 换新引用，
  // 否则把 filters 放进拉取 effect 依赖的调用方会被无关参数触发一次多余请求（设计 §6.1）。
  // 签名里带上**键名**（`key=value` 而非仅值）：切换到 filterKeys 不同的资源时，
  // 即便值恰好相同也不会命中上一份缓存的 filters，避免返回过期对象。
  const filtersSignature = resolvedFilterKeys
    .map((key) => `${key}=${state.filters[key] ?? ''}`)
    .join('\u0000');
  const filters = useMemo(
    () => state.filters,
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [filtersSignature],
  );

  // 开发态提示筛选键撞保留字 —— 生产不崩（设计 §8）
  if (import.meta.env?.DEV) {
    const reserved = findReservedFilterKeys(resolvedFilterKeys);
    if (reserved.length > 0) {
      console.warn(`[useListQuery] 筛选键与列表保留字冲突，会被互相覆盖：${reserved.join(', ')}`);
    }
  }

  const patch = useCallback(
    (next: ListQueryPatch, patchOptions: { resetPage?: boolean } = {}) => {
      setSearchParams(
        (prev) =>
          applyListPatch(prev, next, {
            prefix,
            defaultPageSize,
            filterKeys: resolvedFilterKeys,
            resetPage: patchOptions.resetPage,
          }),
        { replace: true },
      );
    },
    [setSearchParams, prefix, defaultPageSize, resolvedFilterKeys],
  );

  const setPage = useCallback((page: number) => patch({ page }), [patch]);
  const setPageSize = useCallback(
    (pageSize: number) => patch({ pageSize }, { resetPage: true }),
    [patch],
  );
  const setKeyword = useCallback(
    (keyword: string) => patch({ keyword }, { resetPage: true }),
    [patch],
  );
  const setFilters = useCallback(
    (filters: Record<string, string>) => patch({ filters }, { resetPage: true }),
    [patch],
  );
  /** 改单个筛选：其余筛选原样保留（`patch.filters` 是完整目标状态） */
  const setFilter = useCallback(
    (key: string, value: string) =>
      patch({ filters: { ...state.filters, [key]: value } }, { resetPage: true }),
    [patch, state.filters],
  );

  return { ...state, filters, patch, setPage, setPageSize, setKeyword, setFilter, setFilters };
}

/** 单值参数的编解码（dayjs、字符串数组等非字符串量） */
export interface UrlParamCodec<T> {
  parse: (raw: string) => T;
  serialize: (value: T) => string;
}

/** 单个键的待写值：`raw === null` 表示该键应被**删除**（写入值等于缺省值或为空） */
export interface UrlParamWrite {
  key: string;
  raw: string | null;
}

type SetSearchParams = ReturnType<typeof useSearchParams>[1];

/**
 * 一次 `setSearchParams` 原子写入多个键 —— `useUrlParam` 与 {@link useUrlParams} 的共用底层。
 *
 * <p>为什么必须「一次写完」：react-router 的 `setSearchParams(fn)` 是把**本次渲染闭包里的
 * `searchParams`** 交给 `fn` 去求 `prev`，所以同一事件里连着调两次 setter，两次都从同一份
 * 旧参数出发 —— **后一次覆盖前一次**，先写的键会丢。凡是「一次交互要改多个键」的场景
 * 都要走这里（设计 §5.1）。
 */
const writeUrlParams = (setSearchParams: SetSearchParams, writes: UrlParamWrite[]) => {
  setSearchParams(
    (prev) => {
      const next = new URLSearchParams(prev);
      for (const { key, raw } of writes) {
        if (raw === null) next.delete(key);
        else next.set(key, raw);
      }
      return next;
    },
    { replace: true },
  );
};

/**
 * 把某个键的新值翻译成待写描述：等于缺省值（或序列化为空串）→ 删除该键。
 * 抽出来是为了让单键写入与 {@link useUrlParams} 的批量写入共用同一套判定，
 * 不产生第二套「什么算缺省」的口径。
 */
const buildWrite = <T>(
  key: string,
  serialize: (value: T) => string,
  defaultSerialized: string,
  next: T,
): UrlParamWrite => {
  const serialized = serialize(next);
  return { key, raw: serialized === defaultSerialized || serialized === '' ? null : serialized };
};

/**
 * 单值 URL 参数：非列表筛选的零散筛选量（项目详情选中的分区、地图选中的城市、
 * 日历的月份与事件类型）。与 {@link useListQuery} 同样的规则：函数式写入、
 * `replace`、等于缺省值就从 URL 删除（设计 §5.1）。
 *
 * <p>返回的第三项 `toWrite` 是给 {@link useUrlParams} 用的：把新值翻译成待写描述，
 * 交给批量写入一次性提交。只改一个键时用第二项 `setValue` 即可，不需要它。
 */
export function useUrlParam(
  key: string,
  defaultValue: string,
): [string, (next: string) => void, (next: string) => UrlParamWrite];
export function useUrlParam<T>(
  key: string,
  defaultValue: T,
  codec: UrlParamCodec<T>,
): [T, (next: T) => void, (next: T) => UrlParamWrite];
export function useUrlParam<T>(key: string, defaultValue: T, codec?: UrlParamCodec<T>) {
  const [searchParams, setSearchParams] = useSearchParams();
  const raw = searchParams.get(key);

  const serialize = useCallback(
    (value: T): string => (codec ? codec.serialize(value) : String(value)),
    [codec],
  );

  /**
   * 值按 `raw` 缓存（**不是**每次渲染重新解析）。带 `codec` 的解析每次都返回新对象
   * （`dayjs`、`split(',')` 的数组），若不缓存，`value` 的引用就一直在变：
   * 把它放进 `useCallback`/`useMemo` 依赖的调用方（如 `OpsCalendarPage` 的 `loadSummary`
   * 依赖 `panelDate`）就会每次渲染都换新函数，再被 `useEffect([loadSummary])` 捕获，
   * 于是「拉取 → setState → 重渲染 → 再拉取」变成**无限请求循环**。
   *
   * <p>因此有两条约定（设计 §5.1）：
   * 1. `defaultValue` 必须是**稳定引用**（模块常量或 `useMemo`），否则这份缓存会随它一起失效
   *    —— `useUrlParam('types', TYPE_OPTIONS.map(...))` 这种写法会把缓存彻底废掉；
   * 2. 调用方不要依赖 `value` 的「每次都是新对象」来做副作用，`raw` 不变它就不变。
   */
  const value = useMemo(
    () => (raw == null ? defaultValue : codec ? codec.parse(raw) : (raw as unknown as T)),
    [raw, codec, defaultValue],
  );
  const defaultSerialized = serialize(defaultValue);

  const toWrite = useCallback(
    (next: T): UrlParamWrite => buildWrite(key, serialize, defaultSerialized, next),
    [key, serialize, defaultSerialized],
  );

  const setValue = useCallback(
    (next: T) => writeUrlParams(setSearchParams, [toWrite(next)]),
    [setSearchParams, toWrite],
  );

  return [value, setValue, toWrite] as [T, (next: T) => void, (next: T) => UrlParamWrite];
}

/**
 * 一次原子写入多个单值参数（`useUrlParam` 的批量版）。配合 `useUrlParam` 的第三项
 * `toWrite` 使用：
 *
 * ```ts
 * const [, , monthWrite] = useUrlParam('month', today, MONTH_CODEC);
 * const [, , dateWrite] = useUrlParam('date', today, DATE_CODEC);
 * const writeParams = useUrlParams();
 * // 一次交互要同时改多个键时，必须合并成一次调用
 * writeParams([monthWrite(day), dateWrite(day)]);
 * ```
 *
 * <p>典型场景：antd `Calendar` 跨月选中某天时会**同时**触发 `onPanelChange` 与 `onSelect`
 * （`generateCalendar.js` 里 `triggerChange` 先调 `triggerPanelChange`、再调 `onSelect`），
 * 若两个 handler 各自写一次 URL，后写的会覆盖先写的、先写的键就丢了。让**最后**那次写入
 * 同时覆盖两个键，结果就与 antd 到底触发几次无关。
 */
export function useUrlParams() {
  const [, setSearchParams] = useSearchParams();
  return useCallback(
    (writes: UrlParamWrite[]) => writeUrlParams(setSearchParams, writes),
    [setSearchParams],
  );
}
