"use strict";
/**
 * 应用日志端侧 SDK（`@ams/log-sdk`）。
 *
 * 设计：docs/superpowers/specs/2026-09-12-app-log-observability-design.md §5
 *
 * ## 只做三件事
 *   `js`      —— JS 运行时错误（web: `window.onerror`）
 *   `promise` —— Promise 未捕获（web: `unhandledrejection`）
 *   `api`     —— API 失败（由各端 `api.ts` / `utils/api.js` 调用）
 *
 * 不做业务埋点 `track()`、不做性能埋点。原因见设计 §1.3：后端 `operation_log` 已记录
 * 接口级业务动作，端侧再埋一遍是重复。
 *
 * ## 单文件 + 环境探测
 * web 与小程序共用同一份逻辑（`typeof window` / `typeof wx` 探测），避免两套实现漂移。
 * 小程序用的是 tsc 产出的 CommonJS 单文件（`dist/log-sdk.mp.js`），因此：
 *   - 零运行时依赖；
 *   - 不使用 `import.meta`、顶层 await 等 ESM 专有语法；
 *   - 不引用 DOM 类型（`lib` 里没有 DOM），所有宿主对象都经 `typeof` 守卫后从 `globalThis` 取。
 *
 * ## 失败即丢弃
 * 上报失败不做重试队列（设计 D5 / §5.5）：本期全是 ERROR 且量小，而重试队列需要
 * 持久化、去重、上限、退避一整套，收益远不抵复杂度。
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.createLogger = exports.stackOf = exports.describeUnknown = exports.capExtra = exports.truncate = exports.clientFingerprint = exports.newTraceId = exports.isMiniProgram = void 0;
// ---------------------------------------------------------------------------
// 上限（与后端 AppLogIngestGuard 的截断值一致）
//
// 客户端先截断有两个好处：请求体不会白白超限被 413；真正需要的信息（message 开头、
// 栈头）完整保留。后端仍会再截一次 —— 客户端上限不可信。
// ---------------------------------------------------------------------------
const MAX_MESSAGE = 2000;
const MAX_URL = 512;
const MAX_UA = 512;
const MAX_EXTRA_BYTES = 16 * 1024;
/** 指纹只取栈头，与后端 AppLogFingerprint.STACK_HEAD_LENGTH 同口径。 */
const STACK_HEAD = 200;
/**
 * 去重表上限。
 *
 * 必须有上限：长驻页面（H5 挂一整天）可能产生成千上万个不同错误，
 * 无上限的 Map 就是一条内存泄漏 —— 而「日志 SDK 把页面搞崩」比没有日志糟糕得多。
 */
const MAX_TRACKED_FINGERPRINTS = 200;
const host = globalThis;
/** 微信小程序环境。 */
const isMiniProgram = () => typeof host.wx !== 'undefined' && typeof host.wx.request === 'function';
exports.isMiniProgram = isMiniProgram;
/** 浏览器环境（有 window 与 fetch）。 */
const isBrowser = () => typeof host.window !== 'undefined' && typeof host.fetch === 'function';
// ---------------------------------------------------------------------------
// 纯工具函数（导出以便单测直接断言）
// ---------------------------------------------------------------------------
/**
 * 生成 32 位 hex traceId，与后端 `TraceIdFilter` 的 `UUID.randomUUID().replace("-","")` 同格式。
 *
 * 同格式很重要：后端只做「有没有传」的判断，若端侧格式和后端生成的不一致，
 * 排查时无法从格式上区分「这是端侧生成的」还是「后端补的」。
 */
const newTraceId = () => {
    const uuid = host.crypto?.randomUUID?.();
    if (typeof uuid === 'string') {
        return uuid.replace(/-/g, '');
    }
    // 兜底：小程序与老浏览器没有 randomUUID
    return (Date.now().toString(16).padStart(12, '0') + Math.random().toString(16).slice(2, 22)).slice(0, 32);
};
exports.newTraceId = newTraceId;
/**
 * 错误指纹，**只用于客户端去重，不上报、不参与服务端口径**。
 *
 * 服务端会自己算 `app_log.fingerprint`（sha-256）：客户端可伪造指纹，若以它作告警冷却键，
 * 攻击者每次上报换一个指纹就能把告警刷爆。
 *
 * 这里用 djb2 而不是 Web Crypto：`crypto.subtle` 是异步的、小程序上还不可用，
 * 而去重只需要「同样输入给同样结果」，不需要抗碰撞。
 */
const clientFingerprint = (source, appType, message, stack) => {
    const canonical = [source, appType, message, (stack || '').slice(0, STACK_HEAD)].join('\u0001');
    let hash = 5381;
    for (let i = 0; i < canonical.length; i += 1) {
        // hash * 33 + c，用 |0 保持在 32 位内
        hash = ((hash << 5) + hash + canonical.charCodeAt(i)) | 0;
    }
    return (hash >>> 0).toString(16).padStart(8, '0');
};
exports.clientFingerprint = clientFingerprint;
/** 截断到上限（超长就切，不丢弃 —— 前半段才是排查线索）。 */
const truncate = (value, max) => value.length <= max ? value : value.slice(0, max);
exports.truncate = truncate;
/**
 * 把 extra 收敛到 16KB 以内。
 *
 * 与后端同口径：超限**整块丢弃**并置 `{truncated:true}`，而不是截断成半个 JSON
 * —— 半个 JSON 无法解析，展示出来只会误导排查者。
 */
const capExtra = (extra) => {
    if (!extra)
        return undefined;
    const keys = Object.keys(extra);
    if (keys.length === 0)
        return undefined;
    let json;
    try {
        json = JSON.stringify(extra);
    }
    catch {
        // 循环引用等无法序列化的结构：不能因此丢掉整条日志
        return { unserializable: true };
    }
    if (json === undefined)
        return { unserializable: true };
    // 用 UTF-8 字节数而不是字符数：后端按字节算上限，中文会让字符数低估 3 倍
    if (utf8Length(json) > MAX_EXTRA_BYTES) {
        return { truncated: true };
    }
    return extra;
};
exports.capExtra = capExtra;
const utf8Length = (value) => {
    let bytes = 0;
    for (let i = 0; i < value.length; i += 1) {
        const code = value.charCodeAt(i);
        if (code < 0x80)
            bytes += 1;
        else if (code < 0x800)
            bytes += 2;
        else if (code >= 0xd800 && code <= 0xdbff) {
            // 代理对：两个 char 组成一个 4 字节字符，跳过低位代理
            bytes += 4;
            i += 1;
        }
        else
            bytes += 3;
    }
    return bytes;
};
/** 从任意抛出物里取出「错误信息」，避免把对象序列化成 `[object Object]`。 */
const describeUnknown = (value) => {
    if (value === null || value === undefined)
        return String(value);
    if (value instanceof Error) {
        return value.message ? `${value.name}: ${value.message}` : value.name;
    }
    if (typeof value === 'string')
        return value;
    if (typeof value === 'object') {
        try {
            const json = JSON.stringify(value);
            return json ?? String(value);
        }
        catch {
            return String(value);
        }
    }
    return String(value);
};
exports.describeUnknown = describeUnknown;
/** 从任意抛出物里取出堆栈（无则空串）。 */
const stackOf = (value) => {
    if (value instanceof Error && typeof value.stack === 'string')
        return value.stack;
    return '';
};
exports.stackOf = stackOf;
const createLogger = (options) => {
    const { endpoint, appType, getUserId, debug = false, dedupeWindowMs = 5000, now = () => Date.now(), } = options;
    /**
     * 去重表：指纹 → 上次发送时间 + 已抑制计数。
     *
     * 语义（设计 §5.4）：同指纹在窗口内只发第一条，其余只累加计数；
     * 被抑制的次数**不丢弃也不触发额外请求**，而是在下一次同指纹上报时通过
     * `extra.suppressed` 带出并清零。
     *
     * 代价是「爆发后彻底安静」时最后一次计数不会上报 —— 可接受，因为爆发期的第一条已入库，
     * 而多留一个定时器专门补报，成本大于收益。
     */
    const dedupe = new Map();
    const evictIfNeeded = () => {
        if (dedupe.size <= MAX_TRACKED_FINGERPRINTS)
            return;
        // Map 保持插入顺序，删最旧的若干条即可（够用，不必做 LRU）
        const excess = dedupe.size - MAX_TRACKED_FINGERPRINTS;
        let removed = 0;
        for (const key of dedupe.keys()) {
            dedupe.delete(key);
            removed += 1;
            if (removed >= excess)
                break;
        }
    };
    const currentUserAgent = () => {
        const ua = host.navigator?.userAgent;
        return typeof ua === 'string' ? (0, exports.truncate)(ua, MAX_UA) : undefined;
    };
    const currentUrl = () => {
        const href = host.location?.href;
        return typeof href === 'string' ? (0, exports.truncate)(href, MAX_URL) : undefined;
    };
    const send = (payload) => {
        if (debug) {
            // debug 模式只打印，完全不发请求：本地开发不该往库里灌测试数据。
            // 走 host.console 而不是裸 console：本文件刻意不引用 DOM 类型（见文件头），
            // 所有宿主对象都经 typeof 守卫后从 globalThis 取。
            const consoleRef = host.console;
            if (consoleRef && typeof consoleRef.error === 'function') {
                consoleRef.error('[log-sdk]', payload);
            }
            return;
        }
        if ((0, exports.isMiniProgram)()) {
            // 小程序必须用绝对地址，且失败直接丢弃（低流量，不做重试队列）
            host.wx.request({
                url: endpoint,
                method: 'POST',
                data: payload,
                header: { 'Content-Type': 'application/json' },
                fail: () => undefined,
            });
            return;
        }
        if (!isBrowser())
            return;
        const body = JSON.stringify(payload);
        try {
            host
                .fetch(endpoint, {
                method: 'POST',
                body,
                // keepalive 让请求在页面卸载后仍能完成 —— 错误常发生在卸载/跳转时刻
                keepalive: true,
                headers: { 'Content-Type': 'application/json' },
            })
                .catch(() => {
                // 页面卸载场景兜底；再失败就丢弃
                beaconFallback(body);
            });
        }
        catch {
            beaconFallback(body);
        }
    };
    const beaconFallback = (body) => {
        const sendBeacon = host.navigator?.sendBeacon;
        if (typeof sendBeacon !== 'function')
            return;
        try {
            sendBeacon.call(host.navigator, endpoint, new host.Blob([body], { type: 'application/json' }));
        }
        catch {
            // 连兜底都失败：丢弃。日志系统不能用重试队列把自己变成页面负担
        }
    };
    /**
     * 统一出口：算指纹、去重、截断、组装 payload、发送。
     *
     * 所有捕获入口（error / rejection / api）都汇聚到这里，保证三类的
     * 去重与截断口径完全一致 —— 分散实现是漂移的温床。
     *
     * @param traceIdOverride 用哪个 traceId 落库。**API 失败必须传请求自己的 traceId**：
     *   这是「前端报错 ↔ 后端异常」能被一次查询串起来的前提。若这里另生成一个，
     *   两条记录会落在不同 traceId 下，全链路查询永远只看到半条链路。
     *   `js` / `promise` 没有对应请求，用新生成的即可。
     */
    const emit = (source, message, stack, extra, traceIdOverride) => {
        const safeMessage = (0, exports.truncate)(message, MAX_MESSAGE);
        const fingerprint = (0, exports.clientFingerprint)(source, appType, safeMessage, stack);
        const timestamp = now();
        const state = dedupe.get(fingerprint);
        if (state && timestamp - state.lastSentAt < dedupeWindowMs) {
            state.suppressed += 1;
            return;
        }
        const suppressed = state?.suppressed ?? 0;
        dedupe.delete(fingerprint);
        dedupe.set(fingerprint, { lastSentAt: timestamp, suppressed: 0 });
        evictIfNeeded();
        const mergedExtra = { ...(extra ?? {}) };
        if (suppressed > 0) {
            mergedExtra.suppressed = suppressed;
        }
        if (stack) {
            // 栈头交给服务端算指纹（服务端口径 = sha256(source|appType|message|栈前200字符)），
            // 因此这里必须把栈带上去，否则同一个 message 的无数缺陷会被并成一个指纹
            mergedExtra.stack = stack;
        }
        const payload = {
            traceId: traceIdOverride || (0, exports.newTraceId)(),
            level: 'ERROR',
            appType,
            source,
            message: safeMessage,
            occurredAt: new Date(timestamp).toISOString(),
        };
        const capped = (0, exports.capExtra)(mergedExtra);
        if (capped)
            payload.extra = capped;
        const ua = currentUserAgent();
        if (ua)
            payload.ua = ua;
        const url = currentUrl();
        if (url)
            payload.url = url;
        const userId = getUserId?.();
        if (typeof userId === 'number')
            payload.userId = userId;
        send(payload);
    };
    return {
        newTraceId: exports.newTraceId,
        install() {
            // 只在 web 装钩子。小程序请用 `App({ onError, onUnhandledRejection })`
            // （见设计 §6.2）：那里是最完整的捕获点，而两处都装会让同一个错误走两条路径
            // —— 虽然去重会拦住重复上报，但「同一个错误装了两个钩子」本身就是维护负担。
            if (!isBrowser() || typeof host.addEventListener !== 'function') {
                return () => undefined;
            }
            const onError = (event) => {
                // 资源加载错误（img/script）没有 error 对象，且几乎不是代码缺陷；只收 JS 错误
                const error = event?.error ?? event?.reason;
                const message = error
                    ? (0, exports.describeUnknown)(error)
                    : (0, exports.truncate)(String(event?.message ?? 'unknown error'), MAX_MESSAGE);
                emit('js', message, (0, exports.stackOf)(error), {
                    filename: event?.filename,
                    lineno: event?.lineno,
                    colno: event?.colno,
                });
            };
            const onRejection = (event) => {
                const reason = event?.reason;
                emit('promise', (0, exports.describeUnknown)(reason), (0, exports.stackOf)(reason), undefined);
            };
            host.addEventListener('error', onError);
            host.addEventListener('unhandledrejection', onRejection);
            return () => {
                host.removeEventListener?.('error', onError);
                host.removeEventListener?.('unhandledrejection', onRejection);
            };
        },
        captureError(err, extra) {
            emit('js', (0, exports.describeUnknown)(err), (0, exports.stackOf)(err), extra);
        },
        captureRejection(reason, extra) {
            emit('promise', (0, exports.describeUnknown)(reason), (0, exports.stackOf)(reason), extra);
        },
        captureApiFailure(info) {
            const status = info.status ? `：${info.status}` : '';
            const detail = info.error ?? info.message;
            const message = detail
                ? `${info.method} ${info.path} 失败${status}：${(0, exports.describeUnknown)(detail)}`
                : `${info.method} ${info.path} 失败${status}`;
            // 用请求自己的 traceId 落库（`info.traceId`），这样查询页能用它把
            // 「端侧 api 失败」与「后端异常」两行一起捞出来。仅在缺省时才退化为新生成。
            emit('api', message, (0, exports.stackOf)(info.error), {
                method: info.method,
                path: info.path,
                status: info.status,
            }, info.traceId);
        },
    };
};
exports.createLogger = createLogger;
