const api = require('../../utils/api');
const config = require('../../utils/config');

function todayStr() {
  const d = new Date();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return d.getFullYear() + '-' + m + '-' + day;
}

// 简报是 Markdown，按行拆成带样式的对象再渲染（WXML 不支持字符串方法）
function toLines(contentMd) {
  return (contentMd || '').split('\n').map((raw) => {
    if (raw.indexOf('## ') === 0) return { text: raw.slice(3), cls: 'h' };
    if (raw.indexOf('# ') === 0) return { text: raw.slice(2), cls: 'h1' };
    return { text: raw, cls: '' };
  });
}

Page({
  data: {
    date: '',
    loading: false,
    lines: null, // null = 还没生成；数组 = 简报逐行
    generating: false,
  },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.setData({ date: todayStr() });
    this.load();
  },

  load() {
    this.setData({ loading: true });
    api.getBriefing(this.data.date).then(
      (data) => {
        this.setData({ loading: false, lines: toLines(data.contentMd) });
      },
      () => {
        // 404 = 今天还没生成，给生成按钮
        this.setData({ loading: false, lines: null });
      }
    );
  },

  generate() {
    this.setData({ generating: true });
    api.generateBriefing().then(
      (data) => {
        this.setData({ generating: false, lines: toLines(data.contentMd) });
      },
      (err) => {
        this.setData({ generating: false });
        wx.showToast({ title: err.message, icon: 'none', duration: 3000 });
      }
    );
  },

  // 订阅晨报：订阅消息一次授权只能推一条，触发管道自检走后端 /api/wechat/notify-test
  subscribe() {
    const tmplId = config.TEMPLATE_BRIEFING_ID;
    if (!tmplId) {
      wx.showToast({ title: '订阅模板未配置（见 utils/config.js）', icon: 'none', duration: 3000 });
      return;
    }
    wx.requestSubscribeMessage({
      tmplIds: [tmplId],
      success: () => {
        api.notifyTest('briefing').then(
          (res) => wx.showToast({ title: res.ok ? '测试消息已发' : res.message, icon: 'none', duration: 3000 }),
          (err) => wx.showToast({ title: err.message, icon: 'none', duration: 3000 })
        );
      },
      fail: () => wx.showToast({ title: '未完成订阅授权', icon: 'none' }),
    });
  },

  // 订阅用药/待办提醒（N2 补入口）：两个模板一次授权（单次上限 3 个）；推送由后端按点触发
  subscribeMore() {
    const ids = [config.TEMPLATE_MEDICATION_ID, config.TEMPLATE_TODO_ID].filter((x) => x);
    if (!ids.length) {
      wx.showToast({ title: '用药/待办模板未配置（见 utils/config.js）', icon: 'none', duration: 3000 });
      return;
    }
    wx.requestSubscribeMessage({
      tmplIds: ids,
      success: () => wx.showToast({ title: '已订阅用药与待办提醒', icon: 'none', duration: 3000 }),
      fail: () => wx.showToast({ title: '未完成订阅授权', icon: 'none' }),
    });
  },
});
