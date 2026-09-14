import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';

/**
 * 分区楼层（project_zone_floor，V50）行数据。
 *
 * <p>`assetCount` / `assetArea` 是后端按 `asset.floor_no` 汇总出来的**只读**字段：
 * 楼层面积不接受人工维护（与 `ProjectZone.assetArea` 同口径），所以本表没有面积字段。
 */
export interface ProjectZoneFloor {
  /**
   * 楼层记录 id。
   *
   * <p>**可能为 null**：后端返回的是「楼层行 ∪ 资产实际用到的楼层号」的并集，
   * 仅来自资产的楼层还没有记录（资产表单允许手填楼层号）。
   * 这类楼层不可编辑 / 不可删除，前端据此把按钮置灰。
   */
  id?: number | null;
  zoneId?: number;
  /** 楼层号，负数表示地下层（B1 = -1） */
  floorNo: number;
  name?: string | null;
  remark?: string | null;
  /** 只读：本层资产宗数 */
  assetCount?: number;
  /** 只读：本层资产面积合计(㎡) */
  assetArea?: number;
}

/** 楼层展示名：无名称时回落为「3F」—— 与后端 ProjectZoneFloorService#displayName 同一规则 */
export const floorLabel = (floor: Pick<ProjectZoneFloor, 'floorNo' | 'name'>): string => {
  const name = floor.name?.trim();
  return name ? name : `${floor.floorNo}F`;
};

export interface UseProjectZoneFloorsResult {
  floors: ProjectZoneFloor[];
  loading: boolean;
  /** 加载失败：由调用方在栏内渲染错误态 + 重试，不弹全局 message */
  loadFailed: boolean;
  /** 新增/编辑提交中：驱动弹窗的 confirmLoading */
  saving: boolean;
  reload: () => Promise<void>;
  /** 无 id → POST，有 id → PUT。成功后已 reload；失败抛异常由调用方提示 */
  save: (floor: ProjectZoneFloor) => Promise<void>;
  /** 删除。失败抛异常（后端拒绝原因必须原样透出） */
  remove: (floor: ProjectZoneFloor) => Promise<void>;
}

/**
 * 分区楼层的读写：`ZoneAssetPane` 楼层 Tab 栏的唯一实现。
 *
 * <p>`zoneId` 为空时**不发请求**、直接返回空列表 —— 「全部分区」下楼层无从谈起
 * （楼层是分区的从属结构），打一条 `/zones//floors` 只会 404。
 *
 * <p>与 `useProjectZones` 一样，加载失败不弹全局 `message`：楼层只是页面的一部分，
 * 整页弹错会掩盖「只有楼层栏失败」的事实。
 */
export function useProjectZoneFloors(
  projectId: number | null | undefined,
  zoneId: number | null | undefined,
): UseProjectZoneFloorsResult {
  const [floors, setFloors] = useState<ProjectZoneFloor[]>([]);
  const [loading, setLoading] = useState(projectId != null && zoneId != null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [saving, setSaving] = useState(false);

  const reload = useCallback(async () => {
    if (projectId == null || zoneId == null) {
      setFloors([]);
      setLoading(false);
      setLoadFailed(false);
      return;
    }
    setLoading(true);
    setLoadFailed(false);
    try {
      const list = await api.get<ProjectZoneFloor[]>(
        `/projects/${projectId}/zones/${zoneId}/floors`,
      );
      setFloors(Array.isArray(list) ? list : []);
    } catch {
      setFloors([]);
      setLoadFailed(true);
    } finally {
      setLoading(false);
    }
  }, [projectId, zoneId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const save = useCallback(
    async (floor: ProjectZoneFloor) => {
      // 无归属可挂时**抛错**而不是静默 return：调用方是「await 之后无条件报保存成功」，
      // 静默返回会让一次没发生的写入被报成成功
      if (projectId == null || zoneId == null) {
        throw new Error('请先选中一个具体分区，再维护楼层');
      }
      setSaving(true);
      try {
        if (floor.id != null) {
          await api.put(`/projects/${projectId}/zones/${zoneId}/floors/${floor.id}`, floor);
        } else {
          await api.post(`/projects/${projectId}/zones/${zoneId}/floors`, floor);
        }
        await reload();
      } finally {
        setSaving(false);
      }
    },
    [projectId, zoneId, reload],
  );

  const remove = useCallback(
    async (floor: ProjectZoneFloor) => {
      // 同上：静默 return 会让调用方把一次没发生的删除报成「已删除」。
      // id 为空说明这是「仅来自资产的楼层」，本就没有记录可删
      if (projectId == null || zoneId == null || floor.id == null) {
        throw new Error('该楼层不存在或未选中分区，无法删除');
      }
      await api.del(`/projects/${projectId}/zones/${zoneId}/floors/${floor.id}`);
      await reload();
    },
    [projectId, zoneId, reload],
  );

  return { floors, loading, loadFailed, saving, reload, save, remove };
}
