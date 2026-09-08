export interface ApiResponse<T = unknown> {
  code: number;
  message: string;
  data: T;
  traceId?: string;
}

export interface PageResult<T> {
  list: T[];
  total: number;
  page: number;
  pageSize: number;
}

export interface LoginUser {
  userId: number;
  username: string;
  name: string;
  roles: string[];
  permissions: string[];
}

export interface Asset {
  id: number;
  assetNo: string;
  name: string;
  assetType: string;
  area: number;
  leaseControlStatus: string;
  province?: string;
  city?: string;
  district?: string;
}

const API_BASE = '/api/v1';

export class ApiError extends Error {
  code: number;
  constructor(code: number, message: string) {
    super(message);
    this.code = code;
  }
}

function getToken(): string | null {
  return localStorage.getItem('ams.accessToken');
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set('Content-Type', 'application/json');
  const token = getToken();
  if (token) headers.set('Authorization', `Bearer ${token}`);
  const resp = await fetch(`${API_BASE}${path}`, { ...options, headers });
  let body: ApiResponse<T>;
  try {
    body = (await resp.json()) as ApiResponse<T>;
  } catch {
    throw new ApiError(resp.status || 50000, resp.ok ? '响应解析失败' : `请求失败(${resp.status})`);
  }
  // 非业务包装的 HTTP 错误（如 Spring 默认 404 JSON）
  if (typeof body.code !== 'number') {
    throw new ApiError(resp.status || 50000, (body as { message?: string }).message || `请求失败(${resp.status})`);
  }
  if (body.code !== 0) {
    if (body.code === 40100 || body.code === 40101) {
      localStorage.removeItem('ams.accessToken');
      window.location.href = '/login';
    }
    throw new ApiError(body.code, body.message);
  }
  return body.data;
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, data?: unknown) =>
    request<T>(path, { method: 'POST', body: JSON.stringify(data ?? {}) }),
  put: <T>(path: string, data?: unknown) =>
    request<T>(path, { method: 'PUT', body: JSON.stringify(data ?? {}) }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  /** 带鉴权下载二进制文件（Word 导出等） */
  download: async (path: string, fallbackName = 'download.bin') => {
    const headers = new Headers();
    const token = getToken();
    if (token) headers.set('Authorization', `Bearer ${token}`);
    const resp = await fetch(`${API_BASE}${path}`, { headers });
    if (!resp.ok) {
      let msg = `下载失败(${resp.status})`;
      try {
        const body = (await resp.json()) as ApiResponse;
        if (body?.message) msg = body.message;
      } catch {
        /* ignore */
      }
      throw new ApiError(resp.status, msg);
    }
    const blob = await resp.blob();
    const cd = resp.headers.get('Content-Disposition') ?? '';
    const matched = /filename="?([^";]+)"?/i.exec(cd);
    const fileName = matched?.[1] ?? fallbackName;
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    a.click();
    URL.revokeObjectURL(url);
    return fileName;
  },
};
