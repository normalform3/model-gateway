package com.modelgate.infrastructure.db;

import com.modelgate.common.api.AdminDtos.*;
import com.modelgate.common.domain.EntitlementQuotaLimits;
import com.modelgate.common.domain.ModelQuotaPolicy;
import com.modelgate.common.domain.QuotaMode;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.common.event.QuotaSettlementSnapshot;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Model-scoped current team/member entitlement source of truth. */
@Repository
public class ModelEntitlementRepository {
    private static final ZoneId QUOTA_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;

    public ModelEntitlementRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public ModelEntitlementItem upsertTeam(long teamId, String modelName, UpsertModelEntitlementRequest request) {
        requireTeamOwner(teamId); requireEnabledModel(modelName); QuotaMode mode = mode(request.quotaMode()); EntitlementQuotaLimits.validateTeam(mode, request.quotaLimit());
        if (mode != QuotaMode.UNLIMITED && activeMemberModeMismatch(teamId, modelName, mode) > 0) {
            throw bad("Active member entitlements must use the same periodic quota type as the team.");
        }
        if (mode != QuotaMode.UNLIMITED && activeMemberLimits(teamId, modelName) > request.quotaLimit()) {
            throw bad("The team limit is lower than active member allocations for this model.");
        }
        validateAlertThreshold(request.alertRemainingPercent());
        long id = upsert(teamId, null, modelName, mode, request.quotaLimit(), request.alertRemainingPercent(), request.reason());
        return item(id);
    }

    @Transactional
    public ModelEntitlementItem upsertMember(long teamId, long memberId, String modelName, UpsertModelEntitlementRequest request) {
        if (request.ownerMemberId() == null) throw bad("ownerMemberId is required for member entitlement changes.");
        requireOwner(teamId, request.ownerMemberId()); requireMember(teamId, memberId); requireEnabledModel(modelName);
        ModelQuotaPolicy parent = currentTeamPolicy(teamId, modelName);
        if (parent == null) throw bad("The model is not currently granted to this team.");
        QuotaMode mode = mode(request.quotaMode()); EntitlementQuotaLimits.validateMember(mode, request.quotaLimit());
        if (parent.mode() != QuotaMode.UNLIMITED && parent.mode() != mode) throw bad("A member must use the same periodic quota type as the team.");
        if (parent.mode() != QuotaMode.UNLIMITED && activeMemberLimitsExcept(teamId, memberId, modelName) + request.quotaLimit() > parent.limit()) {
            throw bad("Member allocations exceed the team limit for this model.");
        }
        validateAlertThreshold(request.alertRemainingPercent());
        long id = upsert(teamId, memberId, modelName, mode, request.quotaLimit(), request.alertRemainingPercent(), request.reason());
        return item(id);
    }

    @Transactional
    public List<Long> revokeTeam(long teamId, String modelName) {
        requireEnabledTeam(teamId);
        List<Long> grantIds = activeGrantIds("team_id = ? AND model_name = ?", teamId, modelName);
        jdbc.update("DELETE FROM model_entitlement_grant WHERE team_id = ? AND model_name = ?", teamId, modelName);
        return grantIds;
    }

    @Transactional
    public List<Long> revokeMember(long teamId, long memberId, String modelName, long ownerMemberId) {
        requireOwner(teamId, ownerMemberId);
        List<Long> grantIds = activeGrantIds("team_id = ? AND member_id = ? AND model_name = ?", teamId, memberId, modelName);
        jdbc.update("DELETE FROM model_entitlement_grant WHERE team_id = ? AND member_id = ? AND model_name = ?", teamId, memberId, modelName);
        return grantIds;
    }

    public ModelEntitlementListResponse teamEntitlements(long teamId) { return new ModelEntitlementListResponse(list("team_id = ? AND member_id IS NULL AND project_id IS NULL AND pool_type = 'DEVELOPMENT'", teamId)); }
    public ModelEntitlementListResponse memberEntitlements(long teamId, long memberId) { return new ModelEntitlementListResponse(list("team_id = ? AND member_id = ? AND pool_type = 'DEVELOPMENT'", teamId, memberId)); }
    public ModelEntitlementListResponse teamApplicationEntitlements(long teamId) { return new ModelEntitlementListResponse(list("team_id = ? AND member_id IS NULL AND project_id IS NULL AND pool_type = 'APPLICATION'", teamId)); }
    public ModelEntitlementListResponse projectApplicationEntitlements(long teamId, long projectId) { return new ModelEntitlementListResponse(list("team_id = ? AND project_id = ? AND pool_type = 'APPLICATION'", teamId, projectId)); }

    public RuntimePolicies runtimePolicies(long teamId, long memberId) {
        Map<String, ModelQuotaPolicy> teams = policies("team_id = ? AND member_id IS NULL AND project_id IS NULL AND pool_type = 'DEVELOPMENT' AND status = 'ACTIVE'", teamId);
        Map<String, ModelQuotaPolicy> members = policies("team_id = ? AND member_id = ? AND pool_type = 'DEVELOPMENT' AND status = 'ACTIVE'", teamId, memberId);
        members.keySet().removeIf(model -> !teams.containsKey(model));
        return new RuntimePolicies(teams, members);
    }

    public UsageSnapshot usage(ModelQuotaPolicy policy, String cycleStart) {
        if (!policy.limited()) return new UsageSnapshot(0, 0);
        try {
            return jdbc.queryForObject("SELECT consumed_tokens, frozen_tokens FROM model_entitlement_usage WHERE grant_id = ? AND cycle_started_at = ?",
                    (rs, row) -> new UsageSnapshot(rs.getLong(1), rs.getLong(2)), policy.grantId(), cycleTimestamp(cycleStart));
        } catch (EmptyResultDataAccessException ex) { return new UsageSnapshot(0, 0); }
    }

    @Transactional
    public void settle(ModelQuotaPolicy policy, String cycleStart, int estimated, int actual) { mutateUsage(policy, cycleStart, estimated, actual, false); }
    @Transactional
    public void release(ModelQuotaPolicy policy, String cycleStart, int estimated) { mutateUsage(policy, cycleStart, estimated, 0, true); }

    /** Applies the final ledger mutation after the gateway has already settled Redis. */
    @Transactional
    public void applySettlement(QuotaSettlementSnapshot settlement) {
        ModelQuotaPolicy policy = new ModelQuotaPolicy(settlement.grantId(), settlement.modelName(),
                QuotaMode.valueOf(settlement.quotaMode()), settlement.quotaLimit(), settlement.alertRemainingPercent());
        mutateUsage(policy, settlement.cycleStartedAt().toString(), settlement.estimatedTokens(), settlement.actualTokens(), settlement.released());
    }

    public UsageDashboard teamDashboard(long teamId) {
        return new UsageDashboard(teamId, list("team_id = ? AND member_id IS NULL AND project_id IS NULL AND pool_type = 'DEVELOPMENT'", teamId), trends("u.team_id = ? AND u.credential_type = 'DEVELOPER'", teamId), ranking(teamId));
    }
    public UsageDashboard memberDashboard(long memberId) {
        Long teamId = jdbc.queryForObject("SELECT team_id FROM team_member WHERE id = ?", Long.class, memberId);
        return new UsageDashboard(memberId, list("team_id = ? AND member_id = ? AND pool_type = 'DEVELOPMENT'", teamId, memberId), trends("u.member_id = ? AND u.credential_type = 'DEVELOPER'", memberId), List.of());
    }

    /** Platform-wide view of active team grants. Member grants are derived allocations and must not be counted twice. */
    public QuotaSummary platformQuotaSummary() {
        List<ModelEntitlementItem> grants = jdbc.query("""
                SELECT meg.id, meg.team_id, meg.member_id, meg.model_name, meg.quota_mode, meg.quota_limit, meg.alert_remaining_percent,
                       meg.status, meg.reason, meg.created_at, meg.revoked_at
                FROM model_entitlement_grant meg
                JOIN team t ON t.id = meg.team_id
                WHERE meg.member_id IS NULL AND meg.project_id IS NULL AND meg.pool_type = 'DEVELOPMENT' AND meg.status = 'ACTIVE' AND t.enabled = 1
                ORDER BY meg.model_name, meg.quota_mode, meg.team_id
                """, (rs, row) -> itemFromRow(rs));
        Map<String, QuotaSummaryAccumulator> grouped = new LinkedHashMap<>();
        int unlimited = 0;
        for (ModelEntitlementItem grant : grants) {
            if (grant.quotaLimit() == null) { unlimited++; continue; }
            String key = grant.modelName() + "\u0000" + grant.quotaMode();
            grouped.computeIfAbsent(key, ignored -> new QuotaSummaryAccumulator(grant.modelName(), grant.quotaMode()))
                    .add(grant);
        }
        List<QuotaSummaryItem> items = grouped.values().stream().map(QuotaSummaryAccumulator::item).toList();
        long allocated = items.stream().mapToLong(QuotaSummaryItem::allocatedTokens).sum();
        long consumed = items.stream().mapToLong(QuotaSummaryItem::consumedTokens).sum();
        long frozen = items.stream().mapToLong(QuotaSummaryItem::frozenTokens).sum();
        long remaining = items.stream().mapToLong(QuotaSummaryItem::remainingTokens).sum();
        return new QuotaSummary(allocated, consumed, frozen, remaining, unlimited, items);
    }

    public static String cycleStart(QuotaMode mode) {
        ZonedDateTime now = ZonedDateTime.now(QUOTA_ZONE);
        ZonedDateTime start = switch (mode) {
            case DAILY -> now.toLocalDate().atStartOfDay(QUOTA_ZONE);
            case WEEKLY -> now.toLocalDate().minusDays(now.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue()).atStartOfDay(QUOTA_ZONE);
            case UNLIMITED -> now.toLocalDate().atStartOfDay(QUOTA_ZONE);
        };
        return start.toOffsetDateTime().toString();
    }

    private void mutateUsage(ModelQuotaPolicy policy, String cycleStart, int estimated, int actual, boolean released) {
        if (!policy.limited()) return;
        Timestamp cycle = cycleTimestamp(cycleStart);
        jdbc.update("""
                INSERT INTO model_entitlement_usage(grant_id, cycle_started_at, consumed_tokens, frozen_tokens, version, updated_at)
                SELECT id, ?, ?, ?, 0, ?
                FROM model_entitlement_grant
                WHERE id = ? AND status = 'ACTIVE'
                ON DUPLICATE KEY UPDATE consumed_tokens = consumed_tokens + VALUES(consumed_tokens),
                  frozen_tokens = GREATEST(0, frozen_tokens + VALUES(frozen_tokens)), version = version + 1, updated_at = VALUES(updated_at)
                """, cycle, released ? 0 : actual, -estimated, now(), policy.grantId());
    }

    private List<ModelEntitlementItem> list(String where, Object... args) {
        return jdbc.query("SELECT id, team_id, member_id, model_name, quota_mode, quota_limit, alert_remaining_percent, status, reason, created_at, revoked_at FROM model_entitlement_grant WHERE status = 'ACTIVE' AND " + where + " ORDER BY created_at DESC", (rs, row) -> itemFromRow(rs), args);
    }

    private ModelEntitlementItem itemFromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        long id = rs.getLong("id"); QuotaMode mode = mode(rs.getString("quota_mode")); String cycle = cycleStart(mode); UsageSnapshot usage = usage(new ModelQuotaPolicy(id, rs.getString("model_name"), mode, nullableLong(rs.getObject("quota_limit")), nullableInt(rs.getObject("alert_remaining_percent"))), cycle);
        Long limit = nullableLong(rs.getObject("quota_limit")); Long remaining = limit == null ? null : Math.max(0, limit - usage.consumedTokens() - usage.frozenTokens());
        return new ModelEntitlementItem(id, rs.getLong("team_id"), nullableLong(rs.getObject("member_id")), rs.getString("model_name"), mode.name(), limit, nullableInt(rs.getObject("alert_remaining_percent")), rs.getString("status"), usage.consumedTokens(), usage.frozenTokens(), remaining, OffsetDateTime.parse(cycle), rs.getString("reason"), JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at")), JdbcTime.toOffsetDateTime(rs.getTimestamp("revoked_at")));
    }

    private List<UsageTrendItem> trends(String where, Object... args) {
        return jdbc.query("SELECT DATE(u.occurred_at) day, COALESCE(SUM(u.total_tokens),0) tokens, COALESCE(SUM(b.amount),0) amount, COUNT(*) requests FROM usage_record u LEFT JOIN billing_record b ON b.request_id = u.request_id WHERE " + where + " AND u.occurred_at >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) GROUP BY DATE(u.occurred_at) ORDER BY day", (rs, row) -> new UsageTrendItem(rs.getString("day"), rs.getLong("tokens"), rs.getBigDecimal("amount"), rs.getLong("requests")), args);
    }
    private List<MemberUsageRankItem> ranking(long teamId) {
        return jdbc.query("SELECT m.id, m.name, COALESCE(SUM(u.total_tokens),0) tokens, COALESCE(SUM(b.amount),0) amount FROM team_member m LEFT JOIN usage_record u ON u.member_id = m.id AND u.occurred_at >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) LEFT JOIN billing_record b ON b.request_id = u.request_id WHERE m.team_id = ? GROUP BY m.id,m.name ORDER BY tokens DESC", (rs, row) -> new MemberUsageRankItem(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getBigDecimal(4)), teamId);
    }

    private Map<String, ModelQuotaPolicy> policies(String where, Object... args) {
        Map<String, ModelQuotaPolicy> result = new LinkedHashMap<>();
        jdbc.query("SELECT id, model_name, quota_mode, quota_limit, alert_remaining_percent FROM model_entitlement_grant WHERE " + where, (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getString("model_name"), new ModelQuotaPolicy(rs.getLong("id"), rs.getString("model_name"), mode(rs.getString("quota_mode")), nullableLong(rs.getObject("quota_limit")), nullableInt(rs.getObject("alert_remaining_percent")))), args);
        return result;
    }
    private ModelQuotaPolicy currentTeamPolicy(long teamId, String model) {
        Map<String, ModelQuotaPolicy> result = policies("team_id = ? AND member_id IS NULL AND project_id IS NULL AND pool_type = 'DEVELOPMENT' AND model_name = ? AND status = 'ACTIVE'", teamId, model); return result.get(model);
    }
    private long upsert(long team, Long member, String model, QuotaMode mode, Long limit, Integer alertRemainingPercent, String reason) {
        String where = member == null ? "team_id = ? AND member_id IS NULL AND model_name = ?" : "team_id = ? AND member_id = ? AND model_name = ?";
        Object[] args = member == null ? new Object[] {team, model} : new Object[] {team, member, model};
        List<Long> existing = activeGrantIds(where + " AND pool_type = 'DEVELOPMENT'", args);
        if (!existing.isEmpty()) {
            long id = existing.get(0);
            jdbc.update("UPDATE model_entitlement_grant SET quota_mode = ?, quota_limit = ?, alert_remaining_percent = ?, reason = ? WHERE id = ? AND status = 'ACTIVE' AND pool_type = 'DEVELOPMENT'",
                    mode.name(), limit, alertRemainingPercent, reason == null ? "" : reason.trim(), id);
            return id;
        }
        return GeneratedKeys.insert(jdbc, "INSERT INTO model_entitlement_grant(team_id, member_id, project_id, model_name, pool_type, quota_mode, quota_limit, alert_remaining_percent, status, reason, created_at) VALUES (?, ?, NULL, ?, 'DEVELOPMENT', ?, ?, ?, 'ACTIVE', ?, ?)", team, member, model, mode.name(), limit, alertRemainingPercent, reason == null ? "" : reason.trim(), now());
    }
    private ModelEntitlementItem item(long id) { return list("id = ?", id).get(0); }
    private List<Long> activeGrantIds(String where, Object... args) { return jdbc.query("SELECT id FROM model_entitlement_grant WHERE status = 'ACTIVE' AND " + where, (rs, row) -> rs.getLong(1), args); }
    private long activeMemberLimits(long team, String model) { return activeMemberLimitsExcept(team, -1, model); }
    private long activeMemberModeMismatch(long team, String model, QuotaMode mode) { Long v = jdbc.queryForObject("SELECT COUNT(*) FROM model_entitlement_grant WHERE team_id = ? AND member_id IS NOT NULL AND model_name = ? AND status = 'ACTIVE' AND quota_mode <> ?", Long.class, team, model, mode.name()); return v == null ? 0 : v; }
    private long activeMemberLimitsExcept(long team, long member, String model) { Long v = jdbc.queryForObject("SELECT COALESCE(SUM(quota_limit),0) FROM model_entitlement_grant WHERE team_id = ? AND member_id IS NOT NULL AND member_id <> ? AND model_name = ? AND status = 'ACTIVE' AND quota_mode <> 'UNLIMITED'", Long.class, team, member, model); return v == null ? 0 : v; }
    private void requireTeamOwner(long team) { Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM team t JOIN team_member m ON m.team_id=t.id AND m.user_id=t.owner_user_id WHERE t.id=? AND t.enabled=1 AND m.enabled=1", Integer.class, team); if (count == null || count == 0) throw bad("An enabled team owner is required."); }
    private void requireOwner(long team, long owner) { Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM team t JOIN team_member m ON m.id=? AND m.team_id=t.id AND m.user_id=t.owner_user_id AND m.role='OWNER' AND m.enabled=1 WHERE t.id=? AND t.enabled=1", Integer.class, owner, team); if (count == null || count == 0) throw bad("This operation requires the active team owner."); }
    private void requireEnabledTeam(long team) { Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM team WHERE id=? AND enabled=1", Integer.class, team); if (count == null || count == 0) throw bad("Team was not found or is disabled."); }
    private void requireMember(long team, long member) { Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM team_member WHERE team_id=? AND id=? AND enabled=1", Integer.class, team, member); if (count == null || count == 0) throw bad("Member was not found."); }
    private void requireEnabledModel(String model) { Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM provider_model WHERE model_name=? AND enabled=1", Integer.class, model); if (count == null || count == 0) throw bad("Model was not found or disabled: " + model); }
    private static QuotaMode mode(String raw) { try { return QuotaMode.valueOf(raw == null ? "" : raw.trim().toUpperCase()); } catch (IllegalArgumentException ex) { throw bad("quotaMode must be DAILY, WEEKLY, or UNLIMITED."); } }
    private static Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static Integer nullableInt(Object value) { return value == null ? null : ((Number) value).intValue(); }
    private static void validateAlertThreshold(Integer value) { if (value != null && (value < 1 || value > 100)) throw bad("alertRemainingPercent must be between 1 and 100."); }
    private static Timestamp cycleTimestamp(String cycleStart) { return JdbcTime.toTimestamp(OffsetDateTime.parse(cycleStart)); }
    private static Timestamp now() { return JdbcTime.toTimestamp(OffsetDateTime.now()); }
    private static ModelGateException bad(String message) { return new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, message); }
    public record RuntimePolicies(Map<String, ModelQuotaPolicy> team, Map<String, ModelQuotaPolicy> member) { }
    public record UsageSnapshot(long consumedTokens, long frozenTokens) { }

    private static final class QuotaSummaryAccumulator {
        private final String modelName;
        private final String quotaMode;
        private final Set<Long> teamIds = new LinkedHashSet<>();
        private long allocated;
        private long consumed;
        private long frozen;
        private long remaining;

        private QuotaSummaryAccumulator(String modelName, String quotaMode) { this.modelName = modelName; this.quotaMode = quotaMode; }
        private void add(ModelEntitlementItem item) {
            teamIds.add(item.teamId()); allocated += item.quotaLimit(); consumed += item.consumedTokens(); frozen += item.frozenTokens(); remaining += item.remainingTokens();
        }
        private QuotaSummaryItem item() { return new QuotaSummaryItem(modelName, quotaMode, teamIds.size(), allocated, consumed, frozen, remaining); }
    }
}
