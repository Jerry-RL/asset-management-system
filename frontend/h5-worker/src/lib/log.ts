import { createLogger } from '@ams/log-sdk';

/**
 * 员工 H5 的日志 SDK 单例（设计 §6.1）。
 *
 * <p>与 h5-tenant 的差异只有 `appType` 与用户 ID 的存储键；其余说明见该文件的注释。
 * **不 import `@/lib/api`**：`api.ts` 需要 import 本模块上报失败，反向再 import 就成环。
 */

/** 上报地址：与 `api.ts` 的 `API_BASE` 同源，走相同代理 / 反代链路 */
export const APP_LOG_ENDPOINT = '/api/v1/public/app-logs';

/**
 * 上报接口自身的路径（相对 `API_BASE`）。
 *
 * <p>`request()` 见到它就不再上报失败：日志上报失败若再触发一次日志上报，就是自我
 * 放大的死循环。当前 SDK 走裸 `fetch` 不经过 `request()`，这里是防后人改动的护栏。
 */
export const APP_LOG_INGEST_PATH = '/public/app-logs';

/** 自报用户 ID（仅排查定位用，后端不以它作鉴权依据 —— 上报接口完全免鉴权） */
const readUserId = (): number | null => {
  try {
    const raw = localStorage.getItem('h5worker.user');
    if (!raw) return null;
    const id = (JSON.parse(raw) as { id?: unknown }).id;
    return typeof id === 'number' ? id : null;
  } catch {
    return null;
  }
};

/** 开发态默认只打印不发请求，避免把本机报错灌进任何数据库（见 admin-web 同名说明） */
const debug = import.meta.env.DEV && import.meta.env.VITE_LOG_SDK_DEBUG !== '0';

export const logger = createLogger({
  endpoint: APP_LOG_ENDPOINT,
  appType: 'h5-worker',
  getUserId: readUserId,
  debug,
});
