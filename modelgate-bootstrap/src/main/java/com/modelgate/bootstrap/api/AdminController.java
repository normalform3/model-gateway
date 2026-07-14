package com.modelgate.bootstrap.api;

import com.modelgate.auth.VirtualKeyService;
import com.modelgate.auth.TeamAccessService;
import com.modelgate.auth.ProviderCredentialCipher;
import com.modelgate.common.api.AdminDtos.*;
import com.modelgate.infrastructure.db.AdminControlRepository;
import com.modelgate.infrastructure.db.AdminRepository;
import com.modelgate.infrastructure.db.QuotaAccountRepository;
import com.modelgate.infrastructure.db.UserRepository;
import com.modelgate.infrastructure.db.ProviderCatalogRepository;
import com.modelgate.infrastructure.db.TeamEntitlementRepository;
import com.modelgate.infrastructure.db.BillingRepository;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


@RestController
@RequestMapping(path = "/admin", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminController {
    private final AdminRepository adminRepository;
    private final QuotaAccountRepository quotaAccountRepository;
    private final VirtualKeyService virtualKeyService;
    private final AdminControlRepository adminControlRepository;
    private final UserRepository userRepository;
    private final ProviderCatalogRepository providerCatalogRepository;
    private final ProviderCredentialCipher providerCredentialCipher;
    private final TeamEntitlementRepository teamEntitlementRepository;
    private final TeamAccessService teamAccessService;
    private final BillingRepository billingRepository;

    public AdminController(
            AdminRepository adminRepository,
            QuotaAccountRepository quotaAccountRepository,
            VirtualKeyService virtualKeyService,
            AdminControlRepository adminControlRepository,
            UserRepository userRepository,
            ProviderCatalogRepository providerCatalogRepository,
            ProviderCredentialCipher providerCredentialCipher,
            TeamEntitlementRepository teamEntitlementRepository,
            TeamAccessService teamAccessService,
            BillingRepository billingRepository
    ) {
        this.adminRepository = adminRepository;
        this.quotaAccountRepository = quotaAccountRepository;
        this.virtualKeyService = virtualKeyService;
        this.adminControlRepository = adminControlRepository;
        this.userRepository = userRepository;
        this.providerCatalogRepository = providerCatalogRepository;
        this.providerCredentialCipher = providerCredentialCipher;
        this.teamEntitlementRepository = teamEntitlementRepository;
        this.teamAccessService = teamAccessService;
        this.billingRepository = billingRepository;
    }

    @PostMapping("/bootstrap/demo")
    public Mono<BootstrapDemoResponse> bootstrapDemo() {
        return Mono.fromCallable(adminRepository::bootstrapDemo).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/demo-identities")
    public Mono<DemoIdentityResponse> demoIdentities() {
        return Mono.fromCallable(adminRepository::demoIdentities).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/users")
    public Mono<UserListResponse> users(
            @org.springframework.web.bind.annotation.RequestParam(name = "role", required = false) String role,
            @org.springframework.web.bind.annotation.RequestParam(name = "enabledOnly", defaultValue = "false") boolean enabledOnly
    ) {
        return Mono.fromCallable(() -> userRepository.list(role, enabledOnly)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/users")
    public Mono<UserItem> createUser(@Valid @RequestBody CreateUserRequest request) {
        return Mono.fromCallable(() -> userRepository.create(request)).subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/users/{userId}")
    public Mono<UserItem> updateUser(@PathVariable("userId") long userId, @RequestBody UpdateUserRequest request) {
        return Mono.fromCallable(() -> {
            UserItem user = userRepository.update(userId, request);
            if (user.memberId() != null) virtualKeyService.invalidateMember(user.memberId());
            return user;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @org.springframework.web.bind.annotation.PutMapping("/users/{userId}/team-membership")
    public Mono<UserItem> assignUserMembership(@PathVariable("userId") long userId, @Valid @RequestBody TeamMembershipRequest request) {
        return Mono.error(new com.modelgate.common.error.ModelGateException(
                com.modelgate.common.error.ErrorCode.BAD_MODEL_REQUEST,
                "Use the team owner assignment flow or the owner-led member endpoint instead of direct membership changes."));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/users/{userId}")
    public Mono<org.springframework.http.ResponseEntity<Void>> deleteUser(@PathVariable("userId") long userId) {
        return Mono.fromRunnable(() -> virtualKeyService.invalidateKeyHashes(userRepository.delete(userId).apiKeyHashes())).subscribeOn(Schedulers.boundedElastic())
                .thenReturn(org.springframework.http.ResponseEntity.noContent().build());
    }

    @PostMapping("/teams")
    public Mono<TeamSummary> createTeam(@Valid @RequestBody CreateTeamRequest request) {
        return Mono.fromCallable(() -> adminRepository.createTeam(request)).subscribeOn(Schedulers.boundedElastic());
    }

    @org.springframework.web.bind.annotation.PutMapping("/teams/{teamId}/owner")
    public Mono<TeamSummary> setTeamOwner(@PathVariable("teamId") long teamId, @RequestBody SetTeamOwnerRequest request) {
        return Mono.fromCallable(() -> {
            TeamSummary team = adminRepository.setTeamOwner(teamId, request.ownerUserId());
            virtualKeyService.invalidateTeam(teamId);
            return team;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/teams")
    public Mono<TeamListResponse> teams(
            @org.springframework.web.bind.annotation.RequestParam(name = "keyword", required = false) String keyword,
            @org.springframework.web.bind.annotation.RequestParam(name = "enabled", required = false) Boolean enabled,
            @org.springframework.web.bind.annotation.RequestParam(name = "logicalModel", required = false) String logicalModel,
            @org.springframework.web.bind.annotation.RequestParam(name = "ownerUserId", required = false) Long ownerUserId,
            @org.springframework.web.bind.annotation.RequestParam(name = "page", defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(name = "size", defaultValue = "20") int size) {
        return Mono.fromCallable(() -> adminRepository.listTeams(keyword, enabled, logicalModel, ownerUserId, safePage(page), safeSize(size))).subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/teams/{teamId}")
    public Mono<TeamSummary> updateTeam(@PathVariable("teamId") long teamId, @RequestBody UpdateTeamRequest request) {
        return Mono.fromCallable(() -> {
            TeamSummary team = adminRepository.updateTeam(teamId, request);
            virtualKeyService.invalidateTeam(teamId);
            return team;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/teams/{teamId}")
    public Mono<org.springframework.http.ResponseEntity<Void>> deleteTeam(@PathVariable("teamId") long teamId) {
        return Mono.fromRunnable(() -> {
            UserRepository.DeletedTeam deletedTeam = userRepository.deleteTeam(teamId);
            virtualKeyService.invalidateKeyHashes(deletedTeam.apiKeyHashes());
            deletedTeam.quotaAccountIds().forEach(virtualKeyService::invalidateQuotaAccount);
        }).subscribeOn(Schedulers.boundedElastic())
                .thenReturn(org.springframework.http.ResponseEntity.noContent().build());
    }

    @GetMapping("/teams/{teamId}/members")
    public Mono<TeamMemberListResponse> teamMembers(@PathVariable("teamId") long teamId) {
        return Mono.fromCallable(() -> adminRepository.listTeamMembers(teamId)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/teams/{teamId}/members")
    public Mono<TeamMemberItem> createTeamMember(
            @PathVariable("teamId") long teamId,
            @Valid @RequestBody CreateTeamMemberRequest request
    ) {
        return Mono.error(new com.modelgate.common.error.ModelGateException(
                com.modelgate.common.error.ErrorCode.BAD_MODEL_REQUEST,
                "A team owner must add an existing platform user through the delegated membership endpoint."));
    }

    @PostMapping("/teams/{teamId}/members/from-user")
    public Mono<TeamMemberItem> addExistingTeamMember(
            @PathVariable("teamId") long teamId,
            @Valid @RequestBody AddExistingTeamMemberRequest request
    ) {
        return Mono.fromCallable(() -> teamEntitlementRepository.addMember(teamId, request.ownerMemberId(), request.userId()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/teams/{teamId}/entitlement-requests")
    public Mono<TeamEntitlementItem> requestTeamEntitlement(
            @PathVariable("teamId") long teamId,
            @Valid @RequestBody CreateTeamEntitlementRequest request
    ) {
        return Mono.fromCallable(() -> teamEntitlementRepository.request(teamId, request)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/teams/{teamId}/entitlement-requests")
    public Mono<TeamEntitlementListResponse> teamEntitlementRequests(@PathVariable("teamId") long teamId) {
        return Mono.fromCallable(() -> teamEntitlementRepository.requests(teamId)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/entitlement-requests")
    public Mono<TeamEntitlementListResponse> entitlementRequests(@org.springframework.web.bind.annotation.RequestParam(name = "status", required = false) String status) {
        return Mono.fromCallable(() -> teamEntitlementRepository.allRequests(status)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/teams/{teamId}/entitlements")
    public Mono<org.springframework.http.ResponseEntity<Void>> grantTeamEntitlement(@PathVariable("teamId") long teamId, @Valid @RequestBody GrantTeamEntitlementRequest request) {
        return Mono.fromRunnable(() -> { teamEntitlementRepository.grantTeam(teamId, request); virtualKeyService.invalidateTeam(teamId); }).subscribeOn(Schedulers.boundedElastic()).thenReturn(org.springframework.http.ResponseEntity.noContent().build());
    }

    @PostMapping("/entitlement-requests/{requestId}/review")
    public Mono<TeamEntitlementItem> reviewTeamEntitlement(
            @PathVariable("requestId") long requestId,
            @Valid @RequestBody ReviewTeamEntitlementRequest request
    ) {
        return Mono.fromCallable(() -> {
            TeamEntitlementItem reviewed = teamEntitlementRepository.review(requestId, request);
            virtualKeyService.invalidateTeam(reviewed.teamId());
            return reviewed;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/teams/{teamId}/members/{memberId}/access")
    public Mono<MemberAccessResponse> grantMemberAccess(
            @PathVariable("teamId") long teamId,
            @PathVariable("memberId") long memberId,
            @Valid @RequestBody GrantMemberAccessRequest request
    ) {
        return Mono.fromCallable(() -> teamAccessService.grant(teamId, memberId, request)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/members/{memberId}/api-key-status")
    public Mono<MemberKeyStatusResponse> memberKeyStatus(@PathVariable("memberId") long memberId) {
        return Mono.fromCallable(() -> adminControlRepository.memberKeyStatus(memberId)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/members/{memberId}/api-keys/generate")
    public Mono<CreateApiKeyResponse> generateMemberKey(@PathVariable("memberId") long memberId) {
        return Mono.fromCallable(() -> teamAccessService.generate(memberId, false)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/members/{memberId}/api-keys/rotate")
    public Mono<CreateApiKeyResponse> rotateMemberKey(@PathVariable("memberId") long memberId) {
        return Mono.fromCallable(() -> teamAccessService.generate(memberId, true)).subscribeOn(Schedulers.boundedElastic());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/teams/{teamId}/members/{memberId}/model-access/{modelName}")
    public Mono<org.springframework.http.ResponseEntity<Void>> revokeMemberModelAccess(
            @PathVariable("teamId") long teamId,
            @PathVariable("memberId") long memberId,
            @PathVariable("modelName") String modelName,
            @Valid @RequestBody RevokeMemberModelAccessRequest request
    ) {
        return Mono.fromRunnable(() -> {
            teamEntitlementRepository.revokeMemberModel(teamId, memberId, request.ownerMemberId(), modelName);
            virtualKeyService.invalidateMember(memberId);
        }).subscribeOn(Schedulers.boundedElastic()).thenReturn(org.springframework.http.ResponseEntity.noContent().build());
    }

    @PatchMapping("/teams/{teamId}/members/{memberId}")
    public Mono<TeamMemberItem> updateTeamMember(
            @PathVariable("teamId") long teamId,
            @PathVariable("memberId") long memberId,
            @RequestBody UpdateTeamMemberRequest request
    ) {
        return Mono.error(new com.modelgate.common.error.ModelGateException(
                com.modelgate.common.error.ErrorCode.BAD_MODEL_REQUEST,
                "Team roles are managed through the team owner assignment flow."));
    }

    @PostMapping("/api-keys/{keyId}/disable")
    public Mono<DisableApiKeyResponse> disableApiKey(@PathVariable("keyId") long keyId) {
        return Mono.fromCallable(() -> {
            virtualKeyService.disable(keyId);
            return new DisableApiKeyResponse(keyId, false);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/teams/{teamId}/quota")
    public Mono<QuotaResponse> quota(@PathVariable("teamId") long teamId) {
        return Mono.fromCallable(() -> quotaAccountRepository.findTeamQuota(teamId)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/members/{memberId}/quota")
    public Mono<MemberQuotaResponse> memberQuota(@PathVariable("memberId") long memberId) {
        return Mono.fromCallable(() -> quotaAccountRepository.findMemberQuota(memberId)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/teams/{teamId}/billing-summary")
    public Mono<BillingSummary> teamBillingSummary(@PathVariable("teamId") long teamId) {
        return Mono.fromCallable(() -> billingRepository.teamSummary(teamId)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/members/{memberId}/billing-summary")
    public Mono<BillingSummary> memberBillingSummary(@PathVariable("memberId") long memberId) {
        return Mono.fromCallable(() -> billingRepository.memberSummary(memberId)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/providers")
    public Mono<ProviderListResponse> providers(
            @org.springframework.web.bind.annotation.RequestParam(name = "keyword", required = false) String keyword,
            @org.springframework.web.bind.annotation.RequestParam(name = "providerType", required = false) String providerType,
            @org.springframework.web.bind.annotation.RequestParam(name = "enabled", required = false) Boolean enabled,
            @org.springframework.web.bind.annotation.RequestParam(name = "page", defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(name = "size", defaultValue = "20") int size) {
        return Mono.fromCallable(() -> adminControlRepository.listProviders(keyword, providerType, enabled, safePage(page), safeSize(size))).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/providers")
    public Mono<ProviderSummary> createProvider(@Valid @RequestBody UpsertProviderRequest request) {
        return Mono.fromCallable(() -> adminControlRepository.createProvider(request)).subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/providers/{providerId}")
    public Mono<ProviderSummary> updateProvider(@PathVariable("providerId") long providerId, @RequestBody UpdateProviderRequest request) {
        return Mono.fromCallable(() -> adminControlRepository.updateProvider(providerId, request)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/providers/{providerId}/credentials")
    public Mono<ProviderCredentialListResponse> providerCredentials(@PathVariable("providerId") long providerId) {
        return Mono.fromCallable(() -> providerCatalogRepository.credentials(providerId)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/providers/{providerId}/credentials")
    public Mono<ProviderCredentialItem> createProviderCredential(@PathVariable("providerId") long providerId, @Valid @RequestBody CreateProviderCredentialRequest request) {
        return Mono.fromCallable(() -> {
            ProviderCredentialCipher.EncryptedCredential encrypted = providerCredentialCipher.encrypt(request.apiKey());
            return providerCatalogRepository.createCredential(providerId, request.name(), encrypted.ciphertext(), encrypted.version(), encrypted.lastFour(), request.enabled() == null || request.enabled());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/provider-credentials/{credentialId}")
    public Mono<ProviderCredentialItem> updateProviderCredential(@PathVariable("credentialId") long credentialId, @RequestBody UpdateProviderCredentialRequest request) {
        return Mono.fromCallable(() -> {
            ProviderCredentialCipher.EncryptedCredential encrypted = request.apiKey() == null || request.apiKey().isBlank() ? null : providerCredentialCipher.encrypt(request.apiKey());
            return providerCatalogRepository.updateCredential(credentialId, request.name(), encrypted == null ? null : encrypted.ciphertext(), encrypted == null ? null : encrypted.version(), encrypted == null ? null : encrypted.lastFour(), request.enabled());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/provider-credentials/{credentialId}/disable")
    public Mono<org.springframework.http.ResponseEntity<Void>> disableProviderCredential(@PathVariable("credentialId") long credentialId) {
        return Mono.fromRunnable(() -> providerCatalogRepository.disableCredential(credentialId)).subscribeOn(Schedulers.boundedElastic())
                .thenReturn(org.springframework.http.ResponseEntity.noContent().build());
    }

    @GetMapping("/models")
    public Mono<DirectModelListResponse> directModels() {
        return Mono.fromCallable(providerCatalogRepository::models).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/models")
    public Mono<DirectModelItem> createDirectModel(@Valid @RequestBody UpsertDirectModelRequest request) {
        return Mono.fromCallable(() -> providerCatalogRepository.createModel(request)).subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/models/{modelId}")
    public Mono<DirectModelItem> updateDirectModel(@PathVariable("modelId") long modelId, @Valid @RequestBody UpsertDirectModelRequest request) {
        return Mono.fromCallable(() -> providerCatalogRepository.updateModel(modelId, request)).subscribeOn(Schedulers.boundedElastic());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/models/{modelId}")
    public Mono<org.springframework.http.ResponseEntity<Void>> deleteDirectModel(@PathVariable("modelId") long modelId) {
        return Mono.fromRunnable(() -> providerCatalogRepository.deleteModel(modelId)).subscribeOn(Schedulers.boundedElastic())
                .thenReturn(org.springframework.http.ResponseEntity.noContent().build());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/providers/{providerId}")
    public Mono<org.springframework.http.ResponseEntity<Void>> deleteProvider(@PathVariable("providerId") long providerId) {
        return Mono.fromRunnable(() -> adminControlRepository.deleteProvider(providerId))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(org.springframework.http.ResponseEntity.noContent().build());
    }

    @GetMapping("/providers/{providerId}/deployments")
    public Mono<DeploymentListResponse> deployments(@PathVariable("providerId") long providerId) {
        return Mono.fromCallable(() -> adminControlRepository.listDeployments(providerId)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/providers/{providerId}/deployments")
    public Mono<DeploymentItem> createDeployment(@PathVariable("providerId") long providerId, @Valid @RequestBody UpsertDeploymentRequest request) {
        return Mono.fromCallable(() -> adminControlRepository.createDeployment(providerId, request)).subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/deployments/{deploymentId}")
    public Mono<DeploymentItem> updateDeployment(@PathVariable("deploymentId") long deploymentId, @RequestBody UpsertDeploymentRequest request) {
        return Mono.fromCallable(() -> adminControlRepository.updateDeployment(deploymentId, request)).subscribeOn(Schedulers.boundedElastic());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/deployments/{deploymentId}")
    public Mono<org.springframework.http.ResponseEntity<Void>> deleteDeployment(@PathVariable("deploymentId") long deploymentId) {
        return Mono.fromRunnable(() -> adminControlRepository.deleteDeployment(deploymentId))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(org.springframework.http.ResponseEntity.noContent().build());
    }

    @GetMapping("/logical-models")
    public Mono<LogicalModelListResponse> logicalModels() {
        return Mono.fromCallable(adminControlRepository::listLogicalModels).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/logical-models")
    public Mono<LogicalModelItem> upsertLogicalModel(@Valid @RequestBody UpsertLogicalModelRequest request) {
        return Mono.fromCallable(() -> adminControlRepository.upsertLogicalModel(request)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/logical-models/{logicalModel}/targets")
    public Mono<LogicalModelItem> upsertRouteTarget(@PathVariable("logicalModel") String logicalModel, @RequestBody UpsertRouteTargetRequest request) {
        return Mono.fromCallable(() -> adminControlRepository.upsertRouteTarget(logicalModel, request)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/teams/{teamId}/model-access")
    public Mono<TeamModelAccessResponse> teamModelAccess(@PathVariable("teamId") long teamId) {
        return Mono.fromCallable(() -> adminControlRepository.teamModels(teamId)).subscribeOn(Schedulers.boundedElastic());
    }

    @org.springframework.web.bind.annotation.PutMapping("/teams/{teamId}/model-access")
    public Mono<TeamModelAccessResponse> updateTeamModelAccess(@PathVariable("teamId") long teamId, @RequestBody UpdateTeamModelAccessRequest request) {
        return Mono.error(new com.modelgate.common.error.ModelGateException(
                com.modelgate.common.error.ErrorCode.BAD_MODEL_REQUEST,
                "Approve a team entitlement request instead of directly changing team model access."));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/teams/{teamId}/model-access/{modelName}")
    public Mono<org.springframework.http.ResponseEntity<Void>> revokeTeamModelAccess(
            @PathVariable("teamId") long teamId,
            @PathVariable("modelName") String modelName
    ) {
        return Mono.fromRunnable(() -> {
            teamEntitlementRepository.revokeTeamModel(teamId, modelName);
            virtualKeyService.invalidateTeam(teamId);
        }).subscribeOn(Schedulers.boundedElastic()).thenReturn(org.springframework.http.ResponseEntity.noContent().build());
    }

    @GetMapping("/api-keys")
    public Mono<VirtualApiKeyListResponse> apiKeys(
            @org.springframework.web.bind.annotation.RequestParam(name = "keyword", required = false) String keyword,
            @org.springframework.web.bind.annotation.RequestParam(name = "teamId", required = false) Long teamId,
            @org.springframework.web.bind.annotation.RequestParam(name = "memberId", required = false) Long memberId,
            @org.springframework.web.bind.annotation.RequestParam(name = "enabled", required = false) Boolean enabled,
            @org.springframework.web.bind.annotation.RequestParam(name = "expiry", required = false) String expiry,
            @org.springframework.web.bind.annotation.RequestParam(name = "page", defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(name = "size", defaultValue = "20") int size) {
        return Mono.fromCallable(() -> adminControlRepository.listKeys(keyword, teamId, memberId, enabled, expiry, safePage(page), safeSize(size))).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/dashboard/overview")
    public Mono<DashboardOverview> dashboard() {
        return Mono.fromCallable(adminControlRepository::dashboard).subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/dashboard/runtime-policy")
    public Mono<DashboardOverview> updateRuntimePolicy(@RequestBody UpdateGlobalRuntimePolicyRequest request) {
        return Mono.fromCallable(() -> adminControlRepository.updateGlobalPolicy(request)).subscribeOn(Schedulers.boundedElastic());
    }

    private int safePage(int page) { return Math.max(0, page); }

    private int safeSize(int size) { return Math.min(100, Math.max(1, size)); }
}
