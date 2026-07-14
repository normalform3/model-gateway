package com.modelgate.bootstrap.api;

import com.modelgate.auth.VirtualKeyService;
import com.modelgate.common.api.TestObservabilityDtos.CreateTestRunRequest;
import com.modelgate.common.api.TestObservabilityDtos.MockModelItem;
import com.modelgate.common.api.TestObservabilityDtos.MockModelListResponse;
import com.modelgate.common.api.TestObservabilityDtos.TestCaller;
import com.modelgate.common.api.TestObservabilityDtos.TestCallerListResponse;
import com.modelgate.common.api.TestObservabilityDtos.TestRunCaller;
import com.modelgate.common.api.TestObservabilityDtos.TestRunCreated;
import com.modelgate.common.api.TestObservabilityDtos.TestRunSummary;
import com.modelgate.common.domain.ApiKeyContext;
import com.modelgate.common.domain.RouteTarget;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.infrastructure.db.TestObservabilityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Isolated gateway-side adapter used only by the local Mock test runner. */
@Service
public class TestObservabilityService {
    private static final String MOCK_PROVIDER = "MOCK_OPENAI";
    private final boolean enabled;
    private final TestObservabilityRepository repository;
    private final VirtualKeyService virtualKeyService;

    public TestObservabilityService(
            @Value("${modelgate.test-observability.enabled:false}") boolean enabled,
            TestObservabilityRepository repository,
            VirtualKeyService virtualKeyService
    ) {
        this.enabled = enabled;
        this.repository = repository;
        this.virtualKeyService = virtualKeyService;
    }

    public MockModelListResponse mockModels() {
        requireEnabled();
        return new MockModelListResponse(repository.mockModels().stream().map(MockModelItem::new).toList());
    }

    public TestCallerListResponse callers(String model) {
        requireEnabled();
        requireMockModel(model);
        return new TestCallerListResponse(repository.eligibleCallers(model));
    }

    @Transactional
    public TestRunCreated createRun(CreateTestRunRequest request) {
        requireEnabled();
        requireMockModel(request.model());
        List<TestCaller> eligible = repository.eligibleCallers(request.model());
        List<Long> memberIds = selectMembers(request, eligible);
        String runId = UUID.randomUUID().toString();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(30);
        List<TestRunCaller> callers = memberIds.stream().map(memberId -> {
            TestObservabilityRepository.CallerScope scope = repository.lockEligibleCaller(memberId, request.model())
                    .orElseThrow(() -> bad("Selected developer is no longer eligible: " + memberId));
            String apiKey = "mg-key-test-" + randomToken();
            String prefix = apiKey.substring(0, Math.min(apiKey.length(), 18));
            repository.insertTestKey(scope, runId, prefix, VirtualKeyService.sha256(apiKey), expiresAt);
            return new TestRunCaller(scope.memberId(), scope.memberName(), scope.teamId(), scope.teamName(), apiKey);
        }).toList();
        return new TestRunCreated(runId, request.model(), expiresAt, callers);
    }

    public TestRunSummary runSummary(String runId) {
        requireEnabled();
        validRunId(runId);
        return repository.runSummary(runId);
    }

    @Transactional
    public void completeRun(String runId) {
        requireEnabled();
        validRunId(runId);
        List<TestObservabilityRepository.KeyRef> keys = repository.disableTestKeys(runId);
        virtualKeyService.invalidateKeyHashes(keys.stream().map(TestObservabilityRepository.KeyRef::keyHash).toList());
    }

    /** Returns null for normal production calls, retaining their existing behavior. */
    public String resolveRunId(ApiKeyContext apiKey, String requestedRunId, RouteTarget target) {
        if (requestedRunId == null || requestedRunId.isBlank() || !enabled) {
            return null;
        }
        String runId = validRunId(requestedRunId.trim());
        if (!MOCK_PROVIDER.equals(target.providerType())) {
            throw bad("Test-run calls are allowed only for MOCK_OPENAI routes.");
        }
        if (!repository.isActiveTestKeyForRun(apiKey.keyId(), runId)) {
            throw bad("The test run does not own the API key used for this request.");
        }
        return runId;
    }

    private List<Long> selectMembers(CreateTestRunRequest request, List<TestCaller> eligible) {
        String mode = request.selectionMode().trim().toUpperCase();
        if ("AUTO".equals(mode)) {
            int count = request.autoCount() == null ? 0 : request.autoCount();
            if (count < 1 || count > eligible.size()) throw bad("autoCount must select between 1 and " + eligible.size() + " eligible developers.");
            return eligible.stream().limit(count).map(TestCaller::memberId).toList();
        }
        if ("EXPLICIT".equals(mode)) {
            List<Long> selected = request.memberIds() == null ? List.of() : request.memberIds().stream()
                    .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                    .stream().sorted(Comparator.naturalOrder()).toList();
            if (selected.isEmpty()) throw bad("At least one developer must be selected.");
            java.util.Set<Long> eligibleIds = eligible.stream().map(TestCaller::memberId).collect(java.util.stream.Collectors.toSet());
            if (!eligibleIds.containsAll(selected)) throw bad("Every selected developer must have the Mock model and a positive personal quota.");
            return selected;
        }
        throw bad("selectionMode must be AUTO or EXPLICIT.");
    }

    private void requireMockModel(String model) {
        if (model == null || !repository.mockModels().contains(model)) throw bad("The selected model is not an enabled MOCK_OPENAI model.");
    }

    private void requireEnabled() {
        if (!enabled) throw bad("Test observability is disabled. Set MODELGATE_TEST_OBSERVABILITY_ENABLED=true in a development environment.");
    }

    private String validRunId(String runId) {
        try {
            return UUID.fromString(runId).toString();
        } catch (IllegalArgumentException ex) {
            throw bad("test run id must be a UUID.");
        }
    }

    private static String randomToken() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().replace("-", "").getBytes(StandardCharsets.UTF_8));
    }

    private static ModelGateException bad(String message) {
        return new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, message);
    }
}
