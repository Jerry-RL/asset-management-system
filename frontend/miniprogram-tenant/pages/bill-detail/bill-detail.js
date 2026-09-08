const api = require('../../utils/api');

Page({
  data: { bill: null, id: null, paying: false },
  onLoad(options) {
    const id = options.id;
    this.setData({ id });
    api
      .get('/billing/bills/' + id)
      .then((bill) => this.setData({ bill }))
      .catch((err) => wx.showToast({ title: err.message, icon: 'none' }));
  },
  // FR-MPU-004 微信支付（JSAPI 预留，生产调用微信支付统一下单）
  pay() {
    this.setData({ paying: true });
    api
      .post('/billing/payments/wechat', { billIds: [Number(this.data.id)] })
      .then((params) => {
        // 生产：wx.requestPayment(params)
        wx.showToast({ title: '支付功能对接中', icon: 'none' });
      })
      .catch((err) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ paying: false }));
  },
});
