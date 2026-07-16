package com.modelgate.bootstrap.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.auth.ProviderCredentialService;
import com.modelgate.common.chat.ChatMessage;
import com.modelgate.common.chat.Usage;
import com.modelgate.common.domain.RouteTarget;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.provider.ProviderRequest;
import com.modelgate.provider.ProviderResponse;
import com.modelgate.provider.ProviderStreamChunk;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class OpenAiCompatibleProviderClient {
    private final WebClient.Builder webClient;
    private final ObjectMapper objectMapper;
    private final ProviderCredentialService credentialService;

    public OpenAiCompatibleProviderClient(WebClient.Builder webClient, ObjectMapper objectMapper, ProviderCredentialService credentialService) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.credentialService = credentialService;
    }

    public Mono<ProviderResponse> complete(RouteTarget target, ProviderRequest request) {
        return completeAttempt(target, request, Set.of());
    }

    public Flux<ProviderStreamChunk> stream(RouteTarget target, ProviderRequest request) {
        return streamAttempt(target, request, Set.of());
    }

    private Mono<ProviderResponse> completeAttempt(RouteTarget target, ProviderRequest request, Set<Long> excluded) {
        ProviderCredentialService.ResolvedCredential credential = credentialService.select(target.providerId(), target.deploymentId(), excluded);
        return post(target, request, credential, false).map(this::toCompletion)
                .onErrorResume(this::retryable, error -> completeAttempt(target, request, Set.of(credential.credentialId())));
    }

    private Flux<ProviderStreamChunk> streamAttempt(RouteTarget target, ProviderRequest request, Set<Long> excluded) {
        ProviderCredentialService.ResolvedCredential credential = credentialService.select(target.providerId(), target.deploymentId(), excluded);
        AtomicBoolean emitted = new AtomicBoolean();
        return streamPost(target, request, credential).map(this::toChunk).doOnNext(chunk -> emitted.set(true))
                .onErrorResume(error -> !emitted.get() && retryable(error), error -> streamAttempt(target, request, Set.of(credential.credentialId())));
    }

    private Mono<JsonNode> post(RouteTarget target, ProviderRequest request, ProviderCredentialService.ResolvedCredential credential, boolean stream) {
        return webClient.build().post().uri(endpoint(target)).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + credential.apiKey()).header("Idempotency-Key", request.requestId())
                .bodyValue(payload(request, stream)).exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) return response.bodyToMono(JsonNode.class);
                    return response.bodyToMono(String.class).defaultIfEmpty("").flatMap(body -> Mono.error(providerError(response.statusCode().value(), request.requestId())));
                });
    }

    private Flux<ServerSentEvent<String>> streamPost(RouteTarget target, ProviderRequest request, ProviderCredentialService.ResolvedCredential credential) {
        return webClient.build().post().uri(endpoint(target)).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .header("Authorization", "Bearer " + credential.apiKey()).header("Idempotency-Key", request.requestId())
                .bodyValue(payload(request, true)).exchangeToFlux(response -> {
                    if (response.statusCode().is2xxSuccessful()) return response.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() { });
                    return response.bodyToMono(String.class).defaultIfEmpty("").flatMapMany(body -> Flux.error(providerError(response.statusCode().value(), request.requestId())));
                });
    }

    private ProviderResponse toCompletion(JsonNode root) {
        JsonNode choice = root.path("choices").isArray() && root.path("choices").size() > 0 ? root.path("choices").get(0) : objectMapper.createObjectNode();
        String content = choice.path("message").path("content").asText("");
        return new ProviderResponse(new ChatMessage("assistant", content), usage(root.path("usage")));
    }

    private ProviderStreamChunk toChunk(ServerSentEvent<String> event) {
        String data = event.data();
        if ("[DONE]".equals(data)) return ProviderStreamChunk.done(null);
        try {
            JsonNode root = objectMapper.readTree(data == null ? "{}" : data);
            JsonNode choice = root.path("choices").isArray() && root.path("choices").size() > 0 ? root.path("choices").get(0) : objectMapper.createObjectNode();
            String content = choice.path("delta").path("content").asText("");
            return ProviderStreamChunk.content(content);
        } catch (Exception ex) { throw new ModelGateException(ErrorCode.PROVIDER_UNAVAILABLE, "Provider returned an invalid SSE event."); }
    }

    private Usage usage(JsonNode usage) { if (usage == null || usage.isMissingNode()) return null; int input = usage.path("prompt_tokens").asInt(0); int output = usage.path("completion_tokens").asInt(0); return input + output == 0 ? null : Usage.of(input, output); }
    private String endpoint(RouteTarget target) { if (target.baseUrl() == null || target.baseUrl().isBlank()) throw new ModelGateException(ErrorCode.PROVIDER_UNAVAILABLE, "Provider base URL is not configured."); return target.baseUrl().replaceAll("/+$", "") + "/chat/completions"; }
    private Map<String, Object> payload(ProviderRequest request, boolean stream) { Map<String, Object> payload = new LinkedHashMap<>(); payload.put("model", request.actualModel()); payload.put("messages", request.originalRequest().messages()); payload.put("stream", stream); if (request.originalRequest().maxTokens() != null) payload.put("max_tokens", request.originalRequest().maxTokens()); return payload; }
    private boolean retryable(Throwable error) { return error instanceof ModelGateException exception && (exception.errorCode() == ErrorCode.PROVIDER_UNAVAILABLE || exception.errorCode() == ErrorCode.PROVIDER_TIMEOUT); }
    private ModelGateException providerError(int status, String requestId) { return new ModelGateException(status == 429 || status >= 500 ? ErrorCode.PROVIDER_UNAVAILABLE : ErrorCode.BAD_MODEL_REQUEST, "Provider request was rejected.", requestId); }
}
