const api = require('../../utils/api');

Page({
  data: { form: { assetId: '', result: '', hazardDesc: '' }, loading: false },
  onInput(e) {
    this.setData({ ['form.' + e.currentTarget.dataset.field]: e.detail.value });
  },
  // FR-MPW-004 资产巡检：现场录入隐患
  submit() {
    const { assetId, result, hazardDesc } = this.data.form;
    if (!assetId) {
      wx.showToast({ title: '请填写资产ID', icon: 'none' });
      return;
    }
    this.setData({ loading: true });
    api
      .post('/inspections', {
        assetId: Number(assetId),
        result,
        hazardDesc,
        planDate: new Date().toISOString().slice(0, 10),
      })
      .then(() => {
        wx.showToast({ title: '巡检已提交', icon: 'success' });
        this.setData({ form: { assetId: '', result: '', hazardDesc: '' } });
      })
      .catch((err) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ loading: false }));
  },
});
