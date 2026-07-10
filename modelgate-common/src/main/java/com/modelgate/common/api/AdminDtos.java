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

    public record CreateTeamRequest(
            @NotNull Long organizationId,
            @NotBlank String name,
            Integer keyRpm,
            Integer teamRpm,
            Integer teamConcurrency,
            Integer modelConcurrency,
            @NotBlank String ownerName,
            @NotBlank String ownerEmail
    ) {
    }

    public record UpdateTeamRequest(
            String name,
            Integer keyRpm,
            Integer teamRpm,
            Integer teamConcurrency,
            Integer modelConcurrency,
            Boolean enabled
    ) {
    }

    public record TeamSummary(
            long teamId,
            long organizationId,
            long defaultApplicationId,
            String name,
            boolean enabled,
            int keyRpm,
            int teamRpm,
            int teamConcurrency,
            int modelConcurrency,
            Long ownerMemberId,
            String ownerName,
            String ownerEmail,
            int memberCount,
            int keyCount
    ) {
    }

    public record TeamListResponse(List<TeamSummary> items) {
    }

    public record CreateTeamMemberRequest(
            @NotBlank String name,
            @NotBlank String email
    ) {
    }

    public record UpdateTeamMemberRequest(
            String name,
            String email,
            String role,
            Boolean enabled
    ) {
    }

    public record TeamMemberItem(
            long memberId,
            long organizationId,
            long teamId,
            String name,
            String email,
            String role,
            boolean enabled,
            OffsetDateTime createdAt
    ) {
    }

    public record TeamMemberListResponse(List<TeamMemberItem> items) {
    }

    public record CreateMemberApiKeyRequest(
            @NotNull Long applicationId,
            @NotBlank String name,
            List<String> allowedModels,
            OffsetDateTime expiresAt,
            Long createdByMemberId
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
            Long memberId,
            String memberName,
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
