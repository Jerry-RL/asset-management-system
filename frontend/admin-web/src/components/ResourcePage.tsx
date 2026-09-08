import { useEffect, useMemo, useState } from 'react';
import { api, type PageResult } from '@/lib/api';

export interface FieldConfig {
  name: string;
  label: string;
  type?: 'text' | 'number' | 'date' | 'select' | 'textarea' | 'boolean';
  options?: { value: string; label: string }[];
  required?: boolean;
}

export interface ColumnConfig {
  key: string;
  label: string;
  map?: Record<string, string>;
  render?: (row: Record<string, unknown>) => React.ReactNode;
}

export interface FilterConfig {
  key: string;
  label: string;
  options?: { value: string; label: string }[];
}

export interface ResourceConfig {
  title: string;
  listPath: string;
  detailPath?: (id: number) => string;
  create?: boolean;
  update?: boolean;
  deletable?: boolean;
  idField?: string;
  columns: ColumnConfig[];
  fields?: FieldConfig[];
  filters?: FilterConfig[];
  /** 额外固定查询参数 */
  extraParams?: Record<string, string>;
}

type Row = Record<string, unknown>;

export function ResourcePage({ config }: { config: ResourceConfig }) {
  const [data, setData] = useState<PageResult<Row>>({ list: [], total: 0, page: 1, pageSize: 10 });
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<Row | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState<Record<string, unknown>>({});
  const [error, setError] = useState('');

  const idField = config.idField ?? 'id';

  const load = async (p = page, kw = keyword, flt = filters) => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ page: String(p), pageSize: '10' });
      if (kw) params.set('keyword', kw);
      Object.entries(flt).forEach(([k, v]) => {
        if (v) params.set(k, v);
      });
      Object.entries(config.extraParams ?? {}).forEach(([k, v]) => params.set(k, v));
      const sep = config.listPath.includes('?') ? '&' : '?';
      const data = await api.get<PageResult<Row>>(`${config.listPath}${sep}${params.toString()}`);
      setData(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load(1, '', {});
  }, [config.listPath]);

  const openDetail = async (row: Row) => {
    const id = row[idField] as number;
    if (config.detailPath && id != null) {
      try {
        setDetail(await api.get<Row>(config.detailPath(id)));
      } catch {
        setDetail(row);
      }
    } else {
      setDetail(row);
    }
  };

  const handleCreate = async () => {
    try {
      await api.post(config.listPath, form);
      setShowCreate(false);
      setForm({});
      load(1);
    } catch (e) {
      setError(e instanceof Error ? e.message : '创建失败');
    }
  };

  const handleDelete = async (row: Row) => {
    const id = row[idField] as number;
    if (id == null) return;
    if (!window.confirm('确认删除？')) return;
    try {
      await api.del(`${config.listPath}/${id}`);
      load(page);
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败');
    }
  };

  const columns = useMemo(() => config.columns, [config.columns]);

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-lg font-semibold">{config.title}</h2>
        <div className="flex gap-2">
          {(config.filters ?? []).map((f) => (
            <select
              key={f.key}
              className="border rounded px-2 py-1.5 text-sm"
              value={filters[f.key] ?? ''}
              onChange={(e) => {
                const nf = { ...filters, [f.key]: e.target.value };
                setFilters(nf);
                setPage(1);
                load(1, keyword, nf);
              }}
            >
              <option value="">{f.label}</option>
              {(f.options ?? []).map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          ))}
          <input
            className="border rounded px-3 py-1.5 w-56"
            placeholder="搜索关键字"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                setPage(1);
                load(1);
              }
            }}
          />
          {config.create && (
            <button
              className="bg-blue-600 text-white rounded px-3 py-1.5 hover:bg-blue-700"
              onClick={() => setShowCreate(true)}
            >
              新增
            </button>
          )}
        </div>
      </div>

      {error && <p className="text-red-500 text-sm mb-2">{error}</p>}

      <div className="bg-white rounded-lg shadow overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-left">
            <tr>
              {columns.map((c) => (
                <th key={c.key} className="px-4 py-2 whitespace-nowrap">
                  {c.label}
                </th>
              ))}
              {(config.deletable || config.detailPath) && <th className="px-4 py-2">操作</th>}
            </tr>
          </thead>
          <tbody>
            {loading && (
              <tr>
                <td colSpan={columns.length + 1} className="px-4 py-8 text-center text-gray-400">
                  加载中...
                </td>
              </tr>
            )}
            {!loading &&
              data.list.map((row, i) => (
                <tr
                  key={(row[idField] as number) ?? i}
                  className="border-t hover:bg-gray-50 cursor-pointer"
                  onClick={() => openDetail(row)}
                >
                  {columns.map((c) => (
                    <td key={c.key} className="px-4 py-2 whitespace-nowrap">
                      {c.render
                        ? c.render(row)
                        : c.map
                          ? (c.map[String(row[c.key])] ?? String(row[c.key] ?? '-'))
                          : String(row[c.key] ?? '-')}
                    </td>
                  ))}
                  {(config.deletable || config.detailPath) && (
                    <td className="px-4 py-2" onClick={(e) => e.stopPropagation()}>
                      <button className="text-blue-600 text-xs" onClick={() => openDetail(row)}>
                        详情
                      </button>
                      {config.deletable && (
                        <button
                          className="text-red-500 text-xs ml-3"
                          onClick={() => handleDelete(row)}
                        >
                          删除
                        </button>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            {!loading && data.list.length === 0 && (
              <tr>
                <td colSpan={columns.length + 1} className="px-4 py-8 text-center text-gray-400">
                  暂无数据
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="flex justify-end mt-4 gap-2 text-sm">
        <button
          className="border rounded px-3 py-1 disabled:opacity-40"
          disabled={page <= 1}
          onClick={() => {
            const p = page - 1;
            setPage(p);
            load(p);
          }}
        >
          上一页
        </button>
        <span className="px-3 py-1">
          共 {data.total} 条 · 第 {page} 页
        </span>
        <button
          className="border rounded px-3 py-1 disabled:opacity-40"
          disabled={page * data.pageSize >= data.total}
          onClick={() => {
            const p = page + 1;
            setPage(p);
            load(p);
          }}
        >
          下一页
        </button>
      </div>

      {/* 详情抽屉 */}
      {detail && (
        <div className="fixed inset-0 bg-black/30 z-40" onClick={() => setDetail(null)}>
          <div
            className="absolute right-0 top-0 h-full w-[480px] bg-white shadow-xl p-6 overflow-auto"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex justify-between items-center mb-4">
              <h3 className="text-base font-semibold">{config.title} · 详情</h3>
              <button className="text-gray-400 hover:text-gray-600" onClick={() => setDetail(null)}>
                ✕
              </button>
            </div>
            <dl className="space-y-2 text-sm">
              {Object.entries(detail).map(([k, v]) => {
                const col = columns.find((c) => c.key === k);
                const label = col?.label ?? k;
                const display = col?.map ? (col.map[String(v)] ?? String(v)) : String(v ?? '-');
                return (
                  <div key={k} className="flex border-b py-1.5">
                    <dt className="w-32 text-gray-500 shrink-0">{label}</dt>
                    <dd className="flex-1 break-all">{display}</dd>
                  </div>
                );
              })}
            </dl>
          </div>
        </div>
      )}

      {/* 新增弹窗 */}
      {showCreate && (
        <div className="fixed inset-0 bg-black/30 z-40 flex items-center justify-center">
          <div className="bg-white rounded-lg shadow-xl p-6 w-[520px] max-h-[80vh] overflow-auto">
            <h3 className="text-base font-semibold mb-4">新增{config.title}</h3>
            <div className="space-y-3">
              {(config.fields ?? []).map((f) => (
                <div key={f.name}>
                  <label className="block text-sm text-gray-600 mb-1">
                    {f.label}
                    {f.required && <span className="text-red-500"> *</span>}
                  </label>
                  {f.type === 'select' ? (
                    <select
                      className="w-full border rounded px-3 py-2"
                      value={String(form[f.name] ?? '')}
                      onChange={(e) => setForm({ ...form, [f.name]: e.target.value })}
                    >
                      <option value="">请选择</option>
                      {(f.options ?? []).map((o) => (
                        <option key={o.value} value={o.value}>
                          {o.label}
                        </option>
                      ))}
                    </select>
                  ) : f.type === 'textarea' ? (
                    <textarea
                      className="w-full border rounded px-3 py-2"
                      rows={3}
                      value={String(form[f.name] ?? '')}
                      onChange={(e) => setForm({ ...form, [f.name]: e.target.value })}
                    />
                  ) : f.type === 'boolean' ? (
                    <input
                      type="checkbox"
                      checked={Boolean(form[f.name])}
                      onChange={(e) => setForm({ ...form, [f.name]: e.target.checked })}
                    />
                  ) : (
                    <input
                      type={f.type === 'number' ? 'number' : f.type === 'date' ? 'date' : 'text'}
                      className="w-full border rounded px-3 py-2"
                      value={String(form[f.name] ?? '')}
                      onChange={(e) =>
                        setForm({
                          ...form,
                          [f.name]: f.type === 'number' ? Number(e.target.value) : e.target.value,
                        })
                      }
                    />
                  )}
                </div>
              ))}
            </div>
            {error && <p className="text-red-500 text-sm mt-2">{error}</p>}
            <div className="flex justify-end gap-2 mt-5">
              <button
                className="border rounded px-4 py-2"
                onClick={() => {
                  setShowCreate(false);
                  setForm({});
                }}
              >
                取消
              </button>
              <button className="bg-blue-600 text-white rounded px-4 py-2" onClick={handleCreate}>
                保存
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
