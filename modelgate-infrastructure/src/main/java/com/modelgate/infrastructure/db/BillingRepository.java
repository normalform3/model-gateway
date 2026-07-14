package com.modelgate.infrastructure.db;

import com.modelgate.common.event.UsageReportedEvent;
import com.modelgate.common.api.AdminDtos.BillingSummary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Repository
public class BillingRepository {
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
                            member_id, provider, model, input_tokens, output_tokens, total_tokens, usage_source, status, occurred_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                event.eventId(),
                event.requestId(),
                event.organizationId(),
                event.teamId(),
                event.applicationId(),
                event.apiKeyId(),
                event.memberId(),
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
        Pricing pricing = findPricing(event.provider(), event.model());
        BigDecimal amount = pricing.inputPricePerMillion()
                .multiply(BigDecimal.valueOf(event.inputTokens())).movePointLeft(6)
                .add(pricing.outputPricePerMillion().multiply(BigDecimal.valueOf(event.outputTokens())).movePointLeft(6));
        jdbcTemplate.update("""
                        INSERT IGNORE INTO billing_record(
                            request_id, organization_id, team_id, application_id, api_key_id,
                            member_id, provider, model, input_tokens, output_tokens, unit_price, amount, currency, billing_type, created_at
                            , input_unit_price, output_unit_price
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                event.requestId(),
                event.organizationId(),
                event.teamId(),
                event.applicationId(),
                event.apiKeyId(),
                event.memberId(),
                event.provider(),
                event.model(),
                event.inputTokens(),
                event.outputTokens(),
                pricing.inputPricePerMillion().add(pricing.outputPricePerMillion()),
                amount,
                pricing.currency(),
                "USAGE",
                JdbcTime.toTimestamp(OffsetDateTime.now()),
                pricing.inputPricePerMillion(),
                pricing.outputPricePerMillion());
    }

    public void insertQuotaConsumeTransaction(UsageReportedEvent event) {
        Long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM quota_account WHERE account_type = 'MEMBER' AND owner_id = ?",
                Long.class,
                event.memberId());
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

    public BillingSummary teamSummary(long teamId) {
        return summary("team_id", teamId);
    }

    public BillingSummary memberSummary(long memberId) {
        return summary("member_id", memberId);
    }

    private BillingSummary summary(String column, long scopeId) {
        String sql = """
                SELECT COALESCE(SUM(input_tokens + output_tokens), 0) total_tokens,
                       COALESCE(SUM(amount), 0) total_amount,
                       COALESCE(MAX(currency), 'USD') currency,
                       COUNT(*) record_count
                FROM billing_record
                """ + " WHERE " + column + " = ?";
        return jdbcTemplate.queryForObject(sql, (rs, row) -> new BillingSummary(scopeId,
                rs.getLong("total_tokens"), rs.getBigDecimal("total_amount"), rs.getString("currency"), rs.getLong("record_count")), scopeId);
    }

    private Pricing findPricing(String provider, String model) {
        try {
            return jdbcTemplate.queryForObject("""
                            SELECT pm.input_price_per_million, pm.output_price_per_million, pm.currency
                            FROM provider_model pm JOIN provider p ON p.id = pm.provider_id
                            WHERE p.name = ? AND pm.model_name = ? ORDER BY pm.id ASC LIMIT 1
                            """, (rs, rowNum) -> new Pricing(rs.getBigDecimal("input_price_per_million"),
                    rs.getBigDecimal("output_price_per_million"), rs.getString("currency")), provider, model);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            return new Pricing(BigDecimal.ZERO, BigDecimal.ZERO, "USD");
        }
    }

    private record Pricing(BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion, String currency) {
    }
}
