package com.modelgate.bootstrap;

import com.modelgate.auth.VirtualKeyService;
import com.modelgate.auth.ProviderCredentialCipher;
import com.modelgate.common.api.AdminDtos.DemoIdentity;
import com.modelgate.common.api.AdminDtos.DemoIdentityResponse;
import com.modelgate.common.api.AdminDtos.CreateTeamRequest;
import com.modelgate.common.api.AdminDtos.GrantMemberAccessRequest;
import com.modelgate.common.api.AdminDtos.UserItem;
import com.modelgate.common.chat.ChatCompletionRequest;
import com.modelgate.common.chat.ChatMessage;
import com.modelgate.common.chat.MockBehavior;
import com.modelgate.common.domain.ApiKeyContext;
import com.modelgate.common.domain.BudgetPolicy;
import com.modelgate.common.domain.RateLimitPolicy;
import com.modelgate.common.event.UsageReportedEvent;
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
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.OffsetDateTime;
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
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().contains("DELETE FROM application WHERE team_id"));
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().contains("DELETE FROM team_member WHERE team_id"));
        assertThat(jdbcTemplate.updates).anyMatch(call -> call.sql().contains("DELETE FROM team WHERE id"));
        assertThat(jdbcTemplate.updates).noneMatch(call -> call.sql().contains("DELETE FROM platform_user"));
    }

    @Test
    void virtualKeyCountQueryUsesTheOuterFromClause() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();

        new AdminControlRepository(jdbcTemplate).listKeys(null, null, null, null, null, null, 0, 20);

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
                3L,
                4L,
                5L,
                Set.of("smart-chat"),
                new RateLimitPolicy(60, 600, 20, 50),
                new BudgetPolicy(500_000L),
                true,
                null);

        assertThat(context.memberId()).isEqualTo(4L);
        assertThat(context.modelAllowed("smart-chat")).isTrue();
    }

    @Test
    void teamCreationContractAllowsAnOwnerlessDraft() {
        CreateTeamRequest request = new CreateTeamRequest(1L, "Draft Team", null, null, null, null, null);

        assertThat(request.ownerUserId()).isNull();
        assertThat(request.name()).isEqualTo("Draft Team");
    }

    @Test
    void memberAccessContractCarriesOwnerScopeAndAllocation() {
        GrantMemberAccessRequest request = new GrantMemberAccessRequest(10L, 100L, List.of("mock-gpt-4o-mini"), 60_000L, "developer workspace");

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
    void usageEventCanCarryMemberAttribution() {
        UsageReportedEvent event = new UsageReportedEvent(
                "evt-test",
                "req-test",
                1L,
                2L,
                3L,
                4L,
                10L,
                "mock",
                "mock-chat",
                12,
                8,
                20,
                100L,
                "SUCCESS",
                OffsetDateTime.now());

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

        @Override
        public int update(String sql, Object... args) {
            updates.add(new SqlCall(sql, args));
            return 1;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            queries.add(sql);
            if (requiredType == Long.class) {
                return requiredType.cast(1L);
            }
            throw new IllegalArgumentException("Unexpected result type: " + requiredType.getName());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
            queries.add(sql);
            if (sql.contains("FROM platform_user u")) {
                return (T) new UserItem(4L, "Demo User", "demo@example.com", true,
                        1L, 2L, "Demo Team", "MEMBER", OffsetDateTime.now());
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
            throw new IllegalArgumentException("Unexpected query: " + sql);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            queries.add(sql);
            return List.of();
        }
    }

    private record SqlCall(String sql, Object[] args) {
    }
}
