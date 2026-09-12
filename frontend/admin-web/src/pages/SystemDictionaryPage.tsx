import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Tooltip,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  BookOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  TagsOutlined,
} from '@ant-design/icons';
import { api } from '@/lib/api';
import { confirmDelete } from '@/lib/confirm';
import { TableActions } from '@/components/TableActions';
import { usePermByPath } from '@/lib/perm';

// ============================================================================
// 系统字典：左侧模块 → 右侧字典 Tab → 字典项，支持对单个字典单独新增字典项
// ============================================================================

interface SysDictItem {
  id: number;
  typeId: number;
  value: string;
  label: string;
  sort: number;
  status: number;
  remark?: string;
}

interface SysDictType {
  id: number;
  moduleId: number;
  code: string;
  name: string;
  sort: number;
  status: number;
  remark?: string;
  items: SysDictItem[];
}

interface SysDictModule {
  id: number;
  code: string;
  name: string;
  sort: number;
  status: number;
  remark?: string;
  types: SysDictType[];
}

/**
 * 字典关联：
 *   sourceItemId 为空  → 字典级关联（整本字典挂接另一字典的若干个字典值，仅关联查询）
 *   sourceItemId 非空  → 字典项级级联（父字典该项决定子字典可见项白名单）
 */
interface SysDictRelation {
  id: number;
  sourceTypeId: number;
  sourceItemId?: number | null;
  targetTypeId: number;
  targetItemId: number;
  sort: number;
  status: number;
}

/** 关联编辑草稿：父字典项（可空）+ 一个目标字典 + 选中的字典值 */
interface RelationDraft {
  sourceItemId?: number;
  targetTypeId?: number;
  itemIds: number[];
}

/** 关联只读展示：一组「父字典项 → 目标字典 → 字典值」 */
interface RelationGroupView {
  sourceItemId: number | null;
  sourceItemLabel: string | null;
  targetTypeId: number;
  targetTypeName: string;
  items: { value: string; label: string }[];
}

type EditorKind = 'module' | 'type' | 'item';

const STATUS_OPTIONS = [
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
];

const EDITOR_TITLE: Record<EditorKind, { create: string; edit: string }> = {
  module: { create: '新增字典模块', edit: '编辑字典模块' },
  type: { create: '新增字典', edit: '编辑字典' },
  item: { create: '新增字典项', edit: '编辑字典项' },
};

export function SystemDictionaryPage() {
  // 路由 /system/dict 在 PATH_TO_CODE 镜像里，故按当前路由派生判定，无需写死 code
  const canDo = usePermByPath();
  const [tree, setTree] = useState<SysDictModule[]>([]);
  const [activeModuleId, setActiveModuleId] = useState<number | null>(null);
  const [activeTypeId, setActiveTypeId] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);

  const [editorOpen, setEditorOpen] = useState(false);
  const [editorKind, setEditorKind] = useState<EditorKind>('item');
  const [editing, setEditing] = useState<SysDictModule | SysDictType | SysDictItem | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  // 字典关联编辑（挂在「新增/编辑字典」弹窗内）
  const [relationDrafts, setRelationDrafts] = useState<RelationDraft[]>([]);
  const [relationLoading, setRelationLoading] = useState(false);

  const loadTree = useCallback(async () => {
    setLoading(true);
    try {
      const list = await api.get<SysDictModule[]>('/system/dict/tree');
      setTree(list);
    } catch (e) {
      setTree([]);
      message.error(e instanceof Error ? e.message : '加载字典失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadTree();
  }, [loadTree]);

  // 树加载完成后校正选中项，避免删除后选中失效
  useEffect(() => {
    const module = tree.find((m) => m.id === activeModuleId) ?? tree[0] ?? null;
    const nextModuleId = module?.id ?? null;
    if (nextModuleId !== activeModuleId) setActiveModuleId(nextModuleId);
    const type = module?.types.find((t) => t.id === activeTypeId) ?? module?.types[0] ?? null;
    const nextTypeId = type?.id ?? null;
    if (nextTypeId !== activeTypeId) setActiveTypeId(nextTypeId);
  }, [tree, activeModuleId, activeTypeId]);

  const activeModule = useMemo(
    () => tree.find((m) => m.id === activeModuleId) ?? null,
    [tree, activeModuleId],
  );
  const activeType = useMemo(
    () => activeModule?.types.find((t) => t.id === activeTypeId) ?? null,
    [activeModule, activeTypeId],
  );

  const handleSelectModule = (module: SysDictModule) => {
    setActiveModuleId(module.id);
    setActiveTypeId(module.types[0]?.id ?? null);
  };

  // 关联查询：当前字典挂接的关联字典值（只读展示）
  const [activeRelations, setActiveRelations] = useState<RelationGroupView[]>([]);

  useEffect(() => {
    if (!activeTypeId) {
      setActiveRelations([]);
      return;
    }
    let cancelled = false;
    const load = async () => {
      try {
        const data = await api.get<{ groups: RelationGroupView[] }>(
          `/system/dict/relations/grouped?sourceTypeId=${activeTypeId}`,
        );
        if (!cancelled) setActiveRelations(data.groups ?? []);
      } catch {
        if (!cancelled) setActiveRelations([]);
      }
    };
    void load();
    return () => {
      cancelled = true;
    };
  }, [activeTypeId, tree]);

  // ---- 字典关联：字典 → 另一字典的若干个字典值 ----

  /** 全部字典（跨模块），供关联选择目标字典 */
  const allTypes = useMemo(
    () =>
      tree.flatMap((m) =>
        m.types.map((t) => ({ ...t, moduleName: m.name, fullName: `${m.name} / ${t.name}` })),
      ),
    [tree],
  );

  /** 拉取某字典已有的关联，转成编辑草稿 */
  const loadRelations = useCallback(async (sourceTypeId: number) => {
    setRelationLoading(true);
    try {
      const list = await api.get<SysDictRelation[]>(
        `/system/dict/relations?sourceTypeId=${sourceTypeId}`,
      );
      const grouped = new Map<string, RelationDraft>();
      list.forEach((r) => {
        const key = `${r.sourceItemId ?? ''}|${r.targetTypeId}`;
        const draft = grouped.get(key) ?? {
          sourceItemId: r.sourceItemId ?? undefined,
          targetTypeId: r.targetTypeId,
          itemIds: [],
        };
        draft.itemIds.push(r.targetItemId);
        grouped.set(key, draft);
      });
      setRelationDrafts([...grouped.values()]);
    } catch (e) {
      setRelationDrafts([]);
      message.error(e instanceof Error ? e.message : '加载字典关联失败');
    } finally {
      setRelationLoading(false);
    }
  }, []);

  /**
   * 保存某字典的关联（整体替换）。
   * @param force 编辑已有字典时即使清空也要提交，否则无法删除已有全部关联
   */
  const saveRelations = async (sourceTypeId: number, force: boolean) => {
    const groups = relationDrafts
      .filter((d) => d.targetTypeId && d.itemIds.length > 0)
      .map((d) => ({
        sourceItemId: d.sourceItemId,
        targetTypeId: d.targetTypeId,
        itemIds: d.itemIds,
      }));
    if (!force && relationDrafts.length === 0) return;
    await api.put('/system/dict/relations', { sourceTypeId, groups });
  };

  const itemsOfType = (targetTypeId?: number) =>
    targetTypeId ? (allTypes.find((t) => t.id === targetTypeId)?.items ?? []) : [];

  // ---- 编辑弹窗 ----

  const openEditor = (kind: EditorKind, record?: SysDictModule | SysDictType | SysDictItem) => {
    setEditorKind(kind);
    setEditing(record ?? null);
    form.resetFields();
    if (record) {
      form.setFieldsValue(record);
    } else {
      form.setFieldsValue({ status: 1, sort: 0 });
    }
    setRelationDrafts([]);
    setEditorOpen(true);
    if (kind === 'type' && record) {
      void loadRelations((record as SysDictType).id);
    }
  };

  const handleSubmit = async () => {
    let values: Record<string, unknown>;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    setSubmitting(true);
    try {
      const editingId = editing ? (editing as { id: number }).id : null;
      if (editorKind === 'module') {
        const body = { ...values };
        if (editingId) await api.put(`/system/dict/modules/${editingId}`, body);
        else await api.post('/system/dict/modules', body);
      } else if (editorKind === 'type') {
        if (!activeModule) throw new Error('请先选择字典模块');
        const body = { ...values, moduleId: activeModule.id };
        const saved = editingId
          ? await api.put<SysDictType>(`/system/dict/types/${editingId}`, body)
          : await api.post<SysDictType>('/system/dict/types', body);
        // 字典保存后落地关联（新建字典需等拿到 ID）
        if (saved?.id) await saveRelations(saved.id, !!editingId);
      } else {
        if (!activeType) throw new Error('请先选择字典');
        const body = { ...values, typeId: activeType.id };
        if (editingId) await api.put(`/system/dict/items/${editingId}`, body);
        else await api.post('/system/dict/items', body);
      }
      message.success('保存成功');
      setEditorOpen(false);
      setEditing(null);
      form.resetFields();
      await loadTree();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSubmitting(false);
    }
  };

  const handleDeleteModule = (module: SysDictModule) => {
    confirmDelete({
      name: module.name,
      resourceLabel: '字典模块',
      onOk: async () => {
        try {
          await api.del(`/system/dict/modules/${module.id}`);
          message.success('已删除');
          if (activeModuleId === module.id) {
            setActiveModuleId(null);
            setActiveTypeId(null);
          }
          await loadTree();
        } catch (e) {
          message.error(e instanceof Error ? e.message : '删除失败');
          throw e;
        }
      },
    });
  };

  const handleDeleteType = (type: SysDictType) => {
    confirmDelete({
      name: type.name,
      resourceLabel: '字典',
      onOk: async () => {
        try {
          await api.del(`/system/dict/types/${type.id}`);
          message.success('已删除');
          await loadTree();
        } catch (e) {
          message.error(e instanceof Error ? e.message : '删除失败');
          throw e;
        }
      },
    });
  };

  const handleDeleteItem = (item: SysDictItem) => {
    confirmDelete({
      name: item.label,
      resourceLabel: '字典项',
      onOk: async () => {
        try {
          await api.del(`/system/dict/items/${item.id}`);
          message.success('已删除');
          await loadTree();
        } catch (e) {
          message.error(e instanceof Error ? e.message : '删除失败');
          throw e;
        }
      },
    });
  };

  const itemColumns: ColumnsType<SysDictItem> = [
    {
      title: '字典项名称',
      dataIndex: 'label',
      key: 'label',
      ellipsis: true,
      render: (_, row) => <span className="font-medium text-gray-800">{row.label}</span>,
    },
    {
      title: '字典值',
      dataIndex: 'value',
      key: 'value',
      width: 200,
      ellipsis: true,
      render: (_, row) => <span className="text-gray-500">{row.value}</span>,
    },
    { title: '排序', dataIndex: 'sort', key: 'sort', width: 80 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (_, row) => (
        <Tag color={row.status === 0 ? 'default' : 'success'} className="m-0">
          {row.status === 0 ? '停用' : '启用'}
        </Tag>
      ),
    },
    {
      title: '备注',
      dataIndex: 'remark',
      key: 'remark',
      ellipsis: true,
      render: (_, row) => row.remark || '-',
    },
    {
      title: '操作',
      key: '_actions',
      width: 150,
      fixed: 'right',
      render: (_, row) => (
        <TableActions
          actions={[
            {
              key: 'edit',
              label: '编辑',
              icon: <EditOutlined />,
              perm: 'system.dict:update',
              onClick: () => openEditor('item', row),
            },
            {
              key: 'delete',
              label: '删除',
              icon: <DeleteOutlined />,
              danger: true,
              perm: 'system.dict:delete',
              onClick: () => handleDeleteItem(row),
            },
          ]}
          max={2}
        />
      ),
    },
  ];

  const typeTabs = (activeModule?.types ?? []).map((type) => ({
    key: String(type.id),
    label: (
      <span className="inline-flex items-center gap-1">
        {type.name}
        <span className="text-[11px] text-gray-400 tabular-nums">{type.items.length}</span>
      </span>
    ),
    children: null,
  }));

  return (
    <div className="space-y-4 min-w-0 max-w-full overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold text-gray-900 flex items-center gap-2 m-0">
            <BookOutlined className="text-[var(--ams-primary)]" />
            系统字典
          </h1>
          <p className="text-sm text-gray-500 mt-1 mb-0">
            按模块维护业务字典，字典项可单独新增、编辑与停用
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <Button icon={<ReloadOutlined />} onClick={() => void loadTree()} loading={loading}>
            刷新
          </Button>
          {canDo('create') && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => openEditor('module')}>
              新增模块
            </Button>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-[220px_1fr] gap-4 min-w-0">
        {/* 左侧：字典模块 */}
        <aside className="bg-white rounded-xl border border-[var(--ams-border)] p-2 min-w-0">
          <div className="px-2 py-1.5 text-xs font-medium text-gray-400">字典模块</div>
          {tree.length === 0 ? (
            <div className="px-2 py-6 text-sm text-gray-400 text-center">暂无模块</div>
          ) : (
            <ul className="m-0 p-0 list-none space-y-1">
              {tree.map((module) => {
                const active = module.id === activeModuleId;
                return (
                  <li key={module.id}>
                    <button
                      type="button"
                      onClick={() => handleSelectModule(module)}
                      aria-current={active ? 'true' : undefined}
                      className={`w-full flex items-center justify-between gap-2 rounded-lg px-3 py-2 text-sm text-left transition-colors ${
                        active
                          ? 'bg-blue-50 text-[var(--ams-primary)] font-medium'
                          : 'text-gray-600 hover:bg-gray-50'
                      }`}
                    >
                      <span className="inline-flex items-center gap-2 min-w-0">
                        <TagsOutlined className="shrink-0 opacity-70" />
                        <span className="truncate">{module.name}</span>
                      </span>
                      <span className="text-[11px] text-gray-400 tabular-nums shrink-0">
                        {module.types.length}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </aside>

        {/* 右侧：模块下的字典 Tab 与字典项 */}
        <section className="bg-white rounded-xl border border-[var(--ams-border)] p-3 sm:p-4 min-w-0 overflow-hidden">
          {!activeModule ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择左侧字典模块" />
          ) : (
            <>
              <div className="flex flex-wrap items-start justify-between gap-3 mb-2">
                <div className="min-w-0">
                  <div className="text-base font-semibold text-gray-800 truncate">
                    {activeModule.name}
                  </div>
                  <div className="text-xs text-gray-400 mt-0.5 truncate">
                    模块编码：{activeModule.code}
                  </div>
                </div>
                <Space wrap size={[8, 8]}>
                  {canDo('update') && (
                    <Button
                      size="small"
                      icon={<EditOutlined />}
                      onClick={() => openEditor('module', activeModule)}
                    >
                      编辑模块
                    </Button>
                  )}
                  {canDo('delete') && (
                    <Button
                      size="small"
                      danger
                      icon={<DeleteOutlined />}
                      onClick={() => handleDeleteModule(activeModule)}
                    >
                      删除模块
                    </Button>
                  )}
                  {canDo('create') && (
                    <Button
                      size="small"
                      type="primary"
                      icon={<PlusOutlined />}
                      onClick={() => openEditor('type')}
                    >
                      新增字典
                    </Button>
                  )}
                </Space>
              </div>

              {activeModule.types.length === 0 ? (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="该模块暂无字典，点击「新增字典」创建"
                />
              ) : (
                <>
                  <Tabs
                    activeKey={activeType ? String(activeType.id) : undefined}
                    items={typeTabs}
                    onChange={(key) => setActiveTypeId(Number(key))}
                  />

                  {activeType && (
                    <div className="mt-2 min-w-0">
                      <div className="flex flex-wrap items-center justify-between gap-3 mb-3">
                        <div className="flex items-center gap-2 min-w-0">
                          <span className="text-sm font-medium text-gray-700 truncate">
                            {activeType.name}
                          </span>
                          <span className="text-xs text-gray-400 truncate">
                            编码：{activeType.code}
                          </span>
                          {activeType.status === 0 && (
                            <Tag color="default" className="m-0">
                              已停用
                            </Tag>
                          )}
                        </div>
                        <Space wrap size={[8, 8]}>
                          {canDo('update') && (
                            <Tooltip title="编辑当前字典">
                              <Button
                                size="small"
                                icon={<EditOutlined />}
                                onClick={() => openEditor('type', activeType)}
                              >
                                编辑字典
                              </Button>
                            </Tooltip>
                          )}
                          {canDo('delete') && (
                            <Button
                              size="small"
                              danger
                              icon={<DeleteOutlined />}
                              onClick={() => handleDeleteType(activeType)}
                            >
                              删除字典
                            </Button>
                          )}
                          {canDo('create') && (
                            <Button
                              size="small"
                              type="primary"
                              icon={<PlusOutlined />}
                              onClick={() => openEditor('item')}
                            >
                              新增字典项
                            </Button>
                          )}
                        </Space>
                      </div>

                      {activeRelations.length > 0 && (
                        <div className="mb-3 rounded border border-blue-100 bg-blue-50/40 p-2 text-xs">
                          <div className="text-gray-400 mb-1">
                            关联字典值（带字典项的为级联规则，决定子字典下拉可见项）
                          </div>
                          <div className="space-y-1">
                            {activeRelations.map((group) => (
                              <div
                                key={`${group.sourceItemId ?? 'all'}-${group.targetTypeId}`}
                                className="flex flex-wrap items-center gap-1"
                              >
                                <span className="text-gray-500 shrink-0">
                                  {group.sourceItemLabel ?? '整本字典'} → {group.targetTypeName}：
                                </span>
                                {group.items.map((item) => (
                                  <Tag key={item.value} className="m-0">
                                    {item.label}
                                  </Tag>
                                ))}
                              </div>
                            ))}
                          </div>
                        </div>
                      )}

                      <div className="ams-table-wrap">
                        <Table
                          rowKey="id"
                          size="small"
                          loading={loading}
                          columns={itemColumns}
                          dataSource={activeType.items}
                          pagination={false}
                          scroll={{ x: 720 }}
                        />
                      </div>
                    </div>
                  )}
                </>
              )}
            </>
          )}
        </section>
      </div>

      <Modal
        title={EDITOR_TITLE[editorKind][editing ? 'edit' : 'create']}
        open={editorOpen}
        onOk={() => void handleSubmit()}
        onCancel={() => {
          setEditorOpen(false);
          setEditing(null);
        }}
        confirmLoading={submitting}
        destroyOnHidden
        centered
        width={Math.min(
          editorKind === 'type' ? 780 : 520,
          typeof window !== 'undefined' ? window.innerWidth - 32 : 780,
        )}
      >
        <Form form={form} layout="vertical" className="mt-2">
          {editorKind !== 'item' && (
            <Form.Item
              name="code"
              label={editorKind === 'module' ? '模块编码' : '字典编码'}
              rules={[{ required: true, message: '请输入编码' }]}
            >
              <Input maxLength={64} placeholder="如 asset_type，建议使用英文/下划线" />
            </Form.Item>
          )}
          <Form.Item
            name="name"
            label={
              editorKind === 'item'
                ? '字典项名称'
                : editorKind === 'module'
                  ? '模块名称'
                  : '字典名称'
            }
            rules={[{ required: true, message: '请输入名称' }]}
          >
            <Input maxLength={100} placeholder="请输入名称" />
          </Form.Item>
          {editorKind === 'item' && (
            <Form.Item name="value" label="字典值" extra="留空时默认与名称一致">
              <Input maxLength={64} placeholder="可选，如 low_rent_housing" />
            </Form.Item>
          )}
          <Form.Item name="sort" label="排序" initialValue={0}>
            <InputNumber className="w-full" min={0} precision={0} />
          </Form.Item>
          <Form.Item name="status" label="状态" initialValue={1}>
            <Select options={STATUS_OPTIONS} />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={500} placeholder="可选" />
          </Form.Item>

          {editorKind === 'type' && (
            <div className="border-t border-[var(--ams-border)] pt-3 mt-1">
              <div className="flex items-start justify-between gap-2 mb-2">
                <div className="min-w-0">
                  <div className="text-sm font-medium text-gray-700">关联字典值 / 级联规则</div>
                  <div className="text-xs text-gray-400 mt-0.5">
                    选择「字典项」时表示级联规则：父字典该项决定子字典下拉的可见项；
                    不选「字典项」则表示整本字典挂接，仅用于关联查询
                  </div>
                </div>
                <Button
                  size="small"
                  icon={<PlusOutlined />}
                  onClick={() =>
                    setRelationDrafts((prev) => [
                      ...prev,
                      { sourceItemId: undefined, targetTypeId: undefined, itemIds: [] },
                    ])
                  }
                >
                  添加关联
                </Button>
              </div>

              {editorKind === 'type' && !editing && (
                <div className="text-xs text-amber-600 bg-amber-50 border border-amber-100 rounded px-2 py-1.5 mb-2">
                  新建字典尚无字典项，保存后重新打开即可按字典项配置级联规则
                </div>
              )}

              {relationLoading ? (
                <div className="py-4 text-center text-xs text-gray-400">加载中…</div>
              ) : relationDrafts.length === 0 ? (
                <div className="text-xs text-gray-400 py-3 text-center border border-dashed border-gray-200 rounded">
                  暂未关联其他字典
                </div>
              ) : (
                <div className="space-y-2">
                  {relationDrafts.map((draft, index) => {
                    const options = itemsOfType(draft.targetTypeId);
                    return (
                      <div
                        key={`${index}-${draft.sourceItemId ?? 'all'}-${draft.targetTypeId ?? 'new'}`}
                        className="rounded border border-gray-100 bg-gray-50/60 p-2"
                      >
                        <div className="flex items-start gap-2">
                          <Select
                            className="!w-[26%] shrink-0"
                            value={draft.sourceItemId}
                            allowClear
                            disabled={!editing}
                            showSearch
                            optionFilterProp="label"
                            placeholder="整本字典"
                            options={(editing as SysDictType | null)?.items?.map((item) => ({
                              value: item.id,
                              label: item.label,
                            }))}
                            onChange={(v?: number) =>
                              setRelationDrafts((prev) =>
                                prev.map((d, i) => (i === index ? { ...d, sourceItemId: v } : d)),
                              )
                            }
                          />
                          <Select
                            className="!w-[32%] shrink-0"
                            value={draft.targetTypeId}
                            showSearch
                            optionFilterProp="label"
                            placeholder="选择被关联字典"
                            options={allTypes
                              .filter((t) => t.id !== (editing as SysDictType | null)?.id)
                              .map((t) => ({ value: t.id, label: t.fullName }))}
                            onChange={(v: number) =>
                              setRelationDrafts((prev) =>
                                prev.map((d, i) =>
                                  i === index ? { ...d, targetTypeId: v, itemIds: [] } : d,
                                ),
                              )
                            }
                          />
                          <Select
                            className="flex-1 min-w-0"
                            mode="multiple"
                            allowClear
                            disabled={!draft.targetTypeId}
                            value={draft.itemIds}
                            placeholder={draft.targetTypeId ? '选择字典值' : '请先选择字典'}
                            options={options.map((item) => ({
                              value: item.id,
                              label: item.label,
                            }))}
                            onChange={(vals: number[]) =>
                              setRelationDrafts((prev) =>
                                prev.map((d, i) => (i === index ? { ...d, itemIds: vals } : d)),
                              )
                            }
                          />
                          <Button
                            size="small"
                            danger
                            type="text"
                            icon={<DeleteOutlined />}
                            aria-label="移除关联"
                            onClick={() =>
                              setRelationDrafts((prev) => prev.filter((_, i) => i !== index))
                            }
                          />
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}
        </Form>
      </Modal>
    </div>
  );
}
