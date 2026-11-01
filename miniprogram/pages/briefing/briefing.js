const api = require('../../utils/api');
const config = require('../../utils/config');
const { createSpeechPlayer } = require('../../utils/speech');

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
    contentMd: '', // 简报原文（播报时传给后端分段）
    generating: false,
    speechState: 'idle', // idle / loading / playing / paused / ended / error
    speechLabel: '朗读简报',
    speechHint: '',
    speechDots: [], // 朗读进度星点阵：done 已播 / now 当前段 / todo 未播；段数 >30 时不渲染点阵只留文字
    familyRows: [],
  },

  onLoad() {
    this.speechPlayer = createSpeechPlayer((info) => this.onSpeechState(info));
  },

  onHide() {
    if (this.speechPlayer && this.data.speechState === 'playing') {
      this.speechPlayer.pause();
    }
  },

  onUnload() {
    if (this.speechPlayer) {
      this.speechPlayer.destroy();
    }
  },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.setData({ date: todayStr() });
    this.load();
    this.loadFamily();
  },

  // 全家星图（T14）：每个成员的累计星数与近 7 日复习，数据来自 /api/learn/stats/family
  loadFamily() {
    const ROLE_LABEL = { self: '户主', spouse: '配偶', child: '孩子' };
    api.getFamilyStats().then(
      (rows) => {
        const familyRows = (rows || []).map((r) => ({
          nickname: r.nickname,
          roleLabel: ROLE_LABEL[r.role] || r.role,
          total: r.totalRecords || 0,
          reviews7: r.reviews7Days || 0,
          streak: r.streakDays || 0,
        }));
        this.setData({ familyRows });
      },
      () => {}
    );
  },

  load() {
    this.setData({ loading: true });
    api.getBriefing(this.data.date).then(
      (data) => {
        this.setData({ loading: false, lines: toLines(data.contentMd), contentMd: data.contentMd || '' });
      },
      () => {
        // 404 = 今天还没生成，给生成按钮
        this.setData({ loading: false, lines: null });
      }
    );
  },

  generate() {
    if (this.speechPlayer) {
      this.speechPlayer.stop();
    }
    this.setData({ generating: true });
    api.generateBriefing().then(
      (data) => {
        this.setData({ generating: false, lines: toLines(data.contentMd), contentMd: data.contentMd || '' });
      },
      (err) => {
        this.setData({ generating: false });
        wx.showToast({ title: err.message, icon: 'none', duration: 3000 });
      }
    );
  },

  // —— 简报播报 ——
  buildSpeechDots(total, index, allDone) {
    if (!total || total > 30) return [];
    const dots = [];
    for (let i = 0; i < total; i++) {
      dots.push({ cls: allDone || i < index ? 'done' : (i === index ? 'now' : 'todo') });
    }
    return dots;
  },

  onSpeechState(info) {
    const seg = info.total > 0 ? '第 ' + (info.index + 1) + '/' + info.total + ' 段' : '';
    if (info.state === 'loading') {
      this.setData({ speechState: 'loading', speechLabel: '正在准备…', speechHint: info.total > 0 ? '正在合成' + seg : '正在生成播报计划…', speechDots: this.buildSpeechDots(info.total, info.index, false) });
    } else if (info.state === 'playing') {
      this.setData({ speechState: 'playing', speechLabel: '暂停朗读', speechHint: '正在朗读' + seg, speechDots: this.buildSpeechDots(info.total, info.index, false) });
    } else if (info.state === 'paused') {
      this.setData({ speechState: 'paused', speechLabel: '继续朗读', speechHint: '已暂停 · ' + seg, speechDots: this.buildSpeechDots(info.total, info.index, false) });
    } else if (info.state === 'ended') {
      this.setData({ speechState: 'ended', speechLabel: '重新朗读', speechHint: '朗读完毕', speechDots: this.buildSpeechDots(info.total, info.index, true) });
    } else if (info.state === 'error') {
      this.setData({ speechState: 'error', speechLabel: '重新朗读', speechHint: '', speechDots: [] });
      wx.showToast({ title: info.message || '播报失败', icon: 'none', duration: 3000 });
    } else {
      this.setData({ speechState: 'idle', speechLabel: '朗读简报', speechHint: '', speechDots: [] });
    }
  },

  toggleSpeech() {
    if (!this.speechPlayer) return;
    const state = this.data.speechState;
    if (state === 'playing') {
      this.speechPlayer.pause();
      return;
    }
    if (state === 'paused') {
      this.speechPlayer.resume();
      return;
    }
    if (state === 'loading') return;
    if (!this.data.contentMd) {
      wx.showToast({ title: '先生成简报再朗读', icon: 'none' });
      return;
    }
    this.speechPlayer.play(this.data.contentMd);
  },

  stopSpeech() {
    if (this.speechPlayer) {
      this.speechPlayer.stop();
    }
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
