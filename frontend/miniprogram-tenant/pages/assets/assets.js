const api = require('../../utils/api');

/** 千分位：小程序 JSCore 不保证 toLocaleString 行为一致，这里显式格式化。 */
function withThousands(value) {
  const parts = String(Math.round(Number(value))).split('.');
  parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return parts.join('.');
}

/**
 * 年租金展示。
 *
 * <p>后端 V59 起招租的租金是 `annualRent`（元/年）—— 旧字段 `rentAmount` 是月均额，
 * 端上不要再按「/月」展示，否则同一个招租会在两端显示两个相差 12 倍的数。
 */
function rentLabel(item) {
  if (item.annualRent) return '¥' + withThousands(item.annualRent) + '/年';
  return '面议';
}

Page({
  data: { list: [], loading: false },
  onShow() {
    this.load();
  },
  load() {
    this.setData({ loading: true });
    api
      // 只有审批通过的招租才是 active；待审批 / 已驳回 / 已关闭不可见
      .get('/lease-listings?status=active')
      .then((list) => {
        const rows = (list || []).map((item) => ({
          ...item,
          rentLabel: rentLabel(item),
          title: item.assetName || item.assetNo || '招租资产 #' + item.assetId,
          introText: item.intro ? String(item.intro).slice(0, 40) : '',
        }));
        this.setData({ list: rows });
      })
      .catch((err) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ loading: false }));
  },
  goDetail(e) {
    wx.navigateTo({ url: '/pages/asset-detail/asset-detail?id=' + e.currentTarget.dataset.id });
  },
  goTender() {
    wx.navigateTo({ url: '/pages/tender/tender' });
  },
});
