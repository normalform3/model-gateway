package com.modelgate.auth;

import com.modelgate.common.api.AdminDtos.CreateApiKeyResponse;
import com.modelgate.common.api.AdminDtos.GrantMemberAccessRequest;
import com.modelgate.common.api.AdminDtos.MemberAccessResponse;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.infrastructure.db.AdminRepository;
import com.modelgate.infrastructure.db.TeamEntitlementRepository;
import com.modelgate.infrastructure.db.ModelEntitlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owners allocate access; developers generate their own credential afterwards. */
@Service
public class TeamAccessService {
    private final TeamEntitlementRepository entitlements;
    private final AdminRepository adminRepository;
    private final VirtualKeyService virtualKeyService;
    private final ModelEntitlementRepository modelEntitlements;

    public TeamAccessService(TeamEntitlementRepository entitlements, AdminRepository adminRepository, VirtualKeyService virtualKeyService, ModelEntitlementRepository modelEntitlements) {
        this.entitlements = entitlements;
        this.adminRepository = adminRepository;
        this.virtualKeyService = virtualKeyService;
        this.modelEntitlements = modelEntitlements;
    }

    @Transactional
    public MemberAccessResponse grant(long teamId, long memberId, GrantMemberAccessRequest request) {
        TeamEntitlementRepository.MemberAccessSnapshot access = entitlements.grantMemberAccess(teamId, memberId, request);
        virtualKeyService.invalidateMember(memberId);
        virtualKeyService.invalidateQuotaAccount(access.quotaAccountId());
        return new MemberAccessResponse(access.memberId(), access.quotaAccountId(), access.availableTokens(), access.models());
    }

    @Transactional
    public CreateApiKeyResponse generate(long memberId, boolean rotate) {
        TeamEntitlementRepository.MemberKeyScope scope = entitlements.lockMemberForKey(memberId);
        if (modelEntitlements.runtimePolicies(scope.teamId(), memberId).member().isEmpty()) {
            throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "At least one granted model is required before generating a Key.");
        }
        adminRepository.findActiveMemberKeyId(scope.teamId(), memberId).ifPresent(keyId -> {
            if (!rotate) throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "An active Key already exists. Rotate it instead.");
            virtualKeyService.disable(keyId);
        });
        return virtualKeyService.provisionMemberKey(scope.teamId(), memberId);
    }
}
