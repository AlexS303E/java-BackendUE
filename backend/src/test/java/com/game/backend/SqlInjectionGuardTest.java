package com.game.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlInjectionGuardTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/game/backend");
    private static final Path AUDIT_RETENTION_SERVICE = Path.of(
        "src/main/java/com/game/backend/common/audit/AuditRetentionService.java"
    );

    @Test
    void productionCodeShouldNotUseRawJdbcStatements() throws IOException {
        assertThat(javaFilesUsing(List.of(
            "java.sql.Statement",
            ".createStatement(",
            ".executeQuery(",
            ".executeUpdate("
        )))
            .as("Production code should use JdbcTemplate repository methods with bind parameters")
            .isEmpty();
    }

    @Test
    void dynamicSqlFormattingShouldStayExplicitlyWhitelisted() throws IOException {
        List<Path> dynamicSqlFiles = javaFilesUsing(List.of(
            ".formatted(",
            "String.format("
        ));

        assertThat(dynamicSqlFiles)
            .as("Dynamic SQL formatting is allowed only for fixed internal audit retention table names")
            .containsExactly(AUDIT_RETENTION_SERVICE);

        String auditRetentionSource = Files.readString(AUDIT_RETENTION_SERVICE);
        assertThat(auditRetentionSource)
            .contains("\"admin_audit_events\"")
            .contains("\"server_audit_events\"")
            .contains(".formatted(tableName, tableName)");
    }

    private static List<Path> javaFilesUsing(List<String> patterns) throws IOException {
        try (var paths = Files.walk(SOURCE_ROOT)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> containsAny(path, patterns))
                .toList();
        }
    }

    private static boolean containsAny(Path path, List<String> patterns) {
        try {
            String source = Files.readString(path);
            return patterns.stream().anyMatch(source::contains);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }
}
