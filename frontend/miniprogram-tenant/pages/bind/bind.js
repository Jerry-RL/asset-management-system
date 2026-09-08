const api = require('../../utils/api');

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
    api
      .post('/tenants', { name, phone, idNo, tenantType: 'person' })
      .then(() => {
        wx.showToast({ title: '绑定成功', icon: 'success' });
        setTimeout(() => wx.switchTab({ url: '/pages/index/index' }), 800);
      })
      .catch((err) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ loading: false }));
  },
});
