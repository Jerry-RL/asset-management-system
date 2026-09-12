import { useState } from 'react';
import { Button, Empty, Spin, Table, message } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { confirmDelete } from '@/lib/confirm';
import { PermissionGuard, usePerm } from '@/lib/perm';
import { TableActions, actionsColumnWidth } from '@/components/TableActions';
import { ZoneFormModal } from '@/components/ZoneFormModal';
import { useProjectZones, type ProjectZone } from '@/lib/projectZones';

/** 面积千分位展示，空值按 0 处理（后端对无资产分区已补 0） */
const formatArea = (value: unknown) =>
  Number(value ?? 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

/**
 * 项目列表展开行内的分区面板（设计 §5.2）。
 *
 * <p>三个写操作与后端三个分区级接口一一对应，权限统一为 `asset.project:update`：
 * 这是**完整判定码**而不是 by-path 推导 —— 本组件挂在展开行里，没有自己的路由，
 * `usePermByPath()` 会解析不到 menuCode 而放行。
 *
 * <p>读写逻辑与「项目分区管理」页共用 {@link useProjectZones}，弹窗共用 {@link ZoneFormModal}：
 * 两条入口必须给出同样的排序缺省、只读字段口径与报错透传。
 */
export function ProjectZonesPanel({ projectId }: { projectId: number }) {
  const can = usePerm();
  const canUpdate = can('asset.project', 'update');
  const { zones, loading, loadFailed, saving, reload, save, remove } = useProjectZones(projectId);
  const [editing, setEditing] = useState<ProjectZone | null>(null);

  const handleDelete = (zone: ProjectZone) => {
    confirmDelete({
      name: zone.name,
      resourceLabel: '分区',
      onOk: async () => {
        try {
          await remove(zone);
          message.success('已删除');
        } catch (e) {
          // 后端在分区下有资产 / 有后续记录时返回 400，原因必须原样透出
          message.error(e instanceof Error ? e.message : '删除失败');
          throw e;
        }
      },
    });
  };

  const handleSubmit = async (values: ProjectZone) => {
    if (!editing) return;
    try {
      // 编辑时 id 取自 editing（表单里没有这个字段），其余字段以表单为准
      await save(editing.id != null ? { ...values, id: editing.id } : values);
      message.success(editing.id != null ? '保存成功' : '新增成功');
      setEditing(null);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    }
  };

  const columns: ColumnsType<ProjectZone> = [
    { title: '分区名称', dataIndex: 'name', key: 'name', width: 160 },
    {
      title: '分区编码',
      dataIndex: 'code',
      key: 'code',
      width: 120,
      render: (value: unknown) => String(value ?? '—'),
    },
    {
      title: '资产面积(㎡)',
      dataIndex: 'assetArea',
      key: 'assetArea',
      width: 140,
      render: (value: unknown) => formatArea(value),
    },
    {
      title: '资产数',
      dataIndex: 'assetCount',
      key: 'assetCount',
      width: 90,
      render: (value: unknown) => String(value ?? 0),
    },
    {
      title: '排序',
      dataIndex: 'sort',
      key: 'sort',
      width: 80,
      render: (value: unknown) => String(value ?? '—'),
    },
    {
      title: '备注',
      dataIndex: 'remark',
      key: 'remark',
      render: (value: unknown) => String(value ?? '—'),
    },
  ];

  // 无权时整列不出现，而不是留一个每行都空白的「操作」列
  if (canUpdate) {
    columns.push({
      title: '操作',
      key: '_actions',
      width: actionsColumnWidth(['编辑', '删除']),
      render: (_: unknown, zone: ProjectZone) => (
        <TableActions
          actions={[
            {
              key: 'edit',
              label: '编辑',
              icon: <EditOutlined />,
              onClick: () => setEditing(zone),
            },
            {
              key: 'delete',
              label: '删除',
              icon: <DeleteOutlined />,
              danger: true,
              onClick: () => handleDelete(zone),
            },
          ]}
        />
      ),
    });
  }

  return (
    <div className="space-y-3 py-1">
      <div className="flex items-center justify-between gap-3">
        <span className="text-sm text-gray-600">
          项目分区
          <span className="text-xs text-gray-400 ml-2">
            分区面积由该分区下资产面积自动汇总，只读
          </span>
        </span>
        <span className="flex items-center gap-2">
          <Button size="small" icon={<ReloadOutlined />} onClick={() => void reload()}>
            刷新
          </Button>
          <PermissionGuard perm="asset.project:update">
            <Button
              size="small"
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setEditing({ name: '' })}
            >
              新增分区
            </Button>
          </PermissionGuard>
        </span>
      </div>

      {loadFailed ? (
        <div className="py-6 text-center text-sm text-gray-500">
          分区加载失败
          <Button type="link" size="small" onClick={() => void reload()}>
            重试
          </Button>
        </div>
      ) : loading ? (
        <div className="py-6 text-center">
          <Spin />
        </div>
      ) : zones.length === 0 ? (
        <Empty description="暂无分区" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        <Table
          rowKey={(zone) => String(zone.id ?? zone.name)}
          columns={columns}
          dataSource={zones}
          pagination={false}
          size="small"
        />
      )}

      <ZoneFormModal
        editing={editing}
        submitting={saving}
        onCancel={() => setEditing(null)}
        onSubmit={(values) => void handleSubmit(values)}
      />
    </div>
  );
}
