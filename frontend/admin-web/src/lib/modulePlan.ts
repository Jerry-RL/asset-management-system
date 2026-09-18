/**
 * 「资产经营管理」子模块的规划说明（V61）。
 *
 * <p><b>为什么需要这份配置</b>：本次交付的是**菜单骨架 + 规划页**，不是 7 套完整功能。
 * 侧边栏只渲染「已注册路由」（`isRegisteredRoute`），所以每个菜单项都必须有页面，
 * 否则 DB 里的菜单行会被当脏数据静默丢掉、点了也没反应。规划页把「这个模块要做什么、
 * 现在哪些能力已经能用、哪些还没做」写清楚，避免使用者看到空页面后以为系统坏了。
 *
 * <p><b>为什么不直接把菜单指向既有页面</b>：这 7 个模块大部分已有对应页面，
 * 但仓内约定「同一 path 只落一行」（V45 §2）—— 重复挂同一个 path 会让侧边栏出现
 * 两个指向同一页面的入口、权限矩阵多出无意义行。因此新目录用**自己的 path**，
 * 在页内以链接形式指向既有页面（{@link ModulePlan.links}），既不重复入口，
 * 又能让使用者今天就跳到能干活的地方。
 */

/** 已有的可用入口（在规划页上作为跳转链接展示）。 */
export interface ModulePlanLink {
  /** 路由 path，必须是已注册路由 */
  to: string;
  /** 按钮文案 */
  label: string;
  /** 一句话说明这个入口能做什么 */
  desc: string;
}

export interface ModulePlan {
  /** 与 `menu.code` 对应的页面标识（仅用于调试与 key） */
  key: string;
  path: string;
  title: string;
  /** 模块定位：一句话说清它管什么 */
  purpose: string;
  /** 规划中的能力（尚未实现的部分） */
  capabilities: string[];
  /** 已可用入口；为空表示本模块尚无任何可用能力 */
  links: ModulePlanLink[];
}

/**
 * 规划页配置，按 path 索引。
 *
 * <p>顺序与侧栏排序一致（招租 → 签约 → 风险 → 资源 → 其他使用 → 巡检 → 维修）。
 */
export const MODULE_PLANS: Record<string, ModulePlan> = {
  '/asset-mgmt/lease-listing': {
    key: 'leaseListing',
    path: '/asset-mgmt/lease-listing',
    title: '资产招租管理',
    purpose:
      '把「空置资产如何对外找到承租方」这件事管起来：招租信息的发布与审批、公开招租程序、意向方与租户的准入审查。',
    capabilities: [
      '招租渠道与发布节奏的统一编排（同一资产的多渠道同时挂牌与下架）',
      '招租效果分析：曝光 → 意向 → 成交的漏斗，按项目 / 分区 / 资产维度对比',
      '招租底价与市场参考价的偏差提醒（低于底价挂牌的集中管控）',
      '招租与后续签约的衔接：从意向直接带入签约表单',
    ],
    links: [
      {
        to: '/asset-leasing',
        label: '资产租赁管理',
        desc: '按租控状态查看资产，发布招租（提交审批，通过后小程序端可见）',
      },
      {
        to: '/lease-listings',
        label: '招租管理',
        desc: '招租单据列表：待审批 / 招租中 / 已驳回 / 已关闭',
      },
      {
        to: '/tender/announcements',
        label: '公开招租',
        desc: '公开招租公告、报名、资格审查与备案材料包',
      },
      { to: '/tenants', label: '客商/租户管理', desc: '租户档案、信用分与黑名单准入' },
    ],
  },

  '/asset-mgmt/lease-signing': {
    key: 'leaseSigning',
    path: '/asset-mgmt/lease-signing',
    title: '资产租赁签约管理',
    purpose:
      '承接招租结果，把租赁合同从起草、审批、签署到生效的全过程管住，并保证租控、缴费计划、账单三者的口径一致。',
    capabilities: [
      '签约前的一站式校验清单：权属、抵押、底价、租户准入、面积占用是否都通过',
      '组合 / 拆分签约的向导式办理（多资产打包、一资产多租户）',
      '电子签约进度看板：待发、待签、已签、拒签的集中跟踪',
      '签约与场地交接的联动（交接单、钥匙 / 门禁授权）',
    ],
    links: [
      { to: '/contracts', label: '合同管理', desc: '合同台账、租赁条款、审批与续签退租' },
      { to: '/contract-templates', label: '合同模板', desc: 'Word 模板与变量槽位，一键出合同' },
      { to: '/lease-bundles', label: '组合/拆分租赁', desc: '多资产打包或一资产多租户的计租配置' },
      { to: '/vacate-orders', label: '退租清场与保证金', desc: '退租结算与保证金退还' },
    ],
  },

  '/asset-mgmt/lease-risk': {
    key: 'leaseRisk',
    path: '/asset-mgmt/lease-risk',
    title: '资产租赁风险管理',
    purpose:
      '把租赁经营中的风险信号收拢到一处看：欠费与催缴、合同到期与续签、低效与长期空置、合规与监管要求，做到「风险有人跟、动作有留痕」。',
    capabilities: [
      '风险总览与分级：按项目 / 分区 / 租户聚合风险敞口与金额',
      '欠费风险的处置闭环：催缴等级、法务协同、诉讼时效提醒',
      '合同到期风险的续签 / 退租预案（到期前 N 天的处理建议）',
      '低效资产与长期空置的督办台账（与盘活任务联动）',
      '风险处置的全链路留痕与月度复盘报表',
    ],
    links: [
      { to: '/alerts/records', label: '预警提醒与记录', desc: '各类预警的触发记录与处置' },
      { to: '/alerts/rules', label: '预警配置', desc: '预警规则与阈值维护' },
      { to: '/dunning/records', label: '催缴记录', desc: '欠费催缴的执行记录与结果' },
      { to: '/dunning/auto', label: '自动化催缴', desc: '催缴策略的自动执行' },
      { to: '/revitalization', label: '盘活任务', desc: '空置资产的盘活督办' },
    ],
  },

  '/asset-mgmt/resource': {
    key: 'resource',
    path: '/asset-mgmt/resource',
    title: '资产资源管理',
    purpose:
      '回答「我手上到底有哪些可经营的资源、它们的结构如何」：项目、分区、楼层、计租单元到资产的全层级视图，以及可租面积的真实可用量。',
    capabilities: [
      '资源树与可租面积台账：资产 → 单元 → 可租 / 已租 / 空置面积逐层下钻',
      '资源画像与经营潜力分级（位置、面积、用途、历史出租率）',
      '资源结构调整的记录与影响评估（拆分 / 合并 / 用途变更）',
      '资源与经营计划的对齐（年度出租率目标按资源分解）',
    ],
    links: [
      { to: '/projects', label: '项目管理', desc: '项目台账与项目详情' },
      { to: '/project-zones', label: '项目分区管理', desc: '分区 / 楼层与资产分布' },
      { to: '/assets', label: '资产台账', desc: '资产全字段维护、计租单元拆分合并' },
      { to: '/asset-map', label: '资产地图', desc: '按地理位置查看资产分布' },
      { to: '/assets/structure-logs', label: '拆分合并日志', desc: '结构变更的历史留痕' },
    ],
  },

  '/asset-mgmt/other-use': {
    key: 'otherUse',
    path: '/asset-mgmt/other-use',
    title: '资产其他使用管理',
    purpose:
      '管住「不签租赁合同、但资产也要被占用」的场景：内部自用、临时占用、公用设施、公益借用等，保证占用有单据、租控不失真。',
    capabilities: [
      '其他使用类型的统一登记与审批（自用 / 借用 / 公益 / 临时）',
      '占用期间的费用处理口径（免费、分摊、内部结算）',
      '到期与归还提醒，避免长期占用不释放',
      '其他使用对可租面积与出租率的影响分析',
    ],
    links: [
      { to: '/occupations', label: '临时占用', desc: '临时占用申请、生效与解除' },
      { to: '/self-uses', label: '资产自用', desc: '自用申请、审批与结束' },
    ],
  },

  '/asset-mgmt/inspection': {
    key: 'inspection',
    path: '/asset-mgmt/inspection',
    title: '资产巡检管理',
    purpose: '把资产与租赁现场的巡检计划、执行与隐患闭环管起来，形成可追溯的现场管理证据。',
    capabilities: [
      '巡检计划编排：按资产风险等级 / 项目 / 业态自动生成巡查频次',
      '移动端巡检与现场取证（照片、定位、扫码到位）',
      '隐患闭环：发现 → 派单 → 整改 → 复查，超期自动升级',
      '巡检质量与覆盖率考核（计划完成率、隐患整改及时率）',
    ],
    links: [{ to: '/inspections', label: '巡查记录', desc: '已登记的巡查记录与隐患说明' }],
  },

  '/asset-mgmt/repair': {
    key: 'repair',
    path: '/asset-mgmt/repair',
    title: '资产维修管理',
    purpose:
      '管住报修、派工、维修过程与费用结算，区分「租户责任 / 业主责任」，让维修支出可归集、可分析。',
    capabilities: [
      '报修受理与责任判定（自然损耗 / 人为损坏 / 公共部位）',
      '派工与维修公司管理、维修时效看板',
      '维修费用结算与责任分摊（向租户追偿、计入资产成本）',
      '维修记录沉淀到资产档案，支撑更换与更新决策',
    ],
    links: [
      { to: '/repairs', label: '报修工单', desc: '报修受理、派工与完工确认' },
      { to: '/vendors', label: '维修公司', desc: '外部维修供应商档案' },
    ],
  },
};

/** 侧栏排序（与 DB `menu.sort` 一致），供菜单兜底与文档核对。 */
export const MODULE_PLAN_ORDER: string[] = [
  '/asset-mgmt/lease-listing',
  '/asset-mgmt/lease-signing',
  '/asset-mgmt/lease-risk',
  '/asset-mgmt/resource',
  '/asset-mgmt/other-use',
  '/asset-mgmt/inspection',
  '/asset-mgmt/repair',
];

export const modulePlanByPath = (path: string): ModulePlan | undefined => MODULE_PLANS[path];
