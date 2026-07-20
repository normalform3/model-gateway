# ModelGate 架构设计

## 总览

ModelGate 分为控制面、数据面和异步计量服务。

```text
                         ┌─────────────────────────┐
                         │       管理控制台         │
                         │ 模型、Key、预算、账单配置 │
                         └────────────┬────────────┘
                                      │
                         ┌────────────▼────────────┐
                         │      Control Plane       │
                         │   Spring Boot 管理服务    │
                         └──────┬─────────┬────────┘
                                │         │
                           MySQL│         │Redis
                                │         │
┌─────────────┐       ┌────────▼─────────▼────────┐
│ Agent/应用   │──────>│         AI Gateway         │
│ OpenAI SDK  │ SSE   │ 鉴权、限流、路由、流式转发  │
└─────────────┘       └───────────┬───────────────┘
                                  │
                  ┌───────────────┼────────────────┐
                  │               │                │
          OpenAI-Compatible     Mock Provider     后续 Provider
                  │
                  ▼
             模型调用结果
                  │
                  ▼
          ┌─────────────────┐
          │    RocketMQ      │
          │  Usage Events    │
          └───┬─────┬───────┘
              │     │
       ┌──────▼─┐ ┌─▼────────┐
       │计量服务 │ │账单服务   │
       └──────┬─┘ └─┬────────┘
              │     │
              └──┬──┘
                 ▼
               MySQL
```

## 控制面

控制面负责低频管理操作：

- 组织、团队、成员管理，以及按模型的团队和成员两级周期权益发放（每日、每周或不限）。
- 团队授权申请与审批、成员访问分配，以及系统签发的虚拟 API Key 管理。
- Provider、真实模型目录和加密凭据池配置。
- 限流、并发、预算和额度策略配置。
- 调用日志、用量统计、账单查询和审计查询。
- 控制台账号认证、会话刷新与三类角色的资源范围授权。

控制面同时维护 `MOCK_OPENAI` 与 `OPENAI_COMPATIBLE` Provider。真实凭据只以 AES-GCM 密文保存，控制台仅展示名称和末四位；直接模型名在全局唯一，不经过逻辑模型路由。

特点：

- 请求量较低。
- 业务规则较复杂。
- 强依赖 MySQL。
- 可以接受几十毫秒级响应。

## 数据面

数据面负责每一次模型调用：

- 接收 OpenAI 风格请求。
- 校验虚拟 API Key。
- 校验模型访问权限。
- 执行 Redis 多维限流。
- 执行 Redis 逐模型的团队与成员两层额度预占。
- 按真实模型名解析唯一 Provider 模型。
- 转发普通响应或 SSE 流式响应。
- 采集用量、延迟和错误信息。
- 发送 Usage Event。

特点：

- 请求量高。
- 延迟敏感。
- 不能频繁访问 MySQL。
- 大量依赖 Caffeine、Redis 和异步消息。

## 异步计量服务

异步计量服务消费 RocketMQ 领域事件，完成：

- 用量落库。
- 账单计算。
- 额度流水结算。
- 每日汇总。
- 预算告警。
- 审计记录。
- 后续对账补偿。

一个模型请求完成后，只发送一次领域事件，不在主链路同步完成所有统计工作。

## 模块化单体

第一版建议使用 Maven 多模块，但仍然部署为一个应用：

```text
modelgate-bootstrap
modelgate-gateway
modelgate-auth
modelgate-routing
modelgate-quota
modelgate-usage
modelgate-billing
modelgate-provider
modelgate-infrastructure
frontend
modelgate-common
```

这样可以降低部署、事务、调试和本地开发复杂度，同时保留未来拆分边界。

后续优先拆分：

- `modelgate-gateway`：高并发、延迟敏感、面向水平扩容。
- `modelgate-usage` 和 `modelgate-billing`：吞吐、幂等、重试和对账优先。

## 请求链路

```text
1. 客户端携带虚拟 Key 调用 /v1/chat/completions
2. 网关计算 requestId 和幂等上下文
3. 鉴权模块读取 ApiKeyContext
4. 权限模块校验成员权限与团队授权的交集
5. 限流模块通过一次 Redis Lua 执行全局/Key/团队 RPM、团队 TPM、Key/团队/逻辑模型/全局并发校验
6. 额度模块估算 Token，并原子冻结团队与成员（或应用）的同模型周期额度
7. 解析直接模型并从 Provider 凭据池轮询选择凭据
8. Provider Adapter 转发请求；首包前可切换一把备用凭据
9. 网关向客户端返回普通响应或 SSE 流
10. 请求结束后立即在 Redis 结算两层同模型额度并写入 UsageCompletedEvent Outbox
11. Outbox 异步投递 RocketMQ；额度账本、用量、账单、预算告警和审计消费者各自幂等处理
```

## 缓存策略

ApiKeyContext 读取顺序：

```text
Caffeine 本地缓存
        ↓ miss
Redis
        ↓ miss
MySQL
```

配置变化流程：

```text
更新 MySQL 并提交事务
  -> 发起节点删除 Redis auth:key 缓存和本地 Caffeine
  -> Redis Pub/Sub 广播鉴权缓存失效事件
  -> 其他网关节点删除各自的本地 Caffeine 缓存
```

对于禁用虚拟 Key、撤销团队/成员模型权限、调整逐模型周期额度这类安全敏感操作，应优先让 Redis 状态与本地 Key 缓存快速失效。权益原位调整时，Redis 使用同一权益 ID 原子重算可用额度并保留冻结量；收回时删除该权益的日/周额度键，随后完成的在途请求只释放并发状态，不会重建已删除权益的额度缓存。

## 设计参考

- [Spring Cloud Gateway RequestRateLimiter](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/requestratelimiter-factory.html)
- [Redis Rate Limiter](https://redis.io/docs/latest/develop/use-cases/rate-limiter/)
- [RocketMQ Metrics](https://rocketmq.apache.org/docs/observability/01metrics/)
