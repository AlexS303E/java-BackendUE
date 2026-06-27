package com.game.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ServerOpenApiContractTest {
    private static final Path SERVER_API = Path.of("..", "contracts", "openapi", "server-api.yaml");

    @Test
    void runtimeEventsContractShouldMatchIdempotencyDecisionAndResponseStatus() throws IOException {
        String contract = Files.readString(SERVER_API);

        assertThat(contract)
            .contains("For runtime-events it identifies the replay window and does not need to equal event_id.")
            .contains("enum: [recorded]")
            .doesNotContain("enum: [accepted, duplicate]");
    }

    @Test
    void serverContractShouldNotExposeDeprecatedHeaderFingerprintFallback() throws IOException {
        String contract = Files.readString(SERVER_API);

        assertThat(contract)
            .contains("ServerMutualTls")
            .contains("X-Server-Id")
            .doesNotContain("X-Server-Certificate-Fingerprint")
            .doesNotContain("server_certificate_fingerprint_header_deprecated");
    }
}
