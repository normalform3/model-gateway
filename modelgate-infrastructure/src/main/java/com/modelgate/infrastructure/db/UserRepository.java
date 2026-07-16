package com.modelgate.infrastructure.db;

import com.modelgate.common.api.AdminDtos.CreateUserRequest;
import com.modelgate.common.api.AdminDtos.TeamMembershipRequest;
import com.modelgate.common.api.AdminDtos.UpdateUserRequest;
import com.modelgate.common.api.AdminDtos.UserItem;
import com.modelgate.common.api.AdminDtos.UserListResponse;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UserListResponse list(String role, boolean enabledOnly) {
        StringBuilder sql = new StringBuilder("""
                SELECT u.id, u.name, u.email, u.enabled, u.created_at,
                       m.id member_id, m.team_id, t.name team_name, m.role
                FROM platform_user u
                LEFT JOIN team_member m ON m.user_id = u.id AND m.enabled = 1
                LEFT JOIN team t ON t.id = m.team_id AND t.enabled = 1
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (role != null && !role.isBlank()) {
            if ("DEVELOPER".equalsIgnoreCase(role)) {
                sql.append(" AND m.role IN ('OWNER', 'MEMBER')");
            } else {
                sql.append(" AND m.role = ?");
                args.add(normalizeRole(role));
            }
        }
        if (enabledOnly) {
            sql.append(" AND u.enabled = 1");
        }
        sql.append(" ORDER BY u.name, u.id");
        List<UserItem> items = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new UserItem(
                rs.getLong("id"), rs.getString("name"), rs.getString("email"), rs.getInt("enabled") == 1,
                nullableLong(rs.getObject("member_id")), nullableLong(rs.getObject("team_id")), rs.getString("team_name"),
                rs.getString("role"), JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at"))), args.toArray());
        return new UserListResponse(items);
    }

    public UserItem create(CreateUserRequest request) {
        try {
            long id = GeneratedKeys.insert(jdbcTemplate, """
                    INSERT INTO platform_user(name, email, enabled, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, request.name().trim(), request.email().trim().toLowerCase(), bool(request.enabled(), true), now(), now());
            return require(id);
        } catch (DuplicateKeyException ex) {
            throw badRequest("A user already exists with this email.");
        }
    }

    @Transactional
    public UserItem update(long userId, UpdateUserRequest request) {
        try {
            int changed = jdbcTemplate.update("""
                    UPDATE platform_user SET name = COALESCE(NULLIF(?, ''), name),
                      email = COALESCE(NULLIF(?, ''), email), enabled = COALESCE(?, enabled), updated_at = ?
                    WHERE id = ?
                    """, request.name(), blankLower(request.email()), request.enabled() == null ? null : bool(request.enabled(), true), now(), userId);
            if (changed == 0) throw badRequest("User was not found.");
            UserItem user = require(userId);
            // Re-enabling a global user must not silently restore a membership that a team owner removed.
            jdbcTemplate.update("UPDATE team_member SET name = ?, email = ?, enabled = CASE WHEN ? = 0 THEN 0 ELSE enabled END WHERE user_id = ?",
                    user.name(), user.email(), bool(user.enabled(), true), userId);
            return require(userId);
        } catch (DuplicateKeyException ex) {
            throw badRequest("A user already exists with this email.");
        }
    }

    @Transactional
    public UserItem assignMembership(long userId, TeamMembershipRequest request) {
        UserItem user = require(userId);
        if (!user.enabled()) throw badRequest("Disabled users cannot be assigned to a team.");
        Long organizationId = lookupLong("SELECT organization_id FROM team WHERE id = ?", request.teamId());
        if (organizationId == null) throw badRequest("Team was not found.");
        String role = normalizeRole(request.role());
        Long currentTeamId = lookupLong("SELECT team_id FROM team_member WHERE user_id = ?", userId);
        if (currentTeamId != null && currentTeamId.longValue() != request.teamId()) {
            throw badRequest("A user can belong to only one team. Remove or reassign the current membership first.");
        }
        if ("OWNER".equals(role)) {
            jdbcTemplate.update("UPDATE team_member SET role = 'MEMBER' WHERE team_id = ? AND role = 'OWNER'", request.teamId());
        }
        if (currentTeamId == null) {
            GeneratedKeys.insert(jdbcTemplate, """
                    INSERT INTO team_member(organization_id, team_id, user_id, name, email, role, enabled, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                    """, organizationId, request.teamId(), userId, user.name(), user.email(), role, now());
        } else {
            jdbcTemplate.update("UPDATE team_member SET role = ?, name = ?, email = ?, enabled = 1 WHERE user_id = ?",
                    role, user.name(), user.email(), userId);
        }
        if ("OWNER".equals(role)) {
            jdbcTemplate.update("UPDATE team SET owner_user_id = ? WHERE id = ?", userId, request.teamId());
            jdbcTemplate.update("UPDATE team SET status = CASE WHEN status = 'DRAFT' THEN 'READY_FOR_REQUEST' ELSE status END WHERE id = ?", request.teamId());
        } else {
            Long ownerId = lookupLong("SELECT owner_user_id FROM team WHERE id = ?", request.teamId());
            if (ownerId != null && ownerId == userId) throw badRequest("Transfer team ownership before changing the owner role.");
        }
        return require(userId);
    }

    @Transactional
    public DeletedUser delete(long userId) {
        require(userId);
        Long memberId = lookupLong("SELECT id FROM team_member WHERE user_id = ?", userId);
        List<String> apiKeyHashes = memberId == null ? List.of()
                : jdbcTemplate.queryForList("SELECT key_hash FROM virtual_api_key WHERE owner_member_id = ?", String.class, memberId);
        if (memberId != null) {
            deleteMemberData(userId, memberId);
        }
        jdbcTemplate.update("DELETE FROM platform_user WHERE id = ?", userId);
        return new DeletedUser(apiKeyHashes);
    }

    @Transactional
    public DeletedTeam deleteTeam(long teamId) {
        Long found = lookupLong("SELECT id FROM team WHERE id = ?", teamId);
        if (found == null) throw badRequest("Team was not found.");
        List<String> apiKeyHashes = jdbcTemplate.queryForList("SELECT key_hash FROM virtual_api_key WHERE team_id = ?", String.class, teamId);
        List<Long> quotaAccountIds = jdbcTemplate.queryForList("""
                SELECT id FROM quota_account WHERE account_type IN ('TEAM_DEVELOPMENT','TEAM_APPLICATION') AND owner_id = ?
                UNION ALL
                SELECT qa.id FROM quota_account qa
                JOIN team_member m ON m.id = qa.owner_id
                WHERE qa.account_type = 'MEMBER_DEVELOPMENT' AND m.team_id = ?
                """, Long.class, teamId, teamId);
        deleteTeamData(teamId);
        jdbcTemplate.update("DELETE FROM team WHERE id = ?", teamId);
        return new DeletedTeam(apiKeyHashes, quotaAccountIds);
    }

    /** Stops a team while retaining its operational ledger for audit and reporting. */
    @Transactional
    public DissolvedTeam dissolveTeam(long teamId) {
        Long found = lookupLong("SELECT id FROM team WHERE id = ?", teamId);
        if (found == null) throw badRequest("Team was not found.");
        List<String> apiKeyHashes = jdbcTemplate.queryForList("SELECT key_hash FROM virtual_api_key WHERE team_id = ?", String.class, teamId);
        List<Long> quotaAccountIds = jdbcTemplate.queryForList("""
                SELECT id FROM quota_account WHERE account_type IN ('TEAM_DEVELOPMENT','TEAM_APPLICATION') AND owner_id = ?
                UNION ALL
                SELECT qa.id FROM quota_account qa
                JOIN team_member m ON m.id = qa.owner_id
                WHERE qa.account_type = 'MEMBER_DEVELOPMENT' AND m.team_id = ?
                """, Long.class, teamId, teamId);
        List<Long> entitlementGrantIds = jdbcTemplate.queryForList(
                "SELECT id FROM model_entitlement_grant WHERE team_id = ?", Long.class, teamId);
        jdbcTemplate.update("UPDATE virtual_api_key SET enabled = 0 WHERE team_id = ?", teamId);
        jdbcTemplate.update("UPDATE team_member SET enabled = 0 WHERE team_id = ?", teamId);
        jdbcTemplate.update("UPDATE team SET enabled = 0, status = 'DISSOLVED' WHERE id = ?", teamId);
        return new DissolvedTeam(apiKeyHashes, quotaAccountIds, entitlementGrantIds);
    }

    private UserItem require(long userId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT u.id, u.name, u.email, u.enabled, u.created_at,
                      m.id member_id, m.team_id, t.name team_name, m.role
                    FROM platform_user u LEFT JOIN team_member m ON m.user_id = u.id AND m.enabled = 1
                    LEFT JOIN team t ON t.id = m.team_id AND t.enabled = 1 WHERE u.id = ?
                    """, (rs, rowNum) -> new UserItem(rs.getLong("id"), rs.getString("name"), rs.getString("email"),
                    rs.getInt("enabled") == 1, nullableLong(rs.getObject("member_id")), nullableLong(rs.getObject("team_id")),
                    rs.getString("team_name"), rs.getString("role"), JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at"))), userId);
        } catch (EmptyResultDataAccessException ex) {
            throw badRequest("User was not found.");
        }
    }

    private void deleteMemberData(long userId, long memberId) {
        Long quotaAccountId = lookupLong("SELECT id FROM quota_account WHERE account_type = 'MEMBER_DEVELOPMENT' AND owner_id = ?", memberId);
        jdbcTemplate.update("UPDATE team SET owner_user_id = NULL, status = 'DRAFT' WHERE owner_user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM mq_consume_record WHERE event_id IN (SELECT event_id FROM usage_record WHERE member_id = ?)", memberId);
        jdbcTemplate.update("DELETE FROM billing_record WHERE member_id = ?", memberId);
        jdbcTemplate.update("DELETE FROM usage_record WHERE member_id = ?", memberId);
        jdbcTemplate.update("DELETE FROM ai_request WHERE member_id = ?", memberId);
        jdbcTemplate.update("DELETE FROM virtual_api_key WHERE owner_member_id = ?", memberId);
        jdbcTemplate.update("DELETE FROM member_model_access WHERE member_id = ?", memberId);
        jdbcTemplate.update("DELETE FROM team_entitlement_request WHERE owner_member_id = ?", memberId);
        jdbcTemplate.update("DELETE FROM quota_transfer WHERE member_id = ?", memberId);
        if (quotaAccountId != null) {
            jdbcTemplate.update("DELETE FROM quota_transaction WHERE account_id = ?", quotaAccountId);
            jdbcTemplate.update("DELETE FROM quota_account WHERE id = ?", quotaAccountId);
        }
        jdbcTemplate.update("DELETE FROM team_member WHERE id = ?", memberId);
    }

    private void deleteTeamData(long teamId) {
        jdbcTemplate.update("DELETE FROM mq_consume_record WHERE event_id IN (SELECT event_id FROM usage_record WHERE team_id = ?)", teamId);
        jdbcTemplate.update("DELETE FROM billing_record WHERE team_id = ?", teamId);
        jdbcTemplate.update("DELETE FROM usage_record WHERE team_id = ?", teamId);
        jdbcTemplate.update("DELETE FROM ai_request WHERE team_id = ?", teamId);
        jdbcTemplate.update("DELETE FROM virtual_api_key WHERE team_id = ?", teamId);
        jdbcTemplate.update("DELETE FROM quota_transfer WHERE team_id = ?", teamId);
        jdbcTemplate.update("DELETE FROM team_entitlement_request WHERE team_id = ?", teamId);
        jdbcTemplate.update("DELETE FROM member_model_access WHERE member_id IN (SELECT id FROM team_member WHERE team_id = ?)", teamId);
        jdbcTemplate.update("DELETE FROM quota_transaction WHERE account_id IN (SELECT id FROM quota_account WHERE account_type IN ('TEAM_DEVELOPMENT','TEAM_APPLICATION') AND owner_id = ?)", teamId);
        jdbcTemplate.update("DELETE FROM quota_transaction WHERE account_id IN (SELECT qa.id FROM quota_account qa JOIN team_member m ON m.id = qa.owner_id WHERE qa.account_type = 'MEMBER_DEVELOPMENT' AND m.team_id = ?)", teamId);
        jdbcTemplate.update("DELETE FROM quota_account WHERE account_type = 'MEMBER_DEVELOPMENT' AND owner_id IN (SELECT id FROM team_member WHERE team_id = ?)", teamId);
        jdbcTemplate.update("DELETE FROM quota_account WHERE account_type IN ('TEAM_DEVELOPMENT','TEAM_APPLICATION') AND owner_id = ?", teamId);
        jdbcTemplate.update("DELETE FROM subject_model_access WHERE subject_id IN (SELECT id FROM access_subject WHERE team_id = ?)", teamId);
        jdbcTemplate.update("DELETE FROM subject_entitlement WHERE subject_id IN (SELECT id FROM access_subject WHERE team_id = ?)", teamId);
        jdbcTemplate.update("DELETE FROM access_subject WHERE team_id = ?", teamId);
        jdbcTemplate.update("DELETE FROM team_model_grant WHERE team_id = ?", teamId);
        jdbcTemplate.update("DELETE FROM team_direct_model_access WHERE team_id = ?", teamId);
        jdbcTemplate.update("DELETE FROM team_model_access WHERE team_id = ?", teamId);
        jdbcTemplate.update("DELETE FROM team_member WHERE team_id = ?", teamId);
    }

    private Long lookupLong(String sql, Object value) { try { return jdbcTemplate.queryForObject(sql, Long.class, value); } catch (EmptyResultDataAccessException ex) { return null; } }
    private static int bool(Boolean value, boolean fallback) { return Boolean.TRUE.equals(value == null ? fallback : value) ? 1 : 0; }
    private static String normalizeRole(String role) { String normalized = role == null ? "" : role.trim().toUpperCase(); if (!"OWNER".equals(normalized) && !"MEMBER".equals(normalized)) throw badRequest("Membership role must be OWNER or MEMBER."); return normalized; }
    private static String blankLower(String value) { return value == null || value.isBlank() ? null : value.trim().toLowerCase(); }
    private static java.sql.Timestamp now() { return JdbcTime.toTimestamp(OffsetDateTime.now()); }
    private static Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static ModelGateException badRequest(String message) { return new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, message); }

    public record DeletedUser(List<String> apiKeyHashes) { }
    public record DeletedTeam(List<String> apiKeyHashes, List<Long> quotaAccountIds) { }
    public record DissolvedTeam(List<String> apiKeyHashes, List<Long> quotaAccountIds, List<Long> entitlementGrantIds) { }
}
