package com.game.backend.common.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import com.game.backend.common.network.TrustedClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;

@Component
public class RateLimitingFilter extends OncePerRequestFilter {
    private static final String RATE_LIMIT_CODE = "RATE_LIMIT_EXCEEDED";
    private static final String RATE_LIMIT_UNAVAILABLE_CODE = "RATE_LIMIT_UNAVAILABLE";

    private final RateLimitProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final TrustedClientIpResolver clientIpResolver;
    private final RateLimitCounter counter;

    @Autowired
    public RateLimitingFilter(
        RateLimitProperties properties,
        MeterRegistry meterRegistry,
        TrustedClientIpResolver clientIpResolver,
        RateLimitCounter counter
    ) {
        this(properties, Clock.systemUTC(), meterRegistry, clientIpResolver, counter);
    }

    RateLimitingFilter(
        RateLimitProperties properties,
        Clock clock,
        MeterRegistry meterRegistry,
        TrustedClientIpResolver clientIpResolver,
        RateLimitCounter counter
    ) {
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.clientIpResolver = clientIpResolver;
        this.counter = counter;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        RateLimitBucket bucket = bucketFor(request);
        if (!properties.isEnabled() || bucket == null) {
            filterChain.doFilter(request, response);
            return;
        }

        long now = clock.millis();
        long windowMillis = Math.max(1000, properties.getWindow().toMillis());
        long retryAfterMillis = retryAfterMillis(now, windowMillis);
        try {
            long count = counter.increment(bucket.key(now, windowMillis), java.time.Duration.ofMillis(retryAfterMillis));
            if (count <= bucket.limit()) {
                filterChain.doFilter(request, response);
                return;
            }
        } catch (DataAccessException e) {
            recordRateLimitError(bucket.group());
            if (!properties.isFailClosedOnRedisError()) {
                filterChain.doFilter(request, response);
                return;
            }
            writeRateLimitUnavailable(response);
            return;
        }

        recordRateLimitRejection(bucket.group());
        writeRateLimited(response, retryAfterSeconds(retryAfterMillis));
    }

    private RateLimitBucket bucketFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/auth/")) {
            return new RateLimitBucket("auth", "auth:" + clientIp(request), properties.getAuthLimit());
        }
        if (path.startsWith("/server/")) {
            return new RateLimitBucket("server", "server:" + clientIp(request), properties.getServerLimit());
        }
        if (path.startsWith("/admin/")) {
            return new RateLimitBucket("admin", "admin:" + clientIp(request), properties.getAdminLimit());
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }

    private long retryAfterMillis(long now, long windowMillis) {
        long currentWindowStart = Math.floorDiv(now, windowMillis) * windowMillis;
        return Math.max(1000, currentWindowStart + windowMillis - now);
    }

    private int retryAfterSeconds(long retryAfterMillis) {
        return (int) Math.ceil(retryAfterMillis / 1000.0);
    }

    private void writeRateLimited(HttpServletResponse response, int retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", Integer.toString(retryAfterSeconds));
        response.setContentType("application/problem+json");
        response.getWriter().write(
            "{\"title\":\"" + RATE_LIMIT_CODE
                + "\",\"status\":429,\"detail\":\"Too many requests\",\"code\":\"" + RATE_LIMIT_CODE + "\"}"
        );
    }

    private void writeRateLimitUnavailable(HttpServletResponse response) throws IOException {
        response.setStatus(503);
        response.setHeader("Retry-After", "1");
        response.setContentType("application/problem+json");
        response.getWriter().write(
            "{\"title\":\"" + RATE_LIMIT_UNAVAILABLE_CODE
                + "\",\"status\":503,\"detail\":\"Rate limiter is unavailable\",\"code\":\""
                + RATE_LIMIT_UNAVAILABLE_CODE + "\"}"
        );
    }

    private void recordRateLimitRejection(String bucketGroup) {
        Counter.builder("backend.rate_limit.rejections")
            .description("Rejected requests by fixed-window rate limit bucket group")
            .tag("bucket", bucketGroup)
            .register(meterRegistry)
            .increment();
    }

    private void recordRateLimitError(String bucketGroup) {
        Counter.builder("backend.rate_limit.errors")
            .description("Redis rate-limit counter errors by bucket group")
            .tag("bucket", bucketGroup)
            .register(meterRegistry)
            .increment();
    }

    private record RateLimitBucket(String group, String keyPrefix, int limit) {
        private String key(long now, long windowMillis) {
            return "ue:rate-limit:" + keyPrefix + ":" + Math.floorDiv(now, windowMillis);
        }
    }
}
