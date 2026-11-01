const api = require('../../utils/api');
const config = require('../../utils/config');

const KIND_LABEL = { word: '单词', mistake: '错题', note: '笔记' };

function decorate(r) {
  return Object.assign({}, r, { kindLabel: KIND_LABEL[r.kind] || r.kind });
}

// 成就星瓶（纯展示）：每条学习记录化作一颗星，位置/大小/颜色按序号确定性生成，最多摆 36 颗
function buildStars(count) {
  const stars = [];
  const total = Math.min(count || 0, 36);
  for (let i = 0; i < total; i++) {
    const row = Math.floor(i / 12);
    const left = 8 + (i % 12) * 7.2 + ((i * 29) % 5);
    const bottom = 9 + row * 17 + ((i * 17) % 6);
    const size = 15 + ((i * 13) % 3) * 6;
    const cls = i % 5 === 0 ? 'moon' : (i % 3 === 0 ? 'blue' : 'gold');
    stars.push({
      cls,
      style: 'left:' + left + '%;bottom:' + bottom + '%;width:' + size + 'rpx;height:' + size + 'rpx;'
        + 'animation-delay:' + ((i % 5) * 0.6) + 's;',
    });
  }
  return stars;
}

// 星瓶里程碑阶梯（颗）：到下一阶还差多少条记录
const JAR_TIERS = [10, 25, 50, 100, 200];

function jarHintFor(count) {
  for (const t of JAR_TIERS) {
    if (count < t) {
      return '下一里程碑：' + t + ' 颗星 · 还差 ' + (t - count) + ' 条记录';
    }
  }
  return '里程碑阶梯已全部点亮，继续往瓶里添星吧';
}

const WEEK_LABEL = ['日', '一', '二', '三', '四', '五', '六'];

// 星图面板视图 shaping：阶段三档横条 + 近 7 日柱状 + 薄弱提示（只用后端事实字段，前端不造数）
function buildStatsView(s) {
  const total = Math.max(1, s.totalRecords || 0);
  const stageBars = [
    { label: '新学', count: s.newCount || 0, pct: Math.round((s.newCount || 0) * 100 / total), cls: 'sb-new' },
    { label: '复习中', count: s.reviewingCount || 0, pct: Math.round((s.reviewingCount || 0) * 100 / total), cls: 'sb-mid' },
    { label: '已毕业', count: s.graduatedCount || 0, pct: Math.round((s.graduatedCount || 0) * 100 / total), cls: 'sb-done' },
  ];
  const days = s.reviewsLast7Days || [];
  const max = Math.max(1, ...days.map((d) => d.count || 0));
  const weekBars = days.map((d) => {
    const dt = new Date(d.date + 'T00:00:00');
    return { label: WEEK_LABEL[dt.getDay()], count: d.count || 0, h: Math.max(6, Math.round((d.count || 0) * 100 / max)) };
  });
  let hint = '';
  if ((s.dueCount || 0) > 0) {
    hint = '最该补：先清今日到期的 ' + s.dueCount + ' 条';
  } else if ((s.reviews7Days || 0) >= 5 && (s.forgot7Days || 0) * 2 >= (s.reviews7Days || 0)) {
    hint = '最该补：近 7 日忘得偏多，抽查时先想 3 秒再揭开';
  } else if ((s.totalRecords || 0) > 0) {
    hint = '节奏不错，保持住';
  }
  return { stageBars, weekBars, hint };
}

// 成就徽章（8 枚）：判定只用 stats 与记录总数，点亮金环、未得灰暗并写明还差多少
function buildBadges(s, totalRecords) {
  const defs = [
    { name: '第一颗星', desc: '添第 1 条学习记录', now: totalRecords, goal: 1, unit: '条' },
    { name: '十条成串', desc: '累计 10 条记录', now: totalRecords, goal: 10, unit: '条' },
    { name: '半百星图', desc: '累计 50 条记录', now: totalRecords, goal: 50, unit: '条' },
    { name: '百星成河', desc: '累计 100 条记录', now: totalRecords, goal: 100, unit: '条' },
    { name: '三日连珠', desc: '连续学习 3 天', now: s.streakDays || 0, goal: 3, unit: '天' },
    { name: '七日巡航', desc: '连续学习 7 天', now: s.streakDays || 0, goal: 7, unit: '天' },
    { name: '首个毕业', desc: '第 1 条记录复习毕业', now: s.graduatedCount || 0, goal: 1, unit: '条' },
    { name: '单日勤勉', desc: '一天复习满 20 次', now: s.reviewsToday || 0, goal: 20, unit: '次' },
  ];
  return defs.map((d) => {
    const lit = d.now >= d.goal;
    return Object.assign({}, d, {
      lit,
      note: lit ? '已点亮' : '还差 ' + (d.goal - d.now) + ' ' + d.unit,
    });
  });
}

Page({
  data: {
    filter: '',
    records: [],
    shownRecords: [],
    query: '',
    stars: [],
    jarHint: '',
    stats: null,
    statsView: null,
    badges: [],
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
      (rows) => {
        const records = (rows || []).map(decorate);
        this.setData({ records, stars: buildStars(records.length), jarHint: jarHintFor(records.length) });
        this.applyQuery();
      },
      (err) => wx.showToast({ title: err.message, icon: 'none', duration: 3000 })
    );
    api.reviewToday().then(
      (rows) => this.setData({ loading: false, due: (rows || []).map(decorate) }),
      () => this.setData({ loading: false })
    );
    api.getLearnStats().then(
      (s) => this.setData({
        stats: s,
        statsView: buildStatsView(s),
        badges: buildBadges(s, s.totalRecords || 0),
      }),
      () => {} // 统计拿不到只是少个面板，不打扰主流程
    );
  },

  // 记录搜索：在已加载列表内按内容过滤（与 kind 筛选叠加，口径见卡片内说明）
  onQueryInput(e) {
    this.setData({ query: e.detail.value }, () => this.applyQuery());
  },

  applyQuery() {
    const q = (this.data.query || '').trim().toLowerCase();
    const shown = !q
      ? this.data.records
      : this.data.records.filter((r) => (r.content || '').toLowerCase().indexOf(q) >= 0);
    this.setData({ shownRecords: shown });
  },

  badgeTap(e) {
    const b = this.data.badges[e.currentTarget.dataset.idx];
    if (b) {
      wx.showToast({ title: b.name + '：' + b.desc + (b.lit ? '（已点亮）' : '（' + b.note + '）'), icon: 'none', duration: 2600 });
    }
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
