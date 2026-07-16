package com.modelgate.common.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class AdminDtos {
    private AdminDtos() {
    }

    public record BootstrapDemoResponse(
            long organizationId,
            long teamId,
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
            Integer teamTpm,
            Integer keyConcurrency,
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
            Integer teamTpm,
            Integer keyConcurrency,
            Integer teamConcurrency,
            Integer modelConcurrency,
            Boolean enabled
    ) {
    }

    public record TeamSummary(
            long teamId,
            long organizationId,
            String name,
            String status,
            boolean enabled,
            int keyRpm,
            int teamRpm,
            int teamTpm,
            int keyConcurrency,
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

    /** Platform-admin variant for the development console; real RBAC will guard this operation later. */
    public record AddPlatformTeamMemberRequest(@NotNull Long userId) {
    }

    public record UpdateMemberStatusRequest(@NotNull Boolean enabled) {
    }

    public record UpdateOwnedMemberStatusRequest(@NotNull Long ownerMemberId, @NotNull Boolean enabled) {
    }

    public record TeamMemberCandidate(
            long userId,
            String name,
            String email,
            String previousTeamName,
            boolean rejoining
    ) {
    }

    public record TeamMemberCandidateListResponse(List<TeamMemberCandidate> items) {
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

    public record ProjectItem(long projectId, long teamId, String name, String projectCode, boolean enabled, OffsetDateTime createdAt) { }
    public record ProjectListResponse(List<ProjectItem> items) { }
    public record CreateProjectRequest(@NotBlank String name, @NotBlank String projectCode) { }
    public record UpdateProjectRequest(String name, Boolean enabled) { }
    public record ProjectQuotaRequest(@NotNull Long ownerMemberId, @NotNull Long tokenAllocation, List<String> modelNames, String reason) { }
    public record GrantApplicationPoolRequest(List<String> modelNames, @NotNull Long tokenAllocation, String reason) { }
    public record UpsertApplicationPoolModelRequest(@NotNull Long tokenAllocation, String reason) { }
    public record ProjectQuotaResponse(long projectId, long quotaAccountId, long availableTokens, List<String> modelNames) { }
    public record ProjectServiceAccountItem(long serviceAccountId, long projectId, String name, boolean enabled, OffsetDateTime createdAt) { }
    public record CreateProjectServiceAccountRequest(@NotBlank String name) { }
    public record UpdateProjectServiceAccountRequest(@NotNull Boolean enabled) { }
    public record ApplicationQuotaBalanceResponse(long scopeId, long availableTokens, long frozenTokens, long consumedTokens, OffsetDateTime updatedAt) { }
    public record ApplicationQuotaOverview(long teamId, ApplicationQuotaBalanceResponse balance, List<ModelEntitlementItem> modelEntitlements) { }
    public record ProjectApplicationQuotaOverview(long projectId, ApplicationQuotaBalanceResponse balance, List<ModelEntitlementItem> modelEntitlements) { }
    public record ProjectServiceAccountStatusItem(long serviceAccountId, long projectId, String name, boolean enabled, OffsetDateTime createdAt,
                                                  Long keyId, String keyPrefix, boolean keyEnabled, OffsetDateTime keyCreatedAt) { }
    public record ProjectServiceAccountListResponse(List<ProjectServiceAccountStatusItem> items) { }
    public record AttachProviderPoolCredentialRequest(@NotNull Long credentialId, @NotNull Long availableTokens) { }
    public record UpdateProviderPoolCredentialRequest(@NotNull Long availableTokens, @NotNull Boolean enabled) { }

    public record BillingSummary(
            long scopeId,
            long totalTokens,
            BigDecimal totalAmount,
            String currency,
            long recordCount
    ) {
    }

    /** Read-only filter for the platform billing workbench. */
    public record BillingQuery(
            LocalDate from,
            LocalDate to,
            Long teamId,
            Long projectId,
            Long memberId,
            String provider,
            String model,
            String credentialType,
            String currency
    ) {
    }

    /** Amounts stay per currency because ModelGate does not convert currencies. */
    public record BillingCurrencyAmount(String currency, BigDecimal amount) {
    }

    public record BillingDailyTrend(
            LocalDate day,
            long totalTokens,
            long recordCount,
            List<BillingCurrencyAmount> amounts
    ) {
    }

    /** Reusable aggregation row for team, project, member, or provider/model views. */
    public record BillingDimensionItem(
            Long id,
            Long teamId,
            String label,
            String provider,
            String model,
            long totalTokens,
            long recordCount,
            List<BillingCurrencyAmount> amounts
    ) {
    }

    public record BillingOverview(
            LocalDate from,
            LocalDate to,
            long totalTokens,
            long recordCount,
            List<BillingCurrencyAmount> amounts,
            List<BillingDailyTrend> dailyTrends,
            List<BillingDimensionItem> teams,
            List<BillingDimensionItem> projects,
            List<BillingDimensionItem> members,
            List<BillingDimensionItem> models
    ) {
    }

    public record BillingRecordItem(
            String requestId,
            Long teamId,
            String teamName,
            Long projectId,
            String projectName,
            Long memberId,
            String memberName,
            String credentialType,
            String provider,
            String model,
            int inputTokens,
            int outputTokens,
            BigDecimal inputUnitPrice,
            BigDecimal outputUnitPrice,
            BigDecimal amount,
            String currency,
            OffsetDateTime createdAt
    ) {
    }

    public record BillingRecordPage(List<BillingRecordItem> items, int page, int size, long total) {
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

    public record GrantTeamEntitlementRequest(
            List<String> modelNames,
            @NotNull Long tokenAllocation,
            String reason,
            OffsetDateTime expiresAt
    ) {
    }

    public record ReviewTeamEntitlementRequest(
            @NotBlank String decision,
            List<String> grantedModelNames,
            Long grantedTokens,
            OffsetDateTime grantedExpiresAt,
            String reviewerNote
    ) {
    }

    public record TeamEntitlementItem(
            long requestId,
            long teamId,
            long ownerMemberId,
            List<String> modelNames,
            long requestedTokens,
            List<String> grantedModelNames,
            Long grantedTokens,
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
            List<String> modelNames,
            @NotNull Long tokenAllocation,
            String reason
    ) {
    }

    public record MemberAccessResponse(
            long memberId,
            long quotaAccountId,
            long availableTokens,
            List<String> modelNames
    ) {
    }

    public record RevokeMemberModelAccessRequest(@NotNull Long ownerMemberId) {
    }

    public record MemberKeyStatusResponse(Long keyId, String keyPrefix, boolean enabled, boolean reissueRequired, OffsetDateTime createdAt) { }

    public record UpsertModelEntitlementRequest(
            @NotBlank String quotaMode,
            Long quotaLimit,
            Integer alertRemainingPercent,
            String reason,
            Long ownerMemberId
    ) { }

    public record ModelEntitlementItem(
            long grantId,
            long teamId,
            Long memberId,
            String modelName,
            String quotaMode,
            Long quotaLimit,
            Integer alertRemainingPercent,
            String status,
            long consumedTokens,
            long frozenTokens,
            Long remainingTokens,
            OffsetDateTime cycleStartedAt,
            String reason,
            OffsetDateTime createdAt,
            OffsetDateTime revokedAt
    ) { }

    public record ModelEntitlementListResponse(List<ModelEntitlementItem> items) { }

    public record UsageTrendItem(String day, long tokens, BigDecimal amount, long requests) { }

    public record MemberUsageRankItem(long memberId, String memberName, long tokens, BigDecimal amount) { }

    public record UsageDashboard(
            long scopeId,
            List<ModelEntitlementItem> modelEntitlements,
            List<UsageTrendItem> lastSevenDays,
            List<MemberUsageRankItem> memberRanking
    ) { }

    public record QuotaSummaryItem(
            String modelName,
            String quotaMode,
            int teamCount,
            long allocatedTokens,
            long consumedTokens,
            long frozenTokens,
            long remainingTokens
    ) { }

    public record QuotaSummary(
            long allocatedTokens,
            long consumedTokens,
            long frozenTokens,
            long remainingTokens,
            int unlimitedEntitlementCount,
            List<QuotaSummaryItem> items
    ) { }

    public record VirtualApiKeyItem(
            long keyId,
            String name,
            String keyPrefix,
            long teamId,
            String teamName,
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
