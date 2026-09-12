import { useCallback, useEffect, useState } from 'react';
import { Button, Empty, Form, Input, InputNumber, Modal, Spin, Table, message } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { api } from '@/lib/api';
import { confirmDelete } from '@/lib/confirm';
import { PermissionGuard, usePerm } from '@/lib/perm';
import { TableActions } from '@/components/TableActions';

/**
 * 项目分区（project_zone）行数据。
 *
 * <p>`assetArea` / `assetCount` 是后端汇总出来的**只读**字段：分区面积不接受人工维护，
 * 统一取该分区下资产面积合计（见 V25 迁移与 AssetService#fillZoneAssetStats）。
 */
export interface ProjectZone {
  id?: number;
  projectId?: number;
  name: string;
  code?: string;
  sort?: number;
  remark?: string;
  /** 只读：该分区下资产面积合计(㎡) */
  assetArea?: number;
  /** 只读：该分区下资产数量 */
  assetCount?: number;
}

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
 */
export function ProjectZonesPanel({ projectId }: { projectId: number }) {
  const can = usePerm();
  const canUpdate = can('asset.project', 'update');

  const [zones, setZones] = useState<ProjectZone[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [editing, setEditing] = useState<ProjectZone | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    setLoadFailed(false);
    try {
      setZones(await api.get<ProjectZone[]>(`/projects/${projectId}/zones`));
    } catch {
      // 面板加载失败只影响本面板：外层项目列表继续可用，故不弹全局错误
      setZones([]);
      setLoadFailed(true);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 打开新增（zone=null）或编辑；forceRender 保证 Form 已挂载，可安全回填 */
  const openEditor = (zone: ProjectZone | null) => {
    setEditing(zone ?? { name: '' });
    form.setFieldsValue({
      name: zone?.name ?? '',
      code: zone?.code ?? '',
      sort: zone?.sort,
      remark: zone?.remark ?? '',
    });
  };

  const handleDelete = (zone: ProjectZone) => {
    confirmDelete({
      name: zone.name,
      resourceLabel: '分区',
      onOk: async () => {
        try {
          await api.del(`/projects/${projectId}/zones/${zone.id}`);
          message.success('已删除');
          await load();
        } catch (e) {
          // 后端在分区下有资产时返回 400，原因必须原样透出（设计 §3.2）
          message.error(e instanceof Error ? e.message : '删除失败');
          throw e;
        }
      },
    });
  };

  const handleSubmit = async () => {
    if (!editing) return;
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      if (editing.id != null) {
        await api.put(`/projects/${projectId}/zones/${editing.id}`, values);
        message.success('保存成功');
      } else {
        await api.post(`/projects/${projectId}/zones`, values);
        message.success('新增成功');
      }
      setEditing(null);
      await load();
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSubmitting(false);
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
      width: 140,
      render: (_: unknown, zone: ProjectZone) => (
        <TableActions
          actions={[
            {
              key: 'edit',
              label: '编辑',
              icon: <EditOutlined />,
              onClick: () => openEditor(zone),
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
          <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          <PermissionGuard perm="asset.project:update">
            <Button
              size="small"
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => openEditor(null)}
            >
              新增分区
            </Button>
          </PermissionGuard>
        </span>
      </div>

      {loadFailed ? (
        <div className="py-6 text-center text-sm text-gray-500">
          分区加载失败
          <Button type="link" size="small" onClick={() => void load()}>
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

      <Modal
        title={editing?.id != null ? '编辑分区' : '新增分区'}
        open={!!editing}
        // forceRender：Form 常驻挂载，openEditor 里的 setFieldsValue 不会因未连接而告警
        forceRender
        onCancel={() => setEditing(null)}
        onOk={() => void handleSubmit()}
        confirmLoading={submitting}
        width={Math.min(480, typeof window !== 'undefined' ? window.innerWidth - 32 : 480)}
      >
        <Form form={form} layout="vertical" className="mt-2">
          <Form.Item
            name="name"
            label="分区名称"
            rules={[{ required: true, message: '请填写分区名称' }]}
          >
            <Input placeholder="如 A区" />
          </Form.Item>
          <Form.Item name="code" label="分区编码">
            <Input placeholder="如 A" />
          </Form.Item>
          <Form.Item name="sort" label="排序" extra="留空表示追加到末尾">
            <InputNumber className="w-full" />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
