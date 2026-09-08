const api = require('../../utils/api');

Page({
  data: { list: [] },
  onShow() {
    this.load();
  },
  // FR-MPW-010 现场收款到账确认（财务在 PC 端确认，此处展示待确认款）
  load() {
    api
      .get('/payments?confirmStatus=pending&channel=worker_mp&page=1&pageSize=50')
      .then((data) => this.setData({ list: (data && data.list) || [] }))
      .catch(() => {});
  },
});
