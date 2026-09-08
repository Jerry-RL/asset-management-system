// 全局枚举标签映射（与后端状态机/字典对齐）

export const LEASE_CONTROL_STATUS: Record<string, string> = {
  vacant: '空置',
  leasing: '招租中',
  leased: '在租',
  partial_leased: '部分出租',
  self_use: '自用',
  occupied: '占用',
  vacating: '退租中',
  disposing: '处置中',
  exited: '已退出',
};

export const CONTRACT_STATUS: Record<string, string> = {
  draft: '草稿',
  approving: '审批中',
  active: '生效',
  expiring: '即将到期',
  renewable: '需续签',
  expired: '已到期',
  terminating: '提前解约中',
  terminated: '已终止',
  voided: '已作废',
};

export const BILL_STATUS: Record<string, string> = {
  pending_issue: '待出账',
  unpaid: '待缴',
  partial_paid: '部分缴',
  paid: '已缴',
  reduced: '已减免',
  voided: '已作废',
};

export const ASSET_TYPE: Record<string, string> = {
  property: '房产类',
  land: '土地类',
};

export const RENT_TYPE: Record<string, string> = {
  fixed_monthly: '固定月租',
  fixed_yearly: '固定年租',
  per_area: '按面积',
  per_unit: '按套',
  negotiable: '面议',
};

export const PAYMENT_CYCLE: Record<string, string> = {
  monthly: '月缴',
  quarterly: '季缴',
  yearly: '年缴',
};

export const REPAIR_STATUS: Record<string, string> = {
  pending_review: '待审核',
  dispatched: '待接单',
  accepted: '已接单',
  repairing: '维修中',
  pending_accept: '待验收',
  completed: '已完成',
  rejected: '已驳回',
};

export const ALERT_STATUS: Record<string, string> = {
  pending: '待处理',
  processing: '处理中',
  escalated: '已升级',
  closed: '已关闭',
};

export const APPROVAL_STATUS: Record<string, string> = {
  pending: '审批中',
  approved: '已通过',
  rejected: '已驳回',
};

export const DISPOSAL_STATUS: Record<string, string> = {
  draft: '草稿',
  approving: '审批中',
  rejected: '已驳回',
  pending_execute: '待执行',
  executing: '执行中',
  completed: '已完成',
};

export const REFUND_STATUS: Record<string, string> = {
  applying: '申请中',
  approving: '审批中',
  executing: '执行中',
  completed: '已完成',
  rejected: '已驳回',
};

export const INVOICE_STATUS: Record<string, string> = {
  pending_issue: '待开票',
  issuing: '开票中',
  issued: '已开票',
  red_flushing: '红冲中',
  red_flushed: '已红冲',
  failed: '开票失败',
};

export const TENDER_STATUS: Record<string, string> = {
  open: '进行中',
  closed: '已结束',
  flowed: '已流标',
};

export const VACATE_STATUS: Record<string, string> = {
  applying: '申请中',
  inspecting: '清场验收中',
  settling: '结算中',
  completed: '已完成',
};

export const TASK_STATUS: Record<string, string> = {
  pending: '待办',
  completed: '已完成',
};

export const PAYMENT_CONFIRM: Record<string, string> = {
  pending: '待确认',
  confirmed: '已到账',
};

export const PAYMENT_METHOD: Record<string, string> = {
  cash: '现金',
  bank_transfer: '转账',
  wechat: '微信',
  alipay: '支付宝',
};

export const CHANNEL: Record<string, string> = {
  user_mp: '用户端',
  worker_mp: '工作端',
  pc: 'PC',
  in_app: '站内信',
  sms: '短信',
  mp: '小程序',
};

export const METER_TYPE: Record<string, string> = {
  water: '水表',
  electric: '电表',
  gas: '燃气表',
};

export const MORTGAGE_STATUS: Record<string, string> = {
  active: '在押',
  released: '已解押',
};

export const MIGRATION_STATUS: Record<string, string> = {
  importing: '导入中',
  reconciled: '已对账',
  locked: '已锁定',
};

export const REGULATION_STATUS: Record<string, string> = {
  draft: '草稿',
  reviewed: '已复核',
  submitted: '已报送',
};

export const TRANSFER_STATUS: Record<string, string> = {
  draft: '草稿',
  approving: '审批中',
  approved: '已批准',
  completed: '已完成',
};

export const BIZ_TYPE: Record<string, string> = {
  contract: '合同审批',
  contract_low_price: '低价合同审批',
  contract_special: '特殊合同审批',
  disposal: '资产处置',
  disposal_major: '重大资产处置',
  fee_relief: '费用减免',
  fee_relief_major: '大额费用减免',
  rent_adjust: '租金调价',
  refund: '退款冲正',
  occupation: '临时占用',
  self_use: '资产自用',
  mortgage_release: '解押审批',
  asset_audit_variance: '盘点差异审批',
  transfer: '资产调拨',
  evaluation: '评估申请',
};

export const AGENT_REPORT_STATUS: Record<string, string> = {
  draft: '草稿',
  generating: '生成中',
  ready: '已生成',
  verified: '已核验',
  failed: '失败',
};

/** 通用启用状态 */
export const ENABLE_STATUS: Record<string, string> = {
  '1': '启用',
  '0': '停用',
  active: '启用',
  inactive: '停用',
  closed: '已关闭',
};

/** 字段 key → 中文列名（详情/报表兜底，避免直接展示英文 key） */
export const FIELD_LABELS: Record<string, string> = {
  id: 'ID',
  assetId: '资产ID',
  assetNo: '资产编号',
  name: '名称',
  title: '标题',
  address: '地址',
  area: '面积(㎡)',
  assetType: '资产类型',
  leaseControlStatus: '租控状态',
  projectId: '项目ID',
  companyId: '公司ID',
  operatingCompanyId: '经营公司ID',
  baseRentFloor: '租金底价',
  vacantReason: '空置原因',
  vacantSince: '空置起始',
  vacantDays: '空置天数',
  contractId: '合同ID',
  contractNo: '合同编号',
  tenantId: '租户ID',
  status: '状态',
  startDate: '开始日期',
  endDate: '结束日期',
  rentAmount: '租金',
  depositAmount: '保证金',
  leaseArea: '租赁面积',
  billNo: '账单编号',
  billType: '账单类型',
  billId: '账单ID',
  dueDate: '应付日',
  amount: '金额',
  paidAmount: '已付金额',
  lateFeeAmount: '滞纳金',
  arrears: '欠费',
  description: '说明',
  vendorId: '维修公司ID',
  assigneeId: '经办人ID',
  completedAt: '完成时间',
  createdAt: '创建时间',
  updatedAt: '更新时间',
  createdBy: '创建人',
  updatedBy: '更新人',
  paymentId: '收款ID',
  paymentNo: '收款单号',
  method: '方式',
  channel: '渠道',
  confirmStatus: '到账状态',
  paidAt: '收款时间',
  bizType: '业务类型',
  bizId: '业务单ID',
  taskId: '任务ID',
  instanceId: '实例ID',
  nodeId: '节点',
  taskStatus: '任务状态',
  instanceStatus: '实例状态',
  currentNode: '当前节点',
  submittedBy: '提交人',
  submittedAt: '提交时间',
  format: '格式',
  verified: '已核验',
  reportType: '报表类型',
  period: '期间',
  longitude: '经度',
  latitude: '纬度',
  city: '城市',
  province: '省份',
  phone: '手机号',
  reason: '原因',
  result: '结果',
  remark: '备注',
};

export const BILL_TYPE: Record<string, string> = {
  rent: '租金',
  utility: '水电',
  other: '其他',
  deposit: '保证金',
  late_fee: '滞纳金',
};

/** 取字段中文名；未知 key 时尽量不把 camelCase 直接甩给用户 */
export const fieldLabel = (key: string): string => {
  if (FIELD_LABELS[key]) return FIELD_LABELS[key];
  // 兜底：createdAt → Created At 风格不够中文，直接返回 key 并在调用处可过滤
  return key;
};

/** 枚举值 → 中文；无映射时返回原值字符串 */
export const enumLabel = (map: Record<string, string>, value: unknown, fallback = '-'): string => {
  if (value == null || value === '') return fallback;
  const s = String(value);
  return map[s] ?? s;
};
