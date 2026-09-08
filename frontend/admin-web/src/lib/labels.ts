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
