package com.modelgate.common.domain;

public record RouteTarget(
        long deploymentId,
        String logicalModel,
        String provider,
        String actualModel,
        long providerId,
        String providerType,
        String baseUrl
) {
    public RouteTarget(long deploymentId, String logicalModel, String provider, String actualModel) {
        this(deploymentId, logicalModel, provider, actualModel, 0L, "MOCK_OPENAI", null);
    }
}
