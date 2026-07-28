package com.game.backend.admin.application;

import com.game.backend.common.network.TrustedClientIpResolver;
import com.game.backend.common.network.TrustedProxyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthenticationFilterTest {
    private static final Path BACKEND_JAVA_ROOT = Path.of("src", "main", "java", "com", "game", "backend");
    private static final Pattern SPRING_MAPPING = Pattern.compile("@(Get|Post|Put|Patch|Delete)Mapping\\(\"(/admin/[^\"]+)\"\\)");

    @Test
    void shouldAllowConfiguredIpAndRole() throws Exception {
        AdminAuthenticationFilter filter = filter(List.of("10.10.0.0/16"), List.of("status"));
        MockHttpServletRequest request = request("GET", "/admin/status/overview", "10.10.1.20");
        request.addHeader("X-Admin-Token", "token");

        CountingChain chain = new CountingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.count()).isEqualTo(1);
    }

    @Test
    void shouldRejectDisallowedIpAndMissingRole() throws Exception {
        AdminAuthenticationFilter filter = filter(List.of("10.10.0.0/16"), List.of("status"));
        MockHttpServletRequest request = request("POST", "/admin/control/outbox/retry-failed", "192.168.1.10");
        request.addHeader("X-Admin-Token", "token");
        request.addHeader("X-Admin-Confirm", "true");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new CountingChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ADMIN_IP_FORBIDDEN");

        AdminAuthenticationFilter roleFilter = filter(List.of(), List.of("status"));
        MockHttpServletRequest roleRequest = request("POST", "/admin/control/outbox/retry-failed", "127.0.0.1");
        roleRequest.addHeader("X-Admin-Token", "token");
        roleRequest.addHeader("X-Admin-Confirm", "true");

        MockHttpServletResponse roleResponse = new MockHttpServletResponse();
        roleFilter.doFilter(roleRequest, roleResponse, new CountingChain());

        assertThat(roleResponse.getStatus()).isEqualTo(403);
        assertThat(roleResponse.getContentAsString()).contains("ADMIN_ROLE_FORBIDDEN");
    }

    @Test
    void shouldIgnoreForwardedForFromDirectClient() throws Exception {
        AdminAuthenticationFilter filter = filter(List.of("10.10.0.0/16"), List.of("status"));
        MockHttpServletRequest request = request("GET", "/admin/status/overview", "192.168.1.10");
        request.addHeader("X-Admin-Token", "token");
        request.addHeader("X-Forwarded-For", "10.10.1.20");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new CountingChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ADMIN_IP_FORBIDDEN");
    }

    @Test
    void shouldRequireConfirmationForAdminWriteActions() throws Exception {
        AdminAuthenticationFilter filter = filter(List.of(), List.of("ops"));
        MockHttpServletRequest request = request("POST", "/admin/control/outbox/retry-failed", "127.0.0.1");
        request.addHeader("X-Admin-Token", "token");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new CountingChain());

        assertThat(response.getStatus()).isEqualTo(428);
        assertThat(response.getContentAsString()).contains("ADMIN_CONFIRMATION_REQUIRED");

        MockHttpServletRequest confirmed = request("POST", "/admin/control/outbox/retry-failed", "127.0.0.1");
        confirmed.addHeader("X-Admin-Token", "token");
        confirmed.addHeader("X-Admin-Confirm", "true");
        CountingChain chain = new CountingChain();
        MockHttpServletResponse confirmedResponse = new MockHttpServletResponse();

        filter.doFilter(confirmed, confirmedResponse, chain);

        assertThat(confirmedResponse.getStatus()).isEqualTo(200);
        assertThat(chain.count()).isEqualTo(1);
    }

    @Test
    void shouldRouteCatalogWritesToCatalogRoleOnly() throws Exception {
        AdminAuthenticationFilter filter = filter(List.of(), List.of("catalog"));
        MockHttpServletRequest request = request("POST", "/admin/catalog/publish", "127.0.0.1");
        request.addHeader("X-Admin-Token", "token");
        request.addHeader("X-Admin-Confirm", "true");
        CountingChain chain = new CountingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.count()).isEqualTo(1);

        AdminAuthenticationFilter securityOnlyFilter = filter(List.of(), List.of("security"));
        MockHttpServletRequest securityOnlyRequest = request("POST", "/admin/catalog/publish", "127.0.0.1");
        securityOnlyRequest.addHeader("X-Admin-Token", "token");
        securityOnlyRequest.addHeader("X-Admin-Confirm", "true");
        MockHttpServletResponse securityOnlyResponse = new MockHttpServletResponse();

        securityOnlyFilter.doFilter(securityOnlyRequest, securityOnlyResponse, new CountingChain());

        assertThat(securityOnlyResponse.getStatus()).isEqualTo(403);
        assertThat(securityOnlyResponse.getContentAsString()).contains("Admin role is required: catalog");
    }

    @Test
    void shouldRequireExplicitRoleForEveryCurrentAdminRoute() throws Exception {
        for (RouteRole routeRole : routeRoles()) {
            AdminAuthenticationFilter filter = filter(List.of(), List.of(routeRole.role()));
            MockHttpServletRequest request = request(routeRole.method(), routeRole.requestPath(), "127.0.0.1");
            request.addHeader("X-Admin-Token", "token");
            request.addHeader("X-Admin-Confirm", "true");
            CountingChain chain = new CountingChain();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus())
                .as("%s %s must require role %s", routeRole.method(), routeRole.templatePath(), routeRole.role())
                .isEqualTo(200);
            assertThat(chain.count()).isEqualTo(1);
        }
    }

    @Test
    void roleMatrixShouldCoverEveryImplementedAdminRoute() throws IOException {
        assertThat(roleMatrixOperations())
            .as("Admin route role matrix must cover every literal /admin/* controller mapping")
            .containsAll(implementedAdminOperations());
    }

    @Test
    void shouldFailClosedForUnmappedAdminRoutes() throws Exception {
        AdminAuthenticationFilter filter = filter(List.of(), List.of("status", "access", "catalog", "ops", "security"));
        MockHttpServletRequest request = request("POST", "/admin/new-dangerous-action", "127.0.0.1");
        request.addHeader("X-Admin-Token", "token");
        request.addHeader("X-Admin-Confirm", "true");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new CountingChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ADMIN_ROUTE_FORBIDDEN");
    }

    private AdminAuthenticationFilter filter(List<String> cidrs, List<String> roles) {
        AdminSecurityProperties properties = new AdminSecurityProperties();
        properties.setToken("token");
        properties.setAllowedCidrs(cidrs);
        properties.setDefaultRoles(roles);
        return new AdminAuthenticationFilter(properties, new TrustedClientIpResolver(new TrustedProxyProperties()));
    }

    private MockHttpServletRequest request(String method, String path, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private static List<RouteRole> routeRoles() {
        return List.of(
            new RouteRole("GET", "/admin/status/overview", "/admin/status/overview", "status"),
            new RouteRole("GET", "/admin/status/servers", "/admin/status/servers", "status"),
            new RouteRole("GET", "/admin/status/matches", "/admin/status/matches", "status"),
            new RouteRole("GET", "/admin/status/recent-audit", "/admin/status/recent-audit", "status"),
            new RouteRole("GET", "/admin/status/players/search", "/admin/status/players/search", "status"),
            new RouteRole("GET", "/admin/status/players/{player_id}/weapon-access", "/admin/status/players/00000000-0000-0000-0000-000000000001/weapon-access", "status"),
            new RouteRole("GET", "/admin/status/players/{player_id}/weapon-access/audit", "/admin/status/players/00000000-0000-0000-0000-000000000001/weapon-access/audit", "status"),
            new RouteRole("POST", "/admin/players/{player_id}/access/items/{item_id}", "/admin/players/00000000-0000-0000-0000-000000000001/access/items/weapon.ak12", "access"),
            new RouteRole("POST", "/admin/items/hide", "/admin/items/hide", "access"),
            new RouteRole("POST", "/admin/items/reveal", "/admin/items/reveal", "access"),
            new RouteRole("POST", "/admin/items/shop-lock", "/admin/items/shop-lock", "access"),
            new RouteRole("POST", "/admin/items/shop-unlock", "/admin/items/shop-unlock", "access"),
            new RouteRole("POST", "/admin/items/quest-lock", "/admin/items/quest-lock", "access"),
            new RouteRole("POST", "/admin/items/quest-unlock", "/admin/items/quest-unlock", "access"),
            new RouteRole("POST", "/admin/items/disable", "/admin/items/disable", "access"),
            new RouteRole("POST", "/admin/items/enable", "/admin/items/enable", "access"),
            new RouteRole("POST", "/admin/access/rebuild-projection", "/admin/access/rebuild-projection", "access"),
            new RouteRole("POST", "/admin/cache/invalidate-player", "/admin/cache/invalidate-player", "ops"),
            new RouteRole("POST", "/admin/server-identities/revoke", "/admin/server-identities/revoke", "ops"),
            new RouteRole("POST", "/admin/catalog/publish", "/admin/catalog/publish", "catalog"),
            new RouteRole("POST", "/admin/catalog/rollback", "/admin/catalog/rollback", "catalog"),
            new RouteRole("POST", "/admin/control/players/{player_id}/invalidate-cache", "/admin/control/players/00000000-0000-0000-0000-000000000001/invalidate-cache", "ops"),
            new RouteRole("POST", "/admin/control/server-identities/{server_id}/revoke", "/admin/control/server-identities/00000000-0000-0000-0000-000000000002/revoke", "ops"),
            new RouteRole("POST", "/admin/control/outbox/retry-failed", "/admin/control/outbox/retry-failed", "ops"),
            new RouteRole("POST", "/admin/control/players/{player_id}/weapon-access", "/admin/control/players/00000000-0000-0000-0000-000000000001/weapon-access", "access")
        );
    }

    private static Set<String> roleMatrixOperations() {
        Set<String> operations = new LinkedHashSet<>();
        for (RouteRole routeRole : routeRoles()) {
            operations.add(routeRole.method() + " " + routeRole.templatePath());
        }
        return operations;
    }

    private static Set<String> implementedAdminOperations() throws IOException {
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

    private static final class CountingChain implements FilterChain {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            count.incrementAndGet();
        }

        private int count() {
            return count.get();
        }
    }

    private record RouteRole(String method, String templatePath, String requestPath, String role) {
    }
}
