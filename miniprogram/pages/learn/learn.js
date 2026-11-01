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
  // 学习时钟：复习发生的小时分布（24 桶），只画有量的小时，标注高峰时段
  const hours = s.reviewsByHour || [];
  const hMax = Math.max(1, ...hours.map((c) => c || 0));
  let peakHour = -1;
  hours.forEach((c, h) => { if ((c || 0) > 0 && (peakHour < 0 || c > hours[peakHour])) peakHour = h; });
  const hourBars = hours.map((c, h) => ({ hour: h, count: c || 0, h: Math.max(4, Math.round((c || 0) * 100 / hMax)) }))
    .filter((b) => b.count > 0);
  return { stageBars, weekBars, hint, hourBars, peakHour };
}

// —— 目标冲刺卡（T10）：目标存本机，只算量的账 ——
// goal = { label, date: 'yyyy-MM-dd', target: 累计条数目标 }；进度 = stats.totalRecords
function buildGoalView(goal, totalRecords) {
  if (!goal) return null;
  const total = totalRecords || 0;
  const remaining = Math.max(0, goal.target - total);
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const targetDay = new Date(goal.date + 'T00:00:00');
  const daysLeft = Math.round((targetDay - today) / 86400000);
  const pct = Math.min(100, Math.round(total * 100 / Math.max(1, goal.target)));
  let status;
  if (remaining === 0) {
    status = '已达成，去设下一个目标吧';
  } else if (daysLeft < 0) {
    status = '目标日已过，还差 ' + remaining + ' 条 — 调整日期继续，或换个小一点的目标';
  } else if (daysLeft === 0) {
    status = '就是今天：还差 ' + remaining + ' 条';
  } else {
    status = '还剩 ' + daysLeft + ' 天 · 每天添 ' + Math.ceil(remaining / daysLeft) + ' 条就能到';
  }
  return { label: goal.label, date: goal.date, target: goal.target, total, remaining, daysLeft, pct, status };
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
    goal: null,
    goalView: null,
    goalEditing: false,
    goalForm: { label: '', date: '', target: '' },
    cardMode: false,
    cardIdx: 0,
    posterPath: '',
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
    this.loadGoal();
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
        goalView: buildGoalView(this.data.goal, s.totalRecords || 0),
      }),
      () => {} // 统计拿不到只是少个面板，不打扰主流程
    );
  },

  // —— 目标冲刺卡（T10）——
  loadGoal() {
    const goal = wx.getStorageSync('learnGoal') || null;
    this.setData({ goal, goalView: buildGoalView(goal, (this.data.stats || {}).totalRecords || 0) });
  },

  openGoalEdit() {
    const g = this.data.goal || { label: '', date: '', target: '' };
    this.setData({ goalEditing: true, goalForm: { label: g.label || '', date: g.date || '', target: g.target ? String(g.target) : '' } });
  },

  closeGoalEdit() {
    this.setData({ goalEditing: false });
  },

  onGoalLabel(e) { this.setData({ 'goalForm.label': e.detail.value }); },
  onGoalDate(e) { this.setData({ 'goalForm.date': e.detail.value }); },
  onGoalTarget(e) { this.setData({ 'goalForm.target': e.detail.value }); },

  saveGoal() {
    const f = this.data.goalForm;
    const target = parseInt(f.target, 10);
    if (!f.date) {
      wx.showToast({ title: '先选个目标日期', icon: 'none' });
      return;
    }
    if (!target || target <= 0) {
      wx.showToast({ title: '目标条数要大于 0', icon: 'none' });
      return;
    }
    const goal = { label: (f.label || '').trim() || '我的小目标', date: f.date, target };
    wx.setStorageSync('learnGoal', goal);
    this.setData({ goal, goalEditing: false, goalView: buildGoalView(goal, (this.data.stats || {}).totalRecords || 0) });
    wx.showToast({ title: '目标已挂上星图', icon: 'none' });
  },

  clearGoal() {
    wx.removeStorageSync('learnGoal');
    this.setData({ goal: null, goalView: null, goalEditing: false });
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
    this._grade(e.currentTarget.dataset.idx, e.currentTarget.dataset.result, null);
  },

  // 星牌模式：评完自动滑到下一张未评的牌，全评完收局并刷新星图
  gradeCard(e) {
    const idx = this.data.cardIdx;
    const result = e.currentTarget.dataset.result;
    this._grade(idx, result, () => {
      const items = (this.data.quizItems || []).map((it, i) =>
        (i === idx ? Object.assign({}, it, { done: result }) : it));
      const next = items.findIndex((it, i) => i > idx && !it.done);
      const anyLeft = items.findIndex((it) => !it.done);
      if (anyLeft < 0) {
        this.setData({ cardMode: false });
        wx.showToast({ title: '星牌抽完啦', icon: 'success' });
        this.loadAll();
      } else {
        this.setData({ cardIdx: next >= 0 ? next : anyLeft });
      }
    });
  },

  _grade(idx, result, after) {
    const item = (this.data.quizItems || [])[idx];
    if (!item || item.done) return;
    api.logReview(item.recordId, result).then(
      () => {
        const key = 'quizItems[' + idx + '].done';
        this.setData({ [key]: result });
        this.loadDueOnly();
        if (after) after();
      },
      (err) => wx.showToast({ title: err.message, icon: 'none', duration: 3000 })
    );
  },

  // —— 星牌抽查（T11）：同一批抽查题，一张一张翻牌 ——
  enterCards() {
    const items = this.data.quizItems || [];
    const first = items.findIndex((it) => !it.done);
    if (first < 0) {
      wx.showToast({ title: '这一轮已经抽完了', icon: 'none' });
      return;
    }
    this.setData({ cardMode: true, cardIdx: first });
  },

  exitCards() {
    this.setData({ cardMode: false });
  },

  flipCard() {
    this.toggleRef({ currentTarget: { dataset: { idx: this.data.cardIdx } } });
  },

  // —— 今日星图海报（T12）：canvas 现画，数字全部来自 stats ——
  makePoster() {
    const s = this.data.stats;
    if (!s) {
      wx.showToast({ title: '星图数据还没加载好', icon: 'none' });
      return;
    }
    const ctx = wx.createCanvasContext('posterCanvas', this);
    const W = 750, H = 1040;
    const bg = ctx.createLinearGradient(0, 0, 0, H);
    bg.addColorStop(0, '#0A0F22');
    bg.addColorStop(1, '#05070F');
    ctx.setFillStyle(bg);
    ctx.fillRect(0, 0, W, H);
    let seed = 20261101;
    const rnd = () => { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; };
    for (let i = 0; i < 90; i++) {
      ctx.setFillStyle(rnd() > 0.85 ? 'rgba(255,197,49,0.85)' : 'rgba(255,255,255,0.5)');
      ctx.beginPath();
      ctx.arc(rnd() * W, rnd() * H, rnd() * 2.2 + 0.6, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.setFillStyle('#FFC531');
    ctx.setFontSize(44);
    ctx.setTextAlign('center');
    ctx.fillText('深 空 巡 星', W / 2, 120);
    ctx.setFillStyle('#F4F1E6');
    ctx.setFontSize(30);
    const now = new Date();
    ctx.fillText(now.getFullYear() + ' 年 ' + (now.getMonth() + 1) + ' 月 ' + now.getDate() + ' 日 · 今日星图', W / 2, 178);
    const rows = [
      ['今日复习', String(s.reviewsToday || 0) + ' 次'],
      ['连续学习', String(s.streakDays || 0) + ' 天'],
      ['星瓶藏星', String(s.totalRecords || 0) + ' 颗'],
      ['已经毕业', String(s.graduatedCount || 0) + ' 条'],
    ];
    rows.forEach((r, i) => {
      const y = 320 + i * 128;
      ctx.setFillStyle('rgba(255,255,255,0.06)');
      ctx.fillRect(70, y - 74, W - 140, 104);
      ctx.setTextAlign('left');
      ctx.setFillStyle('#98A2B8');
      ctx.setFontSize(30);
      ctx.fillText(r[0], 110, y);
      ctx.setTextAlign('right');
      ctx.setFillStyle('#FFC531');
      ctx.setFontSize(44);
      ctx.fillText(r[1], W - 110, y);
    });
    ctx.setTextAlign('center');
    ctx.setFillStyle('#98A2B8');
    ctx.setFontSize(26);
    ctx.fillText('每天添一颗星，瓶子总会满', W / 2, H - 90);
    ctx.setFillStyle('#5B6B8C');
    ctx.setFontSize(22);
    ctx.fillText('全家管家 · 学习星图', W / 2, H - 48);
    ctx.draw(false, () => {
      setTimeout(() => {
        wx.canvasToTempFilePath({
          canvasId: 'posterCanvas',
          width: W, height: H, destWidth: W, destHeight: H,
          success: (res) => this.setData({ posterPath: res.tempFilePath }),
          fail: () => wx.showToast({ title: '海报生成失败', icon: 'none' }),
        }, this);
      }, 350);
    });
  },

  savePoster() {
    if (!this.data.posterPath) return;
    wx.saveImageToPhotosAlbum({
      filePath: this.data.posterPath,
      success: () => wx.showToast({ title: '已存到相册', icon: 'success' }),
      fail: () => wx.showToast({ title: '没存上：请在设置里允许保存到相册', icon: 'none', duration: 3000 }),
    });
  },

  closePoster() {
    this.setData({ posterPath: '' });
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
