const { isLoggedIn } = require('./utils/auth');

App({
  onLaunch() {
    if (!isLoggedIn()) {
      wx.reLaunch({ url: '/pages/my/my' });
    }
  },
  globalData: {
    userInfo: null,
  },
});
