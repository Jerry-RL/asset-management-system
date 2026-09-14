/**
 * 「项目分区管理」入口带入的**归属锁定**判定（全仓唯一实现）。
 *
 * <p>为什么单独成模块：这三个布尔量反复出过事故，而且每一次都**不报错、只是让人无路可走或
 * 悄悄写错数据**。最近一次是「锁定 + 新增 + 全部分区」：分区框被灰化且为空，既选不了分区，
 * 又因为没有 required 校验而能提交出 `zone_id = null` 的资产 —— 该资产在任何分区 Tab 下
 * 都看不见。根因是把 `lockScope` 这个**入口**标志直接当成了「逐字段锁定」开关。
 *
 * <p>正确的语义只有一句：**灰化「进入表单时就已经确定下来」的归属，未确定的一律保持可选**。
 * 于是三种字段的判据天然不同 —— 项目必然已定（从某个项目进来），资产公司要靠反查才知道，
 * 分区则要看入口有没有带上 `zoneId`（新增态）或记录本身有没有分区（编辑态）。这层区别散在
 * JSX 里看不出来，所以收敛到本模块，并配一份真值表测试（`scripts/test-asset-scope-lock.mjs`）。
 *
 * <p>配合「锁定入口下分区必填」（见 `AssetFormPage` 的 `rules`）：分区一旦锁成空白，
 * 表单就**既填不上也交不出**，比改前更死。两条约束必须一起读。
 *
 * <p>这只是**入口侧的体验约束**，不是安全边界：服务端 `AssetService#validateReferences`
 * 会独立校验「项目、责任部门属于所选资产公司」与「分区属于所选项目」，不一致一律 400。
 */
export interface AssetScopeLockInput {
  /** 入口标志：URL `lockScope=1`，表示从「项目分区管理」进入 */
  lockScope: boolean;
  /** 编辑态：归属一律以 `/assets/{id}` 的返回值为准 */
  isEdit: boolean;
  /** URL 预设的分区 id（仅新增态会带） */
  presetZoneId?: number | null;
  /** 编辑态下 `/assets/{id}` 回填的分区 id（记录本身就没有分区时为空） */
  recordZoneId?: number | null;
  /** 锁定态下由所选项目反查到的资产公司 id */
  lockedCompanyId?: number | null;
}

export interface AssetScopeLock {
  /** 灰化「资产公司」 */
  company: boolean;
  /** 灰化「项目」 */
  project: boolean;
  /** 灰化「分区」 */
  zone: boolean;
}

/**
 * 三个字段的锁定判据。
 *
 * <pre>
 *   company：锁定 && (编辑 || 反查成功)
 *            反查失败时**不能**灰化 —— 它是必填项，空白 + 灰化 = 用户无路可走
 *            （「锁定 + 新增 + 反查失败」确切实现过又上过线，故保留完整判据，
 *             不压缩成 `lockScope && lockedCompanyId != null`）。
 *   project：锁定 —— 从「项目分区管理」进来时项目必然已确定（编辑态由记录回填）。
 *   zone   ：锁定 && 分区已确定（新增态看 URL 的 presetZoneId，编辑态看记录的 recordZoneId）
 *            「全部分区」Tab 下**没有**分区可锁：灰化 + 空白会让分区彻底选不了。
 *            编辑一个本身无分区的资产同理 —— 那种情况下锁住的是一格空白，
 *            使用者既挪不动它也补不上它。
 * </pre>
 */
export const assetScopeLock = ({
  lockScope,
  isEdit,
  presetZoneId,
  recordZoneId,
  lockedCompanyId,
}: AssetScopeLockInput): AssetScopeLock => ({
  company: lockScope && (isEdit || lockedCompanyId != null),
  project: lockScope,
  zone: lockScope && (presetZoneId != null || recordZoneId != null),
});
