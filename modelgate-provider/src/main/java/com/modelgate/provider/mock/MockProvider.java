package com.modelgate.provider.mock;

import com.modelgate.common.chat.ChatMessage;
import com.modelgate.common.chat.MockBehavior;
import com.modelgate.common.chat.Usage;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.provider.AiProvider;
import com.modelgate.provider.ProviderRequest;
import com.modelgate.provider.ProviderResponse;
import com.modelgate.provider.ProviderStreamChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Component
public class MockProvider implements AiProvider {
    private final long defaultDelayMs;

    public MockProvider(@Value("${modelgate.mock.default-delay-ms:80}") long defaultDelayMs) {
        this.defaultDelayMs = defaultDelayMs;
    }

    @Override
    public String providerName() {
        return "mock";
    }

    @Override
    public Mono<ProviderResponse> complete(ProviderRequest request) {
        MockBehavior behavior = behavior(request);
        if (behavior.mode("429")) {
            return Mono.error(new ModelGateException(ErrorCode.PROVIDER_UNAVAILABLE, "Mock provider returned 429.", request.requestId()));
        }
        if (behavior.mode("500")) {
            return Mono.error(new ModelGateException(ErrorCode.PROVIDER_UNAVAILABLE, "Mock provider returned 500.", request.requestId()));
        }
        long delay = delayMs(behavior);
        Usage usage = behavior.mode("usage_missing") ? null : usage(behavior);
        ChatMessage message = new ChatMessage("assistant", responseText(request));
        return Mono.delay(Duration.ofMillis(delay)).thenReturn(new ProviderResponse(message, usage));
    }

    @Override
    public Flux<ProviderStreamChunk> stream(ProviderRequest request) {
        MockBehavior behavior = behavior(request);
        if (behavior.mode("429")) {
            return Flux.error(new ModelGateException(ErrorCode.PROVIDER_UNAVAILABLE, "Mock provider returned 429.", request.requestId()));
        }
        if (behavior.mode("500")) {
            return Flux.error(new ModelGateException(ErrorCode.PROVIDER_UNAVAILABLE, "Mock provider returned 500.", request.requestId()));
        }

        List<String> parts = List.of("ModelGate", " routes", " AI", " traffic", " through", " a", " controlled", " gateway.");
        Flux<ProviderStreamChunk> chunks = Flux.fromIterable(parts)
                .delayElements(Duration.ofMillis(delayMs(behavior)))
                .map(ProviderStreamChunk::content);

        if (behavior.mode("stream_break")) {
            return chunks.take(4).concatWith(Flux.error(new ModelGateException(ErrorCode.PROVIDER_UNAVAILABLE, "Mock stream interrupted.", request.requestId())));
        }

        Usage usage = behavior.mode("usage_missing") ? null : usage(behavior);
        return chunks.concatWith(Mono.just(ProviderStreamChunk.done(usage)));
    }

    private MockBehavior behavior(ProviderRequest request) {
        MockBehavior mock = request.originalRequest().mock();
        return mock == null ? new MockBehavior("normal", null, null, null) : mock;
    }

    private long delayMs(MockBehavior behavior) {
        if (behavior.mode("slow")) {
            return Math.max(defaultDelayMs, 300L);
        }
        return behavior.delayMs() == null ? defaultDelayMs : Math.max(0L, behavior.delayMs());
    }

    private Usage usage(MockBehavior behavior) {
        int input = behavior.inputTokens() == null ? 64 : Math.max(1, behavior.inputTokens());
        int output = behavior.outputTokens() == null ? 32 : Math.max(1, behavior.outputTokens());
        return Usage.of(input, output);
    }

    private String responseText(ProviderRequest request) {
        return "ModelGate mock response for logical model " + request.logicalModel() + ".";
    }
}
