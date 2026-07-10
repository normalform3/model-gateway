package com.modelgate.common.event;

import java.time.OffsetDateTime;

public record UsageReportedEvent(
        String eventId,
        String requestId,
        long organizationId,
        long teamId,
        long applicationId,
        Long memberId,
        long apiKeyId,
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        int totalTokens,
        long durationMs,
        String status,
        OffsetDateTime occurredAt
) {
}
