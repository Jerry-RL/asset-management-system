import { useMemo, useState } from 'react';
import { Alert, Button, Empty, Modal, Space, Spin, Table, Tag, Tooltip, message } from 'antd';
import { SplitCellsOutlined, MergeCellsOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { LEASE_CONTROL_STATUS } from '@/lib/labels';
import { usePerm } from '@/lib/perm';
import {
  mergeAssetUnits,
  splitAssetUnit,
  useAssetUnits,
  type AssetUnit,
  type AssetUnitOwner,
} from '@/lib/assetUnits';
import { AssetUnitSplitModal } from '@/components/AssetUnitSplitModal';
import { TableActions, actionsColumnWidth, type TableActionItem } from '@/components/TableActions';

const formatArea = (value: unknown) =>
  Number(value ?? 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

/**
 * 可安全参与合并的派生状态：只有「空置」才既无占用、又无生效招租。
 *
 * <p>它只用于**提前禁用勾选框**（省一次注定失败的往返），**不是门禁**：
 * 真正的判定在后端，依据是占用表实时查询（ADR-0021 缺陷 D-04 —— 拿物化派生列当门禁，
 * 会在状态尚未刷新时放行）。
 */
const MERGEABLE_STATUS = 'vacant';

/**
 * 单元状态标签配色。仅用于让人一眼区分「能不能动」：
 * 空置可操作、招租中已在流程里、其余状态都有占用在身（后端会拒绝拆分/合并）。
 */
const UNIT_STATUS_COLOR: Record<string, string | undefined> = {
  vacant: 'green',
  leasing: 'blue',
  leased: 'gold',
  self_use: 'purple',
  occupied: 'magenta',
  vacating: 'orange',
  disposing: 'red',
  exited: undefined,
};

export interface AssetUnitPanelProps {
  /** 归属资产；null 时不发请求、显示空态 */
  asset: AssetUnitOwner | null;
  /**
   * 紧凑模式：供**列表展开行**使用。
   *
   * <p>省掉两处重复信息 —— ①资产抬头（展开行的上方那一行已经写着编号与名称）；
   * ②那段长说明（每个展开行都铺一遍会淹掉真正的数据）。弹窗场景保持完整版。
   */
  compact?: boolean;
  /** 拆分/合并成功后回调：单元变了，外层列表的汇总（分区面积/宗数）可能需要重拉 */
  onChanged?: () => void;
}

/**
 * 计租单元面板：拆分与合并**同一资产下**的可租单元（FR-MDM-001~004、FR-OPS-006）。
 *
 * <p>这是「资产支持部分租赁」的操作入口：一个资产要部分出租，不是去改资产面积，
 * 而是把它的计租单元拆开、让合同挂到具体单元上（ADR-0019 决策 A1）。合并是拆分的
 * 逆操作，用于撤销误拆（ADR-0021 规则 S12）——**两者同屏**，避免「只能拆不能还原」。
 *
 * <p>三处刻意设计：
 * <ul>
 *   <li><b>只列有效单元</b>：拆分后原单元已软删、历史身份留在拆合日志里，
 *       把它列出来只会让人以为该单元还能操作；</li>
 *   <li><b>权限自判</b>：不接收 `canUpdate` 入参，而是自己 `usePerm()` 判 `asset.ledger:update`
 *       —— 与后端 `/assets/units/*` 的 `@RequiresPerm` 同源。少一处由调用方传参，
 *       就少一处「某个入口忘了传」导致按钮越权出现或静默消失；</li>
 *   <li><b>前端状态不当门禁</b>：非空置行只是禁用勾选，提示语写明「最终以后端校验为准」。</li>
 * </ul>
 */
export function AssetUnitPanel({ asset, compact = false, onChanged }: AssetUnitPanelProps) {
  const can = usePerm();
  const canUpdate = can('asset.ledger', 'update');

  const assetId = asset?.id ?? null;
  const { units, loading, loadFailed, reload } = useAssetUnits(assetId);

  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [splittingUnit, setSplittingUnit] = useState<AssetUnit | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const totalArea = useMemo(
    () => units.reduce((acc, u) => acc + Number(u.area ?? 0), 0),
    [units],
  );
  /** 资产可租面积预算：优先租赁面积，回退建筑面积（与后端 `leasableAreaOf` 同口径） */
  const budget = Number(
    (asset?.leaseArea != null && asset.leaseArea > 0 ? asset.leaseArea : asset?.area) ?? 0,
  );

  /**
   * 合并后的「结果单元」= 排序最靠前者，与后端 `AssetUnitService.merge` 的选取规则一致。
   * 在确认框里点名它，是因为其余所选单元会被软删 —— 使用者需要知道**哪一个会留下**。
   */
  const resolveMergeTarget = (rows: AssetUnit[]): AssetUnit | null => {
    if (rows.length === 0) return null;
    return [...rows].sort((a, b) => {
      const sa = a.sort ?? 0;
      const sb = b.sort ?? 0;
      return sa === sb ? a.id - b.id : sa - sb;
    })[0];
  };

  const handleSplitSubmit = async (childAreas: number[], remark: string) => {
    if (!splittingUnit) return;
    setSubmitting(true);
    try {
      await splitAssetUnit(splittingUnit.id, childAreas, remark);
      message.success(`已拆分为 ${childAreas.length} 个单元`);
      setSplittingUnit(null);
      setSelectedIds([]);
      await reload();
      onChanged?.();
    } catch (e) {
      // 后端失败原因是可读的（有占用 / 有招租 / 在押 / 面积不守恒），原样透出
      message.error(e instanceof Error ? e.message : '拆分失败');
    } finally {
      setSubmitting(false);
    }
  };

  const handleMerge = () => {
    const rows = units.filter((u) => selectedIds.includes(u.id));
    if (rows.length < 2) return;
    const target = resolveMergeTarget(rows);
    Modal.confirm({
      title: `确认合并 ${rows.length} 个计租单元？`,
      content: (
        <div className="text-sm space-y-1">
          <div>
            合并后保留 <span className="font-medium">{target?.unitNo}</span>
            （面积与底价取各单元之和），其余 {rows.length - 1} 个单元将被停用。
          </div>
          <div className="text-gray-500">
            停用的单元不再出现在可租单元中，历史身份保留在拆合日志。该操作可再次「拆分」还原，
            但一旦有合同或招租引用了这些单元就无法还原。
          </div>
        </div>
      ),
      okText: '确认合并',
      cancelText: '取消',
      onOk: async () => {
        setSubmitting(true);
        try {
          await mergeAssetUnits(rows.map((u) => u.id));
          message.success(`已合并 ${rows.length} 个单元`);
          setSelectedIds([]);
          await reload();
          onChanged?.();
        } catch (e) {
          message.error(e instanceof Error ? e.message : '合并失败');
          // 抛出以阻止确认框关闭：失败后用户还停在原选择上，便于改选重试
          throw e;
        } finally {
          setSubmitting(false);
        }
      },
    });
  };

  const columns: ColumnsType<AssetUnit> = [
    { title: '单元编号', dataIndex: 'unitNo', key: 'unitNo', width: 170, ellipsis: true },
    {
      title: '单元名称',
      dataIndex: 'unitName',
      key: 'unitName',
      width: 150,
      ellipsis: true,
      render: (value: unknown) => String(value ?? '—'),
    },
    {
      title: '面积(㎡)',
      dataIndex: 'area',
      key: 'area',
      width: 110,
      align: 'right',
      render: (value: unknown) => formatArea(value),
    },
    {
      title: '可租面积(㎡)',
      dataIndex: 'rentableArea',
      key: 'rentableArea',
      width: 120,
      align: 'right',
      render: (value: unknown) => (value == null ? '—' : formatArea(value)),
    },
    {
      title: '底价',
      dataIndex: 'baseRent',
      key: 'baseRent',
      width: 100,
      align: 'right',
      render: (value: unknown) => (value == null ? '—' : formatArea(value)),
    },
    {
      title: '状态',
      dataIndex: 'unitStatus',
      key: 'unitStatus',
      width: 100,
      render: (value: unknown) => {
        const status = String(value ?? '');
        return (
          <Tag color={UNIT_STATUS_COLOR[status]}>
            {LEASE_CONTROL_STATUS[status] ?? (status || '—')}
          </Tag>
        );
      },
    },
  ];

  if (canUpdate) {
    columns.push({
      title: '操作',
      key: '_actions',
      width: actionsColumnWidth(['拆分']),
      render: (_: unknown, unit: AssetUnit) => {
        const actions: TableActionItem[] = [
          {
            key: 'split',
            label: '拆分',
            icon: <SplitCellsOutlined />,
            // 占位单元（面积 0）后端一律拒绝，按钮直接禁用并给出原因，省一次无效往返
            disabled: Number(unit.area ?? 0) <= 0,
            onClick: () => setSplittingUnit(unit),
          },
        ];
        return <TableActions actions={actions} />;
      },
    });
  }

  if (assetId == null) {
    return <Empty description="未指定资产" className="py-10" />;
  }

  const selectedRows = units.filter((u) => selectedIds.includes(u.id));
  const overBudget = budget > 0 && totalArea - budget > 0.005;

  return (
    <div className="space-y-3">
      {/* 紧凑模式下资产抬头由展开行的上一级提供，这里只保留数字口径 */}
      {!compact && (
        <div className="rounded-md bg-gray-50 px-3 py-2 text-sm">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <span className="font-medium">{asset?.assetNo || '—'}</span>
            <span className="text-gray-600 min-w-0 truncate">{asset?.name || '—'}</span>
          </div>
        </div>
      )}

      <div className={`flex flex-wrap items-center gap-x-3 gap-y-1 ${compact ? '' : 'mt-1'}`}>
        <span className="text-xs text-gray-500 tabular-nums">
          有效单元 {units.length} 个 · 单元面积合计 {formatArea(totalArea)} ㎡
          {budget > 0 && ` · 资产可租面积 ${formatArea(budget)} ㎡`}
          {overBudget && (
            <span className="text-red-500 ml-2">超出 {(totalArea - budget).toFixed(2)} ㎡</span>
          )}
        </span>
      </div>

      {compact ? (
        <div className="text-xs text-gray-500">
          部分出租的做法：先把单元拆开，再让合同挂到具体单元上（合同侧接入见改造清单触点 8）。
          有生效占用或招租发布的单元会被后端拒绝，拆分/合并的最终判定以后端为准。
        </div>
      ) : (
        <Alert
          type="info"
          showIcon
          message="一个资产要部分出租，先在这里把计租单元拆开"
          description="合同与招租挂到具体单元上（而非整个资产），这样才能表达「一个资产不同单元租给不同租户」。拆分后原单元失效、子单元独立计租；合并用于把误拆的单元还原。有生效占用或招租发布的单元必须先处理，最终以后端校验为准。"
        />
      )}

      <div className="flex items-center justify-between gap-3 flex-wrap">
        <Space size={4}>
          <Tooltip
            title={
              selectedIds.length < 2
                ? '请至少勾选 2 个空置单元'
                : '合并后保留排序最靠前的单元，其余停用'
            }
          >
            <Button
              size="small"
              type="primary"
              icon={<MergeCellsOutlined />}
              disabled={!canUpdate || selectedIds.length < 2}
              loading={submitting}
              onClick={handleMerge}
            >
              合并所选{selectedIds.length > 0 ? `（${selectedIds.length}）` : ''}
            </Button>
          </Tooltip>
          <Button
            size="small"
            icon={<ReloadOutlined />}
            onClick={() => {
              setSelectedIds([]);
              void reload();
            }}
            aria-label="刷新计租单元列表"
          />
        </Space>
        {!canUpdate && <span className="text-xs text-gray-500">无修改权限，仅可查看</span>}
      </div>

      {loadFailed ? (
        <div className="py-8 text-center text-sm text-gray-500">
          计租单元加载失败
          <Button type="link" size="small" onClick={() => void reload()}>
            重试
          </Button>
        </div>
      ) : loading && units.length === 0 ? (
        <div className="py-10 text-center">
          <Spin />
        </div>
      ) : units.length === 0 ? (
        <Empty
          description="该资产暂无计租单元"
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          className="py-8"
        />
      ) : (
        <Table
          rowKey={(unit) => String(unit.id)}
          size="small"
          columns={columns}
          dataSource={units}
          pagination={false}
          scroll={{ x: 760 }}
          loading={loading}
          rowSelection={
            canUpdate
              ? {
                  selectedRowKeys: selectedIds,
                  onChange: (keys) => setSelectedIds(keys.map((k) => Number(k))),
                  // 只有空置单元可合并；其余状态后端必然拒绝，提前禁用并说明理由
                  getCheckboxProps: (unit) => ({
                    disabled: String(unit.unitStatus ?? '') !== MERGEABLE_STATUS,
                    name: unit.unitNo,
                  }),
                }
              : undefined
          }
        />
      )}

      {canUpdate && selectedIds.length === 1 && (
        <div className="text-xs text-gray-500">合并至少需要 2 个单元，请再勾选一个。</div>
      )}
      {canUpdate && selectedRows.length > 1 && (
        <div className="text-xs text-gray-500">
          将保留 {resolveMergeTarget(selectedRows)?.unitNo}，其余 {selectedRows.length - 1} 个单元停用。
        </div>
      )}

      <AssetUnitSplitModal
        unit={splittingUnit}
        submitting={submitting}
        onCancel={() => setSplittingUnit(null)}
        onSubmit={(childAreas, remark) => void handleSplitSubmit(childAreas, remark)}
      />
    </div>
  );
}
