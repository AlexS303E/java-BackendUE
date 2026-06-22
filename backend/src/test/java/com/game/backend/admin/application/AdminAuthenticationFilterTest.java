package com.game.backend.admin.application;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthenticationFilterTest {
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

    private AdminAuthenticationFilter filter(List<String> cidrs, List<String> roles) {
        AdminSecurityProperties properties = new AdminSecurityProperties();
        properties.setToken("token");
        properties.setAllowedCidrs(cidrs);
        properties.setDefaultRoles(roles);
        return new AdminAuthenticationFilter(properties);
    }

    private MockHttpServletRequest request(String method, String path, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddr);
        return request;
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
}
