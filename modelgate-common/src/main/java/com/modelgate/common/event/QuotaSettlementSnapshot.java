package com.modelgate.common.event;

import java.time.OffsetDateTime;

/** Redis settlement snapshot carried to asynchronous ledger and alert consumers. */
public record QuotaSettlementSnapshot(
        long grantId,
        String modelName,
        String quotaMode,
        Long quotaLimit,
        Integer alertRemainingPercent,
        OffsetDateTime cycleStartedAt,
        int estimatedTokens,
        int actualTokens,
        long remainingTokens,
        boolean released
) {
}
