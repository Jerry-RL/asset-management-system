import { logger, APP_LOG_INGEST_PATH } from './log';
import type { ApiFailureInfo } from '@ams/log-sdk';

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

/**
 * 全链路 TraceId 请求头，与后端 `TraceIdFilter` 的约定逐字一致。
 *
 * <p>发出请求前生成（请求级，设计 D6），后端会复用它写 MDC —— 于是端侧这条
 * 「API 失败」与后端那次异常落在同一个 trace_id 下，查询页点 traceId 即可串起来。
 */
const TRACE_HEADER = 'X-Trace-Id';

/**
 * 上报 API 失败（设计 §6.1）。
 *
 * <p>三条硬约束：① 上报绝不影响业务请求（异常全吞）；② 不报上报接口自己
 * （见 {@link APP_LOG_INGEST_PATH}，否则自我放大）；③ 只在「服务端故障」时由调用方调用，
 * 业务 4xx 不报 —— 那是预期内的用户错误，报了会让 ERROR 告警失真。
 */
function reportApiFailure(info: ApiFailureInfo): void {
  if (info.path.startsWith(APP_LOG_INGEST_PATH)) return;
  try {
    logger.captureApiFailure(info);
  } catch {
    /* SDK 内部已吞异常，这里只兜「SDK 本身炸了」这一层 */
  }
}

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
  const traceId = logger.newTraceId();
  const headers = new Headers(options.headers);
  headers.set('Content-Type', 'application/json');
  headers.set('X-Client-Type', CLIENT_TYPE);
  headers.set(TRACE_HEADER, traceId);
  const token = getToken();
  if (token) headers.set('Authorization', `Bearer ${token}`);

  let resp: Response;
  try {
    resp = await fetch(`${API_BASE}${path}`, { ...options, headers });
  } catch (e) {
    // 网络层失败（断网 / DNS / 跨域）没有 HTTP 状态码，但同样是「用户点不动」的故障
    reportApiFailure({ traceId, method: options.method ?? 'GET', path, error: e });
    throw e;
  }

  let body: ApiResponse<T>;
  try {
    body = (await resp.json()) as ApiResponse<T>;
  } catch {
    // 网关返回 HTML 错误页等非 JSON 响应：此前这里会抛 SyntaxError 且什么都不上报，
    // 表现为「页面报了个看不懂的错」，是最需要 traceId 的一类故障
    reportApiFailure({
      traceId,
      method: options.method ?? 'GET',
      path,
      status: resp.status,
      message: '响应解析失败',
    });
    throw new ApiError(resp.status || 50000, resp.ok ? '响应解析失败' : `请求失败(${resp.status})`);
  }

  // 只报「服务端故障」：HTTP ≥ 500 或业务码 ≥ 50000（ErrorCode.INTERNAL_ERROR）。
  // 4xx / 业务校验失败不报 —— 那是预期内的用户错误。
  if (resp.status >= 500 || body.code >= 50000) {
    // 优先用响应体里的 traceId：后端因请求头非法自己另生成时，以它为准才对得上后端记录
    reportApiFailure({
      traceId: body.traceId ?? traceId,
      method: options.method ?? 'GET',
      path,
      status: resp.status,
      message: body.message,
    });
  }

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
