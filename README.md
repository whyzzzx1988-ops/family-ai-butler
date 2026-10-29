# family-ai-butler

三合一家庭 AI 管家：一个账号、一个订阅，覆盖工作、孩子学习、全家健康。
共用底座建一次，三个模块逐个挂载。方案见《AI改变三件事-三合一总方案-v1》与《底座骨架技术设计-v1》。

## 入口形态（2026-10-29 拍板）

Web 先行（响应式，一套覆盖电脑与手机浏览器），小程序第 5 周再补。

## 两种启动方式

**方式一：全 Docker（接近生产，验收用）**

```bash
docker compose up --build
```

app + Postgres(pgvector) + Redis 全在容器里，数据落库不丢，迁移走 `db/migration`。

**方式二：本地调试（不用 Docker，日常开发用）**

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

也可以什么都不带直接 Run/Debug：`application.yml` 已把默认 Profile 设为 local，没指定时自动走这条路（Docker 部署由 compose 显式注入 prod，不受影响）。此模式数据库是 H2 内存库（迁移走 `db/migration-h2`，重启清零），token 存内存，不需要本机装 Postgres 和 Redis。H2 控制台：http://localhost:8080/h2-console（JDBC URL 填 `jdbc:h2:mem:butler`）。

IDEA 里看数据：新建 **H2** 数据源（不要建 PostgreSQL 的，本地没有 5432 在跑），URL 填 `jdbc:h2:tcp://localhost:9092/mem:butler`，用户 `sa`、密码留空。local 模式启动应用时会自动在 9092 端口起 H2 TCP 服务，连的就是应用正在用的同一个内存库；应用没启动时连不上属正常。

附注：想用真 Postgres 数据又在 IDE 里断点调试，可以 `docker compose up -d db redis` 只起依赖，再用默认 Profile 本地 Run。

收件箱自检（D4，文本/语音都真落库，家庭与成员从 token 反查，body 不再传 familyId/memberId）：

```bash
# 文本进箱（带 D3 的 token T）→ 201，返回事件（含 id / status=new）
curl -X POST localhost:8080/api/inbox/text -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"text":"明天下午三点提醒我开会"}'
# 语音进箱 → 201，响应 text 字段即转写结果（短语音数秒内返回）
curl -X POST localhost:8080/api/inbox/voice -H "Authorization: T" -F "file=@test.wav"
# 列表（Web 端可见的形态）→ 本家庭最近事件，新到旧
curl localhost:8080/api/inbox -H "Authorization: T"
```

语音转写走 Paraformer 实时版（DashScope WebSocket 推流，不需要音频公网地址）。启动应用前先设百炼 API Key 环境变量：PowerShell 里 `$env:DASHSCOPE_API_KEY="sk-..."` 再启动。没设 Key 时语音仍会落盘并返回 `asr_unconfigured` 状态（不丢音频）；音频文件存在项目 `data/audio/`（不入库），支持 wav/mp3/pcm/aac/m4a/amr/opus，请用单声道录音。

长期记忆自检（D5，存/查/删 + 隔离）。继续用 D3 的 token T（户主）和配偶 token T2（用 xifu 账号登录拿）：

```bash
# 1) 存一条私有记忆 → 201，记 id 为 M
curl -X POST localhost:8080/api/memories -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"scope":"work","kind":"preference","content":"开会喜欢先看结论再看细节"}'
# 2) 存一条全家可见的学习记忆
curl -X POST localhost:8080/api/memories -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"scope":"learn","kind":"record","content":"儿子本周背了 50 个单词","visibility":"family"}'
# 3) 我的列表 → 两条都在；按 scope=learn 过滤 → 只剩学习那条
curl localhost:8080/api/memories -H "Authorization: T"
# 4) 配偶视角 → 只能看到全家可见的那条，看不到私有记忆；直接按 id 查私有记忆 → 404
curl localhost:8080/api/memories -H "Authorization: T2"
# 5) 删除私有记忆 → 再查该 id → 404，列表里也消失
curl -X DELETE localhost:8080/api/memories/M -H "Authorization: T"
```

全家简报自检（D6，三段式，缺数据明说）。继续用 token T：

```bash
# 1) 空数据先生成今日简报 → 三段应分别写明「暂无到期待办 / 暂无学习记录 / 暂无睡眠记录」，不编造
curl -X POST localhost:8080/api/briefings/generate -H "Authorization: T"
# 2) 造数据：一条明天 10:00 到期的待办、一条学习记录、一条睡眠记录（后两条走记忆接口）
curl -X POST localhost:8080/api/todos -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"title":"项目周会","dueAt":"<明天日期>T10:00:00+08:00"}'
curl -X POST localhost:8080/api/memories -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"scope":"learn","kind":"record","content":"儿子背了 50 个单词","visibility":"family"}'
curl -X POST localhost:8080/api/memories -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"scope":"health","kind":"record","content":"昨晚 23:20 睡，睡了约 7 小时"}'
# 3) 生成「明天」的简报（date=明天）→ 工作段有 10:00 项目周会、学习段有背单词、睡眠段有睡眠记录
curl -X POST "localhost:8080/api/briefings/generate?date=<明天日期>" -H "Authorization: T"
# 4) 按日期读回
curl "localhost:8080/api/briefings?date=<明天日期>" -H "Authorization: T"
```

定时任务每天 07:00（Asia/Shanghai）自动为每个家庭生成当日简报，应用开着即可在日志里看到「晨间简报已生成」。

模型网关自检（D7，每次调用落 llm_call_log、成本可汇总）。启动前先设 DeepSeek Key：PowerShell 里 `$env:DEEPSEEK_API_KEY="sk-..."` 再启动应用，然后：

```bash
# 1) 聊一句 → 返回 reply 的同时给 logId，成本按 yml 参考价折算
curl -X POST localhost:8080/api/llm/chat -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"prompt":"用一句话介绍你自己"}'
# 2) 用量汇总 → 按模型列出调用数 / token / 成本，失败调用单独计数
curl localhost:8080/api/llm/usage -H "Authorization: T"
# 3) 在 IDEA 数据源里看 llm_call_log 表，应有刚那一行（status=ok、tokens 与 cost 非零）
```

没设 Key 时 chat 返回 503 明示，不会假装成功。

纪要生成自检（第二周 W1，工作模块第一件）。启动前同样先设 `DEEPSEEK_API_KEY`，继续用 token T。百炼 ASR 还没恢复也没关系：先用一条文本事件当「已转写文本」把纪要链路跑通，真语音事件等转写补验后同法验证：

```bash
# 1) 造一条带会议内容的文本事件（模拟已转写的录音），记返回的 id 为 E
curl -X POST localhost:8080/api/inbox/text -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"text":"今天项目周会定了两件事：一是首页改版下周三上线，二是张三负责联系设计师确认图标，预算先按五万走，后续再对账。"}'
# 2) 生成纪要 → 201，contentMd 应含 # 标题与 ## 背景 / ## 结论 / ## 讨论要点 三段，内容对得上原文，响应带 llmLogId
curl -X POST localhost:8080/api/work/minutes -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"eventId":E}'
# 3) 对账：用量汇总里应多一次调用；IDEA 里看 llm_call_log，应有一行 module=work 的新账
curl localhost:8080/api/llm/usage -H "Authorization: T"
# 4) 同一事件再生成一次 → 409（一条事件只生成一份纪要，不重复花模型调用）
curl -X POST localhost:8080/api/work/minutes -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"eventId":E}'
# 5) 不存在的事件 id → 404
curl -X POST localhost:8080/api/work/minutes -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"eventId":999999}'
```

在 IDEA 数据源里应能看到新表 `meeting_note`，纪要行 event_id=E 与来源事件一一对应。

账号与家庭自检（D3，密码已是 BCrypt 真校验）。请求头带 token 时用 `Authorization: <tokenValue>`（Sa-Token 直读，不要加 Bearer 前缀）：

```bash
# 1) 建家庭（= 注册户主），返回 201 + tokenValue，记为 T
curl -X POST localhost:8080/api/families -H "Content-Type: application/json" \
  -d '{"familyName":"我的家","ownerNickname":"宇哥","identifier":"yuge","password":"dev123456"}'
# 2) 密码错登录 → 401 {"error":"用户名或密码不正确"}
curl -X POST localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"identifier":"yuge","password":"wrong"}'
# 3) 密码对登录 → 200 + tokenValue
curl -X POST localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"identifier":"yuge","password":"dev123456"}'
# 4) 带 token 加两个成员（一个带账号、一个孩子先不给账号）
curl -X POST localhost:8080/api/families/members -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"nickname":"媳妇","role":"spouse","identifier":"xifu","password":"dev123456"}'
curl -X POST localhost:8080/api/families/members -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"nickname":"儿子","role":"child"}'
# 5) 成员列表 → 应有 3 人且都在同一个 familyId；不带 token 访问 → 401
curl localhost:8080/api/families/members -H "Authorization: T"
```

## 包结构

- `core.account / memory / voice / llm / agent / proactive / inbox`：共用底座
- `module.work / learn / health`：业务模块挂载点，只通过底座接口协作，不跨包查表

## 密钥

所有密钥只走环境变量（见 docker-compose.yml 与 application.yml 的占位），绝不入库。
