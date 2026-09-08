import { useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

type LocationState = {
  from?: string;
};

/** 当前页完整路径（含 query），用于作为「返回来源」写入 location.state */
export const currentPath = (location: { pathname: string; search?: string }) =>
  `${location.pathname}${location.search ?? ''}`;

/**
 * 返回原始页面：优先 location.state.from → 浏览器历史上一页 → 兜底路径。
 * 进入详情页时请用 navigate(to, { state: { from: currentPath(location) } }) 传入来源。
 */
export function useBackNavigate(fallback: string) {
  const navigate = useNavigate();
  const location = useLocation();

  return useCallback(() => {
    const from = (location.state as LocationState | null)?.from;
    if (from && from !== currentPath(location)) {
      navigate(from);
      return;
    }
    const idx = (window.history.state as { idx?: number } | null)?.idx;
    if (typeof idx === 'number' && idx > 0) {
      navigate(-1);
      return;
    }
    navigate(fallback);
  }, [fallback, location, navigate]);
}
