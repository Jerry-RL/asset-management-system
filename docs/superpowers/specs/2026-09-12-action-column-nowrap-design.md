# 列表「操作」列不换行 设计

**日期**：2026-09-12
**状态**：已确认，待实现
**范围应用**：仅 `frontend/admin-web`
**关联设计**：
- `docs/superpowers/specs/2026-09-12-list-return-state-design.md`（另一件独立工作：列表分页/筛选以 URL 为唯一真相；本设计与之无交集）
- `docs/superpowers/specs/2026-09-12-project-zone-management-design.md`（`ZoneAssetPane` 操作列来源）

---

## 1. 背景与问题

列表页「操作」列的操作链接（编辑 / 一物一档 / 更多…）在宽度不足时会**折成两行**，行高被撑开、同一列的链接错位，扫读时很难对齐。

直接原因是 `src/index.css` 里 `.ams-actions` 允许换行：

```92:100:frontend/admin-web/src/index.css
.ams-actions {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px 4px;
  max-width: 100%;
  /* 抵消首个链接的左右内边距，使文字与表头对齐 */
  margin-left: -6px;
}
```

它命中全部 **14 处** `<TableActions>`（13 个文件）。

### 1.1 只改 `nowrap` 会把「折行」变成「裁掉」

这些表格大多带 `scroll={{ x }}`，antd 因此对 `<table>` 用 `table-layout: fixed`，列的 `width` 被**强制生效**。此时若内容比 `width` 宽，固定右列（`fixed: 'right'`）的溢出部分会被裁掉：

- `.ams-table-wrap .ant-table { overflow: hidden }`（`index.css:80`）—— `ResourcePage` / `SystemMenuPage` / `SystemDictionaryPage` / `SystemRolePage` 都套了这一层。
- 固定右列贴在表格右边缘，溢出方向朝外，肉眼就是「链接被切掉一截」。

因此「不换行」必须**同时**保证列宽足够，否则是拿一个更糟的问题换掉一个较差的问题。

### 1.2 现有列宽与内容实际所需

按当前 CSS 逐项算（单个文字链接 = 左右内边距 12 + 图标 12 + 图标间距 4 + 字数 × 汉字宽 13；「更多」= 2 字 + `MoreOutlined`）。

下表「内容所需」= **不含缓冲**的原始内容宽度（`26 + Σ链接 + 项间 4px 间隙`）；§4.1 的「新宽度」= 再加上 8px 缓冲后的 helper 返回值。两者相差 8px，不要混用。

| 位置 | 现有 `width` | 内容所需 | 差值 | 表现 |
|------|--------------|----------|------|------|
| `ResourcePage`（全部资源列表） | `hasMore ? 180 : showEdit ? 150 : 110` | 196 | −16 | **折行**（「详情/档案 + 编辑 + 更多」放不下） |
| `ZoneAssetPane`（分区资产表） | 170 | 222 | −52 | **折行**（「编辑 + 一物一档 + 更多」放不下） |
| `SystemMenuPage` | 140 | 196 | −56 | **折行**（「编辑 + 删除 + 更多」放不下） |
| `AgentReportsPage` | 140 | 145 | −5 | **折行**（「下载 HTML」放不下） |
| `ProjectFormPage` | 140 | 138 | +2 | 余量 2px，文案一改即折 |
| `ProjectZonesPanel` | 140 | 138 | +2 | 余量 2px，文案一改即折 |
| `SystemDictionaryPage` | 150 | 138 | +12 | 略有富余 |
| `DunningAutoPage` | 110 | 93 | +17 | 富余 |
| `AssetDossierPage`（合同表） | 100 | 80 | +20 | 富余 |
| `ApprovalPage` | 160 | 138 | +22 | 富余 |
| `PaymentConfirmPage` | 130 | 106 | +24 | 富余 |
| `ContractTemplatesPage` | 220 | 196 | +24 | 富余 |

**结论**：写死的宽度数字是本次问题的成因 —— 它和操作文案之间没有任何约束关系。仅调数字（方案 B）会在下次改文案时复发。

---

## 2. 已确认决策

| 项 | 决策 | 理由 |
|----|------|------|
| 含义 | **表格「操作」列里的操作链接不换行** | 用户确认 |
| 范围 | **仅 `frontend/admin-web`** | 用户确认 |
| 不做 | **不改**页面顶部筛选/按钮工具条（`Space wrap` 的那些） | 用户确认；工具条折行是窄屏下的合理降级，与操作列性质不同 |
| 工作安排 | **独立小改动**（本 spec + 小计划） | 与正在评审的「返回还原」设计互不影响 |
| 方案 | **A：CSS `nowrap` + `actionsColumnWidth()` 派生列宽** | 用户确认；宽度与文案绑定，不再靠魔法数字 |
| 运行时量算（ResizeObserver） | **不采用** | 每张表加布局副作用、首屏宽度跳动，收益不抵复杂度 |
| 前端测试框架 | **不新增** | 仓库当前没有 vitest/jest；像素级确认由人工在 :5173 完成 |

---

## 3. 方案 A

### 3.1 `src/index.css`

```
.ams-actions {
  display: inline-flex;
  align-items: center;
  flex-wrap: nowrap;        /* 原 wrap：操作链接不再折行 */
  gap: 2px 4px;
  margin-left: -6px;
  /* 删除 max-width: 100%：现在是 nowrap，该约束已无意义，
     留着只会让人误以为内容仍会被压缩 */
}
```

这一条覆盖全部 14 处，包括 `DashboardPage` 里两个**原生 `<table>`**（`<td>` 中的 `<TableActions>` 今天同样会折行，因为原生表格自动布局 + `w-full`）。

### 3.2 `src/components/TableActions.tsx` 新增导出

```ts
/**
 * 单个文字链接的宽度（px）：左右内边距 12 + 图标 12 + 图标间距 4 + 字数 × 汉字宽 13。
 *
 * <p>刻意**按汉字宽度估算每一个字符**（含 ASCII 与空格）：估算偏大是安全的
 * （列宽多几像素不影响观感），偏小则会让 nowrap 的内容溢出固定右列而被裁掉。
 */
const actionLinkWidth = (label: string) => 28 + label.length * 13;

/** 「更多」按钮：2 字 + MoreOutlined 图标 */
const MORE_WIDTH = actionLinkWidth('更多');

/**
 * 操作列宽度（px）。
 *
 * <p>内联数量必须与传给 {@link TableActions} 的 `max` 同源，否则算出来的宽度与
 * 实际渲染不符。`labels` 传「可能内联出现的全部操作文案」，helper 取**最长的 max 个**
 * —— 与渲染顺序无关，因此同一份配置在不同权限账号下宽度一致。
 *
 * <p>构成：单元格内边距 32 − `.ams-actions` 负左边距 6 = 26；加上各项宽度；
 * 加上项间 4px 间隙；再加 8px 缓冲。
 */
export const actionsColumnWidth = (
  labels: string[],
  { max = 2, hasMore = false }: { max?: number; hasMore?: boolean } = {},
) => {
  const inline = [...labels].sort((a, b) => b.length - a.length).slice(0, max);
  const items = inline.map(actionLinkWidth);
  if (hasMore) items.push(MORE_WIDTH);
  return 26 + items.reduce((sum, w) => sum + w, 0) + Math.max(0, items.length - 1) * 4 + 8;
};
```

### 3.3 为什么用「最长的 max 个」而不是「前 max 个」

`TableActions` 渲染时取 `visible.slice(0, max)` —— 顺序取决于权限过滤结果，**同一份配置在不同账号下内联项可能不同**（如无权编辑时「编辑」被剔除，「删除」前移成为内联项）。若按顺序算宽度，同一张表在不同账号下会得到不同列宽。

取「最长的 max 个」得到的是**该配置下的上界**：与权限无关、稳定，且永远不小于实际渲染内容。

---

## 4. 改造清单

### 4.1 需要改的文件

| # | 文件 | 内联文案（`labels`） | `hasMore` | 新宽度 | 旧 `width` |
|---|------|---------------------|-----------|--------|-----------|
| 1 | `src/index.css` | — | — | — | `flex-wrap: wrap` → `nowrap`，删 `max-width: 100%` |
| 2 | `src/components/TableActions.tsx` | — | — | — | 新增 `actionLinkWidth` / `MORE_WIDTH` / `actionsColumnWidth` |
| 3 | `src/components/ResourcePage.tsx` | `[详情 \| 档案, 编辑]` | 复用 §4.2 的 `hasMore`（同源于 `resolveRowActions`） | **204** | 180 / 150 / 110 |
| 4 | `src/components/ZoneAssetPane.tsx` | `['编辑', '一物一档']` | `true`（删除在 `more`） | **230** | 170 |
| 5 | `src/pages/SystemMenuPage.tsx` | `['编辑', '删除']` | `true`（DIR 行「新增子菜单」） | **204** | 140 |
| 6 | `src/pages/SystemDictionaryPage.tsx` | `['编辑', '删除']` | `false` | **146** | 150 |
| 7 | `src/pages/ProjectFormPage.tsx` | `['详情', '删除']` | `false` | **146** | 140 |
| 8 | `src/pages/DunningAutoPage.tsx` | `['去处理']` | `false` | **101** | 110 |
| 9 | `src/pages/ApprovalPage.tsx` | `['通过', '驳回']` | `false` | **146** | 160 |
| 10 | `src/pages/AssetDossierPage.tsx`（合同表） | `['查看']` | `false` | **88** | 100 |
| 11 | `src/pages/PaymentConfirmPage.tsx` | `['确认到账']` | `false` | **114** | 130 |
| 12 | `src/pages/AgentReportsPage.tsx` | `['下载 HTML']` | `false` | **153** | 140 |
| 13 | `src/components/ProjectZonesPanel.tsx` | `['编辑', '删除']` | `false` | **146** | 140 |
| 14 | `src/pages/ContractTemplatesPage.tsx` | `['预览', '编辑']` | `true`（导出 Word） | **204** | 220 |

### 4.2 `ResourcePage` 的 `hasMore` 必须复用既有判定

`ResourcePage` 的操作列已经在 `tableColumns` 的 `useMemo` 里算过：

```1011:1018:frontend/admin-web/src/components/ResourcePage.tsx
    // 操作列的存在性也要按权限算：无权时整列消失，而不是留一个只有「详情」的空操作列
    const { showEdit, showDelete, rowActions, hasColumn } = resolveRowActions(config, canDo);
    if (hasColumn) {
      const hasMore = !!config.qrcodePath || showDelete || rowActions.length > 0;
      cols.push({
        title: '操作',
        key: '_actions',
        fixed: 'right',
        width: hasMore ? 180 : showEdit ? 150 : 110,
```

改造后**必须**继续用同一个 `hasMore` 与 `showEdit`，不得另写一套判定 —— 否则会出现「算宽度时认为没有更多、渲染时却有」的不一致：

```
width: actionsColumnWidth(
  [
    config.detailPath || config.detailLink
      ? (config.detailLink ? (config.detailLinkLabel ?? '档案') : '详情')
      : null,
    showEdit ? '编辑' : null,
  ].filter((v): v is string => v != null),
  { hasMore },
),
```

`ZoneAssetPane` 同理：它的 `hasMore` 由 `canDeleteLedger` 决定，与 `more` 数组的实际内容保持一致。

### 4.3 明确不改

| 位置 | 为什么不改 |
|------|-----------|
| `src/components/RecordSheetSections.tsx`（领用/处置操作列） | 用的是 antd `<Space>`（`wrap` 默认 `false`），本就不折行 |
| `src/pages/DashboardPage.tsx`（两处 `<TableActions>`） | 原生 `<table>`，没有列宽可设；由 §3.1 的 CSS 直接解决 |
| `ZoneAssetPane` Tab 栏、`AssetQuickActions`、`AssetFormPage` 等 `Space wrap` | 是工具条不是操作列，窄屏折行是合理降级；用户已确认不在范围 |
| `SystemRolePage` 权限矩阵 | 不是「操作列」，其宽度已由 `240 + actions.length * 78` 按列数推导 |

---

## 5. 边界与取舍

| 项 | 说明 |
|----|------|
| 估算偏差方向 | 每个字符都按 13px（汉字宽）计。ASCII 实际约 7px ⇒ 估算**偏大**，安全；偏小才会裁切 |
| 部分列会变窄 | #6 `SystemDictionaryPage` 150→146、#8 `DunningAutoPage` 110→101、#9 `ApprovalPage` 160→146、#10 `AssetDossierPage` 100→88、#11 `PaymentConfirmPage` 130→114、#14 `ContractTemplatesPage` 220→204。宽度精确贴合内容，省下的横向空间让给数据列 |
| 部分列会变宽 | #3 `ResourcePage`（180/150/110 → 204）、#4 `ZoneAssetPane` 170→230、#5 `SystemMenuPage` 140→204、#7 `ProjectFormPage` 140→146、#12 `AgentReportsPage` 140→153、#13 `ProjectZonesPanel` 140→146 —— 这些正是今天折行的位置 |
| 列宽随权限变化 | `ResourcePage` / `ZoneAssetPane` / `SystemMenuPage` 的 `labels`/`hasMore` 由权限过滤后的结果推导 ⇒ 无权账号的列更窄。这是正确行为，不是 bug |
| 文案变更 | 改文案时宽度自动跟随（`label.length` 参与计算），不需要同步改数字 |
| `max` 不一致 | helper 的默认 `max = 2` 必须与 `TableActions` 的 `max` 默认值一致；调用方显式传 `max={2}` 的地方（`ResourcePage`、`ZoneAssetPane`、`SystemDictionaryPage`、`PaymentConfirmPage`、`AgentReportsPage`）与默认值相同，无需额外传参 |
| 窄屏 | 表格本身有横向滚动（`scroll.x` / 外层 `overflow-x-auto`），操作列宽度不受视口压缩影响 |
| 首屏宽度跳动 | 不存在 —— 宽度是静态计算的，不依赖渲染后的测量 |

---

## 6. 验证

1. `pnpm --filter admin-web build`、`pnpm lint`、`pnpm format:check` 全绿（与 CI 同款）。
2. 逐条核对 §4.1 计算宽度：`actionsColumnWidth(labels, { hasMore })` 的返回值不小于该列任一账号下实际渲染内容的宽度上界。
3. 人工目视（:5173，前端 dev server 已在跑）—— 前端仓库没有测试框架，像素级确认无法自动化：
   - `/assets`（`ResourcePage`，「档案 + 编辑 + 更多」）、`/assets/:id/dossier` 合同表
   - `/project-zones`（`ZoneAssetPane`：「编辑 + 一物一档 + 更多」）
   - `/system/menus`（目录行带「新增子菜单」）、`/system/dict`、`/system/roles`
   - `/projects/:id/edit`、`/approvals`、`/payments/pending-confirm`、`/intelligence/reports`、`/contract-templates`、`/dunning/auto`
   - 重点确认：**没有任何链接折到第二行，也没有任何链接被右边缘裁掉**

---

## 7. 明确不做

- H5（`h5-tenant` / `h5-worker`）与两个小程序。
- 页面顶部筛选/按钮工具条（`Space wrap`）的折行行为。
- 引入前端测试框架或 CI 宽度守卫脚本（本仓当前无 vitest/jest；`actionsColumnWidth` 已把「文案 → 宽度」的约束写进代码，比脚本正则更可靠）。
- 操作列的其它视觉调整（图标尺寸、间距、hover 态、`更多` 下拉结构）。
- `TableActions` 的 API 变更（`actions` / `more` / `max` 语义保持原样）。
