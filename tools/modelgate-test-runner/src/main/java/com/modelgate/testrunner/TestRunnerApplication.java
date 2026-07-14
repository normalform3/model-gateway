package com.modelgate.testrunner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone local UI and load generator. It only speaks the documented test-observability HTTP contract. */
public final class TestRunnerApplication {
    private static final int PORT = Integer.parseInt(System.getProperty("modelgate.test-runner.port", "19090"));
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile RunState activeRun;

    public static void main(String[] args) throws IOException {
        new TestRunnerApplication().start();
    }

    private void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/", this::index);
        server.createContext("/health", exchange -> sendJson(exchange, 200, Map.of("status", "ok")));
        server.createContext("/api/mock-models", this::mockModels);
        server.createContext("/api/callers", this::callers);
        server.createContext("/api/start", this::startRun);
        server.createContext("/api/status", this::status);
        server.createContext("/api/stop", this::stop);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("ModelGate test runner listening on http://127.0.0.1:" + PORT);
    }

    private void index(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { sendJson(exchange, 405, Map.of("error", "Method not allowed")); return; }
        try (InputStream input = getClass().getResourceAsStream("/static/index.html")) {
            if (input == null) { sendJson(exchange, 500, Map.of("error", "Runner UI asset missing")); return; }
            byte[] body = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
        }
    }

    private void mockModels(HttpExchange exchange) throws IOException {
        try {
            String gateway = required(query(exchange).get("gatewayBaseUrl"), "gatewayBaseUrl is required");
            sendJson(exchange, 200, get(gateway, "/test-observability/v1/mock-models"));
        } catch (Exception ex) { sendError(exchange, ex); }
    }

    private void callers(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> query = query(exchange);
            String gateway = required(query.get("gatewayBaseUrl"), "gatewayBaseUrl is required");
            String model = required(query.get("model"), "model is required");
            sendJson(exchange, 200, get(gateway, "/test-observability/v1/callers?model=" + java.net.URLEncoder.encode(model, StandardCharsets.UTF_8)));
        } catch (Exception ex) { sendError(exchange, ex); }
    }

    private void startRun(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { sendJson(exchange, 405, Map.of("error", "Method not allowed")); return; }
        try {
            if (activeRun != null && activeRun.running()) throw new IllegalArgumentException("A test run is already active.");
            RunConfig config = json.readValue(exchange.getRequestBody(), RunConfig.class).validated();
            JsonNode created = post(config.gatewayBaseUrl(), "/test-observability/v1/runs", Map.of(
                    "model", config.model(), "selectionMode", config.selectionMode(),
                    "memberIds", config.memberIds(), "autoCount", config.autoCount()));
            RunState run = RunState.from(created, config);
            activeRun = run;
            run.start(this);
            sendJson(exchange, 202, run.status(null));
        } catch (Exception ex) { sendError(exchange, ex); }
    }

    private void status(HttpExchange exchange) throws IOException {
        try {
            RunState run = activeRun;
            if (run == null) { sendJson(exchange, 200, Map.of("state", "IDLE")); return; }
            JsonNode gatewaySummary = run.runId() == null ? null : get(run.config().gatewayBaseUrl(), "/test-observability/v1/runs/" + run.runId());
            sendJson(exchange, 200, run.status(gatewaySummary));
        } catch (Exception ex) { sendError(exchange, ex); }
    }

    private void stop(HttpExchange exchange) throws IOException {
        try {
            RunState run = activeRun;
            if (run != null) run.stop(this);
            sendJson(exchange, 200, run == null ? Map.of("state", "IDLE") : run.status(null));
        } catch (Exception ex) { sendError(exchange, ex); }
    }

    private void invoke(RunState run, int requestIndex, Caller caller) {
        if (run.cancelled.get()) { run.metrics.cancelled(caller.memberId()); return; }
        long delay = run.config().rampUpMs() == 0 ? 0 : Math.round((double) requestIndex * run.config().rampUpMs() / Math.max(1, run.config().totalRequests() - 1));
        if (delay > 0 && !sleep(delay, run.cancelled)) { run.metrics.cancelled(caller.memberId()); return; }
        long start = System.nanoTime();
            long firstTokenMs = -1;
        try {
            Map<String, Object> mock = new LinkedHashMap<>();
            mock.put("mode", run.config().mockMode());
            mock.put("delayMs", run.config().delayMs());
            mock.put("inputTokens", run.config().inputTokens());
            mock.put("outputTokens", run.config().outputTokens());
            Map<String, Object> body = Map.of(
                    "model", run.config().model(), "messages", List.of(Map.of("role", "user", "content", "ModelGate synthetic load-test prompt")),
                    "stream", run.config().stream(), "max_tokens", run.config().maxTokens(), "mock", mock);
            HttpRequest request = HttpRequest.newBuilder(URI.create(trim(run.config().gatewayBaseUrl()) + "/v1/chat/completions"))
                    .timeout(Duration.ofMinutes(2)).header("Authorization", "Bearer " + caller.apiKey())
                    .header("Content-Type", "application/json").header("Idempotency-Key", run.runId() + "-" + requestIndex)
                    .header("X-ModelGate-Test-Run-Id", run.runId()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            if (run.config().stream()) {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 300) throw new HttpFailure(response.statusCode(), read(response.body()));
                firstTokenMs = consumeSse(response.body(), start);
            } else {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 300) throw new HttpFailure(response.statusCode(), response.body());
            }
            run.metrics.success(caller.memberId(), elapsedMs(start), firstTokenMs);
        } catch (HttpFailure ex) {
            run.metrics.failure(caller.memberId(), elapsedMs(start), errorCode(ex.body));
        } catch (Exception ex) {
            run.metrics.failure(caller.memberId(), elapsedMs(start), ex.getClass().getSimpleName());
        }
    }

    private long consumeSse(InputStream input, long start) throws IOException {
        long firstTokenMs = -1;
        try (BufferedReader lines = new BufferedReader(new java.io.InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (firstTokenMs < 0 && line.startsWith("data:") && !line.contains("[DONE]")) firstTokenMs = elapsedMs(start);
            }
        }
        return firstTokenMs;
    }

    private JsonNode get(String gateway, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(trim(gateway) + path)).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) throw new HttpFailure(response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private JsonNode post(String gateway, String path, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(trim(gateway) + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) throw new HttpFailure(response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private void complete(RunState run) {
        try { post(run.config().gatewayBaseUrl(), "/test-observability/v1/runs/" + run.runId() + "/complete", Map.of()); }
        catch (Exception ex) { run.completionError = ex.getMessage(); }
    }

    private static boolean sleep(long millis, AtomicBoolean cancelled) {
        try { Thread.sleep(millis); return !cancelled.get(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return false; }
    }
    private static long elapsedMs(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private static String trim(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    private static String required(String value, String message) { if (value == null || value.isBlank()) throw new IllegalArgumentException(message); return value; }
    private String errorCode(String body) { try { return json.readTree(body).path("error").path("code").asText("HTTP_ERROR"); } catch (Exception ex) { return "HTTP_ERROR"; } }
    private static String read(InputStream input) throws IOException { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new LinkedHashMap<>(); String raw = exchange.getRequestURI().getRawQuery(); if (raw == null) return result;
        for (String part : raw.split("&")) { String[] pair = part.split("=", 2); result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : ""); } return result;
    }
    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException { byte[] bytes = json.writeValueAsBytes(body); exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); exchange.sendResponseHeaders(status, bytes.length); try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); } }
    private void sendError(HttpExchange exchange, Exception ex) throws IOException { int status = ex instanceof HttpFailure failure ? failure.status : 400; sendJson(exchange, status, Map.of("error", ex.getMessage() == null ? "Request failed" : ex.getMessage())); }

    private static final class HttpFailure extends RuntimeException {
        private final int status; private final String body;
        private HttpFailure(int status, String body) { this.status = status; this.body = body; }
        @Override public String getMessage() { return "Gateway returned HTTP " + status + ": " + body; }
    }

    public record RunConfig(String gatewayBaseUrl, String model, String selectionMode, List<Long> memberIds, Integer autoCount,
                            boolean stream, String mockMode, long delayMs, int inputTokens, int outputTokens, int maxTokens,
                            int totalRequests, int concurrency, long rampUpMs) {
        RunConfig validated() {
            required(gatewayBaseUrl, "gatewayBaseUrl is required"); required(model, "model is required"); required(selectionMode, "selectionMode is required");
            if (totalRequests < 1 || concurrency < 1 || concurrency > totalRequests || delayMs < 0 || rampUpMs < 0 || inputTokens < 1 || outputTokens < 1 || maxTokens < 1) throw new IllegalArgumentException("Invalid load configuration.");
            if ("EXPLICIT".equalsIgnoreCase(selectionMode) && (memberIds == null || memberIds.isEmpty())) throw new IllegalArgumentException("Select at least one developer.");
            if ("AUTO".equalsIgnoreCase(selectionMode) && (autoCount == null || autoCount < 1)) throw new IllegalArgumentException("autoCount must be positive.");
            return this;
        }
    }

    private static final class RunState {
        private final RunConfig config; private final String runId; private final List<Caller> callers; private final Metrics metrics = new Metrics();
        private final AtomicBoolean cancelled = new AtomicBoolean(); private final List<Future<?>> futures = new ArrayList<>();
        private volatile String state = "RUNNING"; private volatile String completionError;
        private RunState(RunConfig config, String runId, List<Caller> callers) { this.config = config; this.runId = runId; this.callers = callers; }
        static RunState from(JsonNode created, RunConfig config) {
            List<Caller> callers = new ArrayList<>(); for (JsonNode node : created.path("callers")) callers.add(new Caller(node.path("memberId").asLong(), node.path("memberName").asText(), node.path("apiKey").asText()));
            if (callers.isEmpty() || config.totalRequests() < callers.size()) throw new IllegalArgumentException("Request count must be at least the number of selected developers.");
            return new RunState(config, created.path("runId").asText(), callers);
        }
        void start(TestRunnerApplication app) {
            ExecutorService workers = Executors.newFixedThreadPool(config.concurrency());
            CompletableFuture.runAsync(() -> {
                try { for (int i = 0; i < config.totalRequests(); i++) { final int index = i; futures.add(workers.submit(() -> app.invoke(this, index, callers.get(index % callers.size())))); }
                    for (Future<?> future : futures) { try { future.get(); } catch (Exception ignored) { } }
                } finally { workers.shutdownNow(); app.complete(this); state = cancelled.get() ? "CANCELLED" : "COMPLETE"; }
            });
        }
        void stop(TestRunnerApplication app) { if (cancelled.compareAndSet(false, true)) { futures.forEach(future -> future.cancel(true)); state = "STOPPING"; } }
        boolean running() { return "RUNNING".equals(state) || "STOPPING".equals(state); }
        String runId() { return runId; } RunConfig config() { return config; }
        Map<String, Object> status(JsonNode gateway) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("runId", runId); result.put("state", state); result.put("totalRequests", config.totalRequests());
            result.put("metrics", metrics.snapshot()); result.put("gateway", gateway); result.put("completionError", completionError == null ? "" : completionError);
            return result;
        }
    }

    private record Caller(long memberId, String memberName, String apiKey) { }

    private static final class Metrics {
        private final List<Long> latency = new ArrayList<>(); private final List<Long> firstTokens = new ArrayList<>(); private final Map<String, Long> errors = new LinkedHashMap<>(); private final Map<Long, MemberMetrics> members = new LinkedHashMap<>();
        synchronized void success(long member, long elapsed, long firstToken) { latency.add(elapsed); if (firstToken >= 0) firstTokens.add(firstToken); member(member).success++; member(member).latencies.add(elapsed); if (firstToken >= 0) member(member).firstTokens.add(firstToken); }
        synchronized void failure(long member, long elapsed, String code) { latency.add(elapsed); member(member).failed++; member(member).latencies.add(elapsed); errors.merge(code, 1L, Long::sum); }
        synchronized void cancelled(long member) { member(member).cancelled++; }
        synchronized Map<String, Object> snapshot() { long success = members.values().stream().mapToLong(m -> m.success).sum(), failed = members.values().stream().mapToLong(m -> m.failed).sum(), cancelled = members.values().stream().mapToLong(m -> m.cancelled).sum(); Map<String,Object> result = new LinkedHashMap<>(); result.put("success", success); result.put("failed", failed); result.put("cancelled", cancelled); result.put("p50Ms", percentile(latency, .50)); result.put("p95Ms", percentile(latency, .95)); result.put("p99Ms", percentile(latency, .99)); result.put("firstTokenP50Ms", percentile(firstTokens, .50)); result.put("firstTokenP95Ms", percentile(firstTokens, .95)); result.put("errors", errors); Map<String,Object> perMember = new LinkedHashMap<>(); members.forEach((id,m) -> perMember.put(String.valueOf(id), Map.of("success",m.success,"failed",m.failed,"cancelled",m.cancelled,"p95Ms",percentile(m.latencies,.95),"firstTokenP95Ms",percentile(m.firstTokens,.95)))); result.put("members",perMember); return result; }
        private MemberMetrics member(long id) { return members.computeIfAbsent(id, ignored -> new MemberMetrics()); }
        private static long percentile(List<Long> values, double p) { if (values.isEmpty()) return 0; List<Long> sorted = values.stream().sorted().toList(); return sorted.get(Math.min(sorted.size()-1, (int)Math.ceil(sorted.size()*p)-1)); }
        private static final class MemberMetrics { long success, failed, cancelled; final List<Long> latencies = new ArrayList<>(); final List<Long> firstTokens = new ArrayList<>(); }
    }
}
