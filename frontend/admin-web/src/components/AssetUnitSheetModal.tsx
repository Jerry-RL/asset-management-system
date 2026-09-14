import { Modal } from 'antd';
import { AssetUnitPanel } from '@/components/AssetUnitPanel';
import type { AssetUnitOwner } from '@/lib/assetUnits';

export interface AssetUnitSheetModalProps {
  open: boolean;
  /** 归属资产；`open` 为 false 时不挂载内容 */
  asset: AssetUnitOwner | null;
  onClose: () => void;
  /** 拆分/合并成功后回调：单元变了，外层列表的汇总可能需要重拉 */
  onChanged?: () => void;
}

/**
 * 计租单元弹窗 —— {@link AssetUnitPanel} 的「弹窗形态」。
 *
 * <p>面板本身与形态无关，因此这里只做三件事：套一个 Modal、按 `open` 卸载内容、给宽度。
 *
 * <p><b>为什么按 `open` 卸载内容</b>：单元列表是「打开那一刻」的快照。若只依赖 assetId
 * 驱动重拉，同一个资产连续打开两次会看到上次的陈旧数据（例如上一次刚拆过）。
 * 卸载内容是最省事、且不会漏刷的做法。
 *
 * <p><b>与列表展开行的关系</b>：两者是**互补**而非重复 ——
 * <ul>
 *   <li>资产台账 / 项目分区管理的列表行：用展开行就地看、就地改（主路径，见
 *       {@code ResourcePage.expandable} 与 {@code ZoneAssetPane} 的 Table）；</li>
 *   <li>本弹窗：卡片视图（展开行只在列表模式生效，卡片模式没有「行」的概念）
 *       与窄屏场景下的备用入口。</li>
 * </ul>
 */
export function AssetUnitSheetModal({
  open,
  asset,
  onClose,
  onChanged,
}: AssetUnitSheetModalProps) {
  return (
    <Modal
      title="计租单元"
      open={open}
      onCancel={onClose}
      footer={null}
      width={Math.min(900, typeof window !== 'undefined' ? window.innerWidth - 32 : 900)}
    >
      {open && <AssetUnitPanel asset={asset} onChanged={onChanged} />}
    </Modal>
  );
}
