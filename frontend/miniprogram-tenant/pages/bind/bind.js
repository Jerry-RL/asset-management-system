const api = require('../../utils/api');
const { setAuth } = require('../../utils/auth');

Page({
  data: { form: { name: '', phone: '', idNo: '' }, loading: false },
  onInput(e) {
    this.setData({ ['form.' + e.currentTarget.dataset.field]: e.detail.value });
  },
  // FR-MPU-002 实名认证与身份绑定
  submit() {
    const { name, phone, idNo } = this.data.form;
    if (!name || !phone) {
      wx.showToast({ title: '请填写姓名与手机号', icon: 'none' });
      return;
    }
    this.setData({ loading: true });
    const bindTicket = wx.getStorageSync('bindTicket');
    const payload = { name, phone, idNo, bindTicket };
    const doBind = (extra) =>
      api.post('/auth/wechat/bind', Object.assign({}, payload, extra || {})).then((data) => {
        if (data && data.accessToken) {
          setAuth(data.accessToken, data.user);
          wx.removeStorageSync('bindTicket');
          wx.showToast({ title: '绑定成功', icon: 'success' });
          setTimeout(() => wx.switchTab({ url: '/pages/index/index' }), 800);
        } else {
          throw new Error('绑定失败');
        }
      });

    if (bindTicket) {
      doBind()
        .catch((err) => wx.showToast({ title: err.message, icon: 'none' }))
        .finally(() => this.setData({ loading: false }));
      return;
    }

    wx.login({
      success: (res) => {
        doBind({ code: res.code })
          .catch((err) => wx.showToast({ title: err.message, icon: 'none' }))
          .finally(() => this.setData({ loading: false }));
      },
      fail: () => {
        this.setData({ loading: false });
        wx.showToast({ title: '微信授权失败', icon: 'none' });
      },
    });
  },
});
