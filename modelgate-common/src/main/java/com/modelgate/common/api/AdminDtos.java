package com.modelgate.common.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.math.BigDecimal;
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

    public record DemoIdentity(
            String identityId,
            String displayName,
            String role,
            Long teamId,
            String teamName,
            Long memberId
    ) {
    }

    public record DemoIdentityResponse(boolean initialized, List<DemoIdentity> identities) {
    }

    public record UserItem(
            long userId,
            String name,
            String email,
            boolean enabled,
            Long memberId,
            Long teamId,
            String teamName,
            String role,
            OffsetDateTime createdAt
    ) {
    }

    public record UserListResponse(List<UserItem> items) {
    }

    public record CreateUserRequest(@NotBlank String name, @NotBlank String email, Boolean enabled) {
    }

    public record UpdateUserRequest(String name, String email, Boolean enabled) {
    }

    public record TeamMembershipRequest(@NotNull Long teamId, @NotBlank String role) {
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
            Long ownerUserId
    ) {
    }

    public record SetTeamOwnerRequest(Long ownerUserId) {
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
            String status,
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

    public record TeamListResponse(List<TeamSummary> items, int page, int size, long total) {
    }

    public record CreateTeamMemberRequest(
            @NotBlank String name,
            @NotBlank String email
    ) {
    }

    public record AddExistingTeamMemberRequest(@NotNull Long ownerMemberId, @NotNull Long userId) {
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

    public record MemberQuotaResponse(
            long memberId,
            long availableTokens,
            long frozenTokens,
            long consumedTokens,
            OffsetDateTime updatedAt
    ) {
    }

    public record BillingSummary(
            long scopeId,
            long totalTokens,
            BigDecimal totalAmount,
            String currency,
            long recordCount
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

    public record ProviderSummary(
            long providerId,
            String name,
            String providerType,
            String baseUrl,
            boolean enabled,
            OffsetDateTime updatedAt
    ) {
    }

    public record ProviderListResponse(List<ProviderSummary> items, int page, int size, long total) {
    }

    public record UpsertProviderRequest(
            @NotBlank String name,
            String providerType,
            String baseUrl,
            Boolean enabled
    ) {
    }

    public record UpdateProviderRequest(String name, String providerType, String baseUrl, Boolean enabled) {
    }

    public record ProviderCredentialItem(long credentialId, long providerId, String name, String lastFour, boolean enabled, OffsetDateTime updatedAt) {
    }

    public record ProviderCredentialListResponse(List<ProviderCredentialItem> items) {
    }

    public record CreateProviderCredentialRequest(@NotBlank String name, @NotBlank String apiKey, Boolean enabled) {
    }

    public record UpdateProviderCredentialRequest(String name, String apiKey, Boolean enabled) {
    }

    public record DirectModelItem(
            long modelId,
            long providerId,
            String providerName,
            String modelName,
            boolean enabled,
            BigDecimal inputPricePerMillion,
            BigDecimal outputPricePerMillion,
            String currency
    ) {
    }

    public record DirectModelListResponse(List<DirectModelItem> items) {
    }

    public record UpsertDirectModelRequest(
            @NotNull Long providerId,
            @NotBlank String modelName,
            Boolean enabled,
            BigDecimal inputPricePerMillion,
            BigDecimal outputPricePerMillion,
            String currency
    ) {
    }

    public record DeploymentItem(
            long deploymentId,
            long providerId,
            String name,
            String actualModel,
            boolean enabled,
            BigDecimal inputPricePerMillion,
            BigDecimal outputPricePerMillion,
            String currency
    ) {
    }

    public record DeploymentListResponse(List<DeploymentItem> items) {
    }

    public record UpsertDeploymentRequest(
            @NotBlank String name,
            @NotBlank String actualModel,
            Boolean enabled,
            BigDecimal inputPricePerMillion,
            BigDecimal outputPricePerMillion,
            String currency
    ) {
    }

    public record LogicalModelItem(String logicalModel, boolean routeEnabled, String strategy, List<RouteTargetItem> targets) {
    }

    public record LogicalModelListResponse(List<LogicalModelItem> items) {
    }

    public record RouteTargetItem(long deploymentId, String deploymentName, String providerName, int weight, boolean enabled) {
    }

    public record UpsertLogicalModelRequest(@NotBlank String logicalModel, Boolean enabled, String strategy) {
    }

    public record UpsertRouteTargetRequest(long deploymentId, Integer weight, Boolean enabled) {
    }

    public record ApplicationItem(long applicationId, long organizationId, long teamId, String name, OffsetDateTime createdAt) {
    }

    public record ApplicationListResponse(List<ApplicationItem> items) {
    }

    public record CreateApplicationRequest(@NotBlank String name) {
    }

    public record TeamModelAccessResponse(long teamId, List<String> logicalModels) {
    }

    public record UpdateTeamModelAccessRequest(List<String> logicalModels) {
    }

    public record CreateTeamEntitlementRequest(
            @NotNull Long ownerMemberId,
            List<String> modelNames,
            @NotNull Long requestedTokens,
            @NotBlank String purpose,
            OffsetDateTime expiresAt
    ) {
    }

    public record ReviewTeamEntitlementRequest(@NotBlank String decision, String reviewerNote) {
    }

    public record TeamEntitlementItem(
            long requestId,
            long teamId,
            long ownerMemberId,
            List<String> modelNames,
            long requestedTokens,
            String purpose,
            OffsetDateTime expiresAt,
            String status,
            String reviewerNote,
            OffsetDateTime createdAt,
            OffsetDateTime reviewedAt
    ) {
    }

    public record TeamEntitlementListResponse(List<TeamEntitlementItem> items) {
    }

    public record GrantMemberAccessRequest(
            @NotNull Long ownerMemberId,
            @NotNull Long applicationId,
            List<String> modelNames,
            @NotNull Long tokenAllocation,
            String reason
    ) {
    }

    public record MemberAccessResponse(
            long memberId,
            long quotaAccountId,
            long availableTokens,
            List<String> modelNames,
            Long keyId,
            String keyPrefix,
            String apiKey
    ) {
    }

    public record RevokeMemberModelAccessRequest(@NotNull Long ownerMemberId) {
    }

    public record RotateMemberKeyRequest(@NotNull Long ownerMemberId) {
    }

    public record VirtualApiKeyItem(
            long keyId,
            String name,
            String keyPrefix,
            long teamId,
            String teamName,
            long applicationId,
            String applicationName,
            Long ownerMemberId,
            String ownerMemberName,
            List<String> allowedModels,
            boolean enabled,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt
    ) {
    }

    public record VirtualApiKeyListResponse(List<VirtualApiKeyItem> items, int page, int size, long total) {
    }

    public record DashboardOverview(
            int enabledProviderCount,
            int enabledTeamCount,
            int enabledKeyCount,
            long requestsLast24Hours,
            long successfulRequestsLast24Hours,
            long throttledRequestsLast24Hours,
            long frozenTokens,
            BigDecimal billingAmountLast24Hours,
            String billingCurrency,
            int globalRpm,
            int globalConcurrency
    ) {
    }

    public record UpdateGlobalRuntimePolicyRequest(Integer globalRpm, Integer globalConcurrency) {
    }
}
