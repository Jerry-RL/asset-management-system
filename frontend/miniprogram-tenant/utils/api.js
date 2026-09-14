// 统一请求封装（用户端）
const { BASE_URL } = require('./config');
const logger = require('./logger');

const CLIENT_TYPE = 'tenant-mp';

// 上报接口自身的路径。`request()` 见到它就不再上报失败 —— 否则上报失败会再触发一次上报，
// 变成自我放大的死循环（且会被后端限流打成 429 风暴）。SDK 走 wx.request 不经过这里，
// 这是防后人改动的护栏。
const APP_LOG_INGEST_PATH = '/public/app-logs';

// 只报「服务端故障」：HTTP ≥ 500 或业务码 ≥ 50000（ErrorCode.INTERNAL_ERROR）。
// 4xx / 业务校验失败不报 —— 那是预期内的用户错误，报了会让 ERROR 告警失真。
const SERVER_ERROR_CODE = 50000;

function reportApiFailure(info) {
  if (info.path.indexOf(APP_LOG_INGEST_PATH) === 0) return;
  try {
    logger.captureApiFailure(info);
  } catch (e) {
    // 上报绝不影响业务请求：SDK 内部已吞异常，这里只兜「SDK 本身炸了」这一层
  }
}

function request(path, method = 'GET', data = {}) {
  return new Promise((resolve, reject) => {
    // 请求级 traceId（设计 D6）：每次 API 调用生成一个，随 X-Trace-Id 发出；
    // 后端 TraceIdFilter 复用它写 MDC，于是端侧这条「API 失败」与后端那次异常
    // 落在同一个 trace_id 下，查询页点 traceId 即可把整条链路捞出来。
    const traceId = logger.newTraceId();
    const token = wx.getStorageSync('accessToken');
    const header = {
      'Content-Type': 'application/json',
      'X-Client-Type': CLIENT_TYPE,
      'X-Trace-Id': traceId,
    };
    if (token) header.Authorization = 'Bearer ' + token;
    wx.request({
      url: BASE_URL + path,
      method,
      data,
      header,
      success(res) {
        const body = res.data;
        // 网关返回 HTML 错误页时 body 是字符串，没有 code 字段；
        // 单看 body.code 会漏掉这类「页面报了个看不懂的错」的故障，故同时看 HTTP 状态码
        const status = res.statusCode || 0;
        if (status >= 500 || (body && body.code >= SERVER_ERROR_CODE)) {
          // 优先用响应体里的 traceId：后端因请求头非法自己另生成时，
          // 以它为准才对得上后端那条记录
          reportApiFailure({
            traceId: (body && body.traceId) || traceId,
            method: method,
            path: path,
            status: status,
            message: body && body.message,
          });
        }
        if (body && body.code === 0) {
          resolve(body.data);
        } else if (body && (body.code === 40100 || body.code === 40101)) {
          wx.reLaunch({ url: '/pages/login/login' });
          reject(new Error(body.message || '登录失效'));
        } else {
          reject(new Error((body && body.message) || '请求失败'));
        }
      },
      fail(err) {
        // 网络层失败（断网 / DNS / 超时）没有状态码，但同样是「用户点不动」的故障
        reportApiFailure({
          traceId: traceId,
          method: method,
          path: path,
          error: err,
          message: err && err.errMsg,
        });
        reject(new Error((err && err.errMsg) || '网络异常'));
      },
    });
  });
}

module.exports = {
  get: (p) => request(p, 'GET'),
  post: (p, d) => request(p, 'POST', d),
  put: (p, d) => request(p, 'PUT', d),
  del: (p) => request(p, 'DELETE'),
  BASE_URL,
};
