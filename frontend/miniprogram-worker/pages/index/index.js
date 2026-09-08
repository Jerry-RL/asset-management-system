const api = require('../../utils/api');

Page({
  data: { list: [], filter: 'all' },
  onShow() {
    this.load();
  },
  // FR-MPW-001 任务中心：集中展示待办工单
  load() {
    const scope = this.data.filter === 'mine' ? 'mine' : 'all';
    api
      .get('/tasks?scope=' + scope + '&page=1&pageSize=50')
      .then((data) => this.setData({ list: (data && data.list) || [] }))
      .catch(() => {});
  },
  onFilter(e) {
    this.setData({ filter: e.currentTarget.dataset.filter });
    this.load();
  },
  // 办理：按任务类型跳转
  handle(e) {
    const task = e.currentTarget.dataset.task;
    const map = {
      repair: '/pages/repair/repair',
      dunning: '/pages/dunning/dunning',
      contract_approval: '/pages/approval/approval',
      meter: '/pages/meter/meter',
      charge: '/pages/charge/charge',
      vacate: '/pages/vacate/vacate',
    };
    const url = map[task.taskType] || '/pages/index/index';
    wx.navigateTo({ url });
  },
  complete(e) {
    const id = e.currentTarget.dataset.id;
    api.post('/tasks/' + id + '/complete').then(() => this.load());
  },
});
