import { useCallback, useEffect, useState } from 'react';
import { Button, Empty, Input, Pagination, Spin } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { api, type PageResult } from '@/lib/api';
import { useListQuery } from '@/lib/listQuery';

/** 项目行：资产统计字段由后端在列表里聚合（AssetService#fillProjectAssetStats），无需额外请求 */
interface ProjectRow {
  id: number;
  name: string;
  assetCount?: number;
  assetArea?: number;
}

export interface ProjectListPaneProps {
  /** 当前选中项目（来自 URL），null 表示未选 */
  selectedId: number | null;
  onSelect: (projectId: number) => void;
  /** asset.project:view：无权时不发请求，显示提示 */
  canView: boolean;
}

const PAGE_SIZE = 20;

const formatArea = (value: unknown) =>
  Number(value ?? 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

/**
 * 「项目分区管理」左栏：项目列表（设计 §6.4）。
 */
export function ProjectListPane({ selectedId, onSelect, canView }: ProjectListPaneProps) {
  /** 输入框草稿（本地）：与 URL 上已提交的关键字分离，避免每敲一个字都发请求 */
  const [keyword, setKeyword] = useState('');
  /**
   * 已提交的关键字与页码进 URL（设计 §4）：从本页跳去资产表单再返回时本组件会重新挂载，
   * 只在 state 里就会丢。加 `project` 前缀是因为同一 URL 上右栏资产表也有分页。
   */
  const {
    page,
    keyword: query,
    setPage,
    setKeyword: setQuery,
  } = useListQuery({ prefix: 'project', defaultPageSize: PAGE_SIZE });

  /** URL（前进/后退、外部链接）变化时把输入框同步回来 */
  useEffect(() => setKeyword(query), [query]);
  /** 重拉信号：关键字与页码都没变时（查询 / 刷新 / 重试）靠它触发一次请求 */
  const [reloadToken, setReloadToken] = useState(0);
  const [rows, setRows] = useState<ProjectRow[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);

  const load = useCallback(
    async (p: number, kw: string) => {
      if (!canView) return;
      setLoading(true);
      setLoadFailed(false);
      try {
        const params = new URLSearchParams({ page: String(p), pageSize: String(PAGE_SIZE) });
        if (kw) params.set('keyword', kw);
        // /projects 确定返回 PageResult；后端未返回 list 时按空列表处理，避免白屏
        const result = await api.get<PageResult<ProjectRow>>(`/projects?${params.toString()}`);
        setRows(result?.list ?? []);
        setTotal(Number(result?.total ?? 0));
      } catch {
        // 栏内错误态：不弹全局 message，右栏不受影响
        setRows([]);
        setTotal(0);
        setLoadFailed(true);
      } finally {
        setLoading(false);
      }
    },
    [canView],
  );

  useEffect(() => {
    void load(page, query);
  }, [load, page, query, reloadToken]);

  /** 查询：关键字写入 URL（setKeyword 内部把页码归 1）；关键字没变时靠 reloadToken 重拉 */
  const handleSearch = () => {
    if (query === keyword) setReloadToken((token) => token + 1);
    else setQuery(keyword);
  };

  if (!canView) {
    return (
      <div className="w-full lg:w-[280px] shrink-0 border border-[var(--ams-border)] rounded-lg bg-white p-4 text-sm text-gray-500">
        无项目查看权限
      </div>
    );
  }

  return (
    <div className="w-full lg:w-[280px] shrink-0 border border-[var(--ams-border)] rounded-lg bg-white p-3 flex flex-col min-w-0">
      <div className="flex items-center gap-2 mb-2">
        <Input
          allowClear
          size="small"
          placeholder="搜索项目名称"
          prefix={<SearchOutlined className="text-gray-400" />}
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onPressEnter={handleSearch}
        />
        <Button
          size="small"
          icon={<SearchOutlined />}
          onClick={handleSearch}
          aria-label="查询项目"
        />
        <Button
          size="small"
          icon={<ReloadOutlined />}
          onClick={() => setReloadToken((token) => token + 1)}
          aria-label="刷新项目列表"
        />
      </div>

      <div className="flex-1 min-h-0 overflow-auto">
        {loadFailed ? (
          <div className="py-6 text-center text-sm text-gray-500">
            项目加载失败
            <Button type="link" size="small" onClick={() => setReloadToken((token) => token + 1)}>
              重试
            </Button>
          </div>
        ) : loading ? (
          <div className="py-6 text-center">
            <Spin />
          </div>
        ) : rows.length === 0 ? (
          <Empty description="暂无项目" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <div className="space-y-1">
            {rows.map((row) => {
              const active = row.id === selectedId;
              return (
                <div
                  key={row.id}
                  role="button"
                  tabIndex={0}
                  aria-label={`选择项目 ${row.name}`}
                  aria-current={active ? 'true' : undefined}
                  onClick={() => onSelect(row.id)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      onSelect(row.id);
                    }
                  }}
                  className={`px-3 py-2 rounded cursor-pointer border transition-colors ${
                    active ? 'border-blue-200 bg-blue-50' : 'border-transparent hover:bg-gray-50'
                  }`}
                >
                  <div
                    className={`text-sm truncate ${
                      active ? 'text-blue-600 font-medium' : 'text-gray-800'
                    }`}
                    title={row.name}
                  >
                    {row.name}
                  </div>
                  <div className="text-xs text-gray-400 tabular-nums mt-0.5">
                    资产 {Number(row.assetCount ?? 0)} 宗 · {formatArea(row.assetArea)} ㎡
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      <div className="flex justify-center mt-2 pt-2 border-t border-[var(--ams-border)]">
        <Pagination
          size="small"
          current={page}
          pageSize={PAGE_SIZE}
          total={total}
          showSizeChanger={false}
          onChange={(p) => setPage(p)}
        />
      </div>
    </div>
  );
}
