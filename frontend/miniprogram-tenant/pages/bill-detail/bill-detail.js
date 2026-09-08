const api = require('../../utils/api');

Page({
  data: { bill: null, id: null, paying: false },
  onLoad(options) {
    const id = options.id;
    this.setData({ id });
    this.reload();
  },
  reload() {
    api
      .get('/billing/bills/' + this.data.id)
      .then((bill) => this.setData({ bill }))
      .catch((err) => wx.showToast({ title: err.message, icon: 'none' }));
  },
  // FR-MPU-004 微信支付
  pay() {
    this.setData({ paying: true });
    api
      .post('/billing/payments/wechat', { billIds: [Number(this.data.id)], strategy: 'specified' })
      .then((params) => {
        if (params && params.mock) {
          wx.showToast({ title: '支付成功', icon: 'success' });
          this.reload();
          return;
        }
        return new Promise((resolve, reject) => {
          wx.requestPayment({
            timeStamp: params.timeStamp,
            nonceStr: params.nonceStr,
            package: params.package,
            signType: params.signType || 'RSA',
            paySign: params.paySign,
            success: resolve,
            fail: reject,
          });
        }).then(() => {
          wx.showToast({ title: '支付成功', icon: 'success' });
          this.reload();
        });
      })
      .catch((err) => wx.showToast({ title: err.message || '支付取消', icon: 'none' }))
      .finally(() => this.setData({ paying: false }));
  },
});
