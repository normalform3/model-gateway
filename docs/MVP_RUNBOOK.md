# MVP 运行手册

## 环境变量

真实连接信息只放在本机 shell、CI/CD Secret 或部署环境中，不提交到仓库。

```bash
export MODELGATE_MYSQL_URL='jdbc:mysql://<host>:3306/modelgate?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true'
export MODELGATE_MYSQL_USERNAME='<username>'
export MODELGATE_MYSQL_PASSWORD='<password>'
export MODELGATE_REDIS_HOST='<host>'
export MODELGATE_REDIS_PORT='6379'
export MODELGATE_REDIS_PASSWORD='<password>'
export MODELGATE_ROCKETMQ_ENABLED='true'
export MODELGATE_ROCKETMQ_ENDPOINTS='<nameserver-host>:9876'
```

本地只验证接口形态时，可以保持 `MODELGATE_ROCKETMQ_ENABLED=false`，但此时 Usage Event 不会进入 RocketMQ，账单消费者也不会被触发。

## 启动

```bash
mvn -pl modelgate-bootstrap -am install
mvn -pl modelgate-bootstrap spring-boot:run
```

第一条命令会先构建并安装 `modelgate-bootstrap` 依赖的本地模块；第二条命令只启动含有 `ModelGateApplication` 的 bootstrap 模块。不要对带 `-am` 的完整 reactor 直接执行 `spring-boot:run`，否则 Maven 会尝试运行父聚合模块并报找不到 main class。

## 启动前端控制台

前端控制台是独立 Vite 工程。先启动后端，再启动前端：

```bash
cd frontend
npm install
npm run dev
```

默认访问 `http://localhost:5173`。Vite 开发代理会把 `/admin` 和 `/v1` 转发到 `http://localhost:8080`。

## 初始化 demo 数据

```bash
curl -X POST http://localhost:8080/admin/bootstrap/demo
```

响应中记录 `organizationId`、`teamId`、`applicationId`。初始化会幂等保留 Demo Team Owner，并创建 Demo Developer；不会生成密码、登录会话或可用明文 Key。

前端也会在尚未初始化时显示“初始化演示数据”。初始化后可通过侧边栏切换平台管理员、团队负责人和开发成员。该切换只影响展示和默认筛选，不能用于权限验证；开发环境不得把未受保护的管理接口暴露到公网。

## 模拟 ChatGPT Provider

Demo 初始化会创建 `Mock ChatGPT API` 和 `gpt-4o-mini` 模拟部署。管理员也可以在“模型供应商”页创建、编辑或删除模拟 Provider 与部署；该页不收集 API Key 或外部地址。

路由命中后始终由本地 `MockProvider` 返回 OpenAI 风格内容和 `usage`。下面的请求只覆盖本次模拟调用的用量，仍不会访问外部服务：

```json
{
  "mock": {
    "mode": "normal",
    "inputTokens": 120,
    "outputTokens": 45
  }
}
```

响应中的 `usage.total_tokens` 为 `165`，可用于验证额度、用量和账单链路。

## 创建团队和成员 Key

管理员模块可以创建团队和负责人：

```bash
curl -X POST http://localhost:8080/admin/teams \
  -H 'Content-Type: application/json' \
  -d '{
    "organizationId": 1,
    "name": "AI Platform Team",
    "keyRpm": 60,
    "teamRpm": 600,
    "teamConcurrency": 20,
    "modelConcurrency": 50,
    "ownerName": "Team Owner",
    "ownerEmail": "team-owner@example.com"
  }'
```

再添加成员，并为该成员创建独立虚拟 Key。成员 Key 是按人计量的归因依据：

```bash
curl -X POST http://localhost:8080/admin/teams/1/members \
  -H 'Content-Type: application/json' \
  -d '{"name":"Developer One","email":"developer-one@example.com"}'

curl -X POST http://localhost:8080/admin/teams/1/members/2/api-keys \
  -H 'Content-Type: application/json' \
  -d '{
    "applicationId": 1,
    "name": "developer-one-dev-key",
    "allowedModels": ["mock-gpt-4o-mini"],
    "expiresAt": null,
    "createdByMemberId": 1
  }'
```

## 创建虚拟 Key

```bash
curl -X POST http://localhost:8080/admin/api-keys \
  -H 'Content-Type: application/json' \
  -d '{
    "organizationId": 1,
    "teamId": 1,
    "applicationId": 1,
    "name": "codereader-dev",
    "allowedModels": ["mock-gpt-4o-mini"]
  }'
```

响应里的 `apiKey` 只展示一次。

## 普通调用

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H 'Authorization: Bearer <apiKey>' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{
    "model": "mock-gpt-4o-mini",
    "messages": [{"role": "user", "content": "解释 ModelGate"}],
    "stream": false,
    "max_tokens": 128
  }'
```

## SSE 调用

```bash
curl -N -X POST http://localhost:8080/v1/chat/completions \
  -H 'Authorization: Bearer <apiKey>' \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{
    "model": "mock-gpt-4o-mini",
    "messages": [{"role": "user", "content": "流式解释 ModelGate"}],
    "stream": true,
    "max_tokens": 128
  }'
```

## 查询

```bash
curl http://localhost:8080/admin/applications/1/requests
curl http://localhost:8080/admin/teams/1/quota
```
