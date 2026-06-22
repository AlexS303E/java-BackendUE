package com.game.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MtlsOperationsRunbookTest {
    private static final Path RUNBOOK = Path.of("..", "docs", "mtls-operations.md");
    private static final List<String> REQUIRED_TERMS = List.of(
        "Certificate Rotation Plan",
        "Revocation List",
        "server_identities.certificate_fingerprint",
        "POST /admin/server-identities/revoke",
        "X-Admin-Confirm",
        "Idempotency-Key",
        "revoked_at",
        "tools/mtls/run-mtls-smoke.ps1",
        "ServerAdminSecurityIntegrationTest",
        "AdminParityIntegrationTest",
        "Stage 2"
    );

    @Test
    void mtlsOperationsRunbookCoversRotationAndRevocation() throws IOException {
        String content = Files.readString(RUNBOOK);

        REQUIRED_TERMS.forEach(term -> assertThat(content)
            .as("mTLS operations runbook must mention %s", term)
            .contains(term));
    }
}
