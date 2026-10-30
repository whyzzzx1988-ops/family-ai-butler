// 环境约定（第五周 A1）：
// - 微信开发者工具模拟器调试：保持 127.0.0.1，并在开发者工具里勾选「不校验合法域名」
// - 真机调试：改成电脑的局域网 IP（如 http://192.168.1.10:8080），手机与电脑同一 WiFi
// - 正式版：改成已备案的 HTTPS 域名，并在小程序公众平台「开发管理 → 服务器域名」里配置 request/uploadFile 合法域名
const BASE_URL = 'http://127.0.0.1:8080';

// 订阅消息模板 ID：在公众平台「功能 → 订阅消息」申请后填入，并与后端 WECHAT_TEMPLATE_* 保持一致
// 留空时页面上的订阅按钮会提示未配置
const TEMPLATE_BRIEFING_ID = '';
const TEMPLATE_REVIEW_ID = '';

module.exports = { BASE_URL, TEMPLATE_BRIEFING_ID, TEMPLATE_REVIEW_ID };
