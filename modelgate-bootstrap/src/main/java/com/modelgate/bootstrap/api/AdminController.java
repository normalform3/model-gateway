package com.modelgate.bootstrap.api;

import com.modelgate.auth.VirtualKeyService;
import com.modelgate.common.api.AdminDtos.BootstrapDemoResponse;
import com.modelgate.common.api.AdminDtos.CreateMemberApiKeyRequest;
import com.modelgate.common.api.AdminDtos.CreateApiKeyRequest;
import com.modelgate.common.api.AdminDtos.CreateApiKeyResponse;
import com.modelgate.common.api.AdminDtos.CreateTeamMemberRequest;
import com.modelgate.common.api.AdminDtos.CreateTeamRequest;
import com.modelgate.common.api.AdminDtos.DisableApiKeyResponse;
import com.modelgate.common.api.AdminDtos.QuotaResponse;
import com.modelgate.common.api.AdminDtos.RequestLogResponse;
import com.modelgate.common.api.AdminDtos.TeamListResponse;
import com.modelgate.common.api.AdminDtos.TeamMemberItem;
import com.modelgate.common.api.AdminDtos.TeamMemberListResponse;
import com.modelgate.common.api.AdminDtos.TeamSummary;
import com.modelgate.common.api.AdminDtos.UpdateTeamMemberRequest;
import com.modelgate.common.api.AdminDtos.UpdateTeamRequest;
import com.modelgate.infrastructure.db.AdminRepository;
import com.modelgate.infrastructure.db.QuotaAccountRepository;
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

    public AdminController(
            AdminRepository adminRepository,
            QuotaAccountRepository quotaAccountRepository,
            VirtualKeyService virtualKeyService
    ) {
        this.adminRepository = adminRepository;
        this.quotaAccountRepository = quotaAccountRepository;
        this.virtualKeyService = virtualKeyService;
    }

    @PostMapping("/bootstrap/demo")
    public Mono<BootstrapDemoResponse> bootstrapDemo() {
        return Mono.fromCallable(adminRepository::bootstrapDemo).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/api-keys")
    public Mono<CreateApiKeyResponse> createApiKey(@Valid @RequestBody CreateApiKeyRequest request) {
        return Mono.fromCallable(() -> virtualKeyService.create(request)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/teams")
    public Mono<TeamSummary> createTeam(@Valid @RequestBody CreateTeamRequest request) {
        return Mono.fromCallable(() -> adminRepository.createTeam(request)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/teams")
    public Mono<TeamListResponse> teams() {
        return Mono.fromCallable(adminRepository::listTeams).subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/teams/{teamId}")
    public Mono<TeamSummary> updateTeam(@PathVariable long teamId, @RequestBody UpdateTeamRequest request) {
        return Mono.fromCallable(() -> adminRepository.updateTeam(teamId, request)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/teams/{teamId}/members")
    public Mono<TeamMemberListResponse> teamMembers(@PathVariable long teamId) {
        return Mono.fromCallable(() -> adminRepository.listTeamMembers(teamId)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/teams/{teamId}/members")
    public Mono<TeamMemberItem> createTeamMember(
            @PathVariable long teamId,
            @Valid @RequestBody CreateTeamMemberRequest request
    ) {
        return Mono.fromCallable(() -> adminRepository.createTeamMember(teamId, request)).subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/teams/{teamId}/members/{memberId}")
    public Mono<TeamMemberItem> updateTeamMember(
            @PathVariable long teamId,
            @PathVariable long memberId,
            @RequestBody UpdateTeamMemberRequest request
    ) {
        return Mono.fromCallable(() -> adminRepository.updateTeamMember(teamId, memberId, request)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/teams/{teamId}/members/{memberId}/api-keys")
    public Mono<CreateApiKeyResponse> createMemberApiKey(
            @PathVariable long teamId,
            @PathVariable long memberId,
            @Valid @RequestBody CreateMemberApiKeyRequest request
    ) {
        return Mono.fromCallable(() -> virtualKeyService.createForMember(teamId, memberId, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/api-keys/{keyId}/disable")
    public Mono<DisableApiKeyResponse> disableApiKey(@PathVariable long keyId) {
        return Mono.fromCallable(() -> {
            virtualKeyService.disable(keyId);
            return new DisableApiKeyResponse(keyId, false);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/applications/{applicationId}/requests")
    public Mono<RequestLogResponse> requests(@PathVariable long applicationId) {
        return Mono.fromCallable(() -> adminRepository.findRequestsByApplication(applicationId, 100))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/teams/{teamId}/quota")
    public Mono<QuotaResponse> quota(@PathVariable long teamId) {
        return Mono.fromCallable(() -> quotaAccountRepository.findTeamQuota(teamId)).subscribeOn(Schedulers.boundedElastic());
    }
}
