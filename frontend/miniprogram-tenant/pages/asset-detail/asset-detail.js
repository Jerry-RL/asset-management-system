const api = require('../../utils/api');

Page({
  data: { asset: null, id: null },
  onLoad(options) {
    const id = options.id;
    this.setData({ id });
    if (id) this.load(id);
  },
  load(id) {
    api
      .get('/assets/' + id)
      .then((asset) => this.setData({ asset }))
      .catch((err) => wx.showToast({ title: err.message, icon: 'none' }));
  },
  // FR-MPU-007 扫码查资产：扫码后按资产编号查询
  scan() {
    wx.scanCode({
      success: (res) => {
        const no = res.result;
        api
          .get('/assets?keyword=' + encodeURIComponent(no) + '&page=1&pageSize=1')
          .then((data) => {
            const list = data && data.list;
            if (list && list.length) this.setData({ asset: list[0], id: list[0].id });
            else wx.showToast({ title: '未找到该资产', icon: 'none' });
          })
          .catch((err) => wx.showToast({ title: err.message, icon: 'none' }));
      },
    });
  },
  goTender() {
    wx.navigateTo({ url: '/pages/tender/tender?assetId=' + this.data.id });
  },
});
