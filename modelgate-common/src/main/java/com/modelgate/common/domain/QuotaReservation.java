package com.modelgate.common.domain;

public record QuotaReservation(
        long accountId,
        String requestId,
        int estimatedTokens,
        int inputTokens
) {
}
