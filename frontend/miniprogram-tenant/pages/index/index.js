const api = require('../../utils/api');
const { getUser } = require('../../utils/auth');

Page({
  data: {
    user: null,
    unread: 0,
    banners: ['资产招租', '线上缴费', '报事报修', '身份绑定'],
  },
  onShow() {
    this.setData({ user: getUser() });
    api
      .get('/notifications/unread-count')
      .then((count) => this.setData({ unread: count }))
      .catch(() => {});
  },
  // 快捷入口（FR-MPU-003）
  goRepair() {
    wx.navigateTo({ url: '/pages/repair/repair' });
  },
  goBind() {
    wx.navigateTo({ url: '/pages/bind/bind' });
  },
  goPay() {
    wx.navigateTo({ url: '/pages/bills/bills' });
  },
  goAssets() {
    wx.switchTab({ url: '/pages/assets/assets' });
  },
  goMessages() {
    wx.navigateTo({ url: '/pages/messages/messages' });
  },
  goContracts() {
    wx.navigateTo({ url: '/pages/contracts/contracts' });
  },
});
