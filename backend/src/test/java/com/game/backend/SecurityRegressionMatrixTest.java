package com.game.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityRegressionMatrixTest {
    private static final Path MATRIX = Path.of("..", "docs", "security-regression-matrix.md");
    private static final List<String> REQUIRED_RISKS = List.of(
        "BOLA/IDOR",
        "CSRF",
        "XSS",
        "Replay",
        "Invalid loadout",
        "mTLS denied"
    );
    private static final List<String> REQUIRED_EVIDENCE = List.of(
        "ServerAdminSecurityIntegrationTest",
        "OpenApiContractMatrixTest",
        "DtoContractValidationTest",
        "RuntimePresetChangeIdempotencyTest",
        "LoadoutValidationIntegrationTest",
        "ServerMtlsFallbackDisabledIntegrationTest",
        "ServerMtlsHardeningValidatorTest",
        "X-Admin-Confirm",
        "LOADOUT_VALIDATION_FAILED",
        "Idempotency"
    );

    @Test
    void matrixDocumentsRequiredSecurityRegressionSet() throws IOException {
        String content = Files.readString(MATRIX);

        REQUIRED_RISKS.forEach(risk -> assertThat(content)
            .as("Security regression matrix must cover %s", risk)
            .contains(risk));
    }

    @Test
    void matrixLinksRisksToExecutableEvidence() throws IOException {
        String content = Files.readString(MATRIX);

        REQUIRED_EVIDENCE.forEach(evidence -> assertThat(content)
            .as("Security regression matrix must include evidence %s", evidence)
            .contains(evidence));
    }
}
