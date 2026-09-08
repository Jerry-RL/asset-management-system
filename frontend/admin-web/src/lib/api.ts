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
  const body = (await resp.json()) as ApiResponse<T>;
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
};
