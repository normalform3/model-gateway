package com.modelgate.infrastructure.db;

import com.modelgate.common.api.TestObservabilityDtos.TestCaller;
import com.modelgate.common.api.TestObservabilityDtos.TestRunSummary;
import com.modelgate.common.domain.ModelQuotaPolicy;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Narrow persistence adapter for development-only test-run observability. */
@Repository
public class TestObservabilityRepository {
    private static final String CALLER_SQL = """
            SELECT m.id member_id, m.name member_name, m.organization_id, m.team_id, t.name team_name,
                   0 available_tokens
            FROM team_member m
            JOIN team t ON t.id = m.team_id AND t.enabled = 1
            JOIN model_entitlement_grant tg ON tg.team_id = m.team_id AND tg.member_id IS NULL
                AND tg.model_name = ? AND tg.status = 'ACTIVE'
            JOIN model_entitlement_grant mg ON mg.team_id = m.team_id AND mg.member_id = m.id
                AND mg.model_name = ? AND mg.status = 'ACTIVE'
            WHERE m.role = 'MEMBER' AND m.enabled = 1
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ModelEntitlementRepository modelEntitlements;

    public TestObservabilityRepository(JdbcTemplate jdbcTemplate, ModelEntitlementRepository modelEntitlements) {
        this.jdbcTemplate = jdbcTemplate;
        this.modelEntitlements = modelEntitlements;
    }

    public List<String> mockModels() {
        return jdbcTemplate.queryForList("""
                SELECT pm.model_name
                FROM provider_model pm JOIN provider p ON p.id = pm.provider_id
                WHERE pm.enabled = 1 AND p.enabled = 1 AND p.provider_type = 'MOCK_OPENAI'
                ORDER BY pm.model_name
                """, String.class);
    }

    public List<TestCaller> eligibleCallers(String model) {
        return jdbcTemplate.query(CALLER_SQL + " ORDER BY m.id", (rs, rowNum) -> new CallerScope(
                rs.getLong("organization_id"), rs.getLong("member_id"), rs.getString("member_name"), rs.getLong("team_id"),
                rs.getString("team_name"), rs.getLong("available_tokens")), model, model).stream()
                .map(scope -> withAvailable(scope, model)).filter(Optional::isPresent).map(Optional::get)
                .map(scope -> new TestCaller(scope.memberId(), scope.memberName(), scope.teamId(), scope.teamName(), scope.availableTokens())).toList();
    }

    public Optional<CallerScope> lockEligibleCaller(long memberId, String model) {
        try {
            CallerScope scope = jdbcTemplate.queryForObject(CALLER_SQL + " AND m.id = ? FOR UPDATE", (rs, rowNum) -> new CallerScope(
                    rs.getLong("organization_id"), rs.getLong("member_id"), rs.getString("member_name"),
                    rs.getLong("team_id"), rs.getString("team_name"), rs.getLong("available_tokens")), model, model, memberId);
            return withAvailable(scope, model);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public long insertTestKey(CallerScope scope, String runId, String keyPrefix, String keyHash, OffsetDateTime expiresAt) {
        return GeneratedKeys.insert(jdbcTemplate, """
                INSERT INTO virtual_api_key(
                    organization_id, team_id, owner_member_id, name, key_prefix, key_hash,
                    key_kind, test_run_id, enabled, expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'TEST', ?, 1, ?, ?)
                """, scope.organizationId(), scope.teamId(), scope.memberId(),
                "test-run-" + runId + "-member-" + scope.memberId(), keyPrefix, keyHash, runId,
                Timestamp.from(expiresAt.toInstant()), Timestamp.from(OffsetDateTime.now().toInstant()));
    }

    public boolean isActiveTestKeyForRun(long keyId, String runId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM virtual_api_key
                WHERE id = ? AND key_kind = 'TEST' AND test_run_id = ? AND enabled = 1
                """, Integer.class, keyId, runId);
        return count != null && count == 1;
    }

    public List<KeyRef> disableTestKeys(String runId) {
        List<KeyRef> keys = jdbcTemplate.query("SELECT id, key_hash FROM virtual_api_key WHERE test_run_id = ? AND key_kind = 'TEST' AND enabled = 1",
                (rs, rowNum) -> new KeyRef(rs.getLong("id"), rs.getString("key_hash")), runId);
        jdbcTemplate.update("UPDATE virtual_api_key SET enabled = 0 WHERE test_run_id = ? AND key_kind = 'TEST' AND enabled = 1", runId);
        return keys;
    }

    public TestRunSummary runSummary(String runId) {
        RunCounts requests = jdbcTemplate.queryForObject("""
                SELECT COUNT(*), COALESCE(SUM(status = 'SUCCESS'), 0), COALESCE(SUM(status = 'FAILED'), 0),
                       COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0)
                FROM ai_request WHERE test_run_id = ?
                """, (rs, rowNum) -> new RunCounts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)), runId);
        UsageCounts usage = jdbcTemplate.queryForObject("""
                SELECT COUNT(*), COALESCE(SUM(u.total_tokens), 0)
                FROM usage_record u JOIN ai_request r ON r.request_id = u.request_id
                WHERE r.test_run_id = ?
                """, (rs, rowNum) -> new UsageCounts(rs.getLong(1), rs.getLong(2)), runId);
        BillingCounts billing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*), COALESCE(SUM(b.input_tokens + b.output_tokens), 0),
                       COALESCE(SUM(b.amount), 0), COALESCE(MAX(b.currency), 'USD')
                FROM billing_record b JOIN ai_request r ON r.request_id = b.request_id
                WHERE r.test_run_id = ?
                """, (rs, rowNum) -> new BillingCounts(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3), rs.getString(4)), runId);
        return new TestRunSummary(runId, requests.recorded(), requests.success(), requests.failed(), requests.input(), requests.output(),
                usage.records(), usage.tokens(), billing.records(), billing.tokens(), billing.amount(), billing.currency(),
                Math.max(0, requests.success() - billing.records()));
    }

    private Optional<CallerScope> withAvailable(CallerScope scope, String model) {
        ModelEntitlementRepository.RuntimePolicies policies = modelEntitlements.runtimePolicies(scope.teamId(), scope.memberId());
        ModelQuotaPolicy team = policies.team().get(model);
        ModelQuotaPolicy member = policies.member().get(model);
        if (team == null || member == null) return Optional.empty();
        OptionalLong teamAvailable = available(team);
        OptionalLong memberAvailable = available(member);
        if (teamAvailable.isEmpty() || memberAvailable.isEmpty()) return Optional.empty();
        return Optional.of(new CallerScope(scope.organizationId(), scope.memberId(), scope.memberName(), scope.teamId(), scope.teamName(),
                Math.min(teamAvailable.getAsLong(), memberAvailable.getAsLong())));
    }

    private OptionalLong available(ModelQuotaPolicy policy) {
        if (!policy.limited()) return OptionalLong.of(Long.MAX_VALUE);
        ModelEntitlementRepository.UsageSnapshot usage = modelEntitlements.usage(policy, ModelEntitlementRepository.cycleStart(policy.mode()));
        long remaining = Math.max(0, policy.limit() - usage.consumedTokens() - usage.frozenTokens());
        return remaining > 0 ? OptionalLong.of(remaining) : OptionalLong.empty();
    }

    public record CallerScope(long organizationId, long memberId, String memberName, long teamId, String teamName, long availableTokens) {
    }

    public record KeyRef(long keyId, String keyHash) {
    }

    private record RunCounts(long recorded, long success, long failed, long input, long output) {
    }

    private record UsageCounts(long records, long tokens) {
    }

    private record BillingCounts(long records, long tokens, BigDecimal amount, String currency) {
    }
}
