package com.game.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionDeploymentRunbookTest {
    private static final Path RUNBOOK = Path.of("..", "docs", "production-deployment.md");
    private static final List<String> REQUIRED_TERMS = List.of(
        "server.port",
        "management.server.port",
        "app.server-auth.mtls.port",
        "SERVER_MTLS_REQUIRE_PRIVATE_PORT=true",
        "SERVER_MTLS_ALLOW_HEADER_FINGERPRINT_FALLBACK=false",
        "internal Dedicated Server network",
        "TCP pass-through",
        "X-Forwarded-Client-Cert",
        "X-Server-Certificate-Fingerprint",
        "Vault",
        "KMS",
        "Kubernetes Secret",
        "SERVER_MTLS_KEY_STORE",
        "SERVER_MTLS_TRUST_STORE",
        "tools/mtls/out/",
        "server_identity_certificates",
        "tools/test/run-stage4-gate.ps1",
        "-Mode Fast",
        "-Mode Release",
        "bootJar",
        "production-profile smoke",
        "mTLS smoke",
        "load smoke"
    );

    @Test
    void productionDeploymentRunbookCoversPrivateMtlsTopologyAndSecrets() throws IOException {
        String content = Files.readString(RUNBOOK);

        REQUIRED_TERMS.forEach(term -> assertThat(content)
            .as("production deployment runbook must mention %s", term)
            .contains(term));
    }
}
