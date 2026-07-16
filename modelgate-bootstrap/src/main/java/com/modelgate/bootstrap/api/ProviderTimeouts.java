package com.modelgate.bootstrap.api;

import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/** Converts reactive provider deadlines into the public gateway timeout error. */
final class ProviderTimeouts {
    private ProviderTimeouts() {
    }

    static <T> Mono<T> completion(Mono<T> source, Duration timeout, String requestId) {
        return source.timeout(timeout)
                .onErrorMap(TimeoutException.class, ignored -> timeoutError(requestId));
    }

    static <T> Flux<T> stream(Flux<T> source, Duration firstEventTimeout, Duration idleTimeout, String requestId) {
        return source.timeout(
                        Mono.delay(firstEventTimeout).then(),
                        ignored -> Mono.delay(idleTimeout).then())
                .onErrorMap(TimeoutException.class, ignored -> timeoutError(requestId));
    }

    private static ModelGateException timeoutError(String requestId) {
        return new ModelGateException(ErrorCode.PROVIDER_TIMEOUT, ErrorCode.PROVIDER_TIMEOUT.defaultMessage(), requestId);
    }
}
