import { createLogger } from '@ams/log-sdk';

/**
 * admin-web 的日志 SDK 单例（设计 §6.1）。
 *
 * <p>为什么单独一个模块而不是在 `api.ts` 里 `createLogger`：`main.tsx`（装全局钩子）
 * 与 `api.ts`（上报 API 失败）都要用同一个实例。若各自建一个，去重表就是两份 ——
 * 同一个错误在「API 失败」与「全局钩子」两条路径下会各自发一次，客户端去重形同虚设。
 *
 * <p>**不 import `@/lib/api`**：`api.ts` 需要 import 本模块上报失败，反向再 import 就成环。
 * 用户 ID 直接读 `localStorage` 快照，不值得为此引入一个环。
 */

/** 上报地址：与 `api.ts` 的 `API_BASE` 同源，走相同的代理 / 反代链路 */
export const APP_LOG_ENDPOINT = '/api/v1/public/app-logs';

/**
 * 上报接口自身的路径（相对 `API_BASE`）。
 *
 * <p>`request()` 见到它就不再上报失败：日志上报失败若再触发一次日志上报，
 * 就是自我放大的死循环（且后端限流会把它变成 429 风暴）。必须在最外层断开。
 * 当前 SDK 走裸 `fetch` 不经过 `request()`，这里是防止后人「顺手改成用 api.post」的护栏。
 */
export const APP_LOG_INGEST_PATH = '/public/app-logs';

/**
 * 自报用户 ID（设计 §5.2）：仅用于排查时定位「谁碰上了」，后端**不以它作鉴权依据**
 * —— 端侧上报完全免鉴权，任何字段都是用户可伪造的。
 */
const readUserId = (): number | null => {
  try {
    const raw = localStorage.getItem('ams.user');
    if (!raw) return null;
    const userId = (JSON.parse(raw) as { userId?: unknown }).userId;
    return typeof userId === 'number' ? userId : null;
  } catch {
    // 会话损坏（半截 JSON）时不该让日志上报跟着崩；拿不到 ID 就当作未登录上报
    return null;
  }
};

/**
 * 开发态默认 `debug=true`：本地跑出来的报错（含 Vite HMR 引发的各种怪错）
 * 不该进任何数据库 —— 真进过一次，排查线上问题时就得先分辨「哪些是同事本机的」。
 *
 * <p>需要在本地做联调验证时用 `VITE_LOG_SDK_DEBUG=0 pnpm dev` 显式打开上报。
 */
const debug = import.meta.env.DEV && import.meta.env.VITE_LOG_SDK_DEBUG !== '0';

export const logger = createLogger({
  endpoint: APP_LOG_ENDPOINT,
  appType: 'admin-web',
  getUserId: readUserId,
  debug,
});
