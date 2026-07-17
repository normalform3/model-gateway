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

响应中记录 `organizationId`、`teamId` 和 `quotaAccountId`。初始化会幂等保留 Demo Team Owner，并创建 Demo Developer；不会生成密码、登录会话或可用明文 Key。

前端也会在尚未初始化时显示“初始化演示数据”。初始化后可通过侧边栏切换平台管理员、团队负责人和开发成员。该切换只影响展示和默认筛选，不能用于权限验证；开发环境不得把未受保护的管理接口暴露到公网。

## 初始化三团队展示数据

如需填充管理台、用量仪表盘、账单分析和保护趋势，可在本地或受控演示环境执行：

```bash
curl -X POST http://localhost:8080/admin/bootstrap/showcase
```

该接口会在独立的 `Showcase Organization` 中幂等创建 3 个团队和共 16 名成员，并生成近 7 天的 Mock 用量、账单与少量保护拒绝记录。重复调用会刷新同一批展示事实，不会重复累计。展示 Key 只保存随机哈希，原文会立即丢弃；接口不返回可用 Key，也不会访问真实 Provider、写入 Outbox 或投递 RocketMQ 事件。若同时执行最小 Demo 初始化，团队目录会显示两组数据。

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

## 团队授权、成员额度与系统 Key

先创建平台用户，再由管理员选择其中一个现有用户为负责人。也可以省略 `ownerUserId` 创建草稿团队；草稿团队不能接收模型权限或额度。

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
    "ownerUserId": 101
  }'
```

负责人提交团队模型和 Token 申请，管理员审批后团队公共池才可分配：

```bash
curl -X POST http://localhost:8080/admin/teams/1/entitlement-requests \
  -H 'Content-Type: application/json' \
  -d '{"ownerMemberId":10,"modelNames":["mock-gpt-4o-mini"],"requestedTokens":500000,"purpose":"local demo"}'

curl -X POST http://localhost:8080/admin/entitlement-requests/1/review \
  -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE"}'
```

负责人将未分配的平台用户加入团队，并向成员划拨模型权限和个人额度：

```bash
curl -X POST http://localhost:8080/admin/teams/1/members/from-user \
  -H 'Content-Type: application/json' \
  -d '{"ownerMemberId":10,"userId":102}'

curl -X POST http://localhost:8080/admin/teams/1/members/11/access \
  -H 'Content-Type: application/json' \
  -d '{"ownerMemberId":10,"modelNames":["mock-gpt-4o-mini"],"tokenAllocation":100000,"reason":"local development"}'
```

成员在自己的 Key 页面生成 Key，响应里的 `apiKey` 只展示一次。后续额度或模型调整不会再次返回明文。

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

## Mock 超时与断连演练

默认超时为普通响应 60 秒、SSE 首事件 30 秒、SSE 空闲 60 秒。开发或测试时可用环境变量缩短它们，例如：

```bash
MODELGATE_PROVIDER_STREAM_FIRST_EVENT_TIMEOUT=100ms \
MODELGATE_PROVIDER_STREAM_IDLE_TIMEOUT=100ms \
mvn -pl modelgate-bootstrap spring-boot:run
```

- 在普通请求或流式请求的 `mock` 中设置较大的 `delayMs`，可演练普通响应或首事件超时。
- `{"mode":"stream_stall","delayMs":1000}` 会先输出两个片段，再暂停指定时间，用于验证流中空闲超时。
- 流已开始输出后发生超时或中断时，响应以 `event: error` 结束，不会发送 `[DONE]`。可在 `curl -N` 运行期间中断客户端，确认请求记录为取消且并发、冻结额度仅结算一次。

## 查询

```bash
curl http://localhost:8080/admin/teams/1/quota
```

## 启动多开发者 Mock 测试工具

仅在开发环境打开测试观察契约：

```bash
# Runner 保持独立可执行工程；修改其代码后先构建一次。
mvn -f tools/modelgate-test-runner/pom.xml package
export MODELGATE_TEST_OBSERVABILITY_ENABLED='true'
export MODELGATE_TEST_RUNNER_AUTO_START='true'
mvn -pl modelgate-bootstrap spring-boot:run
```

网关会以独立子进程启动 Runner，并在网关正常停止时回收它。打开控制台侧边栏“开发测试工具”，或访问 `http://127.0.0.1:19090`。Runner 只列出满足当前 Mock 模型授权和个人额度条件的开发成员；可手动勾选或自动选择多名成员。它只发送 Mock 请求，短期测试 Key 会在测试结束、取消或 30 分钟到期时失效。

`MODELGATE_TEST_RUNNER_AUTO_START` 默认为 `false`，生产环境不应开启。若已手动运行 Runner，网关发现 `127.0.0.1:19090` 已被占用后会跳过启动，并且不会管理该外部进程。可用 `MODELGATE_TEST_RUNNER_JAR_PATH` 和 `MODELGATE_TEST_RUNNER_PORT` 覆盖 Jar 路径及端口；修改端口时，也要将控制台的 `VITE_TEST_TOOL_URL` 设为对应地址。
