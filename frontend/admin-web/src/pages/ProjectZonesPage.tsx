import { useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { PartitionOutlined } from '@ant-design/icons';
import { usePerm } from '@/lib/perm';
import { ProjectListPane } from '@/components/ProjectListPane';
import { ASSET_PAGE_PARAM, FLOOR_NO_PARAM, ZoneAssetPane } from '@/components/ZoneAssetPane';

/** URL 数字参数解析：缺失 / 非纯数字 / 非正数一律视为「未选中」 */
const toPositiveInt = (raw: string | null): number | null => {
  // 用正则而不是 `Number.isFinite`：后者会把 '1.5' / '1e3' / '0x10' 都当成合法数字，
  // 拼出 `zoneId=1.5` 这类请求（后端 400，页面进错误态）
  if (!raw || !/^\d+$/.test(raw)) return null;
  const value = Number(raw);
  return value > 0 ? value : null;
};

/**
 * 楼层号解析：**允许 0 与负数**（地下层 B1 = -1）。
 *
 * <p>不能复用 `toPositiveInt`：那会把地下层参数整条丢掉，表现为
 * 「从 B1 楼层 Tab 刷新页面后回到全部楼层」。
 */
const toFloorNo = (raw: string | null): number | null => {
  if (!raw || !/^-?\d+$/.test(raw)) return null;
  const value = Number(raw);
  return Number.isSafeInteger(value) ? value : null;
};

/**
 * 项目分区管理（设计 §5 / §6.6）：左侧项目、右上分区 Tab、右下资产。
 *
 * <p>本组件只持有「跨栏共享」的两件事：URL 上的选中态与左右布局。
 * 左侧的项目列表、右侧的分区与资产读写都在各自组件里，避免一个文件承担全部状态。
 *
 * <p><strong>选中态与各栏分页都以 URL query 为唯一真相</strong>：跳去资产表单页再返回时本页会整页
 * 重新挂载，state 全部丢失，选中项与页码不落在 URL 上就回不来。
 */
export function ProjectZonesPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const can = usePerm();
  const canViewProject = can('asset.project', 'view');

  const projectId = toPositiveInt(searchParams.get('projectId'));
  /**
   * `zoneId` **必须挂在 `projectId` 之下**：`/project-zones?zoneId=5` 这种孤儿参数会让 URL
   * 与右栏（显示「请先在左侧选择项目」）长期不一致，且右栏无法清掉它 —— `selectZone`
   * 在无项目时提前 return，只有用户真的选了项目才会被 `selectProject` 覆盖。
   */
  const zoneId = projectId == null ? null : toPositiveInt(searchParams.get('zoneId'));
  /**
   * `floorNo` **必须挂在 `zoneId` 之下**（V50）：楼层是分区的从属结构，
   * `/project-zones?projectId=1&floorNo=3` 这种没有分区的楼层参数无从解释，
   * 而且右栏 `selectFloor` 在无分区时提前 return，自己清不掉它。
   */
  const floorNo = zoneId == null ? null : toFloorNo(searchParams.get(FLOOR_NO_PARAM));

  /**
   * 切换项目：**不保留 `zoneId`**（回到「全部分区」，spec §5.2 第 1 条），
   * 并清掉右栏资产分页（它与分区强相关，换项目后没有意义）。
   *
   * <p>用合并式写入而不是整包替换：左栏项目的 `projectPage` / `projectKeyword` 必须保留，
   * 整包替换会把左栏的搜索与分页一并抹掉。用 replace 写入：切换项目/Tab 不该在浏览器
   * 历史里留下每一步。
   */
  const selectProject = useCallback(
    (id: number) =>
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          next.set('projectId', String(id));
          next.delete('zoneId');
          next.delete(FLOOR_NO_PARAM);
          next.delete(ASSET_PAGE_PARAM);
          return next;
        },
        { replace: true },
      ),
    [setSearchParams],
  );

  /**
   * 切换分区：null 表示「全部分区」，对应 URL 上 `zoneId` 缺省。
   * 同样清掉右栏资产分页与**楼层选中**（spec §5.2 第 2 条：分页归 1、重拉）——
   * 楼层号只在原分区内成立，跟着换分区就会指向另一个分区里同号的楼层。
   */
  const selectZone = useCallback(
    (id: number | null) => {
      if (projectId == null) return;
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          next.set('projectId', String(projectId));
          if (id == null) next.delete('zoneId');
          else next.set('zoneId', String(id));
          next.delete(FLOOR_NO_PARAM);
          next.delete(ASSET_PAGE_PARAM);
          return next;
        },
        { replace: true },
      );
    },
    [projectId, setSearchParams],
  );

  /**
   * 切换楼层：null 表示「全部楼层」，对应 URL 上 `floorNo` 缺省。
   * 与切分区同一处理：分页归 1（楼层过滤下的页码与上层无关）。
   */
  const selectFloor = useCallback(
    (value: number | null) => {
      if (projectId == null || zoneId == null) return;
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          next.set('projectId', String(projectId));
          next.set('zoneId', String(zoneId));
          if (value == null) next.delete(FLOOR_NO_PARAM);
          else next.set(FLOOR_NO_PARAM, String(value));
          next.delete(ASSET_PAGE_PARAM);
          return next;
        },
        { replace: true },
      );
    },
    [projectId, zoneId, setSearchParams],
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
        <ProjectListPane selectedId={projectId} onSelect={selectProject} canView={canViewProject} />
        <ZoneAssetPane
          projectId={projectId}
          zoneId={zoneId}
          onZoneChange={selectZone}
          floorNo={floorNo}
          onFloorChange={selectFloor}
        />
      </div>
    </div>
  );
}
