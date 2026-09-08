const api = require('../../utils/api');

Page({
  data: { list: [] },
  onShow() {
    this.load();
  },
  load() {
    api
      .get('/notifications?unreadOnly=false')
      .then((list) => this.setData({ list: list || [] }))
      .catch(() => {});
  },
  // FR-MPU-008 消息已读/未读
  read(e) {
    const id = e.currentTarget.dataset.id;
    api.post('/notifications/' + id + '/read').then(() => this.load());
  },
});
