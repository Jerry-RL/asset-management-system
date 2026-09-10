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

/** 全局公司切换：当前生效公司 ID 的存储键；缺省 / 空表示「全部公司」（不限公司） */
export const COMPANY_STORAGE_KEY = 'ams.companyId';
/** 全局公司切换：当前生效公司名称（顶栏展示用，仅缓存，不作为鉴权依据） */
export const COMPANY_NAME_STORAGE_KEY = 'ams.companyName';
/** 全局公司切换请求头，后端据此收敛数据范围（服务端会二次校验） */
const COMPANY_HEADER = 'X-Company-Id';

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

/** 读取当前生效公司 ID；无值或非法时返回 null（表示不限制公司） */
export function getActiveCompanyId(): number | null {
  const raw = localStorage.getItem(COMPANY_STORAGE_KEY);
  if (!raw) return null;
  const id = Number(raw);
  return Number.isFinite(id) && id > 0 ? id : null;
}

/**
 * 写入当前生效公司；companyId 为 null 表示「全部公司」（不发送请求头）。
 * name 仅用于顶栏回显，不作为鉴权依据。
 */
export function setActiveCompanyStorage(companyId: number | null, name?: string): void {
  if (companyId == null) {
    localStorage.removeItem(COMPANY_STORAGE_KEY);
  } else {
    localStorage.setItem(COMPANY_STORAGE_KEY, String(companyId));
  }
  if (name) localStorage.setItem(COMPANY_NAME_STORAGE_KEY, name);
}

/** 退出登录 / 切换账号时清空公司选择，避免把上一个账号的公司带进新会话 */
export function clearCompanySelection(): void {
  localStorage.removeItem(COMPANY_STORAGE_KEY);
  localStorage.removeItem(COMPANY_NAME_STORAGE_KEY);
}

/** 统一附加鉴权与公司切换请求头（普通请求 / 上传 / 下载三条链路共用） */
function buildHeaders(options?: RequestInit, json = true): Headers {
  const headers = new Headers(options?.headers);
  if (json) headers.set('Content-Type', 'application/json');
  const token = getToken();
  if (token) headers.set('Authorization', `Bearer ${token}`);
  const companyId = getActiveCompanyId();
  if (companyId != null) headers.set(COMPANY_HEADER, String(companyId));
  return headers;
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = buildHeaders(options);
  const resp = await fetch(`${API_BASE}${path}`, { ...options, headers });
  let body: ApiResponse<T>;
  try {
    body = (await resp.json()) as ApiResponse<T>;
  } catch {
    throw new ApiError(resp.status || 50000, resp.ok ? '响应解析失败' : `请求失败(${resp.status})`);
  }
  // 非业务包装的 HTTP 错误（如 Spring 默认 404 JSON）
  if (typeof body.code !== 'number') {
    throw new ApiError(
      resp.status || 50000,
      (body as { message?: string }).message || `请求失败(${resp.status})`,
    );
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
  /**
   * 上传文件（multipart/form-data）。
   * 不能走 request()：它会强制 Content-Type: application/json 并 JSON 序列化 body，
   * 破坏 multipart 的 boundary。
   */
  upload: async <T>(path: string, formData: FormData): Promise<T> => {
    const headers = buildHeaders(undefined, false);
    const resp = await fetch(`${API_BASE}${path}`, { method: 'POST', headers, body: formData });
    let body: ApiResponse<T>;
    try {
      body = (await resp.json()) as ApiResponse<T>;
    } catch {
      throw new ApiError(
        resp.status || 50000,
        resp.ok ? '响应解析失败' : `上传失败(${resp.status})`,
      );
    }
    if (typeof body.code !== 'number') {
      throw new ApiError(resp.status || 50000, `上传失败(${resp.status})`);
    }
    if (body.code !== 0) {
      if (body.code === 40100 || body.code === 40101) {
        localStorage.removeItem('ams.accessToken');
        window.location.href = '/login';
      }
      throw new ApiError(body.code, body.message);
    }
    return body.data;
  },
  /** 带鉴权下载二进制文件（Word 导出等） */
  download: async (path: string, fallbackName = 'download.bin') => {
    const headers = buildHeaders(undefined, false);
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
