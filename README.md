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

待办候选自检（第二周 W2，接着上面的纪要走）。生成纪要时同轮会再调一次网关抽候选，记纪要 id 为 N：

```bash
# 1) 看候选：W1 第 2 步的响应里 todoCandidates 应非空——「联系设计师确认图标」带 ownerHint=张三、
#    上线相关候选带 dueHint=下周三（线索只存原文，不解析成日期）；llm_call_log 里应有两行 module=work（纪要 + 抽取）
# 2) 勾选确认一条，并按线索给它一个到期时间（<明天日期> 换成实际日期）→ 201，createdTodos 里 sourceEventId=E、负责人是纪要创建者
curl -X POST localhost:8080/api/work/minutes/N/todos/confirm -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"items":[{"index":0,"dueAt":"<明天日期>T10:00:00+08:00"}]}'
# 3) 待办列表 → 刚确认的那条在列，sourceEventId=E
curl localhost:8080/api/todos -H "Authorization: T"
# 4) 同一下标再确认 → 409（防重复建待办）；下标越界 → 400
curl -X POST localhost:8080/api/work/minutes/N/todos/confirm -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"items":[{"index":0}]}'
# 5) 合流验收：生成「明天」的简报 → 「今日工作」段应出现这条待办
curl -X POST "localhost:8080/api/briefings/generate?date=<明天日期>" -H "Authorization: T"
```

纪要列表/详情自检（第二周 W4，工作模块收尾）：

```bash
# 1) 列表 → 本家庭纪要摘要（id / 标题 / 候选数 / 已确认数），新到旧，不带正文
curl localhost:8080/api/work/minutes -H "Authorization: T"
# 2) 详情（N 换成列表里的纪要 id）→ 全文 contentMd + 候选清单 + 已确认下标
curl localhost:8080/api/work/minutes/N -H "Authorization: T"
# 3) 不存在的纪要 id → 404
curl localhost:8080/api/work/minutes/999999 -H "Authorization: T"
```

收件箱解析与转记忆自检（第二周 W3，补第一周欠账）。规则打标是纯关键词匹配（优先级：纪要/会议记录 → minutes，提醒/别忘了 → reminder，记住/记一下/记下来/备忘 → memory，其余 unknown），文本落库与语音转写回填时都会打标：

```bash
# 1) 三句话验打标 → parsedType 应分别为 memory / reminder / minutes；随便一句话 → unknown
curl -X POST localhost:8080/api/inbox/text -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"text":"记住，儿子对花生过敏"}'
curl -X POST localhost:8080/api/inbox/text -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"text":"明天下午三点提醒我开会"}'
curl -X POST localhost:8080/api/inbox/text -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"text":"这段录音帮我记纪要"}'
# 2) 把第一条（记 id 为 V）一键转记忆 → 201，返回记忆条目（source=text、scope=common、kind=event），带 eventId=V
curl -X POST localhost:8080/api/inbox/V/to-memory -H "Authorization: T" -H "Content-Type: application/json" -d '{}'
# 3) 记忆列表 → 应能看到这条
curl localhost:8080/api/memories -H "Authorization: T"
# 4) 想直接喂简报的学习/睡眠段：转记忆时带 scope，如 {"scope":"health","kind":"record","visibility":"family"}
# 5) 不存在的事件 id 转记忆 → 404
curl -X POST localhost:8080/api/inbox/999999/to-memory -H "Authorization: T" -H "Content-Type: application/json" -d '{}'
```

ASR 恢复后，语音事件同法验收：上传一段「记住……」的语音，转写回填后 parsedType=memory，点 to-memory 后记忆里 source=voice。

学习记录自检（第三周 L1–L4，学习模块第一件）。统一表 `learn_record`（kind=word/mistake/note），加工走 LlmGateway（module=learn 落账），简报学习段直查本表（第四周 R3 起，双写记忆已下线）。先从成员列表里记下孩子的 member id，记为 C：

```bash
# 0) 拿孩子成员 id（D3 自检里建的「儿子」）
curl localhost:8080/api/families/members -H "Authorization: T"
# 1) 直接文本建单词记录（归属孩子）→ 201，detail 应有 headword/phonetic/meaning/example，响应带 llmLogId，reviewStage=0、nextReviewAt 为明天
curl -X POST localhost:8080/api/learn/records -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"kind":"word","subject":"英语","content":"今天学了单词 abandon","childMemberId":C}'
# 2) 乱填 childMemberId → 400；kind 乱填 → 400
curl -X POST localhost:8080/api/learn/records -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"kind":"word","content":"单词 apple","childMemberId":999999}'
# 3) inbox 转学习：先发一条错题文本（parsedType 应为 learn），记事件 id 为 L，再一键转记录（kind 自动推断 mistake，detail 应有 knowledgePoints/analysis）
curl -X POST localhost:8080/api/inbox/text -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"text":"今天数学做错了一道分数加法题，总是通分出错"}'
curl -X POST localhost:8080/api/inbox/L/to-learn -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"subject":"数学","childMemberId":C}'
# 4) 列表过滤：kind=word 只剩单词那条；按 memberId=C 过滤都是孩子的
curl "localhost:8080/api/learn/records?kind=word" -H "Authorization: T"
# 5) 简报合流：生成「明天」的简报 → 「孩子昨日学习」段应出现今天建的、归属孩子的学习内容
curl -X POST "localhost:8080/api/briefings/generate?date=<明天日期>" -H "Authorization: T"
```

复习与抽查自检（第四周 R1–R3）。调度规则：建记录 1 天后第一次到期，mastered 进一段（1→3→7 天，满 3 段毕业），fuzzy 原地 1 天后重试，forgot 回第一段。新记录要等明天才到期，自检先在 IDEA 的 H2 控制台里把一条记录「拨到期」造数（R 换成学习记录 id）：

```sql
update learn_record set next_review_at = current_timestamp where id = R;
```

```bash
# 1) 今日待复习（按孩子过滤）→ 应有刚拨到期的那条
curl "localhost:8080/api/learn/review/today?memberId=C" -H "Authorization: T"
# 2) 抽查出题 → items 与待复习条目一一对应（带 llmLogId；若 note 说改用兜底题，是模型出题降级，题仍齐）
curl -X POST localhost:8080/api/learn/review/quiz -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"memberId":C,"limit":5}'
# 3) 简报计数：生成今日简报 → 「孩子昨日学习」段末应有「今日待复习：N 条」（数据源已是 learn_record，不再靠双写记忆）
curl -X POST localhost:8080/api/briefings/generate -H "Authorization: T"
# 4) 回记 mastered → 201，record 的 reviewStage 应 +1、nextReviewAt 顺延（0→1 段为 +3 天）；再查今日列表，该条应已消失
curl -X POST localhost:8080/api/learn/review/log -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"recordId":R,"result":"mastered"}'
# 5) result 乱填 → 400（改 result 为 good 试）；不存在的 recordId → 404
curl -X POST localhost:8080/api/learn/review/log -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"recordId":999999,"result":"mastered"}'
```

微信登录与订阅消息自检（第五周 A1/A3）。后端先配环境变量 `WECHAT_APPID` / `WECHAT_SECRET`（你自己的小程序，在公众平台「开发管理 → 开发设置」里看）再重启后端；小程序工程打开方式见 `miniprogram/README.md`。curl 只能验到「未配置/参数校验」层，真 code 只能从小程序里 wx.login 拿（一次性、5 分钟有效），完整链路在开发者工具里走登录页：

```bash
# 1) 未配 WECHAT_* 时 → 503 明示（密码登录不受影响）
curl -X POST localhost:8080/api/auth/wechat -H "Content-Type: application/json" -d '{"code":"x"}'
# 2) 配好后在开发者工具里打开小程序：首次登录 → 展开绑定表单 → 填网页端登录名/密码 → 绑定成功进简报页；杀掉重开 → 免密直登
# 3) 订阅管道：先在公众平台申请模板、后端配 WECHAT_TEMPLATE_BRIEFING_ID（模板字段需含 thing1/date2，不同模板改 WechatController 测试数据的键），
#    小程序简报页点「订阅晨报提醒」授权一次，再调下面接口 → ok=true 表示管道通；ok=false 时 message 会写明卡在哪一步
curl -X POST localhost:8080/api/wechat/notify-test -H "Authorization: T" -H "Content-Type: application/json" -d '{}'
# 4) 自动触发（第七周 N1）：晨报 07:00 生成后自动尝试推送（模板=BRIEFING）；每晚 19:00 若孩子名下有到期未复习条目，
#    给户主推复习提醒（模板=REVIEW，cron 可调 butler.learn.review-remind-cron）。模板未配时自动跳过、只记日志，简报与复习数据不受影响；
#    订阅消息一次授权只能推一条，想天天收需要用户在小程序里定期再点订阅（微信平台规则）。
```

健康记录自检（第六周 B1–B4，健康模块第一件）。红线：只记录与管理，不解读。简报睡眠段数据源已切到 `health_record`（只读本人记录），用药计划进晨报「今日用药」段：

```bash
# 1) 直录一条睡眠 → 201，kind=sleep，detail 里应有 durationHours（值 7，规则抽出，不走模型）
curl -X POST localhost:8080/api/health/records -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"content":"昨晚 11 点睡，睡了 7 小时"}'
# 2) 结构化录一条用药计划 → 201，content 应自动拼成「维生素D 每日一片（每天 08:00）」
curl -X POST localhost:8080/api/health/records -H "Authorization: T" -H "Content-Type: application/json" \
  -d '{"kind":"medication","detail":{"medicine":"维生素D","dosage":"每日一片","remindTime":"08:00"}}'
# 3) inbox 链路：发一句健康的话 → parsedType 应为 health；再 to-health 转入（E 换成事件 id）
curl -X POST localhost:8080/api/inbox/text -H "Authorization: T" -H "Content-Type: application/json" -d '{"text":"今天体重 70 公斤"}'
curl -X POST localhost:8080/api/inbox/E/to-health -H "Authorization: T" -H "Content-Type: application/json" -d '{}'
# 4) 简报合流：生成「明天」的简报（date 是 query 参数）→ 睡眠段应有第 1 条（窗口 D-1 12:00 起），并多出「今日用药」段含第 2 条
curl -X POST "localhost:8080/api/briefings/generate?date=明天日期" -H "Authorization: T"
# 5) 删除第 2 条用药计划（R 换成其 id）→ 再生成简报，「今日用药」段应消失；跨家庭 id → 404
curl -X DELETE localhost:8080/api/health/records/R -H "Authorization: T"
# 6) 睡眠周报（第七周 N3）：生成 type=weekly（date 为截止日，统计其前 7 天）→ 「本周睡眠」段应有记录晚数/平均/最长/最短与逐晚明细，只报事实不评价；
#    正式节奏为每周一 07:30 自动生成并推送（butler.briefing.weekly-cron）
curl -X POST "localhost:8080/api/briefings/generate?date=下周一日期&type=weekly" -H "Authorization: T"
curl "localhost:8080/api/briefings?date=下周一日期&type=weekly" -H "Authorization: T"
# 7) 用药按点推送（第七周 N3）：后端配 WECHAT_TEMPLATE_MEDICATION_ID（用药模板，字段同为 thing1/date2），把第 2 条计划的 remindTime
#    改成当前时间的下一分钟（重录一条即可），归属成员在小程序里订阅一次 → 到点应收到推送；也可先用 notify-test 的 medication 打通管道
curl -X POST localhost:8080/api/wechat/notify-test -H "Authorization: T" -H "Content-Type: application/json" -d '{"template":"medication"}'
```

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
