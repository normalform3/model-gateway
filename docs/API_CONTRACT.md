# API 契约

## 原则

- MVP 对外优先提供 OpenAI 风格接口，降低业务应用接入成本。
- 业务应用使用逻辑模型名，不直接指定真实 Provider 模型。
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
  "model": "smart-chat",
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

- `model` 是逻辑模型，例如 `smart-chat`。
- `stream=true` 时返回 SSE。
- `max_tokens` 缺失时，网关使用平台默认上限估算预占额度。
- `Idempotency-Key` 用于防止客户端重试造成重复记录或重复扣费。

普通响应遵循 OpenAI 风格：

```json
{
  "id": "chatcmpl-example",
  "object": "chat.completion",
  "created": 1783670400,
  "model": "smart-chat",
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

### 创建虚拟 Key

```http
POST /admin/api-keys
Content-Type: application/json
```

```json
{
  "organizationId": 1,
  "teamId": 10,
  "applicationId": 100,
  "name": "codereader-dev",
  "allowedModels": ["smart-chat"],
  "expiresAt": "2026-12-31T23:59:59+08:00"
}
```

响应：

```json
{
  "keyId": 1000,
  "keyPrefix": "mg-key-example-prefix",
  "apiKey": "mg-key-example-created-once",
  "enabled": true
}
```

约束：

- `apiKey` 只在创建时展示一次。
- 数据库不得保存明文虚拟 Key。
- 后续页面只展示 `keyPrefix`。

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
      "requestedModel": "smart-chat",
      "actualProvider": "mock",
      "actualModel": "mock-chat",
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

### 查询团队额度

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
| `MODEL_NOT_ALLOWED` | 403 | false | Key 无权访问逻辑模型 |
| `RATE_LIMIT_EXCEEDED` | 429 | true | RPM 或 TPM 超限 |
| `CONCURRENCY_LIMIT_EXCEEDED` | 429 | true | 并发超限 |
| `QUOTA_INSUFFICIENT` | 402 | false | 可用额度不足 |
| `MODEL_ROUTE_NOT_FOUND` | 404 | false | 没有可用路由目标 |
| `PROVIDER_TIMEOUT` | 504 | true | Provider 调用超时 |
| `PROVIDER_UNAVAILABLE` | 503 | true | Provider 临时不可用 |
| `BAD_MODEL_REQUEST` | 400 | false | 请求参数无效 |
| `INTERNAL_ERROR` | 500 | true | 未分类内部错误 |
