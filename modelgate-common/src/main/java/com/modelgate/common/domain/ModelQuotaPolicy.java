package com.modelgate.common.domain;

/** The current quota policy for one concrete model at one entitlement level. */
public record ModelQuotaPolicy(long grantId, String modelName, QuotaMode mode, Long limit) {
    public boolean limited() {
        return mode != QuotaMode.UNLIMITED;
    }
}
