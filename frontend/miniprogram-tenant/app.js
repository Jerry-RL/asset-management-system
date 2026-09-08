const { isLoggedIn } = require('./utils/auth');

App({
  onLaunch() {
    // 启动时若未登录跳转登录页
    if (!isLoggedIn()) {
      wx.reLaunch({ url: '/pages/login/login' });
    }
  },
  globalData: {
    userInfo: null,
    tenant: null,
  },
});
