const api = require('../../utils/api');

Page({
  data: { list: [] },
  onShow() {
    api
      .get('/repairs?page=1&pageSize=50')
      .then((data) => this.setData({ list: (data && data.list) || [] }))
      .catch(() => {});
  },
});
