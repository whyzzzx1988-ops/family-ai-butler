const api = require('../../utils/api');
const config = require('../../utils/config');

const KIND_LABEL = { word: '单词', mistake: '错题', note: '笔记' };

function decorate(r) {
  return Object.assign({}, r, { kindLabel: KIND_LABEL[r.kind] || r.kind });
}

Page({
  data: {
    filter: '',
    records: [],
    due: [],
    quizItems: null,
    quizNote: '',
    loading: false,
  },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.loadAll();
  },

  loadAll() {
    this.setData({ loading: true });
    api.listRecords(this.data.filter || undefined).then(
      (rows) => this.setData({ records: (rows || []).map(decorate) }),
      (err) => wx.showToast({ title: err.message, icon: 'none', duration: 3000 })
    );
    api.reviewToday().then(
      (rows) => this.setData({ loading: false, due: (rows || []).map(decorate) }),
      () => this.setData({ loading: false })
    );
  },

  setFilter(e) {
    this.setData({ filter: e.currentTarget.dataset.kind || '' }, () => this.loadAll());
  },

  startQuiz() {
    api.quiz(5).then(
      (res) => {
        const items = (res.items || []).map((it) =>
          Object.assign({}, it, { kindLabel: KIND_LABEL[it.kind] || it.kind, showRef: false, done: '' }));
        this.setData({ quizItems: items, quizNote: res.note || '' });
        if (!items.length) {
          wx.showToast({ title: '今天没有待复习的', icon: 'none' });
        }
      },
      (err) => wx.showToast({ title: err.message, icon: 'none', duration: 3000 })
    );
  },

  toggleRef(e) {
    const idx = e.currentTarget.dataset.idx;
    const key = 'quizItems[' + idx + '].showRef';
    this.setData({ [key]: !this.data.quizItems[idx].showRef });
  },

  grade(e) {
    const idx = e.currentTarget.dataset.idx;
    const result = e.currentTarget.dataset.result;
    const item = this.data.quizItems[idx];
    api.logReview(item.recordId, result).then(
      () => {
        const key = 'quizItems[' + idx + '].done';
        this.setData({ [key]: result });
        this.loadDueOnly();
      },
      (err) => wx.showToast({ title: err.message, icon: 'none', duration: 3000 })
    );
  },

  loadDueOnly() {
    api.reviewToday().then(
      (rows) => this.setData({ due: (rows || []).map(decorate) }),
      () => {}
    );
  },

  // 订阅复习提醒（N2 补入口）：每天 19:00 后端推给孩子本人与户主；模式同简报页订阅
  subscribeReview() {
    const tmplId = config.TEMPLATE_REVIEW_ID;
    if (!tmplId) {
      wx.showToast({ title: '复习模板未配置（见 utils/config.js）', icon: 'none', duration: 3000 });
      return;
    }
    wx.requestSubscribeMessage({
      tmplIds: [tmplId],
      success: () => {
        api.notifyTest('review').then(
          (res) => wx.showToast({ title: res.ok ? '测试消息已发' : res.message, icon: 'none', duration: 3000 }),
          (err) => wx.showToast({ title: err.message, icon: 'none', duration: 3000 })
        );
      },
      fail: () => wx.showToast({ title: '未完成订阅授权', icon: 'none' }),
    });
  },
});
