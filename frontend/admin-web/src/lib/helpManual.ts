export interface HelpModuleGuide {
  path: string;
  title: string;
  group: string;
  /** 模块用途（一句话） */
  purpose: string;
  /** 主要能力 */
  capabilities: string[];
  /** 典型操作 */
  operations: string[];
}

type GuideBody = Omit<HelpModuleGuide, 'path' | 'title' | 'group'>;

const GUIDES: Record<string, GuideBody> = {
  '/': {
    purpose: '按业务分类快速进入高频功能，查看近期经营待办概览。',
    capabilities: ['经营性 / 固定资产 / 数智管理入口', '工作台快捷入口', '近期日历待办摘要'],
    operations: ['从分类卡片进入目标模块', '用快捷入口处理日常作业', '查看待办后跳转对应业务页'],
  },
  '/ops-calendar': {
    purpose: '集中展示合同到期、账单应付、任务截止等经营日程，避免漏办。',
    capabilities: ['多类型经营事件日历', '按日查看待办明细', '支持补充备注类提醒'],
    operations: ['切换月份查看排期', '点击日期处理到期事项', '新增提醒并跟进闭环'],
  },
  '/dashboard': {
    purpose: '一屏掌握出租率、收缴率、空置与欠费等核心经营指标。',
    capabilities: ['经营 KPI 总览', '趋势与结构分析', '下钻到资产/合同相关页面'],
    operations: ['按公司或项目筛选指标', '关注异常指标并跳转处理', '定期复盘经营达成情况'],
  },
  '/asset-map': {
    purpose: '在地图上展示资产空间分布与租控状态，辅助巡查与盘活决策。',
    capabilities: ['资产点位展示', '按状态筛选', '点击查看资产摘要'],
    operations: ['按区域定位资产', '筛选空置/在租点位', '进入资产详情继续处置'],
  },
  '/reports': {
    purpose: '输出资产、租赁、收费、维修等台账报表，支撑经营分析与报送。',
    capabilities: ['多类台账报表', '条件筛选与分页', '导出下载'],
    operations: ['选择报表类型', '设置筛选条件查询', '导出结果用于汇报'],
  },
  '/business-plans': {
    purpose: '编制与跟踪经营计划、预算目标，对照实际完成情况。',
    capabilities: ['计划/预算登记', '目标与实际对比', '版本留痕查看'],
    operations: ['新建年度或项目计划', '录入预算指标', '按周期复盘偏差'],
  },
  '/dashboard/consolidate': {
    purpose: '汇总集团及下属公司经营数据，形成合并视角的经营看板。',
    capabilities: ['多法人数据合并', '公司维度对比', '集团 KPI 汇总'],
    operations: ['选择合并范围', '查看集团汇总指标', '对比各子公司表现'],
  },
  '/tasks': {
    purpose: '统一承载待办、超时与协同任务，推动跨岗位闭环。',
    capabilities: ['待办列表与超时提醒', '任务分派与处理', '处理结果回写'],
    operations: ['按状态筛选我的待办', '打开任务完成业务动作', '确认办结或转交'],
  },
  '/approvals': {
    purpose: '集中处理合同、减免、调价、处置等审批单据。',
    capabilities: ['待审/已审列表', '同意/驳回', '查看审批上下文'],
    operations: ['打开待审单据核对业务信息', '填写意见并审批', '跟踪被驳回单据整改'],
  },
  '/notifications': {
    purpose: '接收系统消息、业务提醒与公告，避免关键信息遗漏。',
    capabilities: ['消息列表', '已读/未读管理', '跳转到业务单据'],
    operations: ['查看未读消息', '点击跳转处理', '批量标记已读'],
  },
  '/alerts/records': {
    purpose: '查看预警触发记录，及时处置合同到期、欠费、权证等风险。',
    capabilities: ['预警记录查询', '处理状态跟踪', '关联业务对象'],
    operations: ['按规则类型筛选预警', '确认并处理风险事项', '关闭已处置预警'],
  },
  '/alerts/rules': {
    purpose: '配置预警阈值、通知对象与触发条件，实现风险前移。',
    capabilities: ['规则启停', '阈值与提前量配置', '通知渠道设置'],
    operations: ['新建或编辑规则', '启用/停用规则', '验证触发效果'],
  },
  '/regulation/reports': {
    purpose: '生成并管理监管报送材料，满足上级监管与留痕要求。',
    capabilities: ['报送清单', '材料生成/上传', '报送状态跟踪'],
    operations: ['选择报送周期', '核对数据后提交', '归档历史报送'],
  },
  '/contracts': {
    purpose: '管理租赁合同全生命周期：签约、履约、续签与到期提醒。',
    capabilities: ['合同台账与详情', '审批与履约跟踪', '续签/到期管理'],
    operations: ['新建或导入合同', '提交审批并跟踪状态', '到期前提醒并办理续签'],
  },
  '/vacate-orders': {
    purpose: '办理退租清场、验收与保证金结算，规范退租闭环。',
    capabilities: ['退租单管理', '清场验收协同', '保证金退还结算'],
    operations: ['发起退租申请', '跟进清场验收结果', '完成结算并退还保证金'],
  },
  '/lease-listings': {
    purpose: '发布与管理招租信息，推进空置资产对外租赁。',
    capabilities: ['招租发布', '租金与状态管理', '对接公开报名'],
    operations: ['选择空置资产发布招租', '维护招租条件', '跟进意向客户'],
  },
  '/tender/announcements': {
    purpose: '组织公开招租公告、报名审核与中标结果，保障程序合规。',
    capabilities: ['公告发布', '报名审核', '中标后转入租赁'],
    operations: ['发布公开招租公告', '审核报名材料', '中标后添加租赁合同'],
  },
  '/tenants': {
    purpose: '维护客商/租户主数据，支撑合同签约与催缴沟通。',
    capabilities: ['租户档案', '联系人与证照信息', '关联合同查询'],
    operations: ['新增或完善租户资料', '核验证照信息', '从租户入口查看在租合同'],
  },
  '/lease-bundles': {
    purpose: '将多资产组合出租或拆分租赁，满足复杂招商场景。',
    capabilities: ['组合包配置', '拆分租赁', '关联合同生成'],
    operations: ['选择资产组建组合包', '配置租金分摊方式', '生成对应租赁合同'],
  },
  '/contract-templates': {
    purpose: '维护合同 Word 模板与变量槽位，提升出合同效率与规范性。',
    capabilities: ['模板上传/版本管理', '槽位配置', '生成合同文档'],
    operations: ['上传标准模板', '配置变量槽位', '签约时选用模板出文'],
  },
  '/billing/bills': {
    purpose: '收费大厅：查看应收/实收，登记收款并跟踪账单状态。',
    capabilities: ['账单查询与汇总', '线下收款登记', '收缴率统计'],
    operations: ['按合同或账期筛选账单', '登记租金/其他收费', '核销后核对收缴率'],
  },
  '/payments/pending-confirm': {
    purpose: '确认工作端现场收款记录，防止漏确认或重复入账。',
    capabilities: ['待确认收款列表', '确认/驳回', '入账联动账单'],
    operations: ['核对现场收款凭证', '确认入账', '异常收款驳回并沟通'],
  },
  '/dunning/auto': {
    purpose: '按规则自动生成催缴任务，提高欠费催收效率。',
    capabilities: ['自动催缴策略', '批量生成任务', '执行结果回看'],
    operations: ['配置或启停自动催缴', '查看生成结果', '跟进未回款账单'],
  },
  '/dunning/records': {
    purpose: '沉淀催缴过程记录，便于复盘催收效果与责任追踪。',
    capabilities: ['催缴历史查询', '渠道与结果登记', '关联欠费账单'],
    operations: ['按租户/合同查询催缴记录', '补充催缴结果', '对无效催缴调整策略'],
  },
  '/payments': {
    purpose: '查询全部收款流水，支撑对账与财务核对。',
    capabilities: ['收款明细', '支付方式筛选', '关联账单查看'],
    operations: ['按时间/方式筛选收款', '核对异常流水', '跳转账单核对'],
  },
  '/invoices': {
    purpose: '管理开票申请、开具与发票状态，满足租户开票需求。',
    capabilities: ['开票申请处理', '发票台账', '红冲/作废跟踪'],
    operations: ['审核开票申请', '开具并回写发票号', '处理红冲或作废'],
  },
  '/adjustments/fee-reliefs': {
    purpose: '发起并审批费用减免，规范优惠与特殊减免留痕。',
    capabilities: ['减免申请', '审批流', '账单金额联动'],
    operations: ['提交减免申请及依据', '跟踪审批', '审批通过后核对账单'],
  },
  '/adjustments/rent-adjusts': {
    purpose: '办理租金调价并留痕，保障调价合规与后续账单正确。',
    capabilities: ['调价申请', '生效日期控制', '审批与账单影响'],
    operations: ['选择合同发起调价', '设定新租金与生效日', '审批后核验新账单'],
  },
  '/refunds': {
    purpose: '处理退款与冲正，纠正错收、多收或退租结算退款。',
    capabilities: ['退款申请', '冲正记录', '资金与账单联动'],
    operations: ['发起退款/冲正', '审批后执行', '核对账单与收款余额'],
  },
  '/meters': {
    purpose: '维护水电等表计档案，录入抄表数据支撑能耗计费。',
    capabilities: ['表计档案', '抄表录入', '用量计算'],
    operations: ['绑定资产表计', '按周期抄表', '生成用量供出账'],
  },
  '/apportion-configs': {
    purpose: '配置公摊规则，将公共能耗/费用按面积或约定分摊到租户。',
    capabilities: ['公摊规则配置', '分摊基数设置', '出账联动'],
    operations: ['新建公摊方案', '绑定适用资产范围', '出账前核对分摊结果'],
  },
  '/invoice-tax-rates': {
    purpose: '维护发票税率与税目，保证开票税额计算正确。',
    capabilities: ['税率档案', '税目映射', '启用状态管理'],
    operations: ['新增/调整税率', '关联费用类型', '开票前核验税率'],
  },
  '/finance/bank-flows': {
    purpose: '导入并核对银行流水，完成应收与实收对账。',
    capabilities: ['银行流水导入', '自动/手工匹配', '差异处理'],
    operations: ['导入银行流水', '匹配收款或账单', '处理未匹配差异'],
  },
  '/finance/vouchers': {
    purpose: '生成与查询财务凭证，支撑业财衔接与月结。',
    capabilities: ['凭证列表', '按业务生成凭证', '导出对接财务系统'],
    operations: ['按期间查询凭证', '核对业务来源', '导出或同步财务'],
  },
  '/assets': {
    purpose: '经营性资产主台账，维护一物一档并驱动租控状态。',
    capabilities: ['资产增删改查', '导入导出', '二维码与一物一档'],
    operations: ['新增或导入资产', '维护权属与面积信息', '打开档案查看合同/缴费/维修'],
  },
  '/projects': {
    purpose: '管理资产所属项目/园区，作为资产归集与权限边界。',
    capabilities: ['项目档案', '地址与组织归属', '关联资产统计'],
    operations: ['新建项目', '维护项目基础信息', '在资产台账中按项目筛选'],
  },
  '/certificates': {
    purpose: '登记权证信息并跟踪有效期，支撑合规与抵押评估。',
    capabilities: ['权证台账', '到期提醒', '附件留存'],
    operations: ['补录权证', '关注到期预警', '更新换证信息'],
  },
  '/evaluations': {
    purpose: '发起资产评估申请并跟踪评估结果，服务处置与融资。',
    capabilities: ['评估申请', '状态跟踪', '结果归档'],
    operations: ['选择资产发起评估', '上传评估报告', '结果用于处置/融资决策'],
  },
  '/mortgages': {
    purpose: '管理资产抵押登记与到期情况，控制融资风险。',
    capabilities: ['抵押列表', '期限与金额', '到期预警'],
    operations: ['登记抵押信息', '跟踪到期日', '解除或续期抵押'],
  },
  '/asset-transfers': {
    purpose: '办理资产在组织间调拨，保证权属与账实一致。',
    capabilities: ['调拨申请', '审批流转', '调拨后归属更新'],
    operations: ['发起调拨', '审批确认', '核对调入方台账'],
  },
  '/assets/structure-logs': {
    purpose: '留痕资产拆分/合并操作，保证结构变更可追溯。',
    capabilities: ['拆分合并日志', '前后资产关系', '操作人与时间'],
    operations: ['查询历史结构变更', '核对衍生资产编码', '审计追溯'],
  },
  '/fixed-assets': {
    purpose: '管理非经营性固定资产清单，区分经营性房产台账。',
    capabilities: ['固资台账', '分类与状态', '盘点联动'],
    operations: ['录入固资卡片', '维护使用状态', '配合盘点计划清查'],
  },
  '/fixed-assets/inventories': {
    purpose: '组织固定资产盘点计划与实盘结果，核对账实差异。',
    capabilities: ['盘点计划', '盘点明细', '差异处理'],
    operations: ['创建盘点计划', '录入实盘结果', '处理盘盈盘亏'],
  },
  '/intangible-assets': {
    purpose: '登记商标、专利等无形资产，完善全口径资产台账。',
    capabilities: ['无形资产台账', '有效期管理', '价值信息'],
    operations: ['新增无形资产', '维护权利期限', '到期前续展提醒'],
  },
  '/repairs': {
    purpose: '受理报修工单，派工维修公司并跟踪完工验收。',
    capabilities: ['工单受理', '派工与进度', 'SLA 与验收'],
    operations: ['创建或接单报修', '指派维修公司', '完工验收并关闭'],
  },
  '/inspections': {
    purpose: '记录日常巡查与隐患，推动整改闭环。',
    capabilities: ['巡查记录', '隐患登记', '整改跟踪'],
    operations: ['录入巡查结果', '登记隐患并派发整改', '复查销项'],
  },
  '/revitalization': {
    purpose: '跟踪空置资产盘活进展，缩短空置周期。',
    capabilities: ['空置清单', '盘活措施登记', '进展跟踪'],
    operations: ['筛选长期空置资产', '制定盘活措施', '跟进招租或改造结果'],
  },
  '/asset-audits': {
    purpose: '开展经营性资产盘点，核对现场占用与系统租控是否一致。',
    capabilities: ['盘点计划', '现场核对项', '差异整改'],
    operations: ['创建经营盘点计划', '录入现场核对结果', '处理账实不符项'],
  },
  '/occupations': {
    purpose: '登记临时占用申请与期限，规范非租赁占用场景。',
    capabilities: ['占用申请', '期限控制', '到期提醒'],
    operations: ['新建临时占用', '审批后生效', '到期收回或续期'],
  },
  '/self-uses': {
    purpose: '登记资产自用情况，区分对外出租与内部使用。',
    capabilities: ['自用登记', '用途与期限', '结束自用'],
    operations: ['发起自用申请', '维护自用用途', '结束后恢复可租状态'],
  },
  '/disposals': {
    purpose: '办理资产处置流程，覆盖审批、执行与结果归档。',
    capabilities: ['处置申请', '审批与执行', '处置结果留痕'],
    operations: ['发起处置', '跟踪审批与执行', '归档处置结果并更新台账'],
  },
  '/vendors': {
    purpose: '维护维修公司等供应商档案，支撑报修派工。',
    capabilities: ['供应商档案', '联系方式', '服务范围'],
    operations: ['新增维修公司', '维护联系人', '在报修工单中选用'],
  },
  '/intelligence/reports': {
    purpose: '查看智能 Agent 生成的经营分析报告，辅助决策。',
    capabilities: ['报告列表', '报告详情阅读', '按模板生成'],
    operations: ['选择报告查看结论', '按需重新生成', '将结论用于经营例会'],
  },
  '/intelligence/sessions': {
    purpose: '与智能助手会话，查询经营数据或生成分析草稿。',
    capabilities: ['会话历史', '问答交互', '上下文追问'],
    operations: ['新建会话提问', '追问细化分析', '将结论沉淀为报告'],
  },
  '/intelligence/templates': {
    purpose: '配置 Agent 报告模板，统一分析口径与输出结构。',
    capabilities: ['模板管理', '章节与指标配置', '启用版本'],
    operations: ['新建报告模板', '配置关注指标', '用于一键出报告'],
  },
  '/system/users': {
    purpose: '维护后台人员账号，保障登录与岗位权限基础数据。',
    capabilities: ['用户增删改', '启停账号', '绑定角色与组织'],
    operations: ['新建账号并分配角色', '重置密码', '停用离职人员'],
  },
  '/system/roles': {
    purpose: '配置角色与功能权限，实现按岗授权。',
    capabilities: ['角色定义', '菜单/操作权限', '角色分配'],
    operations: ['新建角色勾选权限', '给用户分配角色', '按最小权限原则调整'],
  },
  '/org/companies': {
    purpose: '维护法人公司/管理主体，支撑多组织与数据隔离。',
    capabilities: ['公司档案', '组织树根基', '经营主体归属'],
    operations: ['新增公司主体', '维护公司信息', '作为资产与人员归属'],
  },
  '/org/departments': {
    purpose: '维护部门结构，支撑人员归属与审批流转。',
    capabilities: ['部门树', '上下级关系', '人员挂靠'],
    operations: ['新增部门', '调整组织层级', '人员调入对应部门'],
  },
  '/system/menus': {
    purpose: '管理系统菜单与可见范围，配合角色控制导航。',
    capabilities: ['菜单配置', '排序与显隐', '权限映射'],
    operations: ['调整菜单结构', '控制模块可见性', '与角色权限联动'],
  },
  '/config/versions': {
    purpose: '记录关键业务参数变更版本，满足审计与回看。',
    capabilities: ['参数版本列表', '变更对比', '生效时间'],
    operations: ['查看参数变更历史', '核对变更人与时间', '必要时回退策略沟通'],
  },
  '/migrations/batches': {
    purpose: '管理期初数据迁移批次，保障上线切换与数据核对。',
    capabilities: ['迁移批次', '导入结果', '失败重试'],
    operations: ['创建迁移批次', '上传期初数据', '核对成功/失败明细'],
  },
};

/**
 * 按「API 菜单树 + 注册表」的合并结果生成手册条目；缺文案时给兜底说明，避免漏模块。
 *
 * <p>不再直接消费静态 `MENU`：手册条目必须与侧边栏当前显示的模块、分组、命名一致，
 * 否则菜单改名或停用后手册会指向看不见的页面。
 */
export function buildHelpManual(
  groups: readonly { title: string; items: readonly { path: string; title: string }[] }[],
): HelpModuleGuide[] {
  return groups.flatMap((group) =>
    group.items.map((item) => {
      const body = GUIDES[item.path] ?? {
        purpose: `用于「${item.title}」相关业务处理与查询。`,
        capabilities: ['列表查询与筛选', '业务单据维护', '与上下游模块联动'],
        operations: ['进入模块查看数据', '按权限办理业务', '异常时联系管理员'],
      };
      return {
        path: item.path,
        title: item.title,
        group: group.title,
        ...body,
      };
    }),
  );
}

export const helpGroups = (manual: readonly HelpModuleGuide[]): string[] =>
  Array.from(new Set(manual.map((m) => m.group)));

export const findHelpGuide = (
  manual: readonly HelpModuleGuide[],
  path: string,
): HelpModuleGuide | undefined => manual.find((m) => m.path === path);
