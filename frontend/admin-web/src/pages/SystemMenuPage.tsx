import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Radio,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  DeleteOutlined,
  EditOutlined,
  ExclamationCircleOutlined,
  MenuOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { api } from '@/lib/api';
import { confirmDelete, confirmProceed } from '@/lib/confirm';
import { ICON_NAMES, getIconByName } from '@/lib/menuIcons';
import { isRegisteredRoute } from '@/lib/routeRegistry';
import { useMenu, type ApiMenuNode } from '@/lib/menu';
import { PermissionGuard } from '@/lib/perm';
import { TableActions } from '@/components/TableActions';

// ============================================================================
// 菜单管理（设计 3.3）：树 + 抽屉 + 校验
//
// 与旧 ResourcePage 的区别：旧页面把菜单当普通列表（新增时无法选上级目录、
// 无图标预览、无路由注册表交叉校验、删除无子节点/授权引用保护）。这里全部补齐。
// ============================================================================

/** 两级模型：只有目录与菜单，操作（view/create/...）来自 PermissionAction，不是菜单节点。 */
type MenuType = 'dir' | 'menu';

interface MenuFormValues {
  menuType: MenuType;
  parentId?: number;
  name: string;
  code?: string;
  path?: string;
  icon?: string | null;
  sort?: number;
  status?: number;
}

/** 抽屉状态：新增（可预置类型/上级）或编辑。 */
interface EditorState {
  open: boolean;
  editing: ApiMenuNode | null;
  presetType: MenuType;
  presetParentId?: number;
}

const DIR = 'dir';
const MENU = 'menu';

const TYPE_LABEL: Record<string, string> = { dir: '目录', menu: '菜单' };

const STATUS_OPTIONS = [
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
];

/** 图标选择器选项：名称 + 预览 */
const ICON_OPTIONS = ICON_NAMES.map((name) => ({
  value: name,
  label: (
    <span className="inline-flex items-center gap-2">
      <span className="text-base leading-none">{getIconByName(name)}</span>
      <span className="text-gray-600">{name}</span>
    </span>
  ),
}));

/** 扁平化整棵树，便于查编码重复、找上级目录。 */
const flatten = (tree: ApiMenuNode[]): ApiMenuNode[] =>
  tree.flatMap((node) => [node, ...flatten(node.children ?? [])]);

export function SystemMenuPage() {
  const { reload: reloadSidebar } = useMenu();

  const [tree, setTree] = useState<ApiMenuNode[]>([]);
  const [loading, setLoading] = useState(false);
  const [expandedKeys, setExpandedKeys] = useState<number[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [editor, setEditor] = useState<EditorState>({
    open: false,
    editing: null,
    presetType: DIR,
  });
  const [form] = Form.useForm<MenuFormValues>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const list = await api.get<ApiMenuNode[]>('/system/menus/all');
      setTree(list);
      // 默认全展开：管理树的价值就是一眼看全，折叠态反而需要逐个点开
      setExpandedKeys(flatten(list).filter((n) => n.menuType === DIR).map((n) => n.id));
    } catch (e) {
      setTree([]);
      message.error(e instanceof Error ? e.message : '加载菜单失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const allNodes = useMemo(() => flatten(tree), [tree]);
  const dirNodes = useMemo(() => allNodes.filter((n) => n.menuType === DIR), [allNodes]);

  /**
   * 「随目录停用」的节点：其上级目录已停用。
   *
   * <p>设计 3.3：停用目录 = 隐藏整组，**不论子菜单自身状态**。这些子项在管理树里仍要
   * 只读可见（否则停用目录后就再也点不到子菜单去改），但必须标明它当前并不生效。
   */
  const inheritedDisabledIds = useMemo(() => {
    const ids = new Set<number>();
    for (const dir of dirNodes) {
      if (dir.status === 0) {
        for (const child of dir.children ?? []) ids.add(child.id);
      }
    }
    return ids;
  }, [dirNodes]);

  const dirOptions = useMemo(
    () =>
      dirNodes.map((dir) => ({
        value: dir.id,
        label: dir.status === 0 ? `${dir.name}（已停用）` : dir.name,
      })),
    [dirNodes],
  );

  const watchedType = Form.useWatch('menuType', form);
  const watchedPath = Form.useWatch('path', form);
  const watchedIcon = Form.useWatch('icon', form);

  /** 当前填写的路由是否未在注册表中（保存时会二次确认，而不是静默丢弃）。 */
  const pathUnregistered =
    watchedType === MENU && !!watchedPath?.trim() && !isRegisteredRoute(watchedPath.trim());

  const openCreate = (type: MenuType, parentId?: number) => {
    form.resetFields();
    form.setFieldsValue({
      menuType: type,
      parentId,
      status: 1,
      sort: 0,
      icon: type === DIR ? 'folder' : null,
    });
    setEditor({ open: true, editing: null, presetType: type, presetParentId: parentId });
  };

  const openEdit = (record: ApiMenuNode) => {
    form.resetFields();
    form.setFieldsValue({
      menuType: record.menuType === DIR ? DIR : MENU,
      parentId: record.parentId ?? undefined,
      name: record.name,
      code: record.code ?? undefined,
      path: record.path ?? undefined,
      icon: record.icon ?? null,
      sort: record.sort ?? 0,
      status: record.status ?? 1,
    });
    setEditor({
      open: true,
      editing: record,
      presetType: record.menuType === DIR ? DIR : MENU,
      presetParentId: record.parentId ?? undefined,
    });
  };

  /** 类型切换时清掉互斥字段，避免「目录带着 path」这类脏提交被后端拒绝。 */
  const handleTypeChange = (next: MenuType) => {
    form.setFieldsValue(
      next === DIR
        ? { path: undefined, parentId: undefined }
        : { parentId: editor.presetParentId },
    );
  };

  const handleSubmit = async () => {
    let values: MenuFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const type: MenuType = editor.editing
      ? editor.editing.menuType === DIR
        ? DIR
        : MENU
      : values.menuType;
    const path = values.path?.trim();
    const code = values.code?.trim();

    // 编码是权限命名空间，客户端先查一遍重复（后端仍会校验，这里只是早失败、提示更明确）
    if (!editor.editing && code) {
      const duplicated = allNodes.some((n) => n.code === code);
      if (duplicated) {
        message.error(`编码「${code}」已存在，请更换`);
        return;
      }
    }

    // 设计 3.3：未注册路径要「保存时二次确认」，而不是渲染阶段静默丢弃
    if (type === MENU && path && !isRegisteredRoute(path)) {
      const proceed = await confirmProceed({
        title: '路由未在前端注册表中',
        okText: '仍然保存',
        content: `「${path}」不在前端路由注册表中，保存后该菜单不会出现在侧边栏（会被当作脏数据忽略）。确定继续保存吗？`,
      });
      if (!proceed) return;
    }

    const payload = {
      name: values.name?.trim(),
      code,
      menuType: type,
      // 目录禁止配置路由与上级，菜单两者必填（与后端校验口径一致）
      path: type === MENU ? path : null,
      parentId: type === MENU ? values.parentId : null,
      icon: values.icon || null,
      sort: values.sort ?? 0,
      status: values.status ?? 1,
    };

    setSubmitting(true);
    try {
      if (editor.editing) await api.put(`/system/menus/${editor.editing.id}`, payload);
      else await api.post('/system/menus', payload);
      message.success('保存成功');
      setEditor((prev) => ({ ...prev, open: false, editing: null }));
      await load();
      // 侧边栏数据源同为本接口的可见子树：改名/启停后必须同步，否则顶栏与侧栏会不一致
      await reloadSidebar();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = (record: ApiMenuNode) => {
    confirmDelete({
      name: record.name,
      resourceLabel: TYPE_LABEL[record.menuType] ?? '菜单',
      onOk: async () => {
        try {
          await api.del(`/system/menus/${record.id}`);
          message.success('已删除');
          await load();
          await reloadSidebar();
        } catch (e) {
          // 「存在下级菜单」与「已被角色授权引用」中，后者前端无从判断（要查 role_permission），
          // 所以两条保护统一交给后端判定，这里只把后端的原因原样透出 ——
          // 比前端提前 disable 一个说不清原因的删除按钮更有用
          message.error(e instanceof Error ? e.message : '删除失败');
          throw e;
        }
      },
    });
  };

  const columns: ColumnsType<ApiMenuNode> = [
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
      ellipsis: true,
      // 图标直接渲染在名称前（即侧边栏的真实样子），而不是单开一列：
      // 两列并排会把同一个图标显示两遍，而「按侧边栏的样子看」才能一眼发现配错图标
      render: (_, row) => (
        <span className="inline-flex items-center gap-2 min-w-0">
          <span className="text-base leading-none shrink-0 text-gray-500">
            {getIconByName(row.icon) ?? <MenuOutlined className="text-gray-300" />}
          </span>
          <span className="font-medium text-gray-800 truncate">{row.name}</span>
          {inheritedDisabledIds.has(row.id) && (
            <Tooltip title="上级目录已停用，整组不会出现在侧边栏">
              <Tag color="default" className="m-0">
                随目录停用
              </Tag>
            </Tooltip>
          )}
        </span>
      ),
    },
    {
      title: '编码',
      dataIndex: 'code',
      key: 'code',
      width: 190,
      ellipsis: true,
      render: (_, row) => (
        <span className="font-mono text-xs text-gray-500">{row.code || '-'}</span>
      ),
    },
    {
      title: '路由',
      dataIndex: 'path',
      key: 'path',
      width: 220,
      ellipsis: true,
      render: (_, row) => {
        if (row.menuType === DIR) return <span className="text-gray-300">-</span>;
        if (!row.path) return <span className="text-gray-300">-</span>;
        if (isRegisteredRoute(row.path)) {
          return <span className="font-mono text-xs text-gray-600">{row.path}</span>;
        }
        return (
          <Tooltip title="该路由未在前端路由注册表中，不会出现在侧边栏">
            <span className="font-mono text-xs text-amber-600 inline-flex items-center gap-1">
              <ExclamationCircleOutlined />
              {row.path}
            </span>
          </Tooltip>
        );
      },
    },
    {
      title: '类型',
      dataIndex: 'menuType',
      key: 'menuType',
      width: 80,
      render: (_, row) => TYPE_LABEL[row.menuType] ?? row.menuType,
    },
    { title: '排序', dataIndex: 'sort', key: 'sort', width: 70 },
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
      title: '操作',
      key: '_actions',
      width: 140,
      fixed: 'right',
      render: (_, row) => (
        <TableActions
          actions={[
            {
              key: 'edit',
              label: '编辑',
              icon: <EditOutlined />,
              perm: 'system.menu:update',
              onClick: () => openEdit(row),
            },
            {
              key: 'delete',
              label: '删除',
              icon: <DeleteOutlined />,
              danger: true,
              perm: 'system.menu:delete',
              onClick: () => handleDelete(row),
            },
          ]}
          more={
            row.menuType === DIR
              ? [
                  {
                    key: 'add-child',
                    label: '新增子菜单',
                    icon: <PlusOutlined />,
                    perm: 'system.menu:create',
                    onClick: () => openCreate(MENU, row.id),
                  },
                ]
              : undefined
          }
        />
      ),
    },
  ];

  const isEditingDir = editor.editing?.menuType === DIR;
  const effectiveType: MenuType = isEditingDir ? DIR : watchedType ?? editor.presetType;

  return (
    <div className="space-y-4 min-w-0 max-w-full overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold text-gray-900 flex items-center gap-2 m-0">
            <MenuOutlined className="text-[var(--ams-primary)]" />
            菜单管理
          </h1>
          <p className="text-sm text-gray-500 mt-1 mb-0">
            维护侧边栏的目录与菜单。数据库决定「显示哪些、顺序、命名」，前端路由注册表决定页面是否存在。
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
            刷新
          </Button>
          {/* 与 ResourcePage 统一为「无权即不渲染」（设计 6.2 的显隐口径） */}
          <PermissionGuard perm="system.menu:create">
            <Button icon={<PlusOutlined />} onClick={() => openCreate(DIR)}>
              新增目录
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => openCreate(MENU)}>
              新增菜单
            </Button>
          </PermissionGuard>
        </div>
      </div>

      <div className="bg-white rounded-xl border border-[var(--ams-border)] p-3 sm:p-4 min-w-0 overflow-hidden">
        {!loading && tree.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无菜单数据" />
        ) : (
          <div className="ams-table-wrap">
            <Table
              rowKey="id"
              size="small"
              loading={loading}
              columns={columns}
              dataSource={tree}
              pagination={false}
              scroll={{ x: 1080 }}
              expandable={{
                expandedRowKeys: expandedKeys,
                onExpandedRowsChange: (keys) => setExpandedKeys(keys as number[]),
              }}
            />
          </div>
        )}
      </div>

      <DrawerShell
        title={
          editor.editing
            ? `编辑${TYPE_LABEL[effectiveType] ?? '菜单'} · ${editor.editing.name}`
            : `新增${TYPE_LABEL[effectiveType] ?? '菜单'}`
        }
        open={editor.open}
        onClose={() => setEditor((prev) => ({ ...prev, open: false, editing: null }))}
        submitting={submitting}
        onSave={() => void handleSubmit()}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="menuType" label="类型" rules={[{ required: true }]}>
            <Radio.Group
              disabled={!!editor.editing}
              onChange={(e) => handleTypeChange(e.target.value as MenuType)}
              options={[
                { value: DIR, label: '目录' },
                { value: MENU, label: '菜单' },
              ]}
            />
          </Form.Item>
          {editor.editing && (
            <div className="text-xs text-gray-400 -mt-3 mb-4">
              类型与编码创建后不可修改（编码是权限命名空间，改名会让既有授权指向不存在的权限点）
            </div>
          )}

          {effectiveType === MENU && (
            <Form.Item
              name="parentId"
              label="上级目录"
              rules={[{ required: true, message: '菜单必须选择上级目录' }]}
            >
              <Select
                showSearch
                optionFilterProp="label"
                placeholder="请选择上级目录"
                options={dirOptions}
              />
            </Form.Item>
          )}

          <Form.Item
            name="name"
            label="名称"
            rules={[{ required: true, message: '请输入名称' }]}
          >
            <Input maxLength={50} placeholder={effectiveType === DIR ? '如 资产台账' : '如 资产台账'} />
          </Form.Item>

          <Form.Item
            name="code"
            label="编码"
            tooltip="权限命名空间，格式建议 domain.name（如 asset.ledger）。创建后不可修改。"
            rules={[
              { required: !editor.editing, message: '请输入编码' },
              {
                pattern: /^[a-z][a-zA-Z0-9_.]*$/,
                message: '以小写字母开头，仅可含字母、数字、下划线与点',
              },
            ]}
          >
            <Input
              disabled={!!editor.editing}
              maxLength={64}
              placeholder="如 asset.ledger"
              className="font-mono"
            />
          </Form.Item>

          {effectiveType === MENU && (
            <Form.Item
              name="path"
              label="路由"
              rules={[{ required: true, message: '菜单必须配置路由' }]}
              extra={
                pathUnregistered ? (
                  <span className="text-amber-600">
                    该路由未在前端路由注册表中，保存后不会出现在侧边栏
                  </span>
                ) : (
                  '需与前端已注册的路由一致，如 /assets'
                )
              }
            >
              <Input maxLength={200} placeholder="如 /assets" className="font-mono" />
            </Form.Item>
          )}

          <Form.Item name="icon" label="图标" tooltip="取自具名图标目录；留空时用路由注册表的默认图标">
            <Select
              allowClear
              showSearch
              optionFilterProp="value"
              placeholder="选择图标"
              options={ICON_OPTIONS}
              optionLabelProp="value"
            />
          </Form.Item>
          <div className="text-xs text-gray-400 -mt-3 mb-4 flex items-center gap-2">
            预览：
            <span className="text-lg leading-none text-gray-600">
              {getIconByName(watchedIcon) ?? <MenuOutlined className="text-gray-300" />}
            </span>
          </div>

          <div className="grid grid-cols-2 gap-x-4">
            <Form.Item name="sort" label="排序" tooltip="同级内升序排列">
              <InputNumber className="w-full" min={0} precision={0} />
            </Form.Item>
            <Form.Item
              name="status"
              label="状态"
              tooltip={effectiveType === DIR ? '停用目录 = 隐藏整组及其子菜单' : '停用后该菜单从侧边栏隐藏，权限点保留'}
            >
              <Select options={STATUS_OPTIONS} />
            </Form.Item>
          </div>
        </Form>
      </DrawerShell>
    </div>
  );
}

/** 抽屉外壳：统一宽度、保存按钮与提交态。 */
function DrawerShell({
  title,
  open,
  onClose,
  submitting,
  onSave,
  children,
}: {
  title: string;
  open: boolean;
  onClose: () => void;
  submitting: boolean;
  onSave: () => void;
  children: React.ReactNode;
}) {
  return (
    <Drawer
      title={title}
      open={open}
      onClose={onClose}
      destroyOnHidden
      // antd v6 起 `width` 已废弃，`size` 接受数字（等价且不再告警）
      size={Math.min(520, typeof window !== 'undefined' ? window.innerWidth - 32 : 520)}
      extra={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={submitting} onClick={onSave}>
            保存
          </Button>
        </Space>
      }
    >
      {children}
    </Drawer>
  );
}
