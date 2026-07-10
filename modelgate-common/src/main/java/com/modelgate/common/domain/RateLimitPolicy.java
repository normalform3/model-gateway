package com.modelgate.common.domain;

public record RateLimitPolicy(
        int keyRpm,
        int teamRpm,
        int teamConcurrency,
        int modelConcurrency
) {
}
