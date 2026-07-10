package com.modelgate.bootstrap;

import com.modelgate.auth.VirtualKeyService;
import com.modelgate.common.chat.ChatCompletionRequest;
import com.modelgate.common.chat.ChatMessage;
import com.modelgate.common.chat.MockBehavior;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.provider.ProviderRequest;
import com.modelgate.provider.mock.MockProvider;
import com.modelgate.quota.TokenEstimator;
import com.modelgate.usage.NoopUsageEventPublisher;
import com.modelgate.usage.RocketMqUsageEventPublisher;
import com.modelgate.usage.UsageEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

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
    void mockProviderReturnsNormalCompletion() {
        MockProvider provider = new MockProvider(0);
        ChatCompletionRequest request = new ChatCompletionRequest(
                "smart-chat",
                List.of(new ChatMessage("user", "hello")),
                false,
                64,
                null);

        StepVerifier.create(provider.complete(new ProviderRequest("req-test", "smart-chat", "mock-chat", request)))
                .assertNext(response -> {
                    assertThat(response.message().role()).isEqualTo("assistant");
                    assertThat(response.usage().totalTokens()).isGreaterThan(0);
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
}
