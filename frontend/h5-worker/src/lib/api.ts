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
  id: number;
  username: string;
  name: string;
  companyId?: number;
  tenantId?: number;
  roles: string[];
}

const API_BASE = '/api/v1';
const CLIENT_TYPE = 'worker-mp';

export class ApiError extends Error {
  code: number;
  constructor(code: number, message: string) {
    super(message);
    this.code = code;
  }
}

function getToken(): string | null {
  return localStorage.getItem('h5worker.token');
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set('Content-Type', 'application/json');
  headers.set('X-Client-Type', CLIENT_TYPE);
  const token = getToken();
  if (token) headers.set('Authorization', `Bearer ${token}`);
  const resp = await fetch(`${API_BASE}${path}`, { ...options, headers });
  const body = (await resp.json()) as ApiResponse<T>;
  if (body.code !== 0) {
    if (body.code === 40100 || body.code === 40101) {
      localStorage.removeItem('h5worker.token');
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
};
