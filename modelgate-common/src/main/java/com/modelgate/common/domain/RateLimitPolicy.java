package com.modelgate.common.domain;

public record RateLimitPolicy(
        int keyRpm,
        int teamRpm,
        int teamTpm,
        int keyConcurrency,
        int teamConcurrency,
        int modelConcurrency
) {
}
