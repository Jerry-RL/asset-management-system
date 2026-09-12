import type { ReactNode } from 'react';
import {
  HomeOutlined,
  DashboardOutlined,
  ApartmentOutlined,
  AlertOutlined,
  BankOutlined,
  FileProtectOutlined,
  ShopOutlined,
  DeploymentUnitOutlined,
  FileTextOutlined,
  FileWordOutlined,
  AccountBookOutlined,
  PayCircleOutlined,
  PhoneOutlined,
  ToolOutlined,
  ScheduleOutlined,
  RocketOutlined,
  AuditOutlined,
  BellOutlined,
  ClusterOutlined,
  CopyrightOutlined,
  TeamOutlined,
  SettingOutlined,
  SafetyOutlined,
  RobotOutlined,
  CloudUploadOutlined,
  BarChartOutlined,
  FundOutlined,
  ProjectOutlined,
  IdcardOutlined,
  SwapOutlined,
  FormOutlined,
  NotificationOutlined,
  UserOutlined,
  ExportOutlined,
  FieldTimeOutlined,
  HomeFilled,
  ThunderboltOutlined,
  FileSearchOutlined,
  TransactionOutlined,
  ReconciliationOutlined,
  PercentageOutlined,
  DollarOutlined,
  RollbackOutlined,
  SolutionOutlined,
  BuildOutlined,
  CarryOutOutlined,
  MessageOutlined,
  AppstoreOutlined,
  ControlOutlined,
  MenuOutlined,
  ExperimentOutlined,
  SyncOutlined,
  EnvironmentOutlined,
  LogoutOutlined,
  CalendarOutlined,
  BookOutlined,
  PartitionOutlined,
  FolderOutlined,
} from '@ant-design/icons';

/** 按路由 path 映射菜单/页签/标题图标 */
export const PATH_ICONS: Record<string, ReactNode> = {
  '/': <HomeOutlined />,
  '/dashboard': <DashboardOutlined />,
  '/ops-calendar': <CalendarOutlined />,
  '/dashboard/consolidate': <FundOutlined />,
  '/business-plans': <BarChartOutlined />,
  '/alerts/rules': <ControlOutlined />,
  '/alerts/records': <AlertOutlined />,
  '/projects': <ProjectOutlined />,
  '/assets': <BankOutlined />,
  '/certificates': <FileProtectOutlined />,
  '/mortgages': <IdcardOutlined />,
  '/asset-transfers': <SwapOutlined />,
  '/evaluations': <FormOutlined />,
  '/lease-listings': <ShopOutlined />,
  '/tender/announcements': <NotificationOutlined />,
  '/tenants': <UserOutlined />,
  '/disposals': <ExportOutlined />,
  '/occupations': <FieldTimeOutlined />,
  '/self-uses': <HomeFilled />,
  '/contracts': <FileTextOutlined />,
  '/contract-templates': <FileWordOutlined />,
  '/vacate-orders': <LogoutOutlined />,
  '/billing/bills': <AccountBookOutlined />,
  '/meters': <DashboardOutlined />,
  '/apportion-configs': <PercentageOutlined />,
  '/payments': <PayCircleOutlined />,
  '/payments/pending-confirm': <PayCircleOutlined />,
  '/refunds': <RollbackOutlined />,
  '/adjustments/fee-reliefs': <AccountBookOutlined />,
  '/adjustments/rent-adjusts': <AccountBookOutlined />,
  '/invoices': <ReconciliationOutlined />,
  '/invoice-tax-rates': <PercentageOutlined />,
  '/finance/bank-flows': <TransactionOutlined />,
  '/finance/vouchers': <SolutionOutlined />,
  '/dunning/auto': <PhoneOutlined />,
  '/dunning/records': <PhoneOutlined />,
  '/repairs': <ToolOutlined />,
  '/vendors': <BuildOutlined />,
  '/inspections': <FileSearchOutlined />,
  '/tasks': <CarryOutOutlined />,
  '/approvals': <AuditOutlined />,
  '/revitalization': <RocketOutlined />,
  '/asset-audits': <FileSearchOutlined />,
  '/asset-map': <EnvironmentOutlined />,
  '/reports': <BarChartOutlined />,
  '/regulation/reports': <AuditOutlined />,
  '/notifications': <BellOutlined />,
  '/fixed-assets': <ClusterOutlined />,
  '/intangible-assets': <CopyrightOutlined />,
  '/org/companies': <ApartmentOutlined />,
  '/org/departments': <TeamOutlined />,
  '/org/structure': <PartitionOutlined />,
  '/system/users': <UserOutlined />,
  '/config/versions': <SettingOutlined />,
  '/system/roles': <SafetyOutlined />,
  '/system/menus': <MenuOutlined />,
  '/system/dict': <BookOutlined />,
  '/intelligence/templates': <ExperimentOutlined />,
  '/intelligence/sessions': <RobotOutlined />,
  '/intelligence/reports': <FileTextOutlined />,
  '/migrations/batches': <CloudUploadOutlined />,
};

export const GROUP_ICONS: Record<string, ReactNode> = {
  // 旧版菜单分组（菜单还原）
  首页与工作台: <AppstoreOutlined />,
  经营分析: <BarChartOutlined />,
  风险管控: <AlertOutlined />,
  资产台账: <BankOutlined />,
  资债权证: <FileProtectOutlined />,
  资产招租: <ShopOutlined />,
  资产运营: <DeploymentUnitOutlined />,
  合同管理: <FileTextOutlined />,
  定价与计费: <PercentageOutlined />,
  收费与发票: <ReconciliationOutlined />,
  履约催缴: <PhoneOutlined />,
  巡检维修: <ToolOutlined />,
  任务中心: <CarryOutOutlined />,
  空置盘活: <RocketOutlined />,
  合规监管: <AuditOutlined />,
  消息待办: <BellOutlined />,
  固定资产: <ClusterOutlined />,
  无形资产: <CopyrightOutlined />,
  组织架构: <ApartmentOutlined />,
  运营管理: <TeamOutlined />,
  系统配置: <SettingOutlined />,
  系统管理: <SafetyOutlined />,
  智能中心: <RobotOutlined />,
  期初迁移: <CloudUploadOutlined />,
  // 新版分组（保留兼容）
  工作台: <AppstoreOutlined />,
  协同管控: <AlertOutlined />,
  招租与合同: <FileTextOutlined />,
  收费财务: <DollarOutlined />,
  资产档案: <BankOutlined />,
  现场运维: <ToolOutlined />,
  系统设置: <SettingOutlined />,
};

export function getPathIcon(path: string): ReactNode {
  return PATH_ICONS[path] ?? <AppstoreOutlined />;
}

export function getGroupIcon(title: string): ReactNode {
  return GROUP_ICONS[title] ?? <AppstoreOutlined />;
}

/**
 * 具名图标目录（设计 3.3）：把 `menu.icon` 里存的名字解析成组件。
 *
 * <p>为什么不复用 {@link PATH_ICONS} / {@link GROUP_ICONS}：那两张表分别按<em>路由 path</em>
 * 和<em>中文分组名</em>索引，而 `menu.icon` 存的是 `ledger` / `setting` 这类与 path、
 * 中文名都无关的名字 —— 用它们去找必然全部落空，DB 权威图标会静默退回注册表图标。
 *
 * <p>三处共用同一张表：菜单管理页的图标选择器（U7）、侧边栏渲染（`MenuProvider`）、
 * 以及后端 `V45` 种子的取值口径。
 *
 * <p><strong>取值即契约</strong>：新增取值必须同时出现在这里，否则该图标会退回
 * {@link getPathIcon} 的默认图标。`ICON_NAMES` 供选择器与后端种子校对。
 */
export const ICON_BY_NAME: Record<string, ReactNode> = {
  // ---- V45 目录种子实际使用的 24 个取值 ----
  app: <AppstoreOutlined />,
  chart: <BarChartOutlined />,
  alert: <AlertOutlined />,
  ledger: <BankOutlined />,
  certificate: <FileProtectOutlined />,
  listing: <ShopOutlined />,
  operation: <DeploymentUnitOutlined />,
  contract: <FileTextOutlined />,
  bill: <AccountBookOutlined />,
  payment: <PayCircleOutlined />,
  dunning: <PhoneOutlined />,
  repair: <ToolOutlined />,
  task: <CarryOutOutlined />,
  revitalize: <RocketOutlined />,
  report: <AuditOutlined />,
  notify: <BellOutlined />,
  fixedasset: <ClusterOutlined />,
  intangible: <CopyrightOutlined />,
  org: <ApartmentOutlined />,
  tenant: <TeamOutlined />,
  config: <SettingOutlined />,
  setting: <SettingOutlined />,
  ai: <RobotOutlined />,
  migration: <CloudUploadOutlined />,

  // ---- 通用备用取值（菜单管理页选择器可用） ----
  folder: <FolderOutlined />,
  home: <HomeOutlined />,
  dashboard: <DashboardOutlined />,
  menu: <MenuOutlined />,
  user: <UserOutlined />,
  team: <TeamOutlined />,
  book: <BookOutlined />,
  calendar: <CalendarOutlined />,
  money: <DollarOutlined />,
  plan: <ScheduleOutlined />,
  approval: <AuditOutlined />,
  audit: <AuditOutlined />,
  safety: <SafetyOutlined />,
  map: <EnvironmentOutlined />,
  file: <FileTextOutlined />,
  word: <FileWordOutlined />,
  search: <FileSearchOutlined />,
  percentage: <PercentageOutlined />,
  transaction: <TransactionOutlined />,
  reconciliation: <ReconciliationOutlined />,
  rollback: <RollbackOutlined />,
  identity: <IdcardOutlined />,
  swap: <SwapOutlined />,
  project: <ProjectOutlined />,
  bank: <BankOutlined />,
  build: <BuildOutlined />,
  message: <MessageOutlined />,
  control: <ControlOutlined />,
  experiment: <ExperimentOutlined />,
  sync: <SyncOutlined />,
  export: <ExportOutlined />,
  time: <FieldTimeOutlined />,
  thunder: <ThunderboltOutlined />,
  solution: <SolutionOutlined />,
  fund: <FundOutlined />,
  form: <FormOutlined />,
  notification: <NotificationOutlined />,
  partition: <PartitionOutlined />,
};

/** 可按名字解析的图标名（选择器选项 + 与后端种子校对用）。 */
export const ICON_NAMES: string[] = Object.keys(ICON_BY_NAME).sort();

/**
 * 按名字解析图标。
 *
 * @returns 未登记时返回 `undefined`（而不是默认图标）—— 调用方需要区分
 *     「DB 明确指定了图标但名字拼错」与「DB 没配图标、该用注册表兜底」，
 *     前者应该被看见（U7 会告警），后者是正常路径。
 */
export function getIconByName(name?: string | null): ReactNode | undefined {
  if (!name) return undefined;
  return ICON_BY_NAME[name];
}

/** 看板指标图标 */
export const DASHBOARD_ICONS: Record<string, ReactNode> = {
  资产总数: <BankOutlined />,
  在租宗数: <ShopOutlined />,
  空置宗数: <HomeOutlined />,
  '空置面积 (㎡)': <EnvironmentOutlined />,
  出租率: <FundOutlined />,
  收缴率: <PayCircleOutlined />,
  '应收 (元)': <AccountBookOutlined />,
  '实收 (元)': <DollarOutlined />,
  '欠费 (元)': <AlertOutlined />,
  合同总数: <FileTextOutlined />,
  集团资产总数: <ApartmentOutlined />,
  集团出租率: <FundOutlined />,
  '集团空置面积 (㎡)': <EnvironmentOutlined />,
};
