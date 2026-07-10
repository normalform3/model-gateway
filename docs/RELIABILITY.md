# 可靠性设计

## 原则

- 数据面主链路尽量不访问 MySQL。
- Redis 用于实时判断，MySQL 用于最终账本。
- RocketMQ 用于领域事件传播，消费者必须幂等。
- 失败路径必须释放并发计数和冻结额度。
- 客户端断开不等于没有费用，必须根据已生成内容或 Provider Usage 结算。

## API Key 缓存

读取顺序：

```text
Caffeine 本地缓存
        ↓ miss
Redis
        ↓ miss
MySQL
```

Redis Key 示例：

```text
auth:key:{keyHash}
```

缓存内容只保存运行时判断需要的字段：

```json
{
  "keyId": 1001,
  "teamId": 10,
  "applicationId": 20,
  "enabled": true,
  "allowedModels": ["smart-chat"],
  "expireAt": 1783670400
}
```

## 多维限流

需要检查：

- 虚拟 Key RPM
- 应用 RPM
- 团队 RPM
- 企业 RPM
- 用户或团队 TPM
- 模型并发数
- 团队并发数

Redis Key 示例：

```text
rate:key:{keyId}:rpm
rate:team:{teamId}:rpm
rate:team:{teamId}:tpm
concurrency:key:{keyId}
concurrency:model:{deploymentId}
```

多维限流必须通过 Lua 一次完成检查和扣减，避免出现某一维扣减成功、另一维失败后无法回滚。

## 并发控制

普通计数器在服务崩溃时可能无法释放，因此并发控制优先使用 ZSET：

```text
key = concurrency:team:{teamId}
score = requestExpireAt
member = requestId
```

每次检查前先清理过期请求，再统计当前并发数。

请求完成、失败、超时、取消时都必须释放对应 requestId。

## Token 额度预占

请求开始时估算：

```text
estimatedTokens = inputTokens + maxOutputTokens
```

如果客户端未传 `max_tokens`，使用平台默认上限。

冻结流程：

```text
available >= estimatedTokens
  -> available -= estimatedTokens
  -> frozen += estimatedTokens
```

结算流程：

```text
frozen -= estimatedTokens
consumed += actualTokens
available += estimatedTokens - actualTokens
```

失败释放：

```text
frozen -= estimatedTokens
available += estimatedTokens
```

如果客户端中途断开，需要按以下优先级计算实际用量：

1. Provider 返回的 Usage。
2. 网关已收到的流式片段估算。
3. 后续 Provider 账单回查。
4. 人工补偿。

## RocketMQ Topic

MVP Topic：

```text
AI_USAGE_EVENT
BILLING_EVENT
BUDGET_EVENT
CONFIG_CHANGE_EVENT
AUDIT_EVENT
```

Usage Event Tag：

```text
REQUEST_STARTED
REQUEST_SUCCEEDED
REQUEST_FAILED
REQUEST_CANCELLED
```

一个模型请求完成后，主链路只发送一次核心用量事件，由不同消费者完成不同业务。

## UsageReportedEvent

```json
{
  "eventId": "evt_example_001",
  "requestId": "req_example_001",
  "organizationId": 1,
  "teamId": 10,
  "applicationId": 100,
  "apiKeyId": 1000,
  "provider": "mock",
  "model": "mock-chat",
  "inputTokens": 200,
  "outputTokens": 300,
  "totalTokens": 500,
  "durationMs": 2300,
  "status": "SUCCESS",
  "occurredAt": "2026-07-10T12:00:00+08:00"
}
```

## 消费幂等

消费者处理流程：

```text
开始数据库事务
  -> 插入 mq_consume_record(eventId, consumerGroup)
  -> 唯一键冲突则说明已消费，直接返回成功
  -> 执行业务处理
  -> 依赖业务唯一键防止重复账单
  -> 提交事务
```

账单消费者至少使用：

- `mq_consume_record`
- `billing_record` 业务唯一约束
- `quota_transaction` 事件和类型唯一约束

## 顺序消息

同一额度账户的额度变更需要按顺序处理：

```text
FREEZE
CONSUME
RELEASE
REFUND
EXPIRE
```

顺序消息的 ShardingKey 使用 `accountId`，保证同一账户内有序，不同账户可以并行。

## 延迟消息

延迟消息用于补偿和未来检查：

- 请求冻结额度超时释放。
- 预算告警延迟确认。
- 失败账单重试。
- Provider Usage 延迟回查。
- 对账任务触发。

示例：

```text
请求开始时发送 QUOTA_RESERVATION_TIMEOUT
  -> 10 分钟后触发
  -> 如果请求已 SETTLED，忽略
  -> 如果仍是 FROZEN，释放冻结额度并记录补偿流水
```

## 事务消息

事务消息适合保证：

```text
MySQL 保存调用记录
RocketMQ 发布 UsageReportedEvent
```

它解决本地事务与消息发布的一致性，但不等于端到端 Exactly Once。消费者仍然必须做幂等。

## 配置一致性

控制面更新配置：

```text
更新 MySQL
  -> 发布 CONFIG_CHANGE_EVENT
  -> 网关删除 Caffeine 缓存
  -> 删除 Redis 缓存
```

兜底策略：

- 本地缓存 TTL 不宜过长。
- Redis 配置包含版本号。
- 网关定期校验配置版本。
- 禁用 Key 等高风险操作优先更新 Redis 状态。

## 对账

每日对账检查：

- 成功请求数量与成功 Usage 数量是否一致。
- 成功 Usage 数量与消费流水数量是否一致。
- 账单金额是否与模型单价和 Token 数一致。
- 账户余额是否满足流水公式。

异常处理：

- 写入异常任务。
- 支持人工复核。
- 支持补偿流水。
- 补偿动作必须可审计。

## 设计参考

- [Redis Rate Limiter](https://redis.io/docs/latest/develop/use-cases/rate-limiter/)
- [RocketMQ Ordered Message](https://rocketmq.apache.org/docs/featureBehavior/03fifomessage/)
- [RocketMQ Delay Message](https://rocketmq.apache.org/docs/featureBehavior/02delaymessage/)
- [RocketMQ Transaction Message](https://rocketmq.apache.org/docs/featureBehavior/04transactionmessage/)
- [RocketMQ Consumption Retry](https://rocketmq.apache.org/docs/featureBehavior/10consumerretrypolicy/)
