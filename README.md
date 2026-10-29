# family-ai-butler

三合一家庭 AI 管家：一个账号、一个订阅，覆盖工作、孩子学习、全家健康。
共用底座建一次，三个模块逐个挂载。方案见《AI改变三件事-三合一总方案-v1》与《底座骨架技术设计-v1》。

## 入口形态（2026-10-29 拍板）

Web 先行（响应式，一套覆盖电脑与手机浏览器），小程序第 5 周再补。

## 本地启动

```bash
docker compose up -d db redis
mvn spring-boot:run
# 或整套容器：docker compose up --build
```

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
