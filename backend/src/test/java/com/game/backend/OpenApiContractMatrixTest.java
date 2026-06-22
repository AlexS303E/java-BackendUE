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
}
