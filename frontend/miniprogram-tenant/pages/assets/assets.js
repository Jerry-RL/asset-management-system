const api = require('../../utils/api');

Page({
  data: { list: [], loading: false },
  onShow() {
    this.load();
  },
  load() {
    this.setData({ loading: true });
    api
      .get('/lease-listings?status=active')
      .then((list) => this.setData({ list: list || [] }))
      .catch((err) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ loading: false }));
  },
  goDetail(e) {
    wx.navigateTo({ url: '/pages/asset-detail/asset-detail?id=' + e.currentTarget.dataset.id });
  },
  goTender() {
    wx.navigateTo({ url: '/pages/tender/tender' });
  },
});
