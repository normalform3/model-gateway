package com.modelgate.common.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Development-only contract consumed by the standalone Mock load-test runner. */
public final class TestObservabilityDtos {
    private TestObservabilityDtos() {
    }

    public record MockModelItem(String modelName) {
    }

    public record MockModelListResponse(List<MockModelItem> items) {
    }

    public record TestCaller(long memberId, String memberName, long teamId, String teamName, long availableTokens) {
    }

    public record TestCallerListResponse(List<TestCaller> items) {
    }

    public record CreateTestRunRequest(@NotBlank String model, @NotBlank String selectionMode,
                                       List<@NotNull Long> memberIds, Integer autoCount) {
    }

    public record TestRunCaller(long memberId, String memberName, long teamId, String teamName, String apiKey) {
    }

    public record TestRunCreated(String runId, String model, OffsetDateTime expiresAt, List<TestRunCaller> callers) {
    }

    public record TestRunSummary(String runId, long recordedRequests, long successfulRequests, long failedRequests,
                                 long inputTokens, long outputTokens, long usageRecords, long usageTokens,
                                 long billedRecords, long billedTokens, BigDecimal billedAmount, String currency,
                                 long pendingBillingRecords) {
    }
}
