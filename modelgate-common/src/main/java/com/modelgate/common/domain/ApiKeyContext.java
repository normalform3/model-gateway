package com.modelgate.common.domain;

import java.time.OffsetDateTime;
import java.util.Set;

public record ApiKeyContext(
        long keyId,
        long organizationId,
        long teamId,
        Long memberId,
        long quotaAccountId,
        Set<String> allowedModels,
        RateLimitPolicy rateLimitPolicy,
        BudgetPolicy budgetPolicy,
        boolean enabled,
        OffsetDateTime expiresAt
) {
    public boolean modelAllowed(String model) {
        return allowedModels != null && allowedModels.contains(model);
    }
}
