const api = require('../../utils/api');

Page({
  data: { contracts: [], loading: false },
  onShow() {
    api
      .get('/contracts?page=1&pageSize=50')
      .then((data) => this.setData({ contracts: (data && data.list) || [] }))
      .catch(() => {});
  },
  // FR-MPU-010 / FR-ESIGN-002 电子签约（线上签署，生产对接电子签平台跳转）
  sign(e) {
    const id = e.currentTarget.dataset.id;
    this.setData({ loading: true });
    wx.showModal({
      title: '电子签约',
      content: '确认在线签署合同（合同ID ' + id + '）？',
      success: (res) => {
        if (res.confirm) {
          wx.showToast({ title: '签署完成（生产对接电子签）', icon: 'success' });
        }
      },
      complete: () => this.setData({ loading: false }),
    });
  },
});
