package com.game.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureBoundaryTest {
    private static final Set<Path> KNOWN_REPOSITORY_API_DEPENDENCIES = Set.of();

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
    void jdbcTemplateShouldStayInRepositoryInfrastructure() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/game/backend");
        List<Path> offenders;
        try (var paths = Files.walk(sourceRoot)) {
            offenders = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(ArchitectureBoundaryTest::usesJdbcTemplateDirectly)
                .filter(path -> !isRepositoryInfrastructure(path))
                .toList();
        }

        assertThat(offenders)
            .as("Direct JdbcTemplate usage belongs in repository infrastructure only")
            .isEmpty();
    }

    @Test
    void jdbcRepositoryBaseClassShouldOnlyBeExtendedByRepositories() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/game/backend");
        List<Path> offenders;
        try (var paths = Files.walk(sourceRoot)) {
            offenders = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(ArchitectureBoundaryTest::extendsJdbcRepository)
                .filter(path -> !isRepositoryPackage(path))
                .toList();
        }

        assertThat(offenders)
            .as("JdbcRepository subclasses must live in repository packages")
            .isEmpty();
    }

    @Test
    void everyApplicationPackageShouldStayBehindRepositories() throws IOException {
        List<Path> applicationRoots = applicationPackageRoots();

        assertThat(applicationRoots)
            .as("Architecture guard must discover application packages automatically")
            .isNotEmpty();

        for (Path sourceRoot : applicationRoots) {
            assertApplicationPackageDoesNotOwnSqlQueries(sourceRoot);
        }
    }

    @Test
    void everyApiPackageShouldStayBehindApplicationServices() throws IOException {
        List<Path> apiRoots = packageRoots("api");

        assertThat(apiRoots)
            .as("Architecture guard must discover API packages automatically")
            .isNotEmpty();

        List<Path> offenders = filesUnder(apiRoots).stream()
            .filter(ArchitectureBoundaryTest::usesRepositoryOrJdbcFromApi)
            .toList();

        assertThat(offenders)
            .as("API controllers and DTOs should depend on application services, not repositories or JDBC")
            .isEmpty();
    }

    @Test
    void everyRepositoryPackageShouldStayIndependentFromApiContracts() throws IOException {
        List<Path> repositoryRoots = packageRoots("repository");

        assertThat(repositoryRoots)
            .as("Architecture guard must discover repository packages automatically")
            .isNotEmpty();

        List<Path> offenders = filesUnder(repositoryRoots).stream()
            .filter(ArchitectureBoundaryTest::dependsOnApiPackage)
            .filter(path -> !KNOWN_REPOSITORY_API_DEPENDENCIES.contains(path))
            .toList();

        assertThat(offenders)
            .as("New repository code must not depend on API DTOs or controllers")
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

    @Test
    void outboxApplicationShouldNotOwnSqlQueries() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/game/backend/outbox/application");
        assertApplicationPackageDoesNotOwnSqlQueries(sourceRoot);
    }

    @Test
    void notificationsApplicationShouldNotOwnSqlQueries() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/game/backend/notifications/application");
        assertApplicationPackageDoesNotOwnSqlQueries(sourceRoot);
    }

    @Test
    void adminStatusServiceShouldNotOwnSqlQueries() throws IOException {
        Path sourceFile = Path.of("src/main/java/com/game/backend/admin/application/AdminStatusService.java");

        assertThat(ownsSqlOrGenericRepositoryCalls(sourceFile))
            .as("AdminStatusService should call named repository methods, not own SQL/query plumbing")
            .isFalse();
    }

    @Test
    void adminAuditServiceShouldNotOwnSqlQueries() throws IOException {
        Path sourceFile = Path.of("src/main/java/com/game/backend/admin/application/AdminAuditService.java");

        assertThat(ownsSqlOrGenericRepositoryCalls(sourceFile))
            .as("AdminAuditService should call named repository methods, not own SQL/query plumbing")
            .isFalse();
    }

    @Test
    void adminControlServiceShouldNotOwnSqlQueries() throws IOException {
        Path sourceFile = Path.of("src/main/java/com/game/backend/admin/application/AdminControlService.java");

        assertThat(ownsSqlOrGenericRepositoryCalls(sourceFile))
            .as("AdminControlService should call named repository methods, not own SQL/query plumbing")
            .isFalse();
    }

    @Test
    void adminItemOperationServiceShouldNotOwnSqlQueries() throws IOException {
        Path sourceFile = Path.of("src/main/java/com/game/backend/admin/application/AdminItemOperationService.java");

        assertThat(ownsSqlOrGenericRepositoryCalls(sourceFile))
            .as("AdminItemOperationService should call named repository methods, not own SQL/query plumbing")
            .isFalse();
    }

    @Test
    void adminMutationIdempotencyServiceShouldNotOwnSqlQueries() throws IOException {
        Path sourceFile = Path.of("src/main/java/com/game/backend/admin/application/AdminMutationIdempotencyService.java");

        assertThat(ownsSqlOrGenericRepositoryCalls(sourceFile))
            .as("AdminMutationIdempotencyService should call named repository methods, not own SQL/query plumbing")
            .isFalse();
    }

    @Test
    void adminPlayerAccessServiceShouldNotOwnSqlQueries() throws IOException {
        Path sourceFile = Path.of("src/main/java/com/game/backend/admin/application/AdminPlayerAccessService.java");

        assertThat(ownsSqlOrGenericRepositoryCalls(sourceFile))
            .as("AdminPlayerAccessService should call named repository methods, not own SQL/query plumbing")
            .isFalse();
    }

    @Test
    void adminAccessMaintenanceServiceShouldNotOwnSqlQueries() throws IOException {
        Path sourceFile = Path.of("src/main/java/com/game/backend/admin/application/AdminAccessMaintenanceService.java");

        assertThat(ownsSqlOrGenericRepositoryCalls(sourceFile))
            .as("AdminAccessMaintenanceService should call named repository methods, not own SQL/query plumbing")
            .isFalse();
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

    private static List<Path> applicationPackageRoots() throws IOException {
        return packageRoots("application");
    }

    private static List<Path> packageRoots(String packageName) throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/game/backend");
        try (var paths = Files.walk(sourceRoot)) {
            return paths
                .filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString().equals(packageName))
                .sorted()
                .toList();
        }
    }

    private static List<Path> filesUnder(List<Path> sourceRoots) throws IOException {
        List<Path> files = new java.util.ArrayList<>();
        for (Path sourceRoot : sourceRoots) {
            try (var paths = Files.walk(sourceRoot)) {
                files.addAll(paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList());
            }
        }
        return files;
    }

    private static boolean isRepositoryInfrastructure(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.contains("/repository/")
            || normalized.contains("/common/persistence/");
    }

    private static boolean isRepositoryPackage(Path path) {
        return path.toString().replace('\\', '/').contains("/repository/");
    }

    private static boolean extendsJdbcRepository(Path path) {
        try {
            return Files.readString(path).contains("extends JdbcRepository");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
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

    private static boolean usesRepositoryOrJdbcFromApi(Path path) {
        try {
            String source = Files.readString(path);
            return source.contains(".repository.")
                || source.contains("JdbcRepository")
                || source.contains("JdbcTemplate")
                || source.contains("jdbcTemplate.");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }

    private static boolean dependsOnApiPackage(Path path) {
        try {
            return Files.readString(path).contains(".api.");
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
