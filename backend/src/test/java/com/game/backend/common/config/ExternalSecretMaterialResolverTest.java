package com.game.backend.common.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSecretMaterialResolverTest {

    @Test
    void shouldReadAndTrimSecretFromFile() throws Exception {
        Path secretFile = Files.createTempFile("external-secret-", ".txt");
        Files.writeString(secretFile, "secret-value\n");

        try {
            assertThat(ExternalSecretMaterialResolver.resolve("app.admin.token", "file:" + secretFile))
                    .isEqualTo("secret-value");
        } finally {
            Files.deleteIfExists(secretFile);
        }
    }

    @Test
    void shouldFailWhenSecretFileIsUnavailable() {
        Path missingSecretFile = Path.of("build", "missing-admin-secret.txt");

        assertThatThrownBy(() -> ExternalSecretMaterialResolver.resolve(
                "app.admin.identities[].token", "file:" + missingSecretFile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.admin.identities[].token");
    }

    @Test
    void shouldFailWhenSecretFileIsEmpty() throws Exception {
        Path secretFile = Files.createTempFile("external-secret-", ".txt");

        try {
            assertThatThrownBy(() -> ExternalSecretMaterialResolver.resolve("app.admin.token", "file:" + secretFile))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.admin.token");
        } finally {
            Files.deleteIfExists(secretFile);
        }
    }
}
