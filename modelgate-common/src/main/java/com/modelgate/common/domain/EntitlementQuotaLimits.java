package com.modelgate.common.domain;

import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;

/**
 * Product guardrails for periodic entitlement grants. Provider throughput remains
 * controlled separately by the configured rate/concurrency policies.
 */
public final class EntitlementQuotaLimits {
    public static final long MEMBER_MAX_TOKENS = 99_900_000_000L; // 999 亿
    public static final long TEAM_MAX_TOKENS = 999_000_000_000_000L; // 999 万亿

    private EntitlementQuotaLimits() {
    }

    public static void validateTeam(QuotaMode mode, Long quotaLimit) {
        validate(mode, quotaLimit, TEAM_MAX_TOKENS, "Team");
    }

    public static void validateMember(QuotaMode mode, Long quotaLimit) {
        validate(mode, quotaLimit, MEMBER_MAX_TOKENS, "Member");
    }

    private static void validate(QuotaMode mode, Long quotaLimit, long maximum, String scope) {
        if (mode == QuotaMode.UNLIMITED) {
            if (quotaLimit != null) throw bad("UNLIMITED quota must not specify quotaLimit.");
            return;
        }
        if (quotaLimit == null || quotaLimit <= 0) throw bad("Periodic quota requires a positive quotaLimit.");
        if (quotaLimit > maximum) {
            throw bad(scope + " periodic quota must not exceed " + maximum + " tokens.");
        }
    }

    private static ModelGateException bad(String message) {
        return new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, message);
    }
}
