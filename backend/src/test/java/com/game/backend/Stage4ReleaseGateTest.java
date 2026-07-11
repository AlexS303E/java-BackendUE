package com.game.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Stage4ReleaseGateTest {
    private static final Path STAGE4_GATE = Path.of("..", "tools", "test", "run-stage4-gate.ps1");
    private static final Path STAGE4_SUMMARY_VALIDATOR = Path.of("..", "tools", "test", "validate-stage4-summary.ps1");
    private static final Path PRODUCTION_DEPLOYMENT = Path.of("..", "docs", "production-deployment.md");
    private static final Path STATUS = Path.of("..", "docs", "status.md");

    @Test
    void stage4GateShouldOrchestrateFastAndReleaseChecks() throws IOException {
        String script = Files.readString(STAGE4_GATE);

        assertThat(script)
            .contains("[ValidateSet(\"Fast\", \"Release\")]")
            .contains("tools\\test\\run-all-tests.ps1")
            .contains("tools\\smoke\\prod-profile-smoke.ps1")
            .contains("tools\\mtls\\run-mtls-smoke.ps1")
            .contains("tools\\load\\run-load-smoke.ps1")
            .contains("--no-daemon bootJar")
            .contains("-SkipOpenApi:$SkipOpenApi")
            .contains("[switch]$ListSteps")
            .contains("[string]$SummaryPath = \"artifacts/stage4/stage4-gate-summary.json\"")
            .contains("[switch]$NoSummary")
            .contains("[string]$SkipReason")
            .contains("Test-ReleaseGateHasSkippedChecks")
            .contains("schema_name = \"stage4_gate_summary\"")
            .contains("schema_version = 1")
            .contains("Resolve-RepoRevision")
            .contains("repo_revision")
            .contains("skip_reason")
            .contains("started_at")
            .contains("finished_at")
            .contains("duration_ms")
            .contains("$NoSummary")
            .contains("tools\\test\\validate-stage4-summary.ps1")
            .contains("-SummaryPath $resolvedPath")
            .contains("Write-GateSummary")
            .contains("ConvertTo-Json")
            .contains("error_message")
            .contains("-Result \"failed\"")
            .contains("Planned Stage 4 gate steps:")
            .contains("Stage 4 gate passed.");
    }

    @Test
    void stage4SummaryValidatorShouldKeepEvidenceSchemaStable() throws IOException {
        assertThat(Files.readString(STAGE4_SUMMARY_VALIDATOR))
            .contains("[Parameter(Mandatory = $true)]")
            .contains("stage4_gate_summary")
            .contains("schema_version -eq 1")
            .contains("stage -eq 4")
            .contains("repo_revision is required")
            .contains("@(\"Fast\", \"Release\")")
            .contains("@(\"planned\", \"passed\", \"failed\")")
            .contains("@(\"run\", \"skip\")")
            .contains("Stage 4 summary schema is valid");
    }

    @Test
    void stage4GateShouldBeDocumentedAsTheReleaseEntryPoint() throws IOException {
        assertThat(Files.readString(PRODUCTION_DEPLOYMENT))
            .contains("tools/test/run-stage4-gate.ps1 -Mode Fast")
            .contains("tools/test/run-stage4-gate.ps1 -Mode Release -SkipDocker")
            .contains("tools/test/run-stage4-gate.ps1 -Mode Release -ListSteps")
            .contains("bootJar")
            .contains("production-profile smoke")
            .contains("mTLS smoke")
            .contains("load smoke")
            .contains("artifacts/stage4/stage4-gate-summary.json")
            .contains("-NoSummary")
            .contains("-SkipReason");

        assertThat(Files.readString(STATUS))
            .contains("tools/test/run-stage4-gate.ps1 -Mode Fast")
            .contains("tools/test/run-stage4-gate.ps1 -Mode Release -SkipDocker")
            .contains("tools/test/run-stage4-gate.ps1 -Mode Release -ListSteps")
            .contains("artifacts/stage4/stage4-gate-summary.json")
            .contains("-NoSummary")
            .contains("Record any intentional `-Skip*` release gate switches");
    }
}
