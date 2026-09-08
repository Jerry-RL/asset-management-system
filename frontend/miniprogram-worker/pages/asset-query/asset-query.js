const api = require('../../utils/api');

Page({
  data: { list: [], keyword: '' },
  onInput(e) {
    this.setData({ keyword: e.detail.value });
  },
  // FR-MPW-002 资产查询：检索资产基础/租赁/权属信息
  search() {
    const kw = this.data.keyword;
    api
      .get('/assets?keyword=' + encodeURIComponent(kw) + '&page=1&pageSize=50')
      .then((data) => this.setData({ list: (data && data.list) || [] }))
      .catch((err) => wx.showToast({ title: err.message, icon: 'none' }));
  },
  // 扫码查询
  scan() {
    wx.scanCode({
      success: (res) => {
        this.setData({ keyword: res.result });
        this.search();
      },
    });
  },
});
