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
            .toList();

        assertThat(offenders)
            .as("Repository code must not depend on API DTOs or controllers")
            .isEmpty();
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
