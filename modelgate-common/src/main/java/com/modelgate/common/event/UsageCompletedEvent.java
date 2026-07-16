package com.modelgate.common.event;

import com.modelgate.common.domain.CredentialType;

import java.time.OffsetDateTime;
import java.util.List;

/** Terminal, privacy-safe event emitted once for each accepted model request. */
public record UsageCompletedEvent(
        String eventId,
        String requestId,
        long organizationId,
        long teamId,
        Long memberId,
        long apiKeyId,
        CredentialType credentialType,
        Long projectId,
        Long serviceAccountId,
        String requestedModel,
        String provider,
        String actualModel,
        int inputTokens,
        int outputTokens,
        int totalTokens,
        long durationMs,
        String status,
        OffsetDateTime occurredAt,
        List<QuotaSettlementSnapshot> quotaSettlements
) {
}
