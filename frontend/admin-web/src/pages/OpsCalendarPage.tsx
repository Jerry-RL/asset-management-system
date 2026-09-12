import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { currentPath } from '@/lib/navigation';
import { useUrlParam, useUrlParams, type UrlParamCodec } from '@/lib/listQuery';
import {
  Badge,
  Button,
  Calendar,
  Checkbox,
  DatePicker,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Tag,
  message,
} from 'antd';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import { CalendarOutlined, PlusOutlined, ReloadOutlined, RightOutlined } from '@ant-design/icons';
import { api } from '@/lib/api';

export interface OpsCalendarEvent {
  id: string;
  type: string;
  title: string;
  summary?: string;
  eventDate: string;
  level: number;
  status?: string;
  bizType?: string;
  bizId?: number;
  linkPath?: string;
}

interface DayCell {
  date: string;
  total: number;
  high: number;
  byType: Record<string, number>;
}

interface DaySummary {
  from: string;
  to: string;
  total: number;
  days: DayCell[];
}

const TYPE_OPTIONS: { value: string; label: string; color: string }[] = [
  { value: 'contract_expiry', label: '合同到期', color: '#1677ff' },
  { value: 'bill_due', label: '账单应收', color: '#52c41a' },
  { value: 'task_deadline', label: '任务截止', color: '#fa8c16' },
  { value: 'mortgage_expiry', label: '抵押到期', color: '#eb2f96' },
  { value: 'occupation_end', label: '占用到期', color: '#13c2c2' },
  { value: 'self_use_end', label: '自用到期', color: '#722ed1' },
  { value: 'inspection', label: '巡检计划', color: '#2f54eb' },
  { value: 'repair_sla', label: '报修 SLA', color: '#f5222d' },
  { value: 'note', label: '手工提醒', color: '#595959' },
];

const TYPE_MAP = Object.fromEntries(TYPE_OPTIONS.map((t) => [t.value, t]));

/** 事件类型的**全选**集合：模块级常量，保证 useUrlParam 的默认值引用稳定 */
const ALL_TYPES = TYPE_OPTIONS.map((t) => t.value);

/**
 * 严格解析日期：本仓未加载 dayjs 的 customParseFormat 插件，`dayjs(raw, fmt, true)`
 * 的第三参数不生效，且宽松解析会把 `2026-13`「修」成 2027-01。
 * 用「格式化回写后与原文一致」做严格性判定，不依赖插件。
 */
const parseStrict = (raw: string, format: 'YYYY-MM' | 'YYYY-MM-DD'): Dayjs | null => {
  const value = dayjs(raw);
  if (!value.isValid()) return null;
  return value.format(format) === raw ? value : null;
};

/** 月份：`2026-09`；非法值回落当天 */
const MONTH_CODEC: UrlParamCodec<Dayjs> = {
  parse: (raw) => parseStrict(raw, 'YYYY-MM') ?? dayjs(),
  serialize: (value) => value.format('YYYY-MM'),
};

/** 选中日期：`2026-09-12`；非法值回落当天 */
const DATE_CODEC: UrlParamCodec<Dayjs> = {
  parse: (raw) => parseStrict(raw, 'YYYY-MM-DD') ?? dayjs(),
  serialize: (value) => value.format('YYYY-MM-DD'),
};

/**
 * 事件类型：逗号分隔；**缺省（参数不存在）= 全选**，`none` = 一个都不选。
 *
 * <p>用显式的 `none` 而不是空串：空串在 URL 上既是「没选」又像「没写」，
 * 与「缺省即全选」无法区分（设计 §8 要求不写空值参数）。
 */
const TYPES_CODEC: UrlParamCodec<string[]> = {
  parse: (raw) => {
    if (raw === 'none') return [];
    const known = raw.split(',').filter((value) => TYPE_MAP[value] != null);
    return known.length > 0 ? known : ALL_TYPES;
  },
  serialize: (value) => (value.length === 0 ? 'none' : value.join(',')),
};

const levelColor = (level: number) => (level >= 3 ? 'red' : level === 2 ? 'orange' : 'blue');

const monthRange = (d: Dayjs) => ({
  from: d.startOf('month').format('YYYY-MM-DD'),
  to: d.endOf('month').format('YYYY-MM-DD'),
});

export function OpsCalendarPage() {
  /**
   * 面板月份 / 选中日期 / 事件类型进 URL（设计 §4）：从本页点某事件的「处理」跳走后
   * 本页会重新挂载，只在 state 里就会丢。
   *
   * <p>`today` 与 `ALL_TYPES` 都必须是**稳定引用**（`useMemo` / 模块常量）：hook 内部按
   * `raw` 缓存解析结果，默认值每次渲染换新对象会把那份缓存带失效（设计 §5.1）。
   */
  const today = useMemo(() => dayjs(), []);
  const [panelDate, , monthWrite] = useUrlParam('month', today, MONTH_CODEC);
  const [selectedDate, , dateWrite] = useUrlParam('date', today, DATE_CODEC);
  const [types, setTypes] = useUrlParam('types', ALL_TYPES, TYPES_CODEC);

  /**
   * 一次原子写入多个 URL 参数（见 `useUrlParams` 的 why）。
   *
   * <p>选中某天时必须**同时**写 `month` 与 `date`：antd `Calendar` 跨月选中某天会同时触发
   * `onPanelChange` 与 `onSelect`，两个 handler 各写一次 URL 的话，后写的会覆盖先写的
   * （react-router 的函数式更新用的是本渲染的旧参数快照），`month` 会丢。
   * 让最后一次写入同时覆盖两个键，结果就与 antd 触发几次无关。
   */
  const writeParams = useUrlParams();

  /** 选中某天：面板跟着跳到那一天，所以 `month` 与 `date` 都写这一天 */
  const handleSelectDate = (d: Dayjs) => writeParams([monthWrite(d), dateWrite(d)]);

  /** 只切面板月份（没有选日期）：只写 `month`，不碰 `date` */
  const handlePanelChange = (d: Dayjs) => writeParams([monthWrite(d)]);

  const location = useLocation();

  const [summary, setSummary] = useState<DaySummary | null>(null);
  const [events, setEvents] = useState<OpsCalendarEvent[]>([]);
  const [loading, setLoading] = useState(false);
  const [noteOpen, setNoteOpen] = useState(false);
  const [form] = Form.useForm();

  const typesCsv = useMemo(() => types.join(','), [types]);

  const dayMap = useMemo(() => {
    const map = new Map<string, DayCell>();
    summary?.days?.forEach((d) => map.set(d.date, d));
    return map;
  }, [summary]);

  const loadSummary = useCallback(async () => {
    if (types.length === 0) {
      setSummary({ from: '', to: '', total: 0, days: [] });
      return;
    }
    const { from, to } = monthRange(panelDate);
    try {
      const data = await api.get<DaySummary>(
        `/ops-calendar/day-summary?from=${from}&to=${to}&types=${encodeURIComponent(typesCsv)}`,
      );
      setSummary(data);
    } catch {
      setSummary(null);
    }
  }, [panelDate, types.length, typesCsv]);

  const loadDayEvents = useCallback(async () => {
    if (types.length === 0) {
      setEvents([]);
      return;
    }
    const day = selectedDate.format('YYYY-MM-DD');
    setLoading(true);
    try {
      const list = await api.get<OpsCalendarEvent[]>(
        `/ops-calendar/events?from=${day}&to=${day}&types=${encodeURIComponent(typesCsv)}`,
      );
      setEvents(list);
    } catch {
      setEvents([]);
    } finally {
      setLoading(false);
    }
  }, [selectedDate, types.length, typesCsv]);

  useEffect(() => {
    void loadSummary();
  }, [loadSummary]);

  useEffect(() => {
    void loadDayEvents();
  }, [loadDayEvents]);

  const handleRefresh = () => {
    void loadSummary();
    void loadDayEvents();
  };

  const handleCreateNote = async () => {
    try {
      const values = await form.validateFields();
      await api.post('/ops-calendar/notes', {
        eventDate: (values.eventDate as Dayjs).format('YYYY-MM-DD'),
        title: values.title,
        content: values.content,
        level: values.level ?? 2,
      });
      message.success('提醒已创建');
      setNoteOpen(false);
      form.resetFields();
      handleRefresh();
    } catch {
      /* validation or api */
    }
  };

  const handleDeleteNote = async (bizId?: number) => {
    if (bizId == null) return;
    try {
      await api.del(`/ops-calendar/notes/${bizId}`);
      message.success('已删除');
      handleRefresh();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败');
    }
  };

  const dateCellRender = (value: Dayjs) => {
    const key = value.format('YYYY-MM-DD');
    const cell = dayMap.get(key);
    if (!cell || cell.total === 0) return null;
    return (
      <ul className="m-0 p-0 list-none space-y-0.5">
        <li>
          <Badge
            status={cell.high > 0 ? 'error' : 'processing'}
            text={<span className="text-[11px] text-gray-600">{cell.total} 项</span>}
          />
        </li>
      </ul>
    );
  };

  const dayEvents = events;

  return (
    <div className="space-y-4 min-w-0 max-w-full overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold text-gray-900 flex items-center gap-2 m-0">
            <CalendarOutlined className="text-[var(--ams-primary)]" />
            经营日历
          </h1>
          <p className="text-sm text-gray-500 mt-1 mb-0">
            聚合合同到期、账单应收、任务截止、巡检报修等，方便运营排期与跟进
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <Button icon={<ReloadOutlined />} onClick={handleRefresh} aria-label="刷新">
            刷新
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              form.setFieldsValue({ eventDate: selectedDate, level: 2 });
              setNoteOpen(true);
            }}
          >
            添加提醒
          </Button>
        </div>
      </div>

      <div className="bg-white rounded-xl border border-[var(--ams-border)] p-3 sm:p-4">
        <div className="text-sm font-medium text-gray-700 mb-2">事件类型</div>
        <Checkbox.Group
          className="flex flex-wrap gap-x-4 gap-y-2"
          options={TYPE_OPTIONS.map((t) => ({ label: t.label, value: t.value }))}
          value={types}
          onChange={(v) => setTypes(v as string[])}
        />
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-5 gap-4 min-w-0">
        <div className="xl:col-span-3 bg-white rounded-xl border border-[var(--ams-border)] p-2 sm:p-3 min-w-0 overflow-hidden">
          <Calendar
            value={selectedDate}
            onSelect={handleSelectDate}
            onPanelChange={handlePanelChange}
            cellRender={(current, info) => {
              if (info.type === 'date') return dateCellRender(current);
              return info.originNode;
            }}
          />
        </div>

        <div className="xl:col-span-2 bg-white rounded-xl border border-[var(--ams-border)] p-4 min-w-0 flex flex-col min-h-[360px]">
          <div className="flex items-center justify-between gap-2 mb-3">
            <div className="text-sm font-semibold text-gray-800 truncate">
              {selectedDate.format('YYYY年M月D日')} · {dayEvents.length} 项
            </div>
            {summary && (
              <Tag color="blue" className="m-0 shrink-0">
                本月 {summary.total}
              </Tag>
            )}
          </div>

          {loading ? (
            <div className="text-sm text-gray-400 py-10 text-center">加载中…</div>
          ) : dayEvents.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当日暂无事项" />
          ) : (
            <ul className="flex-1 overflow-y-auto ams-scroll space-y-2 m-0 p-0 list-none">
              {dayEvents.map((ev) => {
                const meta = TYPE_MAP[ev.type];
                return (
                  <li
                    key={ev.id}
                    className="rounded-lg border border-gray-100 hover:border-blue-100 hover:bg-blue-50/40 transition-colors p-3"
                  >
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-1.5 mb-1">
                          <Tag color={meta?.color ?? 'default'} className="m-0">
                            {meta?.label ?? ev.type}
                          </Tag>
                          <Tag color={levelColor(ev.level)} className="m-0">
                            {ev.level >= 3 ? '紧急' : ev.level === 2 ? '重要' : '一般'}
                          </Tag>
                        </div>
                        <div
                          className="text-sm font-medium text-gray-800 truncate"
                          title={ev.title}
                        >
                          {ev.title}
                        </div>
                        {ev.summary && (
                          <div className="text-xs text-gray-500 mt-0.5 line-clamp-2">
                            {ev.summary}
                          </div>
                        )}
                      </div>
                      <div className="flex flex-col items-end gap-1 shrink-0">
                        {ev.linkPath && ev.type !== 'note' && (
                          <Link
                            to={ev.linkPath}
                            state={{ from: currentPath(location) }}
                            className="text-xs text-[var(--ams-primary)] flex items-center gap-0.5"
                            aria-label={`查看 ${ev.title}`}
                          >
                            处理
                            <RightOutlined className="text-[10px]" />
                          </Link>
                        )}
                        {ev.type === 'note' && (
                          <button
                            type="button"
                            className="text-xs text-red-500 hover:underline"
                            onClick={() => void handleDeleteNote(ev.bizId)}
                            aria-label={`删除提醒 ${ev.title}`}
                          >
                            删除
                          </button>
                        )}
                      </div>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </div>

      <Modal
        title="添加经营提醒"
        open={noteOpen}
        onOk={() => void handleCreateNote()}
        onCancel={() => setNoteOpen(false)}
        okText="保存"
        destroyOnHidden
      >
        <Form form={form} layout="vertical" className="mt-2">
          <Form.Item
            name="eventDate"
            label="日期"
            rules={[{ required: true, message: '请选择日期' }]}
          >
            <DatePicker className="w-full" />
          </Form.Item>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input maxLength={200} placeholder="如：催收上门、现场核对水电" />
          </Form.Item>
          <Form.Item name="content" label="说明">
            <Input.TextArea rows={3} maxLength={1000} placeholder="可选补充说明" />
          </Form.Item>
          <Form.Item name="level" label="优先级" initialValue={2}>
            <Select
              options={[
                { value: 1, label: '一般' },
                { value: 2, label: '重要' },
                { value: 3, label: '紧急' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
