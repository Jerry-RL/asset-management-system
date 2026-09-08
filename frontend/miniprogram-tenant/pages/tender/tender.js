const api = require('../../utils/api');
const { getUser } = require('../../utils/auth');

Page({
  data: { list: [], tenantId: null },
  onShow() {
    const user = getUser();
    this.setData({ tenantId: user ? user.id : null });
    this.load();
  },
  load() {
    api
      .get('/tender/announcements?status=open')
      .then((list) => this.setData({ list: list || [] }))
      .catch(() => {});
  },
  // FR-MPU-006 招租报名 / FR-TENDER-002 报名受理
  apply(e) {
    const announcementId = e.currentTarget.dataset.id;
    const tenantId = this.data.tenantId;
    if (!tenantId) {
      wx.showToast({ title: '请先完成身份绑定', icon: 'none' });
      return;
    }
    api
      .post('/tender/announcements/' + announcementId + '/applications', { tenantId })
      .then(() => wx.showToast({ title: '报名成功', icon: 'success' }))
      .catch((err) => wx.showToast({ title: err.message, icon: 'none' }));
  },
});
