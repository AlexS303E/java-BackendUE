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

    @Test
    void matchProfileApplicationShouldNotOwnSqlQueries() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/game/backend/matchprofile/application");
        assertApplicationPackageDoesNotOwnSqlQueries(sourceRoot);
    }

    @Test
    void runtimeChangesApplicationShouldNotOwnSqlQueries() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/game/backend/runtimechanges/application");
        assertApplicationPackageDoesNotOwnSqlQueries(sourceRoot);
    }

    @Test
    void accessApplicationShouldNotOwnSqlQueries() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/game/backend/access/application");
        assertApplicationPackageDoesNotOwnSqlQueries(sourceRoot);
    }

    @Test
    void loadoutValidationServiceShouldNotOwnSqlQueries() throws IOException {
        Path sourceFile = Path.of("src/main/java/com/game/backend/presets/application/LoadoutValidationService.java");

        assertThat(ownsSqlOrGenericRepositoryCalls(sourceFile))
            .as("LoadoutValidationService should call named repository methods, not own SQL/query plumbing")
            .isFalse();
    }

    @Test
    void loadoutSanitizationServiceShouldNotOwnSqlQueries() throws IOException {
        Path sourceFile = Path.of("src/main/java/com/game/backend/presets/application/LoadoutSanitizationService.java");

        assertThat(ownsSqlOrGenericRepositoryCalls(sourceFile))
            .as("LoadoutSanitizationService should call named repository methods, not own SQL/query plumbing")
            .isFalse();
    }

    @Test
    void presetsServiceShouldNotOwnSqlQueries() throws IOException {
        Path sourceFile = Path.of("src/main/java/com/game/backend/presets/application/PresetsService.java");

        assertThat(ownsSqlOrGenericRepositoryCalls(sourceFile))
            .as("PresetsService should call named repository methods, not own SQL/query plumbing")
            .isFalse();
    }

    @Test
    void catalogReadServicesShouldNotOwnSqlQueries() throws IOException {
        List<Path> sourceFiles = List.of(
            Path.of("src/main/java/com/game/backend/catalog/application/CatalogService.java"),
            Path.of("src/main/java/com/game/backend/catalog/application/CatalogValidationData.java")
        );

        assertThat(sourceFiles)
            .as("Catalog read services should call named repository methods, not own SQL/query plumbing")
            .noneMatch(ArchitectureBoundaryTest::ownsSqlOrGenericRepositoryCalls);
    }

    @Test
    void catalogLifecycleServiceShouldNotOwnSqlQueries() throws IOException {
        Path sourceFile = Path.of("src/main/java/com/game/backend/catalog/application/CatalogLifecycleService.java");

        assertThat(ownsSqlOrGenericRepositoryCalls(sourceFile))
            .as("CatalogLifecycleService should call named repository methods, not own SQL/query plumbing")
            .isFalse();
    }

    @Test
    void authServiceShouldNotOwnSqlQueries() throws IOException {
        Path sourceFile = Path.of("src/main/java/com/game/backend/auth/application/AuthService.java");

        assertThat(ownsSqlOrGenericRepositoryCalls(sourceFile))
            .as("AuthService should call named repository methods, not own SQL/query plumbing")
            .isFalse();
    }

    @Test
    void playerBootstrapServiceShouldNotOwnSqlQueries() throws IOException {
        Path sourceFile = Path.of("src/main/java/com/game/backend/auth/application/PlayerBootstrapService.java");

        assertThat(ownsSqlOrGenericRepositoryCalls(sourceFile))
            .as("PlayerBootstrapService should call named repository methods, not own SQL/query plumbing")
            .isFalse();
    }

    @Test
    void serverAuthApplicationShouldNotOwnSqlQueries() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/game/backend/serverauth/application");
        assertApplicationPackageDoesNotOwnSqlQueries(sourceRoot);
    }

    @Test
    void runtimeEventsApplicationShouldNotOwnSqlQueries() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/game/backend/runtimeevents/application");
        assertApplicationPackageDoesNotOwnSqlQueries(sourceRoot);
    }

    @Test
    void postMatchApplicationShouldNotOwnSqlQueries() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/game/backend/postmatch/application");
        assertApplicationPackageDoesNotOwnSqlQueries(sourceRoot);
    }

    private static void assertApplicationPackageDoesNotOwnSqlQueries(Path sourceRoot) throws IOException {
        List<Path> offenders;
        try (var paths = Files.walk(sourceRoot)) {
            offenders = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(ArchitectureBoundaryTest::ownsSqlOrGenericRepositoryCalls)
                .toList();
        }

        assertThat(offenders)
            .as("Application services should call named repository methods, not own SQL/query plumbing")
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

    private static boolean ownsSqlOrGenericRepositoryCalls(Path path) {
        try {
            String source = Files.readString(path);
            return source.contains("\"\"\"")
                || source.contains("repository.query(")
                || source.contains("repository.queryForList(")
                || source.contains("repository.queryForObject(")
                || source.contains("repository.update(");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }
}
