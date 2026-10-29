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

IDE 里调试同理：Active profiles 设为 `local` 后直接 Run/Debug。此模式数据库是 H2 内存库（迁移走 `db/migration-h2`，重启清零），token 存内存，不需要本机装 Postgres 和 Redis。H2 控制台：http://localhost:8080/h2-console（JDBC URL 填 `jdbc:h2:mem:butler`）。

附注：想用真 Postgres 数据又在 IDE 里断点调试，可以 `docker compose up -d db redis` 只起依赖，再用默认 Profile 本地 Run。

启动后自检：`POST http://localhost:8080/api/inbox/text`，body 示例：

```json
{"familyId": 1, "memberId": 1, "text": "明天下午三点提醒我开会"}
```

返回 202 + `status: accepted` 即骨架管道通。鉴权管道自检：`POST /api/auth/login`，body `{"memberId": 1, "password": "dev"}`，返回 tokenValue 即 Sa-Token 打通（骨架期未校验密码，D3 接入 BCrypt 校验）。

## 包结构

- `core.account / memory / voice / llm / agent / proactive / inbox`：共用底座
- `module.work / learn / health`：业务模块挂载点，只通过底座接口协作，不跨包查表

## 密钥

所有密钥只走环境变量（见 docker-compose.yml 与 application.yml 的占位），绝不入库。
