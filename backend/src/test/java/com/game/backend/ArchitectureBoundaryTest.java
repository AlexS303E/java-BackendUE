package com.game.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureBoundaryTest {
    @Test
    void applicationLayerShouldNotUseJdbcTemplateDirectly() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/game/backend");
        List<Path> offenders;
        try (var paths = Files.walk(sourceRoot)) {
            offenders = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> path.toString().contains("\\application\\")
                    || path.toString().contains("/application/")
                    || path.toString().contains("\\common\\audit\\AuditRetentionService.java")
                    || path.toString().contains("/common/audit/AuditRetentionService.java"))
                .filter(ArchitectureBoundaryTest::usesJdbcTemplateDirectly)
                .toList();
        }

        assertThat(offenders)
            .as("Application services must go through repository classes for database access")
            .isEmpty();
    }

    private static boolean usesJdbcTemplateDirectly(Path path) {
        try {
            String source = Files.readString(path);
            return source.contains("org.springframework.jdbc.core.JdbcTemplate")
                || source.contains("JdbcTemplate ")
                || source.contains("jdbcTemplate.");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }
}
