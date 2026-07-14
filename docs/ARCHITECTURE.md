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

- 组织、团队、成员、应用管理。
- 团队授权申请与审批、成员访问分配，以及系统签发的虚拟 API Key 管理。
- Provider、真实模型目录和加密凭据池配置。
- 限流、并发、预算和额度策略配置。
- 调用日志、用量统计、账单查询和审计查询。

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
- 执行 Redis 额度预占。
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
5. 限流模块执行 RPM、TPM、并发校验
6. 额度模块估算 Token 并冻结成员个人额度
7. 解析直接模型并从 Provider 凭据池轮询选择凭据
8. Provider Adapter 转发请求；首包前可切换一把备用凭据
9. 网关向客户端返回普通响应或 SSE 流
10. 请求结束后结算额度并发送 Usage Event
11. 消费者异步写入用量、账单、汇总和审计
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
更新 MySQL
  -> 发布 CONFIG_CHANGE_EVENT
  -> 网关节点删除本地缓存
  -> 删除 Redis 缓存
```

对于禁用虚拟 Key、撤销团队/成员模型权限、调整成员额度这类安全敏感操作，应优先让 Redis 状态快速失效，再广播本地缓存失效。

## 设计参考

- [Spring Cloud Gateway RequestRateLimiter](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/requestratelimiter-factory.html)
- [Redis Rate Limiter](https://redis.io/docs/latest/develop/use-cases/rate-limiter/)
- [RocketMQ Metrics](https://rocketmq.apache.org/docs/observability/01metrics/)
