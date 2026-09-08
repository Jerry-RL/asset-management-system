const api = require('../../utils/api');

Page({
  data: { form: { assetId: '', description: '' }, assets: [], loading: false },
  onLoad() {
    api
      .get('/assets?page=1&pageSize=100')
      .then((data) => this.setData({ assets: (data && data.list) || [] }))
      .catch(() => {});
  },
  onAssetChange(e) {
    this.setData({ 'form.assetId': Number(e.detail.value) });
  },
  onDesc(e) {
    this.setData({ 'form.description': e.detail.value });
  },
  // FR-MPU-005 报事报修
  submit() {
    const { assetId, description } = this.data.form;
    if (!assetId || !description) {
      wx.showToast({ title: '请选择资产并填写说明', icon: 'none' });
      return;
    }
    this.setData({ loading: true });
    api
      .post('/repairs', { assetId, description })
      .then(() => {
        wx.showToast({ title: '报修成功', icon: 'success' });
        setTimeout(() => wx.navigateBack(), 800);
      })
      .catch((err) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ loading: false }));
  },
});
