package com.game.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiContractMatrixTest {
    private static final Path OPENAPI_ROOT = Path.of("..", "contracts", "openapi");
    private static final Path PUBLIC_API = OPENAPI_ROOT.resolve("public-api.yaml");
    private static final Path ADMIN_API = OPENAPI_ROOT.resolve("admin-api.yaml");
    private static final Path SERVER_API = OPENAPI_ROOT.resolve("server-api.yaml");
    private static final Path MATRIX = Path.of("..", "docs", "openapi-contract-test-matrix.md");
    private static final Path BACKEND_JAVA_ROOT = Path.of("src", "main", "java", "com", "game", "backend");
    private static final Pattern OPENAPI_PATH = Pattern.compile("^  (/[^:]+):\\s*$");
    private static final Pattern OPENAPI_METHOD = Pattern.compile("^    (get|post|put|patch|delete):\\s*$");
    private static final Pattern MATRIX_ENDPOINT = Pattern.compile("\\| `([A-Z]+) (/[^`]+)` \\|");
    private static final Pattern SPRING_MAPPING = Pattern.compile("@(Get|Post|Put|Patch|Delete)Mapping\\(\"(/[^\"]+)\"\\)");
    private static final Pattern ERROR_STATUS = Pattern.compile("^\\s+'([45][0-9]{2})':\\s*$");
    private static final Pattern COMPONENT_RESPONSE_REF = Pattern.compile("\\$ref: '#/components/responses/([^']+)'");
    private static final Pattern COMPONENT_SCHEMA_REF = Pattern.compile("\\$ref: '#/components/schemas/([^']+)'");
    private static final Pattern MATRIX_ERROR_CODE = Pattern.compile("^- `([45][0-9]{2}) ([A-Z0-9_]+)`$");
    private static final Pattern OPERATION_ID = Pattern.compile("^\\s+operationId:\\s*([a-zA-Z0-9_]+)\\s*$");

    @Test
    void contractMatrixCoversEveryOpenApiOperation() throws IOException {
        Set<String> openApiOperations = new LinkedHashSet<>();
        try (var paths = Files.list(OPENAPI_ROOT)) {
            for (Path path : paths
                .filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".yaml"))
                .sorted()
                .toList()) {
                openApiOperations.addAll(openApiOperations(path));
            }
        }

        assertThat(matrixOperations())
            .as("docs/openapi-contract-test-matrix.md must list every OpenAPI operation")
            .containsAll(openApiOperations);
    }

    @Test
    void adminWriteOperationsDocumentConfirmationGate() throws IOException {
        Set<String> missingConfirmation = new LinkedHashSet<>();

        for (String line : Files.readAllLines(MATRIX)) {
            Matcher matcher = MATRIX_ENDPOINT.matcher(line);
            if (!matcher.find()) {
                continue;
            }

            String method = matcher.group(1);
            String endpoint = matcher.group(2);
            if ("POST".equals(method) && endpoint.startsWith("/admin/") && !line.contains("X-Admin-Confirm")) {
                missingConfirmation.add(method + " " + endpoint);
            }
        }

        assertThat(missingConfirmation)
            .as("Admin write actions must document the X-Admin-Confirm stage gate")
            .isEmpty();
    }

    @Test
    void adminWriteOperationsDocumentIdempotencyKey() throws IOException {
        Set<String> missingIdempotency = new LinkedHashSet<>();

        for (String line : Files.readAllLines(MATRIX)) {
            Matcher matcher = MATRIX_ENDPOINT.matcher(line);
            if (!matcher.find()) {
                continue;
            }

            String method = matcher.group(1);
            String endpoint = matcher.group(2);
            if ("POST".equals(method) && endpoint.startsWith("/admin/") && !line.contains("Idempotency-Key")) {
                missingIdempotency.add(method + " " + endpoint);
            }
        }

        assertThat(missingIdempotency)
            .as("Admin write actions must document the Idempotency-Key contract")
            .isEmpty();
    }

    @Test
    void adminControlWritesDocumentReasonAuditContext() throws IOException {
        Set<String> missingReasonContext = new LinkedHashSet<>();

        for (String line : Files.readAllLines(MATRIX)) {
            Matcher matcher = MATRIX_ENDPOINT.matcher(line);
            if (!matcher.find()) {
                continue;
            }

            String method = matcher.group(1);
            String endpoint = matcher.group(2);
            if ("POST".equals(method)
                && endpoint.startsWith("/admin/control/")
                && !line.contains("reason/comment audit context")) {
                missingReasonContext.add(method + " " + endpoint);
            }
        }

        assertThat(missingReasonContext)
            .as("Legacy admin control writes must document reason/comment audit context")
            .isEmpty();
    }

    @Test
    void adminOpenApiPostOperationsRequireIdempotencyKey() throws IOException {
        Set<String> missingIdempotency = new LinkedHashSet<>();
        List<String> lines = Files.readAllLines(ADMIN_API);

        for (int index = 0; index < lines.size(); index++) {
            Matcher pathMatcher = OPENAPI_PATH.matcher(lines.get(index));
            if (!pathMatcher.matches() || !pathMatcher.group(1).startsWith("/admin/")) {
                continue;
            }

            String path = pathMatcher.group(1);
            if (index + 1 >= lines.size() || !lines.get(index + 1).trim().equals("post:")) {
                continue;
            }

            String block = operationBlock(lines, index + 1);
            if (!block.contains("#/components/parameters/idempotency_key_header")) {
                missingIdempotency.add("POST " + path);
            }
        }

        assertThat(missingIdempotency)
            .as("Admin POST operations in OpenAPI must require Idempotency-Key")
            .isEmpty();
    }

    @Test
    void adminOpenApiPostOperationsRequireConfirmationHeader() throws IOException {
        Set<String> missingConfirmation = new LinkedHashSet<>();
        List<String> lines = Files.readAllLines(ADMIN_API);

        for (int index = 0; index < lines.size(); index++) {
            Matcher pathMatcher = OPENAPI_PATH.matcher(lines.get(index));
            if (!pathMatcher.matches() || !pathMatcher.group(1).startsWith("/admin/")) {
                continue;
            }

            String path = pathMatcher.group(1);
            if (index + 1 >= lines.size() || !lines.get(index + 1).trim().equals("post:")) {
                continue;
            }

            String block = operationBlock(lines, index + 1);
            if (!block.contains("#/components/parameters/admin_confirm_header")) {
                missingConfirmation.add("POST " + path);
            }
        }

        assertThat(missingConfirmation)
            .as("Admin POST operations in OpenAPI must require X-Admin-Confirm")
            .isEmpty();
    }

    @Test
    void adminOpenApiCoversEveryImplementedAdminRoute() throws IOException {
        assertThat(openApiOperations(ADMIN_API))
            .as("contracts/openapi/admin-api.yaml must list every literal /admin/* controller mapping")
            .containsAll(implementedOperations("/admin/"));
    }

    @Test
    void serverOpenApiCoversEveryImplementedServerRoute() throws IOException {
        assertThat(openApiOperations(SERVER_API))
            .as("contracts/openapi/server-api.yaml must list every literal /server/* controller mapping")
            .containsAll(implementedOperations("/server/"));
    }

    @Test
    void publicOpenApiCoversEveryImplementedPublicRoute() throws IOException {
        assertThat(openApiOperations(PUBLIC_API))
            .as("contracts/openapi/public-api.yaml must list every literal /auth, /catalog, and /me controller mapping")
            .containsAll(implementedOperations(Set.of("/auth/", "/catalog/", "/me/")));
    }

    @Test
    void publicOpenApiAuthenticatedOperationsRequireBearerAuth() throws IOException {
        Set<String> missingBearerAuth = new LinkedHashSet<>();
        List<String> lines = Files.readAllLines(PUBLIC_API);

        for (int index = 0; index < lines.size(); index++) {
            Matcher pathMatcher = OPENAPI_PATH.matcher(lines.get(index));
            if (!pathMatcher.matches()) {
                continue;
            }

            String path = pathMatcher.group(1);
            if (!path.startsWith("/me/") && !"/auth/logout".equals(path)) {
                continue;
            }

            for (int methodIndex = index + 1; methodIndex < lines.size(); methodIndex++) {
                String line = lines.get(methodIndex);
                if (OPENAPI_PATH.matcher(line).matches()) {
                    break;
                }
                Matcher methodMatcher = OPENAPI_METHOD.matcher(line);
                if (methodMatcher.matches()) {
                    String block = operationBlock(lines, methodIndex);
                    if (!block.contains("BearerAuth: []")) {
                        missingBearerAuth.add(methodMatcher.group(1).toUpperCase(Locale.ROOT) + " " + path);
                    }
                }
            }
        }

        assertThat(missingBearerAuth)
            .as("Authenticated public OpenAPI operations must require BearerAuth")
            .isEmpty();
    }

    @Test
    void serverOpenApiOperationsRequireMutualTlsAndServerIdentity() throws IOException {
        Set<String> missingServerSecurity = new LinkedHashSet<>();
        List<String> lines = Files.readAllLines(SERVER_API);

        for (int index = 0; index < lines.size(); index++) {
            Matcher pathMatcher = OPENAPI_PATH.matcher(lines.get(index));
            if (!pathMatcher.matches() || !pathMatcher.group(1).startsWith("/server/")) {
                continue;
            }

            String path = pathMatcher.group(1);
            for (int methodIndex = index + 1; methodIndex < lines.size(); methodIndex++) {
                String line = lines.get(methodIndex);
                if (OPENAPI_PATH.matcher(line).matches()) {
                    break;
                }
                Matcher methodMatcher = OPENAPI_METHOD.matcher(line);
                if (methodMatcher.matches()) {
                    String block = operationBlock(lines, methodIndex);
                    if (!block.contains("ServerMutualTls: []")
                        || !block.contains("ServerIdentityHeader: []")
                        || !block.contains("#/components/parameters/server_id_header")) {
                        missingServerSecurity.add(methodMatcher.group(1).toUpperCase(Locale.ROOT) + " " + path);
                    }
                }
            }
        }

        assertThat(missingServerSecurity)
            .as("Dedicated Server OpenAPI operations must require mTLS and X-Server-Id")
            .isEmpty();
    }

    @Test
    void adminOpenApiOperationsMustKeepAdminTokenSecurity() throws IOException {
        String contract = Files.readString(ADMIN_API);
        assertThat(contract)
            .as("Admin OpenAPI contract must declare global X-Admin-Token security")
            .containsPattern("(?m)^security:\\R\\s+- admin_token_header: \\[\\]");

        Set<String> overriddenWithoutAdminToken = new LinkedHashSet<>();
        List<String> lines = Files.readAllLines(ADMIN_API);

        for (int index = 0; index < lines.size(); index++) {
            Matcher pathMatcher = OPENAPI_PATH.matcher(lines.get(index));
            if (!pathMatcher.matches() || !pathMatcher.group(1).startsWith("/admin/")) {
                continue;
            }

            String path = pathMatcher.group(1);
            for (int methodIndex = index + 1; methodIndex < lines.size(); methodIndex++) {
                String line = lines.get(methodIndex);
                if (OPENAPI_PATH.matcher(line).matches()) {
                    break;
                }
                Matcher methodMatcher = OPENAPI_METHOD.matcher(line);
                if (methodMatcher.matches()) {
                    String block = operationBlock(lines, methodIndex);
                    if (block.contains("\n      security:") && !block.contains("admin_token_header: []")) {
                        overriddenWithoutAdminToken.add(methodMatcher.group(1).toUpperCase(Locale.ROOT) + " " + path);
                    }
                }
            }
        }

        assertThat(overriddenWithoutAdminToken)
            .as("Admin OpenAPI operations must not override global X-Admin-Token security")
            .isEmpty();
    }

    @Test
    void openApiErrorResponsesUseProblemDetailsCompatibleSchemas() throws IOException {
        Set<String> nonProblemDetailsErrors = new LinkedHashSet<>();
        try (var paths = Files.list(OPENAPI_ROOT)) {
            for (Path path : paths
                .filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".yaml"))
                .sorted()
                .toList()) {
                List<String> lines = Files.readAllLines(path);
                for (int index = 0; index < lines.size(); index++) {
                    Matcher statusMatcher = ERROR_STATUS.matcher(lines.get(index));
                    if (!statusMatcher.matches()) {
                        continue;
                    }

                    String responseBlock = nestedBlock(lines, index);
                    Matcher responseRefMatcher = COMPONENT_RESPONSE_REF.matcher(responseBlock);
                    if (!responseRefMatcher.find()) {
                        nonProblemDetailsErrors.add(path.getFileName() + " " + statusMatcher.group(1) + " uses inline or missing error response");
                        continue;
                    }

                    String responseName = responseRefMatcher.group(1);
                    String componentBlock = namedComponentBlock(lines, "responses", responseName);
                    if (!referencesProblemDetailsCompatibleSchema(lines, componentBlock)) {
                        nonProblemDetailsErrors.add(path.getFileName() + " " + statusMatcher.group(1) + " -> " + responseName);
                    }
                }
            }
        }

        assertThat(nonProblemDetailsErrors)
            .as("OpenAPI 4xx/5xx responses must resolve to ProblemDetails-compatible schemas")
            .isEmpty();
    }

    @Test
    void contractMatrixMinimumErrorCodesShouldStayPresentInOpenApiContracts() throws IOException {
        String openApiContracts = Files.readString(PUBLIC_API)
            + "\n"
            + Files.readString(SERVER_API)
            + "\n"
            + Files.readString(ADMIN_API);

        Set<String> missingErrorCodes = new LinkedHashSet<>();
        for (String line : Files.readAllLines(MATRIX)) {
            Matcher matcher = MATRIX_ERROR_CODE.matcher(line);
            if (matcher.matches() && !openApiContracts.contains(matcher.group(2))) {
                missingErrorCodes.add(matcher.group(1) + " " + matcher.group(2));
            }
        }

        assertThat(missingErrorCodes)
            .as("docs/openapi-contract-test-matrix.md minimum checked error codes must stay represented in OpenAPI")
            .isEmpty();
    }

    @Test
    void openApiOperationIdsShouldStayUniqueAndSnakeCase() throws IOException {
        Set<String> operationIds = new LinkedHashSet<>();
        Set<String> duplicateOrInvalidOperationIds = new LinkedHashSet<>();
        try (var paths = Files.list(OPENAPI_ROOT)) {
            for (Path path : paths
                .filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".yaml"))
                .sorted()
                .toList()) {
                for (String line : Files.readAllLines(path)) {
                    Matcher matcher = OPERATION_ID.matcher(line);
                    if (!matcher.matches()) {
                        continue;
                    }

                    String operationId = matcher.group(1);
                    if (!operationId.matches("[a-z][a-z0-9_]*") || !operationIds.add(operationId)) {
                        duplicateOrInvalidOperationIds.add(path.getFileName() + " " + operationId);
                    }
                }
            }
        }

        assertThat(duplicateOrInvalidOperationIds)
            .as("OpenAPI operationId values must be unique and snake_case for generated clients")
            .isEmpty();
    }

    private static Set<String> openApiOperations(Path path) throws IOException {
        Set<String> operations = new LinkedHashSet<>();
        String currentPath = null;

        for (String line : Files.readAllLines(path)) {
            Matcher pathMatcher = OPENAPI_PATH.matcher(line);
            if (pathMatcher.matches()) {
                currentPath = pathMatcher.group(1);
                continue;
            }

            Matcher methodMatcher = OPENAPI_METHOD.matcher(line);
            if (currentPath != null && methodMatcher.matches()) {
                operations.add(methodMatcher.group(1).toUpperCase(Locale.ROOT) + " " + currentPath);
            }
        }

        return operations;
    }

    private static Set<String> implementedOperations(String pathPrefix) throws IOException {
        return implementedOperations(Set.of(pathPrefix));
    }

    private static Set<String> implementedOperations(Set<String> pathPrefixes) throws IOException {
        Set<String> operations = new LinkedHashSet<>();
        try (var files = Files.walk(BACKEND_JAVA_ROOT)) {
            for (Path file : files
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .toList()) {
                for (String line : Files.readAllLines(file)) {
                    Matcher matcher = SPRING_MAPPING.matcher(line);
                    if (matcher.find() && startsWithAny(matcher.group(2), pathPrefixes)) {
                        operations.add(matcher.group(1).toUpperCase(Locale.ROOT) + " " + matcher.group(2));
                    }
                }
            }
        }
        return operations;
    }

    private static boolean startsWithAny(String path, Set<String> prefixes) {
        return prefixes.stream().anyMatch(path::startsWith);
    }

    private static Set<String> matrixOperations() throws IOException {
        Set<String> operations = new LinkedHashSet<>();
        List<String> lines = Files.readAllLines(MATRIX);

        for (String line : lines) {
            Matcher matcher = MATRIX_ENDPOINT.matcher(line);
            if (matcher.find()) {
                operations.add(matcher.group(1) + " " + matcher.group(2));
            }
        }

        return operations;
    }

    private static String operationBlock(List<String> lines, int methodLineIndex) {
        StringBuilder block = new StringBuilder();
        for (int index = methodLineIndex; index < lines.size(); index++) {
            String line = lines.get(index);
            if (index > methodLineIndex && (OPENAPI_PATH.matcher(line).matches() || OPENAPI_METHOD.matcher(line).matches())) {
                break;
            }
            block.append(line).append('\n');
        }
        return block.toString();
    }

    private static String nestedBlock(List<String> lines, int startIndex) {
        int baseIndent = leadingSpaces(lines.get(startIndex));
        StringBuilder block = new StringBuilder();
        for (int index = startIndex; index < lines.size(); index++) {
            String line = lines.get(index);
            if (index > startIndex && !line.isBlank() && leadingSpaces(line) <= baseIndent) {
                break;
            }
            block.append(line).append('\n');
        }
        return block.toString();
    }

    private static String namedComponentBlock(List<String> lines, String sectionName, String componentName) {
        String sectionHeader = "  " + sectionName + ":";
        String componentHeader = "    " + componentName + ":";
        boolean inSection = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.equals(sectionHeader)) {
                inSection = true;
                continue;
            }
            if (inSection && line.startsWith("  ") && !line.startsWith("    ")) {
                return "";
            }
            if (inSection && line.equals(componentHeader)) {
                return nestedBlock(lines, index);
            }
        }
        return "";
    }

    private static boolean referencesProblemDetailsCompatibleSchema(List<String> lines, String responseBlock) {
        if (responseBlock.contains("#/components/schemas/ProblemDetails")) {
            return true;
        }

        Matcher schemaRefMatcher = COMPONENT_SCHEMA_REF.matcher(responseBlock);
        while (schemaRefMatcher.find()) {
            String schemaName = schemaRefMatcher.group(1);
            String schemaBlock = namedComponentBlock(lines, "schemas", schemaName);
            if (schemaBlock.contains("#/components/schemas/ProblemDetails")) {
                return true;
            }
        }
        return false;
    }

    private static int leadingSpaces(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces;
    }
}
