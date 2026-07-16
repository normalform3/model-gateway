package com.modelgate.infrastructure.db;

import com.modelgate.common.api.AdminDtos.*;
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

@Repository
public class TeamEntitlementRepository {
    private final JdbcTemplate jdbc;
    public TeamEntitlementRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional public TeamMemberItem addMember(long teamId, long ownerMemberId, long userId) {
        requireOwner(teamId, ownerMemberId);
        return addMember(teamId, userId);
    }

    /** Development-console platform-admin entry point. Authentication will own this distinction later. */
    @Transactional public TeamMemberItem addMemberAsPlatform(long teamId, long userId) {
        return addMember(teamId, userId);
    }

    @Transactional public MemberDeactivation deactivateMember(long teamId, long memberId, Long ownerMemberId) {
        if (ownerMemberId != null) requireOwner(teamId, ownerMemberId);
        TeamMemberItem member = member(teamId, memberId);
        if ("OWNER".equals(member.role())) throw bad("Transfer team ownership before removing the current owner.");

        List<Long> grantIds = jdbc.query("SELECT id FROM model_entitlement_grant WHERE team_id = ? AND member_id = ? AND status = 'ACTIVE'", (rs, row) -> rs.getLong(1), teamId, memberId);
        List<String> keyHashes = jdbc.queryForList("SELECT key_hash FROM virtual_api_key WHERE owner_member_id = ?", String.class, memberId);
        Long quotaAccountId = nullable("SELECT id FROM quota_account WHERE account_type = 'MEMBER_DEVELOPMENT' AND owner_id = ?", memberId);

        // A revoked entitlement is no longer visible to runtime policy queries, while
        // its model-period usage remains attached to the original grant for audit.
        // Request, usage and billing facts retain member_id/team_id and remain untouched.
        jdbc.update("UPDATE model_entitlement_grant SET status = 'REVOKED', revoked_at = ? WHERE team_id = ? AND member_id = ? AND status = 'ACTIVE'", now(), teamId, memberId);
        jdbc.update("DELETE FROM member_model_access WHERE member_id = ?", memberId);
        jdbc.update("UPDATE virtual_api_key SET enabled = 0 WHERE owner_member_id = ?", memberId);
        jdbc.update("UPDATE team_member SET enabled = 0 WHERE id = ? AND team_id = ?", memberId, teamId);
        return new MemberDeactivation(memberId, grantIds, keyHashes, quotaAccountId);
    }

    private TeamMemberItem addMember(long teamId, long userId) {
        TeamInfo team = team(teamId); UserInfo user = user(userId);
        ExistingMember existing = jdbc.query("SELECT id, team_id, enabled FROM team_member WHERE user_id = ?", (rs, row) -> new ExistingMember(rs.getLong("id"), rs.getLong("team_id"), rs.getInt("enabled") == 1), userId).stream().findFirst().orElse(null);
        long id;
        if (existing == null) {
            id = GeneratedKeys.insert(jdbc, "INSERT INTO team_member(organization_id, team_id, user_id, name, email, role, enabled, created_at) VALUES (?, ?, ?, ?, ?, 'MEMBER', 1, ?)", team.organizationId(), teamId, userId, user.name(), user.email(), now());
        } else if (existing.enabled()) {
            if (existing.teamId() != teamId) throw bad("A user can belong to only one active team.");
            throw bad("This user is already an active member of the team.");
        } else {
            id = existing.memberId();
            jdbc.update("UPDATE team_member SET organization_id = ?, team_id = ?, name = ?, email = ?, role = 'MEMBER', enabled = 1 WHERE id = ?", team.organizationId(), teamId, user.name(), user.email(), id);
        }
        account("MEMBER_DEVELOPMENT", id);
        return member(teamId, id);
    }

    @Transactional public TeamEntitlementItem request(long teamId, CreateTeamEntitlementRequest request) {
        requireOwner(teamId, request.ownerMemberId()); TeamInfo team = team(teamId);
        if ("DRAFT".equals(team.status()) || "SUSPENDED".equals(team.status())) throw bad("Only active teams can request entitlements.");
        List<String> models = distinct(request.modelNames()); if (models.isEmpty() || request.requestedTokens() <= 0) throw bad("Request models and positive Tokens.");
        long id = GeneratedKeys.insert(jdbc, "INSERT INTO team_entitlement_request(team_id, owner_member_id, requested_models, requested_tokens, purpose, expires_at, status, created_at) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)", teamId, request.ownerMemberId(), String.join(",", models), request.requestedTokens(), request.purpose().trim(), JdbcTime.toTimestamp(request.expiresAt()), now());
        return item(id);
    }

    public TeamEntitlementListResponse requests(long teamId) { return list(" WHERE team_id = ?", teamId); }
    public TeamEntitlementListResponse allRequests(String status) { return list(status == null || status.isBlank() ? "" : " WHERE status = ?", status == null || status.isBlank() ? new Object[]{} : new Object[]{status}); }
    private TeamEntitlementListResponse list(String where, Object... args) { return new TeamEntitlementListResponse(jdbc.query("SELECT id, team_id, owner_member_id, requested_models, requested_tokens, granted_models, granted_tokens, purpose, expires_at, status, reviewer_note, created_at, reviewed_at FROM team_entitlement_request" + where + " ORDER BY created_at DESC", (rs, row) -> map(rs.getLong("id"), rs.getLong("team_id"), rs.getLong("owner_member_id"), rs.getString("requested_models"), rs.getLong("requested_tokens"), rs.getString("granted_models"), (Long) rs.getObject("granted_tokens"), rs.getString("purpose"), JdbcTime.toOffsetDateTime(rs.getTimestamp("expires_at")), rs.getString("status"), rs.getString("reviewer_note"), JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at")), JdbcTime.toOffsetDateTime(rs.getTimestamp("reviewed_at"))), args)); }

    @Transactional public TeamEntitlementItem review(long id, ReviewTeamEntitlementRequest request) {
        TeamEntitlementItem current = item(id); if (!"PENDING".equals(current.status())) throw bad("Only pending requests can be reviewed.");
        String decision = request.decision().trim().toUpperCase();
        if ("REJECT".equals(decision)) { jdbc.update("UPDATE team_entitlement_request SET status = 'REJECTED', reviewer_note = ?, reviewed_at = ? WHERE id = ?", request.reviewerNote(), now(), id); return item(id); }
        if (!"APPROVE".equals(decision)) throw bad("Decision must be APPROVE or REJECT.");
        List<String> models = distinct(request.grantedModelNames() == null ? current.modelNames() : request.grantedModelNames()); long tokens = request.grantedTokens() == null ? current.requestedTokens() : request.grantedTokens();
        grant(current.teamId(), models, tokens, request.grantedExpiresAt() == null ? current.expiresAt() : request.grantedExpiresAt(), "request-" + id);
        jdbc.update("UPDATE team_entitlement_request SET status = 'APPROVED', granted_models = ?, granted_tokens = ?, reviewer_note = ?, reviewed_at = ? WHERE id = ?", String.join(",", models), tokens, request.reviewerNote(), now(), id); return item(id);
    }

    @Transactional public void grantTeam(long teamId, GrantTeamEntitlementRequest request) { grant(teamId, distinct(request.modelNames()), request.tokenAllocation(), request.expiresAt(), "direct-" + UUID.randomUUID()); }
    private void grant(long teamId, List<String> models, long tokens, OffsetDateTime expires, String reference) {
        requireTeamOwnerExists(teamId); if (models.isEmpty() || tokens <= 0) throw bad("A grant needs models and positive Tokens.");
        for (String model : models) { enabledModel(model); jdbc.update("INSERT IGNORE INTO team_direct_model_access(team_id, model_name, created_at) VALUES (?, ?, ?)", teamId, model, now()); jdbc.update("INSERT INTO team_model_grant(team_id, model_name, expires_at, created_at) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE expires_at = VALUES(expires_at)", teamId, model, JdbcTime.toTimestamp(expires), now()); }
        account("TEAM_DEVELOPMENT", teamId); jdbc.update("UPDATE quota_account SET available_tokens = available_tokens + ?, version = version + 1, updated_at = ? WHERE account_type = 'TEAM_DEVELOPMENT' AND owner_id = ?", tokens, now(), teamId); long account = require("SELECT id FROM quota_account WHERE account_type = 'TEAM_DEVELOPMENT' AND owner_id = ?", teamId); long balance = require("SELECT available_tokens FROM quota_account WHERE id = ?", account); jdbc.update("INSERT INTO quota_transaction(transaction_no, account_id, transaction_type, amount, balance_after, created_at) VALUES (?, ?, 'DEVELOPMENT_GRANT', ?, ?, ?)", "qt-team-grant-" + reference, account, tokens, balance, now()); jdbc.update("UPDATE team SET status = 'ACTIVE' WHERE id = ?", teamId);
    }

    @Transactional public MemberAccessSnapshot grantMemberAccess(long teamId, long memberId, GrantMemberAccessRequest request) {
        requireOwner(teamId, request.ownerMemberId()); if (!"ACTIVE".equals(team(teamId).status())) throw bad("Only active teams can distribute access."); member(teamId, memberId); List<String> models = distinct(request.modelNames()); if (models.isEmpty() || request.tokenAllocation() <= 0) throw bad("Member access needs models and positive Tokens.");
        for (String model : models) if (count("SELECT COUNT(*) FROM team_model_grant WHERE team_id = ? AND model_name = ? AND (expires_at IS NULL OR expires_at > NOW())", teamId, model) == 0) throw bad("Model is not granted to this team: " + model);
        account("TEAM_DEVELOPMENT", teamId); account("MEMBER_DEVELOPMENT", memberId); long from = require("SELECT id FROM quota_account WHERE account_type = 'TEAM_DEVELOPMENT' AND owner_id = ?", teamId); long to = require("SELECT id FROM quota_account WHERE account_type = 'MEMBER_DEVELOPMENT' AND owner_id = ?", memberId); Long available = nullable("SELECT available_tokens FROM quota_account WHERE id = ? FOR UPDATE", from); if (available == null || available < request.tokenAllocation()) throw new ModelGateException(ErrorCode.QUOTA_INSUFFICIENT, "The team does not have enough unallocated Token quota.");
        jdbc.update("UPDATE quota_account SET available_tokens = available_tokens - ?, version = version + 1, updated_at = ? WHERE id = ?", request.tokenAllocation(), now(), from); jdbc.update("UPDATE quota_account SET available_tokens = available_tokens + ?, version = version + 1, updated_at = ? WHERE id = ?", request.tokenAllocation(), now(), to); jdbc.update("DELETE FROM member_model_access WHERE member_id = ?", memberId); for (String model : models) jdbc.update("INSERT INTO member_model_access(member_id, model_name, created_at) VALUES (?, ?, ?)", memberId, model, now()); long balance = require("SELECT available_tokens FROM quota_account WHERE id = ?", to); String transfer = "qt-transfer-" + UUID.randomUUID(); jdbc.update("INSERT INTO quota_transfer(transfer_no, team_id, member_id, from_account_id, to_account_id, amount, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", transfer, teamId, memberId, from, to, request.tokenAllocation(), request.reason() == null ? "Member allocation" : request.reason(), now()); return new MemberAccessSnapshot(memberId, to, balance, models);
    }

    @Transactional public MemberKeyScope lockMemberForKey(long memberId) { TeamMemberItem member = jdbc.queryForObject("SELECT id, organization_id, team_id, name, email, role, enabled, created_at FROM team_member WHERE id = ? AND enabled = 1 FOR UPDATE", (rs, row) -> new TeamMemberItem(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("team_id"), rs.getString("name"), rs.getString("email"), rs.getString("role"), true, JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at"))), memberId); return new MemberKeyScope(member.teamId(), require("SELECT available_tokens FROM quota_account WHERE account_type = 'MEMBER_DEVELOPMENT' AND owner_id = ?", memberId), memberModels(memberId)); }
    public List<String> memberModels(long memberId) { return jdbc.queryForList("SELECT model_name FROM member_model_access WHERE member_id = ? ORDER BY model_name", String.class, memberId); }
    public void assertOwner(long teamId, long ownerMemberId) { requireOwner(teamId, ownerMemberId); }
    public void revokeMemberModel(long teamId, long memberId, long owner, String model) { requireOwner(teamId, owner); member(teamId, memberId); jdbc.update("DELETE FROM member_model_access WHERE member_id = ? AND model_name = ?", memberId, model); }
    public void revokeTeamModel(long teamId, String model) { requireTeamOwnerExists(teamId); jdbc.update("DELETE FROM team_model_grant WHERE team_id = ? AND model_name = ?", teamId, model); jdbc.update("DELETE FROM team_direct_model_access WHERE team_id = ? AND model_name = ?", teamId, model); }
    private TeamEntitlementItem item(long id) { try { return jdbc.queryForObject("SELECT id, team_id, owner_member_id, requested_models, requested_tokens, granted_models, granted_tokens, purpose, expires_at, status, reviewer_note, created_at, reviewed_at FROM team_entitlement_request WHERE id = ?", (rs, row) -> map(rs.getLong("id"), rs.getLong("team_id"), rs.getLong("owner_member_id"), rs.getString("requested_models"), rs.getLong("requested_tokens"), rs.getString("granted_models"), (Long)rs.getObject("granted_tokens"), rs.getString("purpose"), JdbcTime.toOffsetDateTime(rs.getTimestamp("expires_at")), rs.getString("status"), rs.getString("reviewer_note"), JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at")), JdbcTime.toOffsetDateTime(rs.getTimestamp("reviewed_at"))), id); } catch (EmptyResultDataAccessException e) { throw bad("Entitlement request was not found."); } }
    private TeamEntitlementItem map(long id,long team,long owner,String requested,long requestedTokens,String granted,Long grantedTokens,String purpose,OffsetDateTime expires,String status,String note,OffsetDateTime created,OffsetDateTime reviewed) { return new TeamEntitlementItem(id, team, owner, split(requested), requestedTokens, split(granted), grantedTokens, purpose, expires, status, note, created, reviewed); }
    private void requireOwner(long team,long owner) { if (count("SELECT COUNT(*) FROM team t JOIN team_member m ON m.team_id = t.id WHERE t.id = ? AND t.enabled = 1 AND m.id = ? AND m.user_id = t.owner_user_id AND m.role = 'OWNER' AND m.enabled = 1", team,owner)==0) throw bad("This operation requires the active team owner."); }
    private void requireTeamOwnerExists(long team) { if (count("SELECT COUNT(*) FROM team t JOIN team_member m ON m.team_id=t.id AND m.user_id=t.owner_user_id AND m.role='OWNER' AND m.enabled=1 WHERE t.id=? AND t.enabled=1",team)==0) throw bad("A team needs an active owner."); }
    private TeamInfo team(long id) { try { return jdbc.queryForObject("SELECT id, organization_id, status FROM team WHERE id = ? AND enabled = 1",(rs,row)->new TeamInfo(rs.getLong("id"),rs.getLong("organization_id"),rs.getString("status")),id); } catch (EmptyResultDataAccessException e) { throw bad("Team was not found or is disabled."); } }
    private UserInfo user(long id) { try { return jdbc.queryForObject("SELECT id,name,email FROM platform_user WHERE id=? AND enabled=1",(rs,row)->new UserInfo(rs.getLong("id"),rs.getString("name"),rs.getString("email")),id); } catch (EmptyResultDataAccessException e) { throw bad("User was not found or is disabled."); } }
    private TeamMemberItem member(long team,long id) { try { return jdbc.queryForObject("SELECT id,organization_id,team_id,name,email,role,enabled,created_at FROM team_member WHERE id=? AND team_id=? AND enabled=1",(rs,row)->new TeamMemberItem(rs.getLong("id"),rs.getLong("organization_id"),rs.getLong("team_id"),rs.getString("name"),rs.getString("email"),rs.getString("role"),true,JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at"))),id,team); } catch (EmptyResultDataAccessException e) { throw bad("Member was not found or is disabled."); } }
    private void enabledModel(String name) { if (count("SELECT COUNT(*) FROM provider_model WHERE model_name=? AND enabled=1",name)==0) throw bad("Model was not found or disabled: " + name); }
    private void account(String type,long owner) { jdbc.update("INSERT IGNORE INTO quota_account(account_type, owner_id, available_tokens, frozen_tokens, consumed_tokens, version, updated_at) VALUES (?, ?, 0, 0, 0, 0, ?)",type,owner,now()); }
    private long require(String sql,Object... args) { Long value=jdbc.queryForObject(sql,Long.class,args); if(value==null)throw bad("Required data was not found."); return value; }
    private Long nullable(String sql,Object... args) { try{return jdbc.queryForObject(sql,Long.class,args);}catch(EmptyResultDataAccessException e){return null;} }
    private int count(String sql,Object... args) { Integer value=jdbc.queryForObject(sql,Integer.class,args);return value==null?0:value; }
    private static List<String> distinct(List<String> values) { return values==null?List.of():List.copyOf(new LinkedHashSet<>(values.stream().filter(v->v!=null&&!v.isBlank()).map(String::trim).toList())); }
    private static List<String> split(String values) { return values==null||values.isBlank()?List.of():List.of(values.split(",")); }
    private static java.sql.Timestamp now() { return JdbcTime.toTimestamp(OffsetDateTime.now()); }
    private static ModelGateException bad(String message) { return new ModelGateException(ErrorCode.BAD_MODEL_REQUEST,message); }
    public record MemberAccessSnapshot(long memberId,long quotaAccountId,long availableTokens,List<String> models) { }
    public record MemberKeyScope(long teamId,long availableTokens,List<String> models) { }
    public record MemberDeactivation(long memberId, List<Long> entitlementGrantIds, List<String> keyHashes, Long quotaAccountId) { }
    private record ExistingMember(long memberId, long teamId, boolean enabled) { }
    private record TeamInfo(long teamId,long organizationId,String status) { }
    private record UserInfo(long userId,String name,String email) { }
}
