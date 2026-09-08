const api = require('../../utils/api');

Page({
  data: { list: [], contractId: '' },
  onLoad(options) {
    if (options.contractId) {
      this.setData({ contractId: options.contractId });
      this.loadByContract(options.contractId);
    }
  },
  // FR-MPU-004 线上缴费：查看租金/杂费账单
  loadByContract(contractId) {
    api
      .get('/billing/bills?contractId=' + contractId + '&page=1&pageSize=50')
      .then((data) => this.setData({ list: (data && data.list) || [] }))
      .catch((err) => wx.showToast({ title: err.message, icon: 'none' }));
  },
  // FR-MPU-011 我的合同账单
  onShow() {
    if (!this.data.contractId && !this.data.list.length) {
      api
        .get('/contracts?page=1&pageSize=50')
        .then((data) => this.setData({ list: (data && data.list) || [] }))
        .catch(() => {});
    }
  },
  goDetail(e) {
    wx.navigateTo({ url: '/pages/bill-detail/bill-detail?id=' + e.currentTarget.dataset.id });
  },
});
