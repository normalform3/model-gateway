package com.modelgate.bootstrap;

import com.modelgate.auth.VirtualKeyService;
import com.modelgate.auth.ProviderCredentialCipher;
import com.modelgate.common.api.AdminDtos.DemoIdentity;
import com.modelgate.common.api.AdminDtos.DemoIdentityResponse;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.OffsetDateTime;

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

        @Override
        public int update(String sql, Object... args) {
            updates.add(new SqlCall(sql, args));
            return 1;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (requiredType == Long.class) {
                return requiredType.cast(1L);
            }
            throw new IllegalArgumentException("Unexpected result type: " + requiredType.getName());
        }
    }

    private record SqlCall(String sql, Object[] args) {
    }
}
