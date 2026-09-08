const api = require('../../utils/api');

Page({
  data: { contracts: [], loading: false },
  onShow() {
    this.loadContracts();
  },
  loadContracts() {
    api
      .get('/contracts?page=1&pageSize=50')
      .then((data) => this.setData({ contracts: (data && data.list) || [] }))
      .catch(() => {});
  },
  // FR-MPU-010 / FR-ESIGN-002 电子签约
  sign(e) {
    const id = e.currentTarget.dataset.id;
    this.setData({ loading: true });
    wx.showModal({
      title: '电子签约',
      content: '确认在线签署合同（合同ID ' + id + '）？',
      success: (res) => {
        if (!res.confirm) {
          this.setData({ loading: false });
          return;
        }
        api
          .post('/contracts/' + id + '/esign/start')
          .then((data) => {
            const status = (data && data.esignStatus) || '';
            if (status === 'signed') {
              wx.showToast({ title: '签署完成', icon: 'success' });
            } else if (data && data.signUrl) {
              wx.showToast({ title: '已发起签署', icon: 'success' });
            } else {
              wx.showToast({ title: '已提交', icon: 'success' });
            }
            this.loadContracts();
          })
          .catch((err) => {
            wx.showToast({
              title: (err && err.message) || '签署失败',
              icon: 'none',
            });
          })
          .finally(() => this.setData({ loading: false }));
      },
      fail: () => this.setData({ loading: false }),
    });
  },
});
