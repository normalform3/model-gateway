package com.modelgate.auth;

import com.modelgate.common.api.AdminDtos.CreateApiKeyResponse;
import com.modelgate.common.api.AdminDtos.GrantMemberAccessRequest;
import com.modelgate.common.api.AdminDtos.MemberAccessResponse;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.infrastructure.db.AdminRepository;
import com.modelgate.infrastructure.db.TeamEntitlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owners allocate access; developers generate their own credential afterwards. */
@Service
public class TeamAccessService {
    private final TeamEntitlementRepository entitlements;
    private final AdminRepository adminRepository;
    private final VirtualKeyService virtualKeyService;

    public TeamAccessService(TeamEntitlementRepository entitlements, AdminRepository adminRepository, VirtualKeyService virtualKeyService) {
        this.entitlements = entitlements;
        this.adminRepository = adminRepository;
        this.virtualKeyService = virtualKeyService;
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
        if (scope.availableTokens() <= 0 || scope.models().isEmpty()) {
            throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "A positive quota and at least one granted model are required before generating a Key.");
        }
        adminRepository.findActiveMemberKeyId(scope.teamId(), memberId).ifPresent(keyId -> {
            if (!rotate) throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "An active Key already exists. Rotate it instead.");
            virtualKeyService.disable(keyId);
        });
        return virtualKeyService.provisionMemberKey(scope.teamId(), memberId);
    }
}
