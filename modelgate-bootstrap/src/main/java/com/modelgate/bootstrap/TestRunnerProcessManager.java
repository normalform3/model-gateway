package com.modelgate.bootstrap;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Starts the standalone development test runner as a child process when explicitly enabled.
 *
 * <p>The runner stays an independent executable and is never added to the gateway Maven reactor.
 */
@Component
public final class TestRunnerProcessManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TestRunnerProcessManager.class);

    private final boolean testObservabilityEnabled;
    private final boolean autoStart;
    private final Path runnerJarPath;
    private final int runnerPort;

    private volatile boolean lifecycleRunning;
    private volatile Process runnerProcess;

    public TestRunnerProcessManager(
            @Value("${modelgate.test-observability.enabled:false}") boolean testObservabilityEnabled,
            @Value("${modelgate.test-runner.auto-start:false}") boolean autoStart,
            @Value("${modelgate.test-runner.jar-path:tools/modelgate-test-runner/target/modelgate-test-runner-0.1.0-SNAPSHOT-all.jar}")
                    String runnerJarPath,
            @Value("${modelgate.test-runner.port:19090}") int runnerPort) {
        this.testObservabilityEnabled = testObservabilityEnabled;
        this.autoStart = autoStart;
        this.runnerJarPath = Path.of(runnerJarPath).toAbsolutePath().normalize();
        this.runnerPort = runnerPort;
    }

    @Override
    public synchronized void start() {
        lifecycleRunning = true;
        if (!autoStart) {
            return;
        }
        if (!testObservabilityEnabled) {
            log.warn("Test Runner auto-start was requested but test observability is disabled; skipping startup");
            return;
        }
        if (!Files.isRegularFile(runnerJarPath)) {
            log.warn(
                    "Test Runner auto-start is enabled but its executable is missing: {}. "
                            + "Build it with mvn -f tools/modelgate-test-runner/pom.xml package",
                    runnerJarPath);
            return;
        }
        if (!isLoopbackPortAvailable(runnerPort)) {
            log.info(
                    "Test Runner port 127.0.0.1:{} is already in use; assuming an external runner is active",
                    runnerPort);
            return;
        }

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    javaExecutable(),
                    "-Dmodelgate.test-runner.port=" + runnerPort,
                    "-jar",
                    runnerJarPath.toString());
            processBuilder.inheritIO();
            runnerProcess = processBuilder.start();
            log.info("Started standalone Test Runner on http://127.0.0.1:{} (pid={})", runnerPort, runnerProcess.pid());
        } catch (IOException exception) {
            log.warn("Could not start standalone Test Runner; the gateway will continue without it", exception);
        }
    }

    @Override
    public synchronized void stop() {
        lifecycleRunning = false;
        Process process = runnerProcess;
        runnerProcess = null;
        if (process == null || !process.isAlive()) {
            return;
        }

        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                log.warn("Test Runner did not stop gracefully; terminating child process pid={}", process.pid());
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    @Override
    public boolean isRunning() {
        return lifecycleRunning;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    static boolean isLoopbackPortAvailable(int port) {
        if (port < 1 || port > 65535) {
            return false;
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
