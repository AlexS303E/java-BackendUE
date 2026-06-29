package com.game.backend.serverauth.application;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ServerRouteScopesTest {
    private static final Path BACKEND_JAVA_ROOT = Path.of("src", "main", "java", "com", "game", "backend");
    private static final Pattern SPRING_MAPPING = Pattern.compile("@(Get|Post|Put|Patch|Delete)Mapping\\(\"(/server/[^\"]+)\"\\)");

    @Test
    void shouldResolveRequiredScopesForKnownServerRoutes() {
        assertThat(ServerRouteScopes.requiredScope("POST", "/server/match-profile/build"))
                .contains("match_profile:read");
        assertThat(ServerRouteScopes.requiredScope("POST", "/server/runtime-preset-changes"))
                .contains("runtime_preset_change:write");
        assertThat(ServerRouteScopes.requiredScope("POST", "/server/runtime-events"))
                .contains("runtime_event:write");
    }

    @Test
    void shouldFailClosedForUnknownServerRoute() {
        assertThat(ServerRouteScopes.requiredScope("POST", "/server/not-configured"))
                .isEmpty();
    }

    @Test
    void routeScopesShouldCoverEveryImplementedServerRoute() throws IOException {
        assertThat(ServerRouteScopes.operations())
                .as("Every literal /server/* controller mapping must have an explicit required scope")
                .containsAll(implementedServerOperations());
    }

    private static Set<String> implementedServerOperations() throws IOException {
        Set<String> operations = new LinkedHashSet<>();
        try (var files = Files.walk(BACKEND_JAVA_ROOT)) {
            for (Path file : files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                for (String line : Files.readAllLines(file)) {
                    Matcher matcher = SPRING_MAPPING.matcher(line);
                    if (matcher.find()) {
                        operations.add(matcher.group(1).toUpperCase(Locale.ROOT) + " " + matcher.group(2));
                    }
                }
            }
        }
        return operations;
    }
}
