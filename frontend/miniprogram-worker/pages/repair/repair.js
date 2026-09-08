const api = require('../../utils/api');

Page({
  data: { list: [] },
  onShow() {
    this.load();
  },
  load() {
    api
      .get('/repairs?page=1&pageSize=50')
      .then((data) => this.setData({ list: (data && data.list) || [] }))
      .catch(() => {});
  },
  // FR-MPW-005 资产维修：接收报修工单、更新进度、完工验收
  accept(e) {
    const id = e.currentTarget.dataset.id;
    api.post('/repairs/' + id + '/dispatch', {}).then(() => this.load());
  },
  complete(e) {
    const id = e.currentTarget.dataset.id;
    api.post('/repairs/' + id + '/complete', { resultRemark: '维修完成' }).then(() => this.load());
  },
});
