# 部署手册（第七周 N2）

把全家管家从本机调试搬到公网服务器，并让微信小程序能正式提审。分工：**服务器、域名、备案、微信后台手续只能账号本人办**；代码侧需要的东西（环境变量模板、compose 透传）已在本仓库备齐，照下面顺序走即可。

## 0. 前置清单（用户侧，先办，备案最慢先启动）

- [ ] 云服务器一台（2 核 4G 起步即可）：装好 Docker 与 Docker Compose v2，安全组放行 80/443（8080 只给反代本机回环用，不必公网开放）
- [ ] 域名一个并完成 **ICP 备案**（小程序正式版强制要求已备案的 HTTPS 域名；备案通常 1–3 周，最先启动）
- [ ] DeepSeek API Key、阿里云百炼 API Key（百炼账户状态需正常，否则语音转写返回 Arrearage）
- [ ] 微信公众平台（小程序后台）办 4 件事：
  1. 开发管理 → 开发设置：拿到 AppID / AppSecret；把服务器公网 IP 加进 **IP 白名单**
  2. 功能 → 订阅消息：申请 4 个模板（晨报、复习提醒、用药、待办），字段统一选 `thing1` + `date2`，记下各自模板 ID
  3. 开发管理 → 开发设置 → 服务器域名：`request 合法域名` 与 `uploadFile 合法域名` 都填 `https://你的域名`（小程序真机请求只认这里登记过的域名）
  4. 准备类目与隐私保护指引（提审必填项，细则见项目《小程序提审准备清单》）

## 1. 服务器部署

```bash
git clone https://github.com/whyzzzx1988-ops/family-ai-butler.git
cd family-ai-butler
cp .env.example .env
# 编辑 .env 填真实值（DB_PASSWORD 务必改掉；其余不填的变量对应功能会明示未配置，不假装成功）
docker compose up -d --build
docker compose ps        # app / db / redis 三个服务应为 running，db 为 healthy
curl localhost:8080/api/admin/overview   # 未带 token 返回 401 = 服务已起来
```

数据落在 Docker 卷 `butler_db`（Postgres）与容器内 `./data/audio`（语音原文件）；备份至少定期 `docker compose exec db pg_dump -U butler butler > backup.sql`。

## 2. HTTPS 反向代理（任选其一）

小程序只认 HTTPS。域名解析到服务器后：

**Caddy（最省事，自动签证书）** —— `Caddyfile`：

```
你的域名 {
    reverse_proxy 127.0.0.1:8080
}
```

**Nginx + certbot**：

```nginx
server {
    listen 443 ssl;
    server_name 你的域名;
    ssl_certificate     /etc/letsencrypt/live/你的域名/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/你的域名/privkey.pem;
    client_max_body_size 30m;   # 语音上传上限 25MB，需大于它
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

配好后浏览器打开 `https://你的域名/admin.html`，用家庭账号能登录看板 = 后端公网链路通。

## 3. 小程序切正式环境

1. 改 `miniprogram/utils/config.js`：`BASE_URL` 改为 `https://你的域名`；四个 `TEMPLATE_*` 常量填公众平台申请到的模板 ID（与服务器 `.env` 里保持一致）。订阅入口分布：晨报页有「晨报」与「用药/待办」两个订阅按钮，学习页有「复习」订阅按钮，四个模板都有入口
2. 微信开发者工具：详情 → 本地设置，取消勾选「不校验合法域名」（正式提审构建必须校验；本地连真机调试时才临时取消）
3. 真机预览一遍：微信登录、看简报、说一句（文本+录音）、学习页复习，各走通一次
4. 开发者工具「上传」→ 公众平台提交审核 → 通过后发布

## 4. 常用运维

```bash
docker compose logs -f app        # 看后端日志（推送/调度结果都在日志里）
docker compose restart app        # 改完 .env 后重启后端生效
docker compose pull && docker compose up -d --build   # 拉新代码后升级
```

## 5. 已知边界（上线前须知）

- 语音转写依赖百炼账户状态：欠费/未开通时录音照常保存、转写降级，恢复后新录音即正常，老录音不自动补转
- 订阅消息是「一次订阅推一次」：用户需在小程序里点订阅按钮授权，晨报页/学习页都放了订阅入口
- 管理页 `/admin.html` 凭家庭账号登录，数据只出本家庭；公网暴露后建议给账号设强密码
- **免密登录红线**：`butler.auth.dev-login-enabled` 是开发期开关（只填登录名就能进账号），默认关、只有 local 配置开；上线前务必确认 prod 没有打开它，打开状态下绝不能对公网提供服务
- **种子账号红线**：`DevSeedInitializer` 只在 local Profile 加载，启动自动建演示家庭（`yuge` / `xifu`，密码 `dev123456`）；prod 不加载本类，上线后须确认库里没有这套演示账号
