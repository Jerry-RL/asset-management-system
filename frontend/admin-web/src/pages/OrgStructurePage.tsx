import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Empty, Select, Space, Spin, Tag, Tooltip, message } from 'antd';
import {
  ApartmentOutlined,
  ExpandOutlined,
  ReloadOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import { Graph } from '@antv/g6';
import { api } from '@/lib/api';

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

const formatTime = (value: unknown) => {
  if (!value) return '-';
  const text = String(value);
  return text.length >= 19 ? text.slice(0, 19).replace('T', ' ') : text;
};

/** G6 的 datum.data 为 Record<string, unknown>，统一经 unknown 收窄为业务类型 */
const datumOf = <T,>(d: unknown): T | undefined => (d as { data?: T } | undefined)?.data;

export function OrgStructurePage() {
  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Graph | null>(null);

  const [companies, setCompanies] = useState<Company[]>([]);
  const [rootId, setRootId] = useState<number | undefined>(undefined);
  const [depth, setDepth] = useState<number>(3);
  const [graphData, setGraphData] = useState<OrgGraph | null>(null);
  const [loading, setLoading] = useState(false);
  const [renderError, setRenderError] = useState<string | null>(null);
  const [selected, setSelected] = useState<OrgGraphNode | null>(null);
  const [neighbors, setNeighbors] = useState<{
    incoming: NeighborView[];
    outgoing: NeighborView[];
    degree: number;
  } | null>(null);

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
    if (!container || !graphData) return;

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
            fill: (d) => NODE_META[datumOf<OrgGraphNode>(d)?.nodeType ?? '']?.color ?? '#8c8c8c',
            stroke: '#fff',
            lineWidth: 1.5,
            shadowColor: 'rgba(15,23,42,0.12)',
            shadowBlur: 6,
            labelText: (d) => String(datumOf<OrgGraphNode>(d)?.name ?? ''),
            labelFill: '#fff',
            labelFontSize: 12,
            labelFontWeight: 500,
            labelPlacement: 'center',
            labelWordWrap: true,
            labelMaxWidth: 150,
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
          rankdir: 'TB',
          nodesep: 18,
          ranksep: 70,
        },
        behaviors: ['zoom-canvas', 'drag-canvas', 'drag-element'],
        animation: false,
      });

      graph.on('node:click', (e: unknown) => {
        const evt = e as { target?: { id?: string }; targetId?: string };
        const nodeId = evt.target?.id ?? evt.targetId;
        if (nodeId) setSelected(nodeById.get(nodeId) ?? null);
      });

      graph.render();
      graphRef.current = graph;
      setRenderError(null);
    } catch (e) {
      setRenderError(e instanceof Error ? e.message : '图谱渲染失败');
    }

    return () => {
      if (graphRef.current) {
        graphRef.current.destroy();
        graphRef.current = null;
      }
    };
  }, [graphData, nodeById]);

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
            母公司 → 子公司 → 部门 → 员工，点击节点查看属性与邻居
          </p>
        </div>
        <Space wrap size={[8, 8]}>
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
            <Button icon={<ExpandOutlined />} onClick={handleFit} />
          </Tooltip>
          <Button icon={<ReloadOutlined />} onClick={() => void loadGraph()} loading={loading}>
            刷新
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
            <div
              ref={containerRef}
              className="w-full"
              style={{ height: 'calc(100dvh - 300px)', minHeight: 420 }}
            />
            {!loading && !renderError && (graphData?.nodes.length ?? 0) === 0 && (
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

              <Button size="small" block onClick={() => setSelected(null)}>
                清除选中
              </Button>
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}
