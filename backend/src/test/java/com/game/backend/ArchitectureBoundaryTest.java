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
    void repositoryComponentsShouldOnlyLiveInRepositoryPackages() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/game/backend");
        List<Path> offenders;
        try (var paths = Files.walk(sourceRoot)) {
            offenders = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(ArchitectureBoundaryTest::isRepositoryComponent)
                .filter(path -> !isRepositoryPackage(path))
                .toList();
        }

        assertThat(offenders)
            .as("@Repository components must live in repository packages")
            .isEmpty();
    }

    @Test
    void repositoryComponentsShouldExposeTypedRowsInsteadOfRawObjectMaps() throws IOException {
        List<Path> repositoryRoots = packageRoots("repository");

        assertThat(repositoryRoots)
            .as("Architecture guard must discover repository packages automatically")
            .isNotEmpty();

        List<Path> offenders = filesUnder(repositoryRoots).stream()
            .filter(ArchitectureBoundaryTest::isRepositoryComponent)
            .filter(ArchitectureBoundaryTest::exposesRawObjectMapFromTopLevelPublicMethod)
            .toList();

        assertThat(offenders)
            .as("Repository query APIs should expose typed row records instead of raw Map<String, Object> rows")
            .isEmpty();
    }

    @Test
    void webControllersShouldOnlyLiveInApiPackages() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/game/backend");
        List<Path> offenders;
        try (var paths = Files.walk(sourceRoot)) {
            offenders = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(ArchitectureBoundaryTest::isWebController)
                .filter(path -> !isApiPackage(path))
                .toList();
        }

        assertThat(offenders)
            .as("Web controllers and controller advice must live in API packages")
            .isEmpty();
    }

    @Test
    void serviceComponentsShouldNotLiveInApiOrRepositoryPackages() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/game/backend");
        List<Path> offenders;
        try (var paths = Files.walk(sourceRoot)) {
            offenders = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(ArchitectureBoundaryTest::isServiceComponent)
                .filter(path -> isApiPackage(path) || isRepositoryPackage(path))
                .toList();
        }

        assertThat(offenders)
            .as("@Service components must not live in API or repository packages")
            .isEmpty();
    }

    @Test
    void genericComponentsShouldNotLiveInApiOrRepositoryPackages() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/game/backend");
        List<Path> offenders;
        try (var paths = Files.walk(sourceRoot)) {
            offenders = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(ArchitectureBoundaryTest::isGenericComponent)
                .filter(path -> isApiPackage(path) || isRepositoryPackage(path))
                .toList();
        }

        assertThat(offenders)
            .as("@Component classes must not live in API or repository packages")
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
    void apiRecordsShouldStayPureTransportContracts() throws IOException {
        List<Path> apiRoots = packageRoots("api");

        assertThat(apiRoots)
            .as("Architecture guard must discover API packages automatically")
            .isNotEmpty();

        List<Path> offenders = filesUnder(apiRoots).stream()
            .filter(ArchitectureBoundaryTest::isRecordType)
            .filter(path -> dependsOnApplicationPackage(path) || usesRepositoryOrJdbcFromApi(path))
            .toList();

        assertThat(offenders)
            .as("API records should stay as transport contracts without application, repository, or JDBC dependencies")
            .isEmpty();
    }

    @Test
    void everyApiPackageShouldStayIndependentFromTransactionAndPersistenceTypes() throws IOException {
        List<Path> apiRoots = packageRoots("api");

        assertThat(apiRoots)
            .as("Architecture guard must discover API packages automatically")
            .isNotEmpty();

        List<Path> offenders = filesUnder(apiRoots).stream()
            .filter(ArchitectureBoundaryTest::dependsOnTransactionOrPersistenceType)
            .toList();

        assertThat(offenders)
            .as("API code must not own transactions or persistence mappings")
            .isEmpty();
    }

    @Test
    void everyApplicationPackageShouldStayIndependentFromFeatureApiContracts() throws IOException {
        List<Path> applicationRoots = applicationPackageRoots();

        assertThat(applicationRoots)
            .as("Architecture guard must discover application packages automatically")
            .isNotEmpty();

        List<Path> offenders = filesUnder(applicationRoots).stream()
            .filter(ArchitectureBoundaryTest::dependsOnFeatureApiPackage)
            .toList();

        assertThat(offenders)
            .as("Application services must use application commands/results instead of feature API DTOs")
            .isEmpty();
    }

    @Test
    void applicationServiceMethodsShouldExposeTypedResultsInsteadOfRawMaps() throws IOException {
        List<Path> applicationRoots = applicationPackageRoots();

        assertThat(applicationRoots)
            .as("Architecture guard must discover application packages automatically")
            .isNotEmpty();

        List<Path> offenders = filesUnder(applicationRoots).stream()
            .filter(ArchitectureBoundaryTest::isServiceComponent)
            .filter(ArchitectureBoundaryTest::exposesRawMapFromTopLevelPublicMethod)
            .toList();

        assertThat(offenders)
            .as("Application services should expose typed command/result records instead of raw response maps")
            .isEmpty();
    }

    @Test
    void applicationPackageShouldNotOwnTransportResponseMappers() throws IOException {
        List<Path> applicationRoots = applicationPackageRoots();

        assertThat(applicationRoots)
            .as("Architecture guard must discover application packages automatically")
            .isNotEmpty();

        List<Path> offenders = filesUnder(applicationRoots).stream()
            .filter(ArchitectureBoundaryTest::ownsTransportResponseMapper)
            .toList();

        assertThat(offenders)
            .as("Transport response mapping belongs in API controllers, not application records")
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

    @Test
    void everyRepositoryPackageShouldStayIndependentFromApplicationServices() throws IOException {
        List<Path> repositoryRoots = packageRoots("repository");

        assertThat(repositoryRoots)
            .as("Architecture guard must discover repository packages automatically")
            .isNotEmpty();

        List<Path> offenders = filesUnder(repositoryRoots).stream()
            .filter(ArchitectureBoundaryTest::dependsOnApplicationPackage)
            .toList();

        assertThat(offenders)
            .as("Repository code must not depend on application services or records")
            .isEmpty();
    }

    @Test
    void everyRepositoryPackageShouldStayIndependentFromTransportAndSecurityTypes() throws IOException {
        List<Path> repositoryRoots = packageRoots("repository");

        assertThat(repositoryRoots)
            .as("Architecture guard must discover repository packages automatically")
            .isNotEmpty();

        List<Path> offenders = filesUnder(repositoryRoots).stream()
            .filter(ArchitectureBoundaryTest::dependsOnTransportOrSecurityType)
            .toList();

        assertThat(offenders)
            .as("Repository code must not depend on HTTP, servlet, or Spring Security types")
            .isEmpty();
    }

    @Test
    void everyRepositoryPackageShouldStayIndependentFromTransactionAndPersistenceTypes() throws IOException {
        List<Path> repositoryRoots = packageRoots("repository");

        assertThat(repositoryRoots)
            .as("Architecture guard must discover repository packages automatically")
            .isNotEmpty();

        List<Path> offenders = filesUnder(repositoryRoots).stream()
            .filter(ArchitectureBoundaryTest::dependsOnTransactionOrPersistenceType)
            .toList();

        assertThat(offenders)
            .as("Repository code must not own transactions or persistence mappings")
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

    private static boolean isApiPackage(Path path) {
        return path.toString().replace('\\', '/').contains("/api/");
    }

    private static boolean extendsJdbcRepository(Path path) {
        try {
            return Files.readString(path).contains("extends JdbcRepository");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }

    private static boolean isRepositoryComponent(Path path) {
        try {
            return Files.readString(path).contains("@Repository");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }

    private static boolean isWebController(Path path) {
        try {
            String source = Files.readString(path);
            return source.contains("@RestController")
                || source.contains("@Controller")
                || source.contains("@RestControllerAdvice")
                || source.contains("@ControllerAdvice");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }

    private static boolean isServiceComponent(Path path) {
        try {
            return Files.readString(path).contains("@Service");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }

    private static boolean isGenericComponent(Path path) {
        try {
            return Files.readString(path).contains("@Component");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }

    private static boolean isRecordType(Path path) {
        try {
            return Files.readString(path).contains("public record ");
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

    private static boolean dependsOnFeatureApiPackage(Path path) {
        try {
            String source = Files.readString(path);
            return source.contains(".api.")
                && !source.contains(".common.api.");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }

    private static boolean exposesRawMapFromTopLevelPublicMethod(Path path) {
        try {
            return Files.readString(path)
                .lines()
                .map(String::stripTrailing)
                .anyMatch(line -> line.startsWith("    public Map<String, Object>")
                    || line.startsWith("    public List<Map<String, Object>>")
                    || line.startsWith("    protected Map<String, Object>")
                    || line.startsWith("    protected List<Map<String, Object>>"));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }

    private static boolean ownsTransportResponseMapper(Path path) {
        try {
            return Files.readString(path).contains("asResponse()");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }

    private static boolean exposesRawObjectMapFromTopLevelPublicMethod(Path path) {
        try {
            return Files.readString(path)
                .lines()
                .map(String::stripTrailing)
                .anyMatch(line -> line.startsWith("    public Map<String, Object>")
                    || line.startsWith("    public List<Map<String, Object>>")
                    || line.startsWith("    protected Map<String, Object>")
                    || line.startsWith("    protected List<Map<String, Object>>"));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }

    private static boolean dependsOnApplicationPackage(Path path) {
        try {
            return Files.readString(path).contains(".application.");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }

    private static boolean dependsOnTransportOrSecurityType(Path path) {
        try {
            String source = Files.readString(path);
            return source.contains("org.springframework.http.")
                || source.contains("org.springframework.security.")
                || source.contains("org.springframework.web.")
                || source.contains("jakarta.servlet.");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }

    private static boolean dependsOnTransactionOrPersistenceType(Path path) {
        try {
            String source = Files.readString(path);
            return source.contains("@Transactional")
                || source.contains("org.springframework.transaction.")
                || source.contains("jakarta.persistence.")
                || source.contains("javax.persistence.")
                || source.contains("@Entity")
                || source.contains("@Table")
                || source.contains("@Column");
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
