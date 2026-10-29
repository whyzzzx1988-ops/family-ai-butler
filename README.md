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

启动后自检：`POST http://localhost:8080/api/inbox/text`，body 示例：

```json
{"familyId": 1, "memberId": 1, "text": "明天下午三点提醒我开会"}
```

返回 202 + `status: accepted` 即骨架管道通。

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
