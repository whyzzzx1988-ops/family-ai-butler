const api = require('../../utils/api');

const TYPE_LABEL = {
  minutes: '会议纪要',
  reminder: '提醒',
  memory: '记忆',
  learn: '学习',
  unknown: '待整理',
};

const recorder = wx.getRecorderManager();

Page({
  data: {
    text: '',
    busy: false,
    recording: false,
    lastEvent: null, // {id, label, text, status, transcript}
  },

  onLoad() {
    recorder.onStop((res) => {
      this.setData({ recording: false, busy: true });
      api.uploadVoice(res.tempFilePath).then(
        (data) => {
          this.setData({ busy: false });
          this.showEvent(data);
        },
        (err) => {
          this.setData({ busy: false });
          wx.showToast({ title: err.message, icon: 'none', duration: 3000 });
        }
      );
    });
    recorder.onError(() => {
      this.setData({ recording: false });
      wx.showToast({ title: '录音失败，请检查麦克风权限', icon: 'none' });
    });
  },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
    }
  },

  onTextInput(e) {
    this.setData({ text: e.detail.value });
  },

  submitText() {
    const text = (this.data.text || '').trim();
    if (!text || this.data.busy) return;
    this.setData({ busy: true });
    api.postText(text).then(
      (data) => {
        this.setData({ busy: false, text: '' });
        this.showEvent(data);
      },
      (err) => {
        this.setData({ busy: false });
        wx.showToast({ title: err.message, icon: 'none', duration: 3000 });
      }
    );
  },

  toggleRecord() {
    if (this.data.recording) {
      recorder.stop();
      return;
    }
    wx.authorize({
      scope: 'scope.record',
      success: () => {
        this.setData({ recording: true });
        recorder.start({ format: 'mp3', duration: 60000 });
      },
      fail: () => wx.showToast({ title: '请在设置里允许录音权限', icon: 'none' }),
    });
  },

  showEvent(data) {
    const label = TYPE_LABEL[data.parsedType] || '待整理';
    let status = '';
    if (data.status && data.status !== 'done') {
      status = data.status === 'asr_unconfigured'
        ? '（语音已保存，后端未配 ASR Key，暂无转写）'
        : '（状态：' + data.status + '）';
    }
    this.setData({
      lastEvent: {
        id: data.id,
        label,
        text: data.transcript || data.text || '',
        status,
      },
    });
  },

  toMemory() {
    if (!this.data.lastEvent) return;
    api.toMemory(this.data.lastEvent.id).then(
      () => wx.showToast({ title: '已存入长期记忆', icon: 'success' }),
      (err) => wx.showToast({ title: err.message, icon: 'none', duration: 3000 })
    );
  },

  toLearn() {
    if (!this.data.lastEvent) return;
    api.toLearn(this.data.lastEvent.id).then(
      () => wx.showToast({ title: '已进错题本/单词本', icon: 'success' }),
      (err) => wx.showToast({ title: err.message, icon: 'none', duration: 3000 })
    );
  },
});
