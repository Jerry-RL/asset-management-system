const { getUser, logout } = require('../../utils/auth');

Page({
  data: { user: null },
  onShow() {
    this.setData({ user: getUser() });
  },
  goContracts() {
    wx.navigateTo({ url: '/pages/contracts/contracts' });
  },
  goBills() {
    wx.navigateTo({ url: '/pages/bills/bills' });
  },
  goRepairs() {
    wx.navigateTo({ url: '/pages/repairs/repairs' });
  },
  goMessages() {
    wx.navigateTo({ url: '/pages/messages/messages' });
  },
  goBind() {
    wx.navigateTo({ url: '/pages/bind/bind' });
  },
  goVacate() {
    wx.navigateTo({ url: '/pages/vacate/vacate' });
  },
  goEsign() {
    wx.navigateTo({ url: '/pages/esign/esign' });
  },
  handleLogout() {
    wx.showModal({
      title: '提示',
      content: '确认退出登录？',
      success: (res) => {
        if (res.confirm) logout();
      },
    });
  },
});
