package com.modelgate.bootstrap.api;

import com.modelgate.auth.VirtualKeyService;
import com.modelgate.common.chat.ChatCompletionChunk;
import com.modelgate.common.chat.ChatCompletionRequest;
import com.modelgate.common.chat.ChatCompletionResponse;
import com.modelgate.common.chat.ChatMessage;
import com.modelgate.common.chat.Usage;
import com.modelgate.common.domain.ApiKeyContext;
import com.modelgate.common.domain.QuotaReservation;
import com.modelgate.common.domain.RequestStatus;
import com.modelgate.common.domain.RouteTarget;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.common.event.QuotaSettlementSnapshot;
import com.modelgate.infrastructure.db.RequestRepository;
import com.modelgate.infrastructure.db.RouteRepository;
import com.modelgate.provider.ProviderRegistry;
import com.modelgate.provider.ProviderRequest;
import com.modelgate.provider.ProviderResponse;
import com.modelgate.provider.ProviderStreamChunk;
import com.modelgate.quota.QuotaService;
import com.modelgate.quota.TokenEstimator;
import com.modelgate.usage.UsageCompletedOutboxService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ChatGatewayService {
    private final VirtualKeyService virtualKeyService;
    private final RouteRepository routeRepository;
    private final RequestRepository requestRepository;
    private final ProviderRegistry providerRegistry;
    private final TokenEstimator tokenEstimator;
    private final QuotaService quotaService;
    private final UsageCompletedOutboxService usageCompletedOutboxService;
    private final StringRedisTemplate redisTemplate;
    private final OpenAiCompatibleProviderClient openAiCompatibleProviderClient;
    private final TestObservabilityService testObservabilityService;

    public ChatGatewayService(
            VirtualKeyService virtualKeyService,
            RouteRepository routeRepository,
            RequestRepository requestRepository,
            ProviderRegistry providerRegistry,
            TokenEstimator tokenEstimator,
            QuotaService quotaService,
            UsageCompletedOutboxService usageCompletedOutboxService,
            StringRedisTemplate redisTemplate,
            OpenAiCompatibleProviderClient openAiCompatibleProviderClient,
            TestObservabilityService testObservabilityService
    ) {
        this.virtualKeyService = virtualKeyService;
        this.routeRepository = routeRepository;
        this.requestRepository = requestRepository;
        this.providerRegistry = providerRegistry;
        this.tokenEstimator = tokenEstimator;
        this.quotaService = quotaService;
        this.usageCompletedOutboxService = usageCompletedOutboxService;
        this.redisTemplate = redisTemplate;
        this.openAiCompatibleProviderClient = openAiCompatibleProviderClient;
        this.testObservabilityService = testObservabilityService;
    }

    public Mono<ChatCompletionResponse> complete(String authorization, String idempotencyKey, String testRunId, ChatCompletionRequest request) {
        long start = System.currentTimeMillis();
        String requestId = requestId();
        return prepare(authorization, idempotencyKey, testRunId, request, requestId)
                .flatMap(ctx -> {
                    ApiKeyContext apiKey = ctx.apiKey();
                    RouteTarget target = ctx.target();
                    QuotaReservation reservation = ctx.reservation();
                    AtomicBoolean terminal = new AtomicBoolean();
                    return completeProvider(target, new ProviderRequest(requestId, request.model(), target.actualModel(), request))
                            .flatMap(response -> Mono.fromCallable(() -> {
                                if (!terminal.compareAndSet(false, true)) throw new IllegalStateException("Request already finalized.");
                                Usage usage = normalizedUsage(response, request, reservation);
                                long durationMs = System.currentTimeMillis() - start;
                                List<QuotaSettlementSnapshot> settlements = quotaService.settle(apiKey, request.model(), reservation, usage.totalTokens());
                                usageCompletedOutboxService.complete(apiKey, target, requestId, request.model(), usage, durationMs, null, RequestStatus.SUCCESS, null, settlements);
                                return new ChatCompletionResponse(
                                        requestId,
                                        "chat.completion",
                                        OffsetDateTime.now().toEpochSecond(),
                                        request.model(),
                                        List.of(new ChatCompletionResponse.Choice(0, response.message(), "stop")),
                                        usage);
                            }).subscribeOn(Schedulers.boundedElastic()))
                            .onErrorResume(ex -> fail(apiKey, target, request.model(), reservation, requestId, start, ex, terminal).then(Mono.error(ex)));
                });
    }

    public Flux<ServerSentEvent<Object>> stream(String authorization, String idempotencyKey, String testRunId, ChatCompletionRequest request) {
        long start = System.currentTimeMillis();
        String requestId = requestId();
        AtomicLong firstTokenMs = new AtomicLong(-1L);
        AtomicReference<Usage> usageRef = new AtomicReference<>();
        StringBuilder content = new StringBuilder();

        return prepare(authorization, idempotencyKey, testRunId, request, requestId)
                .flatMapMany(ctx -> {
                    ApiKeyContext apiKey = ctx.apiKey();
                    RouteTarget target = ctx.target();
                    QuotaReservation reservation = ctx.reservation();
                    AtomicBoolean terminal = new AtomicBoolean();
                    return streamProvider(target, new ProviderRequest(requestId, request.model(), target.actualModel(), request))
                            .concatMap(chunk -> toSse(requestId, request, chunk, firstTokenMs, usageRef, content)
                                    .flatMap(event -> {
                                        if (!chunk.done()) {
                                            return Mono.just(event);
                                        }
                                        return finishStream(apiKey, target, request.model(), reservation, requestId, start, firstTokenMs, usageRef, content, terminal)
                                                .thenReturn(event);
                                    }))
                            .onErrorResume(ex -> fail(apiKey, target, request.model(), reservation, requestId, start, ex, terminal).then(Mono.error(ex)))
                            .doOnCancel(() -> fail(apiKey, target, request.model(), reservation, requestId, start,
                                    new ModelGateException(ErrorCode.INTERNAL_ERROR, "Client cancelled stream."), terminal).subscribe());
                });
    }

    private Mono<PreparedRequest> prepare(String authorization, String idempotencyKey, String testRunId, ChatCompletionRequest request, String requestId) {
        return Mono.fromCallable(() -> {
            ApiKeyContext apiKey = virtualKeyService.authenticate(authorization);
            virtualKeyService.assertModelAllowed(apiKey, request.model());
            reserveIdempotency(apiKey, idempotencyKey);
            int inputTokens = tokenEstimator.estimateInputTokens(request);
            int maxOutputTokens = tokenEstimator.maxOutputTokens(request);
            QuotaReservation reservation = quotaService.reserve(apiKey, request.model(), requestId, inputTokens, maxOutputTokens);
            RouteTarget target;
            try {
                target = routeRepository.findFirstTarget(request.model())
                        .orElseThrow(() -> new ModelGateException(ErrorCode.MODEL_ROUTE_NOT_FOUND, "No route target for model: " + request.model(), requestId));
            } catch (RuntimeException ex) {
                quotaService.release(apiKey, request.model(), reservation);
                throw ex;
            }
            String resolvedTestRunId = testObservabilityService.resolveRunId(apiKey, testRunId, target);
            requestRepository.insertStarted(requestId, apiKey, request.model(), target, request.streamEnabled(), inputTokens, reservation.estimatedTokens(), resolvedTestRunId);
            return new PreparedRequest(apiKey, target, reservation);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<ProviderResponse> completeProvider(RouteTarget target, ProviderRequest request) {
        if ("OPENAI_COMPATIBLE".equals(target.providerType())) {
            return openAiCompatibleProviderClient.complete(target, request);
        }
        return providerRegistry.get("mock").complete(request);
    }

    private Flux<ProviderStreamChunk> streamProvider(RouteTarget target, ProviderRequest request) {
        if ("OPENAI_COMPATIBLE".equals(target.providerType())) {
            return openAiCompatibleProviderClient.stream(target, request);
        }
        return providerRegistry.get("mock").stream(request);
    }

    private Mono<ServerSentEvent<Object>> toSse(
            String requestId,
            ChatCompletionRequest request,
            ProviderStreamChunk chunk,
            AtomicLong firstTokenMs,
            AtomicReference<Usage> usageRef,
            StringBuilder content
    ) {
        return Mono.fromCallable(() -> {
            if (chunk.done()) {
                usageRef.set(chunk.usage());
                return ServerSentEvent.builder((Object) "[DONE]").build();
            }
            if (firstTokenMs.compareAndSet(-1L, System.currentTimeMillis())) {
                // Stores the absolute first-token timestamp; finishStream converts it to duration.
            }
            content.append(chunk.content());
            ChatCompletionChunk event = new ChatCompletionChunk(
                    requestId,
                    "chat.completion.chunk",
                    OffsetDateTime.now().toEpochSecond(),
                    request.model(),
                    List.of(new ChatCompletionChunk.Choice(0, new ChatCompletionChunk.Delta(null, chunk.content()), null)));
            return ServerSentEvent.builder((Object) event).build();
        });
    }

    private Mono<Void> finishStream(
            ApiKeyContext apiKey,
            RouteTarget target,
            String requestedModel,
            QuotaReservation reservation,
            String requestId,
            long start,
            AtomicLong firstTokenAt,
            AtomicReference<Usage> usageRef,
            StringBuilder content,
            AtomicBoolean terminal
    ) {
        return Mono.fromRunnable(() -> {
            if (!terminal.compareAndSet(false, true)) return;
            Usage usage = usageRef.get();
            if (usage == null) {
                usage = Usage.of(reservation.inputTokens(), estimateOutputTokens(content.toString()));
            }
            long durationMs = System.currentTimeMillis() - start;
            Long firstTokenMs = firstTokenAt.get() < 0 ? null : firstTokenAt.get() - start;
            List<QuotaSettlementSnapshot> settlements = quotaService.settle(apiKey, requestedModel, reservation, usage.totalTokens());
            usageCompletedOutboxService.complete(apiKey, target, requestId, requestedModel, usage, durationMs, firstTokenMs, RequestStatus.SUCCESS, null, settlements);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Mono<Void> fail(ApiKeyContext apiKey, RouteTarget target, String requestedModel, QuotaReservation reservation, String requestId, long start, Throwable ex, AtomicBoolean terminal) {
        return Mono.fromRunnable(() -> {
            if (!terminal.compareAndSet(false, true)) return;
            List<QuotaSettlementSnapshot> settlements = quotaService.release(apiKey, requestedModel, reservation);
            ErrorCode errorCode = ex instanceof ModelGateException mge ? mge.errorCode() : ErrorCode.INTERNAL_ERROR;
            long durationMs = System.currentTimeMillis() - start;
            RequestStatus status = "Client cancelled stream.".equals(ex.getMessage()) ? RequestStatus.CANCELLED : RequestStatus.FAILED;
            usageCompletedOutboxService.complete(apiKey, target, requestId, requestedModel, Usage.of(reservation.inputTokens(), 0), durationMs, null, status, errorCode.name(), settlements);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Usage normalizedUsage(ProviderResponse response, ChatCompletionRequest request, QuotaReservation reservation) {
        if (response.usage() != null) {
            return response.usage();
        }
        int outputTokens = estimateOutputTokens(response.message().content());
        return Usage.of(reservation.inputTokens(), outputTokens);
    }

    private int estimateOutputTokens(String text) {
        return Math.max(1, (int) Math.ceil((text == null ? 0 : text.length()) / 4.0));
    }

    private void reserveIdempotency(ApiKeyContext apiKey, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        String key = "idempotency:" + apiKey.keyId() + ":" + idempotencyKey;
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, "STARTED", Duration.ofHours(24));
        if (Boolean.FALSE.equals(ok)) {
            throw new ModelGateException(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
    }

    private String requestId() {
        return "req-" + UUID.randomUUID();
    }

    private record PreparedRequest(ApiKeyContext apiKey, RouteTarget target, QuotaReservation reservation) {
    }
}
