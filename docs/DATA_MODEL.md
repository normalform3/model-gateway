# 数据模型

## 实体层级

```text
Organization 企业
    └── Team 团队
          ├── Platform User 用户
          │     └── Team Member 团队成员关系
          └── Virtual API Key 虚拟密钥
```

授权链固定为：平台管理员按模型向团队发放周期权益，团队负责人再按模型向成员发放周期权益，系统据此签发成员 Key。团队负责人为空时团队为 `DRAFT`，不得接收授权。

## 核心表分组

身份和租户：

- `organization`
- `platform_user`
- `team`
- `team_member`
- `virtual_api_key`
- `team_entitlement_request`
- `team_model_grant`
- `member_model_access`
- `model_entitlement_grant`
- `model_entitlement_usage`

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

可靠消息：

- `mq_consume_record`
- `usage_event_outbox`
- `budget_alert`
- `audit_log`

## API Key 上下文

网关主链路使用的缓存对象：

```java
public record ApiKeyContext(
        Long keyId,
        Long organizationId,
        Long teamId,
        Long memberId,
        Long quotaAccountId,
        Set<String> allowedModels,
        Map<String, ModelQuotaPolicy> teamModelQuotas,
        Map<String, ModelQuotaPolicy> memberModelQuotas,
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
- 成员级计量依赖独立虚拟 Key。Key 只绑定 `owner_member_id`，不能用共享 Key 伪造按人统计。
- 负责人同时也是开发者，可在自己的 Key 页面生成或轮换个人 Key，也可在团队概览中向自己发放成员权益。模型权限与逐模型周期策略不再存为 Key 的事实来源。

## Provider、直接模型与团队授权

- `provider` 类型为 `MOCK_OPENAI` 或 `OPENAI_COMPATIBLE`；后者保存公开协议的 Base URL。
- `provider_credential` 一条记录代表一把 Provider API Key，仅保存 AES-GCM 密文、版本、末四位和状态；一个 Provider 可有多条启用凭据。
- `provider_model.model_name` 是全局唯一的真实模型名，包含输入/输出每百万 Token 单价和币种。
- `team_model_grant` 记录管理员批准的团队模型及有效期；`team_direct_model_access` 保留为模型目录兼容索引。
- `member_model_access` 是负责人发放给成员的模型集合。网关有效权限为团队有效授权和成员授权的交集。
- `global_runtime_policy` 保存全局 RPM 和并发阈值，数据面与团队、Key、模型限制一起原子校验。

## team_member

`platform_user` 保存全局用户资料。`team_member` 是用户与团队的唯一归属关系；一个用户最多属于一个**活动**团队，一个团队仅一位活动 `OWNER`。

关键字段：

- `organization_id`
- `team_id`
- `user_id`
- `name`
- `email`
- `role`：`OWNER` 或 `MEMBER`
- `enabled`
- `created_at`

`virtual_api_key.owner_member_id` 指向实际使用者。控制台认证与虚拟 API Key 鉴权相互独立：前者保护管理操作，后者只保护 `/v1/**` 模型调用。

`platform_user.platform_admin=1` 表示 `PLATFORM_ADMIN`；启用团队中的 `OWNER` 与 `MEMBER` 分别映射 `TEAM_ADMIN` 与 `DEVELOPER`。已启用但未加入活动团队的普通用户映射为受限的 `UNASSIGNED`，可登录查看账户状态，但没有团队、额度或控制面操作权限。平台管理员不能同时拥有活动团队成员关系。

`platform_user.password_hash` 仅保存 BCrypt 哈希，`password_change_required` 用于首次登录改密。`auth_session` 保存刷新令牌 SHA-256 哈希、到期时间和撤销状态；access token 不入库。

本地 `dev` profile 可重置 `platform_user.email`、`password_hash` 和 `password_change_required` 以提供可预测的开发凭据，并撤销现有 `auth_session`。这不是数据库迁移或生产账号策略；未启用该 profile 时，邮箱和密码数据保持常规管理行为。

移出成员不是删除 `platform_user`，而是将 `team_member.enabled` 置为 `0`：当前成员模型权益、兼容访问记录和虚拟 Key 会立即失效，调用、用量和账单事实继续保留原 `member_id` 与 `team_id`。重新加入时复用同一成员记录，但不会恢复旧权益或旧 Key。

## ai_request

请求事实表记录一次模型调用发生了什么。

```sql
CREATE TABLE ai_request (
    id BIGINT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    organization_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
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
    limit_dimension VARCHAR(32),
    duration_ms BIGINT,
    first_token_ms BIGINT,
    created_at DATETIME NOT NULL,
    completed_at DATETIME,
    UNIQUE KEY uk_request_id(request_id),
    KEY idx_team_created(team_id, created_at),
);
```

`limit_dimension` 仅在通过鉴权和模型权限后被 RPM、TPM 或并发保护拒绝时记录，用于管理端观测；历史请求为空。对外 API 仍只返回稳定的 `RATE_LIMIT_EXCEEDED` 或 `CONCURRENCY_LIMIT_EXCEEDED` 错误码。

## 开发期测试 Key 与运行归因

`virtual_api_key.key_kind` 区分 `STANDARD` 与 `TEST`。`TEST` Key 绑定真实 `owner_member_id`，并通过 `test_run_id` 与一次开发期测试关联；它使用成员的原模型权限和额度，但不参与常规 Key 列表或仪表盘 Key 数量。

`ai_request.test_run_id` 是可空的运行归因字段。观察接口只据此关联既有 `usage_record`、`billing_record` 与额度流水，不创建第二套计量或账单表。测试完成后 Key 被停用，保留脱敏审计记录；过期时间是 Runner 异常退出时的兜底。

## usage_record

用量事实表记录 Token 和 Provider 返回的 Usage 信息。

关键字段：

- `request_id`
- `organization_id`
- `team_id`
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

## model_entitlement_grant 与 model_entitlement_usage

运行时额度事实以逐模型权益为准。`model_entitlement_grant` 记录团队或成员的 `DAILY`、`WEEKLY`、`UNLIMITED` 策略；同一“团队/成员 + 模型”只有一条 `ACTIVE` 权益。调整直接更新该记录并保留 `grantId`。成员被移出时，其 `ACTIVE` 权益改为 `REVOKED` 并写入撤销时间，不物理删除，以保留已关联的历史用量；运行时和控制台当前权益列表只读取 `ACTIVE` 行。`quota_limit` 始终以原始 Token 整数保存；控制台仅在显示和输入时换算为百万、亿或万亿，不能将单位文本写入数据库。

`model_entitlement_usage` 以权益和周期开始时间为主键，保存消费、冻结和版本。成员移出通过撤销权益而不是删除权益，因而对应的历史用量可追溯。周期采用 `Asia/Shanghai`：每日 00:00、每周周一 00:00 重置且不结转。有限成员权益必须与团队同模型权益周期一致；团队不限时成员可选择任意周期或不限。

网关对团队和成员两层有限权益同时预占、结算和释放；不限权益只统计用量。平台配额仪表盘只聚合启用团队的团队级 `ACTIVE` 权益，成员权益是从团队权益划拨出的子集，不能再次累计；不限权益单独计数，不与有限额度做比例。`quota_account`、`quota_transfer` 与 `quota_transaction` 仅保留旧账本审计，不再作为新网关额度校验来源。

## quota_account（旧账本）

额度账户记录历史一次性余额，迁移后不参与运行时校验。

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

`billing_record` 是当前唯一的费用事实来源。平台账单工作台只按时间、团队、项目、成员、凭证类型、供应商和实际模型聚合这张表，不创建第二套费用记录，也不能将平台、团队、项目、成员和模型视图的金额相加。团队 → 成员（开发凭证）与团队 → 项目（应用凭证）是两条并行归因链。

金额按原始 `currency` 分组展示，系统不在查询层进行汇率换算；价格使用写入明细时的输入/输出单价快照，后续改价不影响历史账单。当前查询直接聚合明细表；`billing_daily_summary` 仅是未来在查询量增长后才考虑引入的物化汇总，不是现有表。

关键字段：

- `billing_id`
- `request_id`
- `organization_id`
- `team_id`
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

## usage_event_outbox、budget_alert 与 audit_log

请求终态与 `usage_event_outbox` 在同一 MySQL 事务中保存。Outbox 保存脱敏的 `UsageCompletedEvent` JSON、投递状态、租约和重试时间；只有 RocketMQ 成功确认后标记为已发送。

`budget_alert` 以 `(grant_id, cycle_started_at, alert_remaining_percent)` 唯一，确保权益在一个周期内跨越同一阈值只告警一次。`audit_log` 只保存请求归属、模型、Token、状态和耗时，不保存 Prompt、响应内容、明文 Key 或 Provider 凭据。

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
