package com.modelgate.auth;

import com.modelgate.common.api.AdminDtos.CreateApiKeyResponse;
import com.modelgate.common.api.AdminDtos.GrantMemberAccessRequest;
import com.modelgate.common.api.AdminDtos.MemberAccessResponse;
import com.modelgate.infrastructure.db.AdminRepository;
import com.modelgate.infrastructure.db.TeamEntitlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates an owner allocation with system-owned virtual-key generation. */
@Service
public class TeamAccessService {
    private final TeamEntitlementRepository entitlementRepository;
    private final AdminRepository adminRepository;
    private final VirtualKeyService virtualKeyService;

    public TeamAccessService(TeamEntitlementRepository entitlementRepository, AdminRepository adminRepository, VirtualKeyService virtualKeyService) {
        this.entitlementRepository = entitlementRepository;
        this.adminRepository = adminRepository;
        this.virtualKeyService = virtualKeyService;
    }

    @Transactional
    public MemberAccessResponse grant(long teamId, long memberId, GrantMemberAccessRequest request) {
        TeamEntitlementRepository.MemberAccessSnapshot access = entitlementRepository.grantMemberAccess(teamId, memberId, request);
        Long existingKeyId = adminRepository.findActiveMemberKeyId(teamId, memberId, request.applicationId()).orElse(null);
        CreateApiKeyResponse key = existingKeyId == null
                ? virtualKeyService.provisionSystemMemberKey(teamId, memberId, request.applicationId())
                : null;
        virtualKeyService.invalidateMember(memberId);
        virtualKeyService.invalidateQuotaAccount(access.quotaAccountId());
        return new MemberAccessResponse(access.memberId(), access.quotaAccountId(), access.availableTokens(), access.models(),
                key == null ? existingKeyId : key.keyId(), key == null ? null : key.keyPrefix(), key == null ? null : key.apiKey());
    }

    @Transactional
    public CreateApiKeyResponse rotate(long teamId, long memberId, long applicationId, long ownerMemberId) {
        entitlementRepository.assertOwner(teamId, ownerMemberId);
        if (entitlementRepository.memberModels(memberId).isEmpty()) {
            throw new com.modelgate.common.error.ModelGateException(com.modelgate.common.error.ErrorCode.BAD_MODEL_REQUEST,
                    "Grant the member at least one model before rotating their Key.");
        }
        adminRepository.findActiveMemberKeyId(teamId, memberId, applicationId).ifPresent(keyId -> virtualKeyService.disable(keyId));
        return virtualKeyService.provisionSystemMemberKey(teamId, memberId, applicationId);
    }
}
