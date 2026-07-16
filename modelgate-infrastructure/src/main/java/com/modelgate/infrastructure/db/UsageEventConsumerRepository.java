package com.modelgate.infrastructure.db;

import com.modelgate.common.event.QuotaSettlementSnapshot;
import com.modelgate.common.event.UsageCompletedEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

@Repository
public class UsageEventConsumerRepository {
    private final JdbcTemplate jdbc;

    public UsageEventConsumerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean markConsumed(String eventId, String consumerGroup) {
        try {
            jdbc.update("INSERT INTO mq_consume_record(event_id, consumer_group, consumed_at) VALUES (?, ?, ?)", eventId, consumerGroup, JdbcTime.toTimestamp(OffsetDateTime.now()));
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    public void insertBudgetAlert(UsageCompletedEvent event, QuotaSettlementSnapshot settlement) {
        if (settlement.released() || settlement.quotaLimit() == null || settlement.alertRemainingPercent() == null) return;
        long thresholdTokens = settlement.quotaLimit() * settlement.alertRemainingPercent() / 100;
        if (settlement.remainingTokens() > thresholdTokens) return;
        jdbc.update("""
                INSERT IGNORE INTO budget_alert(event_id, grant_id, cycle_started_at, alert_remaining_percent, remaining_tokens, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, event.eventId(), settlement.grantId(), JdbcTime.toTimestamp(settlement.cycleStartedAt()),
                settlement.alertRemainingPercent(), settlement.remainingTokens(), JdbcTime.toTimestamp(OffsetDateTime.now()));
    }

    public void insertAuditLog(UsageCompletedEvent event) {
        jdbc.update("""
                INSERT IGNORE INTO audit_log(event_id, request_id, organization_id, team_id, member_id, project_id, api_key_id,
                    requested_model, actual_model, provider, status, total_tokens, duration_ms, occurred_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.eventId(), event.requestId(), event.organizationId(), event.teamId(), event.memberId(), event.projectId(), event.apiKeyId(),
                event.requestedModel(), event.actualModel(), event.provider(), event.status(), event.totalTokens(), event.durationMs(),
                JdbcTime.toTimestamp(event.occurredAt()), JdbcTime.toTimestamp(OffsetDateTime.now()));
    }
}
