package com.modelgate.infrastructure.db;

import com.modelgate.common.api.AdminDtos.CreateTeamEntitlementRequest;
import com.modelgate.common.api.AdminDtos.GrantMemberAccessRequest;
import com.modelgate.common.api.AdminDtos.TeamEntitlementItem;
import com.modelgate.common.api.AdminDtos.TeamEntitlementListResponse;
import com.modelgate.common.api.AdminDtos.TeamMemberItem;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Control-plane entitlement hierarchy: platform -> team treasury -> member account. */
@Repository
public class TeamEntitlementRepository {
    private final JdbcTemplate jdbcTemplate;

    public TeamEntitlementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public TeamMemberItem addMember(long teamId, long ownerMemberId, long userId) {
        requireOwner(teamId, ownerMemberId);
        TeamInfo team = requireTeam(teamId);
        if (!"ACTIVE".equals(team.status()) && !"READY_FOR_REQUEST".equals(team.status())) {
            throw badRequest("Draft or suspended teams cannot add members.");
        }
        UserInfo user = requireUser(userId);
        Long existingTeam = nullableLong("SELECT team_id FROM team_member WHERE user_id = ?", userId);
        if (existingTeam != null && existingTeam != teamId) {
            throw badRequest("A user can belong to only one team. Transfer the user before adding them.");
        }
        long memberId;
        if (existingTeam == null) {
            memberId = GeneratedKeys.insert(jdbcTemplate, """
                    INSERT INTO team_member(organization_id, team_id, user_id, name, email, role, enabled, created_at)
                    VALUES (?, ?, ?, ?, ?, 'MEMBER', 1, ?)
                    """, team.organizationId(), teamId, userId, user.name(), user.email(), now());
        } else {
            memberId = requireLong("SELECT id FROM team_member WHERE user_id = ?", userId);
        }
        ensureMemberAccount(memberId);
        return member(teamId, memberId);
    }

    @Transactional
    public TeamEntitlementItem request(long teamId, CreateTeamEntitlementRequest request) {
        requireOwner(teamId, request.ownerMemberId());
        TeamInfo team = requireTeam(teamId);
        if ("DRAFT".equals(team.status()) || "SUSPENDED".equals(team.status())) {
            throw badRequest("Only an owned, enabled team can request entitlements.");
        }
        List<String> models = distinct(request.modelNames());
        if (models.isEmpty() || request.requestedTokens() <= 0) throw badRequest("Request at least one model and a positive Token amount.");
        long id = GeneratedKeys.insert(jdbcTemplate, """
                INSERT INTO team_entitlement_request(team_id, owner_member_id, requested_models, requested_tokens, purpose, expires_at, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, teamId, request.ownerMemberId(), String.join(",", models), request.requestedTokens(), request.purpose().trim(),
                JdbcTime.toTimestamp(request.expiresAt()), now());
        return entitlement(id);
    }

    public TeamEntitlementListResponse requests(long teamId) {
        requireTeam(teamId);
        return new TeamEntitlementListResponse(jdbcTemplate.query("""
                SELECT id, team_id, owner_member_id, requested_models, requested_tokens, purpose, expires_at, status, reviewer_note, created_at, reviewed_at
                FROM team_entitlement_request WHERE team_id = ? ORDER BY created_at DESC
                """, (rs, row) -> new TeamEntitlementItem(rs.getLong("id"), rs.getLong("team_id"), rs.getLong("owner_member_id"),
                split(rs.getString("requested_models")), rs.getLong("requested_tokens"), rs.getString("purpose"),
                JdbcTime.toOffsetDateTime(rs.getTimestamp("expires_at")), rs.getString("status"), rs.getString("reviewer_note"),
                JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at")), JdbcTime.toOffsetDateTime(rs.getTimestamp("reviewed_at"))), teamId));
    }

    @Transactional
    public TeamEntitlementItem review(long requestId, String decision, String note) {
        TeamEntitlementItem item = entitlement(requestId);
        if (!"PENDING".equals(item.status())) throw badRequest("Only pending entitlement requests can be reviewed.");
        TeamInfo team = requireTeam(item.teamId());
        requireOwner(item.teamId(), item.ownerMemberId());
        String normalized = decision == null ? "" : decision.trim().toUpperCase();
        if (!"APPROVE".equals(normalized) && !"REJECT".equals(normalized)) throw badRequest("Decision must be APPROVE or REJECT.");
        if ("REJECT".equals(normalized)) {
            jdbcTemplate.update("UPDATE team_entitlement_request SET status = 'REJECTED', reviewer_note = ?, reviewed_at = ? WHERE id = ?", note, now(), requestId);
            return entitlement(requestId);
        }
        for (String model : item.modelNames()) requireEnabledModel(model);
        for (String model : item.modelNames()) {
            jdbcTemplate.update("INSERT IGNORE INTO team_direct_model_access(team_id, model_name, created_at) VALUES (?, ?, ?)", item.teamId(), model, now());
            jdbcTemplate.update("""
                    INSERT INTO team_model_grant(team_id, model_name, expires_at, created_at) VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE expires_at = VALUES(expires_at)
                    """, item.teamId(), model, JdbcTime.toTimestamp(item.expiresAt()), now());
        }
        ensureTeamAccount(item.teamId());
        jdbcTemplate.update("UPDATE quota_account SET available_tokens = available_tokens + ?, version = version + 1, updated_at = ? WHERE account_type = 'TEAM' AND owner_id = ?",
                item.requestedTokens(), now(), item.teamId());
        long accountId = requireLong("SELECT id FROM quota_account WHERE account_type = 'TEAM' AND owner_id = ?", item.teamId());
        long balance = requireLong("SELECT available_tokens FROM quota_account WHERE id = ?", accountId);
        jdbcTemplate.update("""
                INSERT INTO quota_transaction(transaction_no, account_id, transaction_type, amount, balance_after, created_at)
                VALUES (?, ?, 'TEAM_GRANT', ?, ?, ?)
                """, "qt-team-grant-" + requestId, accountId, item.requestedTokens(), balance, now());
        jdbcTemplate.update("UPDATE team SET status = 'ACTIVE' WHERE id = ?", team.teamId());
        jdbcTemplate.update("UPDATE team_entitlement_request SET status = 'APPROVED', reviewer_note = ?, reviewed_at = ? WHERE id = ?", note, now(), requestId);
        return entitlement(requestId);
    }

    @Transactional
    public MemberAccessSnapshot grantMemberAccess(long teamId, long memberId, GrantMemberAccessRequest request) {
        requireOwner(teamId, request.ownerMemberId());
        TeamInfo team = requireTeam(teamId);
        if (!"ACTIVE".equals(team.status())) throw badRequest("Only active teams can distribute member access.");
        member(teamId, memberId);
        requireApplication(teamId, request.applicationId());
        List<String> models = distinct(request.modelNames());
        if (models.isEmpty() || request.tokenAllocation() <= 0) throw badRequest("Member access needs models and a positive Token allocation.");
        for (String model : models) {
            Integer granted = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM team_model_grant WHERE team_id = ? AND model_name = ? AND (expires_at IS NULL OR expires_at > NOW())", Integer.class, teamId, model);
            if (granted == null || granted == 0) throw badRequest("A member can only receive models granted to the team: " + model);
        }
        ensureTeamAccount(teamId);
        ensureMemberAccount(memberId);
        long teamAccountId = requireLong("SELECT id FROM quota_account WHERE account_type = 'TEAM' AND owner_id = ?", teamId);
        long memberAccountId = requireLong("SELECT id FROM quota_account WHERE account_type = 'MEMBER' AND owner_id = ?", memberId);
        Long available = nullableLong("SELECT available_tokens FROM quota_account WHERE id = ? FOR UPDATE", teamAccountId);
        if (available == null || available < request.tokenAllocation()) throw new ModelGateException(ErrorCode.QUOTA_INSUFFICIENT, "The team does not have enough unallocated Token quota.");
        jdbcTemplate.update("UPDATE quota_account SET available_tokens = available_tokens - ?, version = version + 1, updated_at = ? WHERE id = ?", request.tokenAllocation(), now(), teamAccountId);
        jdbcTemplate.update("UPDATE quota_account SET available_tokens = available_tokens + ?, version = version + 1, updated_at = ? WHERE id = ?", request.tokenAllocation(), now(), memberAccountId);
        jdbcTemplate.update("DELETE FROM member_model_access WHERE member_id = ?", memberId);
        for (String model : models) jdbcTemplate.update("INSERT INTO member_model_access(member_id, model_name, created_at) VALUES (?, ?, ?)", memberId, model, now());
        long memberBalance = requireLong("SELECT available_tokens FROM quota_account WHERE id = ?", memberAccountId);
        String transferNo = "qt-transfer-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO quota_transfer(transfer_no, team_id, member_id, from_account_id, to_account_id, amount, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, transferNo, teamId, memberId, teamAccountId, memberAccountId, request.tokenAllocation(),
                request.reason() == null || request.reason().isBlank() ? "Member access allocation" : request.reason().trim(), now());
        jdbcTemplate.update("""
                INSERT INTO quota_transaction(transaction_no, account_id, transaction_type, amount, balance_after, created_at)
                VALUES (?, ?, 'MEMBER_ALLOCATION', ?, ?, ?)
                """, transferNo + "-member", memberAccountId, request.tokenAllocation(), memberBalance, now());
        return new MemberAccessSnapshot(memberId, memberAccountId, memberBalance, models, request.applicationId());
    }

    public List<String> memberModels(long memberId) {
        return jdbcTemplate.queryForList("SELECT model_name FROM member_model_access WHERE member_id = ? ORDER BY model_name", String.class, memberId);
    }

    public void assertOwner(long teamId, long ownerMemberId) {
        requireOwner(teamId, ownerMemberId);
    }

    @Transactional
    public void revokeMemberModel(long teamId, long memberId, long ownerMemberId, String modelName) {
        requireOwner(teamId, ownerMemberId);
        member(teamId, memberId);
        jdbcTemplate.update("DELETE FROM member_model_access WHERE member_id = ? AND model_name = ?", memberId, modelName);
    }

    @Transactional
    public void revokeTeamModel(long teamId, String modelName) {
        requireTeamOwnerExists(teamId);
        jdbcTemplate.update("DELETE FROM team_model_grant WHERE team_id = ? AND model_name = ?", teamId, modelName);
        jdbcTemplate.update("DELETE FROM team_direct_model_access WHERE team_id = ? AND model_name = ?", teamId, modelName);
        Integer grants = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM team_model_grant WHERE team_id = ? AND (expires_at IS NULL OR expires_at > NOW())", Integer.class, teamId);
        if (grants == null || grants == 0) jdbcTemplate.update("UPDATE team SET status = 'READY_FOR_REQUEST' WHERE id = ? AND status = 'ACTIVE'", teamId);
    }

    private TeamEntitlementItem entitlement(long id) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT id, team_id, owner_member_id, requested_models, requested_tokens, purpose, expires_at, status, reviewer_note, created_at, reviewed_at
                    FROM team_entitlement_request WHERE id = ?
                    """, (rs, row) -> new TeamEntitlementItem(rs.getLong("id"), rs.getLong("team_id"), rs.getLong("owner_member_id"),
                    split(rs.getString("requested_models")), rs.getLong("requested_tokens"), rs.getString("purpose"),
                    JdbcTime.toOffsetDateTime(rs.getTimestamp("expires_at")), rs.getString("status"), rs.getString("reviewer_note"),
                    JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at")), JdbcTime.toOffsetDateTime(rs.getTimestamp("reviewed_at"))), id);
        } catch (EmptyResultDataAccessException ex) { throw badRequest("Entitlement request was not found."); }
    }

    private void requireOwner(long teamId, long ownerMemberId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM team t JOIN team_member m ON m.team_id = t.id
                WHERE t.id = ? AND m.id = ? AND m.user_id = t.owner_user_id AND m.role = 'OWNER' AND m.enabled = 1
                """, Integer.class, teamId, ownerMemberId);
        if (count == null || count == 0) throw badRequest("This operation requires the active team owner.");
    }
    private void requireTeamOwnerExists(long teamId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM team t JOIN team_member m ON m.team_id = t.id AND m.user_id = t.owner_user_id AND m.role = 'OWNER' AND m.enabled = 1 WHERE t.id = ? AND t.enabled = 1", Integer.class, teamId);
        if (count == null || count == 0) throw badRequest("A team must have an active owner before it can receive or lose entitlements.");
    }

    private TeamInfo requireTeam(long teamId) {
        try { return jdbcTemplate.queryForObject("SELECT id, organization_id, status FROM team WHERE id = ? AND enabled = 1", (rs, row) -> new TeamInfo(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("status")), teamId); }
        catch (EmptyResultDataAccessException ex) { throw badRequest("Team was not found or is disabled."); }
    }
    private UserInfo requireUser(long userId) {
        try { return jdbcTemplate.queryForObject("SELECT id, name, email FROM platform_user WHERE id = ? AND enabled = 1", (rs, row) -> new UserInfo(rs.getLong("id"), rs.getString("name"), rs.getString("email")), userId); }
        catch (EmptyResultDataAccessException ex) { throw badRequest("User was not found or is disabled."); }
    }
    private TeamMemberItem member(long teamId, long memberId) {
        try { return jdbcTemplate.queryForObject("SELECT id, organization_id, team_id, name, email, role, enabled, created_at FROM team_member WHERE id = ? AND team_id = ? AND enabled = 1", (rs, row) -> new TeamMemberItem(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("team_id"), rs.getString("name"), rs.getString("email"), rs.getString("role"), true, JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at"))), memberId, teamId); }
        catch (EmptyResultDataAccessException ex) { throw badRequest("Member was not found or is disabled."); }
    }
    private void requireApplication(long teamId, long applicationId) { Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM application WHERE id = ? AND team_id = ?", Integer.class, applicationId, teamId); if (count == null || count == 0) throw badRequest("Application does not belong to the team."); }
    private void requireEnabledModel(String name) { Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM provider_model WHERE model_name = ? AND enabled = 1", Integer.class, name); if (count == null || count == 0) throw badRequest("Model was not found or is disabled: " + name); }
    private void ensureTeamAccount(long teamId) { jdbcTemplate.update("INSERT IGNORE INTO quota_account(account_type, owner_id, available_tokens, frozen_tokens, consumed_tokens, version, updated_at) VALUES ('TEAM', ?, 0, 0, 0, 0, ?)", teamId, now()); }
    private void ensureMemberAccount(long memberId) { jdbcTemplate.update("INSERT IGNORE INTO quota_account(account_type, owner_id, available_tokens, frozen_tokens, consumed_tokens, version, updated_at) VALUES ('MEMBER', ?, 0, 0, 0, 0, ?)", memberId, now()); }
    private long requireLong(String sql, Object... args) { Long value = jdbcTemplate.queryForObject(sql, Long.class, args); if (value == null) throw badRequest("Required data was not found."); return value; }
    private Long nullableLong(String sql, Object... args) { try { return jdbcTemplate.queryForObject(sql, Long.class, args); } catch (EmptyResultDataAccessException ex) { return null; } }
    private static List<String> distinct(List<String> values) { return values == null ? List.of() : List.copyOf(new LinkedHashSet<>(values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList())); }
    private static List<String> split(String values) { return values == null || values.isBlank() ? List.of() : List.of(values.split(",")); }
    private static java.sql.Timestamp now() { return JdbcTime.toTimestamp(OffsetDateTime.now()); }
    private static ModelGateException badRequest(String message) { return new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, message); }
    public record MemberAccessSnapshot(long memberId, long quotaAccountId, long availableTokens, List<String> models, long applicationId) { }
    private record TeamInfo(long teamId, long organizationId, String status) { }
    private record UserInfo(long userId, String name, String email) { }
}
