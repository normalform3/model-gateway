# 数据模型

## 实体层级

```text
Organization 企业
    └── Team 团队
          ├── Platform User 用户
          │     └── Team Member 团队成员关系
          └── Application 应用
                └── Virtual API Key 虚拟密钥
```

授权链固定为：平台管理员向团队发放模型权限和公共 Token，团队负责人再向成员划拨 Token 与模型权限，系统据此签发成员 Key。团队负责人为空时团队为 `DRAFT`，不得接收授权。

## 核心表分组

身份和租户：

- `organization`
- `platform_user`
- `team`
- `team_member`
- `application`
- `virtual_api_key`
- `team_entitlement_request`
- `team_model_grant`
- `member_model_access`

模型管理：

- `provider`
- `provider_model`
- `provider_credential`

限流和预算：

- `rate_limit_policy`
- `budget`
- `quota_account`
- `quota_transaction`
- `quota_transfer`

请求和计费：

- `ai_request`
- `usage_record`
- `billing_record`
- `billing_daily_summary`

可靠消息：

- `mq_consume_record`

## API Key 上下文

网关主链路使用的缓存对象：

```java
public record ApiKeyContext(
        Long keyId,
        Long organizationId,
        Long teamId,
        Long applicationId,
        Long memberId,
        Long quotaAccountId,
        Set<String> allowedModels,
        RateLimitPolicy rateLimitPolicy,
        BudgetPolicy budgetPolicy,
        boolean enabled
) {}
```

虚拟 Key 设计约束：

- Redis Key 和数据库认证字段使用虚拟 Key 的哈希值。
- 页面展示只允许使用 Key 前缀。
- 明文虚拟 Key 只在创建时返回一次。
- 真实 Provider 凭据不暴露给业务应用。
- 成员级计量依赖系统签发的独立虚拟 Key。Key 必须绑定 `owner_member_id` 和 `application_id`，不能用共享 Key 伪造按人统计。
- 负责人不生成 Key，只完成成员模型权限与额度划拨；成员首次在应用下获得有效访问权限时系统生成 Key。模型权限不再存为 Key 的事实来源。

## Provider、直接模型与团队授权

- `provider` 类型为 `MOCK_OPENAI` 或 `OPENAI_COMPATIBLE`；后者保存公开协议的 Base URL。
- `provider_credential` 一条记录代表一把 Provider API Key，仅保存 AES-GCM 密文、版本、末四位和状态；一个 Provider 可有多条启用凭据。
- `provider_model.model_name` 是全局唯一的真实模型名，包含输入/输出每百万 Token 单价和币种。
- `team_model_grant` 记录管理员批准的团队模型及有效期；`team_direct_model_access` 保留为模型目录兼容索引。
- `member_model_access` 是负责人发放给成员的模型集合。网关有效权限为团队有效授权和成员授权的交集。
- `global_runtime_policy` 保存全局 RPM 和并发阈值，数据面与团队、Key、模型限制一起原子校验。

## team_member

`platform_user` 保存全局用户资料。`team_member` 是用户与团队的唯一归属关系；一个用户最多属于一个团队，一个团队仅一位 `OWNER`。

关键字段：

- `organization_id`
- `team_id`
- `user_id`
- `name`
- `email`
- `role`：`OWNER` 或 `MEMBER`
- `enabled`
- `created_at`

`virtual_api_key.owner_member_id` 指向实际使用者。MVP 暂不做登录和 RBAC 校验，但负责人上下文仍必须与团队所有者关系匹配，便于后续接入权限系统。

控制台可直接选择负责人或开发成员用户切换上下文；该选择只控制筛选范围，不构成登录、认证或 RBAC。

## ai_request

请求事实表记录一次模型调用发生了什么。

```sql
CREATE TABLE ai_request (
    id BIGINT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    organization_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    api_key_id BIGINT NOT NULL,
    member_id BIGINT,
    requested_model VARCHAR(100) NOT NULL,
    actual_provider VARCHAR(50),
    actual_model VARCHAR(100),
    stream_enabled TINYINT NOT NULL,
    input_tokens INT DEFAULT 0,
    output_tokens INT DEFAULT 0,
    estimated_tokens INT DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    duration_ms BIGINT,
    first_token_ms BIGINT,
    created_at DATETIME NOT NULL,
    completed_at DATETIME,
    UNIQUE KEY uk_request_id(request_id),
    KEY idx_team_created(team_id, created_at),
    KEY idx_application_created(application_id, created_at)
);
```

## usage_record

用量事实表记录 Token 和 Provider 返回的 Usage 信息。

关键字段：

- `request_id`
- `organization_id`
- `team_id`
- `application_id`
- `api_key_id`
- `member_id`
- `provider`
- `model`
- `input_tokens`
- `output_tokens`
- `total_tokens`
- `usage_source`
- `status`
- `occurred_at`

`usage_source` 用于区分：

- Provider 原始 Usage。
- 网关流式片段估算。
- 后续账单回查。
- 人工补偿。

## quota_account

额度账户记录实时账户状态，但最终正确性必须结合流水校验。

关键字段：

- `account_id`
- `account_type`
- `owner_id`
- `available_tokens`
- `frozen_tokens`
- `consumed_tokens`
- `version`
- `updated_at`

账户类型：

- `TEAM`：管理员已充值、尚未由负责人划拨给成员的公共 Token 池；不直接用于模型调用。
- `MEMBER`：个人 Token 账户；网关预占、结算和释放的唯一运行时账户。

`quota_transfer` 记录一次负责人划拨的来源团队账户、目标成员账户、额度和原因。调用费用只在成员账户消费一次；团队账单由成员明细聚合，不会重复扣除公共池。

## quota_transaction

额度流水是账本核心，不能只维护余额。

```sql
CREATE TABLE quota_transaction (
    id BIGINT PRIMARY KEY,
    transaction_no VARCHAR(64) NOT NULL,
    account_id BIGINT NOT NULL,
    request_id VARCHAR(64),
    transaction_type VARCHAR(32) NOT NULL,
    amount BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    event_id VARCHAR(64),
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_transaction_no(transaction_no),
    UNIQUE KEY uk_event_type(event_id, transaction_type),
    KEY idx_account_created(account_id, created_at)
);
```

交易类型：

- `TEAM_GRANT`：管理员向团队公共池发放
- `MEMBER_ALLOCATION`：负责人向成员划拨
- `FREEZE`：冻结
- `CONSUME`：消费
- `RELEASE`：解冻
- `REFUND`：退还
- `EXPIRE`：过期
- `ADJUST`：人工调整

## billing_record

账单明细记录费用归因。

关键字段：

- `billing_id`
- `request_id`
- `organization_id`
- `team_id`
- `application_id`
- `api_key_id`
- `provider`
- `model`
- `input_tokens`
- `output_tokens`
- `unit_price`
- `amount`
- `currency`
- `billing_type`
- `created_at`

建议唯一约束：

```sql
UNIQUE KEY uk_request_billing(request_id, billing_type)
```

## mq_consume_record

消费者幂等记录。

```sql
CREATE TABLE mq_consume_record (
    event_id VARCHAR(64) NOT NULL,
    consumer_group VARCHAR(64) NOT NULL,
    consumed_at DATETIME NOT NULL,
    PRIMARY KEY (event_id, consumer_group)
);
```

账单消费者应同时依赖：

- 消费记录表。
- 业务表唯一约束。

不要只使用 Redis 去重，因为账单数据需要长期正确。

## 三套事实

ModelGate 不把单一表当成全部事实来源。

- `ai_request`：请求事实。
- `usage_record`：用量事实。
- `quota_transaction` 和 `billing_record`：额度和费用事实。

每日对账需要校验：

```text
成功请求数量
=
Usage 成功记录数量
=
消费流水数量
```

并校验：

```text
账户初始额度
+ 发放
+ 退款
- 消费
- 过期
= 当前余额
```
