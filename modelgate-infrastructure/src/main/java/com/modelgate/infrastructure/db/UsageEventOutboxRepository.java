package com.modelgate.infrastructure.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class UsageEventOutboxRepository {
    private final JdbcTemplate jdbc;

    public UsageEventOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String eventId, String requestId, String topic, String tag, String payloadJson) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("""
                INSERT INTO usage_event_outbox(event_id, request_id, topic, tag, payload_json, status, attempts, next_attempt_at, created_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                """, eventId, requestId, topic, tag, payloadJson, JdbcTime.toTimestamp(now), JdbcTime.toTimestamp(now));
    }

    public List<OutboxRecord> due(int limit) {
        return jdbc.query("""
                SELECT event_id, request_id, topic, tag, payload_json, attempts
                FROM usage_event_outbox
                WHERE status = 'PENDING' AND next_attempt_at <= ? AND (lease_until IS NULL OR lease_until < ?)
                ORDER BY created_at ASC LIMIT ?
                """, (rs, rowNum) -> new OutboxRecord(rs.getString("event_id"), rs.getString("request_id"), rs.getString("topic"),
                rs.getString("tag"), rs.getString("payload_json"), rs.getInt("attempts")), JdbcTime.toTimestamp(OffsetDateTime.now()), JdbcTime.toTimestamp(OffsetDateTime.now()), limit);
    }

    public boolean lease(String eventId) {
        OffsetDateTime now = OffsetDateTime.now();
        return jdbc.update("""
                UPDATE usage_event_outbox
                SET lease_until = ?
                WHERE event_id = ? AND status = 'PENDING' AND next_attempt_at <= ? AND (lease_until IS NULL OR lease_until < ?)
                """, JdbcTime.toTimestamp(now.plusSeconds(30)), eventId, JdbcTime.toTimestamp(now), JdbcTime.toTimestamp(now)) == 1;
    }

    public void markSent(String eventId) {
        jdbc.update("UPDATE usage_event_outbox SET status = 'SENT', sent_at = ?, lease_until = NULL, last_error = NULL WHERE event_id = ?",
                JdbcTime.toTimestamp(OffsetDateTime.now()), eventId);
    }

    public void reschedule(String eventId, int attempts, String error) {
        int nextAttempts = attempts + 1;
        long delaySeconds = Math.min(300, 1L << Math.min(8, nextAttempts));
        jdbc.update("""
                UPDATE usage_event_outbox
                SET attempts = ?, lease_until = NULL, next_attempt_at = ?, last_error = ?
                WHERE event_id = ? AND status = 'PENDING'
                """, nextAttempts, JdbcTime.toTimestamp(OffsetDateTime.now().plusSeconds(delaySeconds)), truncate(error), eventId);
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }

    public record OutboxRecord(String eventId, String requestId, String topic, String tag, String payloadJson, int attempts) {
    }
}
