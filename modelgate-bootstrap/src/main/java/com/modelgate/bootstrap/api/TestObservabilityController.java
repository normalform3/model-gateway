package com.modelgate.bootstrap.api;

import com.modelgate.common.api.TestObservabilityDtos.CreateTestRunRequest;
import com.modelgate.common.api.TestObservabilityDtos.MockModelListResponse;
import com.modelgate.common.api.TestObservabilityDtos.TestCallerListResponse;
import com.modelgate.common.api.TestObservabilityDtos.TestRunCreated;
import com.modelgate.common.api.TestObservabilityDtos.TestRunSummary;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Development-only HTTP boundary for the standalone test runner. */
@RestController
@RequestMapping("/test-observability/v1")
@ConditionalOnProperty(prefix = "modelgate.test-observability", name = "enabled", havingValue = "true")
public class TestObservabilityController {
    private final TestObservabilityService service;

    public TestObservabilityController(TestObservabilityService service) {
        this.service = service;
    }

    @GetMapping("/mock-models")
    public Mono<MockModelListResponse> mockModels() {
        return Mono.fromCallable(service::mockModels).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/callers")
    public Mono<TestCallerListResponse> callers(@RequestParam("model") String model) {
        return Mono.fromCallable(() -> service.callers(model)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/runs")
    public Mono<TestRunCreated> createRun(@Valid @RequestBody CreateTestRunRequest request) {
        return Mono.fromCallable(() -> service.createRun(request)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/runs/{runId}")
    public Mono<TestRunSummary> runSummary(@PathVariable("runId") String runId) {
        return Mono.fromCallable(() -> service.runSummary(runId)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/runs/{runId}/complete")
    public Mono<ResponseEntity<Void>> complete(@PathVariable("runId") String runId) {
        return Mono.fromRunnable(() -> service.completeRun(runId)).subscribeOn(Schedulers.boundedElastic())
                .thenReturn(ResponseEntity.noContent().build());
    }
}
