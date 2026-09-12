import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Checkbox,
  Empty,
  Form,
  Input,
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
  CheckCircleFilled,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SafetyOutlined,
  TeamOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { api, type PageResult } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { confirmDelete, confirmProceed } from '@/lib/confirm';
import { useMenu, type ApiMenuNode } from '@/lib/menu';
import { usePerm } from '@/lib/perm';
import {
  buildCompanyIndex,
  buildPermissionItems,
  collectInheritedExcluded,
  dirActionState,
  underExcludedAncestor,
} from '@/lib/roleDraft';
import { cn } from '@/lib/utils';

// ============================================================================
// 角色权限（设计 4.4）：左角色列表 + 右三 Tab（功能权限 / 数据权限 / 成员）
//
// 保存模型：**每个角色一个保存按钮，一次提交「功能权限」与「数据范围」两个请求**；
// 有未保存改动时切换角色或页签需二次确认；两个写入各自报错，失败项要能分辨。
// ============================================================================

interface Role {
  id: number;
  code: string;
  name: string;
  dataScope: string;
  status: number;
}

interface MatrixGranted {
  menuId: number;
  actions: string[];
}

interface RolePermissionMatrix {
  roleId: number;
  menus: ApiMenuNode[];
  granted: MatrixGranted[];
  /** 固定动作词表（顺序即矩阵列顺序，前端不得硬编码） */
  actions: string[];
  /** 已强制校验的 `menuCode:action`（据此标记「未生效」） */
  enforced: string[];
}

interface CompanyNode {
  id: number;
  name: string;
  shortName?: string | null;
  parentId?: number | null;
}

interface RoleDataScopeView {
  roleId: number;
  dataScope: string;
  /** 该取值实际生效的可见范围描述 */
  effectiveScope: string;
  /** 本期未实现、已退化的取值（如 dept/project/self 退化为公司级） */
  degradedScopes: string[];
  excludedCompanyIds: number[];
  companies: CompanyNode[];
  /** 不可排除的公司（调用者自己所属公司） */
  lockedCompanyIds: number[];
}

interface MemberUser {
  id: number;
  username: string;
  name: string;
  phone?: string;
  departmentName?: string;
  companyName?: string;
  status: number;
}

/** 动作的中文名。词表本身来自接口，这里只做展示翻译，缺译时回落英文码。 */
const ACTION_LABEL: Record<string, string> = {
  view: '查看',
  create: '新增',
  update: '修改',
  delete: '删除',
  export: '导出',
  import: '导入',
  approve: '审批',
  audit: '审核',
  assign: '授权',
};

const actionLabel = (action: string) => ACTION_LABEL[action] ?? action;

const DATA_SCOPE_LABEL: Record<string, string> = {
  all: '全部',
  company: '公司',
  dept: '部门',
  project: '项目',
  self: '本人',
};

const DATA_SCOPE_OPTIONS = Object.entries(DATA_SCOPE_LABEL).map(([value, label]) => ({
  value,
  label,
}));

/** 改动类型：功能权限 / 数据范围 —— 保存与提示都要按这两项分别归因。 */
type DirtyKind = 'permissions' | 'dataScope';

const flatten = (tree: ApiMenuNode[]): ApiMenuNode[] =>
  tree.flatMap((node) => [node, ...flatten(node.children ?? [])]);

export function SystemRolePage() {
  const can = usePerm();
  const { refreshUser } = useAuth();
  const { reload: reloadSidebar } = useMenu();

  const [roles, setRoles] = useState<Role[]>([]);
  const [rolesLoading, setRolesLoading] = useState(false);
  const [activeRoleId, setActiveRoleId] = useState<number | null>(null);

  const [matrix, setMatrix] = useState<RolePermissionMatrix | null>(null);
  const [matrixLoading, setMatrixLoading] = useState(false);
  const [scope, setScope] = useState<RoleDataScopeView | null>(null);
  const [scopeLoading, setScopeLoading] = useState(false);
  const [members, setMembers] = useState<PageResult<MemberUser> | null>(null);
  const [membersLoading, setMembersLoading] = useState(false);
  const [memberPage, setMemberPage] = useState(1);

  /** 草稿：`menuId → 已勾动作`。只含非空项，空即表示未授权。 */
  const [draftPerms, setDraftPerms] = useState<Record<number, string[]>>({});
  /** 草稿：显式排除的公司 id（原值，不含展开子树）。 */
  const [draftExcludes, setDraftExcludes] = useState<number[]>([]);
  const [dirty, setDirty] = useState<Record<DirtyKind, boolean>>({
    permissions: false,
    dataScope: false,
  });
  const [saving, setSaving] = useState(false);
  const [activeTab, setActiveTab] = useState('permissions');

  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<Role | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<Partial<Role>>();

  const canAssign = can('system.role:assign');
  const canDelete = can('system.role:delete');

  const hasDirty = dirty.permissions || dirty.dataScope;

  // ---- 数据加载 ----

  const loadRoles = useCallback(async () => {
    setRolesLoading(true);
    try {
      const list = await api.get<Role[]>('/system/roles');
      setRoles(list);
    } catch (e) {
      setRoles([]);
      message.error(e instanceof Error ? e.message : '加载角色失败');
    } finally {
      setRolesLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadRoles();
  }, [loadRoles]);

  useEffect(() => {
    if (roles.length === 0) {
      setActiveRoleId(null);
      return;
    }
    if (!roles.some((r) => r.id === activeRoleId)) setActiveRoleId(roles[0].id);
  }, [roles, activeRoleId]);

  const loadRoleDetail = useCallback(async (roleId: number) => {
    setMatrixLoading(true);
    setScopeLoading(true);
    setMembersLoading(true);
    try {
      // 矩阵与数据范围是一屏内的两个 Tab，一起取，避免切 Tab 才转圈
      const [m, s] = await Promise.all([
        api.get<RolePermissionMatrix>(`/system/roles/${roleId}/permissions`),
        api.get<RoleDataScopeView>(`/system/roles/${roleId}/data-scope`),
      ]);
      setMatrix(m);
      const nextPerms: Record<number, string[]> = {};
      for (const g of m.granted ?? []) {
        if (g.menuId == null || !g.actions?.length) continue;
        nextPerms[g.menuId] = [...g.actions];
      }
      setDraftPerms(nextPerms);
      setScope(s);
      setDraftExcludes([...(s.excludedCompanyIds ?? [])]);
      setDirty({ permissions: false, dataScope: false });
    } catch (e) {
      setMatrix(null);
      setScope(null);
      setDraftPerms({});
      setDraftExcludes([]);
      message.error(e instanceof Error ? e.message : '加载角色权限失败');
    } finally {
      setMatrixLoading(false);
      setScopeLoading(false);
    }
  }, []);

  useEffect(() => {
    if (activeRoleId == null) {
      setMatrix(null);
      setScope(null);
      setMembers(null);
      return;
    }
    void loadRoleDetail(activeRoleId);
  }, [activeRoleId, loadRoleDetail]);

  const loadMembers = useCallback(async (roleId: number, page: number) => {
    setMembersLoading(true);
    try {
      const data = await api.get<PageResult<MemberUser>>(
        `/system/users?roleId=${roleId}&page=${page}&pageSize=10`,
      );
      setMembers(data);
    } catch (e) {
      setMembers(null);
      message.error(e instanceof Error ? e.message : '加载角色成员失败');
    } finally {
      setMembersLoading(false);
    }
  }, []);

  useEffect(() => {
    if (activeRoleId == null) return;
    setMemberPage(1);
    void loadMembers(activeRoleId, 1);
  }, [activeRoleId, loadMembers]);

  // ---- 功能权限草稿 ----

  const actionsOf = useCallback(
    (menuId: number): string[] => draftPerms[menuId] ?? [],
    [draftPerms],
  );

  const setMenuActions = useCallback((menuId: number, actions: string[]) => {
    setDraftPerms((prev) => {
      const next = { ...prev };
      if (actions.length === 0) delete next[menuId];
      else next[menuId] = actions;
      return next;
    });
    setDirty((prev) => (prev.permissions ? prev : { ...prev, permissions: true }));
  }, []);

  const toggleMenuAction = (menuId: number, action: string, checked: boolean) => {
    const current = new Set(actionsOf(menuId));
    if (checked) current.add(action);
    else current.delete(action);
    setMenuActions(menuId, [...current]);
  };

  /** 目录行：对该动作在其全部子菜单上全选 / 全不选。 */
  const toggleDirAction = (dir: ApiMenuNode, action: string, checked: boolean) => {
    const children = dir.children ?? [];
    setDraftPerms((prev) => {
      const next = { ...prev };
      for (const child of children) {
        const actions = new Set(next[child.id] ?? []);
        if (checked) actions.add(action);
        else actions.delete(action);
        if (actions.size === 0) delete next[child.id];
        else next[child.id] = [...actions];
      }
      return next;
    });
    setDirty((prev) => (prev.permissions ? prev : { ...prev, permissions: true }));
  };

  // ---- 数据范围草稿 ----

  const companyIndex = useMemo(
    () => buildCompanyIndex(scope?.companies ?? []),
    [scope],
  );

  /** 被显式排除的公司 → 其全部下级（继承排除）。 */
  const inheritedExcluded = useMemo(
    () =>
      collectInheritedExcluded(companyIndex.roots, companyIndex.childrenOf, draftExcludes),
    [companyIndex, draftExcludes],
  );

  const toggleExclude = (companyId: number, checked: boolean) => {
    setDraftExcludes((prev) =>
      checked ? [...new Set([...prev, companyId])] : prev.filter((id) => id !== companyId),
    );
    setDirty((prev) => (prev.dataScope ? prev : { ...prev, dataScope: true }));
  };

  // ---- 切换守卫（有未保存改动时二次确认） ----

  const guard = useCallback(
    async (action: string): Promise<boolean> => {
      if (!hasDirty) return true;
      return confirmProceed({
        title: '存在未保存的改动',
        content: `当前角色有未保存的改动（${[
          dirty.permissions ? '功能权限' : null,
          dirty.dataScope ? '数据范围' : null,
        ]
          .filter(Boolean)
          .join('、')}）。${action}将丢弃这些改动，确定继续吗？`,
        okText: '丢弃并继续',
        okType: 'primary',
      });
    },
    [dirty.dataScope, dirty.permissions, hasDirty],
  );

  const handleSelectRole = async (role: Role) => {
    if (role.id === activeRoleId) return;
    if (!(await guard('切换角色'))) return;
    setActiveRoleId(role.id);
  };

  const handleTabChange = async (key: string) => {
    if (key === activeTab) return;
    if (!(await guard('切换页签'))) return;
    setActiveTab(key);
  };

  // ---- 保存：一次提交两个请求，失败项分别归因 ----

  const handleSave = async () => {
    if (activeRoleId == null) return;
    const items = buildPermissionItems(draftPerms);
    const failures: string[] = [];
    const succeeded: DirtyKind[] = [];

    setSaving(true);
    try {
      if (dirty.permissions) {
        try {
          await api.put(`/system/roles/${activeRoleId}/permissions`, { items });
          succeeded.push('permissions');
        } catch (e) {
          failures.push(`功能权限：${e instanceof Error ? e.message : '保存失败'}`);
        }
      }
      if (dirty.dataScope) {
        try {
          await api.put(`/system/roles/${activeRoleId}/data-scope`, {
            companyIds: draftExcludes,
          });
          succeeded.push('dataScope');
        } catch (e) {
          failures.push(`数据范围：${e instanceof Error ? e.message : '保存失败'}`);
        }
      }

      // 成功的那一项要清 dirty；失败的那一项必须保留，否则用户以为已保存而实际丢了改动
      setDirty((prev) => ({
        permissions: succeeded.includes('permissions') ? false : prev.permissions,
        dataScope: succeeded.includes('dataScope') ? false : prev.dataScope,
      }));

      if (failures.length) {
        message.error(failures.join('；'));
        return;
      }
      message.success('保存成功');
      // 设计 4.4：保存后即时刷新当前管理员的权限快照与菜单树 ——
      // 否则「给自己的角色新勾的菜单」要等重新登录才出现
      await refreshUser();
      await reloadSidebar();
    } finally {
      setSaving(false);
    }
  };

  // ---- 角色增删改 ----

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ status: 1, dataScope: 'company' });
    setEditorOpen(true);
  };

  const openEdit = (role: Role) => {
    setEditing(role);
    form.resetFields();
    form.setFieldsValue(role);
    setEditorOpen(true);
  };

  const handleSubmitRole = async () => {
    let values: Partial<Role>;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    setSubmitting(true);
    try {
      if (editing) await api.put(`/system/roles/${editing.id}`, values);
      else await api.post('/system/roles', values);
      message.success('保存成功');
      setEditorOpen(false);
      setEditing(null);
      await loadRoles();
      // 自己所属角色的数据范围可能被改，快照要跟着刷新
      await refreshUser();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSubmitting(false);
    }
  };

  const handleDeleteRole = (role: Role) => {
    confirmDelete({
      name: role.name,
      resourceLabel: '角色',
      onOk: async () => {
        try {
          await api.del(`/system/roles/${role.id}`);
          message.success('已删除');
          await loadRoles();
        } catch (e) {
          // 后端会说明「仍有 N 名成员」，原样透出
          message.error(e instanceof Error ? e.message : '删除失败');
          throw e;
        }
      },
    });
  };

  const activeRole = roles.find((r) => r.id === activeRoleId) ?? null;

  const memberColumns: ColumnsType<MemberUser> = [
    { title: '姓名', dataIndex: 'name', key: 'name', ellipsis: true },
    { title: '用户名', dataIndex: 'username', key: 'username', ellipsis: true },
    { title: '手机号', dataIndex: 'phone', key: 'phone', width: 130, render: (_, r) => r.phone || '-' },
    {
      title: '所属公司',
      dataIndex: 'companyName',
      key: 'companyName',
      ellipsis: true,
      render: (_, r) => r.companyName || '-',
    },
    {
      title: '所属部门',
      dataIndex: 'departmentName',
      key: 'departmentName',
      ellipsis: true,
      render: (_, r) => r.departmentName || '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 80,
      render: (_, r) => (
        <Tag color={r.status === 0 ? 'default' : 'success'} className="m-0">
          {r.status === 0 ? '停用' : '启用'}
        </Tag>
      ),
    },
  ];

  return (
    <div className="space-y-4 min-w-0 max-w-full overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold text-gray-900 flex items-center gap-2 m-0">
            <SafetyOutlined className="text-[var(--ams-primary)]" />
            角色权限
          </h1>
          <p className="text-sm text-gray-500 mt-1 mb-0">
            配置角色的功能权限、数据范围与成员。功能权限决定「能不能做」，数据范围决定「能看哪些数据」。
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <Button icon={<ReloadOutlined />} onClick={() => void loadRoles()} loading={rolesLoading}>
            刷新
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            disabled={!canAssign}
            onClick={openCreate}
          >
            新建角色
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-[260px_1fr] gap-4 min-w-0">
        {/* 左：角色列表 */}
        <aside className="bg-white rounded-xl border border-[var(--ams-border)] p-2 min-w-0">
          <div className="px-2 py-1.5 text-xs font-medium text-gray-400">
            角色（{roles.length}）
          </div>
          {roles.length === 0 ? (
            <div className="px-2 py-6 text-sm text-gray-400 text-center">
              {rolesLoading ? '加载中…' : '暂无角色'}
            </div>
          ) : (
            <ul className="m-0 p-0 list-none space-y-1">
              {roles.map((role) => {
                const active = role.id === activeRoleId;
                return (
                  <li key={role.id}>
                    <button
                      type="button"
                      onClick={() => void handleSelectRole(role)}
                      aria-current={active ? 'true' : undefined}
                      className={cn(
                        'w-full flex items-center justify-between gap-2 rounded-lg px-3 py-2 text-sm text-left transition-colors',
                        active
                          ? 'bg-blue-50 text-[var(--ams-primary)] font-medium'
                          : 'text-gray-600 hover:bg-gray-50',
                      )}
                    >
                      <span className="min-w-0">
                        <span className="block truncate">{role.name}</span>
                        <span className="block text-[11px] text-gray-400 truncate font-mono">
                          {role.code}
                        </span>
                      </span>
                      <span className="flex items-center gap-1 shrink-0">
                        {role.status === 0 && (
                          <Tag color="default" className="m-0 text-[10px] leading-4">
                            停用
                          </Tag>
                        )}
                        {/* 有未保存改动时把状态标出来，否则切换角色丢了改动会莫名其妙 */}
                        {active && hasDirty && (
                          <Tooltip title="有未保存的改动">
                            <span className="w-1.5 h-1.5 rounded-full bg-amber-500 inline-block" />
                          </Tooltip>
                        )}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </aside>

        {/* 右：三个 Tab */}
        <section className="bg-white rounded-xl border border-[var(--ams-border)] p-3 sm:p-4 min-w-0 overflow-hidden">
          {!activeRole ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择左侧角色" />
          ) : (
            <>
              <div className="flex flex-wrap items-start justify-between gap-3 mb-2">
                <div className="min-w-0">
                  <div className="text-base font-semibold text-gray-800 truncate flex items-center gap-2">
                    {activeRole.name}
                    {activeRole.dataScope && (
                      <Tag color="blue" className="m-0">
                        数据范围：{DATA_SCOPE_LABEL[activeRole.dataScope] ?? activeRole.dataScope}
                      </Tag>
                    )}
                  </div>
                  <div className="text-xs text-gray-400 mt-0.5 truncate font-mono">
                    {activeRole.code}
                  </div>
                </div>
                <Space wrap size={[8, 8]}>
                  <Button
                    size="small"
                    icon={<EditOutlined />}
                    disabled={!canAssign}
                    onClick={() => openEdit(activeRole)}
                  >
                    编辑角色
                  </Button>
                  <Button
                    size="small"
                    danger
                    icon={<DeleteOutlined />}
                    disabled={!canDelete}
                    onClick={() => handleDeleteRole(activeRole)}
                  >
                    删除
                  </Button>
                  <Tooltip title={!canAssign ? '需要 system.role:assign 权限' : undefined}>
                    <Button
                      type="primary"
                      size="small"
                      loading={saving}
                      disabled={!canAssign || !hasDirty}
                      onClick={() => void handleSave()}
                    >
                      保存{hasDirty ? '（有改动）' : ''}
                    </Button>
                  </Tooltip>
                </Space>
              </div>

              <Tabs
                activeKey={activeTab}
                onChange={(key) => void handleTabChange(key)}
                items={[
                  {
                    key: 'permissions',
                    label: (
                      <span>
                        功能权限
                        {dirty.permissions && <DirtyDot />}
                      </span>
                    ),
                    children: (
                      <PermissionMatrixTab
                        matrix={matrix}
                        loading={matrixLoading}
                        actionsOf={actionsOf}
                        onToggleMenu={toggleMenuAction}
                        onToggleDir={toggleDirAction}
                        readOnly={!canAssign}
                      />
                    ),
                  },
                  {
                    key: 'dataScope',
                    label: (
                      <span>
                        数据权限
                        {dirty.dataScope && <DirtyDot />}
                      </span>
                    ),
                    children: (
                      <DataScopeTab
                        scope={scope}
                        loading={scopeLoading}
                        excluded={draftExcludes}
                        inherited={inheritedExcluded}
                        childrenOf={companyIndex.childrenOf}
                        parentOf={companyIndex.parentOf}
                        rootCompanies={companyIndex.roots}
                        onToggle={toggleExclude}
                        readOnly={!canAssign}
                      />
                    ),
                  },
                  {
                    key: 'members',
                    label: (
                      <span className="inline-flex items-center gap-1">
                        <TeamOutlined />
                        成员
                        {members ? <span className="text-gray-400">{members.total}</span> : null}
                      </span>
                    ),
                    children: (
                      <Table<MemberUser>
                        rowKey="id"
                        size="small"
                        loading={membersLoading}
                        columns={memberColumns}
                        dataSource={members?.list ?? []}
                        pagination={{
                          current: members?.page ?? memberPage,
                          pageSize: members?.pageSize ?? 10,
                          total: members?.total ?? 0,
                          showSizeChanger: false,
                          onChange: (p) => {
                            setMemberPage(p);
                            if (activeRoleId != null) void loadMembers(activeRoleId, p);
                          },
                        }}
                        scroll={{ x: 800 }}
                      />
                    ),
                  },
                ]}
              />
            </>
          )}
        </section>
      </div>

      <Modal
        title={editing ? `编辑角色 · ${editing.name}` : '新建角色'}
        open={editorOpen}
        onOk={() => void handleSubmitRole()}
        onCancel={() => {
          setEditorOpen(false);
          setEditing(null);
        }}
        confirmLoading={submitting}
        destroyOnHidden
        centered
        width={Math.min(480, typeof window !== 'undefined' ? window.innerWidth - 32 : 480)}
      >
        <Form form={form} layout="vertical" className="mt-2">
          <Form.Item
            name="name"
            label="角色名称"
            rules={[{ required: true, message: '请输入角色名称' }]}
          >
            <Input maxLength={50} placeholder="如 资产管理员" />
          </Form.Item>
          <Form.Item
            name="code"
            label="角色编码"
            tooltip="创建后不可修改：编码是权限与配置的锚点"
            rules={[
              { required: !editing, message: '请输入角色编码' },
              { pattern: /^[a-z][a-z0-9_]*$/, message: '以小写字母开头，仅可含小写字母、数字与下划线' },
            ]}
          >
            <Input disabled={!!editing} maxLength={64} placeholder="如 asset_admin" className="font-mono" />
          </Form.Item>
          <Form.Item
            name="dataScope"
            label="数据范围"
            tooltip="本期 dept / project / self 一律按「所属公司 + 全部下级子树」生效"
            rules={[{ required: true, message: '请选择数据范围' }]}
          >
            <Select options={DATA_SCOPE_OPTIONS} />
          </Form.Item>
          <Form.Item name="status" label="状态" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 1, label: '启用' },
                { value: 0, label: '停用' },
              ]}
            />
          </Form.Item>
          <Alert
            type="info"
            showIcon
            message="数据范围「全部」需要在下方「数据权限」页签里进一步收窄"
            description="若不给该角色设置排除清单，它将可访问全集团所有公司（仍受功能权限约束）。"
          />
        </Form>
      </Modal>
    </div>
  );
}

/** 未保存改动的小圆点 */
function DirtyDot() {
  return (
    <Tooltip title="有未保存的改动">
      <span className="inline-block w-1.5 h-1.5 rounded-full bg-amber-500 ml-1.5 align-middle" />
    </Tooltip>
  );
}

// ============================================================================
// Tab 1：功能权限矩阵（菜单树 × 动作）
// ============================================================================

function PermissionMatrixTab({
  matrix,
  loading,
  actionsOf,
  onToggleMenu,
  onToggleDir,
  readOnly,
}: {
  matrix: RolePermissionMatrix | null;
  loading: boolean;
  actionsOf: (menuId: number) => string[];
  onToggleMenu: (menuId: number, action: string, checked: boolean) => void;
  onToggleDir: (dir: ApiMenuNode, action: string, checked: boolean) => void;
  readOnly: boolean;
}) {
  const [expandedKeys, setExpandedKeys] = useState<number[]>([]);

  useEffect(() => {
    setExpandedKeys(flatten(matrix?.menus ?? []).filter((n) => n.menuType === 'dir').map((n) => n.id));
  }, [matrix]);

  const enforced = useMemo(() => new Set(matrix?.enforced ?? []), [matrix]);
  const menuRows = useMemo(
    () => flatten(matrix?.menus ?? []).filter((n) => n.menuType === 'menu'),
    [matrix],
  );

  /** 每个动作在全量菜单中「尚未强制校验」的数量，用于列头提示。 */
  const unenforcedByAction = useMemo(() => {
    const counter: Record<string, number> = {};
    for (const action of matrix?.actions ?? []) {
      counter[action] = menuRows.filter(
        (m) => m.code && !enforced.has(`${m.code}:${action}`),
      ).length;
    }
    return counter;
  }, [enforced, matrix, menuRows]);

  const isEnforced = (menu: ApiMenuNode, action: string) =>
    !!menu.code && enforced.has(`${menu.code}:${action}`);

  if (!matrix) {
    return loading ? (
      <div className="py-10 text-center text-sm text-gray-400">加载中…</div>
    ) : (
      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无权限数据" />
    );
  }

  const columns: ColumnsType<ApiMenuNode> = [
    {
      title: '菜单',
      dataIndex: 'name',
      key: 'name',
      width: 220,
      ellipsis: true,
      render: (_, row) => (
        <span className={cn('truncate', row.menuType === 'dir' && 'font-medium text-gray-700')}>
          {row.name}
          {/* 停用菜单仍可授权（权限点保留），但要提示它当前不生效 */}
          {row.status === 0 && (
            <Tag color="default" className="m-0 ml-1 text-[10px] leading-4">
              停用
            </Tag>
          )}
        </span>
      ),
    },
    ...matrix.actions.map((action) => ({
      title: (
        <span className="inline-flex items-center gap-1">
          {actionLabel(action)}
          {unenforcedByAction[action] > 0 && (
            <Tooltip
              title={`有 ${unenforcedByAction[action]} 个菜单的「${actionLabel(action)}」尚未接入强制校验：勾选后暂不拦截，仅前端按快照隐藏入口`}
            >
              <WarningOutlined className="text-amber-500" />
            </Tooltip>
          )}
        </span>
      ),
      key: `action-${action}`,
      width: 78,
      align: 'center' as const,
      render: (_: unknown, row: ApiMenuNode) => {
        if (row.menuType === 'dir') {
          const children = row.children ?? [];
          const state = dirActionState(children, actionsOf, action);
          return (
            <Checkbox
              disabled={readOnly || children.length === 0}
              checked={state.checked}
              indeterminate={state.indeterminate}
              onChange={(e) => onToggleDir(row, action, e.target.checked)}
              aria-label={`${row.name} 全部子菜单的「${actionLabel(action)}」`}
            />
          );
        }
        const checked = actionsOf(row.id).includes(action);
        const unenforced = !isEnforced(row, action);
        return (
          <span className="inline-flex items-center gap-1">
            <Checkbox
              disabled={readOnly}
              checked={checked}
              onChange={(e) => onToggleMenu(row.id, action, e.target.checked)}
              aria-label={`${row.name} 的「${actionLabel(action)}」`}
            />
            {/* 勾了但后端尚未强制校验 —— 必须显式标记，否则管理员会误判「已生效」 */}
            {checked && unenforced && (
              <Tooltip title="该动作尚未接入强制校验：当前仅前端按权限快照隐藏入口，后端不会拦截">
                <WarningOutlined className="text-amber-500 text-xs" />
              </Tooltip>
            )}
          </span>
        );
      },
    })),
  ];

  return (
    <div className="space-y-2 min-w-0">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-gray-500">
        <span className="inline-flex items-center gap-1">
          <WarningOutlined className="text-amber-500" />
          表示该动作尚未接入强制校验（勾选后暂不生效）
        </span>
        <span>
          <CheckCircleFilled className="text-[var(--ams-primary)]" /> 目录行可整列全选 / 半选
        </span>
        <span>页面可见性只认「查看」，其余动作单独生效</span>
      </div>
      <div className="ams-table-wrap">
        <Table<ApiMenuNode>
          rowKey="id"
          size="small"
          loading={loading}
          columns={columns}
          dataSource={matrix.menus}
          pagination={false}
          scroll={{ x: 240 + matrix.actions.length * 78 }}
          expandable={{
            expandedRowKeys: expandedKeys,
            onExpandedRowsChange: (keys) => setExpandedKeys(keys as number[]),
          }}
        />
      </div>
    </div>
  );
}

// ============================================================================
// Tab 2：数据权限（公司树 + 排除清单，勾选即排除）
// ============================================================================

function DataScopeTab({
  scope,
  loading,
  excluded,
  inherited,
  childrenOf,
  parentOf,
  rootCompanies,
  onToggle,
  readOnly,
}: {
  scope: RoleDataScopeView | null;
  loading: boolean;
  /** 显式排除（可取消） */
  excluded: number[];
  /** 继承排除（父级已排除，置灰） */
  inherited: Set<number>;
  childrenOf: (parentId: number) => CompanyNode[];
  parentOf: (id: number) => CompanyNode | undefined;
  rootCompanies: CompanyNode[];
  onToggle: (companyId: number, checked: boolean) => void;
  readOnly: boolean;
}) {
  if (!scope) {
    return loading ? (
      <div className="py-10 text-center text-sm text-gray-400">加载中…</div>
    ) : (
      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据范围数据" />
    );
  }
  const locked = new Set(scope.lockedCompanyIds ?? []);

  const renderNodes = (nodes: CompanyNode[], depth: number) =>
    nodes.map((company) => {
      // 三态互斥：显式排除（可取消）/ 继承排除（置灰）/ 基线内
      const isExplicit = excluded.includes(company.id);
      const isInherited = inherited.has(company.id);
      const isLocked = locked.has(company.id);
      // 显式排除但已被上级覆盖：此刻冗余，但取消上级后会生效，因此仍必须可取消
      const isRedundant = isExplicit && underExcludedAncestor(company.id, parentOf, excluded);
      return (
        <div key={company.id}>
          <label
            className={cn(
              'flex items-start gap-2 rounded px-2 py-1.5',
              isInherited && 'opacity-60',
              !readOnly && !isInherited && !isLocked && 'hover:bg-gray-50 cursor-pointer',
            )}
            style={{ paddingLeft: 8 + depth * 18 }}
          >
            <Checkbox
              className="mt-0.5"
              checked={isExplicit || isInherited}
              disabled={readOnly || isInherited || isLocked}
              onChange={(e) => onToggle(company.id, e.target.checked)}
            />
            <span className="min-w-0 flex-1">
              <span className="text-sm text-gray-700">{company.name}</span>
              {company.shortName && (
                <span className="text-xs text-gray-400 ml-2">{company.shortName}</span>
              )}
              {isLocked && (
                <Tag color="blue" className="m-0 ml-2">
                  自己所属公司，不可排除
                </Tag>
              )}
              {isInherited && (
                <Tag color="default" className="m-0 ml-2">
                  继承排除
                </Tag>
              )}
              {isRedundant && (
                <Tooltip title="上级公司已排除，此项当前是冗余的；取消上级排除后它会重新生效">
                  <Tag color="default" className="m-0 ml-2">
                    上级已排除
                  </Tag>
                </Tooltip>
              )}
            </span>
          </label>
          {renderNodes(childrenOf(company.id), depth + 1)}
        </div>
      );
    });

  return (
    <div className="space-y-3 min-w-0">
      <Alert
        type="warning"
        showIcon
        message="这里勾选的是「排除」，不是「可访问」"
        description="勾选某公司 = 把它连同其全部下级公司一起从该角色的可访问范围中扣除。取消勾选父公司后，其下级会恢复可勾选。"
      />

      <div className="rounded border border-[var(--ams-border)] p-2 text-xs text-gray-600 space-y-1">
        <div>
          角色数据范围：<span className="font-medium">{DATA_SCOPE_LABEL[scope.dataScope] ?? scope.dataScope}</span>
        </div>
        <div>实际生效范围：{scope.effectiveScope}</div>
        {(scope.degradedScopes ?? []).length > 0 && (
          <div className="text-amber-600">
            注意：{scope.degradedScopes.map((s) => DATA_SCOPE_LABEL[s] ?? s).join('、')} 本期尚未实现，
            当前按「所属公司 + 全部下级子树」生效，并未真正收窄范围。
          </div>
        )}
        <div className="text-gray-400">
          修改「数据范围」取值请点右上角「编辑角色」。
        </div>
      </div>

      <div>
        <div className="text-sm font-medium text-gray-700 mb-1">
          公司树（{scope.companies?.length ?? 0} 家）
        </div>
        {rootCompanies.length === 0 ? (
          <div className="py-6 text-center text-sm text-gray-400">暂无可用公司</div>
        ) : (
          <div className="rounded border border-[var(--ams-border)] py-1 max-h-[420px] overflow-auto ams-scroll">
            {renderNodes(rootCompanies, 0)}
          </div>
        )}
      </div>

      <div className="text-xs text-gray-400">
        已显式排除 {excluded.length} 家
        {inherited.size > 0 ? `，连带下级共 ${excluded.length + inherited.size} 家` : ''}
      </div>
    </div>
  );
}
