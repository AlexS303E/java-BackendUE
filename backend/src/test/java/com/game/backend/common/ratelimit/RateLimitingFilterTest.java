package com.game.backend.common.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitingFilterTest {
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void shouldLimitAuthRequestsByClientIp() throws Exception {
        RateLimitingFilter filter = filter(true, 2, 10, 10);

        assertThat(doRequest(filter, "/auth/login", "10.0.0.1", null, null).status()).isEqualTo(200);
        assertThat(doRequest(filter, "/auth/login", "10.0.0.1", null, null).status()).isEqualTo(200);

        FilterResult limited = doRequest(filter, "/auth/login", "10.0.0.1", null, null);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.retryAfter()).isNotBlank();
        assertThat(limited.body()).contains("RATE_LIMIT_EXCEEDED");
        assertThat(rateLimitRejections("auth")).isEqualTo(1.0);
    }

    @Test
    void shouldUseServerIdentityAsServerBucketKey() throws Exception {
        RateLimitingFilter filter = filter(true, 10, 1, 10);

        assertThat(doRequest(filter, "/server/runtime-events", "10.0.0.1", "server-a", null).status()).isEqualTo(200);
        assertThat(doRequest(filter, "/server/runtime-events", "10.0.0.1", "server-b", null).status()).isEqualTo(200);

        assertThat(doRequest(filter, "/server/runtime-events", "10.0.0.1", "server-a", null).status()).isEqualTo(429);
        assertThat(rateLimitRejections("server")).isEqualTo(1.0);

        RateLimitingFilter anotherFilter = filter(true, 10, 1, 10);
        assertThat(doRequest(anotherFilter, "/server/runtime-events", "10.0.0.1", "server-c", null).status()).isEqualTo(200);
        assertThat(doRequest(anotherFilter, "/server/runtime-events", "10.0.0.2", "server-c", null).status()).isEqualTo(429);
    }

    @Test
    void shouldPassThroughWhenDisabledOrPathIsNotLimited() throws Exception {
        RateLimitingFilter disabled = filter(false, 0, 0, 0);
        assertThat(doRequest(disabled, "/auth/login", "10.0.0.1", null, null).status()).isEqualTo(200);

        RateLimitingFilter enabled = filter(true, 0, 0, 0);
        assertThat(doRequest(enabled, "/catalog/snapshot", "10.0.0.1", null, null).status()).isEqualTo(200);
    }

    private RateLimitingFilter filter(boolean enabled, int authLimit, int serverLimit, int adminLimit) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(enabled);
        properties.setWindow(Duration.ofMinutes(1));
        properties.setAuthLimit(authLimit);
        properties.setServerLimit(serverLimit);
        properties.setAdminLimit(adminLimit);
        return new RateLimitingFilter(properties, meterRegistry);
    }

    private double rateLimitRejections(String bucket) {
        return meterRegistry.get("backend.rate_limit.rejections")
            .tag("bucket", bucket)
            .counter()
            .count();
    }

    private FilterResult doRequest(
        RateLimitingFilter filter,
        String path,
        String remoteAddr,
        String serverId,
        String adminId
    ) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(remoteAddr);
        if (serverId != null) {
            request.addHeader("X-Server-Id", serverId);
        }
        if (adminId != null) {
            request.addHeader("X-Admin-Id", adminId);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        CountingFilterChain chain = new CountingFilterChain();

        filter.doFilter(request, response, chain);
        if (chain.count() > 0 && response.getStatus() == 200) {
            return new FilterResult(200, response.getHeader("Retry-After"), response.getContentAsString());
        }
        return new FilterResult(response.getStatus(), response.getHeader("Retry-After"), response.getContentAsString());
    }

    private record FilterResult(int status, String retryAfter, String body) {
    }

    private static final class CountingFilterChain implements FilterChain {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
            count.incrementAndGet();
        }

        private int count() {
            return count.get();
        }
    }
}
