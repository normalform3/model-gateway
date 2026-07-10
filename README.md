# ModelGate

ModelGate 是面向企业内部多个 Agent、AI 应用和研发团队的 AI 网关与用量计费平台。

它提供统一的大模型调用入口，负责多模型接入、虚拟 API Key 管理、分布式限流、额度控制、模型路由、故障降级、用量统计和费用结算。

```text
CodeReader Agent ─┐
客服 Agent       ├──> ModelGate ──> OpenAI-Compatible / 本地模型 / 其他 Provider
知识库应用       ┤
内部业务系统     ┘
```

## 核心目标

- 让业务应用只持有 ModelGate 虚拟 Key，不直接接触真实模型密钥。
- 通过统一 OpenAI 风格接口降低多模型接入成本。
- 基于 Redis 完成高频鉴权缓存、分布式限流、并发控制和 Token 额度预占。
- 通过 RocketMQ 将用量统计、账单计算、预算告警和审计从主链路解耦。
- 以 MySQL 保存请求事实、用量事实和额度账本，保证后续可追溯和可对账。
- 使用 Mock Provider 支持本地开发、压测和故障演练，避免依赖真实模型费用。

## MVP 闭环

第一版最小闭环：

```text
创建虚拟 Key
  -> 配置团队额度
  -> 使用 Key 调用逻辑模型
  -> Redis 完成限流和额度预占
  -> 网关流式转发模型响应
  -> RocketMQ 异步发送 Usage Event
  -> 消费者完成结算
  -> 控制面查看调用记录和剩余额度
```

MVP 完整目标接入：

- 一个 OpenAI-Compatible Provider
- 一个 Mock Provider

当前第一版最小闭环先实现 Mock Provider，OpenAI-Compatible Provider 放在下一小步，避免一开始被外部模型配置和费用拖慢。

Claude、通义、DeepSeek、本地 vLLM、熔断降级、事务消息、日终对账和管理控制台增强能力放入后续阶段。

## 技术栈方向

后端：

- Java 17（后续可升级 Java 21）
- Spring Boot
- Spring WebFlux
- Spring Cloud Gateway
- Spring Security
- MyBatis-Plus
- MySQL
- Redis
- RocketMQ
- Resilience4j
- Caffeine

可观测性：

- Micrometer
- Prometheus
- Grafana
- OpenTelemetry
- SkyWalking 或 Jaeger

前端：

- Vue 3
- Vite
- Element Plus
- ECharts

压测：

- JMeter 或 Gatling
- Mock Model Provider
- Docker Compose

## 建议工程结构

第一版采用 Maven 多模块的模块化单体：

```text
model-gate
├── modelgate-bootstrap
├── modelgate-gateway
├── modelgate-auth
├── modelgate-routing
├── modelgate-quota
├── modelgate-usage
├── modelgate-billing
├── modelgate-provider
│   ├── provider-core
│   ├── provider-openai-compatible
│   └── provider-mock
├── modelgate-infrastructure
│   ├── infrastructure-redis
│   ├── infrastructure-rocketmq
│   └── infrastructure-persistence
├── modelgate-console
└── modelgate-common
```

这仍然是一个可部署应用，但代码边界按未来拆分方向组织。后续优先拆分：

- AI Gateway 数据面
- Usage/Billing 计量服务

## 文档导航

- [产品需求](docs/PRD.md)
- [架构设计](docs/ARCHITECTURE.md)
- [API 契约](docs/API_CONTRACT.md)
- [数据模型](docs/DATA_MODEL.md)
- [可靠性设计](docs/RELIABILITY.md)
- [开发路线图](docs/ROADMAP.md)
- [测试与压测方案](docs/TESTING.md)
- [MVP 运行手册](docs/MVP_RUNBOOK.md)

## 设计参考

- [LiteLLM Budgets and Rate Limits](https://docs.litellm.ai/docs/proxy/users)
- [Spring Cloud Gateway RequestRateLimiter](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/requestratelimiter-factory.html)
- [Redis Rate Limiter](https://redis.io/docs/latest/develop/use-cases/rate-limiter/)
- [RocketMQ Ordered Message](https://rocketmq.apache.org/docs/featureBehavior/03fifomessage/)
- [RocketMQ Delay Message](https://rocketmq.apache.org/docs/featureBehavior/02delaymessage/)
- [RocketMQ Transaction Message](https://rocketmq.apache.org/docs/featureBehavior/04transactionmessage/)
- [RocketMQ Consumption Retry](https://rocketmq.apache.org/docs/featureBehavior/10consumerretrypolicy/)
- [RocketMQ Metrics](https://rocketmq.apache.org/docs/observability/01metrics/)
