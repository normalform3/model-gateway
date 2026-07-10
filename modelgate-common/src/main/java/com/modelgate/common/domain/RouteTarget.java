package com.modelgate.common.domain;

public record RouteTarget(
        long deploymentId,
        String logicalModel,
        String provider,
        String actualModel
) {
}
