import { Link } from 'react-router-dom';
import { Empty, Tag } from 'antd';
import { useEffect, useState } from 'react';
import { useAuth } from '@/lib/auth';
import { api } from '@/lib/api';
import type { OpsCalendarEvent } from '@/pages/OpsCalendarPage';
import {
  DashboardOutlined,
  BankOutlined,
  FileProtectOutlined,
  ShopOutlined,
  FileTextOutlined,
  AccountBookOutlined,
  AlertOutlined,
  TeamOutlined,
  ToolOutlined,
  EnvironmentOutlined,
  BarChartOutlined,
  SettingOutlined,
  SafetyOutlined,
  AppstoreOutlined,
  ClusterOutlined,
  ScheduleOutlined,
  CalendarOutlined,
  SettingFilled,
  ThunderboltOutlined,
  CopyrightOutlined,
  CarryOutOutlined,
  RightOutlined,
  ReconciliationOutlined,
  PhoneOutlined,
} from '@ant-design/icons';

interface AppEntry {
  title: string;
  desc: string;
  path: string;
  icon: React.ReactNode;
  color: string;
}

const OPERATING: AppEntry[] = [
  { title: '经营日历', desc: '到期与待办排期', path: '/ops-calendar', icon: <CalendarOutlined />, color: '#fa541c' },
  { title: '经营看板', desc: '经营指标总览', path: '/dashboard', icon: <DashboardOutlined />, color: '#1677ff' },
  { title: '合同管理', desc: '合同与退租', path: '/contracts', icon: <FileTextOutlined />, color: '#2f54eb' },
  { title: '收费大厅', desc: '账单与表计', path: '/billing/bills', icon: <AccountBookOutlined />, color: '#52c41a' },
  { title: '资产管理', desc: '资产台账与项目', path: '/assets', icon: <BankOutlined />, color: '#13c2c2' },
  { title: '招租管理', desc: '公开招租与客商', path: '/lease-listings', icon: <ShopOutlined />, color: '#eb2f96' },
  { title: '履约催缴', desc: '催缴记录', path: '/dunning/auto', icon: <PhoneOutlined />, color: '#f5222d' },
  { title: '巡检维修', desc: '报修与巡查', path: '/repairs', icon: <ToolOutlined />, color: '#1890ff' },
  { title: '发票管理', desc: '开票与税率', path: '/invoices', icon: <ReconciliationOutlined />, color: '#fa541c' },
  { title: '资债权证', desc: '权证抵押评估', path: '/certificates', icon: <FileProtectOutlined />, color: '#722ed1' },
  { title: '资产运营', desc: '处置占用自用', path: '/disposals', icon: <ShopOutlined />, color: '#fa8c16' },
  { title: '资产地图', desc: '空间分布', path: '/asset-map', icon: <EnvironmentOutlined />, color: '#36cfc9' },
  { title: '数据报表', desc: '经营分析', path: '/reports', icon: <BarChartOutlined />, color: '#597ef7' },
];

const FIXED: AppEntry[] = [
  { title: '固资清单', desc: '固定资产台账', path: '/fixed-assets', icon: <ClusterOutlined />, color: '#1677ff' },
];

const DIGITAL: AppEntry[] = [
  { title: '任务中心', desc: '待办与超时', path: '/tasks', icon: <ScheduleOutlined />, color: '#fa8c16' },
  { title: '预警管理', desc: '规则与提醒', path: '/alerts/records', icon: <AlertOutlined />, color: '#f5222d' },
  { title: '运营管理', desc: '消息与配置', path: '/notifications', icon: <AppstoreOutlined />, color: '#13c2c2' },
  { title: '组织架构', desc: '组织与人员', path: '/org/companies', icon: <TeamOutlined />, color: '#1677ff' },
  { title: '系统管理', desc: '用户角色日志', path: '/system/users', icon: <SafetyOutlined />, color: '#595959' },
  { title: '系统配置', desc: '参数与版本', path: '/config/versions', icon: <SettingOutlined />, color: '#722ed1' },
];

const WORKBENCH_QUICK: AppEntry[] = [
  { title: '经营日历', desc: '快速进入', path: '/ops-calendar', icon: <CalendarOutlined />, color: '#fa541c' },
  { title: '任务中心', desc: '快速进入', path: '/tasks', icon: <CarryOutOutlined />, color: '#13c2c2' },
  { title: '合同管理', desc: '快速进入', path: '/contracts', icon: <FileTextOutlined />, color: '#2f54eb' },
  { title: '收费大厅', desc: '快速进入', path: '/billing/bills', icon: <AccountBookOutlined />, color: '#52c41a' },
  { title: '资产台账', desc: '快速进入', path: '/assets', icon: <BankOutlined />, color: '#1677ff' },
  { title: '报修工单', desc: '快速进入', path: '/repairs', icon: <ToolOutlined />, color: '#fa8c16' },
];

const EVENT_TYPE_LABEL: Record<string, string> = {
  contract_expiry: '合同',
  bill_due: '账单',
  task_deadline: '任务',
  mortgage_expiry: '抵押',
  occupation_end: '占用',
  self_use_end: '自用',
  inspection: '巡检',
  repair_sla: '报修',
  note: '提醒',
};

function EntryGrid({ items }: { items: AppEntry[] }) {
  return (
    <div className="grid grid-cols-2 xl:grid-cols-3 gap-3">
      {items.map((item) => (
        <Link
          key={item.path + item.title}
          to={item.path}
          className="flex items-center gap-3 p-2 rounded-lg hover:bg-white/70 transition-colors group"
        >
          <span
            className="w-10 h-10 rounded-lg flex items-center justify-center text-white text-lg shrink-0 shadow-sm group-hover:scale-105 transition-transform"
            style={{ background: item.color }}
          >
            {item.icon}
          </span>
          <span className="min-w-0">
            <span className="block text-sm font-medium text-gray-800 truncate">{item.title}</span>
            <span className="block text-xs text-gray-400 truncate">{item.desc}</span>
          </span>
        </Link>
      ))}
    </div>
  );
}

function SectionTitle({
  icon,
  title,
  color,
}: {
  icon: React.ReactNode;
  title: string;
  color: string;
}) {
  return (
    <div className="flex items-center gap-2 mb-3">
      <span
        className="w-6 h-6 rounded flex items-center justify-center text-white text-xs"
        style={{ background: color }}
      >
        {icon}
      </span>
      <span className="text-sm font-semibold text-gray-800">{title}</span>
    </div>
  );
}

function UpcomingTodos() {
  const [items, setItems] = useState<OpsCalendarEvent[]>([]);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    api
      .get<OpsCalendarEvent[]>('/ops-calendar/upcoming?days=7')
      .then((list) => setItems((list ?? []).slice(0, 6)))
      .catch(() => setItems([]))
      .finally(() => setLoaded(true));
  }, []);

  if (!loaded) {
    return <div className="text-xs text-gray-400 py-6 text-center">加载中…</div>;
  }
  if (items.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="近7日暂无事项" />;
  }

  return (
    <ul className="m-0 p-0 list-none space-y-2 max-h-[180px] overflow-y-auto ams-scroll">
      {items.map((ev) => (
        <li key={ev.id}>
          <Link
            to={ev.linkPath || '/ops-calendar'}
            className="flex items-start gap-2 rounded-lg px-1 py-1 hover:bg-blue-50 transition-colors"
          >
            <Tag
              color={ev.level >= 3 ? 'red' : 'blue'}
              className="m-0 shrink-0 text-[10px] leading-5"
            >
              {EVENT_TYPE_LABEL[ev.type] ?? '事项'}
            </Tag>
            <span className="min-w-0 flex-1">
              <span className="block text-xs text-gray-800 truncate">{ev.title}</span>
              <span className="block text-[11px] text-gray-400">{ev.eventDate}</span>
            </span>
          </Link>
        </li>
      ))}
    </ul>
  );
}

export function HomePage() {
  const { user } = useAuth();

  return (
    <div className="space-y-4 min-w-0 max-w-full overflow-hidden">
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-4">
        <div className="xl:col-span-2 rounded-xl overflow-hidden bg-gradient-to-r from-[#1677ff] to-[#69b1ff] text-white p-4 sm:p-6 flex items-center justify-between min-h-[120px] sm:min-h-[140px] relative">
          <div className="absolute right-4 sm:right-8 top-4 sm:top-6 opacity-20 text-5xl sm:text-7xl pointer-events-none">
            <ThunderboltOutlined />
          </div>
          <div className="flex items-center gap-3 sm:gap-4 relative z-10 min-w-0">
            <div className="w-12 h-12 sm:w-14 sm:h-14 rounded-full bg-white/20 flex items-center justify-center text-xl sm:text-2xl font-semibold ring-2 ring-white/30 shrink-0">
              {(user?.name ?? '用').slice(0, 1)}
            </div>
            <div className="min-w-0">
              <div className="text-base sm:text-lg font-semibold truncate">
                欢迎回来，{user?.name ?? '用户'}
              </div>
              <div className="text-sm text-white/80 mt-1 flex items-center gap-1.5 min-w-0">
                <SafetyOutlined className="shrink-0" />
                <span className="truncate">{user?.roles?.join('、') || '资产管理'}</span>
              </div>
            </div>
          </div>
          <div className="hidden md:block text-right text-base font-medium max-w-xs leading-relaxed relative z-10 shrink-0 pl-4">
            资产全生命周期数智化管理
          </div>
        </div>

        <div className="bg-white rounded-xl border border-[var(--ams-border)] p-4 min-h-[140px] min-w-0 overflow-hidden flex flex-col">
          <div className="text-sm font-semibold mb-2 flex items-center justify-between gap-2">
            <span className="flex items-center gap-2 min-w-0">
              <CalendarOutlined className="text-[var(--ams-primary)] shrink-0" />
              <span className="truncate">待办任务</span>
            </span>
            <Link
              to="/ops-calendar"
              className="text-xs text-[var(--ams-primary)] flex items-center gap-0.5 shrink-0"
              aria-label="打开经营日历"
            >
              日历
              <RightOutlined className="text-[10px]" />
            </Link>
          </div>
          <div className="flex-1 min-h-0">
            <UpcomingTodos />
          </div>
        </div>
      </div>

      <div className="bg-white rounded-xl border border-[var(--ams-border)] p-4 min-w-0 overflow-hidden">
        <div className="flex items-center justify-between mb-3 gap-2 min-w-0">
          <div className="text-sm font-semibold flex items-center gap-2 min-w-0">
            <AppstoreOutlined className="text-[var(--ams-primary)] shrink-0" />
            <span className="truncate">工作台</span>
          </div>
          <Link
            to="/config/versions"
            className="text-xs text-[var(--ams-primary)] flex items-center gap-1 shrink-0"
          >
            <SettingFilled />
            设置
            <RightOutlined className="text-[10px]" />
          </Link>
        </div>
        <div className="grid grid-cols-3 md:grid-cols-6 gap-2 sm:gap-3">
          {WORKBENCH_QUICK.map((item) => (
            <Link
              key={item.title}
              to={item.path}
              className="flex flex-col items-center gap-2 p-2 sm:p-3 rounded-lg hover:bg-blue-50 transition-colors min-w-0"
            >
              <span
                className="w-10 h-10 sm:w-11 sm:h-11 rounded-xl flex items-center justify-center text-white text-lg sm:text-xl shadow-sm shrink-0"
                style={{ background: item.color }}
              >
                {item.icon}
              </span>
              <span className="text-xs text-gray-700 truncate max-w-full text-center">{item.title}</span>
            </Link>
          ))}
        </div>
      </div>

      <div className="min-w-0">
        <h2 className="text-base font-semibold mb-3 flex items-center gap-2">
          <AppstoreOutlined className="text-[var(--ams-primary)]" />
          应用中心
        </h2>
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
          <div className="rounded-xl border border-blue-100 bg-gradient-to-br from-[#e6f4ff] to-white p-4 min-h-[220px] min-w-0 overflow-hidden">
            <SectionTitle icon={<BankOutlined />} title="经营性资产" color="#1677ff" />
            <EntryGrid items={OPERATING} />
          </div>
          <div className="rounded-xl border border-slate-100 bg-gradient-to-br from-[#f5f5f5] to-white p-4 min-h-[220px] min-w-0 overflow-hidden">
            <SectionTitle icon={<ThunderboltOutlined />} title="数智管理" color="#595959" />
            <EntryGrid items={DIGITAL} />
          </div>
          <div className="rounded-xl border border-cyan-100 bg-gradient-to-br from-[#e6fffb] to-white p-4 min-w-0 overflow-hidden">
            <SectionTitle icon={<ClusterOutlined />} title="固定资产" color="#13c2c2" />
            <EntryGrid items={FIXED} />
          </div>
          <div className="rounded-xl border border-purple-100 bg-gradient-to-br from-[#f9f0ff] to-white p-4 min-w-0 overflow-hidden">
            <SectionTitle icon={<CopyrightOutlined />} title="无形资产" color="#722ed1" />
            <div className="text-sm text-gray-400 py-8 text-center flex flex-col items-center gap-2">
              <CopyrightOutlined className="text-3xl text-purple-200" />
              敬请期待
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
