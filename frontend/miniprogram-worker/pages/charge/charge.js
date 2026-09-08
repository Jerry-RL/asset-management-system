const api = require('../../utils/api');

Page({
  data: { form: { contractId: '', amount: '', method: 'cash' }, loading: false },
  onInput(e) {
    this.setData({ ['form.' + e.currentTarget.dataset.field]: e.detail.value });
  },
  onMethodChange(e) {
    this.setData({ 'form.method': e.detail.value });
  },
  // FR-MPW-003 现场收费 / FR-MPW-010 现场收款登记（待确认）
  submit() {
    const { contractId, amount, method } = this.data.form;
    if (!contractId || !amount) {
      wx.showToast({ title: '请填写合同与金额', icon: 'none' });
      return;
    }
    this.setData({ loading: true });
    api
      .post('/payments/worker/register', {
        contractId: Number(contractId),
        amount: Number(amount),
        method,
      })
      .then((payment) => {
        wx.showToast({ title: '收款已登记（待财务确认）', icon: 'success' });
      })
      .catch((err) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ loading: false }));
  },
});
