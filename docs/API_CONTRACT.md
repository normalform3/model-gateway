# API 契约

## 原则

- MVP 对外优先提供 OpenAI 风格接口，降低业务应用接入成本。
- 业务应用使用控制台登记的真实模型名，例如 `gpt-4o`；同名模型全局唯一。
- 所有需要鉴权的接口使用 `Authorization: Bearer mg-key-example` 形式的虚拟 Key。
- 示例中的 Key、URL、ID 均为占位符，不代表真实凭据或真实租户。
- 错误响应必须可诊断，但不得暴露真实 Provider 密钥、私有端点或内部堆栈。

## Chat Completions

```http
POST /v1/chat/completions
Authorization: Bearer mg-key-example
Idempotency-Key: request-example-001
Content-Type: application/json
```

请求：

```json
{
  "model": "gpt-4o",
  "messages": [
    {
      "role": "user",
      "content": "解释这个项目的架构"
    }
  ],
  "stream": true,
  "max_tokens": 800
}
```

说明：

- `model` 是真实模型名，例如 `gpt-4o`。
- `stream=true` 时返回 SSE。
- `max_tokens` 缺失时，网关使用平台默认上限估算预占额度。
- `Idempotency-Key` 用于防止客户端重试造成重复记录或重复扣费。

当前开发期还支持仅供 Mock Provider 使用的 `mock` 字段，例如 `{"mode":"normal","inputTokens":120,"outputTokens":45}`。它控制本地模拟的 Provider 用量返回；不会改变路由，也不会访问外部 API。

普通响应遵循 OpenAI 风格：

```json
{
  "id": "chatcmpl-example",
  "object": "chat.completion",
  "created": 1783670400,
  "model": "gpt-4o",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "ModelGate 分为控制面、数据面和异步计量服务。"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 100,
    "completion_tokens": 50,
    "total_tokens": 150
  }
}
```

SSE 响应：

```text
data: {"id":"chatcmpl-example","object":"chat.completion.chunk","choices":[{"delta":{"content":"ModelGate"}}]}

data: {"id":"chatcmpl-example","object":"chat.completion.chunk","choices":[{"delta":{"content":" 分为"}}]}

data: [DONE]
```

## 虚拟 API Key 管理

MVP 控制面接口可以先面向内部管理后台，不要求完全公开。

### 管理员控制面增量

- `GET/POST/PATCH/DELETE /admin/providers` 管理 `MOCK_OPENAI` 与 `OPENAI_COMPATIBLE` Provider；真实 Provider 的 Base URL 使用占位或环境配置，禁止记录私有地址。
- `GET/POST /admin/providers/{providerId}/credentials`、`PATCH /admin/provider-credentials/{credentialId}`、`POST /admin/provider-credentials/{credentialId}/disable` 管理加密凭据池；明文仅在提交时使用，响应不返回明文。
- `GET/POST/PATCH/DELETE /admin/models` 管理全局唯一的真实模型名和计费单价。
- `GET /admin/api-keys` 仅返回系统签发的 Key 前缀、成员归属、有效模型与状态；控制台不提供手工创建 Key。
- `POST /admin/entitlement-requests/{requestId}/review` 审批负责人提交的团队模型和 Token 申请；无负责人团队不能审批或直接获授资源。
- `GET /admin/dashboard/overview` 与 `PATCH /admin/dashboard/runtime-policy` 提供平台概览及全局 RPM/并发保护配置。

列表接口统一支持 `page`（从 0 开始）和 `size`（最大 100），响应返回 `items`、`page`、`size`、`total`。额外筛选条件：

- Provider：`keyword`、`providerType`（当前固定为 `MOCK_OPENAI`）、`enabled`。
- 团队：`keyword`、`enabled`、`logicalModel`。
- 虚拟 Key：`keyword`、`teamId`、`memberId`、`enabled`、`expiry`（`ACTIVE` 或 `EXPIRED`）。

### 用户与开发期视角

`POST /admin/bootstrap/demo` 会幂等创建 Demo Team、`Demo Owner` 和 `Demo Developer`，不创建密码、会话或预置明文 API Key。

`GET/POST /admin/users`、`PATCH/DELETE /admin/users/{userId}` 管理全局用户。删除用户会在同一事务中清理其成员关系、虚拟 Key、用量、账单和额度数据；用户只可属于一个团队。`DELETE /admin/teams/{teamId}` 同样会清理团队的成员关系、Key、调用、账单、额度与授权数据，但保留平台用户。管理员设置团队负责人时只能选择启用、未归属其他团队的现有用户。

控制台可按角色选择用户：负责人仅请求其所属团队，开发成员仅查看自己的 Key。该选择只影响导航和默认筛选，**不构成登录、鉴权或 RBAC**。

## 团队和成员管理

MVP 暂不引入真实登录和 RBAC，但控制面先落企业、团队、成员和成员 Key 的数据关系。

### 创建团队

```http
POST /admin/teams
Content-Type: application/json
```

```json
{
  "organizationId": 1,
  "name": "AI Platform Team",
  "keyRpm": 60,
  "teamRpm": 600,
  "teamConcurrency": 20,
  "modelConcurrency": 50,
  "ownerUserId": 101
}
```

`ownerUserId` 可省略。省略时团队为 `DRAFT`，没有负责人，管理员也不能向其发放额度或模型权限。创建团队不会新建临时用户、应用或预置额度；团队公共额度账户初始为 0。

### 查询团队

```http
GET /admin/teams
```

响应包含团队基础信息、负责人、成员数量和 Key 数量。

### 设置负责人和管理成员

```http
PUT /admin/teams/{teamId}/owner
POST /admin/teams/{teamId}/members/from-user
```

设置负责人：

```json
{
  "ownerUserId": 101
}
```

负责人只能将启用且未分配的既有平台用户加入团队：

```json
{
  "ownerMemberId": 10,
  "userId": 102
}
```

### 团队授权申请与审批

负责人提交：

```http
POST /admin/teams/{teamId}/entitlement-requests
```

```json
{
  "ownerMemberId": 10,
  "modelNames": ["gpt-4o"],
  "requestedTokens": 500000,
  "purpose": "Code review assistant",
  "expiresAt": "2026-12-31T23:59:59+08:00"
}
```

管理员通过 `POST /admin/entitlement-requests/{requestId}/review` 使用 `APPROVE` 或 `REJECT` 审批。批准会增加团队公共 Token 池，并授予相应团队模型权限。

### 负责人分配成员访问，系统签发 Key

```http
POST /admin/teams/{teamId}/members/{memberId}/access
Content-Type: application/json
```

```json
{
  "ownerMemberId": 10,
  "modelNames": ["gpt-4o"],
  "tokenAllocation": 100000,
  "reason": "Developer workspace"
}
```

模型必须是团队当前获批模型的子集，Token 从团队公共池划拨到成员个人账户。成员权限变更不会生成 Key；开发者在自己的页面确认额度和模型后生成一把成员专属 Key，响应只在生成或轮换当次返回明文。

开发者可调用 `POST /admin/members/{memberId}/api-keys/generate` 生成个人 Key，或调用 `POST /admin/members/{memberId}/api-keys/rotate` 轮换；旧 Key 会立即失效，新明文仍只返回一次。

### 禁用虚拟 Key

```http
POST /admin/api-keys/{keyId}/disable
```

响应：

```json
{
  "keyId": 1000,
  "enabled": false
}
```

禁用属于安全敏感操作，应尽快删除 Redis 和本地缓存。

## 用量查询

### 查询应用调用记录

```http
GET /admin/applications/{applicationId}/requests?from=2026-07-01&to=2026-07-10
```

响应：

```json
{
  "items": [
    {
      "requestId": "req-example-001",
      "memberId": 11,
      "memberName": "Developer One",
      "requestedModel": "gpt-4o",
      "actualProvider": "mock",
      "actualModel": "gpt-4o-mini",
      "status": "SUCCESS",
      "inputTokens": 100,
      "outputTokens": 50,
      "durationMs": 1200,
      "firstTokenMs": 200,
      "createdAt": "2026-07-10T12:00:00+08:00"
    }
  ],
  "nextCursor": null
}
```

### 查询团队公共额度

```http
GET /admin/teams/{teamId}/quota
```

响应：

```json
{
  "teamId": 10,
  "availableTokens": 500000,
  "frozenTokens": 10000,
  "consumedTokens": 490000,
  "updatedAt": "2026-07-10T12:00:00+08:00"
}
```

团队公共额度是负责人尚可分配的 Token；实际调用从成员个人账户预占和结算。个人和团队账单均从同一条成员调用明细聚合，不能重复计算 Provider 成本。

`GET /admin/members/{memberId}/quota` 查询个人账户；`GET /admin/teams/{teamId}/billing-summary` 和 `GET /admin/members/{memberId}/billing-summary` 分别返回团队与个人聚合账单。

## 错误响应

统一格式：

```json
{
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "The request exceeded the configured rate limit.",
    "requestId": "req-example-001",
    "retryable": true
  }
}
```

MVP 错误码：

| Code | HTTP | Retryable | 说明 |
| --- | --- | --- | --- |
| `INVALID_API_KEY` | 401 | false | 虚拟 Key 不存在或格式错误 |
| `API_KEY_DISABLED` | 403 | false | 虚拟 Key 已禁用 |
| `API_KEY_EXPIRED` | 403 | false | 虚拟 Key 已过期 |
| `MODEL_NOT_ALLOWED` | 403 | false | Key 无权访问真实模型 |
| `RATE_LIMIT_EXCEEDED` | 429 | true | RPM 或 TPM 超限 |
| `CONCURRENCY_LIMIT_EXCEEDED` | 429 | true | 并发超限 |
| `QUOTA_INSUFFICIENT` | 402 | false | 可用额度不足 |
| `MODEL_ROUTE_NOT_FOUND` | 404 | false | 没有可用路由目标 |
| `PROVIDER_TIMEOUT` | 504 | true | Provider 调用超时 |
| `PROVIDER_UNAVAILABLE` | 503 | true | Provider 临时不可用 |
| `BAD_MODEL_REQUEST` | 400 | false | 请求参数无效 |
| `INTERNAL_ERROR` | 500 | true | 未分类内部错误 |
