const api = require('../../utils/api');

Page({
  data: { form: { billId: '', level: 2, method: 'notice_post', content: '' }, loading: false },
  onInput(e) {
    this.setData({ ['form.' + e.currentTarget.dataset.field]: e.detail.value });
  },
  onLevelChange(e) {
    this.setData({ 'form.level': Number(e.detail.value) });
  },
  // FR-MPW-006 资产催租：催缴单现场张贴 + 上传留痕
  submit() {
    const { billId, level, method, content } = this.data.form;
    if (!billId) {
      wx.showToast({ title: '请填写账单ID', icon: 'none' });
      return;
    }
    this.setData({ loading: true });
    api
      .post('/dunning/records', {
        billId: Number(billId),
        level: Number(level),
        method,
        content,
        result: '已张贴',
      })
      .then(() => wx.showToast({ title: '催缴记录已提交', icon: 'success' }))
      .catch((err) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ loading: false }));
  },
});
