import { useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { normalizeList } from '@/lib/org';

// ============================================================================
// 系统字典 / 远程下拉通用 Hook
//   表单属性统一取自「系统管理 → 系统字典 → 资产管理字典」，
//   字典项动态维护后，使用这些 Hook 的下拉会自动同步，无需改代码。
// ============================================================================

export type SelectOption = { value: string | number; label: string };

/**
 * 远程下拉：path 为 null 表示依赖字段未选，暂不请求。
 * valueKey / labelKey 指定后端返回体的取值字段（如字典项为 value / label）。
 */
export const useRemoteOptions = (path: string | null, valueKey = 'id', labelKey = 'name') => {
  const [options, setOptions] = useState<SelectOption[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!path) {
      setOptions([]);
      return;
    }
    let cancelled = false;
    setLoading(true);
    // 依赖变化（path 改变）时先清空，避免级联下拉在新选项到达前仍可选中上一级的旧选项
    setOptions([]);
    api
      .get<unknown>(path)
      .then((raw) => {
        if (cancelled) return;
        setOptions(
          normalizeList<Record<string, unknown>>(raw).map((item) => ({
            value: item[valueKey] as string | number,
            label: String(item[labelKey] ?? item[valueKey]),
          })),
        );
      })
      .catch(() => {
        if (!cancelled) setOptions([]);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [path, valueKey, labelKey]);

  return { options, loading };
};

/**
 * 系统字典下拉：字典项 value / label（GET /system/dict/items?code=xxx）。
 *
 * @param code 字典编码；为空表示暂不拉取（返回空选项），便于按配置条件调用
 * @param cascade 字典项级级联：父字典编码 + 父字典取值。
 *        父级取值为空时不启用过滤（后端返回该字典全量项）
 */
export const useDictOptions = (
  code: string | null | undefined,
  cascade?: { parentCode: string; parentValue?: string | number | null },
) => {
  const parentCode = cascade?.parentCode;
  const parentValue = cascade?.parentValue;
  let path: string | null = null;
  if (code) {
    const params = new URLSearchParams({ code });
    if (parentCode && parentValue !== undefined && parentValue !== null && parentValue !== '') {
      params.set('parentCode', parentCode);
      params.set('parentValue', String(parentValue));
    }
    path = `/system/dict/items?${params.toString()}`;
  }
  return useRemoteOptions(path, 'value', 'label');
};

/** 字典项列表（数组 / PageResult）→ { value: label } 映射 */
export const dictItemsToLabelMap = (raw: unknown): Record<string, string> => {
  const map: Record<string, string> = {};
  normalizeList<Record<string, unknown>>(raw).forEach((item) => {
    if (item.value == null) return;
    map[String(item.value)] = String(item.label ?? item.value);
  });
  return map;
};

/**
 * 批量字典映射：codes 为字典编码列表，返回 { [code]: { [value]: label } }。
 * 用于列表列 / 详情按字典值动态回显（如项目类型），一次请求合并多个字典，
 * 字典维护后自动同步；单个字典请求失败时降级为空映射，不影响其余列展示。
 */
export const useDictLabelMaps = (codes: string[]): Record<string, Record<string, string>> => {
  // 用稳定字符串做依赖，避免调用方每次渲染传新数组导致重复请求
  const key = [...codes].sort().join(',');
  const [maps, setMaps] = useState<Record<string, Record<string, string>>>({});

  useEffect(() => {
    const list = key ? key.split(',') : [];
    if (list.length === 0) {
      setMaps({});
      return;
    }
    let cancelled = false;
    void Promise.all(
      list.map((code) =>
        api
          .get<unknown>(`/system/dict/items?code=${code}`)
          .then((raw) => [code, dictItemsToLabelMap(raw)] as const)
          .catch(() => [code, {} as Record<string, string>] as const),
      ),
    ).then((entries) => {
      if (cancelled) return;
      setMaps(Object.fromEntries(entries));
    });
    return () => {
      cancelled = true;
    };
  }, [key]);

  return maps;
};
