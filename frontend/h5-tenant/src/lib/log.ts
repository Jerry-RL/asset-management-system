import { createLogger } from '@ams/log-sdk';

/**
 * 租户 H5 的日志 SDK 单例（设计 §6.1）。
 *
 * <p>与 admin-web 的同名模块是刻意的重复而不是抽公共包：两者只差 `appType`
 * 与「用户 ID 存在哪个 localStorage 键」，抽象出去要引入一个「应用标识」参数层，
 * 而各端 `appType` 本来就与自己的构建产物绑定，放在各自仓库里更不容易配错。
 *
 * <p>**不 import `@/lib/api`**：`api.ts` 需要 import 本模块上报失败，反向再 import 就成环。
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

/**
 * 自报用户 ID（设计 §5.2）：仅用于排查定位，后端**不以它作鉴权依据**
 * —— 上报接口完全免鉴权，任何字段都是用户可伪造的。
 *
 * <p>用 `?.` 而非解析错误兜底：H5 的会话快照可能被旧版本写入过，
 * 字段缺失是正常情况，拿不到 ID 就按未登录上报，不该因此丢掉整条日志。
 */
const readUserId = (): number | null => {
  try {
    const raw = localStorage.getItem('h5tenant.user');
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
  appType: 'h5-tenant',
  getUserId: readUserId,
  debug,
});
