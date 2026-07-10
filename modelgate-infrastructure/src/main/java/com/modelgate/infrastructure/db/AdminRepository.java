package com.modelgate.infrastructure.db;

import com.modelgate.common.api.AdminDtos.BootstrapDemoResponse;
import com.modelgate.common.api.AdminDtos.CreateMemberApiKeyRequest;
import com.modelgate.common.api.AdminDtos.CreateApiKeyRequest;
import com.modelgate.common.api.AdminDtos.CreateTeamMemberRequest;
import com.modelgate.common.api.AdminDtos.CreateTeamRequest;
import com.modelgate.common.api.AdminDtos.RequestLogItem;
import com.modelgate.common.api.AdminDtos.RequestLogResponse;
import com.modelgate.common.api.AdminDtos.TeamListResponse;
import com.modelgate.common.api.AdminDtos.TeamMemberItem;
import com.modelgate.common.api.AdminDtos.TeamMemberListResponse;
import com.modelgate.common.api.AdminDtos.TeamSummary;
import com.modelgate.common.api.AdminDtos.UpdateTeamMemberRequest;
import com.modelgate.common.api.AdminDtos.UpdateTeamRequest;
import com.modelgate.common.domain.ApiKeyContext;
import com.modelgate.common.domain.BudgetPolicy;
import com.modelgate.common.domain.RateLimitPolicy;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
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

        jdbcTemplate.update("""
                        INSERT IGNORE INTO team_member(organization_id, team_id, name, email, role, enabled, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                orgId, teamId, "Demo Owner", "demo-owner@example.com", "OWNER", 1, JdbcTime.toTimestamp(now));

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

    public long insertMemberApiKey(
            long teamId,
            long ownerMemberId,
            CreateMemberApiKeyRequest request,
            String keyPrefix,
            String keyHash
    ) {
        MemberKeyScope scope = findMemberKeyScope(teamId, ownerMemberId, request.applicationId());
        Long createdByMemberId = request.createdByMemberId() == null ? findOwnerMemberId(teamId) : request.createdByMemberId();
        if (createdByMemberId != null && !memberBelongsToTeam(teamId, createdByMemberId)) {
            throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "createdByMemberId does not belong to this team.");
        }
        String allowedModels = String.join(",", safeAllowedModels(request.allowedModels()));
        return GeneratedKeys.insert(jdbcTemplate, """
                        INSERT INTO virtual_api_key(
                            organization_id, team_id, application_id, owner_member_id, created_by_member_id,
                            name, key_prefix, key_hash, allowed_models, enabled, expires_at, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.organizationId(),
                scope.teamId(),
                scope.applicationId(),
                ownerMemberId,
                createdByMemberId,
                request.name(),
                keyPrefix,
                keyHash,
                allowedModels,
                1,
                JdbcTime.toTimestamp(request.expiresAt()),
                JdbcTime.toTimestamp(OffsetDateTime.now()));
    }

    public TeamSummary createTeam(CreateTeamRequest request) {
        OffsetDateTime now = OffsetDateTime.now();
        try {
            long teamId = GeneratedKeys.insert(jdbcTemplate, """
                            INSERT INTO team(
                                organization_id, name, key_rpm, team_rpm, team_concurrency, model_concurrency, enabled, created_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    request.organizationId(),
                    request.name(),
                    defaultInt(request.keyRpm(), 60),
                    defaultInt(request.teamRpm(), 600),
                    defaultInt(request.teamConcurrency(), 20),
                    defaultInt(request.modelConcurrency(), 50),
                    1,
                    JdbcTime.toTimestamp(now));

            GeneratedKeys.insert(jdbcTemplate, """
                            INSERT INTO application(organization_id, team_id, name, created_at)
                            VALUES (?, ?, ?, ?)
                            """,
                    request.organizationId(),
                    teamId,
                    "Default Application",
                    JdbcTime.toTimestamp(now));

            GeneratedKeys.insert(jdbcTemplate, """
                            INSERT INTO team_member(organization_id, team_id, name, email, role, enabled, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    request.organizationId(),
                    teamId,
                    request.ownerName(),
                    request.ownerEmail(),
                    "OWNER",
                    1,
                    JdbcTime.toTimestamp(now));

            jdbcTemplate.update("""
                            INSERT INTO quota_account(account_type, owner_id, available_tokens, frozen_tokens, consumed_tokens, version, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    "TEAM",
                    teamId,
                    500_000L,
                    0L,
                    0L,
                    0L,
                    JdbcTime.toTimestamp(now));

            return findTeamSummary(teamId).orElseThrow(() -> new IllegalStateException("Created team was not found."));
        } catch (DuplicateKeyException ex) {
            throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Team name or owner email already exists.");
        }
    }

    public TeamListResponse listTeams() {
        List<TeamSummary> items = jdbcTemplate.query(teamSummarySql(""),
                (rs, rowNum) -> mapTeamSummary(rs));
        return new TeamListResponse(items);
    }

    public Optional<TeamSummary> findTeamSummary(long teamId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(teamSummarySql("WHERE t.id = ?"),
                    (rs, rowNum) -> mapTeamSummary(rs),
                    teamId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public TeamSummary updateTeam(long teamId, UpdateTeamRequest request) {
        jdbcTemplate.update("""
                        UPDATE team
                        SET name = COALESCE(NULLIF(?, ''), name),
                            key_rpm = COALESCE(?, key_rpm),
                            team_rpm = COALESCE(?, team_rpm),
                            team_concurrency = COALESCE(?, team_concurrency),
                            model_concurrency = COALESCE(?, model_concurrency),
                            enabled = COALESCE(?, enabled)
                        WHERE id = ?
                        """,
                request.name(),
                request.keyRpm(),
                request.teamRpm(),
                request.teamConcurrency(),
                request.modelConcurrency(),
                request.enabled() == null ? null : (request.enabled() ? 1 : 0),
                teamId);
        return findTeamSummary(teamId)
                .orElseThrow(() -> new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Team was not found."));
    }

    public TeamMemberListResponse listTeamMembers(long teamId) {
        List<TeamMemberItem> items = jdbcTemplate.query("""
                        SELECT id, organization_id, team_id, name, email, role, enabled, created_at
                        FROM team_member
                        WHERE team_id = ?
                        ORDER BY role DESC, id ASC
                        """,
                (rs, rowNum) -> mapTeamMember(rs),
                teamId);
        return new TeamMemberListResponse(items);
    }

    public TeamMemberItem createTeamMember(long teamId, CreateTeamMemberRequest request) {
        long organizationId = findTeamOrganizationId(teamId);
        try {
            long memberId = GeneratedKeys.insert(jdbcTemplate, """
                            INSERT INTO team_member(organization_id, team_id, name, email, role, enabled, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    organizationId,
                    teamId,
                    request.name(),
                    request.email(),
                    "MEMBER",
                    1,
                    JdbcTime.toTimestamp(OffsetDateTime.now()));
            return findTeamMember(teamId, memberId)
                    .orElseThrow(() -> new IllegalStateException("Created member was not found."));
        } catch (DuplicateKeyException ex) {
            throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Member email already exists in this team.");
        }
    }

    public TeamMemberItem updateTeamMember(long teamId, long memberId, UpdateTeamMemberRequest request) {
        String role = normalizeRole(request.role());
        jdbcTemplate.update("""
                        UPDATE team_member
                        SET name = COALESCE(NULLIF(?, ''), name),
                            email = COALESCE(NULLIF(?, ''), email),
                            role = COALESCE(?, role),
                            enabled = COALESCE(?, enabled)
                        WHERE id = ? AND team_id = ?
                        """,
                request.name(),
                request.email(),
                role,
                request.enabled() == null ? null : (request.enabled() ? 1 : 0),
                memberId,
                teamId);
        return findTeamMember(teamId, memberId)
                .orElseThrow(() -> new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Team member was not found."));
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
                                k.owner_member_id,
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
                            nullableLong(rs.getObject("owner_member_id")),
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
                        SELECT r.request_id, r.member_id, m.name member_name, r.requested_model,
                               r.actual_provider, r.actual_model, r.status, r.input_tokens, r.output_tokens,
                               r.duration_ms, r.first_token_ms, r.created_at
                        FROM ai_request r
                        LEFT JOIN team_member m ON m.id = r.member_id
                        WHERE r.application_id = ?
                        ORDER BY r.created_at DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> new RequestLogItem(
                        rs.getString("request_id"),
                        nullableLong(rs.getObject("member_id")),
                        rs.getString("member_name"),
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

    private String teamSummarySql(String whereClause) {
        return """
                SELECT
                    t.id team_id,
                    t.organization_id,
                    COALESCE((SELECT a.id FROM application a WHERE a.team_id = t.id ORDER BY a.id ASC LIMIT 1), 0) default_application_id,
                    t.name,
                    t.enabled,
                    t.key_rpm,
                    t.team_rpm,
                    t.team_concurrency,
                    t.model_concurrency,
                    (SELECT m.id FROM team_member m WHERE m.team_id = t.id AND m.role = 'OWNER' ORDER BY m.id ASC LIMIT 1) owner_member_id,
                    (SELECT m.name FROM team_member m WHERE m.team_id = t.id AND m.role = 'OWNER' ORDER BY m.id ASC LIMIT 1) owner_name,
                    (SELECT m.email FROM team_member m WHERE m.team_id = t.id AND m.role = 'OWNER' ORDER BY m.id ASC LIMIT 1) owner_email,
                    (SELECT COUNT(*) FROM team_member m WHERE m.team_id = t.id) member_count,
                    (SELECT COUNT(*) FROM virtual_api_key k WHERE k.team_id = t.id) key_count
                FROM team t
                """ + whereClause + " ORDER BY t.id DESC";
    }

    private TeamSummary mapTeamSummary(ResultSet rs) throws SQLException {
        return new TeamSummary(
                rs.getLong("team_id"),
                rs.getLong("organization_id"),
                rs.getLong("default_application_id"),
                rs.getString("name"),
                rs.getInt("enabled") == 1,
                rs.getInt("key_rpm"),
                rs.getInt("team_rpm"),
                rs.getInt("team_concurrency"),
                rs.getInt("model_concurrency"),
                nullableLong(rs.getObject("owner_member_id")),
                rs.getString("owner_name"),
                rs.getString("owner_email"),
                rs.getInt("member_count"),
                rs.getInt("key_count"));
    }

    private TeamMemberItem mapTeamMember(ResultSet rs) throws SQLException {
        return new TeamMemberItem(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("team_id"),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("role"),
                rs.getInt("enabled") == 1,
                JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at")));
    }

    private Optional<TeamMemberItem> findTeamMember(long teamId, long memberId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                            SELECT id, organization_id, team_id, name, email, role, enabled, created_at
                            FROM team_member
                            WHERE team_id = ? AND id = ?
                            """,
                    (rs, rowNum) -> mapTeamMember(rs),
                    teamId,
                    memberId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private MemberKeyScope findMemberKeyScope(long teamId, long ownerMemberId, long applicationId) {
        try {
            return jdbcTemplate.queryForObject("""
                            SELECT m.organization_id, m.team_id, a.id application_id
                            FROM team_member m
                            JOIN application a ON a.team_id = m.team_id
                            WHERE m.team_id = ? AND m.id = ? AND m.enabled = 1 AND a.id = ?
                            """,
                    (rs, rowNum) -> new MemberKeyScope(
                            rs.getLong("organization_id"),
                            rs.getLong("team_id"),
                            rs.getLong("application_id")),
                    teamId,
                    ownerMemberId,
                    applicationId);
        } catch (EmptyResultDataAccessException ex) {
            throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Member or application was not found for this team.");
        }
    }

    private Long findOwnerMemberId(long teamId) {
        try {
            return jdbcTemplate.queryForObject("""
                            SELECT id
                            FROM team_member
                            WHERE team_id = ? AND role = 'OWNER' AND enabled = 1
                            ORDER BY id ASC
                            LIMIT 1
                            """,
                    Long.class,
                    teamId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private boolean memberBelongsToTeam(long teamId, long memberId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM team_member WHERE team_id = ? AND id = ?",
                Integer.class,
                teamId,
                memberId);
        return count != null && count > 0;
    }

    private long findTeamOrganizationId(long teamId) {
        try {
            Long organizationId = jdbcTemplate.queryForObject(
                    "SELECT organization_id FROM team WHERE id = ?",
                    Long.class,
                    teamId);
            if (organizationId == null) {
                throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Team was not found.");
            }
            return organizationId;
        } catch (EmptyResultDataAccessException ex) {
            throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Team was not found.");
        }
    }

    private static int defaultInt(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        String normalized = role.trim().toUpperCase();
        if (!"OWNER".equals(normalized) && !"MEMBER".equals(normalized)) {
            throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Member role must be OWNER or MEMBER.");
        }
        return normalized;
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

    private record MemberKeyScope(long organizationId, long teamId, long applicationId) {
    }
}
