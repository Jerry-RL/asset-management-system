const api = require('../../utils/api');
const { setAuth } = require('../../utils/auth');

Page({
  data: { loading: false },
  // FR-MPU-001 微信快捷授权登录
  handleLogin() {
    this.setData({ loading: true });
    wx.login({
      success: (res) => {
        api
          .post('/auth/wechat/login', { code: res.code })
          .then((data) => {
            if (data && data.accessToken) {
              setAuth(data.accessToken, data.user);
              wx.switchTab({ url: '/pages/index/index' });
              return;
            }
            if (data && data.needBind) {
              wx.setStorageSync('bindTicket', data.bindTicket || '');
              wx.redirectTo({ url: '/pages/bind/bind' });
              return;
            }
            wx.redirectTo({ url: '/pages/bind/bind' });
          })
          .catch((err) => {
            wx.showToast({ title: err.message, icon: 'none' });
          })
          .finally(() => this.setData({ loading: false }));
      },
      fail: () => {
        this.setData({ loading: false });
        wx.showToast({ title: '微信登录失败', icon: 'none' });
      },
    });
  },
});
