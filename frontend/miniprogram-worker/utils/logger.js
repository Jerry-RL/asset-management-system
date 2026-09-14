// 应用日志上报器（设计 §6.2）。
//
// `./log-sdk` 是 `packages/log-sdk/dist/log-sdk.mp.js` 的拷贝产物，**不要手改此文件**：
// 小程序构建器不解析 monorepo 路径，只能拷贝；一致性由 `pnpm check:mp-sdk` 守卫
// （scripts/check-mp-log-sdk.mjs 逐字节比对）。
const { BASE_URL } = require('./config');
const { createLogger } = require('./log-sdk');
const { getUser } = require('./auth');

// 自报用户 ID（仅排查定位用，后端不以它作鉴权依据 —— 上报接口完全免鉴权）。
// 兼容 id / userId 两种字段名：小程序登录返回的用户对象与 PC 端契约不完全一致，
// 为了一个「可选的排查线索」去把契约钉死并不划算，取不到就按未登录上报。
function getUserId() {
  try {
    const user = getUser();
    if (!user) return null;
    const id = user.id != null ? user.id : user.userId;
    return typeof id === 'number' ? id : null;
  } catch (e) {
    return null;
  }
}

module.exports = createLogger({
  // 小程序不支持相对路径，必须绝对地址；与业务请求同域，无需新增「服务器域名」
  endpoint: BASE_URL + '/public/app-logs',
  appType: 'worker-mp',
  getUserId: getUserId,
  // 小程序只能连真实后端（无本地模拟层），因此不做 debug 开关：
  // 开发期上报到本地后端正是想要的，写死 false 比留一个没人记得翻的开关更清楚
  debug: false,
});
