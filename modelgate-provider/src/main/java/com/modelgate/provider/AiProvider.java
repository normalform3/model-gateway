package com.modelgate.provider;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AiProvider {
    String providerName();

    Mono<ProviderResponse> complete(ProviderRequest request);

    Flux<ProviderStreamChunk> stream(ProviderRequest request);
}
