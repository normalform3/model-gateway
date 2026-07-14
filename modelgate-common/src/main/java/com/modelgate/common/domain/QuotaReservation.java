package com.modelgate.common.domain;

public record QuotaReservation(
        String requestId,
        int estimatedTokens,
        int inputTokens,
        ModelQuotaPolicy memberQuota,
        ModelQuotaPolicy teamQuota,
        String cycleStart
) {
}
