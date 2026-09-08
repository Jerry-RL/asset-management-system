const api = require('../../utils/api');

Page({
  data: { list: [], loading: false },
  onShow() {
    this.load();
  },
  load() {
    api
      .get('/vacate-orders')
      .then((list) => this.setData({ list: list || [] }))
      .catch(() => {});
  },
  // FR-MPW-008 清场验收：现场录入水电气读数、设施清点
  inspect(e) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '清场验收',
      editable: true,
      placeholderText: '水电气读数/设施清点说明',
      success: (res) => {
        if (res.confirm) {
          api
            .post('/vacate-orders/' + id + '/inspection', {
              waterReading: 0,
              electricReading: 0,
              remark: res.content || '',
            })
            .then(() => {
              wx.showToast({ title: '验收已提交', icon: 'success' });
              this.load();
            })
            .catch((err) => wx.showToast({ title: err.message, icon: 'none' }));
        }
      },
    });
  },
});
