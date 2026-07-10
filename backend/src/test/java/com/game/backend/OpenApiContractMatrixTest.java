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
    private static final Pattern SUCCESS_STATUS = Pattern.compile("^\\s+'(2[0-9]{2})':\\s*$");
    private static final Pattern COMPONENT_RESPONSE_REF = Pattern.compile("\\$ref: '#/components/responses/([^']+)'");
    private static final Pattern COMPONENT_SCHEMA_REF = Pattern.compile("\\$ref: '#/components/schemas/([^']+)'");
    private static final Pattern COMPONENT_PARAMETER_REF = Pattern.compile("\\$ref: '#/components/parameters/([^']+)'");
    private static final Pattern MATRIX_ERROR_CODE = Pattern.compile("^- `([45][0-9]{2}) ([A-Z0-9_]+)`$");
    private static final Pattern OPERATION_ID = Pattern.compile("^\\s+operationId:\\s*([a-zA-Z0-9_]+)\\s*$");
    private static final Pattern PATH_TEMPLATE_VARIABLE = Pattern.compile("\\{([^}]+)}");
    private static final Pattern PARAMETER_NAME = Pattern.compile("^\\s+name:\\s*([a-zA-Z0-9_-]+)\\s*$");
    private static final Pattern PARAMETER_LOCATION = Pattern.compile("^\\s+in:\\s*(path|query|header)\\s*$");
    private static final Pattern TOP_LEVEL_TAG = Pattern.compile("^  - name:\\s*([a-zA-Z0-9_]+)\\s*$");
    private static final Pattern OPERATION_TAGS = Pattern.compile("^\\s+tags:\\s*\\[([^]]+)]\\s*$");
    private static final Pattern OPERATION_SUMMARY = Pattern.compile("^\\s+summary:\\s*(\\S.*)$");
    private static final Pattern SERVER_URL = Pattern.compile("^  - url:\\s*(\\S+)\\s*$");
    private static final Pattern INFO_VERSION = Pattern.compile("(?m)^  version:\\s*([0-9]+\\.[0-9]+\\.[0-9]+)\\s*$");
    private static final Pattern SECURITY_REQUIREMENT = Pattern.compile("^\\s+-?\\s*([A-Za-z_][A-Za-z0-9_]*): \\[]\\s*$");

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
    void openApiSuccessResponsesShouldDeclareSchemaBackedJsonBodies() throws IOException {
        Set<String> successResponsesWithoutSchemas = new LinkedHashSet<>();

        try (var paths = Files.list(OPENAPI_ROOT)) {
            for (Path path : paths
                .filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".yaml"))
                .sorted()
                .toList()) {
                List<String> lines = Files.readAllLines(path);
                for (int index = 0; index < lines.size(); index++) {
                    Matcher statusMatcher = SUCCESS_STATUS.matcher(lines.get(index));
                    if (!statusMatcher.matches() || "204".equals(statusMatcher.group(1))) {
                        continue;
                    }

                    String responseBlock = nestedBlock(lines, index);
                    if (!referencesSchemaBackedJsonBody(lines, responseBlock)) {
                        successResponsesWithoutSchemas.add(path.getFileName() + " " + statusMatcher.group(1));
                    }
                }
            }
        }

        assertThat(successResponsesWithoutSchemas)
            .as("OpenAPI 2xx responses except 204 must declare schema-backed JSON bodies")
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

    @Test
    void openApiWriteOperationsShouldDeclareRequiredSchemaBackedRequestBodies() throws IOException {
        Set<String> bodylessWriteOperations = Set.of("POST /me/notifications/{notification_id}/read");
        Set<String> missingRequiredRequestBodySchema = new LinkedHashSet<>();

        try (var paths = Files.list(OPENAPI_ROOT)) {
            for (Path path : paths
                .filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".yaml"))
                .sorted()
                .toList()) {
                List<String> lines = Files.readAllLines(path);
                String currentPath = null;

                for (int index = 0; index < lines.size(); index++) {
                    Matcher pathMatcher = OPENAPI_PATH.matcher(lines.get(index));
                    if (pathMatcher.matches()) {
                        currentPath = pathMatcher.group(1);
                        continue;
                    }

                    Matcher methodMatcher = OPENAPI_METHOD.matcher(lines.get(index));
                    if (currentPath == null || !methodMatcher.matches()) {
                        continue;
                    }

                    String method = methodMatcher.group(1).toUpperCase(Locale.ROOT);
                    String operation = method + " " + currentPath;
                    if (!Set.of("POST", "PUT", "PATCH").contains(method) || bodylessWriteOperations.contains(operation)) {
                        continue;
                    }

                    String block = operationBlock(lines, index);
                    if (!block.contains("\n      requestBody:")
                        || !block.contains("\n        required: true")
                        || !block.contains("$ref: '#/components/schemas/")) {
                        missingRequiredRequestBodySchema.add(path.getFileName() + " " + operation);
                    }
                }
            }
        }

        assertThat(missingRequiredRequestBodySchema)
            .as("OpenAPI write operations with bodies must declare required schema-backed requestBody")
            .isEmpty();
    }

    @Test
    void openApiPathTemplateVariablesShouldMatchPathParameters() throws IOException {
        Set<String> mismatchedPathParameters = new LinkedHashSet<>();

        try (var paths = Files.list(OPENAPI_ROOT)) {
            for (Path path : paths
                .filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".yaml"))
                .sorted()
                .toList()) {
                List<String> lines = Files.readAllLines(path);
                String currentPath = null;

                for (int index = 0; index < lines.size(); index++) {
                    Matcher pathMatcher = OPENAPI_PATH.matcher(lines.get(index));
                    if (pathMatcher.matches()) {
                        currentPath = pathMatcher.group(1);
                        continue;
                    }

                    Matcher methodMatcher = OPENAPI_METHOD.matcher(lines.get(index));
                    if (currentPath == null || !methodMatcher.matches()) {
                        continue;
                    }

                    Set<String> templateVariables = pathTemplateVariables(currentPath);
                    if (templateVariables.isEmpty()) {
                        continue;
                    }

                    Set<String> pathParameters = pathParameterNames(lines, operationBlock(lines, index));
                    if (!pathParameters.equals(templateVariables)) {
                        mismatchedPathParameters.add(
                            path.getFileName()
                                + " "
                                + methodMatcher.group(1).toUpperCase(Locale.ROOT)
                                + " "
                                + currentPath
                                + " template="
                                + templateVariables
                                + " parameters="
                                + pathParameters
                        );
                    }
                }
            }
        }

        assertThat(mismatchedPathParameters)
            .as("OpenAPI path template variables must match required in:path parameters")
            .isEmpty();
    }

    @Test
    void openApiReusableParametersShouldResolveAndKeepRequiredShape() throws IOException {
        Set<String> criticalRequiredHeaders = Set.of("Idempotency-Key", "X-Admin-Confirm", "X-Server-Id", "If-Match");
        Set<String> invalidParameters = new LinkedHashSet<>();

        try (var paths = Files.list(OPENAPI_ROOT)) {
            for (Path path : paths
                .filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".yaml"))
                .sorted()
                .toList()) {
                List<String> lines = Files.readAllLines(path);
                String contract = String.join("\n", lines);
                Matcher parameterRefMatcher = COMPONENT_PARAMETER_REF.matcher(contract);
                while (parameterRefMatcher.find()) {
                    String parameterName = parameterRefMatcher.group(1);
                    String parameterBlock = namedComponentBlock(lines, "parameters", parameterName);
                    if (parameterBlock.isBlank()) {
                        invalidParameters.add(path.getFileName() + " " + parameterName + " unresolved");
                        continue;
                    }

                    String documentedName = parameterName(parameterBlock);
                    String location = parameterLocation(parameterBlock);
                    if (documentedName == null || location == null || !parameterBlock.contains("\n      schema:")) {
                        invalidParameters.add(path.getFileName() + " " + parameterName + " missing name/in/schema");
                        continue;
                    }

                    if ("path".equals(location) && !parameterBlock.contains("\n      required: true")) {
                        invalidParameters.add(path.getFileName() + " " + parameterName + " path parameter not required");
                    }
                    if ("query".equals(location) && !parameterBlock.contains("\n      required: ")) {
                        invalidParameters.add(path.getFileName() + " " + parameterName + " query parameter missing explicit required flag");
                    }
                    if (criticalRequiredHeaders.contains(documentedName) && !parameterBlock.contains("\n      required: true")) {
                        invalidParameters.add(path.getFileName() + " " + parameterName + " critical header not required");
                    }
                }
            }
        }

        assertThat(invalidParameters)
            .as("OpenAPI reusable parameter refs must resolve and preserve required path/header/query shape")
            .isEmpty();
    }

    @Test
    void openApiSchemaReferencesShouldResolveToStructuredComponents() throws IOException {
        Set<String> unresolvedOrUnstructuredSchemas = new LinkedHashSet<>();

        try (var paths = Files.list(OPENAPI_ROOT)) {
            for (Path path : paths
                .filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".yaml"))
                .sorted()
                .toList()) {
                List<String> lines = Files.readAllLines(path);
                String contract = String.join("\n", lines);
                Matcher schemaRefMatcher = COMPONENT_SCHEMA_REF.matcher(contract);
                while (schemaRefMatcher.find()) {
                    String schemaName = schemaRefMatcher.group(1);
                    String schemaBlock = namedComponentBlock(lines, "schemas", schemaName);
                    if (schemaBlock.isBlank()) {
                        unresolvedOrUnstructuredSchemas.add(path.getFileName() + " " + schemaName + " unresolved");
                        continue;
                    }
                    if (!isStructuredSchemaComponent(schemaBlock)) {
                        unresolvedOrUnstructuredSchemas.add(path.getFileName() + " " + schemaName + " missing schema shape");
                    }
                }
            }
        }

        assertThat(unresolvedOrUnstructuredSchemas)
            .as("OpenAPI schema refs must resolve to structured reusable schema components")
            .isEmpty();
    }

    @Test
    void openApiReusableResponsesShouldResolveAndKeepDescriptionOrJsonSchema() throws IOException {
        Set<String> invalidResponses = new LinkedHashSet<>();

        try (var paths = Files.list(OPENAPI_ROOT)) {
            for (Path path : paths
                .filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".yaml"))
                .sorted()
                .toList()) {
                List<String> lines = Files.readAllLines(path);
                String contract = String.join("\n", lines);
                Matcher responseRefMatcher = COMPONENT_RESPONSE_REF.matcher(contract);
                while (responseRefMatcher.find()) {
                    String responseName = responseRefMatcher.group(1);
                    String responseBlock = namedComponentBlock(lines, "responses", responseName);
                    if (responseBlock.isBlank()) {
                        invalidResponses.add(path.getFileName() + " " + responseName + " unresolved");
                        continue;
                    }
                    if (!responseBlock.contains("\n      description:")) {
                        invalidResponses.add(path.getFileName() + " " + responseName + " missing description");
                    }
                    if (responseBlock.contains("application/json:") && !COMPONENT_SCHEMA_REF.matcher(responseBlock).find()) {
                        invalidResponses.add(path.getFileName() + " " + responseName + " json response missing schema ref");
                    }
                }
            }
        }

        assertThat(invalidResponses)
            .as("OpenAPI response refs must resolve and keep description/schema-backed JSON shape")
            .isEmpty();
    }

    @Test
    void openApiOperationsShouldUseDeclaredSingleSnakeCaseTags() throws IOException {
        Set<String> invalidTags = new LinkedHashSet<>();

        try (var paths = Files.list(OPENAPI_ROOT)) {
            for (Path path : paths
                .filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".yaml"))
                .sorted()
                .toList()) {
                List<String> lines = Files.readAllLines(path);
                Set<String> declaredTags = declaredTags(lines);
                String currentPath = null;

                for (int index = 0; index < lines.size(); index++) {
                    Matcher pathMatcher = OPENAPI_PATH.matcher(lines.get(index));
                    if (pathMatcher.matches()) {
                        currentPath = pathMatcher.group(1);
                        continue;
                    }

                    Matcher methodMatcher = OPENAPI_METHOD.matcher(lines.get(index));
                    if (currentPath == null || !methodMatcher.matches()) {
                        continue;
                    }

                    String operation = path.getFileName()
                        + " "
                        + methodMatcher.group(1).toUpperCase(Locale.ROOT)
                        + " "
                        + currentPath;
                    Set<String> operationTags = operationTags(operationBlock(lines, index));
                    if (operationTags.size() != 1) {
                        invalidTags.add(operation + " must declare exactly one tag");
                        continue;
                    }

                    String tag = operationTags.iterator().next();
                    if (!tag.matches("[a-z][a-z0-9_]*") || !declaredTags.contains(tag)) {
                        invalidTags.add(operation + " invalid tag " + tag);
                    }
                }
            }
        }

        assertThat(invalidTags)
            .as("OpenAPI operations must use exactly one declared snake_case tag")
            .isEmpty();
    }

    @Test
    void openApiOperationsShouldKeepConcreteSummaries() throws IOException {
        Set<String> invalidSummaries = new LinkedHashSet<>();

        try (var paths = Files.list(OPENAPI_ROOT)) {
            for (Path path : paths
                .filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".yaml"))
                .sorted()
                .toList()) {
                List<String> lines = Files.readAllLines(path);
                String currentPath = null;

                for (int index = 0; index < lines.size(); index++) {
                    Matcher pathMatcher = OPENAPI_PATH.matcher(lines.get(index));
                    if (pathMatcher.matches()) {
                        currentPath = pathMatcher.group(1);
                        continue;
                    }

                    Matcher methodMatcher = OPENAPI_METHOD.matcher(lines.get(index));
                    if (currentPath == null || !methodMatcher.matches()) {
                        continue;
                    }

                    String operation = path.getFileName()
                        + " "
                        + methodMatcher.group(1).toUpperCase(Locale.ROOT)
                        + " "
                        + currentPath;
                    String summary = operationSummary(operationBlock(lines, index));
                    if (summary == null || summary.length() < 10 || summary.toLowerCase(Locale.ROOT).contains("todo")) {
                        invalidSummaries.add(operation);
                    }
                }
            }
        }

        assertThat(invalidSummaries)
            .as("OpenAPI operations must keep concrete non-placeholder summaries")
            .isEmpty();
    }

    @Test
    void openApiServersShouldRemainRelative() throws IOException {
        Set<String> absoluteServers = new LinkedHashSet<>();

        try (var paths = Files.list(OPENAPI_ROOT)) {
            for (Path path : paths
                .filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".yaml"))
                .sorted()
                .toList()) {
                for (String line : Files.readAllLines(path)) {
                    Matcher matcher = SERVER_URL.matcher(line);
                    if (matcher.matches() && !"/".equals(matcher.group(1))) {
                        absoluteServers.add(path.getFileName() + " " + matcher.group(1));
                    }
                }
            }
        }

        assertThat(absoluteServers)
            .as("OpenAPI server URLs must stay relative so generated clients do not bake dev/prod hosts")
            .isEmpty();
    }

    @Test
    void openApiDocumentsShouldKeepStableInfoMetadata() throws IOException {
        Set<String> invalidMetadata = new LinkedHashSet<>();

        try (var paths = Files.list(OPENAPI_ROOT)) {
            for (Path path : paths
                .filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".yaml"))
                .sorted()
                .toList()) {
                String contract = Files.readString(path);
                if (!contract.startsWith("openapi: 3.1.0\n")) {
                    invalidMetadata.add(path.getFileName() + " openapi version");
                }
                if (!contract.contains("\ninfo:\n") || !contract.contains("\n  title: UE5 Backend ")) {
                    invalidMetadata.add(path.getFileName() + " info.title");
                }
                if (!INFO_VERSION.matcher(contract).find()) {
                    invalidMetadata.add(path.getFileName() + " info.version");
                }
            }
        }

        assertThat(invalidMetadata)
            .as("OpenAPI documents must keep stable versioned info metadata")
            .isEmpty();
    }

    @Test
    void openApiSecurityRequirementsShouldResolveToDeclaredSchemes() throws IOException {
        Set<String> invalidSecurityRequirements = new LinkedHashSet<>();

        try (var paths = Files.list(OPENAPI_ROOT)) {
            for (Path path : paths
                .filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".yaml"))
                .sorted()
                .toList()) {
                List<String> lines = Files.readAllLines(path);
                for (String line : lines) {
                    Matcher matcher = SECURITY_REQUIREMENT.matcher(line);
                    if (!matcher.matches()) {
                        continue;
                    }

                    String schemeName = matcher.group(1);
                    String schemeBlock = namedComponentBlock(lines, "securitySchemes", schemeName);
                    if (schemeBlock.isBlank() || !schemeBlock.contains("\n      type:")) {
                        invalidSecurityRequirements.add(path.getFileName() + " " + schemeName);
                    }
                }
            }
        }

        assertThat(invalidSecurityRequirements)
            .as("OpenAPI security requirements must resolve to declared securitySchemes")
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

    private static boolean referencesSchemaBackedJsonBody(List<String> lines, String responseBlock) {
        if (responseBlock.contains("application/json:") && COMPONENT_SCHEMA_REF.matcher(responseBlock).find()) {
            return true;
        }

        Matcher responseRefMatcher = COMPONENT_RESPONSE_REF.matcher(responseBlock);
        while (responseRefMatcher.find()) {
            String responseName = responseRefMatcher.group(1);
            String componentBlock = namedComponentBlock(lines, "responses", responseName);
            if (componentBlock.contains("application/json:") && COMPONENT_SCHEMA_REF.matcher(componentBlock).find()) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> pathTemplateVariables(String path) {
        Set<String> variables = new LinkedHashSet<>();
        Matcher matcher = PATH_TEMPLATE_VARIABLE.matcher(path);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        return variables;
    }

    private static Set<String> pathParameterNames(List<String> contractLines, String operationBlock) {
        Set<String> parameterNames = new LinkedHashSet<>();
        Matcher parameterRefMatcher = COMPONENT_PARAMETER_REF.matcher(operationBlock);
        while (parameterRefMatcher.find()) {
            String parameterBlock = namedComponentBlock(contractLines, "parameters", parameterRefMatcher.group(1));
            if (parameterBlock.contains("\n      in: path")) {
                for (String line : parameterBlock.split("\\R")) {
                    Matcher nameMatcher = PARAMETER_NAME.matcher(line);
                    if (nameMatcher.matches()) {
                        parameterNames.add(nameMatcher.group(1));
                    }
                }
            }
        }
        return parameterNames;
    }

    private static String parameterName(String parameterBlock) {
        for (String line : parameterBlock.split("\\R")) {
            Matcher matcher = PARAMETER_NAME.matcher(line);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private static String parameterLocation(String parameterBlock) {
        for (String line : parameterBlock.split("\\R")) {
            Matcher matcher = PARAMETER_LOCATION.matcher(line);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private static boolean isStructuredSchemaComponent(String schemaBlock) {
        return schemaBlock.contains("\n      type:")
            || schemaBlock.contains("\n      allOf:")
            || schemaBlock.contains("\n      oneOf:")
            || schemaBlock.contains("\n      anyOf:")
            || schemaBlock.contains("\n      enum:")
            || schemaBlock.contains("\n      const:");
    }

    private static Set<String> declaredTags(List<String> lines) {
        Set<String> tags = new LinkedHashSet<>();
        for (String line : lines) {
            Matcher matcher = TOP_LEVEL_TAG.matcher(line);
            if (matcher.matches()) {
                tags.add(matcher.group(1));
            }
        }
        return tags;
    }

    private static Set<String> operationTags(String operationBlock) {
        Set<String> tags = new LinkedHashSet<>();
        for (String line : operationBlock.split("\\R")) {
            Matcher matcher = OPERATION_TAGS.matcher(line);
            if (matcher.matches()) {
                for (String tag : matcher.group(1).split(",")) {
                    tags.add(tag.trim());
                }
            }
        }
        return tags;
    }

    private static String operationSummary(String operationBlock) {
        for (String line : operationBlock.split("\\R")) {
            Matcher matcher = OPERATION_SUMMARY.matcher(line);
            if (matcher.matches()) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }

    private static int leadingSpaces(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces;
    }
}
