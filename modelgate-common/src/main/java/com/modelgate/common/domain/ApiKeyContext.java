package com.modelgate.common.domain;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

public record ApiKeyContext(
        long keyId,
        long organizationId,
        long teamId,
        Long memberId,
        long quotaAccountId,
        Set<String> allowedModels,
        Map<String, ModelQuotaPolicy> teamModelQuotas,
        Map<String, ModelQuotaPolicy> memberModelQuotas,
        RateLimitPolicy rateLimitPolicy,
        BudgetPolicy budgetPolicy,
        boolean enabled,
        OffsetDateTime expiresAt
) {
    public boolean modelAllowed(String model) {
        return allowedModels != null && allowedModels.contains(model);
    }

    public ModelQuotaPolicy teamQuota(String model) { return teamModelQuotas.get(model); }
    public ModelQuotaPolicy memberQuota(String model) { return memberModelQuotas.get(model); }
}
