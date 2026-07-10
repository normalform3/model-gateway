package com.modelgate.common.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

public final class AdminDtos {
    private AdminDtos() {
    }

    public record BootstrapDemoResponse(
            long organizationId,
            long teamId,
            long applicationId,
            long quotaAccountId,
            String logicalModel
    ) {
    }

    public record CreateApiKeyRequest(
            @NotNull Long organizationId,
            @NotNull Long teamId,
            @NotNull Long applicationId,
            @NotBlank String name,
            List<String> allowedModels,
            OffsetDateTime expiresAt
    ) {
    }

    public record CreateApiKeyResponse(
            long keyId,
            String keyPrefix,
            String apiKey,
            boolean enabled
    ) {
    }

    public record DisableApiKeyResponse(long keyId, boolean enabled) {
    }

    public record QuotaResponse(
            long teamId,
            long availableTokens,
            long frozenTokens,
            long consumedTokens,
            OffsetDateTime updatedAt
    ) {
    }

    public record RequestLogItem(
            String requestId,
            String requestedModel,
            String actualProvider,
            String actualModel,
            String status,
            int inputTokens,
            int outputTokens,
            long durationMs,
            Long firstTokenMs,
            OffsetDateTime createdAt
    ) {
    }

    public record RequestLogResponse(List<RequestLogItem> items, String nextCursor) {
    }
}
