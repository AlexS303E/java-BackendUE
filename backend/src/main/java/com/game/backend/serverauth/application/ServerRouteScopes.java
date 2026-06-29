package com.game.backend.serverauth.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

final class ServerRouteScopes {
    private static final List<RouteScope> ROUTES = List.of(
            new RouteScope("POST", "/server/match-profile/build", "match_profile:read"),
            new RouteScope("POST", "/server/runtime-preset-changes", "runtime_preset_change:write"),
            new RouteScope("POST", "/server/runtime-events", "runtime_event:write")
    );

    private ServerRouteScopes() {
    }

    static Optional<String> requiredScope(String method, String path) {
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        return ROUTES.stream()
                .filter(route -> route.method().equals(normalizedMethod))
                .filter(route -> route.path().equals(path))
                .map(RouteScope::scope)
                .findFirst();
    }

    static Set<String> operations() {
        Set<String> operations = new LinkedHashSet<>();
        for (RouteScope route : ROUTES) {
            operations.add(route.method() + " " + route.path());
        }
        return operations;
    }

    private record RouteScope(String method, String path, String scope) {
    }
}
