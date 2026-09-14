/**
 * 资产显示文案的**唯一**一份实现（权属流转 / 资产调拨记录共用）。
 *
 * 为什么单独成文件：两个模块的资产下拉项与详情资产行都显示同一套四段文案
 * （`项目 · 分区 · 楼层 · 资产名称`）。各自抄一份的话，某天后端只改了其中一个 DTO 的
 * 字段名（或用例只覆盖了其中一个页面），另一处就会静默显示成裸编号 ——
 * 不报错、不变红，只在界面上少了一段字。
 */

/**
 * 资产显示的公共入参：**下拉项与详情资产行的公共子集**。
 *
 * 为什么要有这个接口：下拉项的名字字段是 `name`，而详情资产行是 `assetName` ——
 * 两者其余字段一致。若把 `assetOptionLabel` 写成只吃其中一种，另一处就必须自己再拼一份 label，
 * 而「两处拼接必然漂移」（同一张单在两个页面显示成不同的资产名）。
 */
export interface AssetLabelSource {
  assetId: number;
  assetNo?: string | null;
  /** 下拉项的字段名 */
  name?: string | null;
  /** 详情资产行的字段名 */
  assetName?: string | null;
  projectName?: string | null;
  zoneName?: string | null;
  floorNo?: number | null;
}

/**
 * 资产下拉的显示文案：`项目 · 分区 · 楼层 · 资产名称`（需求指定的四段）。
 *
 * 空段跳过而不是留空占位：`- · - · 5层 · 厂房A` 比 `厂房A` 更难读。
 * 名称缺失时回落到资产编号 —— 至少让用户能选中一个可辨认的东西。
 */
export const assetOptionLabel = (option: AssetLabelSource): string => {
  const title = option.name ?? option.assetName ?? null;
  const floor =
    option.floorNo === null || option.floorNo === undefined ? null : `${option.floorNo}层`;
  const segments = [option.projectName, option.zoneName, floor, title].filter(
    (segment): segment is string => Boolean(segment && String(segment).trim()),
  );
  return segments.length > 0 ? segments.join(' · ') : (option.assetNo ?? `资产 #${option.assetId}`);
};
