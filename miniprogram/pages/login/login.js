const api = require('../../utils/api');

Page({
  data: {
    needBind: false,
    identifier: '',
    password: '',
    busy: false,
  },

  onLoad() {
    if (api.getToken()) {
      wx.switchTab({ url: '/pages/briefing/briefing' });
    }
  },

  onIdentifierInput(e) {
    this.setData({ identifier: e.detail.value });
  },

  onPasswordInput(e) {
    const d = {};
    d['password'] = e.detail.value;
    this.setData(d);
  },

  // wx.login 拿 code 调后端：已绑定直登，未绑定展开绑定表单
  doLogin() {
    if (this.data.busy) return;
    this.setData({ busy: true });
    wx.login({
      success: (res) => {
        api.wechatLogin({ code: res.code }).then(
          (data) => {
            this.setData({ busy: false });
            this.onLoggedIn(data);
          },
          (err) => {
            this.setData({ busy: false });
            if (err.message.indexOf('尚未绑定') >= 0) {
              this.setData({ needBind: true });
              wx.showToast({ title: '首次使用，先绑定家庭账号', icon: 'none' });
            } else {
              wx.showToast({ title: err.message, icon: 'none', duration: 3000 });
            }
          }
        );
      },
      fail: () => {
        this.setData({ busy: false });
        wx.showToast({ title: '微信登录失败，请重试', icon: 'none' });
      },
    });
  },

  // 账号密码登录：不走微信那套，后端没配 AppID/Secret 时也能进；账号与网页端通用
  doPasswordLogin() {
    if (this.data.busy) return;
    if (!this.data.identifier || !this.data.password) {
      wx.showToast({ title: '请填登录名和密码', icon: 'none' });
      return;
    }
    this.setData({ busy: true });
    api.passwordLogin(this.data.identifier, this.data.password).then(
      (data) => {
        this.setData({ busy: false });
        this.onLoggedIn(data);
      },
      (err) => {
        this.setData({ busy: false });
        wx.showToast({ title: err.message, icon: 'none', duration: 3000 });
      }
    );
  },

  // 绑定：code 一次性，必须重新 wx.login 取新 code，并同带账号密码过一次校验
  doBind() {
    if (this.data.busy) return;
    if (!this.data.identifier || !this.data.password) {
      wx.showToast({ title: '请填登录名和密码', icon: 'none' });
      return;
    }
    this.setData({ busy: true });
    wx.login({
      success: (res) => {
        const payload = { code: res.code, identifier: this.data.identifier };
        payload['password'] = this.data.password;
        api.wechatLogin(payload).then(
          (data) => {
            this.setData({ busy: false });
            this.onLoggedIn(data);
          },
          (err) => {
            this.setData({ busy: false });
            wx.showToast({ title: err.message, icon: 'none', duration: 3000 });
          }
        );
      },
      fail: () => {
        this.setData({ busy: false });
        wx.showToast({ title: '微信登录失败，请重试', icon: 'none' });
      },
    });
  },

  onLoggedIn(data) {
    wx.setStorageSync('token', data.tokenValue);
    wx.showToast({ title: data.bound ? '绑定成功' : '登录成功', icon: 'success' });
    wx.switchTab({ url: '/pages/briefing/briefing' });
  },
});
