import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';

/**
 * 项目分区（project_zone）行数据。
 *
 * <p>`assetArea` / `assetCount` 是后端汇总出来的**只读**字段：分区面积不接受人工维护，
 * 统一取该分区下资产面积合计（见 V25 迁移与 AssetService#fillZoneAssetStats）。
 *
 * <p>**这是全仓唯一一份 `ProjectZone` 定义**：`ProjectZonesPanel.tsx` 与 `ProjectFormPage.tsx`
 * 曾各写一份，字段口径靠人工同步必然漂移；三者（含「项目分区管理」页）共用同一批接口，
 * 类型必须同源。
 */
export interface ProjectZone {
  id?: number;
  projectId?: number;
  name: string;
  code?: string;
  sort?: number;
  remark?: string;
  /** 只读：该分区下资产面积合计(㎡) */
  assetArea?: number;
  /** 只读：该分区下资产数量 */
  assetCount?: number;
}

export interface UseProjectZonesResult {
  zones: ProjectZone[];
  loading: boolean;
  /** 加载失败：由调用方在栏内渲染错误态 + 重试，不弹全局 message */
  loadFailed: boolean;
  /** 新增/编辑提交中：驱动弹窗的 confirmLoading */
  saving: boolean;
  /** 重新拉取；失败置 loadFailed，不抛异常 */
  reload: () => Promise<void>;
  /** 无 id → POST，有 id → PUT。成功后已 reload；失败抛异常由调用方提示 */
  save: (zone: ProjectZone) => Promise<void>;
  /** 删除。失败抛异常（后端拒绝原因必须原样透出） */
  remove: (zone: ProjectZone) => Promise<void>;
}

/**
 * 项目分区的读写（设计 §6.2）：`ProjectZonesPanel` 与「项目分区管理」页共用的唯一实现。
 *
 * <p>`projectId` 为空时**不发请求**、直接返回空列表 —— 页面未选项目、或调用方无权查看项目时
 * 都不该打出一条注定失败/403 的请求。
 *
 * <p>加载失败刻意不弹全局 `message`：分区只是页面的一部分（展开行、右栏），
 * 整页弹错会掩盖「只有这一块失败」的事实。
 */
export function useProjectZones(projectId: number | null | undefined): UseProjectZonesResult {
  const [zones, setZones] = useState<ProjectZone[]>([]);
  const [loading, setLoading] = useState(projectId != null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [saving, setSaving] = useState(false);

  const reload = useCallback(async () => {
    if (projectId == null) {
      setZones([]);
      setLoading(false);
      setLoadFailed(false);
      return;
    }
    setLoading(true);
    setLoadFailed(false);
    try {
      const list = await api.get<ProjectZone[]>(`/projects/${projectId}/zones`);
      setZones(Array.isArray(list) ? list : []);
    } catch {
      setZones([]);
      setLoadFailed(true);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const save = useCallback(
    async (zone: ProjectZone) => {
      // 无项目可挂时**抛错**而不是静默 return：调用方是「await 之后无条件报保存成功」，
      // 静默返回会让一次没发生的写入被报成成功（与上面「失败抛异常」的契约也自相矛盾）
      if (projectId == null) {
        throw new Error('未选择项目，无法保存分区');
      }
      setSaving(true);
      try {
        if (zone.id != null) {
          await api.put(`/projects/${projectId}/zones/${zone.id}`, zone);
        } else {
          await api.post(`/projects/${projectId}/zones`, zone);
        }
        await reload();
      } finally {
        setSaving(false);
      }
    },
    [projectId, reload],
  );

  const remove = useCallback(
    async (zone: ProjectZone) => {
      // 同上：静默 return 会让调用方把一次没发生的删除报成「已删除」
      if (projectId == null || zone.id == null) {
        throw new Error('该分区不存在或未选择项目，无法删除');
      }
      await api.del(`/projects/${projectId}/zones/${zone.id}`);
      await reload();
    },
    [projectId, reload],
  );

  return { zones, loading, loadFailed, saving, reload, save, remove };
}
