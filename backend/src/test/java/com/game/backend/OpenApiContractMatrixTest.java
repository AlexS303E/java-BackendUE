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
    private static final Path ADMIN_API = OPENAPI_ROOT.resolve("admin-api.yaml");
    private static final Path MATRIX = Path.of("..", "docs", "openapi-contract-test-matrix.md");
    private static final Pattern OPENAPI_PATH = Pattern.compile("^  (/[^:]+):\\s*$");
    private static final Pattern OPENAPI_METHOD = Pattern.compile("^    (get|post|put|patch|delete):\\s*$");
    private static final Pattern MATRIX_ENDPOINT = Pattern.compile("\\| `([A-Z]+) (/[^`]+)` \\|");

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
}
