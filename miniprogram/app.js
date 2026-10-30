App({
  onLaunch() {
    // 冷启动没 token 直接去登录页（登录页不在 tabBar 里，用 reLaunch）
    if (!wx.getStorageSync('token')) {
      wx.reLaunch({ url: '/pages/login/login' });
    }
  },
  globalData: {},
});
