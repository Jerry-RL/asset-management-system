/**
 * `path → menuCode` 镜像（**由 `V45__menu_tree_and_role_data_scope.sql` 生成，勿手改**）。
 *
 * <p>存在的唯一理由：`/system/menus` 请求失败时要「回退静态菜单并**按 permissions 过滤**」
 * （验收第 2 条）。静态 `MENU` 只有 path / title，没有权限码，而过滤必须用
 * `code:view` 判定 —— 没有这张表就只能把整个静态菜单原样显示给无权限账号。
 *
 * <p><strong>它是一份镜像，因此必须防漂移</strong>：{@link checkCodeMappingDrift} 在
 * `/system/menus` 成功加载后逐条比对，不一致时在控制台告警并列出差异。
 * 漂移的后果被限制在「接口失败 + 首屏无缓存」这一条罕见路径上：
 * 缺条目 → 该菜单在降级态不显示（安全侧）；多条目 → 点进去 403（后端仍会拦）。
 *
 * <p><strong>为什么单独成文件而不是留在 `routeRegistry.ts`</strong>：镜像必须能被
 * `lib/perm.tsx` 消费，而 `routeRegistry.ts` 需要 `import` 页面配置 `pages/modules.tsx`。
 * 若镜像与注册表同文件，就会形成
 * `modules.tsx → ProjectZonesPanel → perm → routeRegistry → modules.tsx` 的循环导入，
 * 使 `routeRegistry` 在 `MENU` 初始化前求值并抛 `Cannot access 'MENU' before initialization`。
 * 本文件**刻意不 import 任何模块**，把这个环断开在基础层。
 */

/**
 * 镜像内容与 V45 种子逐条对齐；生成方式见设计 D7。
 * `scripts/check-perm-invariants.mjs` 直接解析本文件，**改动格式请同步脚本**。
 */
export const PATH_TO_CODE: Record<string, string> = {
  // ---- 首页与工作台（home / icon=app） ----
  '/': 'home.center',
  '/ops-calendar': 'home.calendar',
  '/dashboard': 'home.dashboard',
  '/dashboard/consolidate': 'home.consolidate',
  // ---- 经营分析（analysis / icon=chart） ----
  '/business-plans': 'analysis.plan',
  '/reports': 'analysis.report',
  // ---- 风险管控（risk / icon=alert） ----
  '/alerts/rules': 'risk.rule',
  '/alerts/records': 'risk.record',
  // ---- 资产台账（asset / icon=ledger） ----
  '/projects': 'asset.project',
  '/project-zones': 'asset.projectZone',
  '/assets': 'asset.ledger',
  '/assets/structure-logs': 'asset.structureLog',
  '/asset-map': 'asset.map',
  // ---- 资债权证（deed / icon=certificate） ----
  '/certificates': 'deed.certificate',
  '/mortgages': 'deed.mortgage',
  '/asset-transfers': 'deed.transfer',
  '/evaluations': 'deed.evaluation',
  // ---- 资产招租（lease / icon=listing） ----
  '/lease-listings': 'lease.listing',
  '/tender/announcements': 'lease.tender',
  // ---- 资产运营（operation / icon=operation） ----
  '/disposals': 'operation.disposal',
  '/occupations': 'operation.occupation',
  '/self-uses': 'operation.selfUse',
  '/asset-audits': 'operation.audit',
  // ---- 合同管理（contract / icon=contract） ----
  '/contracts': 'contract.ledger',
  '/vacate-orders': 'contract.vacate',
  '/lease-bundles': 'contract.bundle',
  '/contract-templates': 'contract.template',
  // ---- 定价与计费（billing / icon=bill） ----
  '/billing/bills': 'billing.bill',
  '/meters': 'billing.meter',
  '/apportion-configs': 'billing.apportion',
  '/adjustments/fee-reliefs': 'billing.relief',
  '/adjustments/rent-adjusts': 'billing.rentAdjust',
  // ---- 收费与发票（finance / icon=payment） ----
  '/payments': 'finance.payment',
  '/payments/pending-confirm': 'finance.paymentConfirm',
  '/refunds': 'finance.refund',
  '/invoices': 'finance.invoice',
  '/invoice-tax-rates': 'finance.taxRate',
  '/finance/bank-flows': 'finance.bankFlow',
  '/finance/vouchers': 'finance.voucher',
  // ---- 履约催缴（dunning / icon=dunning） ----
  '/dunning/records': 'dunning.record',
  '/dunning/auto': 'dunning.auto',
  // ---- 巡检维修（maintenance / icon=repair） ----
  '/repairs': 'maintenance.repair',
  '/vendors': 'maintenance.vendor',
  '/inspections': 'maintenance.inspection',
  // ---- 任务中心（task / icon=task） ----
  '/tasks': 'task.manage',
  '/approvals': 'task.approval',
  // ---- 空置盘活（revitalize / icon=revitalize） ----
  '/revitalization': 'revitalize.task',
  // ---- 合规监管（compliance / icon=report） ----
  '/regulation/reports': 'compliance.report',
  // ---- 消息待办（message / icon=notify） ----
  '/notifications': 'message.notify',
  // ---- 固定资产（fixedasset / icon=fixedasset） ----
  '/fixed-assets': 'fixedasset.ledger',
  '/fixed-assets/inventories': 'fixedasset.inventory',
  // ---- 无形资产（intangible / icon=intangible） ----
  '/intangible-assets': 'intangible.ledger',
  // ---- 组织架构（org / icon=org） ----
  '/org/structure': 'org.structure',
  '/org/companies': 'org.company',
  '/org/departments': 'org.department',
  '/system/users': 'org.user',
  // ---- 运营管理（ops / icon=tenant） ----
  '/tenants': 'ops.tenant',
  // ---- 系统配置（config / icon=config） ----
  '/config/versions': 'config.version',
  // ---- 系统管理（system / icon=setting） ----
  '/system/roles': 'system.role',
  '/system/menus': 'system.menu',
  '/system/dict': 'system.dict',
  // ---- 智能中心（intelligence / icon=ai） ----
  '/intelligence/templates': 'intelligence.template',
  '/intelligence/reports': 'intelligence.report',
  '/intelligence/sessions': 'intelligence.session',
  // ---- 期初迁移（migration / icon=migration） ----
  '/migrations/batches': 'migration.batch',
};

export const codeForPath = (path: string): string | undefined => PATH_TO_CODE[path];

/** 该 path 是否被授予了 `code:view`（静态降级态专用判定）。 */
export const isPathViewable = (path: string, permissions: readonly string[]): boolean => {
  const code = PATH_TO_CODE[path];
  // 镜像缺失时保守放行：不显示菜单比显示一个可能 403 的入口更难排查，且后端仍会拦截
  if (!code) return true;
  return permissions.includes(`${code}:view`);
};

/**
 * 校验 `PATH_TO_CODE` 镜像与接口返回的 `code → path` 是否一致。
 *
 * <p>只在开发环境告警，不抛错：镜像漂移只影响「接口失败时的降级菜单」，
 * 让整个页面因为这个次要路径挂掉是不划算的。但它必须**可见**，否则会静默过期。
 *
 * @param apiPairs 接口菜单树里收集到的 `[code, path]`
 */
export function checkCodeMappingDrift(apiPairs: Array<[string, string]>): void {
  if (import.meta.env.PROD) return;
  const apiByPath = new Map(apiPairs);
  const missing: string[] = [];
  const mismatched: string[] = [];
  for (const [path, code] of apiByPath) {
    const mirrored = PATH_TO_CODE[path];
    if (mirrored === undefined) missing.push(`${path} → ${code}`);
    else if (mirrored !== code) mismatched.push(`${path}: 镜像=${mirrored} 接口=${code}`);
  }
  const extra = Object.keys(PATH_TO_CODE).filter((path) => !apiByPath.has(path));
  if (!missing.length && !mismatched.length && !extra.length) return;
  console.warn('[pathToCode] PATH_TO_CODE 镜像与接口菜单不一致，请同步 V45 种子后重新生成：', {
    镜像缺失: missing,
    编码不一致: mismatched,
    多余条目: extra,
  });
}
