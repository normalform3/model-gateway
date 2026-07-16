package com.modelgate.bootstrap.api;

import com.modelgate.common.api.ErrorResponse;
import com.modelgate.common.chat.ChatCompletionRequest;
import com.modelgate.common.chat.ChatMessage;
import com.modelgate.common.chat.MockBehavior;
import com.modelgate.common.chat.Usage;
import com.modelgate.common.domain.ApiKeyContext;
import com.modelgate.common.domain.BudgetPolicy;
import com.modelgate.common.domain.CredentialType;
import com.modelgate.common.domain.ModelQuotaPolicy;
import com.modelgate.common.domain.QuotaMode;
import com.modelgate.common.domain.QuotaReservation;
import com.modelgate.common.domain.RateLimitPolicy;
import com.modelgate.common.domain.RequestStatus;
import com.modelgate.common.domain.RouteTarget;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.auth.VirtualKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.common.event.QuotaSettlementSnapshot;
import com.modelgate.infrastructure.db.RequestRepository;
import com.modelgate.infrastructure.db.RouteRepository;
import com.modelgate.provider.AiProvider;
import com.modelgate.provider.ProviderRegistry;
import com.modelgate.provider.ProviderRequest;
import com.modelgate.provider.ProviderResponse;
import com.modelgate.provider.ProviderStreamChunk;
import com.modelgate.provider.mock.MockProvider;
import com.modelgate.quota.QuotaService;
import com.modelgate.quota.TokenEstimator;
import com.modelgate.usage.UsageCompletedOutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderStreamResilienceTests {

    @Test
    void completionDeadlineMapsToProviderTimeout() {
        StepVerifier.withVirtualTime(() -> ProviderTimeouts.completion(Mono.never(), Duration.ofSeconds(1), "req-timeout"))
                .thenAwait(Duration.ofSeconds(1))
                .expectErrorSatisfies(error -> assertProviderTimeout(error, "req-timeout"))
                .verify();
    }

    @Test
    void firstStreamEventDeadlineMapsToProviderTimeout() {
        StepVerifier.withVirtualTime(() -> ProviderTimeouts.stream(Flux.never(), Duration.ofSeconds(1), Duration.ofSeconds(5), "req-first"))
                .thenAwait(Duration.ofSeconds(1))
                .expectErrorSatisfies(error -> assertProviderTimeout(error, "req-first"))
                .verify();
    }

    @Test
    void streamIdleDeadlineMapsToProviderTimeout() {
        StepVerifier.withVirtualTime(() -> ProviderTimeouts.stream(
                        Flux.concat(Mono.just("first"), Mono.never()), Duration.ofSeconds(1), Duration.ofSeconds(2), "req-idle"))
                .expectNext("first")
                .thenAwait(Duration.ofSeconds(2))
                .expectErrorSatisfies(error -> assertProviderTimeout(error, "req-idle"))
                .verify();
    }

    @Test
    void mockStreamStallTriggersTheIdleDeadlineAfterInitialChunks() {
        MockProvider provider = new MockProvider(0);
        ChatCompletionRequest request = new ChatCompletionRequest(
                "mock-chat", List.of(new ChatMessage("user", "stream")), true, null,
                new MockBehavior("stream_stall", 100L, null, null));

        StepVerifier.create(ProviderTimeouts.stream(
                        provider.stream(new ProviderRequest("req-stall", "mock-chat", "mock-chat", request)),
                        Duration.ofSeconds(1), Duration.ofMillis(20), "req-stall"))
                .expectNextCount(2)
                .expectErrorSatisfies(error -> assertProviderTimeout(error, "req-stall"))
                .verify();
    }

    @Test
    void streamErrorEventContainsOnlyThePublicErrorContract() {
        ServerSentEvent<Object> event = ChatGatewayService.errorSse(
                new ModelGateException(ErrorCode.PROVIDER_TIMEOUT, "private upstream detail", "req-error"), "req-error");

        assertThat(event.event()).isEqualTo("error");
        assertThat(event.data()).isInstanceOf(ErrorResponse.class);
        ErrorResponse response = (ErrorResponse) event.data();
        assertThat(response.error().code()).isEqualTo(ErrorCode.PROVIDER_TIMEOUT.name());
        assertThat(response.error().message()).isEqualTo(ErrorCode.PROVIDER_TIMEOUT.defaultMessage());
        assertThat(response.error().message()).doesNotContain("private upstream detail");
        assertThat(response.error().requestId()).isEqualTo("req-error");
    }

    @Test
    void clientCancellationSettlesReceivedChunksExactlyOnce() throws InterruptedException {
        ApiKeyContext context = apiKeyContext();
        RouteTarget target = new RouteTarget(1L, "mock-chat", "mock", "mock-chat");
        ModelQuotaPolicy policy = new ModelQuotaPolicy(1L, "mock-chat", QuotaMode.DAILY, 1_000L, 20);
        QuotaReservation reservation = new QuotaReservation("reserved-request", 32, 4, policy, policy, "2026-07-16T00:00+08:00");
        FixedKeyService keys = new FixedKeyService(context);
        FixedRouteRepository routes = new FixedRouteRepository(target);
        RecordingRequestRepository requests = new RecordingRequestRepository();
        RecordingQuotaService quota = new RecordingQuotaService(reservation);
        RecordingOutboxService outbox = new RecordingOutboxService();
        TestObservabilityService observability = new DisabledObservabilityService();
        AiProvider provider = new AiProvider() {
            @Override public String providerName() { return "mock"; }
            @Override public Mono<ProviderResponse> complete(ProviderRequest request) { return Mono.never(); }
            @Override public Flux<ProviderStreamChunk> stream(ProviderRequest request) {
                return Flux.concat(Mono.just(ProviderStreamChunk.content("hello")), Mono.never());
            }
        };

        ChatGatewayService service = new ChatGatewayService(
                keys, routes, requests, new ProviderRegistry(List.of(provider)), new TokenEstimator(32), quota, outbox,
                new StringRedisTemplate(), null, observability,
                new ProviderTimeoutProperties(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)));
        ChatCompletionRequest request = new ChatCompletionRequest(
                "mock-chat", List.of(new ChatMessage("user", "stream")), true, null, null);

        StepVerifier.create(service.stream("Bearer mg-key-example", null, null, request).take(1))
                .assertNext(event -> assertThat(event.data()).isNotInstanceOf(ErrorResponse.class))
                .verifyComplete();

        assertThat(outbox.completed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(quota.settlements).isEqualTo(1);
        assertThat(quota.releases).isZero();
        assertThat(quota.actualTokens).isEqualTo(6);
        assertThat(outbox.status).isEqualTo(RequestStatus.CANCELLED);
        assertThat(outbox.usage).isEqualTo(Usage.of(4, 2));
    }

    private static void assertProviderTimeout(Throwable error, String requestId) {
        assertThat(error).isInstanceOf(ModelGateException.class);
        ModelGateException exception = (ModelGateException) error;
        assertThat(exception.errorCode()).isEqualTo(ErrorCode.PROVIDER_TIMEOUT);
        assertThat(exception.requestId()).isEqualTo(requestId);
    }

    private static ApiKeyContext apiKeyContext() {
        return new ApiKeyContext(10L, 1L, 2L, 3L, 4L, Set.of("mock-chat"), Map.of(), Map.of(),
                new RateLimitPolicy(60, 600, 120_000, 5, 20, 50), new BudgetPolicy(1_000L), true, null,
                CredentialType.DEVELOPER, null, null);
    }

    private static final class FixedKeyService extends VirtualKeyService {
        private final ApiKeyContext context;

        private FixedKeyService(ApiKeyContext context) {
            super(null, null, new ObjectMapper(), event -> { });
            this.context = context;
        }

        @Override public ApiKeyContext authenticate(String authorizationHeader) { return context; }
        @Override public void assertModelAllowed(ApiKeyContext ignored, String model) { }
    }

    private static final class FixedRouteRepository extends RouteRepository {
        private final RouteTarget target;

        private FixedRouteRepository(RouteTarget target) {
            super(null);
            this.target = target;
        }

        @Override public Optional<RouteTarget> findFirstTarget(String logicalModel) { return Optional.of(target); }
    }

    private static final class RecordingRequestRepository extends RequestRepository {
        private RecordingRequestRepository() { super(null); }

        @Override public void insertStarted(String requestId, ApiKeyContext context, String requestedModel, RouteTarget target,
                                            boolean stream, int inputTokens, int estimatedTokens, String testRunId) { }
    }

    private static final class RecordingQuotaService extends QuotaService {
        private final QuotaReservation reservation;
        private int settlements;
        private int releases;
        private int actualTokens;

        private RecordingQuotaService(QuotaReservation reservation) {
            super(null, null, null, 300);
            this.reservation = reservation;
        }

        @Override public QuotaReservation reserve(ApiKeyContext context, String logicalModel, String requestId, int inputTokens, int maxOutputTokens) {
            return reservation;
        }

        @Override public List<QuotaSettlementSnapshot> settle(ApiKeyContext context, String logicalModel, QuotaReservation reservation, int actualTokens) {
            settlements++;
            this.actualTokens = actualTokens;
            return List.of();
        }

        @Override public List<QuotaSettlementSnapshot> release(ApiKeyContext context, String logicalModel, QuotaReservation reservation) {
            releases++;
            return List.of();
        }
    }

    private static final class RecordingOutboxService extends UsageCompletedOutboxService {
        private Usage usage;
        private RequestStatus status;
        private final CountDownLatch completed = new CountDownLatch(1);

        private RecordingOutboxService() { super(null, null, new ObjectMapper(), "test"); }

        @Override public void complete(ApiKeyContext key, RouteTarget target, String requestId, String requestedModel, Usage usage,
                                       long durationMs, Long firstTokenMs, RequestStatus status, String errorCode,
                                       List<QuotaSettlementSnapshot> settlements) {
            this.usage = usage;
            this.status = status;
            completed.countDown();
        }
    }

    private static final class DisabledObservabilityService extends TestObservabilityService {
        private DisabledObservabilityService() { super(false, null, null); }

        @Override public String resolveRunId(ApiKeyContext apiKey, String requestedRunId, RouteTarget target) { return null; }
    }
}
