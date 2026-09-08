const api = require('../../utils/api');

Page({
  data: { meters: [], selectedMeter: '', form: { reading: '' }, loading: false },
  onShow() {
    api
      .get('/meters')
      .then((list) => this.setData({ meters: list || [] }))
      .catch(() => {});
  },
  onMeterChange(e) {
    this.setData({ selectedMeter: this.data.meters[e.detail.value] });
  },
  onReading(e) {
    this.setData({ 'form.reading': e.detail.value });
  },
  // FR-MPW-009 抄表：执行周期抄表任务，录入表计读数
  submit() {
    const meter = this.data.selectedMeter;
    const reading = this.data.form.reading;
    if (!meter || !reading) {
      wx.showToast({ title: '请选择表计并填写读数', icon: 'none' });
      return;
    }
    this.setData({ loading: true });
    api
      .post('/meters/' + meter.id + '/readings', {
        reading: Number(reading),
        readingDate: new Date().toISOString().slice(0, 10),
      })
      .then(() => {
        wx.showToast({ title: '抄表成功', icon: 'success' });
        this.setData({ form: { reading: '' } });
      })
      .catch((err) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ loading: false }));
  },
});
