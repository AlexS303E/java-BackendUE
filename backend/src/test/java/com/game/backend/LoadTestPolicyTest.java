package com.game.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LoadTestPolicyTest {
    private static final Path K6_DIR = Path.of("..", "tools", "load", "k6");
    private static final Path GATES = K6_DIR.resolve("performance-gates.js");
    private static final Path POLICY = Path.of("..", "docs", "load-test-policy.md");

    @Test
    void k6PerformanceGatesShouldDefineStageOneP95Targets() throws IOException {
        String gates = Files.readString(GATES);

        Map.of(
            "health", 100,
            "catalog", 100,
            "auth", 500,
            "access", 200,
            "presets", 200,
            "matchProfile", 300,
            "runtimeChanges", 200,
            "mixedSmoke", 500
        ).forEach((gate, p95) -> assertThat(gates)
            .contains(gate + ": {")
            .contains("\"p(95)<" + p95 + "\""));
    }

    @Test
    void endpointIsolationScriptsShouldUseCentralPerformanceGates() throws IOException {
        Map<String, String> scripts = Map.of(
            "endpoint-health-only.js", "health",
            "endpoint-catalog-only.js", "catalog",
            "endpoint-auth-only.js", "auth",
            "endpoint-access-only.js", "access",
            "endpoint-presets-only.js", "presets",
            "endpoint-match-profile-only.js", "matchProfile",
            "endpoint-match-profile-warm-only.js", "matchProfile",
            "endpoint-match-profile-cold-only.js", "matchProfile",
            "endpoint-runtime-changes-only.js", "runtimeChanges",
            "load-smoke.js", "mixedSmoke"
        );

        scripts.forEach((file, gate) -> {
            try {
                assertThat(Files.readString(K6_DIR.resolve(file)))
                    .contains("performance-gates.js")
                    .contains("thresholds: PERFORMANCE_GATES." + gate);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to read " + file, exception);
            }
        });
    }

    @Test
    void policyShouldNameK6AsAuthoritativeAndTankAsDiagnostic() throws IOException {
        assertThat(Files.readString(POLICY))
            .contains("k6 is the authoritative Stage 1 performance gate")
            .contains("Yandex.Tank is diagnostic only")
            .contains("must not be used as release evidence");
    }
}
