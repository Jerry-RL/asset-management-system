import { useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { PartitionOutlined } from '@ant-design/icons';
import { usePerm } from '@/lib/perm';
import { ProjectListPane } from '@/components/ProjectListPane';
import { ZoneAssetPane } from '@/components/ZoneAssetPane';

/** URL 数字参数解析：缺失 / 非法 / 非正数一律视为「未选中」 */
const toPositiveInt = (raw: string | null): number | null => {
  if (!raw) return null;
  const value = Number(raw);
  return Number.isFinite(value) && value > 0 ? value : null;
};

/**
 * 项目分区管理（设计 §5 / §6.6）：左侧项目、右上分区 Tab、右下资产。
 *
 * <p>本组件只持有「跨栏共享」的两件事：URL 上的选中态与左右布局。
 * 左侧的项目列表、右侧的分区与资产读写都在各自组件里，避免一个文件承担全部状态。
 *
 * <p><strong>选中态以 URL query 为唯一真相</strong>：跳去资产表单页再返回时本页会整页
 * 重新挂载，state 全部丢失，选中项不落在 URL 上就回不来。
 */
export function ProjectZonesPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const can = usePerm();
  const canViewProject = can('asset.project', 'view');

  const projectId = toPositiveInt(searchParams.get('projectId'));
  const zoneId = toPositiveInt(searchParams.get('zoneId'));

  /**
   * 切换项目：**不保留 `zoneId`**，即回到「全部分区」（spec §5.2 第 1 条）。
   * 用 replace 写入：切换项目/Tab 不该在浏览器历史里留下每一步。
   */
  const selectProject = useCallback(
    (id: number) => setSearchParams({ projectId: String(id) }, { replace: true }),
    [setSearchParams],
  );

  /** 切换分区：null 表示「全部分区」，对应 URL 上 `zoneId` 缺省 */
  const selectZone = useCallback(
    (id: number | null) => {
      if (projectId == null) return;
      setSearchParams(
        id == null
          ? { projectId: String(projectId) }
          : { projectId: String(projectId), zoneId: String(id) },
        { replace: true },
      );
    },
    [projectId, setSearchParams],
  );

  return (
    <div className="space-y-3 min-w-0">
      <h2 className="text-base font-semibold m-0 flex items-center gap-2 min-w-0">
        <span className="inline-flex items-center justify-center w-7 h-7 rounded-md bg-blue-50 text-[var(--ams-primary)] text-sm shrink-0">
          <PartitionOutlined />
        </span>
        <span className="truncate">项目分区管理</span>
      </h2>

      <div className="flex flex-col lg:flex-row gap-3 min-w-0 items-stretch">
        <ProjectListPane
          selectedId={projectId}
          onSelect={selectProject}
          canView={canViewProject}
        />
        <ZoneAssetPane projectId={projectId} zoneId={zoneId} onZoneChange={selectZone} />
      </div>
    </div>
  );
}
