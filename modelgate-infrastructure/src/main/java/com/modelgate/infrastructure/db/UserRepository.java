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
                LEFT JOIN team_member m ON m.user_id = u.id
                LEFT JOIN team t ON t.id = m.team_id
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (role != null && !role.isBlank()) {
            sql.append(" AND m.role = ?");
            args.add(normalizeRole(role));
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
            jdbcTemplate.update("UPDATE team_member SET name = ?, email = ?, enabled = ? WHERE user_id = ?",
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
        } else {
            Long ownerId = lookupLong("SELECT owner_user_id FROM team WHERE id = ?", request.teamId());
            if (ownerId != null && ownerId == userId) throw badRequest("Transfer team ownership before changing the owner role.");
        }
        return require(userId);
    }

    @Transactional
    public void delete(long userId) {
        require(userId);
        DependencySummary dependencies = userDependencies(userId);
        if (!dependencies.empty()) {
            throw new ModelGateException(ErrorCode.USER_HAS_DEPENDENCIES, dependencies.message("User"));
        }
        jdbcTemplate.update("DELETE FROM platform_user WHERE id = ?", userId);
    }

    public void deleteTeam(long teamId) {
        Long found = lookupLong("SELECT id FROM team WHERE id = ?", teamId);
        if (found == null) throw badRequest("Team was not found.");
        DependencySummary dependencies = teamDependencies(teamId);
        if (!dependencies.empty()) {
            throw new ModelGateException(ErrorCode.TEAM_HAS_DEPENDENCIES, dependencies.message("Team"));
        }
        jdbcTemplate.update("DELETE FROM team WHERE id = ?", teamId);
    }

    private UserItem require(long userId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT u.id, u.name, u.email, u.enabled, u.created_at,
                      m.id member_id, m.team_id, t.name team_name, m.role
                    FROM platform_user u LEFT JOIN team_member m ON m.user_id = u.id
                    LEFT JOIN team t ON t.id = m.team_id WHERE u.id = ?
                    """, (rs, rowNum) -> new UserItem(rs.getLong("id"), rs.getString("name"), rs.getString("email"),
                    rs.getInt("enabled") == 1, nullableLong(rs.getObject("member_id")), nullableLong(rs.getObject("team_id")),
                    rs.getString("team_name"), rs.getString("role"), JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at"))), userId);
        } catch (EmptyResultDataAccessException ex) {
            throw badRequest("User was not found.");
        }
    }

    private DependencySummary userDependencies(long userId) {
        Long memberId = lookupLong("SELECT id FROM team_member WHERE user_id = ?", userId);
        int memberships = count("SELECT COUNT(*) FROM team_member WHERE user_id = ?", userId);
        int keys = memberId == null ? 0 : count("SELECT COUNT(*) FROM virtual_api_key WHERE owner_member_id = ?", memberId);
        int requests = memberId == null ? 0 : count("SELECT COUNT(*) FROM ai_request WHERE member_id = ?", memberId);
        return new DependencySummary(memberships, keys, requests, 0, 0);
    }

    private DependencySummary teamDependencies(long teamId) {
        return new DependencySummary(
                count("SELECT COUNT(*) FROM team_member WHERE team_id = ?", teamId),
                count("SELECT COUNT(*) FROM virtual_api_key WHERE team_id = ?", teamId),
                count("SELECT COUNT(*) FROM ai_request WHERE team_id = ?", teamId),
                count("SELECT COUNT(*) FROM application WHERE team_id = ?", teamId),
                count("SELECT COUNT(*) FROM quota_account WHERE account_type = 'TEAM' AND owner_id = ?", teamId));
    }

    private int count(String sql, Object value) { Integer count = jdbcTemplate.queryForObject(sql, Integer.class, value); return count == null ? 0 : count; }
    private Long lookupLong(String sql, Object value) { try { return jdbcTemplate.queryForObject(sql, Long.class, value); } catch (EmptyResultDataAccessException ex) { return null; } }
    private static int bool(Boolean value, boolean fallback) { return Boolean.TRUE.equals(value == null ? fallback : value) ? 1 : 0; }
    private static String normalizeRole(String role) { String normalized = role == null ? "" : role.trim().toUpperCase(); if (!"OWNER".equals(normalized) && !"MEMBER".equals(normalized)) throw badRequest("Membership role must be OWNER or MEMBER."); return normalized; }
    private static String blankLower(String value) { return value == null || value.isBlank() ? null : value.trim().toLowerCase(); }
    private static java.sql.Timestamp now() { return JdbcTime.toTimestamp(OffsetDateTime.now()); }
    private static Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static ModelGateException badRequest(String message) { return new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, message); }

    private record DependencySummary(int members, int keys, int requests, int applications, int quotaAccounts) {
        boolean empty() { return members + keys + requests + applications + quotaAccounts == 0; }
        String message(String resource) { return resource + " cannot be deleted while it has dependencies: members=" + members + ", keys=" + keys + ", requests=" + requests + ", applications=" + applications + ", quotaAccounts=" + quotaAccounts + "."; }
    }
}
