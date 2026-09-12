import { useCallback, useEffect, useState } from 'react';
import { Button, Empty, Pagination, Space, Spin, Table, Tabs, message } from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  FileTextOutlined,
  PlusOutlined,
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
import { TableActions, actionsColumnWidth, type TableActionItem } from '@/components/TableActions';
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
}

/** 「全部分区」Tab 的 key：分区 id 都是正数，不会与它冲突 */
const ALL_ZONES_KEY = 'all';
const PAGE_SIZE = 10;

/**
 * 右栏资产分页的 URL 键名。
 *
 * <p>导出给 {@link ProjectZonesPage}：切换项目/分区时它必须**原子地**把这个键删掉
 * （回到第 1 页），否则会先按上一个分区的旧页码发一次请求再补发第 1 页。
 */
export const ASSET_PAGE_PARAM = 'assetPage';

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
const emptyAssetText = (zoneId: number | null, zonesKnown: boolean, zoneCount: number): string => {
  if (zoneId != null) return '该分区暂无资产';
  if (zonesKnown && zoneCount === 0) return '该项目暂无分区';
  return '该项目暂无资产';
};

/**
 * 「项目分区管理」右侧：分区 Tab 栏 + 该分区资产表（设计 §5.1 / §6.5）。
 */
export function ZoneAssetPane({ projectId, zoneId, onZoneChange }: ZoneAssetPaneProps) {
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
    [projectId, zoneId, canViewLedger],
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
   * 切换项目时关掉分区弹窗：`editingZone` 是上一个项目的行数据，留着它再提交会
   * PUT `/projects/{新项目}/zones/{旧分区}`（后端会拒，但报错与用户眼前所见对不上）。
   */
  useEffect(() => {
    setEditingZone(null);
  }, [projectId]);

  /** 翻页只写 URL：上面的 effect 会以新页码拉取，避免「显式 load + effect」双发 */
  const handlePageChange = (p: number) => setPage(p);

  const reloadAssets = () => setAssetsReloadToken((token) => token + 1);

  /**
   * spec §5.2 第 7 条：「刷新」必须**同时**重拉 Tab 栏与资产区（资产归第 1 页）。
   *
   * <p>只刷资产表会让 Tab 上的汇总数字（`assetCount` / `assetArea`，后端按分区实时汇总）
   * 与表内行数逐渐对不上 —— 这正是本页最容易漏的一处联动。
   */
  const handleRefresh = () => {
    void reloadZones();
    setPage(1);
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

  const handleAddAsset = () => {
    const params = new URLSearchParams({ lockScope: '1' });
    if (projectId != null) params.set('projectId', String(projectId));
    // 「全部分区」下不预填分区：归属留空由使用者在表单里选
    if (zoneId != null) params.set('zoneId', String(zoneId));
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
          // Tab 上的资产数/面积由后端按分区汇总，资产变动后必须一并重拉（spec §5.2 第 5 条）
          await reloadZones();
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
    {
      title: '分区楼层',
      dataIndex: 'floorNo',
      key: 'floorNo',
      width: 100,
      render: (value: unknown) => (value == null ? '—' : `${value}F`),
    },
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

  const scopeLabel = currentZone?.name ?? '全部分区';

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
      )}

      <div className="flex items-center justify-between gap-3 mb-3 flex-wrap">
        <span className="text-sm text-gray-600 min-w-0">
          <span className="font-medium">{scopeLabel}</span>
          <span className="text-xs text-gray-400 ml-2 tabular-nums">共 {total} 宗</span>
          {currentZone && (
            <span className="text-xs text-gray-400 ml-2 tabular-nums">
              合计 {formatArea(currentZone.assetArea)} ㎡
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
          description={emptyAssetText(zoneId, canViewProject && !zonesFailed, zones.length)}
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
    </div>
  );
}
