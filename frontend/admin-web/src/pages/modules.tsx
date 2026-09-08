import type { ResourceConfig } from '@/components/ResourcePage';
import * as L from '@/lib/labels';

// ============================================================================
// 全量模块注册表：菜单树 + 资源配置（对齐 SRS §3.2 PC 管理后台功能域）
// ============================================================================

export interface MenuItem {
  path: string;
  title: string;
}

export interface MenuGroup {
  title: string;
  items: MenuItem[];
}

export const MENU: MenuGroup[] = [
  {
    title: '首页与工作台',
    items: [
      { path: '/', title: '经营看板' },
      { path: '/dashboard/consolidate', title: '集团合并看板' },
    ],
  },
  {
    title: '经营分析',
    items: [
      { path: '/business-plans', title: '经营计划与预算' },
      { path: '/dashboard/consolidate', title: '集团合并看板' },
    ],
  },
  {
    title: '风险管控',
    items: [
      { path: '/alerts/rules', title: '预警配置' },
      { path: '/alerts/records', title: '预警提醒与记录' },
    ],
  },
  {
    title: '资产台账',
    items: [
      { path: '/projects', title: '项目管理' },
      { path: '/assets', title: '资产台账' },
    ],
  },
  {
    title: '资债权证',
    items: [
      { path: '/certificates', title: '权证信息' },
      { path: '/mortgages', title: '抵押列表' },
      { path: '/asset-transfers', title: '资产调拨' },
      { path: '/evaluations', title: '评估申请' },
    ],
  },
  {
    title: '资产招租',
    items: [
      { path: '/lease-listings', title: '招租管理' },
      { path: '/tender/announcements', title: '公开招租' },
      { path: '/tenants', title: '客商/租户管理' },
    ],
  },
  {
    title: '资产运营',
    items: [
      { path: '/disposals', title: '资产处置' },
      { path: '/occupations', title: '临时占用' },
      { path: '/self-uses', title: '资产自用' },
    ],
  },
  {
    title: '合同管理',
    items: [
      { path: '/contracts', title: '合同管理' },
      { path: '/vacate-orders', title: '退租清场与保证金' },
    ],
  },
  {
    title: '定价与计费',
    items: [
      { path: '/billing/bills', title: '账单（收费大厅）' },
      { path: '/meters', title: '表计档案与抄表' },
      { path: '/apportion-configs', title: '公摊配置' },
    ],
  },
  {
    title: '收费与发票',
    items: [
      { path: '/payments', title: '收款记录' },
      { path: '/refunds', title: '退款冲正' },
      { path: '/invoices', title: '发票管理' },
      { path: '/invoice-tax-rates', title: '发票税率' },
      { path: '/finance/bank-flows', title: '银行对账' },
      { path: '/finance/vouchers', title: '财务凭证' },
    ],
  },
  {
    title: '履约催缴',
    items: [{ path: '/dunning/records', title: '催缴记录' }],
  },
  {
    title: '巡检维修',
    items: [
      { path: '/repairs', title: '报修工单' },
      { path: '/vendors', title: '维修公司' },
      { path: '/inspections', title: '巡查记录' },
    ],
  },
  {
    title: '任务中心',
    items: [{ path: '/tasks', title: '任务管理' }],
  },
  {
    title: '空置盘活',
    items: [{ path: '/revitalization', title: '盘活任务' }],
  },
  {
    title: '合规监管',
    items: [{ path: '/regulation/reports', title: '监管报送' }],
  },
  {
    title: '消息待办',
    items: [{ path: '/notifications', title: '消息通知' }],
  },
  {
    title: '固定资产',
    items: [{ path: '/fixed-assets', title: '固资清单' }],
  },
  {
    title: '无形资产',
    items: [{ path: '/intangible-assets', title: '无形资产台账' }],
  },
  {
    title: '组织架构',
    items: [
      { path: '/org/companies', title: '公司管理' },
      { path: '/org/departments', title: '部门管理' },
      { path: '/system/users', title: '人员维护' },
    ],
  },
  {
    title: '运营管理',
    items: [{ path: '/tenants', title: '租户管理' }],
  },
  {
    title: '系统配置',
    items: [{ path: '/config/versions', title: '参数版本留痕' }],
  },
  {
    title: '系统管理',
    items: [
      { path: '/system/roles', title: '角色权限' },
      { path: '/system/menus', title: '菜单管理' },
    ],
  },
  {
    title: '智能中心',
    items: [
      { path: '/intelligence/templates', title: '报告模板' },
      { path: '/intelligence/sessions', title: 'Agent 会话' },
    ],
  },
  {
    title: '期初迁移',
    items: [{ path: '/migrations/batches', title: '迁移批次与试算平衡' }],
  },
];

// ============================================================================
// 资源配置
// ============================================================================

const common: Record<string, { value: string; label: string }[]> = {
  status1: [
    { value: 'vacant', label: '空置' },
    { value: 'leasing', label: '招租中' },
    { value: 'leased', label: '在租' },
    { value: 'self_use', label: '自用' },
    { value: 'occupied', label: '占用' },
  ],
  contractStatus: Object.entries(L.CONTRACT_STATUS).map(([value, label]) => ({ value, label })),
  billStatus: Object.entries(L.BILL_STATUS).map(([value, label]) => ({ value, label })),
};

export const RESOURCES: Record<string, ResourceConfig> = {
  projects: {
    title: '项目管理',
    listPath: '/projects',
    create: true,
    deletable: true,
    columns: [
      { key: 'id', label: 'ID' },
      { key: 'name', label: '项目名称' },
      { key: 'address', label: '地址' },
      { key: 'companyId', label: '经营公司ID' },
      { key: 'status', label: '状态' },
    ],
    fields: [
      { name: 'name', label: '项目名称', required: true },
      { name: 'address', label: '地址', type: 'textarea' },
      { name: 'companyId', label: '经营公司ID', type: 'number' },
      { name: 'longitude', label: '经度', type: 'number' },
      { name: 'latitude', label: '纬度', type: 'number' },
    ],
  },
  assets: {
    title: '资产台账',
    listPath: '/assets',
    create: true,
    detailPath: (id) => `/assets/${id}`,
    columns: [
      { key: 'assetNo', label: '资产编号' },
      { key: 'name', label: '名称' },
      { key: 'assetType', label: '类型', map: L.ASSET_TYPE },
      { key: 'area', label: '面积(㎡)' },
      { key: 'leaseControlStatus', label: '租控状态', map: L.LEASE_CONTROL_STATUS },
      { key: 'operatingCompanyId', label: '经营公司' },
    ],
    filters: [
      {
        key: 'assetType',
        label: '类型',
        options: Object.entries(L.ASSET_TYPE).map(([value, label]) => ({ value, label })),
      },
      {
        key: 'leaseControlStatus',
        label: '租控状态',
        options: Object.entries(L.LEASE_CONTROL_STATUS).map(([value, label]) => ({ value, label })),
      },
    ],
    fields: [
      { name: 'assetNo', label: '资产编号', required: true },
      { name: 'name', label: '名称', required: true },
      {
        name: 'assetType',
        label: '类型',
        type: 'select',
        required: true,
        options: Object.entries(L.ASSET_TYPE).map(([value, label]) => ({ value, label })),
      },
      { name: 'area', label: '面积(㎡)', type: 'number' },
      { name: 'projectId', label: '项目ID', type: 'number' },
      { name: 'sourceType', label: '来源类型' },
      { name: 'ownershipType', label: '权属' },
      { name: 'province', label: '省' },
      { name: 'city', label: '市' },
      { name: 'district', label: '区' },
      { name: 'address', label: '坐落地址', type: 'textarea' },
    ],
  },
  tenants: {
    title: '租户/客商管理',
    listPath: '/tenants',
    create: true,
    detailPath: (id) => `/tenants/${id}`,
    columns: [
      { key: 'name', label: '名称' },
      { key: 'phone', label: '手机号' },
      { key: 'tenantType', label: '类型', map: { person: '个人', enterprise: '企业' } },
      { key: 'creditScore', label: '信用分' },
      { key: 'blacklist', label: '黑名单', render: (r) => (r.blacklist ? '是' : '否') },
      { key: 'status', label: '状态', map: { '1': '启用', '0': '停用' } },
    ],
    filters: [
      {
        key: 'blacklist',
        label: '黑名单',
        options: [
          { value: 'true', label: '是' },
          { value: 'false', label: '否' },
        ],
      },
    ],
    fields: [
      { name: 'name', label: '名称', required: true },
      { name: 'phone', label: '手机号' },
      {
        name: 'tenantType',
        label: '类型',
        type: 'select',
        options: [
          { value: 'person', label: '个人' },
          { value: 'enterprise', label: '企业' },
        ],
      },
      { name: 'idNo', label: '证件号' },
    ],
  },
  'lease-listings': {
    title: '招租管理',
    listPath: '/lease-listings',
    create: true,
    columns: [
      { key: 'id', label: 'ID' },
      { key: 'assetId', label: '资产ID' },
      { key: 'rentAmount', label: '租金' },
      { key: 'rentNegotiable', label: '面议', render: (r) => (r.rentNegotiable ? '是' : '否') },
      { key: 'status', label: '状态', map: { active: '进行中', closed: '已关闭' } },
    ],
    fields: [
      { name: 'assetId', label: '资产ID', type: 'number', required: true },
      { name: 'rentAmount', label: '租金', type: 'number' },
      { name: 'rentNegotiable', label: '可面议', type: 'boolean' },
    ],
  },
  'tender/announcements': {
    title: '公开招租公告',
    listPath: '/tender/announcements',
    create: true,
    columns: [
      { key: 'title', label: '公告标题' },
      { key: 'registerDeadline', label: '报名截止' },
      { key: 'displayPeriodDays', label: '公示期(天)' },
      { key: 'status', label: '状态', map: L.TENDER_STATUS },
      { key: 'result', label: '结果' },
    ],
    filters: [
      {
        key: 'status',
        label: '状态',
        options: Object.entries(L.TENDER_STATUS).map(([value, label]) => ({ value, label })),
      },
    ],
    fields: [
      { name: 'title', label: '标题', required: true },
      { name: 'registerDeadline', label: '报名截止', type: 'date' },
      { name: 'displayPeriodDays', label: '公示期(天)', type: 'number' },
    ],
  },
  contracts: {
    title: '合同管理',
    listPath: '/contracts',
    create: true,
    detailPath: (id) => `/contracts/${id}`,
    columns: [
      { key: 'contractNo', label: '合同编号' },
      { key: 'assetId', label: '资产ID' },
      { key: 'tenantId', label: '租户ID' },
      { key: 'rentType', label: '租金类型', map: L.RENT_TYPE },
      { key: 'rentAmount', label: '周期租金' },
      { key: 'status', label: '状态', map: L.CONTRACT_STATUS },
    ],
    filters: [{ key: 'status', label: '状态', options: common.contractStatus }],
    fields: [
      { name: 'assetId', label: '资产ID', type: 'number', required: true },
      { name: 'tenantId', label: '租户ID', type: 'number', required: true },
      { name: 'startDate', label: '起租日', type: 'date', required: true },
      { name: 'endDate', label: '到期日', type: 'date', required: true },
      {
        name: 'rentType',
        label: '租金类型',
        type: 'select',
        options: Object.entries(L.RENT_TYPE).map(([value, label]) => ({ value, label })),
      },
      { name: 'rentAmount', label: '租金', type: 'number', required: true },
      { name: 'depositAmount', label: '保证金', type: 'number' },
      {
        name: 'paymentCycle',
        label: '缴费周期',
        type: 'select',
        options: Object.entries(L.PAYMENT_CYCLE).map(([value, label]) => ({ value, label })),
      },
    ],
  },
  'vacate-orders': {
    title: '退租单',
    listPath: '/vacate-orders',
    columns: [
      { key: 'contractId', label: '合同ID' },
      { key: 'status', label: '状态', map: L.VACATE_STATUS },
      { key: 'reason', label: '原因' },
      { key: 'settlementAmount', label: '结算金额' },
      { key: 'depositRefund', label: '保证金退还' },
    ],
    filters: [{ key: 'contractId', label: '合同ID' }],
  },
  'billing/bills': {
    title: '账单（收费大厅）',
    listPath: '/billing/bills',
    columns: [
      { key: 'billNo', label: '账单编号' },
      { key: 'contractId', label: '合同ID' },
      { key: 'billType', label: '类型', map: { rent: '租金', utility: '水电', other: '其他' } },
      { key: 'amount', label: '应收本金' },
      { key: 'paidAmount', label: '已核销' },
      { key: 'lateFeeAmount', label: '滞纳金' },
      { key: 'status', label: '状态', map: L.BILL_STATUS },
      {
        key: 'dunningLevel',
        label: '催缴等级',
        render: (r) => (r.dunningLevel ? `L${r.dunningLevel}` : '-'),
      },
    ],
    filters: [{ key: 'status', label: '状态', options: common.billStatus }],
  },
  payments: {
    title: '收款记录',
    listPath: '/payments',
    columns: [
      { key: 'paymentNo', label: '收款单号' },
      { key: 'contractId', label: '合同ID' },
      { key: 'amount', label: '金额' },
      {
        key: 'method',
        label: '方式',
        map: { cash: '现金', bank_transfer: '转账', wechat: '微信', alipay: '支付宝' },
      },
      { key: 'channel', label: '来源', map: { user_mp: '用户端', worker_mp: '工作端', pc: 'PC' } },
      { key: 'confirmStatus', label: '到账状态', map: L.PAYMENT_CONFIRM },
      { key: 'paidAt', label: '收款时间' },
    ],
    filters: [
      {
        key: 'channel',
        label: '来源',
        options: [
          { value: 'user_mp', label: '用户端' },
          { value: 'worker_mp', label: '工作端' },
          { value: 'pc', label: 'PC' },
        ],
      },
      {
        key: 'confirmStatus',
        label: '到账状态',
        options: Object.entries(L.PAYMENT_CONFIRM).map(([value, label]) => ({ value, label })),
      },
    ],
  },
  refunds: {
    title: '退款冲正',
    listPath: '/refunds',
    columns: [
      { key: 'paymentId', label: '原收款ID' },
      { key: 'amount', label: '退款金额' },
      { key: 'reason', label: '原因' },
      { key: 'status', label: '状态', map: L.REFUND_STATUS },
      { key: 'thirdPartyRefundNo', label: '三方退款单号' },
    ],
  },
  invoices: {
    title: '发票管理',
    listPath: '/invoices',
    columns: [
      { key: 'invoiceNo', label: '发票号' },
      { key: 'paymentId', label: '收款ID' },
      { key: 'amount', label: '金额' },
      { key: 'taxRate', label: '税率' },
      { key: 'taxAmount', label: '税额' },
      { key: 'status', label: '状态', map: L.INVOICE_STATUS },
    ],
  },
  'invoice-tax-rates': {
    title: '发票税率',
    listPath: '/invoice-tax-rates',
    create: true,
    columns: [
      { key: 'tax_code', label: '税目编码' },
      { key: 'tax_name', label: '税目名称' },
      { key: 'rate', label: '税率' },
      { key: 'effective_date', label: '生效日期' },
    ],
    fields: [
      { name: 'taxCode', label: '税目编码', required: true },
      { name: 'taxName', label: '税目名称', required: true },
      { name: 'rate', label: '税率', type: 'number', required: true },
      { name: 'effectiveDate', label: '生效日期', type: 'date' },
    ],
  },
  'dunning/records': {
    title: '催缴记录',
    listPath: '/dunning/records',
    columns: [
      { key: 'billId', label: '账单ID' },
      { key: 'contractId', label: '合同ID' },
      { key: 'level', label: '等级', render: (r) => `L${r.level}` },
      {
        key: 'method',
        label: '方式',
        map: { sms: '短信', notice_post: '催缴单', lawyer_letter: '律师函', legal: '法务' },
      },
      { key: 'result', label: '回款结果' },
      { key: 'createdAt', label: '时间' },
    ],
  },
  repairs: {
    title: '报修工单',
    listPath: '/repairs',
    create: true,
    columns: [
      { key: 'assetId', label: '资产ID' },
      { key: 'reporterName', label: '报修人' },
      { key: 'description', label: '说明' },
      { key: 'status', label: '状态', map: L.REPAIR_STATUS },
      { key: 'vendorId', label: '维修公司' },
    ],
    filters: [
      {
        key: 'status',
        label: '状态',
        options: Object.entries(L.REPAIR_STATUS).map(([value, label]) => ({ value, label })),
      },
    ],
    fields: [
      { name: 'assetId', label: '资产ID', type: 'number', required: true },
      { name: 'description', label: '报修说明', type: 'textarea', required: true },
      { name: 'reporterName', label: '报修人' },
      { name: 'reporterPhone', label: '联系电话' },
    ],
  },
  vendors: {
    title: '维修公司',
    listPath: '/vendors',
    create: true,
    columns: [
      { key: 'name', label: '名称' },
      { key: 'contact', label: '联系人' },
      { key: 'phone', label: '电话' },
      { key: 'scope', label: '服务范围' },
      { key: 'status', label: '状态', map: { '1': '启用', '0': '停用' } },
    ],
    fields: [
      { name: 'name', label: '名称', required: true },
      { name: 'contact', label: '联系人' },
      { name: 'phone', label: '电话' },
      { name: 'scope', label: '服务范围', type: 'textarea' },
    ],
  },
  inspections: {
    title: '巡查记录',
    listPath: '/inspections',
    create: true,
    columns: [
      { key: 'assetId', label: '资产ID' },
      { key: 'inspectorId', label: '巡查人ID' },
      { key: 'planDate', label: '巡查日期' },
      { key: 'result', label: '结果' },
      { key: 'hazardDesc', label: '隐患描述' },
      { key: 'status', label: '状态' },
    ],
    fields: [
      { name: 'assetId', label: '资产ID', type: 'number', required: true },
      { name: 'planDate', label: '巡查日期', type: 'date' },
      { name: 'result', label: '结果' },
      { name: 'hazardDesc', label: '隐患描述', type: 'textarea' },
    ],
  },
  'alerts/rules': {
    title: '预警配置',
    listPath: '/alerts/rules',
    create: true,
    columns: [
      { key: 'alertType', label: '预警类型' },
      { key: 'subType', label: '子类型' },
      { key: 'level', label: '等级' },
      { key: 'conditionJson', label: '触发条件' },
      { key: 'enabled', label: '启用', render: (r) => (r.enabled ? '是' : '否') },
    ],
    fields: [
      { name: 'alertType', label: '预警类型', required: true },
      { name: 'subType', label: '子类型' },
      { name: 'level', label: '等级', type: 'number' },
      { name: 'conditionJson', label: '条件JSON', type: 'textarea' },
      { name: 'enabled', label: '启用', type: 'boolean' },
    ],
  },
  'alerts/records': {
    title: '预警记录',
    listPath: '/alerts/records',
    columns: [
      { key: 'title', label: '标题' },
      { key: 'alertType', label: '类型' },
      { key: 'level', label: '等级' },
      { key: 'status', label: '状态', map: L.ALERT_STATUS },
      { key: 'assigneeId', label: '责任人ID' },
      { key: 'handleRemark', label: '处理说明' },
    ],
    filters: [
      {
        key: 'status',
        label: '状态',
        options: Object.entries(L.ALERT_STATUS).map(([value, label]) => ({ value, label })),
      },
    ],
  },
  tasks: {
    title: '任务管理',
    listPath: '/tasks',
    create: true,
    columns: [
      { key: 'taskType', label: '任务类型' },
      { key: 'refId', label: '业务单ID' },
      { key: 'assigneeId', label: '经办人ID' },
      { key: 'deadline', label: '截止时间' },
      { key: 'status', label: '状态', map: L.TASK_STATUS },
      { key: 'overdueMinutes', label: '超时(分钟)' },
    ],
    filters: [
      {
        key: 'status',
        label: '状态',
        options: Object.entries(L.TASK_STATUS).map(([value, label]) => ({ value, label })),
      },
      {
        key: 'scope',
        label: '范围',
        options: [
          { value: 'mine', label: '我的' },
          { value: 'all', label: '全部' },
        ],
      },
    ],
    fields: [
      { name: 'taskType', label: '任务类型', required: true },
      { name: 'assigneeId', label: '经办人ID', type: 'number' },
      { name: 'deadline', label: '截止时间', type: 'date' },
    ],
  },
  disposals: {
    title: '资产处置',
    listPath: '/disposals',
    create: true,
    columns: [
      { key: 'assetId', label: '资产ID' },
      {
        key: 'disposalType',
        label: '处置方式',
        map: { sale: '出售', scrap: '报废', transfer: '划转' },
      },
      { key: 'reason', label: '原因' },
      { key: 'assessedValue', label: '评估价' },
      { key: 'actualAmount', label: '实际金额' },
      { key: 'status', label: '状态', map: L.DISPOSAL_STATUS },
    ],
    fields: [
      { name: 'assetId', label: '资产ID', type: 'number', required: true },
      {
        name: 'disposalType',
        label: '处置方式',
        type: 'select',
        options: [
          { value: 'sale', label: '出售' },
          { value: 'scrap', label: '报废' },
          { value: 'transfer', label: '划转' },
        ],
      },
      { name: 'reason', label: '原因', type: 'textarea' },
    ],
  },
  occupations: {
    title: '临时占用',
    listPath: '/occupations',
    create: true,
    columns: [
      { key: 'assetId', label: '资产ID' },
      { key: 'department', label: '占用部门' },
      { key: 'startDate', label: '起' },
      { key: 'endDate', label: '止' },
      {
        key: 'status',
        label: '状态',
        map: { draft: '草稿', approving: '审批中', occupied: '占用中', released: '已解除' },
      },
    ],
    fields: [
      { name: 'assetId', label: '资产ID', type: 'number', required: true },
      { name: 'department', label: '占用部门' },
      { name: 'startDate', label: '起', type: 'date' },
      { name: 'endDate', label: '止', type: 'date' },
      { name: 'reason', label: '事由', type: 'textarea' },
    ],
  },
  'self-uses': {
    title: '资产自用',
    listPath: '/self-uses',
    create: true,
    columns: [
      { key: 'assetId', label: '资产ID' },
      { key: 'department', label: '使用部门' },
      { key: 'purpose', label: '用途' },
      { key: 'startDate', label: '起' },
      { key: 'endDate', label: '止' },
      {
        key: 'status',
        label: '状态',
        map: { draft: '草稿', approving: '审批中', self_use: '自用中', ended: '已结束' },
      },
    ],
    fields: [
      { name: 'assetId', label: '资产ID', type: 'number', required: true },
      { name: 'department', label: '使用部门' },
      { name: 'purpose', label: '用途' },
      { name: 'startDate', label: '起', type: 'date' },
      { name: 'endDate', label: '止', type: 'date' },
    ],
  },
  evaluations: {
    title: '评估申请',
    listPath: '/evaluations',
    create: true,
    columns: [
      { key: 'assetId', label: '资产ID' },
      { key: 'purpose', label: '目的', map: { lease: '招租', disposal: '处置', filing: '备案' } },
      { key: 'institution', label: '评估机构' },
      {
        key: 'status',
        label: '状态',
        map: { applying: '申请中', accepted: '已受理', evaluating: '评估中', reported: '已出具' },
      },
      { key: 'resultValue', label: '评估价' },
    ],
    fields: [
      { name: 'assetId', label: '资产ID', type: 'number', required: true },
      {
        name: 'purpose',
        label: '目的',
        type: 'select',
        options: [
          { value: 'lease', label: '招租' },
          { value: 'disposal', label: '处置' },
          { value: 'filing', label: '备案' },
        ],
      },
      { name: 'institution', label: '评估机构' },
    ],
  },
  revitalization: {
    title: '空置盘活任务',
    listPath: '/revitalization',
    create: true,
    columns: [
      { key: 'assetId', label: '资产ID' },
      { key: 'vacantReason', label: '空置原因' },
      { key: 'planType', label: '盘活方案' },
      { key: 'assigneeId', label: '责任人ID' },
      { key: 'targetDate', label: '目标日' },
      {
        key: 'status',
        label: '状态',
        map: { pending: '待办', listing: '挂牌', signed: '已签约', done: '完成' },
      },
    ],
    fields: [
      { name: 'assetId', label: '资产ID', type: 'number', required: true },
      { name: 'vacantReason', label: '空置原因' },
      { name: 'planType', label: '盘活方案' },
      { name: 'assigneeId', label: '责任人ID', type: 'number' },
      { name: 'targetDate', label: '目标日', type: 'date' },
    ],
  },
  'business-plans': {
    title: '经营计划与预算',
    listPath: '/business-plans',
    create: true,
    columns: [
      { key: 'companyId', label: '公司ID' },
      { key: 'projectId', label: '项目ID' },
      { key: 'planYear', label: '年度' },
      { key: 'planMonth', label: '月份' },
      { key: 'targetRentalRate', label: '目标出租率' },
      { key: 'targetCollectionRate', label: '目标收缴率' },
      { key: 'targetIncome', label: '目标收入' },
      { key: 'version', label: '版本' },
    ],
    fields: [
      { name: 'companyId', label: '公司ID', type: 'number' },
      { name: 'planYear', label: '年度', type: 'number', required: true },
      { name: 'planMonth', label: '月份', type: 'number' },
      { name: 'targetRentalRate', label: '目标出租率', type: 'number' },
      { name: 'targetCollectionRate', label: '目标收缴率', type: 'number' },
      { name: 'targetIncome', label: '目标收入', type: 'number' },
    ],
  },
  'regulation/reports': {
    title: '监管报送',
    listPath: '/regulation/reports',
    columns: [
      { key: 'reportType', label: '报送类型' },
      { key: 'period', label: '期间' },
      { key: 'status', label: '状态', map: L.REGULATION_STATUS },
      { key: 'submittedAt', label: '报送时间' },
    ],
  },
  notifications: {
    title: '消息通知',
    listPath: '/notifications',
    columns: [
      { key: 'title', label: '标题' },
      { key: 'content', label: '内容' },
      { key: 'bizType', label: '业务类型' },
      { key: 'channel', label: '渠道', map: { in_app: '站内信', sms: '短信', mp: '小程序' } },
      { key: 'readAt', label: '已读时间' },
      { key: 'createdAt', label: '创建时间' },
    ],
  },
  'fixed-assets': {
    title: '固定资产清单',
    listPath: '/fixed-assets',
    create: true,
    deletable: true,
    columns: [
      { key: 'assetNo', label: '资产编号' },
      { key: 'name', label: '名称' },
      { key: 'assetType', label: '类型' },
      { key: 'originalValue', label: '原值' },
      { key: 'netValue', label: '净值' },
      { key: 'userName', label: '使用人' },
      { key: 'status', label: '状态' },
    ],
    fields: [
      { name: 'assetNo', label: '资产编号', required: true },
      { name: 'name', label: '名称', required: true },
      { name: 'assetType', label: '类型' },
      { name: 'originalValue', label: '原值', type: 'number' },
      { name: 'netValue', label: '净值', type: 'number' },
      { name: 'userName', label: '使用人' },
      { name: 'location', label: '位置' },
    ],
  },
  'intangible-assets': {
    title: '无形资产台账',
    listPath: '/intangible-assets',
    create: true,
    deletable: true,
    columns: [
      { key: 'assetNo', label: '编号' },
      { key: 'name', label: '名称' },
      { key: 'rightsType', label: '权利类型' },
      { key: 'originalValue', label: '原值' },
      { key: 'netValue', label: '净值' },
      { key: 'expiryDate', label: '到期日' },
      { key: 'status', label: '状态' },
    ],
    fields: [
      { name: 'assetNo', label: '编号', required: true },
      { name: 'name', label: '名称', required: true },
      { name: 'rightsType', label: '权利类型' },
      { name: 'originalValue', label: '原值', type: 'number' },
      { name: 'expiryDate', label: '到期日', type: 'date' },
    ],
  },
  'org/companies': {
    title: '公司管理',
    listPath: '/org/companies',
    create: true,
    columns: [
      { key: 'id', label: 'ID' },
      { key: 'name', label: '公司名称' },
      { key: 'companyType', label: '类型' },
      { key: 'parentId', label: '上级ID' },
      { key: 'status', label: '状态', map: { '1': '启用', '0': '停用' } },
    ],
    fields: [
      { name: 'name', label: '公司名称', required: true },
      { name: 'companyType', label: '类型' },
      { name: 'parentId', label: '上级ID', type: 'number' },
    ],
  },
  'org/departments': {
    title: '部门管理',
    listPath: '/org/departments',
    create: true,
    columns: [
      { key: 'id', label: 'ID' },
      { key: 'name', label: '部门名称' },
      { key: 'companyId', label: '公司ID' },
      { key: 'parentId', label: '上级ID' },
      { key: 'status', label: '状态', map: { '1': '启用', '0': '停用' } },
    ],
    fields: [
      { name: 'name', label: '部门名称', required: true },
      { name: 'companyId', label: '公司ID', type: 'number' },
      { name: 'parentId', label: '上级ID', type: 'number' },
    ],
  },
  'system/users': {
    title: '人员维护',
    listPath: '/system/users',
    create: true,
    columns: [
      { key: 'username', label: '账号' },
      { key: 'name', label: '姓名' },
      { key: 'phone', label: '手机号' },
      { key: 'companyId', label: '公司ID' },
      { key: 'status', label: '状态', map: { '1': '启用', '0': '停用' } },
    ],
    fields: [
      { name: 'username', label: '账号', required: true },
      { name: 'password', label: '初始密码' },
      { name: 'name', label: '姓名', required: true },
      { name: 'phone', label: '手机号' },
      { name: 'companyId', label: '公司ID', type: 'number' },
    ],
  },
  'system/roles': {
    title: '角色权限',
    listPath: '/system/roles',
    create: true,
    columns: [
      { key: 'id', label: 'ID' },
      { key: 'code', label: '角色编码' },
      { key: 'name', label: '角色名称' },
      {
        key: 'dataScope',
        label: '数据范围',
        map: { all: '全部', company: '公司', dept: '部门', project: '项目', self: '本人' },
      },
      { key: 'status', label: '状态', map: { '1': '启用', '0': '停用' } },
    ],
    fields: [
      { name: 'code', label: '角色编码', required: true },
      { name: 'name', label: '角色名称', required: true },
      {
        name: 'dataScope',
        label: '数据范围',
        type: 'select',
        options: [
          { value: 'all', label: '全部' },
          { value: 'company', label: '公司' },
          { value: 'dept', label: '部门' },
          { value: 'project', label: '项目' },
          { value: 'self', label: '本人' },
        ],
      },
    ],
  },
  'system/menus': {
    title: '菜单管理',
    listPath: '/system/menus/all',
    create: true,
    columns: [
      { key: 'id', label: 'ID' },
      { key: 'name', label: '菜单名称' },
      { key: 'code', label: '编码' },
      { key: 'path', label: '路由' },
      { key: 'menuType', label: '类型', map: { dir: '目录', menu: '菜单', button: '按钮' } },
      { key: 'sort', label: '排序' },
    ],
    fields: [
      { name: 'name', label: '菜单名称', required: true },
      { name: 'code', label: '编码' },
      { name: 'path', label: '路由' },
      { name: 'icon', label: '图标' },
      {
        name: 'menuType',
        label: '类型',
        type: 'select',
        options: [
          { value: 'dir', label: '目录' },
          { value: 'menu', label: '菜单' },
          { value: 'button', label: '按钮' },
        ],
      },
      { name: 'sort', label: '排序', type: 'number' },
    ],
  },
  'config/versions': {
    title: '参数版本留痕',
    listPath: '/config/versions',
    columns: [
      { key: 'configKey', label: '参数名' },
      { key: 'configValue', label: '参数值' },
      { key: 'effectiveDate', label: '生效日期' },
      { key: 'version', label: '版本号' },
      { key: 'oldValue', label: '旧值' },
      { key: 'newValue', label: '新值' },
    ],
  },
  'intelligence/templates': {
    title: '报告模板',
    listPath: '/intelligence/templates',
    columns: [
      { key: 'templateCode', label: '模板编码' },
      { key: 'name', label: '模板名称' },
      { key: 'version', label: '版本' },
      { key: 'enabled', label: '启用', render: (r) => (r.enabled ? '是' : '否') },
    ],
  },
  'intelligence/sessions': {
    title: 'Agent 会话',
    listPath: '/intelligence/sessions',
    columns: [
      { key: 'id', label: 'ID' },
      { key: 'title', label: '标题' },
      { key: 'companyId', label: '数据范围公司ID' },
      { key: 'status', label: '状态', map: { active: '活跃', archived: '已归档' } },
      { key: 'createdAt', label: '创建时间' },
    ],
  },
  'migrations/batches': {
    title: '期初迁移批次',
    listPath: '/migrations/batches',
    columns: [
      { key: 'id', label: '批次ID' },
      { key: 'cutoverDate', label: '基准日' },
      { key: 'status', label: '状态', map: L.MIGRATION_STATUS },
      { key: 'sourceFile', label: '来源文件' },
      { key: 'balanceResult', label: '试算平衡差额' },
      { key: 'lockedAt', label: '锁定时间' },
    ],
  },
  meters: {
    title: '表计档案与抄表',
    listPath: '/meters',
    create: true,
    columns: [
      { key: 'id', label: 'ID' },
      { key: 'assetId', label: '资产ID' },
      { key: 'contractId', label: '合同ID' },
      { key: 'meterType', label: '表类型', map: L.METER_TYPE },
      { key: 'meterNo', label: '表号' },
      { key: 'multiplier', label: '倍率' },
    ],
    fields: [
      { name: 'assetId', label: '资产ID', type: 'number', required: true },
      {
        name: 'meterType',
        label: '表类型',
        type: 'select',
        options: Object.entries(L.METER_TYPE).map(([value, label]) => ({ value, label })),
      },
      { name: 'meterNo', label: '表号' },
      { name: 'multiplier', label: '倍率', type: 'number' },
    ],
  },
  'apportion-configs': {
    title: '公摊配置',
    listPath: '/apportion-configs',
    create: true,
    columns: [
      { key: 'id', label: 'ID' },
      { key: 'company_id', label: '公司ID' },
      { key: 'project_id', label: '项目ID' },
      {
        key: 'apportion_basis',
        label: '分摊口径',
        map: { area: '按面积', meter: '按表数', head: '按人头', usage: '按用量' },
      },
      { key: 'enabled', label: '启用', render: (r) => (r.enabled ? '是' : '否') },
    ],
    fields: [
      { name: 'companyId', label: '公司ID', type: 'number' },
      { name: 'projectId', label: '项目ID', type: 'number' },
      {
        name: 'apportionBasis',
        label: '分摊口径',
        type: 'select',
        options: [
          { value: 'area', label: '按面积' },
          { value: 'meter', label: '按表数' },
          { value: 'head', label: '按人头' },
          { value: 'usage', label: '按用量' },
        ],
      },
      { name: 'enabled', label: '启用', type: 'boolean' },
    ],
  },
  'finance/bank-flows': {
    title: '银行对账',
    listPath: '/finance/bank-flows/unmatched',
    columns: [
      { key: 'flowNo', label: '流水号' },
      { key: 'amount', label: '金额' },
      { key: 'direction', label: '方向', map: { in: '收入', out: '支出' } },
      { key: 'tradeDate', label: '交易日期' },
      { key: 'summary', label: '摘要' },
      {
        key: 'matchStatus',
        label: '匹配状态',
        map: { matched: '已匹配', unmatched: '未达', partial: '部分匹配' },
      },
    ],
  },
  'finance/vouchers': {
    title: '财务凭证',
    listPath: '/finance/vouchers',
    columns: [
      { key: 'voucherNo', label: '凭证号' },
      { key: 'bizType', label: '业务类型' },
      { key: 'bizId', label: '业务单ID' },
      { key: 'status', label: '状态', map: { pending: '待推送', pushed: '已推送' } },
      { key: 'pushedAt', label: '推送时间' },
    ],
  },
  mortgages: {
    title: '抵押列表',
    listPath: '/mortgages',
    columns: [
      { key: 'assetId', label: '资产ID' },
      { key: 'mortgagee', label: '抵押权人' },
      { key: 'amount', label: '抵押金额' },
      { key: 'startDate', label: '起' },
      { key: 'endDate', label: '止' },
      { key: 'status', label: '状态', map: L.MORTGAGE_STATUS },
    ],
  },
  certificates: {
    title: '权证信息',
    listPath: '/certificates',
    columns: [
      { key: 'assetId', label: '资产ID' },
      { key: 'certType', label: '证照类型' },
      { key: 'certNo', label: '证号' },
      { key: 'ownerName', label: '产权人' },
      { key: 'registerDate', label: '登记日期' },
      { key: 'mortgageStatus', label: '抵押状态', map: { none: '无抵押', mortgaged: '在押' } },
    ],
  },
  'asset-transfers': {
    title: '资产调拨',
    listPath: '/asset-transfers',
    create: true,
    columns: [
      { key: 'assetId', label: '资产ID' },
      { key: 'fromCompanyId', label: '调出公司' },
      { key: 'toCompanyId', label: '调入公司' },
      {
        key: 'transferType',
        label: '类型',
        map: { with_contract: '带租调拨', vacant: '空置调拨' },
      },
      { key: 'status', label: '状态', map: L.TRANSFER_STATUS },
      { key: 'effectiveDate', label: '生效日期' },
    ],
    fields: [
      { name: 'assetId', label: '资产ID', type: 'number', required: true },
      { name: 'fromCompanyId', label: '调出公司ID', type: 'number' },
      { name: 'toCompanyId', label: '调入公司ID', type: 'number', required: true },
      {
        name: 'transferType',
        label: '类型',
        type: 'select',
        options: [
          { value: 'with_contract', label: '带租调拨' },
          { value: 'vacant', label: '空置调拨' },
        ],
      },
      { name: 'effectiveDate', label: '生效日期', type: 'date' },
    ],
  },
};
