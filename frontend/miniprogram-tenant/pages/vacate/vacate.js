const api = require('../../utils/api');

Page({
  data: { contracts: [], form: { contractId: '', reason: '', expectedVacateDate: '' }, loading: false },
  onLoad() {
    api
      .get('/contracts?page=1&pageSize=50')
      .then((data) => this.setData({ contracts: (data && data.list) || [] }))
      .catch(() => {});
  },
  onContractChange(e) {
    this.setData({ 'form.contractId': Number(e.detail.value) });
  },
  onReason(e) {
    this.setData({ 'form.reason': e.detail.value });
  },
  onDate(e) {
    this.setData({ 'form.expectedVacateDate': e.detail.value });
  },
  // FR-MPU-010 / FR-VACATE-004 用户端退租申请
  submit() {
    const { contractId, reason, expectedVacateDate } = this.data.form;
    if (!contractId) {
      wx.showToast({ title: '请选择合同', icon: 'none' });
      return;
    }
    this.setData({ loading: true });
    api
      .post('/contracts/' + contractId + '/vacate', { reason, expectedVacateDate })
      .then(() => {
        wx.showToast({ title: '退租申请已提交', icon: 'success' });
        setTimeout(() => wx.navigateBack(), 800);
      })
      .catch((err) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ loading: false }));
  },
});
