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
  // 项目属性口径（历史数据）
  property: '房产类',
  land: '土地类',
  // 「资产管理字典 → 资产类型」字典项
  low_rent_housing: '廉租房',
  public_rental_housing: '公租房',
  affordable_housing: '经济适用房',
  commercial_housing: '商品房',
  housing_reform: '房改房',
  resettlement_housing: '安置房',
  self_use_asset: '自用资产',
  factory_building: '厂房',
  gymnasium: '体育馆',
  residential: '住宅',
};

/** 部分租赁状态（sys_dict_type.code = partial_lease_status） */
export const PARTIAL_LEASE_STATUS: Record<string, string> = {
  support: '支持',
  not_support: '不支持',
};

/** 资产性质（sys_dict_type.code = asset_nature） */
export const ASSET_NATURE: Record<string, string> = {
  operational: '经营性',
  public_welfare: '公益性',
  financial: '金融性',
  resource: '资源性',
  self_owned: '自有',
};

/** 资产来源（sys_dict_type.code = asset_source）；「项目属性」级联决定其可见项 */
export const ASSET_SOURCE_DICT_CODE = 'asset_source';

export const ASSET_SOURCE: Record<string, string> = {
  investment_construction: '投资建设',
  acquisition_reserve: '收储',
  transferred: '移交资产',
  allocated_in: '划入',
  leased_in: '租入',
  self_funded_construction: '自筹建设',
  entrusted: '托管',
  other: '其他',
  // 土地类专属来源（「项目属性 → 关联字典值」中配置为土地类可见）
  land_grant: '出让',
  administrative_allocation: '行政划拨',
};

/** 资产权属（sys_dict_type.code = asset_ownership） */
export const ASSET_OWNERSHIP_DICT_CODE = 'asset_ownership';

export const ASSET_OWNERSHIP: Record<string, string> = {
  joint_operation: '联营资产',
  entrusted_operation: '委托经营资产',
  self_owned: '自有资产',
  custodial: '代管资产',
  transferred: '移交资产',
  other: '其他',
};

/** 建筑规划（sys_dict_type.code = building_plan） */
export const BUILDING_PLAN: Record<string, string> = {
  residential_building: '住宅建筑',
  commercial_building: '商业建筑',
  office_building: '办公建筑',
  industrial_building: '工业建筑',
  public_building: '公共建筑',
  complex_building: '综合建筑',
};

/** 建筑结构（sys_dict_type.code = building_structure） */
export const BUILDING_STRUCTURE: Record<string, string> = {
  brick_wood: '砖木结构',
  steel_concrete: '钢混结构',
  shear_wall: '剪力墙结构',
  brick_concrete: '砖混结构',
  frame: '框架结构',
  circular_single_suspension: '圆形单层悬索结构',
  gas: '气体结构',
  tube: '简体结构',
  mixed: '混合结构',
  arch: '拱结构',
  circular_double_suspension: '圆形双层寻索结构',
  space_frame: '网架结构',
  mo_structure: '摸结构',
  space_thin_wall: '空间暴毙结构',
  flat_slab: '无梁楼盖结构',
  shell: '壳体结构',
  orthogonal_cable_net: '双向正交索网结构',
  truss: '衍架结构',
  longitudinal_wall_bearing: '纵墙承重',
};

/** 资产用途（sys_dict_type.code = asset_usage） */
export const ASSET_USAGE: Record<string, string> = {
  office: '办公',
  commercial: '商业',
  residential: '住宅',
  garage: '车库',
};

/** 资产户型（sys_dict_type.code = asset_house_type） */
export const ASSET_HOUSE_TYPE: Record<string, string> = {
  one_bed_one_living: '一室一厅',
  two_bed_one_living: '两室一厅',
  three_bed_two_living: '三室两厅',
  four_bed_two_living: '四室两厅',
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

/** 公司类型（公司管理字典 → 公司类型）；group / subsidiary 为存量数据取值 */
export const COMPANY_TYPE: Record<string, string> = {
  group: '集团',
  provincial_sasac: '省国资委',
  public_institution: '事业单位',
  state_owned: '国企',
  private_enterprise: '私企',
  subsidiary: '子公司',
};

/** 通用启用状态 */
export const ENABLE_STATUS: Record<string, string> = {
  '1': '启用',
  '0': '停用',
  active: '启用',
  inactive: '停用',
  closed: '已关闭',
};

/** 项目类型对应的字典编码：「系统管理 → 系统字典 → 资产管理字典 → 项目属性」 */
export const PROJECT_TYPE_DICT_CODE = 'project_property';

/**
 * 项目类型：表单选项与列表映射均取自上述字典，此处仅作字典未就绪时的回显兜底。
 * property / land 与字典种子数据对齐；park / building / other 为历史数据取值，保留以兼容旧项目展示。
 */
export const PROJECT_TYPE: Record<string, string> = {
  property: '房产类',
  land: '土地类',
  park: '园区',
  building: '楼宇',
  other: '其他',
};

/** 项目状态：正常 / 停用 */
export const PROJECT_STATUS: Record<string, string> = {
  '1': '正常',
  '0': '停用',
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
  district: '区/县',
  type: '类型',
  imageUrl: '项目图片',
  imageFileId: '图片附件ID',
  usageType: '用途',
  houseType: '资产房型',
  zones: '分区配置',
  zoneId: '所属分区',
  zoneName: '分区',
  assetArea: '资产面积(㎡)',
  assetCount: '资产数',
  idleCount: '闲置宗数',
  revitalizedCount: '盘活宗数',
  utilizationRate: '资产利用率(%)',
  // ---- 资产表单新增字段 ----
  assetCompanyId: '资产公司',
  assetCompanyName: '资产公司',
  propertyCompanyName: '产权公司',
  floorNo: '分区楼层',
  partialLeaseStatus: '部分租赁状态',
  assetNature: '资产性质',
  buildingPlan: '建筑规划',
  registeredAt: '登记入库时间',
  responsibleDepartmentId: '责任部门',
  responsibleDepartmentName: '责任部门',
  responsibleUserId: '责任人',
  responsibleUserName: '责任人',
  phone: '手机号',
  reason: '原因',
  result: '结果',
  remark: '备注',
  username: '账号',
  password: '初始密码',
  departmentId: '所属部门',
  departmentName: '所属部门',
  companyName: '所属公司',
  parentName: '上级',
  shortName: '简称',
  roleIds: '角色',
  roleNames: '角色',
  lastLoginAt: '最近登录',
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
