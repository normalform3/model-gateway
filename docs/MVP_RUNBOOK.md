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
mvn -pl modelgate-bootstrap -am spring-boot:run
```

## 启动前端控制台

前端控制台是独立 Vite 工程。先启动后端，再启动前端：

```bash
cd modelgate-console
npm install
npm run dev
```

默认访问 `http://localhost:5173`。Vite 开发代理会把 `/admin` 和 `/v1` 转发到 `http://localhost:8080`。

## 初始化 demo 数据

```bash
curl -X POST http://localhost:8080/admin/bootstrap/demo
```

响应中记录 `organizationId`、`teamId`、`applicationId`。

## 创建虚拟 Key

```bash
curl -X POST http://localhost:8080/admin/api-keys \
  -H 'Content-Type: application/json' \
  -d '{
    "organizationId": 1,
    "teamId": 1,
    "applicationId": 1,
    "name": "codereader-dev",
    "allowedModels": ["smart-chat"]
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
    "model": "smart-chat",
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
    "model": "smart-chat",
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
