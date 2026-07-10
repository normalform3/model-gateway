package com.modelgate.infrastructure.db;

import com.modelgate.common.event.UsageReportedEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Repository
public class BillingRepository {
    private static final BigDecimal ZERO_PRICE = new BigDecimal("0.00000000");
    private final JdbcTemplate jdbcTemplate;

    public BillingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean markConsumed(String eventId, String consumerGroup) {
        try {
            jdbcTemplate.update("INSERT INTO mq_consume_record(event_id, consumer_group, consumed_at) VALUES (?, ?, ?)",
                    eventId, consumerGroup, JdbcTime.toTimestamp(OffsetDateTime.now()));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public void insertUsage(UsageReportedEvent event) {
        jdbcTemplate.update("""
                        INSERT IGNORE INTO usage_record(
                            event_id, request_id, organization_id, team_id, application_id, api_key_id,
                            provider, model, input_tokens, output_tokens, total_tokens, usage_source, status, occurred_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                event.eventId(),
                event.requestId(),
                event.organizationId(),
                event.teamId(),
                event.applicationId(),
                event.apiKeyId(),
                event.provider(),
                event.model(),
                event.inputTokens(),
                event.outputTokens(),
                event.totalTokens(),
                "PROVIDER",
                event.status(),
                JdbcTime.toTimestamp(event.occurredAt()));
    }

    public void insertBilling(UsageReportedEvent event) {
        jdbcTemplate.update("""
                        INSERT IGNORE INTO billing_record(
                            request_id, organization_id, team_id, application_id, api_key_id,
                            provider, model, input_tokens, output_tokens, unit_price, amount, currency, billing_type, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                event.requestId(),
                event.organizationId(),
                event.teamId(),
                event.applicationId(),
                event.apiKeyId(),
                event.provider(),
                event.model(),
                event.inputTokens(),
                event.outputTokens(),
                ZERO_PRICE,
                ZERO_PRICE,
                "TOKENS",
                "USAGE",
                JdbcTime.toTimestamp(OffsetDateTime.now()));
    }

    public void insertQuotaConsumeTransaction(UsageReportedEvent event) {
        Long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM quota_account WHERE account_type = 'TEAM' AND owner_id = ?",
                Long.class,
                event.teamId());
        Long balanceAfter = jdbcTemplate.queryForObject(
                "SELECT available_tokens FROM quota_account WHERE id = ?",
                Long.class,
                accountId);
        jdbcTemplate.update("""
                        INSERT IGNORE INTO quota_transaction(
                            transaction_no, account_id, request_id, transaction_type, amount, balance_after, event_id, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                "qt-" + event.eventId() + "-consume",
                accountId,
                event.requestId(),
                "CONSUME",
                event.totalTokens(),
                balanceAfter == null ? 0L : balanceAfter,
                event.eventId(),
                JdbcTime.toTimestamp(OffsetDateTime.now()));
    }
}
