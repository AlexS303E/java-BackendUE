package com.game.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Stage4ReleaseGateTest {
    private static final Path STAGE4_GATE = Path.of("..", "tools", "test", "run-stage4-gate.ps1");

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
            .contains("Stage 4 gate passed.");
    }
}
