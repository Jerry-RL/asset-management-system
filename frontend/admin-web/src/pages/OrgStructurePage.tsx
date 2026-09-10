import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import {
  Alert,
  Button,
  Dropdown,
  Empty,
  Form,
  Input,
  InputNumber,
  Menu,
  Modal,
  Radio,
  Select,
  Space,
  Spin,
  Tag,
  Tooltip,
  Tree,
  message,
} from 'antd';
import type { MenuProps } from 'antd';
import {
  ApartmentOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  ExpandOutlined,
  MoreOutlined,
  PlusOutlined,
  ReloadOutlined,
  StopOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import { Graph } from '@antv/g6';
import { api } from '@/lib/api';
import { confirmDelete } from '@/lib/confirm';

// ============================================================================
// 组织架构图谱：母公司 → 子公司 → 部门 → 员工，节点 + 类型化边
// ============================================================================

interface OrgGraphNode {
  id: string;
  nodeType: 'company' | 'department' | 'employee';
  bizId: number;
  name: string;
  subtitle?: string;
  status?: number;
  parentId?: string;
  companyId?: number;
  attrs?: Record<string, unknown>;
}

interface OrgGraphEdge {
  id: string;
  source: string;
  target: string;
  edgeType: string;
  label?: string;
}

interface OrgGraph {
  nodes: OrgGraphNode[];
  edges: OrgGraphEdge[];
  stats: Record<string, unknown>;
}

interface Company {
  id: number;
  name: string;
  parentId?: number;
}

interface NeighborView {
  edgeId: string;
  edgeType: string;
  label: string;
  direction: string;
  nodeId: string;
}

const NODE_META: Record<string, { label: string; color: string }> = {
  company: { label: '公司', color: '#1677ff' },
  department: { label: '部门', color: '#52c41a' },
  employee: { label: '员工', color: '#fa8c16' },
};

const DEPTH_OPTIONS = [
  { value: 1, label: '仅公司' },
  { value: 2, label: '公司 + 部门' },
  { value: 3, label: '公司 + 部门 + 员工' },
];

const VIEW_OPTIONS = [
  { value: 'graph', label: '图谱' },
  { value: 'tree', label: '树形' },
];

const LAYOUT_OPTIONS = [
  { value: 'TB', label: '纵向' },
  { value: 'LR', label: '横向' },
];

/** 树形视图节点 */
interface OrgTreeNode {
  key: string;
  title: ReactNode;
  children?: OrgTreeNode[];
}

const formatTime = (value: unknown) => {
  if (!value) return '-';
  const text = String(value);
  return text.length >= 19 ? text.slice(0, 19).replace('T', ' ') : text;
};

/** G6 的 datum.data 为 Record<string, unknown>，统一经 unknown 收窄为业务类型 */
const datumOf = <T,>(d: unknown): T | undefined => (d as { data?: T } | undefined)?.data;

type Option = { value: string | number; label: string };

/** 图谱内联表单字段（新增/编辑共用一套渲染） */
interface OrgFieldConfig {
  name: string;
  label: string;
  type?: 'text' | 'number' | 'textarea' | 'select';
  options?: Option[];
  multiple?: boolean;
  required?: boolean;
}

interface OrgFormContext {
  title: string;
  fields: OrgFieldConfig[];
  initial: Record<string, unknown>;
  submit: (values: Record<string, unknown>) => Promise<void>;
}

const STATUS_OPTIONS: Option[] = [
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
];

/**
 * 兼容后端 PageResult 与直接返回数组两种形态的下拉选项加载。
 */
const fetchOptions = async (
  path: string,
  valueKey = 'id',
  labelKey = 'name',
  extraKey?: string,
): Promise<Option[]> => {
  try {
    const raw = await api.get<unknown>(path);
    const list = Array.isArray(raw)
      ? raw
      : ((raw as { list?: unknown[] } | null)?.list ?? []);
    return (list as Record<string, unknown>[]).map((item) => {
      const value = item[valueKey] as string | number;
      const base = String(item[labelKey] ?? value);
      const extra = extraKey && item[extraKey] != null ? `（${String(item[extraKey])}）` : '';
      return { value, label: `${base}${extra}` };
    });
  } catch {
    return [];
  }
};

/** 从图谱节点 id（company:3）解析业务 ID */
const bizIdOfParent = (parentId?: string): number | undefined => {
  if (!parentId) return undefined;
  const raw = parentId.split(':')[1];
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : undefined;
};

const DISABLED_FILL = '#e9edf3';
const DISABLED_STROKE = '#cbd5e1';
const DISABLED_LABEL = '#94a3b8';

/** 右键菜单估算尺寸，用于边界收敛 */
const MENU_WIDTH = 176;
const MENU_HEIGHT = 236;

const isDisabledNode = (d: unknown) => datumOf<OrgGraphNode>(d)?.status === 0;

export function OrgStructurePage() {
  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Graph | null>(null);
  /** 当前画布 render() 的 Promise，用于等待首次渲染完成后再设置元素状态 */
  const readyRef = useRef<Promise<unknown> | null>(null);
  /** 已高亮的节点 id，用于在选中项切换时清理上一个节点的高亮态 */
  const highlightedRef = useRef<string | null>(null);

  const [companies, setCompanies] = useState<Company[]>([]);
  const [rootId, setRootId] = useState<number | undefined>(undefined);
  const [depth, setDepth] = useState<number>(3);
  /** 视图模式：G6 画布图谱 / 树形嵌套 */
  const [viewMode, setViewMode] = useState<'graph' | 'tree'>('graph');
  /** 图谱布局方向：纵向（上→下）/ 横向（左→右） */
  const [layoutDir, setLayoutDir] = useState<'TB' | 'LR'>('TB');
  const [graphData, setGraphData] = useState<OrgGraph | null>(null);
  const [loading, setLoading] = useState(false);
  const [renderError, setRenderError] = useState<string | null>(null);
  const [selected, setSelected] = useState<OrgGraphNode | null>(null);
  const [neighbors, setNeighbors] = useState<{
    incoming: NeighborView[];
    outgoing: NeighborView[];
    degree: number;
  } | null>(null);

  // ---- 图谱内联新增/编辑 ----
  const [formCtx, setFormCtx] = useState<OrgFormContext | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [nodeForm] = Form.useForm();
  /** 图谱右键菜单：坐标相对画布容器左上角 */
  const [menu, setMenu] = useState<{ x: number; y: number; node: OrgGraphNode } | null>(null);
  /**
   * 双击节点 → 编辑。
   * G6 事件在渲染 effect 中注册，早于下方动作定义，故用 ref 持有最新引用避免声明顺序问题。
   */
  const openEditRef = useRef<(node: OrgGraphNode) => void>(() => {});
  /** 下拉数据源：字典项 + 公司 + 部门 + 员工 */
  const [companyOptions, setCompanyOptions] = useState<Option[]>([]);
  const [departmentOptions, setDepartmentOptions] = useState<Option[]>([]);
  const [userOptions, setUserOptions] = useState<Option[]>([]);
  const [companyTypeOptions, setCompanyTypeOptions] = useState<Option[]>([]);
  const [departmentTypeOptions, setDepartmentTypeOptions] = useState<Option[]>([]);
  const [roleOptions, setRoleOptions] = useState<Option[]>([]);

  // 公司下拉选项（母公司 + 全部子公司）
  useEffect(() => {
    const loadCompanies = async () => {
      try {
        setCompanies(await api.get<Company[]>('/org/companies'));
      } catch {
        setCompanies([]);
      }
    };
    void loadCompanies();
  }, []);

  /** 拉取图谱内联表单的下拉数据源（公司/部门/员工/字典） */
  const loadRefOptions = useCallback(async () => {
    const [companiesOpt, departmentsOpt, usersOpt, companyTypes, deptTypes, roles] =
      await Promise.all([
        fetchOptions('/org/companies', 'id', 'name', 'shortName'),
        fetchOptions('/org/departments', 'id', 'name', 'companyName'),
        fetchOptions('/system/users?page=1&pageSize=500', 'id', 'name', 'username'),
        fetchOptions('/system/dict/items?code=company_type', 'value', 'label'),
        fetchOptions('/system/dict/items?code=department_type', 'value', 'label'),
        fetchOptions('/system/roles', 'id', 'name'),
      ]);
    setCompanyOptions(companiesOpt);
    setDepartmentOptions(departmentsOpt);
    setUserOptions(usersOpt);
    setCompanyTypeOptions(companyTypes);
    setDepartmentTypeOptions(deptTypes);
    setRoleOptions(roles);
  }, []);

  useEffect(() => {
    void loadRefOptions();
  }, [loadRefOptions]);

  const loadGraph = useCallback(async () => {
    setLoading(true);
    setRenderError(null);
    try {
      const params = new URLSearchParams();
      if (rootId != null) params.set('rootId', String(rootId));
      params.set('depth', String(depth));
      const data = await api.get<OrgGraph>(`/org/graph?${params.toString()}`);
      setGraphData(data);
    } catch (e) {
      setGraphData(null);
      message.error(e instanceof Error ? e.message : '加载组织架构失败');
    } finally {
      setLoading(false);
    }
  }, [rootId, depth]);

  /** 变更成功后统一刷新图谱与下拉数据源 */
  const refreshAll = useCallback(async () => {
    await loadGraph();
    await loadRefOptions();
    try {
      setCompanies(await api.get<Company[]>('/org/companies'));
    } catch {
      /* 忽略：下拉刷新失败不影响主流程 */
    }
    setSelected(null);
  }, [loadGraph, loadRefOptions]);

  useEffect(() => {
    void loadGraph();
  }, [loadGraph]);

  const nodeById = useMemo(() => {
    const map = new Map<string, OrgGraphNode>();
    graphData?.nodes.forEach((n) => map.set(n.id, n));
    return map;
  }, [graphData]);

  // ---- G6 渲染 ----
  useEffect(() => {
    const container = containerRef.current;
    // 树形视图下容器不挂载，跳过渲染
    if (viewMode !== 'graph' || !container || !graphData) return;

    // 销毁上一次实例，避免残留画布
    if (graphRef.current) {
      graphRef.current.destroy();
      graphRef.current = null;
    }
    container.innerHTML = '';

    if (graphData.nodes.length === 0) return;

    try {
      const graph = new Graph({
        container,
        autoFit: 'view',
        padding: 20,
        data: {
          nodes: graphData.nodes.map((n) => ({ id: n.id, data: { ...n } })),
          edges: graphData.edges.map((e) => ({
            id: e.id,
            source: e.source,
            target: e.target,
            data: { ...e },
          })),
        },
        node: {
          type: 'rect',
          style: {
            size: [170, 42],
            radius: 8,
            // 停用节点：灰底 + 虚线描边，与启用节点一眼区分
            fill: (d) =>
              isDisabledNode(d)
                ? DISABLED_FILL
                : (NODE_META[datumOf<OrgGraphNode>(d)?.nodeType ?? '']?.color ?? '#8c8c8c'),
            stroke: (d) => (isDisabledNode(d) ? DISABLED_STROKE : '#ffffff'),
            lineWidth: 1.5,
            lineDash: (d) => (isDisabledNode(d) ? [5, 4] : undefined),
            shadowColor: 'rgba(15,23,42,0.12)',
            shadowBlur: 6,
            labelText: (d) => String(datumOf<OrgGraphNode>(d)?.name ?? ''),
            labelFill: (d) => (isDisabledNode(d) ? DISABLED_LABEL : '#fff'),
            labelFontSize: 12,
            labelFontWeight: 500,
            labelPlacement: 'center',
            labelWordWrap: true,
            labelMaxWidth: 150,
          },
          // 选中态高亮：由 setElementState(nodeId, 'selected') 触发
          state: {
            selected: {
              stroke: '#1677ff',
              lineWidth: 2.5,
              shadowColor: 'rgba(22,119,255,0.45)',
              shadowBlur: 14,
            },
          },
        },
        edge: {
          type: 'polyline',
          style: {
            stroke: '#cbd5e1',
            lineWidth: 1.2,
            endArrow: true,
            router: { type: 'orth' },
            labelText: (d) => String(datumOf<OrgGraphEdge>(d)?.label ?? ''),
            labelFontSize: 10,
            labelFill: '#94a3b8',
            labelBackground: true,
            labelBackgroundFill: '#ffffff',
            labelBackgroundOpacity: 0.9,
          },
        },
        layout: {
          type: 'antv-dagre',
          rankdir: layoutDir,
          nodesep: 18,
          ranksep: 70,
        },
        behaviors: ['zoom-canvas', 'drag-canvas', 'drag-element'],
        animation: false,
      });

      const nodeIdOf = (e: unknown): string | undefined => {
        const evt = e as { target?: { id?: string }; targetId?: string };
        return evt.target?.id ?? evt.targetId;
      };

      graph.on('node:click', (e: unknown) => {
        const nodeId = nodeIdOf(e);
        if (nodeId) setSelected(nodeById.get(nodeId) ?? null);
      });

      // 双击节点 = 编辑该节点（快捷路径）
      graph.on('node:dblclick', (e: unknown) => {
        const nodeId = nodeIdOf(e);
        const node = nodeId ? nodeById.get(nodeId) : undefined;
        if (node) openEditRef.current(node);
      });

      // 右键节点弹出操作菜单（新增/编辑/停用/删除），并选中该节点
      graph.on('node:contextmenu', (e: unknown) => {
        const evt = e as {
          /** 视口坐标（相对浏览器），与 G6 内置右键菜单插件保持一致 */
          client?: { x: number; y: number };
          preventDefault?: () => void;
        };
        // 阻止浏览器默认右键菜单
        evt.preventDefault?.();

        const nodeId = nodeIdOf(e);
        const node = nodeId ? nodeById.get(nodeId) : undefined;
        if (!node) return;

        const rect = container.getBoundingClientRect();
        const rawX = (evt.client?.x ?? rect.left + rect.width / 2) - rect.left;
        const rawY = (evt.client?.y ?? rect.top + rect.height / 2) - rect.top;
        // 贴近右下边界时向内收，避免菜单溢出画布
        setMenu({
          node,
          x: Math.max(4, Math.min(rawX, rect.width - MENU_WIDTH - 4)),
          y: Math.max(4, Math.min(rawY, rect.height - MENU_HEIGHT - 4)),
        });
        setSelected(node);
      });

      // render() 为异步：记录 Promise，供选中态高亮等待画布就绪
      readyRef.current = graph.render();
      graphRef.current = graph;
      // 新画布不继承旧的选中态，需重新同步
      highlightedRef.current = null;
      setRenderError(null);
    } catch (e) {
      setRenderError(e instanceof Error ? e.message : '图谱渲染失败');
    }

    return () => {
      if (graphRef.current) {
        graphRef.current.destroy();
        graphRef.current = null;
      }
      readyRef.current = null;
    };
  }, [graphData, nodeById, viewMode, layoutDir]);

  // ---- 选中态高亮同步到画布 ----
  useEffect(() => {
    const graph = graphRef.current;
    if (viewMode !== 'graph' || !graph) return;
    const knownIds = new Set((graphData?.nodes ?? []).map((n) => n.id));

    const applyHighlight = () => {
      // 画布可能已被替换/销毁（切换视图或刷新数据），旧 graph 上的状态调用会抛错
      if (graphRef.current !== graph) return;
      const prev = highlightedRef.current;
      // 数据刷新后旧节点 id 已不存在，需要先判存在性再清理状态
      if (prev && prev !== selected?.id && knownIds.has(prev)) {
        void graph.setElementState(prev, []);
      }
      if (selected?.id && knownIds.has(selected.id)) {
        void graph.setElementState(selected.id, ['selected']);
      }
      highlightedRef.current = selected?.id ?? null;
    };

    // graph.render() 是异步的，未渲染完成时调用 setElementState 会读到未初始化的画布
    const ready = readyRef.current;
    if (ready) {
      void ready.then(applyHighlight).catch(() => {});
    } else {
      applyHighlight();
    }
  }, [selected, graphData, viewMode]);

  // ---- 右键菜单：点击空白/滚动/缩放时关闭 ----
  useEffect(() => {
    if (!menu) return;
    const close = () => setMenu(null);
    window.addEventListener('click', close);
    window.addEventListener('resize', close);
    window.addEventListener('scroll', close, true);
    return () => {
      window.removeEventListener('click', close);
      window.removeEventListener('resize', close);
      window.removeEventListener('scroll', close, true);
    };
  }, [menu]);

  // ---- 选中节点：拉取邻居 ----
  useEffect(() => {
    if (!selected) {
      setNeighbors(null);
      return;
    }
    let cancelled = false;
    const load = async () => {
      try {
        const data = await api.get<{
          incoming: NeighborView[];
          outgoing: NeighborView[];
          degree: number;
        }>(`/org/graph/neighbors?nodeType=${selected.nodeType}&bizId=${selected.bizId}`);
        if (!cancelled) setNeighbors(data);
      } catch {
        if (!cancelled) setNeighbors(null);
      }
    };
    void load();
    return () => {
      cancelled = true;
    };
  }, [selected]);

  const handleFit = () => {
    void graphRef.current?.fitView();
  };

  // ---- 图谱/树内联维护：新增、编辑、停用、删除 ----

  const openForm = useCallback(
    (ctx: OrgFormContext) => {
      setFormCtx(ctx);
      nodeForm.setFieldsValue(ctx.initial);
    },
    [nodeForm],
  );

  const handleSubmitForm = useCallback(async () => {
    if (!formCtx) return;
    let values: Record<string, unknown>;
    try {
      values = await nodeForm.validateFields();
    } catch {
      return; // 校验失败，Form 已就地提示
    }
    setSubmitting(true);
    try {
      await formCtx.submit(values);
      message.success('保存成功');
      setFormCtx(null);
      nodeForm.resetFields();
      await refreshAll();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败');
    } finally {
      setSubmitting(false);
    }
  }, [formCtx, nodeForm, refreshAll]);

  /** 新增/编辑公司；presetParentId 用于「新增子公司」 */
  const openCompanyForm = useCallback(
    (node?: OrgGraphNode, presetParentId?: number) => {
      const editing = !!node;
      openForm({
        title: editing ? `编辑公司 · ${node!.name}` : '新增公司',
        fields: [
          { name: 'name', label: '公司名称', required: true },
          { name: 'shortName', label: '公司简称' },
          { name: 'companyType', label: '公司类型', type: 'select', options: companyTypeOptions },
          { name: 'address', label: '公司地址', type: 'textarea' },
          { name: 'phone', label: '联系电话' },
          {
            name: 'parentId',
            label: '上级公司',
            type: 'select',
            // 排除自身，避免误选成自己的上级（后端另有防环校验）
            options: companyOptions.filter((o) => o.value !== node?.bizId),
          },
          { name: 'sort', label: '排序', type: 'number' },
          { name: 'status', label: '状态', type: 'select', options: STATUS_OPTIONS },
        ],
        initial: editing
          ? {
              name: node!.name,
              shortName: node!.attrs?.shortName ?? undefined,
              companyType: node!.attrs?.companyType ?? undefined,
              address: node!.attrs?.address ?? undefined,
              phone: node!.attrs?.phone ?? undefined,
              parentId: bizIdOfParent(node!.parentId),
              status: node!.status ?? 1,
            }
          : { status: 1, sort: 0, parentId: presetParentId },
        submit: async (values) => {
          if (editing) {
            // 必须回传 parentId：后端据此走防环校验，缺省会误判为「变更为母公司」
            await api.put(`/org/companies/${node!.bizId}`, values);
            return;
          }
          await api.post('/org/companies', values);
        },
      });
    },
    [openForm, companyOptions, companyTypeOptions],
  );

  /** 新增/编辑部门；新增时 companyId + parentId 决定其挂载位置 */
  const openDepartmentForm = useCallback(
    (opts: { node?: OrgGraphNode; companyId?: number; parentId?: number }) => {
      const { node, companyId, parentId } = opts;
      const editing = !!node;
      openForm({
        title: editing ? `编辑部门 · ${node!.name}` : '新增部门',
        fields: [
          { name: 'name', label: '部门名称', required: true },
          {
            name: 'companyId',
            label: '所属公司',
            type: 'select',
            options: companyOptions,
            required: true,
          },
          { name: 'type', label: '部门类型', type: 'select', options: departmentTypeOptions },
          { name: 'leaderId', label: '负责人', type: 'select', options: userOptions },
          { name: 'sort', label: '排序', type: 'number' },
          { name: 'status', label: '状态', type: 'select', options: STATUS_OPTIONS },
          { name: 'remark', label: '备注', type: 'textarea' },
        ],
        initial: editing
          ? {
              name: node!.name,
              companyId: node!.companyId,
              type: node!.attrs?.type ?? undefined,
              leaderId: node!.attrs?.leaderId ?? undefined,
              status: node!.status ?? 1,
              remark: node!.attrs?.remark ?? undefined,
            }
          : { companyId, status: 1, sort: 0 },
        submit: async (values) => {
          if (editing) {
            await api.put(`/org/departments/${node!.bizId}`, values);
            return;
          }
          // 新增时把上级部门一并写入，图谱才会挂到正确的父节点
          await api.post('/org/departments', { ...values, parentId: parentId ?? null });
        },
      });
    },
    [openForm, companyOptions, departmentTypeOptions, userOptions],
  );

  /** 新增/编辑人员；编辑前先取详情，保证角色回显不被清空 */
  const openUserForm = useCallback(
    async (opts: { node?: OrgGraphNode; companyId?: number; departmentId?: number }) => {
      const { node, companyId, departmentId } = opts;
      const editing = !!node;
      let detail: Record<string, unknown> | null = null;
      if (editing) {
        try {
          detail = await api.get<Record<string, unknown>>(`/system/users/${node!.bizId}`);
        } catch {
          detail = null; // 取不到详情时退化为节点数据
        }
      }
      const roleIds = detail?.roleIds;
      const identityFields: OrgFieldConfig[] = editing
        ? []
        : [
            { name: 'username', label: '账号', required: true },
            { name: 'password', label: '初始密码' },
          ];
      openForm({
        title: editing ? `编辑人员 · ${node!.name}` : '新增人员',
        fields: [
          ...identityFields,
          { name: 'name', label: '姓名', required: true },
          { name: 'phone', label: '手机号' },
          { name: 'companyId', label: '所属公司', type: 'select', options: companyOptions },
          { name: 'departmentId', label: '所属部门', type: 'select', options: departmentOptions },
          { name: 'roleIds', label: '角色', type: 'select', multiple: true, options: roleOptions },
          { name: 'status', label: '状态', type: 'select', options: STATUS_OPTIONS },
        ],
        initial: editing
          ? {
              name: detail?.name ?? node!.name,
              phone: detail?.phone ?? node!.attrs?.phone ?? undefined,
              companyId: detail?.companyId ?? node!.companyId,
              departmentId: detail?.departmentId ?? node!.attrs?.departmentId ?? undefined,
              roleIds: Array.isArray(roleIds) ? roleIds : undefined,
              status: (detail?.status as number | undefined) ?? node!.status ?? 1,
            }
          : { companyId, departmentId, status: 1 },
        submit: async (values) => {
          if (editing) {
            await api.put(`/system/users/${node!.bizId}`, values);
            return;
          }
          await api.post('/system/users', values);
        },
      });
    },
    [openForm, companyOptions, departmentOptions, roleOptions],
  );

  /** 按节点类型拼出「维护」动作 */
  const actionsFor = useCallback(
    (node: OrgGraphNode): { key: string; label: string; icon: ReactNode; onClick: () => void }[] => {
      if (node.nodeType === 'company') {
        return [
          {
            key: 'addSubCompany',
            label: '新增子公司',
            icon: <PlusOutlined />,
            onClick: () => openCompanyForm(undefined, node.bizId),
          },
          {
            key: 'addDept',
            label: '新增部门',
            icon: <PlusOutlined />,
            onClick: () => openDepartmentForm({ companyId: node.bizId }),
          },
          {
            key: 'edit',
            label: '编辑',
            icon: <EditOutlined />,
            onClick: () => openCompanyForm(node),
          },
        ];
      }
      if (node.nodeType === 'department') {
        return [
          {
            key: 'addSubDept',
            label: '新增子部门',
            icon: <PlusOutlined />,
            onClick: () => openDepartmentForm({ companyId: node.companyId, parentId: node.bizId }),
          },
          {
            key: 'addUser',
            label: '新增人员',
            icon: <PlusOutlined />,
            onClick: () =>
              void openUserForm({ companyId: node.companyId, departmentId: node.bizId }),
          },
          {
            key: 'edit',
            label: '编辑',
            icon: <EditOutlined />,
            onClick: () => openDepartmentForm({ node }),
          },
        ];
      }
      return [
        {
          key: 'edit',
          label: '编辑',
          icon: <EditOutlined />,
          onClick: () => void openUserForm({ node }),
        },
      ];
    },
    [openCompanyForm, openDepartmentForm, openUserForm],
  );

  /** 停用/启用：三类节点各自走独立状态端点，避免误触发上级变更 */
  const toggleStatus = useCallback(
    async (node: OrgGraphNode) => {
      const next = (node.status ?? 1) === 1 ? 0 : 1;
      const path =
        node.nodeType === 'company'
          ? `/org/companies/${node.bizId}/status`
          : node.nodeType === 'department'
            ? `/org/departments/${node.bizId}/status`
            : `/system/users/${node.bizId}/status`;
      try {
        await api.put(path, { status: next });
        message.success(next === 1 ? '已启用' : '已停用');
        await refreshAll();
      } catch (e) {
        message.error(e instanceof Error ? e.message : '操作失败');
      }
    },
    [refreshAll],
  );

  const handleDelete = useCallback(
    (node: OrgGraphNode) => {
      const path =
        node.nodeType === 'company'
          ? `/org/companies/${node.bizId}`
          : node.nodeType === 'department'
            ? `/org/departments/${node.bizId}`
            : `/system/users/${node.bizId}`;
      confirmDelete({
        name: node.name,
        resourceLabel: NODE_META[node.nodeType]?.label,
        onOk: async () => {
          try {
            await api.del(path);
            message.success('已删除');
            await refreshAll();
          } catch (e) {
            message.error(e instanceof Error ? e.message : '删除失败');
            throw e;
          }
        },
      });
    },
    [refreshAll],
  );

  /** 双击节点的默认动作：直接进入该节点的编辑表单 */
  const openNodeEdit = useCallback(
    (node: OrgGraphNode) => {
      if (node.nodeType === 'company') {
        openCompanyForm(node);
        return;
      }
      if (node.nodeType === 'department') {
        openDepartmentForm({ node });
        return;
      }
      void openUserForm({ node });
    },
    [openCompanyForm, openDepartmentForm, openUserForm],
  );

  useEffect(() => {
    openEditRef.current = openNodeEdit;
  }, [openNodeEdit]);

  /**
   * 节点操作菜单（图谱右键菜单与树形「···」共用）。
   * 每组动作都会先收起菜单，避免操作完成后菜单残留。
   */
  const menuItemsFor = useCallback(
    (node: OrgGraphNode): MenuProps['items'] => {
      const close = <T,>(fn: () => T) => () => {
        setMenu(null);
        fn();
      };
      const items: MenuProps['items'] = actionsFor(node).map((a) => ({
        key: a.key,
        label: a.label,
        icon: a.icon,
        onClick: close(a.onClick),
      }));
      items.push({ type: 'divider' });
      items.push({
        key: 'toggle',
        label: node.status === 0 ? '启用' : '停用',
        icon: node.status === 0 ? <CheckCircleOutlined /> : <StopOutlined />,
        onClick: close(() => void toggleStatus(node)),
      });
      items.push({
        key: 'delete',
        label: '删除',
        icon: <DeleteOutlined />,
        danger: true,
        onClick: close(() => handleDelete(node)),
      });
      return items;
    },
    [actionsFor, toggleStatus, handleDelete],
  );

  /** 树形视图数据：由 parentId 组装，带环保护（依赖维护动作，故置于其后） */
  const treeData = useMemo<OrgTreeNode[]>(() => {
    if (!graphData) return [];
    const ids = new Set(graphData.nodes.map((n) => n.id));
    const childrenOf = new Map<string, OrgGraphNode[]>();
    graphData.nodes.forEach((n) => {
      if (n.parentId && ids.has(n.parentId)) {
        childrenOf.set(n.parentId, [...(childrenOf.get(n.parentId) ?? []), n]);
      }
    });

    const titleOf = (node: OrgGraphNode): ReactNode => (
      <span className="group inline-flex items-center gap-2 min-w-0 w-full">
        <Tag color={NODE_META[node.nodeType]?.color} className="m-0 shrink-0">
          {NODE_META[node.nodeType]?.label ?? node.nodeType}
        </Tag>
        <span
          className={`truncate font-medium ${node.status === 0 ? 'text-gray-400' : 'text-gray-800'}`}
        >
          {node.name}
        </span>
        {node.subtitle && (
          <span className="text-xs text-gray-400 truncate">{node.subtitle}</span>
        )}
        {node.status === 0 && (
          <Tag color="default" className="m-0 shrink-0">
            已停用
          </Tag>
        )}
        {/* 悬浮显现的单一「···」入口：替代原先一排图标，降低视觉噪音 */}
        <span
          className="ml-auto inline-flex shrink-0 opacity-0 group-hover:opacity-100 focus-within:opacity-100 max-sm:opacity-100 transition-opacity"
          onClick={(e) => e.stopPropagation()}
        >
          <Dropdown
            trigger={['click']}
            placement="bottomRight"
            menu={{ items: menuItemsFor(node) }}
          >
            <Button
              size="small"
              type="text"
              icon={<MoreOutlined />}
              aria-label="节点操作"
              title="节点操作"
            />
          </Dropdown>
        </span>
      </span>
    );

    const build = (node: OrgGraphNode, visited: Set<string>): OrgTreeNode => {
      visited.add(node.id);
      const children = (childrenOf.get(node.id) ?? [])
        .filter((child) => !visited.has(child.id))
        .map((child) => build(child, new Set(visited)));
      return {
        key: node.id,
        title: titleOf(node),
        children: children.length > 0 ? children : undefined,
      };
    };

    return graphData.nodes
      .filter((n) => !n.parentId || !ids.has(n.parentId))
      .map((n) => build(n, new Set<string>()));
  }, [graphData, menuItemsFor]);

  const stats = graphData?.stats ?? {};

  return (
    <div className="space-y-3 min-w-0 max-w-full overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold text-gray-900 flex items-center gap-2 m-0">
            <ApartmentOutlined className="text-[var(--ams-primary)]" />
            组织架构图谱
          </h1>
          <p className="text-sm text-gray-500 mt-1 mb-0">
            母公司 → 子公司 → 部门 → 员工，可切换图谱/树形视图与布局方向。
            <span className="text-gray-400">
              图谱中右键节点、树形中悬浮节点可新增/编辑/停用/删除，双击节点快速编辑
            </span>
          </p>
        </div>
        <Space wrap size={[8, 8]}>
          <Radio.Group
            value={viewMode}
            optionType="button"
            buttonStyle="solid"
            options={VIEW_OPTIONS}
            onChange={(e) => setViewMode(e.target.value as 'graph' | 'tree')}
          />
          <Radio.Group
            value={layoutDir}
            optionType="button"
            buttonStyle="solid"
            options={LAYOUT_OPTIONS}
            disabled={viewMode !== 'graph'}
            onChange={(e) => setLayoutDir(e.target.value as 'TB' | 'LR')}
          />
          <Select
            allowClear
            style={{ minWidth: 200 }}
            placeholder="根节点（默认母公司）"
            value={rootId}
            showSearch
            optionFilterProp="label"
            options={companies.map((c) => ({ value: c.id, label: c.name }))}
            onChange={(v: number | undefined) => setRootId(v)}
          />
          <Select
            style={{ minWidth: 180 }}
            value={depth}
            options={DEPTH_OPTIONS}
            onChange={(v: number) => setDepth(v)}
          />
          <Tooltip title="适应画布">
            <Button icon={<ExpandOutlined />} onClick={handleFit} disabled={viewMode !== 'graph'} />
          </Tooltip>
          <Button icon={<ReloadOutlined />} onClick={() => void loadGraph()} loading={loading}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openCompanyForm()}>
            新增公司
          </Button>
        </Space>
      </div>

      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-sm">
        <span className="text-gray-500">统计</span>
        <Tag color="blue" className="m-0">
          公司 {String(stats.companyCount ?? 0)}
        </Tag>
        <Tag color="green" className="m-0">
          部门 {String(stats.departmentCount ?? 0)}
        </Tag>
        <Tag color="orange" className="m-0">
          员工 {String(stats.employeeCount ?? 0)}
        </Tag>
        <span className="text-gray-400 text-xs">
          节点 {String(stats.nodeCount ?? 0)} · 边 {String(stats.edgeCount ?? 0)}
          {stats.rootName ? ` · 根：${String(stats.rootName)}` : ''}
        </span>
        <span className="ml-auto flex flex-wrap items-center gap-2">
          {Object.entries(NODE_META).map(([key, meta]) => (
            <span key={key} className="inline-flex items-center gap-1 text-xs text-gray-500">
              <span
                className="inline-block w-3 h-3 rounded-sm"
                style={{ background: meta.color }}
              />
              {meta.label}
            </span>
          ))}
        </span>
      </div>

      {renderError && (
        <Alert
          type="error"
          showIcon
          message="图谱渲染失败"
          description={renderError}
          action={
            <Button size="small" onClick={() => void loadGraph()}>
              重试
            </Button>
          }
        />
      )}

      <div className="grid grid-cols-1 xl:grid-cols-[1fr_320px] gap-3 min-w-0">
        <div className="bg-white rounded-xl border border-[var(--ams-border)] min-w-0 overflow-hidden">
          <Spin spinning={loading}>
            {viewMode === 'graph' ? (
              <div
                className="relative w-full"
                style={{ height: 'calc(100dvh - 300px)', minHeight: 420 }}
              >
                <div ref={containerRef} className="w-full h-full" />
                {/* 右键节点菜单：绝对定位于点击处，故随画布滚动/缩放即时收起 */}
                {menu && (
                  <div
                    className="absolute z-50 min-w-[176px] py-1 bg-white rounded-lg border border-[var(--ams-border)] shadow-lg"
                    style={{ left: menu.x, top: menu.y }}
                    onClick={(e) => e.stopPropagation()}
                  >
                    <div className="px-3 pt-1 pb-1.5 text-[11px] text-gray-400 truncate border-b border-[var(--ams-border)]">
                      {NODE_META[menu.node.nodeType]?.label} · {menu.node.name}
                    </div>
                    <Menu
                      mode="vertical"
                      selectable={false}
                      className="!border-0 !shadow-none !bg-transparent"
                      items={menuItemsFor(menu.node)}
                    />
                  </div>
                )}
              </div>
            ) : (
              <div
                className="p-3 overflow-auto ams-scroll"
                style={{ height: 'calc(100dvh - 300px)', minHeight: 420 }}
              >
                {treeData.length > 0 ? (
                  <Tree
                    // 数据变化后重挂载，保证 defaultExpandAll 生效
                    key={`org-tree-${String(stats.nodeCount ?? 0)}-${String(rootId ?? 'all')}-${depth}`}
                    showLine
                    blockNode
                    defaultExpandAll
                    treeData={treeData}
                    selectedKeys={selected ? [selected.id] : []}
                    onSelect={(keys) => {
                      const id = keys[0];
                      setSelected(id ? (nodeById.get(String(id)) ?? null) : null);
                    }}
                  />
                ) : (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description="暂无可展示的组织节点"
                  />
                )}
              </div>
            )}
            {viewMode === 'graph' &&
              !loading &&
              !renderError &&
              (graphData?.nodes.length ?? 0) === 0 && (
                <div className="py-16">
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可展示的组织节点" />
                </div>
              )}
          </Spin>
        </div>

        <aside className="bg-white rounded-xl border border-[var(--ams-border)] p-3 min-w-0">
          {!selected ? (
            <div className="text-sm text-gray-400 py-10 text-center">
              <TeamOutlined className="block mx-auto mb-2 text-xl" />
              点击图谱中的节点查看详情
            </div>
          ) : (
            <div className="space-y-3 min-w-0">
              <div className="min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <Tag color={NODE_META[selected.nodeType]?.color} className="m-0">
                    {NODE_META[selected.nodeType]?.label ?? selected.nodeType}
                  </Tag>
                  {selected.status === 0 && (
                    <Tag color="default" className="m-0">
                      已停用
                    </Tag>
                  )}
                </div>
                <div className="text-base font-semibold text-gray-800 break-all">
                  {selected.name}
                </div>
                {selected.subtitle && (
                  <div className="text-xs text-gray-400 break-all">{selected.subtitle}</div>
                )}
              </div>

              <dl className="m-0 text-sm space-y-0">
                {Object.entries(selected.attrs ?? {}).map(([key, value]) => {
                  if (value == null || value === '') return null;
                  const label =
                    {
                      shortName: '简称',
                      companyType: '公司类型',
                      address: '地址',
                      phone: '联系电话',
                      createdAt: '创建时间',
                      updatedAt: '修改时间',
                      type: '部门类型',
                      leaderId: '负责人ID',
                      remark: '备注',
                      username: '账号',
                      departmentId: '部门ID',
                      lastLoginAt: '最近登录',
                      isRoot: '是否母公司',
                    }[key] ?? key;
                  const text =
                    key === 'createdAt' || key === 'updatedAt' || key === 'lastLoginAt'
                      ? formatTime(value)
                      : key === 'isRoot'
                        ? value
                          ? '是'
                          : '否'
                        : String(value);
                  return (
                    <div key={key} className="flex gap-2 border-b border-[var(--ams-border)] py-1.5">
                      <dt className="w-20 text-gray-500 shrink-0 truncate" title={label}>
                        {label}
                      </dt>
                      <dd className="flex-1 min-w-0 break-all m-0">{text}</dd>
                    </div>
                  );
                })}
              </dl>

              <div>
                <div className="text-xs text-gray-400 mb-1">
                  邻居（度 {neighbors?.degree ?? 0}）
                </div>
                {neighbors && (neighbors.incoming.length > 0 || neighbors.outgoing.length > 0) ? (
                  <ul className="m-0 p-0 list-none space-y-1">
                    {[...neighbors.incoming, ...neighbors.outgoing].map((nb) => {
                      const target = nodeById.get(nb.nodeId);
                      return (
                        <li
                          key={`${nb.edgeId}-${nb.direction}`}
                          className="flex items-center gap-2 text-xs min-w-0"
                        >
                          <Tag className="m-0 shrink-0" color={nb.direction === 'in' ? 'default' : 'blue'}>
                            {nb.direction === 'in' ? '← ' : '→ '}
                            {nb.label}
                          </Tag>
                          <button
                            type="button"
                            className="truncate text-left text-gray-600 hover:text-[var(--ams-primary)]"
                            title={target?.name ?? nb.nodeId}
                            onClick={() => {
                              const node = nodeById.get(nb.nodeId);
                              if (node) setSelected(node);
                            }}
                          >
                            {target?.name ?? nb.nodeId}
                          </button>
                        </li>
                      );
                    })}
                  </ul>
                ) : (
                  <div className="text-xs text-gray-400">无相连节点</div>
                )}
              </div>

              {selected && (
                <div className="pt-2 border-t border-[var(--ams-border)] space-y-2">
                  <div className="flex items-baseline justify-between gap-2">
                    <span className="text-xs font-medium text-gray-500 shrink-0">节点操作</span>
                    <span className="text-[11px] text-gray-300 truncate">
                      {viewMode === 'graph' ? '右键节点可快速操作' : '悬浮节点出现「···」'}
                    </span>
                  </div>
                  <Space wrap size={[6, 6]}>
                    {actionsFor(selected).map((a) => (
                      <Button key={a.key} size="small" icon={a.icon} onClick={a.onClick}>
                        {a.label}
                      </Button>
                    ))}
                  </Space>
                  {/* 状态类与危险操作单独一行，避免与新增/编辑混淆 */}
                  <div className="flex items-center gap-2 pt-2 border-t border-dashed border-[var(--ams-border)]">
                    <Button
                      size="small"
                      icon={
                        selected.status === 0 ? <CheckCircleOutlined /> : <StopOutlined />
                      }
                      onClick={() => void toggleStatus(selected)}
                    >
                      {selected.status === 0 ? '启用' : '停用'}
                    </Button>
                    <Button
                      size="small"
                      danger
                      icon={<DeleteOutlined />}
                      onClick={() => handleDelete(selected)}
                    >
                      删除
                    </Button>
                    <Button
                      size="small"
                      type="text"
                      className="ml-auto"
                      onClick={() => setSelected(null)}
                    >
                      清除选中
                    </Button>
                  </div>
                </div>
              )}
            </div>
          )}
        </aside>
      </div>

      <Modal
        title={formCtx?.title ?? '组织维护'}
        open={!!formCtx}
        onCancel={() => {
          setFormCtx(null);
          nodeForm.resetFields();
        }}
        onOk={() => void handleSubmitForm()}
        confirmLoading={submitting}
        destroyOnClose
        width={Math.min(560, typeof window !== 'undefined' ? window.innerWidth - 32 : 560)}
        styles={{ body: { maxHeight: '60vh', overflowY: 'auto' } }}
      >
        <Form form={nodeForm} layout="vertical" className="mt-2">
          {(formCtx?.fields ?? []).map((f) => (
            <Form.Item
              key={f.name}
              name={f.name}
              label={f.label}
              rules={f.required ? [{ required: true, message: `请填写${f.label}` }] : undefined}
            >
              {f.type === 'select' ? (
                <Select
                  allowClear
                  showSearch
                  optionFilterProp="label"
                  mode={f.multiple ? 'multiple' : undefined}
                  placeholder="请选择"
                  options={(f.options ?? []).map((o) => ({ value: o.value, label: o.label }))}
                />
              ) : f.type === 'textarea' ? (
                <Input.TextArea rows={2} />
              ) : f.type === 'number' ? (
                <InputNumber className="w-full" />
              ) : (
                <Input />
              )}
            </Form.Item>
          ))}
        </Form>
      </Modal>
    </div>
  );
}
