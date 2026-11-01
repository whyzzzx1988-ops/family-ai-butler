// 星空航线：三类学习记录 = 三个星系，毕业进度 = 航程。数据全部来自 /api/learn/stats 的 kindStages
const api = require('../../utils/api');

const KIND_META = {
  word: { name: '单词星系', cls: 'g-word', desc: '生词与单词卡的星域' },
  mistake: { name: '错题星系', cls: 'g-mistake', desc: '错题与易错点的星域' },
  note: { name: '笔记星系', cls: 'g-note', desc: '课堂笔记与要点的星域' },
};
const KIND_ORDER = ['word', 'mistake', 'note'];

Page({
  data: {
    galaxies: [],
    total: 0,
    graduated: 0,
    pct: 0,
    loading: true,
  },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.load();
  },

  load() {
    api.getLearnStats().then(
      (s) => {
        const byKind = {};
        (s.kindStages || []).forEach((k) => { byKind[k.kind] = k; });
        const galaxies = KIND_ORDER.map((kind) => {
          const k = byKind[kind] || { total: 0, graduated: 0 };
          const meta = KIND_META[kind];
          return {
            kind,
            name: meta.name,
            cls: meta.cls,
            desc: meta.desc,
            total: k.total || 0,
            graduated: k.graduated || 0,
            pct: k.total ? Math.round((k.graduated || 0) * 100 / k.total) : 0,
            size: 150 + Math.min(110, (k.total || 0) * 5),
            arrived: (k.total || 0) > 0,
          };
        });
        const total = s.totalRecords || 0;
        const graduated = s.graduatedCount || 0;
        this.setData({
          galaxies,
          total,
          graduated,
          pct: total ? Math.round(graduated * 100 / total) : 0,
          loading: false,
        });
      },
      (err) => {
        this.setData({ loading: false });
        wx.showToast({ title: err.message, icon: 'none', duration: 3000 });
      }
    );
  },
});
