const api = require('../../utils/api');

Page({
  data: { list: [] },
  onShow() {
    api
      .get('/contracts?page=1&pageSize=50')
      .then((data) => this.setData({ list: (data && data.list) || [] }))
      .catch(() => {});
  },
  // FR-MPU-011 我的合同 + 合同文件查看（下载合同 PDF 预留）
  viewFile(e) {
    const id = e.currentTarget.dataset.id;
    wx.showToast({ title: '合同文件查看（合同ID ' + id + '）', icon: 'none' });
  },
});
