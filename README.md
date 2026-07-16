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
创建有负责人的团队
  -> 负责人申请团队模型权限与额度
  -> 管理员审批并充值团队公共额度
  -> 负责人向成员划拨额度与模型权限
  -> 系统自动签发成员专属 Key
  -> 使用 Key 调用真实模型名
  -> Redis 完成限流和额度预占
  -> 网关流式转发模型响应
  -> RocketMQ 异步发送 Usage Event
  -> 消费者完成结算
  -> 控制面查看成员明细、团队聚合账单和剩余额度
```

MVP 支持 `MOCK_OPENAI` 与 `OPENAI_COMPATIBLE` Provider。客户端直接传递全局唯一的真实模型名；Provider 的多把 API Key 使用 AES-GCM 加密保存，并由网关轮询选择，在首次响应前对可重试故障切换一次备用凭据。

## 双额度体系

团队额度分为互不占用的开发额度池和应用额度池。开发额度沿用“团队 → 开发者 → 开发者 Key”，适合 IDE、Claude Code 等开发工具；应用额度采用“团队 → 项目 → 服务账号 Key”，适合 Agent、RAG 与业务服务。网关按凭证类型冻结团队与下级的同模型权益并分别统计用量和费用。

真实 Provider 的同一直接模型可配置多个加密外部账号组成固定模型额度池。客户端仍只看到模型名；网关只在该模型绑定的 Provider 凭据池内选择可用账号，不进行跨模型或跨 Provider 路由。

Mock Provider 仍可在控制台配置，用于本地验证与故障演练；真实 Provider 的 Base URL 和 API Key 均不得出现在仓库或日志中。

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
├── frontend
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
