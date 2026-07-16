package com.modelgate.bootstrap;

import com.modelgate.auth.VirtualKeyService;
import com.modelgate.auth.ProviderCredentialCipher;
import com.modelgate.common.api.AdminDtos.DemoIdentity;
import com.modelgate.common.api.AdminDtos.DemoIdentityResponse;
import com.modelgate.common.api.AdminDtos.CreateTeamRequest;
import com.modelgate.common.api.AdminDtos.BillingQuery;
import com.modelgate.common.api.AdminDtos.GrantMemberAccessRequest;
import com.modelgate.common.api.AdminDtos.ModelEntitlementItem;
import com.modelgate.common.api.AdminDtos.UpsertModelEntitlementRequest;
import com.modelgate.common.api.AdminDtos.UserItem;
import com.modelgate.common.chat.ChatCompletionRequest;
import com.modelgate.common.chat.ChatMessage;
import com.modelgate.common.chat.MockBehavior;
import com.modelgate.common.domain.ApiKeyContext;
import com.modelgate.common.domain.CredentialType;
import com.modelgate.common.domain.BudgetPolicy;
import com.modelgate.common.domain.EntitlementQuotaLimits;
import com.modelgate.common.domain.QuotaMode;
import com.modelgate.infrastructure.db.ModelEntitlementRepository;
import com.modelgate.infrastructure.db.ProjectRepository;
import com.modelgate.infrastructure.db.TeamEntitlementRepository;
import com.modelgate.common.domain.RateLimitPolicy;
import com.modelgate.common.event.UsageReportedEvent;
import com.modelgate.common.event.QuotaSettlementSnapshot;
import com.modelgate.common.event.UsageCompletedEvent;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.provider.ProviderRequest;
import com.modelgate.provider.mock.MockProvider;
import com.modelgate.quota.TokenEstimator;
import com.modelgate.usage.NoopUsageEventPublisher;
import com.modelgate.usage.RocketMqUsageEventPublisher;
import com.modelgate.usage.UsageEventPublisher;
import com.modelgate.infrastructure.db.AdminRepository;
import com.modelgate.infrastructure.db.AdminControlRepository;
import com.modelgate.infrastructure.db.BillingRepository;
import com.modelgate.infrastructure.db.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MvpUnitTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    NoopUsageEventPublisher.class);

    @Test
    void sha256HashIsStableAndDoesNotRevealKey() {
        String hash = VirtualKeyService.sha256("mg-key-example-value");

        assertThat(hash).hasSize(64);
        assertThat(hash).isEqualTo(VirtualKeyService.sha256("mg-key-example-value"));
        assertThat(hash).doesNotContain("mg-key-example-value");
    }

    @Test
    void providerCredentialCipherKeepsPlaintextOutOfStoredValue() {
        String masterKey = Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
        ProviderCredentialCipher cipher = new ProviderCredentialCipher(masterKey);

        ProviderCredentialCipher.EncryptedCredential encrypted = cipher.encrypt("provider-api-key-placeholder");

        assertThat(encrypted.ciphertext()).doesNotContain("provider-api-key-placeholder");
        assertThat(encrypted.lastFour()).isEqualTo("lder");
        assertThat(cipher.decrypt(encrypted.ciphertext(), encrypted.version())).isEqualTo("provider-api-key-placeholder");
    }

    @Test
    void demoIdentityContractDistinguishesUninitializedAndInitializedStates() {
        DemoIdentityResponse before = new DemoIdentityResponse(false, List.of());
        DemoIdentityResponse after = new DemoIdentityResponse(true, List.of(
                new DemoIdentity("platform-admin", "Demo Platform Admin", "platform-admin", null, null, null),
                new DemoIdentity("demo-team-owner", "Demo Owner", "team-admin", 1L, "Demo Team", 10L),
                new DemoIdentity("demo-developer", "Demo Developer", "developer", 1L, "Demo Team", 11L)));

        assertThat(before.initialized()).isFalse();
        assertThat(before.identities()).isEmpty();
        assertThat(after.initialized()).isTrue();
        assertThat(after.identities()).hasSize(3);
        assertThat(after.identities().get(0).memberId()).isNull();
        assertThat(after.identities().get(2).memberId()).isEqualTo(11L);
    }

    @Test
    void demoBootstrapAddsOwnerAndDeveloperWithIdempotentInserts() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        AdminRepository repository = new AdminRepository(jdbcTemplate);

        repository.bootstrapDemo();
        repository.bootstrapDemo();

        List<SqlCall> memberInserts = jdbcTemplate.updates.stream()
                .filter(call -> call.sql().contains("INSERT IGNORE INTO team_member"))
                .toList();
        assertThat(memberInserts).hasSize(4);
        assertThat(memberInserts).allMatch(call -> call.sql().contains("INSERT IGNORE"));
        assertThat(memberInserts).allMatch(call -> call.sql().contains("user_id"));
        assertThat(memberInserts).anyMatch(call -> "Demo Owner".equals(call.args()[3]) && "OWNER".equals(call.args()[5]));
        assertThat(memberInserts).anyMatch(call -> "Demo Developer".equals(call.args()[3]) && "MEMBER".equals(call.args()[5]));
    }

    @Test
    void deletingUserCascadesMemberDataAndReturnsKeyHashesForInvalidation() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        UserRepository repository = new UserRepository(jdbcTemplate);

        UserRepository.DeletedUser deletedUser = repository.delete(4L);

        assertThat(deletedUser.apiKeyHashes()).containsExactly("key-hash-1");
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().contains("DELETE FROM virtual_api_key WHERE owner_member_id"));
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().contains("DELETE FROM billing_record WHERE member_id"));
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().contains("DELETE FROM team_member WHERE id"));
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().contains("DELETE FROM platform_user WHERE id"));
    }

    @Test
    void deletingTeamCascadesTeamDataButPreservesPlatformUsers() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        UserRepository repository = new UserRepository(jdbcTemplate);

        UserRepository.DeletedTeam deletedTeam = repository.deleteTeam(2L);

        assertThat(deletedTeam.apiKeyHashes()).containsExactly("key-hash-1");
        assertThat(deletedTeam.quotaAccountIds()).containsExactly(7L);
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().contains("DELETE FROM virtual_api_key WHERE team_id"));
        assertThat(jdbcTemplate.updates).noneMatch(call -> call.sql().contains("DELETE FROM application WHERE team_id"));
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().contains("DELETE FROM team_member WHERE team_id"));
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().contains("DELETE FROM team WHERE id"));
        assertThat(jdbcTemplate.updates).noneMatch(call -> call.sql().contains("DELETE FROM platform_user"));
    }

    @Test
    void teamDirectoryFiltersByKeywordStatusOwnerAndPaginates() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        AdminRepository repository = new AdminRepository(jdbcTemplate);

        repository.listTeams("demo", true, "ACTIVE", null, 8L, true, 2, 20);

        assertThat(jdbcTemplate.queries).anyMatch(sql -> sql.contains("CAST(t.id AS CHAR)")
                && sql.contains("owner.name LIKE ?")
                && sql.contains("t.status = ?")
                && sql.contains("t.owner_user_id = ?"));
        assertThat(jdbcTemplate.queries).anyMatch(sql -> sql.contains("LIMIT ? OFFSET ?"));
    }

    @Test
    void teamDirectoryRejectsUnknownStatus() {
        AdminRepository repository = new AdminRepository(new RecordingJdbcTemplate());

        assertThatThrownBy(() -> repository.listTeams(null, null, "RETIRED", null, null, null, 0, 20))
                .isInstanceOf(ModelGateException.class)
                .hasMessageContaining("Unsupported team status filter");
    }

    @Test
    void teamDirectorySupportsUnassignedOwnersAndUnfilteredDefault() {
        RecordingJdbcTemplate unassignedJdbc = new RecordingJdbcTemplate();
        new AdminRepository(unassignedJdbc).listTeams(null, null, null, null, null, false, 0, 20);
        assertThat(unassignedJdbc.queries).anyMatch(sql -> sql.contains("t.owner_user_id IS NULL"));

        RecordingJdbcTemplate defaultJdbc = new RecordingJdbcTemplate();
        new AdminRepository(defaultJdbc).listTeams(null, null, null, null, null, null, 0, 20);
        assertThat(defaultJdbc.queries).anyMatch(sql -> sql.startsWith("SELECT COUNT(*) FROM team t WHERE 1 = 1"));
    }

    @Test
    void dissolvingTeamDisablesAccessButRetainsLedgerData() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        UserRepository repository = new UserRepository(jdbcTemplate);

        UserRepository.DissolvedTeam dissolvedTeam = repository.dissolveTeam(2L);

        assertThat(dissolvedTeam.apiKeyHashes()).containsExactly("key-hash-1");
        assertThat(dissolvedTeam.quotaAccountIds()).containsExactly(7L);
        assertThat(dissolvedTeam.entitlementGrantIds()).containsExactly(31L);
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().startsWith("UPDATE virtual_api_key SET enabled = 0"));
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().startsWith("UPDATE team_member SET enabled = 0"));
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().contains("status = 'DISSOLVED'"));
        assertThat(jdbcTemplate.updates).noneMatch(call -> call.sql().contains("DELETE FROM usage_record") || call.sql().contains("DELETE FROM billing_record") || call.sql().contains("DELETE FROM quota_account") || call.sql().contains("DELETE FROM model_entitlement_grant"));
    }

    @Test
    void virtualKeyCountQueryUsesTheOuterFromClause() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();

        new AdminControlRepository(jdbcTemplate).listKeys(null, null, null, null, null, 0, 20);

        assertThat(jdbcTemplate.queries).anyMatch(sql -> sql.contains("SELECT COUNT(*) FROM virtual_api_key k"));
        assertThat(jdbcTemplate.queries).noneMatch(sql -> sql.startsWith("SELECT COUNT(*) FROM member_model_access"));
    }

    @Test
    void billingSummarySeparatesWhereClauseFromItsScopeColumn() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();

        new BillingRepository(jdbcTemplate).teamSummary(4L);

        assertThat(jdbcTemplate.queries).anyMatch(sql -> sql.contains("FROM billing_record\n WHERE team_id = ?"));
    }

    @Test
    void billingRecordsApplyAllAttributionFiltersToTheSameFactQuery() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        BillingRepository repository = new BillingRepository(jdbcTemplate);

        repository.records(new BillingQuery(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 7), 2L, 3L, 4L,
                "mock", "mock-gpt-4o-mini", "APPLICATION", "USD"), 0, 20);

        assertThat(jdbcTemplate.queries).anyMatch(sql -> sql.contains("b.team_id = ?")
                && sql.contains("b.project_id = ?") && sql.contains("b.member_id = ?")
                && sql.contains("b.provider = ?") && sql.contains("b.model = ?")
                && sql.contains("b.credential_type = ?") && sql.contains("b.currency = ?"));
    }

    @Test
    void billingQueryRejectsAnUnsupportedCredentialTypeBeforeQuerying() {
        BillingRepository repository = new BillingRepository(new RecordingJdbcTemplate());

        assertThatThrownBy(() -> repository.records(new BillingQuery(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 7), null, null, null,
                null, null, "SHARED", null), 0, 20))
                .isInstanceOf(ModelGateException.class)
                .hasMessageContaining("credentialType must be DEVELOPER or APPLICATION");
    }

    @Test
    void billingQueryRejectsRangesLongerThan366CalendarDays() {
        BillingRepository repository = new BillingRepository(new RecordingJdbcTemplate());

        assertThatThrownBy(() -> repository.records(new BillingQuery(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 2), null, null, null,
                null, null, null, null), 0, 20))
                .isInstanceOf(ModelGateException.class)
                .hasMessageContaining("no longer than 366 days");
    }

    @Test
    void tokenEstimatorUsesMessageLengthAndDefaultMaxOutput() {
        TokenEstimator estimator = new TokenEstimator(512);
        ChatCompletionRequest request = new ChatCompletionRequest(
                "smart-chat",
                List.of(new ChatMessage("user", "123456789")),
                false,
                null,
                null);

        assertThat(estimator.estimateInputTokens(request)).isEqualTo(3);
        assertThat(estimator.maxOutputTokens(request)).isEqualTo(512);
    }

    @Test
    void apiKeyContextCanCarryMemberAttribution() {
        ApiKeyContext context = new ApiKeyContext(
                10L,
                1L,
                2L,
                4L,
                5L,
                Set.of("smart-chat"),
                Map.of(),
                Map.of(),
                new RateLimitPolicy(60, 600, 120000, 5, 20, 50),
                new BudgetPolicy(500_000L),
                true,
                null,
                CredentialType.DEVELOPER,
                null,
                null);

        assertThat(context.memberId()).isEqualTo(4L);
        assertThat(context.modelAllowed("smart-chat")).isTrue();
    }

    @Test
    void teamCreationContractAllowsAnOwnerlessDraft() {
        CreateTeamRequest request = new CreateTeamRequest(1L, "Draft Team", null, null, null, null, null, null, null);

        assertThat(request.ownerUserId()).isNull();
        assertThat(request.name()).isEqualTo("Draft Team");
    }

    @Test
    void memberAccessContractCarriesOwnerScopeAndAllocation() {
        GrantMemberAccessRequest request = new GrantMemberAccessRequest(10L, List.of("mock-gpt-4o-mini"), 60_000L, "developer workspace");

        assertThat(request.ownerMemberId()).isEqualTo(10L);
        assertThat(request.tokenAllocation()).isEqualTo(60_000L);
        assertThat(request.modelNames()).containsExactly("mock-gpt-4o-mini");
    }

    @Test
    void hierarchicalEntitlementMigrationDefinesMemberAccountsAndAccessGrants() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/db/migration/V5__hierarchical_team_entitlements.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("team_entitlement_request", "member_model_access", "team_model_grant", "quota_transfer", "'MEMBER'");
        }
    }

    @Test
    void applicationRemovalMigrationDisablesLegacyKeysAndRemovesApplicationColumns() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/db/migration/V6__remove_applications_and_add_entitlement_grants.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("UPDATE virtual_api_key SET enabled = 0", "DROP TABLE application", "DROP COLUMN application_id", "granted_models");
        }
    }

    @Test
    void dualQuotaMigrationSeparatesDeveloperAndApplicationSubjects() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/db/migration/V10__dual_development_and_application_quota_pools.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("TEAM_DEVELOPMENT", "MEMBER_DEVELOPMENT", "TEAM_APPLICATION", "PROJECT_APPLICATION");
            assertThat(sql).contains("credential_type", "project_service_account", "provider_model_quota_pool");
        }
    }

    @Test
    void usageCompletedPipelineMigrationDefinesRuntimeControlsAndDurableConsumers() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/db/migration/V11__usage_completed_event_pipeline.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("team_tpm", "key_concurrency", "alert_remaining_percent", "usage_event_outbox", "budget_alert", "audit_log");
        }
    }

    @Test
    void usageCompletedEventCarriesOnlyTerminalMetadataAndQuotaSnapshots() {
        UsageCompletedEvent event = new UsageCompletedEvent("usage-req-1", "req-1", 1L, 2L, 3L, 4L,
                CredentialType.DEVELOPER, null, null, "smart-chat", "mock", "mock-chat", 10, 20, 30, 45,
                "SUCCESS", OffsetDateTime.now(), List.of(new QuotaSettlementSnapshot(7L, "smart-chat", "DAILY", 100L,
                20, OffsetDateTime.now(), 40, 30, 70L, false)));

        assertThat(event.eventId()).isEqualTo("usage-req-1");
        assertThat(event.quotaSettlements()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.remainingTokens()).isEqualTo(70L);
            assertThat(snapshot.alertRemainingPercent()).isEqualTo(20);
        });
    }

    @Test
    void applicationCredentialCarriesAnIndependentProjectSubject() {
        ApiKeyContext context = new ApiKeyContext(8L, 1L, 2L, null, 14L, Set.of("gpt-model"), Map.of(), Map.of(),
                new RateLimitPolicy(10, 20, 1000, 2, 3, 5), new BudgetPolicy(100L), true, null,
                CredentialType.APPLICATION, 22L, 31L);

        assertThat(context.credentialType()).isEqualTo(CredentialType.APPLICATION);
        assertThat(context.memberId()).isNull();
        assertThat(context.projectId()).isEqualTo(22L);
        assertThat(context.serviceAccountId()).isEqualTo(31L);
    }

    @Test
    void testRunnerMigrationSeparatesTemporaryKeysAndRequestRuns() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/db/migration/V8__test_runner_observability.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("key_kind", "test_run_id", "idx_ai_request_test_run");
        }
    }

    @Test
    void testRunnerAutoStartRequiresObservabilityToBeEnabled() {
        TestRunnerProcessManager manager = new TestRunnerProcessManager(
                false,
                true,
                "build/does-not-exist/test-runner.jar",
                19090);

        manager.start();

        assertThat(manager.isRunning()).isTrue();
        manager.stop();
        assertThat(manager.isRunning()).isFalse();
    }

    @Test
    void testRunnerRejectsInvalidLoopbackPorts() {
        assertThat(TestRunnerProcessManager.isLoopbackPortAvailable(0)).isFalse();
        assertThat(TestRunnerProcessManager.isLoopbackPortAvailable(65_536)).isFalse();
    }

    @Test
    void periodicModelEntitlementMigrationDefinesGrantUsageAndLegacyDailyConversion() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/db/migration/V7__model_periodic_entitlements.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("model_entitlement_grant", "model_entitlement_usage", "'DAILY'", "Migrated from legacy token balances");
        }
    }

    @Test
    void currentStateEntitlementMigrationRemovesHistoricalGrantsAndCascadesUsage() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/db/migration/V9__make_model_entitlements_current_state.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("'SUPERSEDED'", "'REVOKED'", "FOREIGN KEY (grant_id)", "ON DELETE CASCADE");
        }
    }

    @Test
    void existingEntitlementIsUpdatedInPlaceAndRevocationDeletesIt() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(List.of(42L));
        ModelEntitlementRepository repository = new ModelEntitlementRepository(jdbcTemplate);
        UpsertModelEntitlementRequest request = new UpsertModelEntitlementRequest("DAILY", 100L, null, "adjusted", null);

        ModelEntitlementItem item = repository.upsertTeam(1L, "mock-gpt-4o-mini", request);
        List<Long> deleted = repository.revokeTeam(1L, "mock-gpt-4o-mini");

        assertThat(item.grantId()).isEqualTo(42L);
        assertThat(deleted).containsExactly(42L);
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().startsWith("UPDATE model_entitlement_grant SET quota_mode"));
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().startsWith("DELETE FROM model_entitlement_grant"));
        assertThat(jdbcTemplate.updates).noneMatch(call -> call.sql().contains("SUPERSEDED"));
        assertThat(jdbcTemplate.updates).noneMatch(call -> call.sql().startsWith("INSERT INTO model_entitlement_grant"));
    }

    @Test
    void periodicQuotaCycleUsesShanghaiDailyAndMondayWeeklyBoundaries() {
        assertThat(ModelEntitlementRepository.cycleStart(QuotaMode.DAILY)).contains("T00:00");
        assertThat(ModelEntitlementRepository.cycleStart(QuotaMode.WEEKLY)).contains("T00:00");
    }

    @Test
    void entitlementQuotaLimitsKeepMemberAndTeamRangesSeparate() {
        EntitlementQuotaLimits.validateMember(QuotaMode.WEEKLY, EntitlementQuotaLimits.MEMBER_MAX_TOKENS);
        EntitlementQuotaLimits.validateTeam(QuotaMode.WEEKLY, EntitlementQuotaLimits.TEAM_MAX_TOKENS);
        EntitlementQuotaLimits.validateMember(QuotaMode.UNLIMITED, null);

        assertThatThrownBy(() -> EntitlementQuotaLimits.validateMember(QuotaMode.DAILY, EntitlementQuotaLimits.MEMBER_MAX_TOKENS + 1))
                .isInstanceOf(ModelGateException.class)
                .hasMessageContaining("Member periodic quota");
        assertThatThrownBy(() -> EntitlementQuotaLimits.validateTeam(QuotaMode.DAILY, EntitlementQuotaLimits.TEAM_MAX_TOKENS + 1))
                .isInstanceOf(ModelGateException.class)
                .hasMessageContaining("Team periodic quota");
        assertThatThrownBy(() -> EntitlementQuotaLimits.validateTeam(QuotaMode.UNLIMITED, 1L))
                .isInstanceOf(ModelGateException.class)
                .hasMessageContaining("UNLIMITED");
    }

    @Test
    void platformQuotaSummaryOnlyReadsActiveTeamGrantsFromEnabledTeams() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();

        ModelEntitlementRepository repository = new ModelEntitlementRepository(jdbcTemplate);
        assertThat(repository.platformQuotaSummary().items()).isEmpty();

        assertThat(jdbcTemplate.queries).anyMatch(sql -> sql.contains("JOIN team t ON t.id = meg.team_id"));
        assertThat(jdbcTemplate.queries).anyMatch(sql -> sql.contains("meg.member_id IS NULL") && sql.contains("meg.status = 'ACTIVE'") && sql.contains("t.enabled = 1"));
    }

    @Test
    void developmentAndApplicationEntitlementQueriesUseSeparatePoolFilters() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        ModelEntitlementRepository repository = new ModelEntitlementRepository(jdbcTemplate);

        repository.teamEntitlements(2L);
        repository.teamApplicationEntitlements(2L);
        repository.projectApplicationEntitlements(2L, 8L);

        assertThat(jdbcTemplate.queries).anyMatch(sql -> sql.contains("project_id IS NULL") && sql.contains("pool_type = 'DEVELOPMENT'"));
        assertThat(jdbcTemplate.queries).anyMatch(sql -> sql.contains("project_id IS NULL") && sql.contains("pool_type = 'APPLICATION'"));
        assertThat(jdbcTemplate.queries).anyMatch(sql -> sql.contains("project_id = ?") && sql.contains("pool_type = 'APPLICATION'"));
    }

    @Test
    void disablingAProjectServiceAccountAlsoDisablesItsApplicationKeys() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        ProjectRepository repository = new ProjectRepository(jdbcTemplate);

        repository.updateServiceAccount(2L, 8L, 12L, new com.modelgate.common.api.AdminDtos.UpdateProjectServiceAccountRequest(false));

        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().startsWith("UPDATE project_service_account SET enabled=?") && Integer.valueOf(0).equals(call.args()[0]));
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().contains("UPDATE virtual_api_key SET enabled=0") && call.sql().contains("credential_type='APPLICATION'"));
    }

    @Test
    void memberRemovalDisablesKeysAndClearsOnlyCurrentAccessState() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(List.of(31L, 32L));
        TeamEntitlementRepository repository = new TeamEntitlementRepository(jdbcTemplate);

        TeamEntitlementRepository.MemberDeactivation removed = repository.deactivateMember(2L, 7L, 10L);

        assertThat(removed.memberId()).isEqualTo(7L);
        assertThat(removed.entitlementGrantIds()).containsExactly(31L, 32L);
        assertThat(removed.keyHashes()).containsExactly("key-hash-1");
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().startsWith("UPDATE model_entitlement_grant SET status = 'REVOKED'"));
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().startsWith("DELETE FROM member_model_access"));
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().startsWith("UPDATE virtual_api_key SET enabled = 0"));
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().startsWith("UPDATE team_member SET enabled = 0"));
        assertThat(jdbcTemplate.updates).noneMatch(call -> call.sql().contains("DELETE FROM model_entitlement_grant") || call.sql().contains("DELETE FROM model_entitlement_usage") || call.sql().contains("DELETE FROM usage_record") || call.sql().contains("DELETE FROM billing_record"));
    }

    @Test
    void memberRemovalRejectsTheCurrentOwner() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(List.of(), "OWNER", 1);
        TeamEntitlementRepository repository = new TeamEntitlementRepository(jdbcTemplate);

        assertThatThrownBy(() -> repository.deactivateMember(2L, 7L, null))
                .isInstanceOf(ModelGateException.class)
                .hasMessageContaining("Transfer team ownership");
        assertThat(jdbcTemplate.updates).isEmpty();
    }

    @Test
    void ownerScopedMemberRemovalRequiresAnActiveOwnerOfThatTeam() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(List.of(), "MEMBER", 0);
        TeamEntitlementRepository repository = new TeamEntitlementRepository(jdbcTemplate);

        assertThatThrownBy(() -> repository.deactivateMember(2L, 7L, 10L))
                .isInstanceOf(ModelGateException.class)
                .hasMessageContaining("active team owner");
        assertThat(jdbcTemplate.updates).isEmpty();
    }

    @Test
    void usageEventCanCarryMemberAttribution() {
        UsageReportedEvent event = new UsageReportedEvent(
                "evt-test",
                "req-test",
                1L,
                2L,
                4L,
                10L,
                "mock",
                "mock-chat",
                12,
                8,
                20,
                100L,
                "SUCCESS",
                OffsetDateTime.now(), CredentialType.DEVELOPER, null, null);

        assertThat(event.memberId()).isEqualTo(4L);
        assertThat(event.totalTokens()).isEqualTo(20);
    }

    @Test
    void mockProviderReturnsNormalCompletion() {
        MockProvider provider = new MockProvider(0);
        ChatCompletionRequest request = new ChatCompletionRequest(
                "smart-chat",
                List.of(new ChatMessage("user", "hello")),
                false,
                64,
                new MockBehavior("normal", null, 120, 45));

        StepVerifier.create(provider.complete(new ProviderRequest("req-test", "smart-chat", "mock-chat", request)))
                .assertNext(response -> {
                    assertThat(response.message().role()).isEqualTo("assistant");
                    assertThat(response.usage().promptTokens()).isEqualTo(120);
                    assertThat(response.usage().completionTokens()).isEqualTo(45);
                    assertThat(response.usage().totalTokens()).isEqualTo(165);
                })
                .verifyComplete();
    }

    @Test
    void mockProviderStreamsChunksAndDoneMarker() {
        MockProvider provider = new MockProvider(0);
        ChatCompletionRequest request = new ChatCompletionRequest(
                "smart-chat",
                List.of(new ChatMessage("user", "hello")),
                true,
                64,
                null);

        StepVerifier.create(provider.stream(new ProviderRequest("req-test", "smart-chat", "mock-chat", request)))
                .expectNextMatches(chunk -> !chunk.done() && !chunk.content().isBlank())
                .thenConsumeWhile(chunk -> !chunk.done())
                .expectNextMatches(chunk -> chunk.done() && chunk.usage() != null)
                .verifyComplete();
    }

    @Test
    void mockProviderCanSimulateProviderFailure() {
        MockProvider provider = new MockProvider(0);
        ChatCompletionRequest request = new ChatCompletionRequest(
                "smart-chat",
                List.of(new ChatMessage("user", "hello")),
                false,
                64,
                new MockBehavior("500", null, null, null));

        StepVerifier.create(provider.complete(new ProviderRequest("req-test", "smart-chat", "mock-chat", request)))
                .expectError(ModelGateException.class)
                .verify();
    }

    @Test
    void rocketMqUsesNoopWhenDisabled() {
        contextRunner
                .withPropertyValues(
                        "modelgate.rocketmq.enabled=false",
                        "modelgate.rocketmq.endpoints=")
                .run(context -> {
                    assertThat(context).hasSingleBean(UsageEventPublisher.class);
                    assertThat(context).hasSingleBean(NoopUsageEventPublisher.class);
                });
    }

    @Test
    void rocketMqPublisherRejectsBlankEndpointsWhenEnabled() {
        assertThatThrownBy(() -> new RocketMqUsageEventPublisher(
                new ObjectMapper(),
                "",
                "modelgate-usage-producer",
                "AI_USAGE_EVENT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelgate.rocketmq.endpoints");
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<SqlCall> updates = new ArrayList<>();
        private final List<String> queries = new ArrayList<>();
        private final List<Long> activeEntitlementGrantIds;
        private final String memberRole;
        private final int ownerScopeCount;

        private RecordingJdbcTemplate() {
            this(List.of(), "MEMBER", 1);
        }

        private RecordingJdbcTemplate(List<Long> activeEntitlementGrantIds) {
            this(activeEntitlementGrantIds, "MEMBER", 1);
        }

        private RecordingJdbcTemplate(List<Long> activeEntitlementGrantIds, String memberRole, int ownerScopeCount) {
            this.activeEntitlementGrantIds = activeEntitlementGrantIds;
            this.memberRole = memberRole;
            this.ownerScopeCount = ownerScopeCount;
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(new SqlCall(sql, args));
            return 1;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            queries.add(sql);
            if (requiredType == Long.class) {
                if (sql.contains("quota_mode <>")) return requiredType.cast(0L);
                if (sql.contains("COALESCE(SUM")) return requiredType.cast(0L);
                return requiredType.cast(1L);
            }
            if (requiredType == Integer.class) {
                if (sql.contains("m.user_id = t.owner_user_id") && sql.contains("m.role = 'OWNER'")) return requiredType.cast(ownerScopeCount);
                return requiredType.cast(1);
            }
            throw new IllegalArgumentException("Unexpected result type: " + requiredType.getName());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
            queries.add(sql);
            if (sql.contains("FROM team_member WHERE id=? AND team_id=? AND enabled=1")) {
                return (T) new com.modelgate.common.api.AdminDtos.TeamMemberItem(7L, 1L, 2L, "Demo Developer", "developer@example.com", memberRole, true, OffsetDateTime.now());
            }
            if (sql.contains("FROM platform_user u")) {
                return (T) new UserItem(4L, "Demo User", "demo@example.com", true,
                        1L, 2L, "Demo Team", "MEMBER", OffsetDateTime.now());
            }
            if (sql.contains("SELECT consumed_tokens, frozen_tokens FROM model_entitlement_usage")) {
                throw new EmptyResultDataAccessException(1);
            }
            return null;
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            if (elementType == String.class && sql.contains("SELECT key_hash FROM virtual_api_key")) {
                return List.of(elementType.cast("key-hash-1"));
            }
            if (elementType == Long.class && sql.contains("SELECT id FROM quota_account")) {
                return List.of(elementType.cast(7L));
            }
            if (elementType == Long.class && sql.contains("SELECT id FROM model_entitlement_grant")) {
                return List.of(elementType.cast(31L));
            }
            throw new IllegalArgumentException("Unexpected query: " + sql);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            queries.add(sql);
            if (sql.startsWith("SELECT id FROM model_entitlement_grant")) {
                return (List<T>) activeEntitlementGrantIds;
            }
            if (sql.startsWith("SELECT id, team_id, member_id, model_name")) {
                return List.of((T) new ModelEntitlementItem(42L, 1L, null, "mock-gpt-4o-mini", "DAILY", 100L, null,
                        "ACTIVE", 0L, 0L, 100L, OffsetDateTime.now(), "adjusted", OffsetDateTime.now(), null));
            }
            return List.of();
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
            queries.add(sql);
            return List.of();
        }
    }

    private record SqlCall(String sql, Object[] args) {
    }
}
