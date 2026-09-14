const { isLoggedIn } = require('./utils/auth');
const logger = require('./utils/logger');

App({
  onLaunch() {
    if (!isLoggedIn()) {
      wx.reLaunch({ url: '/pages/my/my' });
    }
  },
  // 端侧错误采集（设计 §6.2）：小程序的 onError / onUnhandledRejection 是最完整的捕获点，
  // 未捕获的渲染错误也能收到；在页面里逐个 try/catch 做不到这一点。
  onError(err) {
    logger.captureError(err);
  },
  onUnhandledRejection(res) {
    // 有的基础库直接把 reason 传进来（而不是 { reason, promise }），两种都兜住
    logger.captureRejection(res && res.reason !== undefined ? res.reason : res);
  },
  globalData: {
    userInfo: null,
  },
});
