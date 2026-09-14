import { useCallback, useEffect, useState } from 'react';
import { Button, Empty, Pagination, Space, Spin, Table, Tabs, message } from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  FileTextOutlined,
  PlusOutlined,
  ProfileOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useLocation, useNavigate } from 'react-router-dom';
import { api, type PageResult } from '@/lib/api';
import { confirmDelete } from '@/lib/confirm';
import { ASSET_TYPE, LEASE_CONTROL_STATUS } from '@/lib/labels';
import { useListQuery } from '@/lib/listQuery';
import { currentPath } from '@/lib/navigation';
import { usePerm } from '@/lib/perm';
import { useProjectZones, type ProjectZone } from '@/lib/projectZones';
import { floorLabel, useProjectZoneFloors, type ProjectZoneFloor } from '@/lib/projectZoneFloors';
import { TableActions, actionsColumnWidth, type TableActionItem } from '@/components/TableActions';
import { RecordSheetModal } from '@/components/RecordSheetModal';
import { ZoneFloorFormModal } from '@/components/ZoneFloorFormModal';
import { ZoneFormModal } from '@/components/ZoneFormModal';

/** 资产行：列表接口已回显 zoneName（AssetService#fillZoneNames），无需前端再查 */
interface AssetRow {
  id: number;
  assetNo?: string;
  name?: string;
  assetType?: string;
  zoneId?: number | null;
  zoneName?: string | null;
  floorNo?: number | null;
  area?: number;
  leaseControlStatus?: string;
}

export interface ZoneAssetPaneProps {
  projectId: number | null;
  /** null = 「全部分区」 */
  zoneId: number | null;
  onZoneChange: (zoneId: number | null) => void;
  /** null = 「全部楼层」；仅在选中具体分区时有意义 */
  floorNo: number | null;
  onFloorChange: (floorNo: number | null) => void;
}

/** 「全部分区」Tab 的 key：分区 id 都是正数，不会与它冲突 */
const ALL_ZONES_KEY = 'all';
/** 「全部楼层」Tab 的 key：楼层号可能有负数（地下层），用字符串避免与数字 key 混淆 */
const ALL_FLOORS_KEY = 'all';
const PAGE_SIZE = 10;

/**
 * 右栏资产分页的 URL 键名。
 *
 * <p>导出给 {@link ProjectZonesPage}：切换项目/分区时它必须**原子地**把这个键删掉
 * （回到第 1 页），否则会先按上一个分区的旧页码发一次请求再补发第 1 页。
 */
export const ASSET_PAGE_PARAM = 'assetPage';

/**
 * 右栏楼层 Tab 的 URL 键名（V50）。
 *
 * <p>与 `zoneId` 同一套理由：跳去资产表单再返回时本页整页重新挂载，选中楼层不落在 URL 上就回不来。
 * 导出给 ProjectZonesPage：**切分区时必须原子地删掉它** —— 楼层号只在所属分区内才有意义，
 * 换成另一个分区还留着 `floorNo=3`，右栏就会按新分区里可能不存在的 3F 过滤（空表 + 表头写着 3F）。
 */
export const FLOOR_NO_PARAM = 'floorNo';

const formatArea = (value: unknown) =>
  Number(value ?? 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

/**
 * 资产区空态文案（spec §5.3）：
 * 选中具体分区 → 「该分区暂无资产」；「全部分区」下项目本身就没有分区 → 「该项目暂无分区」
 * （Tab 栏里就摆着「新增分区」入口）；其余（有分区但当下无资产）→ 「该项目暂无资产」。
 *
 * <p>`zonesKnown` 为 false 时不宣称「暂无分区」—— 「不知道」不等于「为空」，
 * 分区加载失败或无权查看项目时都会走到这里。
 */
const emptyAssetText = (
  zoneId: number | null,
  floorNo: number | null,
  zonesKnown: boolean,
  zoneCount: number,
): string => {
  // 楼层比分区更具体：选中楼层时「本层暂无资产」才是使用者真正在问的问题
  if (floorNo != null) return '该楼层暂无资产';
  if (zoneId != null) return '该分区暂无资产';
  if (zonesKnown && zoneCount === 0) return '该项目暂无分区';
  return '该项目暂无资产';
};

/**
 * 楼层栏「编辑 / 删除」按钮的提示。
 *
 * <p>三层语义各不同，合并成一句会让使用者以为按钮坏了：
 * 未选具体楼层 → 先选；仅来自资产的楼层（id 为 null）→ 先新增该楼层；否则可操作。
 */
const floorEditTitle = (floor: ProjectZoneFloor | null): string => {
  if (!floor) return '请先选中一个具体楼层';
  if (floor.id == null) return `楼层号 ${floor.floorNo} 来自资产数据，请先「新增楼层」登记后再维护`;
  return `编辑 / 删除「${floorLabel(floor)}」`;
};

/**
 * 「项目分区管理」右侧：分区 Tab 栏 + 楼层 Tab 栏 + 资产表（设计 §5.1 / §6.5）。
 *
 * <p>楼层栏是 V50 新增的**第二级 Tab**：分区 Tab 的补充，只在选中具体分区时出现
 * （「全部分区」下楼层无归属，展示它只会让人以为楼层是项目级概念）。
 */
export function ZoneAssetPane({
  projectId,
  zoneId,
  onZoneChange,
  floorNo,
  onFloorChange,
}: ZoneAssetPaneProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const can = usePerm();

  const canViewProject = can('asset.project', 'view');
  const canUpdateProject = can('asset.project', 'update');
  const canViewLedger = can('asset.ledger', 'view');
  const canCreateLedger = can('asset.ledger', 'create');
  const canUpdateLedger = can('asset.ledger', 'update');
  const canDeleteLedger = can('asset.ledger', 'delete');

  // 无权查看项目时连请求都不发：useProjectZones 对 null 直接返回空列表
  const {
    zones,
    loading: zonesLoading,
    loadFailed: zonesFailed,
    saving: zoneSaving,
    reload: reloadZones,
    save: saveZone,
    remove: removeZone,
  } = useProjectZones(canViewProject ? projectId : null);

  const [editingZone, setEditingZone] = useState<ProjectZone | null>(null);
  /**
   * 分区后续记录弹窗：记录挂在具体分区上，「全部分区」下不可用。
   * 存**分区对象**而不是布尔量：切到「全部分区」时若只关掉 open，布尔量仍是 true，
   * 再选回任一分区会凭空弹出弹窗；存对象则天然只对用户点过的那一个分区生效。
   */
  const [recordSheetZone, setRecordSheetZone] = useState<ProjectZone | null>(null);

  /**
   * 楼层栏（V50）：只在选中具体分区时请求 —— 「全部分区」下没有楼层可维护。
   * 与上面分区一样带上 `canViewProject` 闸门：无权看项目时不该打出一条注定 403 的请求。
   */
  const {
    floors,
    loading: floorsLoading,
    loadFailed: floorsFailed,
    saving: floorSaving,
    reload: reloadFloors,
    save: saveFloor,
    remove: removeFloor,
  } = useProjectZoneFloors(canViewProject ? projectId : null, zoneId);

  const [editingFloor, setEditingFloor] = useState<ProjectZoneFloor | null>(null);

  const [assets, setAssets] = useState<AssetRow[]>([]);
  const [total, setTotal] = useState(0);
  /** 资产分页进 URL（设计 §4）：跳资产表单再返回时本页会整页重新挂载 */
  const { page, setPage } = useListQuery({
    prefix: 'asset',
    defaultPageSize: PAGE_SIZE,
  });
  const [assetsLoading, setAssetsLoading] = useState(false);
  const [assetsFailed, setAssetsFailed] = useState(false);
  const [assetsReloadToken, setAssetsReloadToken] = useState(0);

  const currentZone = zoneId == null ? null : (zones.find((zone) => zone.id === zoneId) ?? null);
  const currentFloor = floorNo == null ? null : (floors.find((f) => f.floorNo === floorNo) ?? null);

  const loadAssets = useCallback(
    async (p: number) => {
      if (projectId == null || !canViewLedger) {
        setAssets([]);
        setTotal(0);
        return;
      }
      setAssetsLoading(true);
      setAssetsFailed(false);
      try {
        const params = new URLSearchParams({
          projectId: String(projectId),
          page: String(p),
          pageSize: String(PAGE_SIZE),
        });
        // 「全部分区」不拼 zoneId：后端该参数可选，缺省即不过滤（spec §2）
        if (zoneId != null) params.set('zoneId', String(zoneId));
        // 「全部楼层」同理不拼 floorNo（V50）
        if (floorNo != null) params.set('floorNo', String(floorNo));
        const result = await api.get<PageResult<AssetRow>>(`/assets?${params.toString()}`);
        setAssets(result?.list ?? []);
        setTotal(Number(result?.total ?? 0));
      } catch {
        setAssets([]);
        setTotal(0);
        setAssetsFailed(true);
      } finally {
        setAssetsLoading(false);
      }
    },
    [projectId, zoneId, floorNo, canViewLedger],
  );

  /**
   * 项目 / 分区 / 页码 / 重拉信号变化 → 按当前条件拉取。
   *
   * <p>**不再在这里 `setPage(1)`**：切换项目或分区时由父级（{@link ProjectZonesPage}）
   * 把 {@link ASSET_PAGE_PARAM} 从 URL 删掉，本组件下一次渲染拿到的就已经是第 1 页，
   * 只发一次请求（原实现会先用旧页码发一次、再补发第 1 页）。
   */
  useEffect(() => {
    void loadAssets(page);
  }, [loadAssets, page, assetsReloadToken]);

  /**
   * URL 里的 zoneId 不属于当前项目（删分区后回退、手改链接）→ 回到「全部分区」。
   *
   * <p>**失败态也必须回退**（spec §5.3「分区列表加载失败时，资产区可继续用『全部分区』」）：
   * `useProjectZones` 失败时会清空 `zones`（`lib/projectZones.ts:70`），此时 `currentZone`
   * 变成 null、`scopeLabel` 回落成「全部分区」，但请求仍按 `zoneId` 过滤 ⇒
   * **表头写「全部分区」而表内只有那一个分区的行**；同时错误态会替掉整个 Tab 栏，
   * 用户连点回「全部分区」的机会都没有。所以这里**不**把 `zonesFailed` 列为提前返回：
   * 失败时 `zones` 已为空，`some(...)` 必假，`zoneId` 自愈为 null，表头与请求重新一致。
   */
  useEffect(() => {
    if (zoneId == null || zonesLoading) return;
    if (zones.some((zone) => zone.id === zoneId)) return;
    onZoneChange(null);
  }, [zoneId, zones, zonesLoading, onZoneChange]);

  /**
   * URL 里的 floorNo 不属于当前分区的楼层 → 回到「全部楼层」。
   *
   * <p>与上面分区那条同一个理由：手改链接、或在楼层栏之外把某层删掉之后，
   * 请求仍会按已不存在的楼层号过滤 —— 表头写「全部楼层」而表内空空如也。
   * 楼层栏加载失败时 `floors` 已为空，同样自愈为「全部楼层」（宁可放宽不过滤，不留下自相矛盾的界面）。
   */
  useEffect(() => {
    if (floorNo == null || floorsLoading) return;
    if (floors.some((floor) => floor.floorNo === floorNo)) return;
    onFloorChange(null);
  }, [floorNo, floors, floorsLoading, onFloorChange]);

  /**
   * 切换项目时关掉分区弹窗：`editingZone` 是上一个项目的行数据，留着它再提交会
   * PUT `/projects/{新项目}/zones/{旧分区}`（后端会拒，但报错与用户眼前所见对不上）。
   */
  useEffect(() => {
    setEditingZone(null);
  }, [projectId]);

  /**
   * 切换分区时关掉楼层弹窗：`editingFloor` 属于上一个分区，留着它再提交会
   * PUT `/zones/{新分区}/floors/{旧楼层}`。楼层 Tab 本身随 `zoneId` 变化重新拉取，
   * 弹窗也必须一起收掉。
   */
  useEffect(() => {
    setEditingFloor(null);
  }, [zoneId]);

  /** 翻页只写 URL：上面的 effect 会以新页码拉取，避免「显式 load + effect」双发 */
  const handlePageChange = (p: number) => setPage(p);

  /**
   * 重拉资产：**先回到第 1 页**，再自增重拉信号。
   *
   * <p>改造前这一步由「项目 / 分区变化」的 effect 无条件 `setPage(1)` 完成，因此每次
   * 只自增 `assetsReloadToken` 的 `reloadAssets()` 都会顺带把资产表归到第 1 页。列表状态
   * 进 URL 后该 effect 不再归页，重置必须回到本函数里，否则删除末页最后一条会停在空页。
   * 同一处理函数里 `setPage(1)` 与信号自增会被 React 18 批处理成**一次** effect 运行
   * （`page` 与 `assetsReloadToken` 同批更新，effect 只跑一次），不会发两次请求。
   */
  const reloadAssets = () => {
    setPage(1);
    setAssetsReloadToken((token) => token + 1);
  };

  /**
   * spec §5.2 第 7 条：「刷新」必须**同时**重拉 Tab 栏与资产区（资产归第 1 页）。
   *
   * <p>只刷资产表会让 Tab 上的汇总数字（`assetCount` / `assetArea`，后端按分区实时汇总）
   * 与表内行数逐渐对不上 —— 这正是本页最容易漏的一处联动。
   *
   * <p>V50 起楼层栏上的数字同样来自后端汇总：楼层 Tab 的宗数不刷就会出现
   * 「分区说 20 宗、楼层加起来只有 18 宗」。
   */
  const handleRefresh = () => {
    void reloadZones();
    void reloadFloors();
    reloadAssets();
  };

  const handleSubmitZone = async (values: ProjectZone) => {
    if (!editingZone) return;
    try {
      // 编辑时 id 取自 editingZone（表单不含该字段），其余以表单为准
      await saveZone(editingZone.id != null ? { ...values, id: editingZone.id } : values);
      message.success(editingZone.id != null ? '保存成功' : '新增成功');
      setEditingZone(null);
      // spec §5.2 第 3 条：分区**新增/编辑**同样要重拉资产区并归第 1 页。
      // （分区删除走 handleDeleteZone，那条路径靠 onZoneChange(null) 改动 zoneId 间接触发）
      reloadAssets();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    }
  };

  const handleDeleteZone = () => {
    if (!currentZone) return;
    confirmDelete({
      name: currentZone.name,
      resourceLabel: '分区',
      onOk: async () => {
        try {
          await removeZone(currentZone);
          message.success('已删除');
          // 删的就是当前 Tab：回到「全部分区」（spec §5.2 第 4 条）
          onZoneChange(null);
        } catch (e) {
          // 有资产 / 有后续记录时后端返回 400，原因原样透出
          message.error(e instanceof Error ? e.message : '删除失败');
          throw e;
        }
      },
    });
  };

  /**
   * 新增楼层：缺省楼层号取「同分区最大楼层号 + 1」。
   *
   * <p>取最大而非「行数 + 1」：楼层号允许跳号（20F/23F 这种真实楼栋很常见），
   * 用行数会撞号；`Math.max` 在空列表上返回 `-Infinity`，故用 reduce 并给 0 兜底。
   */
  const handleAddFloor = () => {
    const maxFloorNo = floors.reduce((max, floor) => Math.max(max, floor.floorNo), 0);
    setEditingFloor({ floorNo: maxFloorNo + 1 });
  };

  const handleSubmitFloor = async (values: ProjectZoneFloor) => {
    if (!editingFloor) return;
    try {
      // 编辑时 id 取自 editingFloor（表单不含该字段），其余以表单为准
      await saveFloor(editingFloor.id != null ? { ...values, id: editingFloor.id } : values);
      message.success(editingFloor.id != null ? '保存成功' : '新增成功');
      setEditingFloor(null);
      // 楼层栏上的资产数/面积由后端按 floor_no 汇总，楼层变动后资产区必须一并重拉
      reloadAssets();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    }
  };

  const handleDeleteFloor = () => {
    if (!currentFloor) return;
    confirmDelete({
      name: floorLabel(currentFloor),
      resourceLabel: '楼层',
      onOk: async () => {
        try {
          await removeFloor(currentFloor);
          message.success('已删除');
          // 删的就是当前楼层 Tab → 回到「全部楼层」
          onFloorChange(null);
          reloadAssets();
        } catch (e) {
          // 该层还有资产时后端返回 400，原因原样透出
          message.error(e instanceof Error ? e.message : '删除失败');
          throw e;
        }
      },
    });
  };

  const handleAddAsset = () => {
    const params = new URLSearchParams({ lockScope: '1' });
    if (projectId != null) params.set('projectId', String(projectId));
    /**
     * 「全部分区」下**不预填也不锁定**分区：表单里项目是灰化的（归属已定），
     * 分区则留空且**可选**，由使用者在该项目的分区中指定 —— 这正是「全部分区」Tab 的现实语义：
     * 还没决定放哪层，进表单再选。预填无意义，锁定则会让分区选不了。
     */
    if (zoneId != null) params.set('zoneId', String(zoneId));
    // 选中楼层时把楼层号一并带过去，新增资产直接落在这一层（AssetFormPage 读 floorNo 参数）
    if (floorNo != null) params.set('floorNo', String(floorNo));
    navigate(`/assets/create?${params.toString()}`, { state: { from: currentPath(location) } });
  };

  const handleEditAsset = (assetId: number) => {
    navigate(`/assets/${assetId}/edit?lockScope=1`, {
      state: { from: currentPath(location) },
    });
  };

  const handleDeleteAsset = (asset: AssetRow) => {
    confirmDelete({
      name: asset.name,
      resourceLabel: '资产',
      onOk: async () => {
        try {
          await api.del(`/assets/${asset.id}`);
          message.success('已删除');
          // Tab 上的资产数/面积由后端按分区汇总，资产变动后必须一并重拉（spec §5.2 第 5 条）。
          // 楼层 Tab 的数字同一来源（V50），也要一起刷
          await reloadZones();
          void reloadFloors();
          reloadAssets();
        } catch (e) {
          message.error(e instanceof Error ? e.message : '删除失败');
          throw e;
        }
      },
    });
  };

  const tabItems = [
    { key: ALL_ZONES_KEY, label: '全部分区' },
    ...(canViewProject
      ? zones.map((zone) => ({
          key: String(zone.id),
          label: (
            <span className="inline-flex items-center gap-1">
              {zone.name}
              <span className="text-xs text-gray-400 tabular-nums">
                {Number(zone.assetCount ?? 0)}
              </span>
            </span>
          ),
        }))
      : []),
  ];

  /**
   * 楼层 Tab 项。数字是后端按 `asset.floor_no` 汇总的本层宗数 ——
   * 与分区 Tab 同一个数字来源，不刷新就没有可比性（见 handleRefresh）。
   */
  const floorTabItems = [
    { key: ALL_FLOORS_KEY, label: '全部楼层' },
    ...floors.map((floor) => ({
      key: String(floor.floorNo),
      label: (
        <span className="inline-flex items-center gap-1">
          {floorLabel(floor)}
          <span className="text-xs text-gray-400 tabular-nums">
            {Number(floor.assetCount ?? 0)}
          </span>
        </span>
      ),
    })),
  ];

  const zoneColumn: ColumnsType<AssetRow> =
    zoneId == null
      ? [
          {
            title: '分区',
            dataIndex: 'zoneName',
            key: 'zoneName',
            width: 130,
            ellipsis: true,
            render: (value: unknown) => String(value ?? '—'),
          },
        ]
      : [];

  const columns: ColumnsType<AssetRow> = [
    { title: '资产编号', dataIndex: 'assetNo', key: 'assetNo', width: 150, ellipsis: true },
    { title: '资产名称', dataIndex: 'name', key: 'name', width: 180, ellipsis: true },
    {
      title: '资产类型',
      dataIndex: 'assetType',
      key: 'assetType',
      width: 110,
      render: (value: unknown) => ASSET_TYPE[String(value ?? '')] ?? String(value ?? '—'),
    },
    // 「全部分区」下各行的分区不同才有必要展示；选中具体分区时该列恒为同一个值
    ...zoneColumn,
    // 同理：选中具体楼层时该列恒为同一个值，占一列宽毫无信息量
    ...(floorNo == null
      ? [
          {
            title: '分区楼层',
            dataIndex: 'floorNo',
            key: 'floorNo',
            width: 100,
            render: (value: unknown) =>
              value == null ? '—' : floorLabel({ floorNo: Number(value), name: null }),
          } as ColumnsType<AssetRow>[number],
        ]
      : []),
    {
      title: '资产面积(㎡)',
      dataIndex: 'area',
      key: 'area',
      width: 130,
      render: (value: unknown) => formatArea(value),
    },
    {
      title: '租控状态',
      dataIndex: 'leaseControlStatus',
      key: 'leaseControlStatus',
      width: 110,
      render: (value: unknown) => LEASE_CONTROL_STATUS[String(value ?? '')] ?? String(value ?? '—'),
    },
  ];

  if (canViewLedger) {
    columns.push({
      title: '操作',
      key: '_actions',
      // 删除在 more 里：hasMore 必须与下方 more 数组的实际内容（canDeleteLedger）一致
      width: actionsColumnWidth(['编辑', '一物一档'], { hasMore: canDeleteLedger }),
      render: (_: unknown, asset: AssetRow) => {
        const actions: TableActionItem[] = [];
        if (canUpdateLedger) {
          actions.push({
            key: 'edit',
            label: '编辑',
            icon: <EditOutlined />,
            onClick: () => handleEditAsset(asset.id),
          });
        }
        actions.push({
          key: 'dossier',
          label: '一物一档',
          icon: <FileTextOutlined />,
          to: `/assets/${asset.id}/dossier`,
        });
        const more: TableActionItem[] = [];
        if (canDeleteLedger) {
          more.push({
            key: 'delete',
            label: '删除',
            icon: <DeleteOutlined />,
            danger: true,
            onClick: () => handleDeleteAsset(asset),
          });
        }
        return <TableActions actions={actions} more={more} max={2} />;
      },
    });
  }

  if (projectId == null) {
    return (
      <div className="flex-1 min-w-0 border border-[var(--ams-border)] rounded-lg bg-white p-4">
        <Empty description="请先在左侧选择项目" className="py-16" />
      </div>
    );
  }

  /**
   * 当前展示范围的名字：选中楼层时带上分区，避免只写「3F」看不出是哪个分区的 3F。
   */
  const scopeLabel =
    currentZone && currentFloor
      ? `${currentZone.name} / ${floorLabel(currentFloor)}`
      : (currentZone?.name ?? '全部分区');
  /** 合计面积：选中楼层取本层，否则取分区；「全部分区」下两者都没有，不显示 */
  const scopeArea = currentFloor?.assetArea ?? currentZone?.assetArea;

  return (
    <div className="flex-1 min-w-0 border border-[var(--ams-border)] rounded-lg bg-white p-3 flex flex-col">
      {!canViewProject ? (
        <div className="py-2 text-sm text-gray-500">无项目查看权限，仅显示全部分区资产</div>
      ) : zonesFailed ? (
        <div className="py-2 text-center text-sm text-gray-500">
          分区加载失败
          <Button type="link" size="small" onClick={() => void reloadZones()}>
            重试
          </Button>
        </div>
      ) : (
        <>
          <Tabs
            size="small"
            activeKey={zoneId == null ? ALL_ZONES_KEY : String(zoneId)}
            onChange={(key) => onZoneChange(key === ALL_ZONES_KEY ? null : Number(key))}
            items={tabItems}
            tabBarExtraContent={
              canUpdateProject ? (
                <Space size={4} wrap>
                  <Button
                    size="small"
                    type="primary"
                    icon={<PlusOutlined />}
                    onClick={() => setEditingZone({ name: '' })}
                  >
                    新增分区
                  </Button>
                  <Button
                    size="small"
                    icon={<EditOutlined />}
                    disabled={!currentZone}
                    onClick={() => currentZone && setEditingZone(currentZone)}
                  >
                    编辑
                  </Button>
                  {/* 后续记录逐分区维护：「全部分区」下没有可挂的归属，故须先选中具体分区 */}
                  <Button
                    size="small"
                    icon={<ProfileOutlined />}
                    disabled={!currentZone}
                    title={currentZone ? '查看 / 编辑该分区的后续记录' : '请先选中一个具体分区'}
                    onClick={() => setRecordSheetZone(currentZone)}
                  >
                    后续记录
                  </Button>
                  <Button
                    size="small"
                    danger
                    icon={<DeleteOutlined />}
                    disabled={!currentZone}
                    onClick={handleDeleteZone}
                  >
                    删除
                  </Button>
                </Space>
              ) : undefined
            }
          />

          {/*
            楼层栏（V50）：一级分区 Tab 的**二级 Tab**，只在选中具体分区时出现 ——
            「全部分区」下楼层没有归属，摆出来只会让人以为楼层是项目级概念。
            缩进 + 左侧竖线表示从属关系，避免两行 Tab 看起来是并列的两组筛选。
          */}
          {zoneId != null && (
            <div className="pl-3 -mt-2 border-l-2 border-[var(--ams-border)]">
              {floorsFailed ? (
                <div className="py-1 text-sm text-gray-500">
                  楼层加载失败
                  <Button type="link" size="small" onClick={() => void reloadFloors()}>
                    重试
                  </Button>
                </div>
              ) : (
                <Tabs
                  size="small"
                  activeKey={floorNo == null ? ALL_FLOORS_KEY : String(floorNo)}
                  onChange={(key) => onFloorChange(key === ALL_FLOORS_KEY ? null : Number(key))}
                  items={floorTabItems}
                  tabBarExtraContent={
                    canUpdateProject ? (
                      <Space size={4} wrap>
                        <Button size="small" icon={<PlusOutlined />} onClick={handleAddFloor}>
                          新增楼层
                        </Button>
                        <Button
                          size="small"
                          icon={<EditOutlined />}
                          disabled={!currentFloor || currentFloor.id == null}
                          title={floorEditTitle(currentFloor)}
                          onClick={() => currentFloor && setEditingFloor(currentFloor)}
                        >
                          编辑
                        </Button>
                        <Button
                          size="small"
                          danger
                          icon={<DeleteOutlined />}
                          disabled={!currentFloor || currentFloor.id == null}
                          title={floorEditTitle(currentFloor)}
                          onClick={handleDeleteFloor}
                        >
                          删除
                        </Button>
                      </Space>
                    ) : undefined
                  }
                />
              )}
            </div>
          )}
        </>
      )}

      <div className="flex items-center justify-between gap-3 mb-3 flex-wrap">
        <span className="text-sm text-gray-600 min-w-0">
          <span className="font-medium">{scopeLabel}</span>
          <span className="text-xs text-gray-400 ml-2 tabular-nums">共 {total} 宗</span>
          {scopeArea != null && (
            <span className="text-xs text-gray-400 ml-2 tabular-nums">
              合计 {formatArea(scopeArea)} ㎡
            </span>
          )}
        </span>
        <span className="flex items-center gap-2">
          <Button
            size="small"
            icon={<ReloadOutlined />}
            onClick={handleRefresh}
            aria-label="刷新分区与资产列表"
          />
          {canCreateLedger && (
            <Button size="small" type="primary" icon={<PlusOutlined />} onClick={handleAddAsset}>
              新增资产
            </Button>
          )}
        </span>
      </div>

      {!canViewLedger ? (
        <div className="py-16 text-center text-sm text-gray-500">无资产查看权限</div>
      ) : assetsFailed ? (
        <div className="py-16 text-center text-sm text-gray-500">
          资产加载失败
          <Button type="link" size="small" onClick={reloadAssets}>
            重试
          </Button>
        </div>
      ) : assetsLoading && assets.length === 0 ? (
        <div className="py-16 text-center">
          <Spin />
        </div>
      ) : assets.length === 0 ? (
        <Empty
          description={emptyAssetText(
            zoneId,
            floorNo,
            canViewProject && !zonesFailed,
            zones.length,
          )}
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          className="py-10"
        />
      ) : (
        <>
          <Table
            rowKey={(asset) => String(asset.id)}
            columns={columns}
            dataSource={assets}
            loading={assetsLoading}
            pagination={false}
            size="small"
            scroll={{ x: 960 }}
          />
          <div className="flex justify-end mt-3">
            <Pagination
              size="small"
              current={page}
              pageSize={PAGE_SIZE}
              total={total}
              showSizeChanger={false}
              showTotal={(t) => `共 ${t} 条`}
              onChange={handlePageChange}
            />
          </div>
        </>
      )}

      <ZoneFormModal
        editing={editingZone}
        submitting={zoneSaving}
        onCancel={() => setEditingZone(null)}
        onSubmit={(values) => void handleSubmitZone(values)}
      />

      {/* 楼层新增/编辑：与上面分区弹窗同一入口，不再跳页维护（V50） */}
      <ZoneFloorFormModal
        editing={editingFloor}
        submitting={floorSaving}
        onCancel={() => setEditingFloor(null)}
        onSubmit={(values) => void handleSubmitFloor(values)}
      />

      {/* 分区后续记录：与分区详情页同一套读写，就地增删改查而不必跳页 */}
      <RecordSheetModal
        open={!!recordSheetZone}
        ownerType="zone"
        ownerId={recordSheetZone?.id}
        projectId={projectId}
        subjectLabel={recordSheetZone?.name}
        // 与分区详情页、后端 PUT /projects/{pid}/zones/{zoneId}/record-sheet 的权限口径一致
        savePerm="asset.project:update"
        onClose={() => setRecordSheetZone(null)}
      />
    </div>
  );
}
