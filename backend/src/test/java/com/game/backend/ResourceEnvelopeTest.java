package com.game.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceEnvelopeTest {
    private static final Path ENVELOPE = Path.of("..", "config", "production-resource-envelope.env");
    private static final Path PREFLIGHT = Path.of("..", "tools", "deploy", "validate-resource-envelope.ps1");
    private static final Path SMOKE = Path.of("..", "tools", "smoke", "prod-profile-smoke.ps1");
    private static final Path DOCUMENTATION = Path.of("..", "docs", "resource-sizing.md");

    @Test
    void productionEnvelopeShouldDefineBoundedCpuMemoryAndHeap() throws IOException {
        String envelope = Files.readString(ENVELOPE);

        assertThat(envelope)
            .contains("APP_CPU_REQUEST=2")
            .contains("APP_CPU_LIMIT=4")
            .contains("APP_MEMORY_REQUEST_MIB=1536")
            .contains("APP_MEMORY_LIMIT_MIB=2048")
            .contains("-XX:InitialRAMPercentage=25")
            .contains("-XX:MaxRAMPercentage=60")
            .contains("-XX:+UseG1GC")
            .contains("-XX:+ExitOnOutOfMemoryError");
    }

    @Test
    void preflightAndProductionSmokeShouldEnforceTheEnvelope() throws IOException {
        assertThat(Files.readString(PREFLIGHT))
            .contains("RESOURCE_ENVELOPE_OK")
            .contains("CPU limit")
            .contains("Memory limit")
            .contains("requiredJvmOptions");

        assertThat(Files.readString(SMOKE))
            .contains("production-resource-envelope.env")
            .contains("validate-resource-envelope.ps1")
            .contains("$env:JAVA_TOOL_OPTIONS");
    }

    @Test
    void sizingDocumentationShouldDefineRevalidationTriggers() throws IOException {
        assertThat(Files.readString(DOCUMENTATION))
            .contains("25 VU Stage 1 profile")
            .contains("CPU saturation exceeds 70%")
            .contains("memory working set exceeds 80%")
            .contains("GC pause p95 exceeds 100 ms");
    }
}
