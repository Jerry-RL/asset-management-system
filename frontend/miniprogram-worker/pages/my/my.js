const { getUser } = require('../../utils/auth');

Page({
  data: { user: null, menu: [] },
  onShow() {
    this.setData({
      user: getUser(),
      menu: [
        { title: '资产收费', url: '/pages/charge/charge' },
        { title: '资产巡检', url: '/pages/inspect/inspect' },
        { title: '资产维修', url: '/pages/repair/repair' },
        { title: '资产催租', url: '/pages/dunning/dunning' },
        { title: '合同审批', url: '/pages/approval/approval' },
        { title: '清场验收', url: '/pages/vacate/vacate' },
        { title: '抄表', url: '/pages/meter/meter' },
        { title: '现场收款到账', url: '/pages/payment-confirm/payment-confirm' },
      ],
    });
  },
  go(e) {
    wx.navigateTo({ url: e.currentTarget.dataset.url });
  },
});
