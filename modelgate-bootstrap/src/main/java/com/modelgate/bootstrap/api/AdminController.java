package com.modelgate.bootstrap.api;

import com.modelgate.auth.VirtualKeyService;
import com.modelgate.common.api.AdminDtos.BootstrapDemoResponse;
import com.modelgate.common.api.AdminDtos.CreateApiKeyRequest;
import com.modelgate.common.api.AdminDtos.CreateApiKeyResponse;
import com.modelgate.common.api.AdminDtos.DisableApiKeyResponse;
import com.modelgate.common.api.AdminDtos.QuotaResponse;
import com.modelgate.common.api.AdminDtos.RequestLogResponse;
import com.modelgate.infrastructure.db.AdminRepository;
import com.modelgate.infrastructure.db.QuotaAccountRepository;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
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
