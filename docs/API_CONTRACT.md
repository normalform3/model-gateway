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
- `GET /admin/dashboard/overview` 与 `PATCH /admin/dashboard/runtime-policy` 提供全网关概览及全局 RPM/并发保护配置，不按单个团队过滤。
- `GET /admin/dashboard/quota-summary` 返回所有启用团队的当前周期有限额度汇总；成员二次分配不参与平台汇总，避免与团队上限重复计算。

列表接口统一支持 `page`（从 0 开始）和 `size`（最大 100），响应返回 `items`、`page`、`size`、`total`。额外筛选条件：

- Provider：`keyword`、`providerType`（当前固定为 `MOCK_OPENAI`）、`enabled`。
- 团队：`keyword`、`enabled`、`logicalModel`。
- 虚拟 Key：`keyword`、`teamId`、`memberId`、`enabled`、`expiry`（`ACTIVE` 或 `EXPIRED`）。

### 用户与开发期视角

`POST /admin/bootstrap/demo` 会幂等创建 Demo Team、`Demo Owner` 和 `Demo Developer`，不创建密码、会话或预置明文 API Key。

`GET/POST /admin/users`、`PATCH/DELETE /admin/users/{userId}` 管理全局用户。删除用户会在同一事务中清理其成员关系、虚拟 Key、用量、账单和额度数据；用户最多属于一个**活动**团队，已停用的成员关系保留为历史并可重新加入其他团队。`POST /admin/teams/{teamId}/dissolve` 是控制台使用的团队终态操作：它将团队标为 `DISSOLVED` 并停用团队、成员和 Key，同时保留平台用户、调用、用量、账单、权益与额度流水，且不能通过常规更新接口恢复。现有 `DELETE /admin/teams/{teamId}` 仍是物理清理接口，不在控制台暴露。管理员设置团队负责人时只能选择启用、未归属其他活动团队的现有用户。

控制台可按角色选择用户：负责人仅请求其所属团队，也可生成和轮换自己的个人 Key；开发成员仅查看自己的 Key。负责人同样可切换到开发成员视角。该选择只影响导航和默认筛选，**不构成登录、鉴权或 RBAC**。

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
GET /admin/teams?keyword=demo&enabled=true&status=ACTIVE&ownerUserId=10&ownerAssigned=true&page=0&size=20
```

`keyword` 匹配团队名称、团队 ID、当前负责人的姓名或邮箱；`enabled`、`status`、`ownerUserId` 与 `ownerAssigned` 均为可选筛选条件。`ownerAssigned=false` 用于筛选尚未指定负责人的团队；`status` 仅接受 `DRAFT`、`READY_FOR_REQUEST`、`ACTIVE`、`SUSPENDED` 或 `DISSOLVED`。接口按 `page`、`size` 返回团队目录，响应包含团队基础信息、负责人、成员数量和 Key 数量。

### 设置负责人和管理成员

```http
PUT /admin/teams/{teamId}/owner
GET /admin/teams/{teamId}/member-candidates
POST /admin/teams/{teamId}/members
POST /admin/teams/{teamId}/members/from-user
PATCH /admin/teams/{teamId}/members/{memberId}
PATCH /admin/teams/{teamId}/members/{memberId}/from-owner
```

设置负责人：

```json
{
  "ownerUserId": 101
}
```

负责人只能将启用且未归属活动团队的既有平台用户加入团队：

```json
{
  "ownerMemberId": 10,
  "userId": 102
}
```

平台管理员可通过 `POST /admin/teams/{teamId}/members` 传入 `{ "userId": 102 }` 代管成员加入；负责人继续使用 `from-user` 并提交 `ownerMemberId`。`GET /member-candidates` 只返回启用且没有活动团队归属的用户，并标记是否为已移出的可重新加入成员。

成员移出使用 `enabled: false`：平台管理员调用 `PATCH /admin/teams/{teamId}/members/{memberId}`，负责人调用 `PATCH /admin/teams/{teamId}/members/{memberId}/from-owner` 并提交 `{ "ownerMemberId": 10, "enabled": false }`。不能移出当前负责人，必须先通过负责人设置接口转移负责人。移出会停用该成员所有 Key 并收回当前成员权益，但不删除请求、用量或账单事实；重新加入不会恢复旧权益或旧 Key。

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

管理员通过 `POST /admin/entitlement-requests/{requestId}/review` 使用 `APPROVE` 或 `REJECT` 审批。旧请求会兼容转换为逐模型每日权益。

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

该旧接口兼容转换为逐模型每日权益。新控制台使用下列逐模型接口；成员权限变更不会生成 Key，开发者确认权益后自行生成成员专属 Key。

### 逐模型周期权益与用量

```http
GET /admin/teams/{teamId}/model-entitlements
PUT /admin/teams/{teamId}/model-entitlements/{modelName}
DELETE /admin/teams/{teamId}/model-entitlements/{modelName}
GET /admin/teams/{teamId}/members/{memberId}/model-entitlements
PUT /admin/teams/{teamId}/members/{memberId}/model-entitlements/{modelName}
DELETE /admin/teams/{teamId}/members/{memberId}/model-entitlements/{modelName}?ownerMemberId=10
GET /admin/teams/{teamId}/usage-dashboard
GET /admin/members/{memberId}/usage-dashboard
GET /admin/dashboard/quota-summary
POST /admin/teams/{teamId}/dissolve
```

更新请求：

```json
{"quotaMode":"DAILY","quotaLimit":100000,"reason":"Developer workspace","ownerMemberId":10}
```

`quotaMode` 只能为 `DAILY`、`WEEKLY` 或 `UNLIMITED`；不限时 `quotaLimit` 必须为空。`quotaLimit` 始终是精确的原始 Token 整数：控制台可用“百万 / 亿 / 万亿”展示和输入，但接口不传单位。成员上限为 `99,900,000,000`，团队上限为 `999,000,000,000,000`；两者是内部预算防误配边界，不替代 Provider 的 RPM、TPM 或并发限制。团队日/周权益要求成员同模型使用同一周期，所有成员上限之和不能超过团队上限。对已有同模型权益的 `PUT` 会原位更新并保留 `grantId`；`DELETE` 会物理删除该权益及其周期用量。收回团队权益同时删除同模型的成员子权益，相关 Key 缓存和额度缓存立即失效；已开始的调用仍会记录请求、用量和账单事实，但不会重建已删除权益的额度数据。

`GET /admin/dashboard/quota-summary` 的有限额度项按 `modelName + quotaMode` 聚合，包含团队数、已分配、已用、冻结与剩余 Token；`UNLIMITED` 权益只返回数量，不进入有限额度比例。所有数值均是各权益当前有效周期的快照，混合每日与每周周期时应按明细阅读。

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

### 查询旧团队公共额度

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

该接口仅返回迁移前旧账本余额；新的控制台应使用逐模型权益和用量仪表盘。个人和团队账单均从同一条成员调用明细聚合，不能重复计算 Provider 成本。

`GET /admin/members/{memberId}/quota` 查询个人账户；`GET /admin/teams/{teamId}/billing-summary` 和 `GET /admin/members/{memberId}/billing-summary` 分别返回团队与个人聚合账单。

## 开发期测试观察契约

当且仅当 `MODELGATE_TEST_OBSERVABILITY_ENABLED=true` 时，网关公开 `/test-observability/v1`。该契约仅给独立本地 Runner 使用，不属于业务应用接入接口。

- `GET /mock-models` 返回启用的 `MOCK_OPENAI` 模型。
- `GET /callers?model=...` 返回对该模型具备权限且个人额度为正的开发成员。
- `POST /runs` 使用 `selectionMode: EXPLICIT` 加 `memberIds`，或 `AUTO` 加 `autoCount` 创建运行；网关只为这些成员签发 30 分钟过期的 `TEST` Key，明文仅返回给 Runner 一次。
- `GET /runs/{runId}` 汇总该 Run 的请求、Usage 与账单结算；`POST /runs/{runId}/complete` 立即停用关联测试 Key。

Runner 的每次模型调用均携带 `X-ModelGate-Test-Run-Id` 和唯一 `Idempotency-Key`。带该 Header 的调用必须使用该 Run 签发的 `TEST` Key，且路由必须为 `MOCK_OPENAI`；否则请求在访问上游前以 `BAD_MODEL_REQUEST` 拒绝。

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
