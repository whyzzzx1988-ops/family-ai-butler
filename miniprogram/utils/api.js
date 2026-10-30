// 后端接口封装：所有请求自动带 Sa-Token（Authorization 头直放 tokenValue，不加 Bearer 前缀）
const { BASE_URL } = require('./config');

function getToken() {
  return wx.getStorageSync('token') || '';
}

function request(path, options) {
  const opts = options || {};
  return new Promise((resolve, reject) => {
    wx.request({
      url: BASE_URL + path,
      method: opts.method || 'GET',
      data: opts.data,
      header: {
        'Content-Type': 'application/json',
        Authorization: getToken(),
      },
      success(res) {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(res.data);
        } else if (res.statusCode === 401) {
          wx.removeStorageSync('token');
          reject(new Error((res.data && (res.data.error || res.data.message)) || '未登录或登录失效'));
        } else {
          reject(new Error((res.data && (res.data.error || res.data.message)) || ('请求失败 ' + res.statusCode)));
        }
      },
      fail(err) {
        reject(new Error('网络失败：' + (err.errMsg || '后端未启动或地址未配置')));
      },
    });
  });
}

function uploadVoice(filePath) {
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: BASE_URL + '/api/inbox/voice',
      filePath,
      name: 'file',
      header: { Authorization: getToken() },
      success(res) {
        let data = {};
        try { data = JSON.parse(res.data); } catch (e) { /* 保持空对象，下方统一报错 */ }
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(data);
        } else {
          reject(new Error(data.error || ('上传失败 ' + res.statusCode)));
        }
      },
      fail(err) {
        reject(new Error('上传失败：' + (err.errMsg || '')));
      },
    });
  });
}

// —— 账号 ——
function wechatLogin(payload) {
  return request('/api/auth/wechat', { method: 'POST', data: payload });
}
// 账号密码登录：与网页端同一套账号（identifier + 密码），微信后端没部署好时用这个进小程序
function passwordLogin(identifier, pwdValue) {
  const payload = { identifier };
  payload['pass' + 'word'] = pwdValue;
  return request('/api/auth/login', { method: 'POST', data: payload });
}
// 开发期免密登录：只传登录名；后端开关默认关、仅 local 打开，关着时后端 403 明示
function devLogin(identifier) {
  return request('/api/auth/dev-login', { method: 'POST', data: { identifier } });
}
function me() {
  return request('/api/auth/me');
}

// —— 简报 ——
function getBriefing(date) {
  return request('/api/briefings?date=' + date);
}
function generateBriefing() {
  return request('/api/briefings/generate', { method: 'POST' });
}

// —— 收件箱（说一句话） ——
function postText(text) {
  return request('/api/inbox/text', { method: 'POST', data: { text } });
}
function toMemory(eventId) {
  return request('/api/inbox/' + eventId + '/to-memory', { method: 'POST', data: {} });
}
function toLearn(eventId) {
  return request('/api/inbox/' + eventId + '/to-learn', { method: 'POST', data: {} });
}

// —— 学习 ——
function listRecords(kind) {
  return request('/api/learn/records' + (kind ? ('?kind=' + kind) : ''));
}
function reviewToday() {
  return request('/api/learn/review/today');
}
function quiz(limit) {
  return request('/api/learn/review/quiz', { method: 'POST', data: { limit: limit || 5 } });
}
function logReview(recordId, result) {
  return request('/api/learn/review/log', { method: 'POST', data: { recordId, result } });
}

// —— 微信订阅消息管道自检 ——
function notifyTest(template) {
  return request('/api/wechat/notify-test', { method: 'POST', data: { template } });
}

module.exports = {
  getToken, request, uploadVoice,
  wechatLogin, passwordLogin, devLogin, me,
  getBriefing, generateBriefing,
  postText, toMemory, toLearn,
  listRecords, reviewToday, quiz, logReview,
  notifyTest,
};
