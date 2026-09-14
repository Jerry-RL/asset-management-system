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
  userId: number;
  username: string;
  name: string;
  roles: string[];
  permissions: string[];
  /**
   * 是否超级管理员（由后端 `LoginUser.isSuperAdmin()` 序列化而来）。
   *
   * <p>可选：老会话的 `localStorage` 快照里没有这个字段，缺失时按 `false` 处理（安全侧）。
   */
  superAdmin?: boolean;
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

/**
 * 全链路 TraceId 请求头，与后端 `TraceIdFilter` 的约定逐字一致。
 *
 * <p>字面量只在这里出现一次：三处 fetch（普通 / 上传 / 下载）都从这里取，
 * 免得某天改头名时漏掉一处 —— 漏掉的那条链路会静默失去全链路关联能力。
 */
const TRACE_HEADER = 'X-Trace-Id';

/**
 * 上报 API 失败（设计 §6.1）。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li>**上报本身绝不能影响业务请求** —— 全部包在 try/catch 里，异常吞掉。</li>
 *   <li>**不报上报接口自己** —— 见 {@link APP_LOG_INGEST_PATH}，否则会自我放大。</li>
 *   <li>只由调用方在「服务端故障」时调用（HTTP ≥ 500 或业务码 ≥ 50000），
 *       4xx 与业务校验失败不在此列。</li>
 * </ol>
 */
function reportApiFailure(info: ApiFailureInfo): void {
  if (info.path.startsWith(APP_LOG_INGEST_PATH)) return;
  try {
    logger.captureApiFailure(info);
  } catch {
    /* SDK 内部已吞异常，这里只兜「SDK 本身炸了」这一层，保证绝不冒泡到业务 */
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
  // 请求级 traceId（设计 D6）：每次 API 调用一个新 ID，随 X-Trace-Id 发给后端。
  // 后端 TraceIdFilter 会复用它写 MDC，于是「端侧这条失败」与「后端那次异常」
  // 落在同一个 trace_id 下 —— 查询页点 traceId 就能把整条链路捞出来。
  const traceId = logger.newTraceId();
  const headers = buildHeaders(options);
  headers.set(TRACE_HEADER, traceId);

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
    reportApiFailure({
      traceId,
      method: options.method ?? 'GET',
      path,
      status: resp.status,
      message: '响应解析失败',
    });
    throw new ApiError(resp.status || 50000, resp.ok ? '响应解析失败' : `请求失败(${resp.status})`);
  }
  // 非业务包装的 HTTP 错误（如 Spring 默认 404 JSON）
  if (typeof body.code !== 'number') {
    throw new ApiError(
      resp.status || 50000,
      (body as { message?: string }).message || `请求失败(${resp.status})`,
    );
  }
  // 只报「服务端故障」：HTTP ≥ 500 或业务码 ≥ 50000（ErrorCode.INTERNAL_ERROR）。
  // 4xx / 业务校验失败不报 —— 那是预期内的用户错误，报了会让 ERROR 告警全是噪声。
  if (resp.status >= 500 || body.code >= 50000) {
    // 优先用响应体里的 traceId：后端可能因为请求头格式非法自己另生成了一个，
    // 此时以它为准才能对上后端那条 app_log（宁丢关联，不丢日志）。
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
    const traceId = logger.newTraceId();
    const headers = buildHeaders(undefined, false);
    headers.set(TRACE_HEADER, traceId);
    let resp: Response;
    try {
      resp = await fetch(`${API_BASE}${path}`, { method: 'POST', headers, body: formData });
    } catch (e) {
      reportApiFailure({ traceId, method: 'POST', path, error: e });
      throw e;
    }
    let body: ApiResponse<T>;
    try {
      body = (await resp.json()) as ApiResponse<T>;
    } catch {
      reportApiFailure({
        traceId,
        method: 'POST',
        path,
        status: resp.status,
        message: '上传响应解析失败',
      });
      throw new ApiError(
        resp.status || 50000,
        resp.ok ? '响应解析失败' : `上传失败(${resp.status})`,
      );
    }
    if (typeof body.code !== 'number') {
      throw new ApiError(resp.status || 50000, `上传失败(${resp.status})`);
    }
    if (resp.status >= 500 || body.code >= 50000) {
      reportApiFailure({
        traceId: body.traceId ?? traceId,
        method: 'POST',
        path,
        status: resp.status,
        message: body.message,
      });
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
    const traceId = logger.newTraceId();
    const headers = buildHeaders(undefined, false);
    headers.set(TRACE_HEADER, traceId);
    let resp: Response;
    try {
      resp = await fetch(`${API_BASE}${path}`, { headers });
    } catch (e) {
      // 与 request()/upload() 同口径：网络层失败也要可见，否则「点导出没反应」查不到任何线索
      reportApiFailure({ traceId, method: 'GET', path, error: e });
      throw e;
    }
    if (!resp.ok) {
      let msg = `下载失败(${resp.status})`;
      try {
        const body = (await resp.json()) as ApiResponse;
        if (body?.message) msg = body.message;
        if (resp.status >= 500 || (body?.code ?? 0) >= 50000) {
          reportApiFailure({
            traceId: body?.traceId ?? traceId,
            method: 'GET',
            path,
            status: resp.status,
            message: body?.message ?? msg,
          });
        }
      } catch {
        // 响应不是 JSON（网关 HTML 错误页等）：仍按服务端故障上报，否则这类故障完全不可见
        if (resp.status >= 500) {
          reportApiFailure({ traceId, method: 'GET', path, status: resp.status, message: msg });
        }
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
