package com.game.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionArtifactGuardTest {
    private static final Path REPO_ROOT_GITIGNORE = Path.of("..", ".gitignore");
    private static final Path BUILD_FILE = Path.of("build.gradle.kts");
    private static final Path MAIN_RESOURCES = Path.of("src/main/resources");
    private static final Set<String> BOOT_JAR_EXCLUDES = Set.of(
        "application-local.yml",
        "db/migration/V013__seed_dev_server_identity.sql",
        "db/migration/V014__seed_dev_limited_server_identity.sql"
    );

    @Test
    void bootJarShouldExcludeDevOnlyResources() throws IOException {
        String build = Files.readString(BUILD_FILE);

        for (String excluded : BOOT_JAR_EXCLUDES) {
            assertThat(build).contains("exclude(\"" + excluded + "\")");
        }
    }

    @Test
    void mainResourcesShouldNotContainPrivateKeyOrCertificateFiles() throws IOException {
        try (var paths = Files.walk(MAIN_RESOURCES)) {
            assertThat(paths
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString().toLowerCase())
                .filter(name -> name.endsWith(".pem")
                    || name.endsWith(".key")
                    || name.endsWith(".p12")
                    || name.endsWith(".pfx")
                    || name.endsWith(".jks"))
                .toList())
                .isEmpty();
        }
    }

    @Test
    void repositoryShouldIgnoreLocalSecretsAndGeneratedCertificateOutputs() throws IOException {
        String gitignore = Files.readString(REPO_ROOT_GITIGNORE);

        assertThat(gitignore)
            .contains(".env")
            .contains("tools/mtls/out*")
            .contains("tools/mtls/work/")
            .contains("tools/mtls/logs/");
    }
}
