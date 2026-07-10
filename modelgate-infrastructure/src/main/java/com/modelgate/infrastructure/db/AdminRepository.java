package com.modelgate.infrastructure.db;

import com.modelgate.common.api.AdminDtos.BootstrapDemoResponse;
import com.modelgate.common.api.AdminDtos.CreateApiKeyRequest;
import com.modelgate.common.api.AdminDtos.RequestLogItem;
import com.modelgate.common.api.AdminDtos.RequestLogResponse;
import com.modelgate.common.domain.ApiKeyContext;
import com.modelgate.common.domain.BudgetPolicy;
import com.modelgate.common.domain.RateLimitPolicy;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class AdminRepository {
    private final JdbcTemplate jdbcTemplate;

    public AdminRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BootstrapDemoResponse bootstrapDemo() {
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update("INSERT IGNORE INTO organization(name, created_at) VALUES (?, ?)",
                "Demo Organization", JdbcTime.toTimestamp(now));
        long orgId = requireId("SELECT id FROM organization WHERE name = ?", "Demo Organization");

        jdbcTemplate.update("""
                        INSERT IGNORE INTO team(organization_id, name, key_rpm, team_rpm, team_concurrency, model_concurrency, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                orgId, "Demo Team", 60, 600, 20, 50, JdbcTime.toTimestamp(now));
        long teamId = requireId("SELECT id FROM team WHERE organization_id = ? AND name = ?", orgId, "Demo Team");

        jdbcTemplate.update("INSERT IGNORE INTO application(organization_id, team_id, name, created_at) VALUES (?, ?, ?, ?)",
                orgId, teamId, "CodeReader Demo", JdbcTime.toTimestamp(now));
        long appId = requireId("SELECT id FROM application WHERE team_id = ? AND name = ?", teamId, "CodeReader Demo");

        jdbcTemplate.update("INSERT IGNORE INTO provider(name, provider_type, enabled, created_at) VALUES (?, ?, ?, ?)",
                "mock", "MOCK", 1, JdbcTime.toTimestamp(now));
        long providerId = requireId("SELECT id FROM provider WHERE name = ?", "mock");

        jdbcTemplate.update("INSERT IGNORE INTO model(logical_model, created_at) VALUES (?, ?)",
                "smart-chat", JdbcTime.toTimestamp(now));

        jdbcTemplate.update("""
                        INSERT IGNORE INTO model_deployment(provider_id, name, actual_model, enabled, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                providerId, "mock-chat-deployment", "mock-chat", 1, JdbcTime.toTimestamp(now));
        long deploymentId = requireId("SELECT id FROM model_deployment WHERE provider_id = ? AND name = ?",
                providerId, "mock-chat-deployment");

        jdbcTemplate.update("INSERT IGNORE INTO model_route(logical_model, strategy, enabled, created_at) VALUES (?, ?, ?, ?)",
                "smart-chat", "FIXED", 1, JdbcTime.toTimestamp(now));
        long routeId = requireId("SELECT id FROM model_route WHERE logical_model = ?", "smart-chat");

        jdbcTemplate.update("INSERT IGNORE INTO route_target(route_id, deployment_id, weight, enabled, created_at) VALUES (?, ?, ?, ?, ?)",
                routeId, deploymentId, 100, 1, JdbcTime.toTimestamp(now));

        jdbcTemplate.update("""
                        INSERT IGNORE INTO quota_account(account_type, owner_id, available_tokens, frozen_tokens, consumed_tokens, version, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                "TEAM", teamId, 500_000L, 0L, 0L, 0L, JdbcTime.toTimestamp(now));
        long quotaAccountId = requireId("SELECT id FROM quota_account WHERE account_type = ? AND owner_id = ?", "TEAM", teamId);

        return new BootstrapDemoResponse(orgId, teamId, appId, quotaAccountId, "smart-chat");
    }

    public long insertApiKey(CreateApiKeyRequest request, String keyPrefix, String keyHash) {
        String allowedModels = String.join(",", safeAllowedModels(request.allowedModels()));
        return GeneratedKeys.insert(jdbcTemplate, """
                        INSERT INTO virtual_api_key(
                            organization_id, team_id, application_id, name, key_prefix, key_hash, allowed_models, enabled, expires_at, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                request.organizationId(),
                request.teamId(),
                request.applicationId(),
                request.name(),
                keyPrefix,
                keyHash,
                allowedModels,
                1,
                JdbcTime.toTimestamp(request.expiresAt()),
                JdbcTime.toTimestamp(OffsetDateTime.now()));
    }

    public boolean disableApiKey(long keyId) {
        return jdbcTemplate.update("UPDATE virtual_api_key SET enabled = 0 WHERE id = ?", keyId) > 0;
    }

    public Optional<String> findKeyHash(long keyId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT key_hash FROM virtual_api_key WHERE id = ?",
                    String.class,
                    keyId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<ApiKeyContext> findApiKeyContextByHash(String keyHash) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                            SELECT
                                k.id key_id,
                                k.organization_id,
                                k.team_id,
                                k.application_id,
                                q.id quota_account_id,
                                k.allowed_models,
                                k.enabled,
                                k.expires_at,
                                t.key_rpm,
                                t.team_rpm,
                                t.team_concurrency,
                                t.model_concurrency,
                                q.available_tokens
                            FROM virtual_api_key k
                            JOIN team t ON t.id = k.team_id
                            JOIN quota_account q ON q.account_type = 'TEAM' AND q.owner_id = k.team_id
                            WHERE k.key_hash = ?
                            """,
                    (rs, rowNum) -> new ApiKeyContext(
                            rs.getLong("key_id"),
                            rs.getLong("organization_id"),
                            rs.getLong("team_id"),
                            rs.getLong("application_id"),
                            rs.getLong("quota_account_id"),
                            splitAllowedModels(rs.getString("allowed_models")),
                            new RateLimitPolicy(
                                    rs.getInt("key_rpm"),
                                    rs.getInt("team_rpm"),
                                    rs.getInt("team_concurrency"),
                                    rs.getInt("model_concurrency")),
                            new BudgetPolicy(rs.getLong("available_tokens")),
                            rs.getInt("enabled") == 1,
                            JdbcTime.toOffsetDateTime(rs.getTimestamp("expires_at"))
                    ),
                    keyHash));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public RequestLogResponse findRequestsByApplication(long applicationId, int limit) {
        List<RequestLogItem> items = jdbcTemplate.query("""
                        SELECT request_id, requested_model, actual_provider, actual_model, status,
                               input_tokens, output_tokens, duration_ms, first_token_ms, created_at
                        FROM ai_request
                        WHERE application_id = ?
                        ORDER BY created_at DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> new RequestLogItem(
                        rs.getString("request_id"),
                        rs.getString("requested_model"),
                        rs.getString("actual_provider"),
                        rs.getString("actual_model"),
                        rs.getString("status"),
                        rs.getInt("input_tokens"),
                        rs.getInt("output_tokens"),
                        rs.getLong("duration_ms"),
                        nullableLong(rs.getObject("first_token_ms")),
                        JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at"))),
                applicationId,
                limit);
        return new RequestLogResponse(items, null);
    }

    private long requireId(String sql, Object... args) {
        Long id = jdbcTemplate.queryForObject(sql, Long.class, args);
        if (id == null) {
            throw new IllegalStateException("Expected row for query: " + sql);
        }
        return id;
    }

    private static Set<String> safeAllowedModels(List<String> allowedModels) {
        if (allowedModels == null || allowedModels.isEmpty()) {
            return Set.of("smart-chat");
        }
        return new LinkedHashSet<>(allowedModels);
    }

    private static Set<String> splitAllowedModels(String allowedModels) {
        if (allowedModels == null || allowedModels.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList(allowedModels.split(",")));
    }

    private static Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        return ((Number) value).longValue();
    }
}
