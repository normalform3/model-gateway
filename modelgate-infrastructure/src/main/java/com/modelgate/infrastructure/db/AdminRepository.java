package com.modelgate.infrastructure.db;

import com.modelgate.common.api.AdminDtos.BootstrapDemoResponse;
import com.modelgate.common.api.AdminDtos.ShowcaseBootstrapResponse;
import com.modelgate.common.api.AdminDtos.ShowcaseTeam;
import com.modelgate.common.api.AdminDtos.DemoIdentity;
import com.modelgate.common.api.AdminDtos.DemoIdentityResponse;
import com.modelgate.common.api.AdminDtos.CreateTeamMemberRequest;
import com.modelgate.common.api.AdminDtos.CreateTeamRequest;
import com.modelgate.common.api.AdminDtos.RequestLogItem;
import com.modelgate.common.api.AdminDtos.RequestLogResponse;
import com.modelgate.common.api.AdminDtos.TeamListResponse;
import com.modelgate.common.api.AdminDtos.TeamMemberItem;
import com.modelgate.common.api.AdminDtos.TeamMemberCandidate;
import com.modelgate.common.api.AdminDtos.TeamMemberCandidateListResponse;
import com.modelgate.common.api.AdminDtos.TeamMemberListResponse;
import com.modelgate.common.api.AdminDtos.TeamSummary;
import com.modelgate.common.api.AdminDtos.UpdateTeamMemberRequest;
import com.modelgate.common.api.AdminDtos.UpdateTeamRequest;
import com.modelgate.common.domain.ApiKeyContext;
import com.modelgate.common.domain.CredentialType;
import com.modelgate.common.domain.BudgetPolicy;
import com.modelgate.common.domain.ModelQuotaPolicy;
import com.modelgate.common.domain.QuotaMode;
import com.modelgate.common.domain.RateLimitPolicy;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Set;

@Repository
public class AdminRepository {
    private static final String DEMO_ORGANIZATION_NAME = "Demo Organization";
    private static final Set<String> TEAM_STATUSES = Set.of("DRAFT", "READY_FOR_REQUEST", "ACTIVE", "SUSPENDED", "DISSOLVED");
    private static final String DEMO_TEAM_NAME = "Demo Team";
    private static final String DEMO_OWNER_EMAIL = "demo-owner@example.com";
    private static final String DEMO_DEVELOPER_EMAIL = "demo-developer@example.com";
    private static final String SHOWCASE_ORGANIZATION_NAME = "Showcase Organization";
    private static final String SHOWCASE_PROVIDER_NAME = "Showcase Mock OpenAI";
    private static final String SHOWCASE_REASON = "Showcase data seed";
    private static final ZoneId SHOWCASE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final SecureRandom SHOWCASE_RANDOM = new SecureRandom();
    private static final ModelSpec SHOWCASE_CHAT_MODEL = new ModelSpec("showcase-chat-mini", "DAILY", 250_000L,
            new BigDecimal("0.15"), new BigDecimal("0.60"));
    private static final ModelSpec SHOWCASE_REASONING_MODEL = new ModelSpec("showcase-reasoning-mini", "WEEKLY", 100_000L,
            new BigDecimal("0.60"), new BigDecimal("2.40"));

    private final JdbcTemplate jdbcTemplate;

    public AdminRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BootstrapDemoResponse bootstrapDemo() {
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update("INSERT IGNORE INTO organization(name, created_at) VALUES (?, ?)",
                DEMO_ORGANIZATION_NAME, JdbcTime.toTimestamp(now));
        long orgId = requireId("SELECT id FROM organization WHERE name = ?", DEMO_ORGANIZATION_NAME);

        jdbcTemplate.update("""
                        INSERT IGNORE INTO team(organization_id, name, key_rpm, team_rpm, team_tpm, key_concurrency, team_concurrency, model_concurrency, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                orgId, DEMO_TEAM_NAME, 60, 600, 120000, 5, 20, 50, JdbcTime.toTimestamp(now));
        long teamId = requireId("SELECT id FROM team WHERE organization_id = ? AND name = ?", orgId, DEMO_TEAM_NAME);

        jdbcTemplate.update("""
                        INSERT IGNORE INTO platform_user(name, email, enabled, created_at, updated_at)
                        VALUES (?, ?, 1, ?, ?)
                        """, "Demo Owner", DEMO_OWNER_EMAIL, JdbcTime.toTimestamp(now), JdbcTime.toTimestamp(now));
        jdbcTemplate.update("""
                        INSERT IGNORE INTO platform_user(name, email, enabled, created_at, updated_at)
                        VALUES (?, ?, 1, ?, ?)
                        """, "Demo Developer", DEMO_DEVELOPER_EMAIL, JdbcTime.toTimestamp(now), JdbcTime.toTimestamp(now));
        long ownerUserId = requireId("SELECT id FROM platform_user WHERE email = ?", DEMO_OWNER_EMAIL);
        long developerUserId = requireId("SELECT id FROM platform_user WHERE email = ?", DEMO_DEVELOPER_EMAIL);

        jdbcTemplate.update("""
                        INSERT IGNORE INTO team_member(organization_id, team_id, user_id, name, email, role, enabled, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                orgId, teamId, ownerUserId, "Demo Owner", DEMO_OWNER_EMAIL, "OWNER", 1, JdbcTime.toTimestamp(now));

        jdbcTemplate.update("""
                        INSERT IGNORE INTO team_member(organization_id, team_id, user_id, name, email, role, enabled, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                orgId, teamId, developerUserId, "Demo Developer", DEMO_DEVELOPER_EMAIL, "MEMBER", 1, JdbcTime.toTimestamp(now));
        jdbcTemplate.update("UPDATE team SET owner_user_id = ? WHERE id = ?", ownerUserId, teamId);

        jdbcTemplate.update("INSERT IGNORE INTO provider(name, provider_type, enabled, created_at) VALUES (?, ?, ?, ?)",
                "Mock ChatGPT API", "MOCK_OPENAI", 1, JdbcTime.toTimestamp(now));
        long providerId = requireId("SELECT id FROM provider WHERE name = ?", "Mock ChatGPT API");

        jdbcTemplate.update("INSERT IGNORE INTO model(logical_model, created_at) VALUES (?, ?)",
                "smart-chat", JdbcTime.toTimestamp(now));

        jdbcTemplate.update("""
                        INSERT IGNORE INTO model_deployment(provider_id, name, actual_model, enabled, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                providerId, "mock-gpt-4o-mini", "gpt-4o-mini", 1, JdbcTime.toTimestamp(now));
        long deploymentId = requireId("SELECT id FROM model_deployment WHERE provider_id = ? AND name = ?",
                providerId, "mock-gpt-4o-mini");

        jdbcTemplate.update("""
                        INSERT IGNORE INTO provider_model(provider_id, model_name, enabled, input_price_per_million, output_price_per_million, currency, created_at, updated_at)
                        VALUES (?, ?, 1, 0, 0, 'USD', ?, ?)
                        """, providerId, "mock-gpt-4o-mini", JdbcTime.toTimestamp(now), JdbcTime.toTimestamp(now));

        jdbcTemplate.update("INSERT IGNORE INTO model_route(logical_model, strategy, enabled, created_at) VALUES (?, ?, ?, ?)",
                "smart-chat", "FIXED", 1, JdbcTime.toTimestamp(now));
        long routeId = requireId("SELECT id FROM model_route WHERE logical_model = ?", "smart-chat");

        jdbcTemplate.update("INSERT IGNORE INTO team_model_access(team_id, logical_model, created_at) VALUES (?, ?, ?)",
                teamId, "smart-chat", JdbcTime.toTimestamp(now));
        jdbcTemplate.update("INSERT IGNORE INTO team_direct_model_access(team_id, model_name, created_at) VALUES (?, ?, ?)",
                teamId, "mock-gpt-4o-mini", JdbcTime.toTimestamp(now));

        jdbcTemplate.update("INSERT IGNORE INTO route_target(route_id, deployment_id, weight, enabled, created_at) VALUES (?, ?, ?, ?, ?)",
                routeId, deploymentId, 100, 1, JdbcTime.toTimestamp(now));

        jdbcTemplate.update("""
                        INSERT IGNORE INTO quota_account(account_type, owner_id, available_tokens, frozen_tokens, consumed_tokens, version, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                "TEAM", teamId, 500_000L, 0L, 0L, 0L, JdbcTime.toTimestamp(now));
        long quotaAccountId = requireId("SELECT id FROM quota_account WHERE account_type = ? AND owner_id = ?", "TEAM", teamId);

        return new BootstrapDemoResponse(orgId, teamId, quotaAccountId, "mock-gpt-4o-mini");
    }

    /**
     * Seeds a self-contained, non-production showcase dataset. All records use stable showcase names and request IDs,
     * so repeated calls refresh the same facts without touching regular teams or publishing usage events.
     */
    @Transactional
    public ShowcaseBootstrapResponse bootstrapShowcase() {
        OffsetDateTime now = OffsetDateTime.now(SHOWCASE_ZONE);
        LocalDate today = LocalDate.now(SHOWCASE_ZONE);
        LocalDate weekStart = today.minusDays(today.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
        Timestamp nowTimestamp = JdbcTime.toTimestamp(now);
        removeExpiredShowcaseFacts(today.minusDays(6));

        jdbcTemplate.update("INSERT IGNORE INTO organization(name, created_at) VALUES (?, ?)",
                SHOWCASE_ORGANIZATION_NAME, nowTimestamp);
        long organizationId = requireId("SELECT id FROM organization WHERE name = ?", SHOWCASE_ORGANIZATION_NAME);
        ensureShowcaseProvider(nowTimestamp);

        Map<SeedGrant, Long> grantUsage = new HashMap<>();
        Map<Long, Long> memberConsumed = new HashMap<>();
        Map<Long, Long> teamDeveloperConsumed = new HashMap<>();
        Map<Long, Long> projectConsumed = new HashMap<>();
        List<ShowcaseTeam> responseTeams = new ArrayList<>();
        int successfulRequests = 0;
        int rejectedRequests = 0;

        for (int teamIndex = 0; teamIndex < showcaseTeams().size(); teamIndex++) {
            ShowcaseTeamSpec spec = showcaseTeams().get(teamIndex);
            long teamId = ensureShowcaseTeam(organizationId, spec, nowTimestamp);
            List<ShowcaseMemberSeed> members = ensureShowcaseMembers(organizationId, teamId, spec, nowTimestamp);
            Map<String, SeedGrant> teamGrants = ensureTeamEntitlements(teamId, members.size(), nowTimestamp);
            Map<Long, Map<String, SeedGrant>> memberGrants = ensureMemberEntitlements(teamId, members, nowTimestamp);
            ShowcaseProjectSeed project = ensureShowcaseProject(organizationId, teamId, spec, nowTimestamp);
            SeedGrant applicationGrant = upsertShowcaseEntitlement(teamId, null, null, SHOWCASE_CHAT_MODEL,
                    "APPLICATION", 900_000L, nowTimestamp);
            SeedGrant projectGrant = upsertShowcaseEntitlement(teamId, null, project.projectId(), SHOWCASE_CHAT_MODEL,
                    "APPLICATION", 600_000L, nowTimestamp);

            for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
                ShowcaseMemberSeed member = members.get(memberIndex);
                for (int dayOffset = 6; dayOffset >= 0; dayOffset--) {
                    LocalDate day = today.minusDays(dayOffset);
                    ModelSpec model = (memberIndex + dayOffset + teamIndex) % 4 == 0
                            ? SHOWCASE_REASONING_MODEL : SHOWCASE_CHAT_MODEL;
                    int inputTokens = 1_200 + teamIndex * 180 + memberIndex * 90 + dayOffset * 35;
                    int outputTokens = 380 + teamIndex * 45 + memberIndex * 20 + dayOffset * 12;
                    OffsetDateTime occurredAt = day.atTime(9 + (memberIndex % 7), 15).atZone(SHOWCASE_ZONE).toOffsetDateTime();
                    String requestId = "showcase-v1-dev-" + spec.slug() + "-" + memberIndex + "-" + day;
                    writeShowcaseSuccess(requestId, organizationId, teamId, member.memberId(), member.keyId(), null, null,
                            "DEVELOPER", model, inputTokens, outputTokens, occurredAt, (memberIndex + dayOffset) % 3 == 0);
                    addGrantUsage(grantUsage, teamGrants.get(model.name()), model, day, today, weekStart, inputTokens + outputTokens);
                    addGrantUsage(grantUsage, memberGrants.get(member.memberId()).get(model.name()), model, day, today, weekStart, inputTokens + outputTokens);
                    memberConsumed.merge(member.memberId(), (long) inputTokens + outputTokens, Long::sum);
                    teamDeveloperConsumed.merge(teamId, (long) inputTokens + outputTokens, Long::sum);
                    successfulRequests++;
                }
            }

            for (int dayOffset = 6; dayOffset >= 0; dayOffset--) {
                LocalDate day = today.minusDays(dayOffset);
                int inputTokens = 2_400 + teamIndex * 240 + dayOffset * 60;
                int outputTokens = 720 + teamIndex * 80 + dayOffset * 20;
                OffsetDateTime occurredAt = day.atTime(17, 30).atZone(SHOWCASE_ZONE).toOffsetDateTime();
                String requestId = "showcase-v1-app-" + spec.slug() + "-" + day;
                writeShowcaseSuccess(requestId, organizationId, teamId, null, project.keyId(), project.projectId(), project.serviceAccountId(),
                        "APPLICATION", SHOWCASE_CHAT_MODEL, inputTokens, outputTokens, occurredAt, dayOffset % 2 == 0);
                addGrantUsage(grantUsage, applicationGrant, SHOWCASE_CHAT_MODEL, day, today, weekStart, inputTokens + outputTokens);
                addGrantUsage(grantUsage, projectGrant, SHOWCASE_CHAT_MODEL, day, today, weekStart, inputTokens + outputTokens);
                projectConsumed.merge(project.projectId(), (long) inputTokens + outputTokens, Long::sum);
                successfulRequests++;
            }

            for (int rejection = 0; rejection < 2; rejection++) {
                ShowcaseMemberSeed member = members.get(rejection + 1);
                String errorCode = rejection == 0 ? "RATE_LIMIT_EXCEEDED" : "CONCURRENCY_LIMIT_EXCEEDED";
                String dimension = rejection == 0 ? "KEY_RPM" : "GLOBAL_CONCURRENCY";
                String requestId = "showcase-v1-reject-" + spec.slug() + "-" + rejection + "-" + today;
                writeShowcaseRejection(requestId, organizationId, teamId, member.memberId(), member.keyId(),
                        SHOWCASE_CHAT_MODEL.name(), errorCode, dimension, today.atTime(18, 10 + rejection).atZone(SHOWCASE_ZONE).toOffsetDateTime());
                rejectedRequests++;
            }

            long memberQuota = SHOWCASE_CHAT_MODEL.memberQuota() + SHOWCASE_REASONING_MODEL.memberQuota();
            for (ShowcaseMemberSeed member : members) {
                long consumed = memberConsumed.getOrDefault(member.memberId(), 0L);
                upsertQuotaAccount("MEMBER_DEVELOPMENT", member.memberId(), Math.max(0, memberQuota - consumed), consumed, nowTimestamp);
            }
            long teamQuota = members.size() * memberQuota;
            long teamConsumed = teamDeveloperConsumed.getOrDefault(teamId, 0L);
            upsertQuotaAccount("TEAM_DEVELOPMENT", teamId, Math.max(0, teamQuota - teamConsumed), teamConsumed, nowTimestamp);
            long applicationConsumed = projectConsumed.getOrDefault(project.projectId(), 0L);
            upsertQuotaAccount("TEAM_APPLICATION", teamId, 300_000L, 0L, nowTimestamp);
            upsertQuotaAccount("PROJECT_APPLICATION", project.projectId(), Math.max(0, 600_000L - applicationConsumed), applicationConsumed, nowTimestamp);

            responseTeams.add(new ShowcaseTeam(teamId, spec.name(), members.size()));
        }

        for (Map.Entry<SeedGrant, Long> entry : grantUsage.entrySet()) {
            upsertGrantUsage(entry.getKey(), entry.getValue(), today, weekStart, nowTimestamp);
        }
        return new ShowcaseBootstrapResponse(organizationId, List.copyOf(responseTeams), 16, successfulRequests, rejectedRequests);
    }

    private void ensureShowcaseProvider(Timestamp now) {
        jdbcTemplate.update("INSERT IGNORE INTO provider(name, provider_type, enabled, created_at) VALUES (?, 'MOCK_OPENAI', 1, ?)",
                SHOWCASE_PROVIDER_NAME, now);
        long providerId = requireId("SELECT id FROM provider WHERE name = ?", SHOWCASE_PROVIDER_NAME);
        for (ModelSpec model : List.of(SHOWCASE_CHAT_MODEL, SHOWCASE_REASONING_MODEL)) {
            jdbcTemplate.update("""
                    INSERT INTO provider_model(provider_id, model_name, enabled, input_price_per_million, output_price_per_million, currency, created_at, updated_at)
                    VALUES (?, ?, 1, ?, ?, 'USD', ?, ?)
                    ON DUPLICATE KEY UPDATE provider_id = VALUES(provider_id), enabled = 1,
                      input_price_per_million = VALUES(input_price_per_million), output_price_per_million = VALUES(output_price_per_million),
                      currency = 'USD', updated_at = VALUES(updated_at)
                    """, providerId, model.name(), model.inputPrice(), model.outputPrice(), now, now);
        }
    }

    private void removeExpiredShowcaseFacts(LocalDate oldestDay) {
        Timestamp oldestTimestamp = JdbcTime.toTimestamp(oldestDay.atStartOfDay(SHOWCASE_ZONE).toOffsetDateTime());
        jdbcTemplate.update("DELETE FROM billing_record WHERE request_id LIKE 'showcase-v1-%' AND created_at < ?", oldestTimestamp);
        jdbcTemplate.update("DELETE FROM usage_record WHERE request_id LIKE 'showcase-v1-%' AND occurred_at < ?", oldestTimestamp);
        jdbcTemplate.update("DELETE FROM ai_request WHERE request_id LIKE 'showcase-v1-%' AND created_at < ?", oldestTimestamp);
    }

    private long ensureShowcaseTeam(long organizationId, ShowcaseTeamSpec spec, Timestamp now) {
        jdbcTemplate.update("""
                INSERT INTO team(organization_id, name, key_rpm, team_rpm, team_tpm, key_concurrency, team_concurrency, model_concurrency, enabled, status, created_at)
                VALUES (?, ?, 120, 1200, 240000, 10, 40, 80, 1, 'ACTIVE', ?)
                ON DUPLICATE KEY UPDATE key_rpm = VALUES(key_rpm), team_rpm = VALUES(team_rpm), team_tpm = VALUES(team_tpm),
                  key_concurrency = VALUES(key_concurrency), team_concurrency = VALUES(team_concurrency), model_concurrency = VALUES(model_concurrency),
                  enabled = 1, status = 'ACTIVE'
                """, organizationId, spec.name(), now);
        return requireId("SELECT id FROM team WHERE organization_id = ? AND name = ?", organizationId, spec.name());
    }

    private List<ShowcaseMemberSeed> ensureShowcaseMembers(long organizationId, long teamId, ShowcaseTeamSpec spec, Timestamp now) {
        List<ShowcaseMemberSeed> members = new ArrayList<>();
        for (int index = 0; index < spec.memberNames().size(); index++) {
            String name = spec.memberNames().get(index);
            String email = "showcase-" + spec.slug() + "-" + index + "@example.com";
            jdbcTemplate.update("""
                    INSERT INTO platform_user(name, email, enabled, created_at, updated_at)
                    VALUES (?, ?, 1, ?, ?)
                    ON DUPLICATE KEY UPDATE name = VALUES(name), enabled = 1, updated_at = VALUES(updated_at)
                    """, name, email, now, now);
            long userId = requireId("SELECT id FROM platform_user WHERE email = ?", email);
            String role = index == 0 ? "OWNER" : "MEMBER";
            jdbcTemplate.update("""
                    INSERT INTO team_member(organization_id, team_id, user_id, name, email, role, enabled, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                    ON DUPLICATE KEY UPDATE team_id = VALUES(team_id), name = VALUES(name), email = VALUES(email), role = VALUES(role), enabled = 1
                    """, organizationId, teamId, userId, name, email, role, now);
            long memberId = requireId("SELECT id FROM team_member WHERE user_id = ?", userId);
            long keyId = ensureShowcaseMemberKey(organizationId, teamId, memberId, spec.slug() + "-" + index, now);
            members.add(new ShowcaseMemberSeed(memberId, keyId));
            if (index == 0) jdbcTemplate.update("UPDATE team SET owner_user_id = ?, status = 'ACTIVE', enabled = 1 WHERE id = ?", userId, teamId);
        }
        return members;
    }

    private Map<String, SeedGrant> ensureTeamEntitlements(long teamId, int memberCount, Timestamp now) {
        Map<String, SeedGrant> grants = new HashMap<>();
        for (ModelSpec model : List.of(SHOWCASE_CHAT_MODEL, SHOWCASE_REASONING_MODEL)) {
            long limit = model.memberQuota() * memberCount;
            SeedGrant grant = upsertShowcaseEntitlement(teamId, null, null, model, "DEVELOPMENT", limit, now);
            grants.put(model.name(), grant);
            jdbcTemplate.update("INSERT IGNORE INTO team_direct_model_access(team_id, model_name, created_at) VALUES (?, ?, ?)", teamId, model.name(), now);
            jdbcTemplate.update("INSERT IGNORE INTO team_model_grant(team_id, model_name, expires_at, created_at) VALUES (?, ?, NULL, ?)", teamId, model.name(), now);
        }
        return grants;
    }

    private Map<Long, Map<String, SeedGrant>> ensureMemberEntitlements(long teamId, List<ShowcaseMemberSeed> members, Timestamp now) {
        Map<Long, Map<String, SeedGrant>> grants = new HashMap<>();
        for (ShowcaseMemberSeed member : members) {
            Map<String, SeedGrant> memberGrants = new HashMap<>();
            for (ModelSpec model : List.of(SHOWCASE_CHAT_MODEL, SHOWCASE_REASONING_MODEL)) {
                memberGrants.put(model.name(), upsertShowcaseEntitlement(teamId, member.memberId(), null, model,
                        "DEVELOPMENT", model.memberQuota(), now));
                jdbcTemplate.update("INSERT IGNORE INTO member_model_access(member_id, model_name, created_at) VALUES (?, ?, ?)",
                        member.memberId(), model.name(), now);
            }
            grants.put(member.memberId(), memberGrants);
        }
        return grants;
    }

    private ShowcaseProjectSeed ensureShowcaseProject(long organizationId, long teamId, ShowcaseTeamSpec spec, Timestamp now) {
        String projectName = spec.name() + " Assistant";
        String projectCode = "showcase-" + spec.slug();
        jdbcTemplate.update("""
                INSERT INTO project(organization_id, team_id, name, project_code, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?)
                ON DUPLICATE KEY UPDATE name = VALUES(name), enabled = 1, updated_at = VALUES(updated_at)
                """, organizationId, teamId, projectName, projectCode, now, now);
        long projectId = requireId("SELECT id FROM project WHERE team_id = ? AND project_code = ?", teamId, projectCode);
        String accountName = "showcase-service";
        jdbcTemplate.update("""
                INSERT INTO project_service_account(project_id, name, enabled, created_at, updated_at)
                VALUES (?, ?, 1, ?, ?)
                ON DUPLICATE KEY UPDATE enabled = 1, updated_at = VALUES(updated_at)
                """, projectId, accountName, now, now);
        long serviceAccountId = requireId("SELECT id FROM project_service_account WHERE project_id = ? AND name = ?", projectId, accountName);
        jdbcTemplate.update("INSERT IGNORE INTO project_model_access(project_id, model_name, created_at) VALUES (?, ?, ?)",
                projectId, SHOWCASE_CHAT_MODEL.name(), now);
        long keyId = ensureShowcaseApplicationKey(organizationId, teamId, projectId, serviceAccountId, spec.slug(), now);
        return new ShowcaseProjectSeed(projectId, serviceAccountId, keyId);
    }

    private SeedGrant upsertShowcaseEntitlement(long teamId, Long memberId, Long projectId, ModelSpec model,
                                                  String poolType, long quotaLimit, Timestamp now) {
        int updated = jdbcTemplate.update("""
                UPDATE model_entitlement_grant
                SET quota_mode = ?, quota_limit = ?, alert_remaining_percent = 80, status = 'ACTIVE', revoked_at = NULL
                WHERE team_id = ? AND member_id <=> ? AND project_id <=> ? AND model_name = ? AND pool_type = ?
                  AND status = 'ACTIVE' AND reason = ?
                """, model.quotaMode(), quotaLimit, teamId, memberId, projectId, model.name(), poolType, SHOWCASE_REASON);
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO model_entitlement_grant(team_id, member_id, project_id, model_name, pool_type, quota_mode, quota_limit,
                      alert_remaining_percent, status, reason, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 80, 'ACTIVE', ?, ?)
                    """, teamId, memberId, projectId, model.name(), poolType, model.quotaMode(), quotaLimit, SHOWCASE_REASON, now);
        }
        long grantId = requireId("""
                SELECT id FROM model_entitlement_grant
                WHERE team_id = ? AND member_id <=> ? AND project_id <=> ? AND model_name = ? AND pool_type = ?
                  AND status = 'ACTIVE' AND reason = ?
                ORDER BY id DESC LIMIT 1
                """, teamId, memberId, projectId, model.name(), poolType, SHOWCASE_REASON);
        return new SeedGrant(grantId, model.quotaMode());
    }

    private long ensureShowcaseMemberKey(long organizationId, long teamId, long memberId, String suffix, Timestamp now) {
        return ensureShowcaseKey("SELECT id FROM virtual_api_key WHERE owner_member_id = ? AND name = ? AND credential_type = 'DEVELOPER' ORDER BY id DESC LIMIT 1",
                new Object[] {memberId, "showcase-member-key-" + suffix}, () -> jdbcTemplate.update("""
                        INSERT INTO virtual_api_key(organization_id, team_id, owner_member_id, name, key_prefix, key_hash, key_kind, credential_type, enabled, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'STANDARD', 'DEVELOPER', 1, ?)
                        """, organizationId, teamId, memberId, "showcase-member-key-" + suffix, "mg-showcase-" + suffix,
                        discardedShowcaseKeyHash(), now));
    }

    private long ensureShowcaseApplicationKey(long organizationId, long teamId, long projectId, long serviceAccountId, String suffix, Timestamp now) {
        return ensureShowcaseKey("SELECT id FROM virtual_api_key WHERE service_account_id = ? AND name = ? AND credential_type = 'APPLICATION' ORDER BY id DESC LIMIT 1",
                new Object[] {serviceAccountId, "showcase-app-key-" + suffix}, () -> jdbcTemplate.update("""
                        INSERT INTO virtual_api_key(organization_id, team_id, owner_member_id, project_id, service_account_id, name, key_prefix, key_hash, key_kind, credential_type, enabled, created_at)
                        VALUES (?, ?, NULL, ?, ?, ?, ?, ?, 'STANDARD', 'APPLICATION', 1, ?)
                        """, organizationId, teamId, projectId, serviceAccountId, "showcase-app-key-" + suffix, "mg-showcase-app-" + suffix,
                        discardedShowcaseKeyHash(), now));
    }

    private long ensureShowcaseKey(String lookupSql, Object[] lookupArguments, Runnable create) {
        try {
            Long existing = jdbcTemplate.queryForObject(lookupSql, Long.class, lookupArguments);
            if (existing != null) return existing;
        } catch (EmptyResultDataAccessException ignored) {
            // The key is intentionally created only once; its plaintext material is discarded immediately below.
        }
        create.run();
        return requireId(lookupSql, lookupArguments);
    }

    private void writeShowcaseSuccess(String requestId, long organizationId, long teamId, Long memberId, long apiKeyId,
                                      Long projectId, Long serviceAccountId, String credentialType, ModelSpec model,
                                      int inputTokens, int outputTokens, OffsetDateTime occurredAt, boolean stream) {
        Timestamp timestamp = JdbcTime.toTimestamp(occurredAt);
        int totalTokens = inputTokens + outputTokens;
        jdbcTemplate.update("""
                INSERT INTO ai_request(request_id, organization_id, team_id, api_key_id, member_id, credential_type, project_id, service_account_id,
                  requested_model, actual_provider, actual_model, stream_enabled, input_tokens, output_tokens, estimated_tokens, status,
                  duration_ms, first_token_ms, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUCCESS', ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE input_tokens = VALUES(input_tokens), output_tokens = VALUES(output_tokens), estimated_tokens = VALUES(estimated_tokens),
                  status = 'SUCCESS', error_code = NULL, limit_dimension = NULL, duration_ms = VALUES(duration_ms), first_token_ms = VALUES(first_token_ms),
                  created_at = VALUES(created_at), completed_at = VALUES(completed_at)
                """, requestId, organizationId, teamId, apiKeyId, memberId, credentialType, projectId, serviceAccountId,
                model.name(), SHOWCASE_PROVIDER_NAME, model.name(), stream ? 1 : 0, inputTokens, outputTokens, totalTokens,
                stream ? 1_120L : 760L, stream ? 180L : null, timestamp, timestamp);
        String eventId = "showcase-v1-usage-" + requestId.substring("showcase-v1-".length());
        jdbcTemplate.update("""
                INSERT INTO usage_record(event_id, request_id, organization_id, team_id, api_key_id, member_id, credential_type, project_id,
                  service_account_id, provider, model, input_tokens, output_tokens, total_tokens, usage_source, status, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROVIDER', 'SUCCESS', ?)
                ON DUPLICATE KEY UPDATE input_tokens = VALUES(input_tokens), output_tokens = VALUES(output_tokens), total_tokens = VALUES(total_tokens),
                  status = 'SUCCESS', occurred_at = VALUES(occurred_at)
                """, eventId, requestId, organizationId, teamId, apiKeyId, memberId, credentialType, projectId, serviceAccountId,
                SHOWCASE_PROVIDER_NAME, model.name(), inputTokens, outputTokens, totalTokens, timestamp);
        BigDecimal amount = model.inputPrice().multiply(BigDecimal.valueOf(inputTokens)).movePointLeft(6)
                .add(model.outputPrice().multiply(BigDecimal.valueOf(outputTokens)).movePointLeft(6));
        jdbcTemplate.update("""
                INSERT INTO billing_record(request_id, organization_id, team_id, api_key_id, member_id, credential_type, project_id, service_account_id,
                  provider, model, input_tokens, output_tokens, unit_price, amount, currency, billing_type, created_at, input_unit_price, output_unit_price)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'USD', 'USAGE', ?, ?, ?)
                ON DUPLICATE KEY UPDATE input_tokens = VALUES(input_tokens), output_tokens = VALUES(output_tokens), unit_price = VALUES(unit_price),
                  amount = VALUES(amount), currency = 'USD', created_at = VALUES(created_at), input_unit_price = VALUES(input_unit_price),
                  output_unit_price = VALUES(output_unit_price)
                """, requestId, organizationId, teamId, apiKeyId, memberId, credentialType, projectId, serviceAccountId,
                SHOWCASE_PROVIDER_NAME, model.name(), inputTokens, outputTokens, model.inputPrice().add(model.outputPrice()), amount,
                timestamp, model.inputPrice(), model.outputPrice());
    }

    private void writeShowcaseRejection(String requestId, long organizationId, long teamId, long memberId, long apiKeyId,
                                        String modelName, String errorCode, String dimension, OffsetDateTime occurredAt) {
        Timestamp timestamp = JdbcTime.toTimestamp(occurredAt);
        jdbcTemplate.update("""
                INSERT INTO ai_request(request_id, organization_id, team_id, api_key_id, member_id, credential_type, requested_model,
                  stream_enabled, input_tokens, estimated_tokens, status, error_code, limit_dimension, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, 'DEVELOPER', ?, 0, 900, 1500, 'FAILED', ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE status = 'FAILED', error_code = VALUES(error_code), limit_dimension = VALUES(limit_dimension),
                  created_at = VALUES(created_at), completed_at = VALUES(completed_at)
                """, requestId, organizationId, teamId, apiKeyId, memberId, modelName, errorCode, dimension, timestamp, timestamp);
    }

    private void addGrantUsage(Map<SeedGrant, Long> usage, SeedGrant grant, ModelSpec model, LocalDate day,
                               LocalDate today, LocalDate weekStart, int tokens) {
        if (("DAILY".equals(model.quotaMode()) && day.equals(today))
                || ("WEEKLY".equals(model.quotaMode()) && !day.isBefore(weekStart))) {
            usage.merge(grant, (long) tokens, Long::sum);
        }
    }

    private void upsertGrantUsage(SeedGrant grant, long consumedTokens, LocalDate today, LocalDate weekStart, Timestamp now) {
        LocalDate cycleStart = "WEEKLY".equals(grant.quotaMode()) ? weekStart : today;
        jdbcTemplate.update("""
                INSERT INTO model_entitlement_usage(grant_id, cycle_started_at, consumed_tokens, frozen_tokens, version, updated_at)
                VALUES (?, ?, ?, 0, 0, ?)
                ON DUPLICATE KEY UPDATE consumed_tokens = VALUES(consumed_tokens), frozen_tokens = 0,
                  version = version + 1, updated_at = VALUES(updated_at)
                """, grant.id(), JdbcTime.toTimestamp(cycleStart.atStartOfDay(SHOWCASE_ZONE).toOffsetDateTime()), consumedTokens, now);
    }

    private void upsertQuotaAccount(String accountType, long ownerId, long availableTokens, long consumedTokens, Timestamp now) {
        jdbcTemplate.update("""
                INSERT INTO quota_account(account_type, owner_id, available_tokens, frozen_tokens, consumed_tokens, version, updated_at)
                VALUES (?, ?, ?, 0, ?, 0, ?)
                ON DUPLICATE KEY UPDATE available_tokens = VALUES(available_tokens), frozen_tokens = 0,
                  consumed_tokens = VALUES(consumed_tokens), version = version + 1, updated_at = VALUES(updated_at)
                """, accountType, ownerId, availableTokens, consumedTokens, now);
    }

    private static String discardedShowcaseKeyHash() {
        byte[] rawMaterial = new byte[32];
        SHOWCASE_RANDOM.nextBytes(rawMaterial);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawMaterial);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 must be available for showcase key hashing.", ex);
        }
    }

    private static List<ShowcaseTeamSpec> showcaseTeams() {
        return List.of(
                new ShowcaseTeamSpec("ai-engineering", "Showcase AI Engineering", List.of("Maya Chen", "Noah Wang", "Lina Zhao", "Ethan Liu", "Iris Sun", "Owen Hu")),
                new ShowcaseTeamSpec("product-intelligence", "Showcase Product Intelligence", List.of("Sofia Li", "Leo Zhang", "Mia Gao", "Ryan Xu", "Ella Wu")),
                new ShowcaseTeamSpec("customer-experience", "Showcase Customer Experience", List.of("Kai Zhou", "Nora He", "Theo Lin", "Ava Qian", "Finn Gu"))
        );
    }

    public DemoIdentityResponse demoIdentities() {
        List<DemoMember> members = jdbcTemplate.query("""
                        SELECT t.id AS team_id, t.name AS team_name,
                               m.id AS member_id, m.name AS member_name, m.email, m.role
                        FROM organization o
                        JOIN team t ON t.organization_id = o.id
                        JOIN team_member m ON m.team_id = t.id
                        WHERE o.name = ? AND t.name = ? AND m.email IN (?, ?)
                        ORDER BY m.id ASC
                        """,
                (rs, rowNum) -> new DemoMember(
                        rs.getLong("team_id"),
                        rs.getString("team_name"),
                        rs.getLong("member_id"),
                        rs.getString("member_name"),
                        rs.getString("email"),
                        rs.getString("role")),
                DEMO_ORGANIZATION_NAME, DEMO_TEAM_NAME, DEMO_OWNER_EMAIL, DEMO_DEVELOPER_EMAIL);

        DemoMember owner = members.stream().filter(member -> DEMO_OWNER_EMAIL.equals(member.email())).findFirst().orElse(null);
        DemoMember developer = members.stream().filter(member -> DEMO_DEVELOPER_EMAIL.equals(member.email())).findFirst().orElse(null);
        if (owner == null || developer == null) {
            return new DemoIdentityResponse(false, List.of());
        }

        return new DemoIdentityResponse(true, List.of(
                new DemoIdentity("platform-admin", "Demo Platform Admin", "platform-admin", null, null, null),
                new DemoIdentity("demo-team-owner", owner.name(), "team-admin", owner.teamId(), owner.teamName(), owner.memberId()),
                new DemoIdentity("demo-developer", developer.name(), "developer", developer.teamId(), developer.teamName(), developer.memberId())
        ));
    }

    public long insertSystemMemberApiKey(long teamId, long ownerMemberId, String keyPrefix, String keyHash) {
        MemberKeyScope scope = findMemberKeyScope(teamId, ownerMemberId);
        return GeneratedKeys.insert(jdbcTemplate, """
                INSERT INTO virtual_api_key(
                    organization_id, team_id, owner_member_id, name, key_prefix, key_hash, enabled, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                """, scope.organizationId(), scope.teamId(), ownerMemberId,
                "member-" + ownerMemberId, keyPrefix, keyHash, JdbcTime.toTimestamp(OffsetDateTime.now()));
    }

    public Optional<Long> findActiveMemberKeyId(long teamId, long memberId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT id FROM virtual_api_key WHERE team_id = ? AND owner_member_id = ? AND enabled = 1 AND key_kind = 'STANDARD'
                    ORDER BY id DESC LIMIT 1
                    """, Long.class, teamId, memberId));
        } catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }

    public List<String> findKeyHashesByTeam(long teamId) {
        return jdbcTemplate.queryForList("SELECT key_hash FROM virtual_api_key WHERE team_id = ?", String.class, teamId);
    }

    public List<String> findKeyHashesByMember(long memberId) {
        return jdbcTemplate.queryForList("SELECT key_hash FROM virtual_api_key WHERE owner_member_id = ?", String.class, memberId);
    }

    @Transactional
    public TeamSummary createTeam(CreateTeamRequest request) {
        OffsetDateTime now = OffsetDateTime.now();
        try {
            long teamId = GeneratedKeys.insert(jdbcTemplate, """
                            INSERT INTO team(
                                organization_id, name, key_rpm, team_rpm, team_tpm, key_concurrency, team_concurrency, model_concurrency, enabled, created_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    request.organizationId(),
                    request.name(),
                    defaultInt(request.keyRpm(), 60),
                    defaultInt(request.teamRpm(), 600),
                    defaultInt(request.teamTpm(), 120000),
                    defaultInt(request.keyConcurrency(), 5),
                    defaultInt(request.teamConcurrency(), 20),
                    defaultInt(request.modelConcurrency(), 50),
                    1,
                    JdbcTime.toTimestamp(now));
            jdbcTemplate.update("""
                            INSERT INTO quota_account(account_type, owner_id, available_tokens, frozen_tokens, consumed_tokens, version, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    "TEAM",
                    teamId,
                    0L,
                    0L,
                    0L,
                    0L,
                    JdbcTime.toTimestamp(now));
            jdbcTemplate.update("UPDATE team SET status = ? WHERE id = ?", request.ownerUserId() == null ? "DRAFT" : "READY_FOR_REQUEST", teamId);
            if (request.ownerUserId() != null) setTeamOwner(teamId, request.ownerUserId());

            return findTeamSummary(teamId).orElseThrow(() -> new IllegalStateException("Created team was not found."));
        } catch (DuplicateKeyException ex) {
            throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Team name already exists.");
        }
    }

    @Transactional
    public TeamSummary setTeamOwner(long teamId, Long ownerUserId) {
        long organizationId = findTeamOrganizationId(teamId);
        if (ownerUserId == null) {
            jdbcTemplate.update("UPDATE team SET owner_user_id = NULL, status = 'DRAFT' WHERE id = ?", teamId);
            jdbcTemplate.update("UPDATE team_member SET role = 'MEMBER' WHERE team_id = ? AND role = 'OWNER'", teamId);
            return findTeamSummary(teamId).orElseThrow(() -> new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Team was not found."));
        }
        try {
            UserRecord user = jdbcTemplate.queryForObject("SELECT id, name, email FROM platform_user WHERE id = ? AND enabled = 1",
                    (rs, row) -> new UserRecord(rs.getLong("id"), rs.getString("name"), rs.getString("email")), ownerUserId);
            Long currentTeamId;
            try {
                currentTeamId = jdbcTemplate.queryForObject("SELECT team_id FROM team_member WHERE user_id = ? AND enabled = 1", Long.class, ownerUserId);
            } catch (EmptyResultDataAccessException ignored) {
                currentTeamId = null;
            }
            if (currentTeamId != null && currentTeamId != teamId) throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Transfer the user before assigning them as this team's owner.");
            jdbcTemplate.update("UPDATE team_member SET role = 'MEMBER' WHERE team_id = ? AND role = 'OWNER'", teamId);
            if (currentTeamId == null) {
                Long historicalMemberId;
                try {
                    historicalMemberId = jdbcTemplate.queryForObject("SELECT id FROM team_member WHERE user_id = ?", Long.class, ownerUserId);
                } catch (EmptyResultDataAccessException ignored) {
                    historicalMemberId = null;
                }
                if (historicalMemberId == null) {
                    jdbcTemplate.update("""
                            INSERT INTO team_member(organization_id, team_id, user_id, name, email, role, enabled, created_at)
                            VALUES (?, ?, ?, ?, ?, 'OWNER', 1, ?)
                            """, organizationId, teamId, user.userId(), user.name(), user.email(), JdbcTime.toTimestamp(OffsetDateTime.now()));
                } else {
                    jdbcTemplate.update("""
                            UPDATE team_member
                            SET organization_id = ?, team_id = ?, name = ?, email = ?, role = 'OWNER', enabled = 1
                            WHERE id = ?
                            """, organizationId, teamId, user.name(), user.email(), historicalMemberId);
                }
            } else {
                jdbcTemplate.update("UPDATE team_member SET role = 'OWNER', enabled = 1 WHERE user_id = ?", ownerUserId);
            }
            jdbcTemplate.update("INSERT IGNORE INTO quota_account(account_type, owner_id, available_tokens, frozen_tokens, consumed_tokens, version, updated_at) SELECT 'MEMBER', id, 0, 0, 0, 0, ? FROM team_member WHERE user_id = ?", JdbcTime.toTimestamp(OffsetDateTime.now()), ownerUserId);
            jdbcTemplate.update("UPDATE team SET owner_user_id = ?, status = CASE WHEN status IN ('SUSPENDED', 'ACTIVE') THEN status ELSE 'READY_FOR_REQUEST' END WHERE id = ?", ownerUserId, teamId);
            return findTeamSummary(teamId).orElseThrow(() -> new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Team was not found."));
        } catch (EmptyResultDataAccessException ex) {
            throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Owner user was not found or is disabled.");
        }
    }

    public TeamListResponse listTeams(String keyword, Boolean enabled, String status, String logicalModel, Long ownerUserId, Boolean ownerAssigned, int page, int size) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + keyword.trim() + "%";
            where.append("""
                     AND (t.name LIKE ? OR CAST(t.id AS CHAR) LIKE ? OR EXISTS (
                         SELECT 1 FROM team_member owner
                         WHERE owner.team_id = t.id AND owner.role = 'OWNER' AND owner.enabled = 1
                           AND (owner.name LIKE ? OR owner.email LIKE ?)
                     ))
                    """);
            args.add(pattern); args.add(pattern); args.add(pattern); args.add(pattern);
        }
        if (enabled != null) { where.append(" AND t.enabled = ?"); args.add(enabled ? 1 : 0); }
        if (status != null && !status.isBlank()) {
            String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
            if (!TEAM_STATUSES.contains(normalizedStatus)) {
                throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Unsupported team status filter.");
            }
            where.append(" AND t.status = ?"); args.add(normalizedStatus);
        }
        if (logicalModel != null && !logicalModel.isBlank()) { where.append(" AND EXISTS (SELECT 1 FROM team_direct_model_access ma WHERE ma.team_id = t.id AND ma.model_name = ?)"); args.add(logicalModel); }
        if (ownerUserId != null) { where.append(" AND t.owner_user_id = ?"); args.add(ownerUserId); }
        if (ownerAssigned != null) { where.append(ownerAssigned ? " AND t.owner_user_id IS NOT NULL" : " AND t.owner_user_id IS NULL"); }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM team t " + where, Long.class, args.toArray());
        List<Object> pageArgs = new java.util.ArrayList<>(args); pageArgs.add(size); pageArgs.add(page * size);
        List<TeamSummary> items = jdbcTemplate.query(teamSummarySql(where.toString()) + " LIMIT ? OFFSET ?",
                (rs, rowNum) -> mapTeamSummary(rs), pageArgs.toArray());
        return new TeamListResponse(items, page, size, total == null ? 0L : total);
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
        TeamSummary current = findTeamSummary(teamId)
                .orElseThrow(() -> new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Team was not found."));
        if ("DISSOLVED".equals(current.status())) throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Team has been dissolved and cannot be updated.");
        jdbcTemplate.update("""
                        UPDATE team
                        SET name = COALESCE(NULLIF(?, ''), name),
                            key_rpm = COALESCE(?, key_rpm),
                            team_rpm = COALESCE(?, team_rpm),
                            team_tpm = COALESCE(?, team_tpm),
                            key_concurrency = COALESCE(?, key_concurrency),
                            team_concurrency = COALESCE(?, team_concurrency),
                            model_concurrency = COALESCE(?, model_concurrency),
                            enabled = COALESCE(?, enabled)
                        WHERE id = ? AND status <> 'DISSOLVED'
                        """,
                request.name(),
                request.keyRpm(),
                request.teamRpm(),
                request.teamTpm(),
                request.keyConcurrency(),
                request.teamConcurrency(),
                request.modelConcurrency(),
                request.enabled() == null ? null : (request.enabled() ? 1 : 0),
                teamId);
        if (Boolean.FALSE.equals(request.enabled())) {
            jdbcTemplate.update("UPDATE team SET status = 'SUSPENDED' WHERE id = ?", teamId);
        } else if (Boolean.TRUE.equals(request.enabled())) {
            jdbcTemplate.update("""
                    UPDATE team SET status = CASE WHEN owner_user_id IS NULL THEN 'DRAFT' ELSE 'READY_FOR_REQUEST' END
                    WHERE id = ? AND status = 'SUSPENDED'
                    """, teamId);
        }
        return findTeamSummary(teamId)
                .orElseThrow(() -> new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Team was not found."));
    }

    public TeamMemberListResponse listTeamMembers(long teamId) {
        List<TeamMemberItem> items = jdbcTemplate.query("""
                        SELECT id, organization_id, team_id, name, email, role, enabled, created_at
                        FROM team_member
                        WHERE team_id = ?
                        ORDER BY enabled DESC, role DESC, id ASC
                        """,
                (rs, rowNum) -> mapTeamMember(rs),
                teamId);
        return new TeamMemberListResponse(items);
    }

    /** Enabled users without an active membership. A disabled former membership may be safely reactivated. */
    public TeamMemberCandidateListResponse listTeamMemberCandidates(long teamId) {
        List<TeamMemberCandidate> items = jdbcTemplate.query("""
                        SELECT u.id user_id, u.name, u.email, previous_member.id previous_member_id,
                               previous_team.name previous_team_name
                        FROM platform_user u
                        LEFT JOIN team_member previous_member ON previous_member.user_id = u.id
                        LEFT JOIN team previous_team ON previous_team.id = previous_member.team_id
                        WHERE u.enabled = 1
                          AND NOT EXISTS (
                              SELECT 1 FROM team_member active_member
                              WHERE active_member.user_id = u.id AND active_member.enabled = 1
                          )
                        ORDER BY u.name, u.id
                        """, (rs, rowNum) -> new TeamMemberCandidate(
                rs.getLong("user_id"), rs.getString("name"), rs.getString("email"),
                rs.getString("previous_team_name"), rs.getObject("previous_member_id") != null));
        return new TeamMemberCandidateListResponse(items);
    }

    public TeamMemberItem createTeamMember(long teamId, CreateTeamMemberRequest request) {
        long organizationId = findTeamOrganizationId(teamId);
        try {
            long userId = GeneratedKeys.insert(jdbcTemplate, """
                            INSERT INTO platform_user(name, email, enabled, created_at, updated_at)
                            VALUES (?, ?, 1, ?, ?)
                            """, request.name(), request.email().trim().toLowerCase(), JdbcTime.toTimestamp(OffsetDateTime.now()), JdbcTime.toTimestamp(OffsetDateTime.now()));
            long memberId = GeneratedKeys.insert(jdbcTemplate, """
                            INSERT INTO team_member(organization_id, team_id, user_id, name, email, role, enabled, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    organizationId,
                    teamId,
                    userId,
                    request.name(),
                    request.email().trim().toLowerCase(),
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
            String credentialType = jdbcTemplate.queryForObject("SELECT credential_type FROM virtual_api_key WHERE key_hash = ?", String.class, keyHash);
            if (CredentialType.APPLICATION.name().equals(credentialType)) {
                return applicationApiKeyContext(keyHash);
            }
            ApiKeyContext base = jdbcTemplate.queryForObject("""
                            SELECT
                                k.id key_id,
                                k.organization_id,
                                k.team_id,
                                k.owner_member_id,
                                k.credential_type,
                                k.project_id,
                                k.service_account_id,
                                q.id quota_account_id,
                                COALESCE((SELECT GROUP_CONCAT(mma.model_name ORDER BY mma.model_name SEPARATOR ',')
                                          FROM member_model_access mma
                                          JOIN team_direct_model_access tma ON tma.team_id = k.team_id AND tma.model_name = mma.model_name
                                          JOIN team_model_grant tmg ON tmg.team_id = k.team_id AND tmg.model_name = mma.model_name
                                          WHERE mma.member_id = k.owner_member_id AND (tmg.expires_at IS NULL OR tmg.expires_at > NOW())), '') allowed_models,
                                CASE WHEN k.enabled = 1 AND t.enabled = 1 AND t.status = 'ACTIVE'
                                          AND m.enabled = 1 AND u.enabled = 1 THEN 1 ELSE 0 END enabled,
                                k.expires_at,
                                t.key_rpm,
                                t.team_rpm,
                                t.team_tpm,
                                t.key_concurrency,
                                t.team_concurrency,
                                t.model_concurrency,
                                COALESCE(q.available_tokens, 0) available_tokens
                            FROM virtual_api_key k
                            JOIN team t ON t.id = k.team_id
                            JOIN team_member m ON m.id = k.owner_member_id
                            JOIN platform_user u ON u.id = m.user_id
                            LEFT JOIN quota_account q ON q.account_type = 'MEMBER_DEVELOPMENT' AND q.owner_id = m.id
                            WHERE k.key_hash = ?
                            """,
                    (rs, rowNum) -> new ApiKeyContext(
                            rs.getLong("key_id"),
                            rs.getLong("organization_id"),
                            rs.getLong("team_id"),
                            nullableLong(rs.getObject("owner_member_id")),
                            rs.getLong("quota_account_id"),
                            Set.of(),
                            Map.of(),
                            Map.of(),
                            new RateLimitPolicy(
                                    rs.getInt("key_rpm"),
                                    rs.getInt("team_rpm"),
                                    rs.getInt("team_tpm"),
                                    rs.getInt("key_concurrency"),
                                    rs.getInt("team_concurrency"),
                                    rs.getInt("model_concurrency")),
                            new BudgetPolicy(rs.getLong("available_tokens")),
                            rs.getInt("enabled") == 1,
                            JdbcTime.toOffsetDateTime(rs.getTimestamp("expires_at")),
                            CredentialType.valueOf(rs.getString("credential_type")),
                            nullableLong(rs.getObject("project_id")),
                            nullableLong(rs.getObject("service_account_id"))
                    ), keyHash);
            Map<String, ModelQuotaPolicy> teamPolicies = modelPolicies(base.teamId(), null);
            Map<String, ModelQuotaPolicy> memberPolicies = modelPolicies(base.teamId(), base.memberId());
            memberPolicies.keySet().removeIf(model -> !teamPolicies.containsKey(model));
            return Optional.of(new ApiKeyContext(base.keyId(), base.organizationId(), base.teamId(), base.memberId(), base.quotaAccountId(),
                    Set.copyOf(memberPolicies.keySet()), teamPolicies, memberPolicies, base.rateLimitPolicy(), base.budgetPolicy(), base.enabled(), base.expiresAt(),
                    base.credentialType(), base.projectId(), base.serviceAccountId()));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private Optional<ApiKeyContext> applicationApiKeyContext(String keyHash) {
        try {
            ApiKeyContext base = jdbcTemplate.queryForObject("""
                    SELECT k.id key_id,k.organization_id,k.team_id,k.project_id,k.service_account_id,
                      q.id quota_account_id,k.enabled,k.expires_at,t.key_rpm,t.team_rpm,t.team_tpm,t.key_concurrency,t.team_concurrency,t.model_concurrency,
                      COALESCE(q.available_tokens,0) available_tokens
                    FROM virtual_api_key k JOIN project p ON p.id=k.project_id AND p.team_id=k.team_id AND p.enabled=1
                      JOIN project_service_account sa ON sa.id=k.service_account_id AND sa.project_id=p.id AND sa.enabled=1
                      JOIN team t ON t.id=k.team_id AND t.enabled=1 AND t.status='ACTIVE'
                      LEFT JOIN quota_account q ON q.account_type='PROJECT_APPLICATION' AND q.owner_id=p.id
                    WHERE k.key_hash=? AND k.credential_type='APPLICATION'
                    """,(rs,row)->new ApiKeyContext(rs.getLong("key_id"),rs.getLong("organization_id"),rs.getLong("team_id"),null,
                    rs.getLong("quota_account_id"),Set.of(),Map.of(),Map.of(),new RateLimitPolicy(rs.getInt("key_rpm"),rs.getInt("team_rpm"),rs.getInt("team_tpm"),rs.getInt("key_concurrency"),rs.getInt("team_concurrency"),rs.getInt("model_concurrency")),
                    new BudgetPolicy(rs.getLong("available_tokens")),rs.getInt("enabled")==1,JdbcTime.toOffsetDateTime(rs.getTimestamp("expires_at")),CredentialType.APPLICATION,
                    rs.getLong("project_id"),rs.getLong("service_account_id")),keyHash);
            Map<String, ModelQuotaPolicy> team = modelPolicies(base.teamId(), null, "APPLICATION", null);
            Map<String, ModelQuotaPolicy> project = modelPolicies(base.teamId(), null, "APPLICATION", base.projectId());
            project.keySet().removeIf(model -> !team.containsKey(model));
            return Optional.of(new ApiKeyContext(base.keyId(),base.organizationId(),base.teamId(),null,base.quotaAccountId(),Set.copyOf(project.keySet()),team,project,base.rateLimitPolicy(),base.budgetPolicy(),base.enabled(),base.expiresAt(),CredentialType.APPLICATION,base.projectId(),base.serviceAccountId()));
        } catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }

    private Map<String, ModelQuotaPolicy> modelPolicies(long teamId, Long memberId) { return modelPolicies(teamId, memberId, "DEVELOPMENT", null); }
    private Map<String, ModelQuotaPolicy> modelPolicies(long teamId, Long memberId, String poolType, Long projectId) {
        Map<String, ModelQuotaPolicy> result = new LinkedHashMap<>();
        String sql; Object[] args;
        if (projectId != null) { sql="SELECT id,model_name,quota_mode,quota_limit,alert_remaining_percent FROM model_entitlement_grant WHERE team_id=? AND project_id=? AND pool_type=? AND status='ACTIVE'"; args=new Object[]{teamId,projectId,poolType}; }
        else if (memberId == null) { sql="SELECT id,model_name,quota_mode,quota_limit,alert_remaining_percent FROM model_entitlement_grant WHERE team_id=? AND member_id IS NULL AND project_id IS NULL AND pool_type=? AND status='ACTIVE'"; args=new Object[]{teamId,poolType}; }
        else { sql="SELECT id,model_name,quota_mode,quota_limit,alert_remaining_percent FROM model_entitlement_grant WHERE team_id=? AND member_id=? AND pool_type=? AND status='ACTIVE'"; args=new Object[]{teamId,memberId,poolType}; }
        jdbcTemplate.query(sql, (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getString("model_name"), new ModelQuotaPolicy(rs.getLong("id"), rs.getString("model_name"),
                QuotaMode.valueOf(rs.getString("quota_mode")), nullableLong(rs.getObject("quota_limit")), nullableInt(rs.getObject("alert_remaining_percent")))), args);
        return result;
    }

    private String teamSummarySql(String whereClause) {
        return """
                SELECT
                    t.id team_id,
                    t.organization_id,
                    t.name,
                    t.status,
                    t.enabled,
                    t.key_rpm,
                    t.team_rpm,
                    t.team_tpm,
                    t.key_concurrency,
                    t.team_concurrency,
                    t.model_concurrency,
                    t.owner_user_id,
                    (SELECT m.id FROM team_member m WHERE m.team_id = t.id AND m.role = 'OWNER' AND m.enabled = 1 ORDER BY m.id ASC LIMIT 1) owner_member_id,
                    (SELECT m.name FROM team_member m WHERE m.team_id = t.id AND m.role = 'OWNER' AND m.enabled = 1 ORDER BY m.id ASC LIMIT 1) owner_name,
                    (SELECT m.email FROM team_member m WHERE m.team_id = t.id AND m.role = 'OWNER' AND m.enabled = 1 ORDER BY m.id ASC LIMIT 1) owner_email,
                    (SELECT COUNT(*) FROM team_member m WHERE m.team_id = t.id AND m.enabled = 1) member_count,
                    (SELECT COUNT(*) FROM virtual_api_key k WHERE k.team_id = t.id AND k.enabled = 1) key_count
                FROM team t
                """ + whereClause + " ORDER BY t.id DESC";
    }

    private TeamSummary mapTeamSummary(ResultSet rs) throws SQLException {
        return new TeamSummary(
                rs.getLong("team_id"),
                rs.getLong("organization_id"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getInt("enabled") == 1,
                rs.getInt("key_rpm"),
                rs.getInt("team_rpm"),
                rs.getInt("team_tpm"),
                rs.getInt("key_concurrency"),
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

    private MemberKeyScope findMemberKeyScope(long teamId, long ownerMemberId) {
        try {
            return jdbcTemplate.queryForObject("""
                            SELECT m.organization_id, m.team_id
                            FROM team_member m
                            WHERE m.team_id = ? AND m.id = ? AND m.enabled = 1
                            """,
                    (rs, rowNum) -> new MemberKeyScope(
                            rs.getLong("organization_id"),
                            rs.getLong("team_id")), teamId, ownerMemberId);
        } catch (EmptyResultDataAccessException ex) {
            throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Member was not found for this team.");
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
                    "SELECT organization_id FROM team WHERE id = ? AND enabled = 1",
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

    private static Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private record MemberKeyScope(long organizationId, long teamId) {
    }

    private record DemoMember(long teamId, String teamName, long memberId, String name, String email, String role) {
    }

    private record UserRecord(long userId, String name, String email) {
    }

    private record ModelSpec(String name, String quotaMode, long memberQuota, BigDecimal inputPrice, BigDecimal outputPrice) {
    }

    private record ShowcaseTeamSpec(String slug, String name, List<String> memberNames) {
    }

    private record ShowcaseMemberSeed(long memberId, long keyId) {
    }

    private record ShowcaseProjectSeed(long projectId, long serviceAccountId, long keyId) {
    }

    private record SeedGrant(long id, String quotaMode) {
    }
}
