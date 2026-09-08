const api = require('../../utils/api');

Page({
  data: { list: [] },
  onShow() {
    this.load();
  },
  // FR-MPW-007 合同审批：移动端处理审批节点
  load() {
    api
      .get('/approvals/tasks')
      .then((list) => this.setData({ list: list || [] }))
      .catch(() => {});
  },
  approve(e) {
    const id = e.currentTarget.dataset.id;
    api.post('/approvals/' + id + '/approve', { comment: '同意' }).then(() => {
      wx.showToast({ title: '已通过', icon: 'success' });
      this.load();
    });
  },
  reject(e) {
    const id = e.currentTarget.dataset.id;
    api.post('/approvals/' + id + '/reject', { comment: '驳回' }).then(() => {
      wx.showToast({ title: '已驳回', icon: 'none' });
      this.load();
    });
  },
});
