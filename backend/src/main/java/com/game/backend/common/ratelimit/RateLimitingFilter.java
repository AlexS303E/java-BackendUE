package com.game.backend.common.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RateLimitingFilter extends OncePerRequestFilter {
    private static final String RATE_LIMIT_CODE = "RATE_LIMIT_EXCEEDED";

    private final RateLimitProperties properties;
    private final Clock clock;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanupAt = new AtomicLong(0);

    @Autowired
    public RateLimitingFilter(RateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RateLimitingFilter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
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
        cleanupExpiredCounters(now, windowMillis);

        WindowCounter counter = counters.computeIfAbsent(bucket.key(), ignored -> new WindowCounter(now));
        int count = counter.incrementAndGet(now, windowMillis);
        if (count <= bucket.limit()) {
            filterChain.doFilter(request, response);
            return;
        }

        writeRateLimited(response, retryAfterSeconds(counter, now, windowMillis));
    }

    private RateLimitBucket bucketFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/auth/")) {
            return new RateLimitBucket("auth:" + clientIp(request), properties.getAuthLimit());
        }
        if (path.startsWith("/server/")) {
            return new RateLimitBucket("server:" + firstNonBlank(request.getHeader("X-Server-Id"), clientIp(request)), properties.getServerLimit());
        }
        if (path.startsWith("/admin/")) {
            return new RateLimitBucket("admin:" + firstNonBlank(request.getHeader("X-Admin-Id"), clientIp(request)), properties.getAdminLimit());
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT);
    }

    private int retryAfterSeconds(WindowCounter counter, long now, long windowMillis) {
        long windowAge = Math.max(0, now - counter.windowStartMillis());
        long retryAfterMillis = Math.max(1000, windowMillis - windowAge);
        return (int) Math.ceil(retryAfterMillis / 1000.0);
    }

    private void cleanupExpiredCounters(long now, long windowMillis) {
        long previousCleanupAt = lastCleanupAt.get();
        if (now - previousCleanupAt < windowMillis) {
            return;
        }
        if (!lastCleanupAt.compareAndSet(previousCleanupAt, now)) {
            return;
        }
        counters.entrySet().removeIf(entry -> now - entry.getValue().windowStartMillis() > windowMillis * 2);
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

    private record RateLimitBucket(String key, int limit) {
    }

    private static final class WindowCounter {
        private long windowStartMillis;
        private int count;

        private WindowCounter(long windowStartMillis) {
            this.windowStartMillis = windowStartMillis;
        }

        private synchronized int incrementAndGet(long now, long windowMillis) {
            if (now - windowStartMillis >= windowMillis) {
                windowStartMillis = now;
                count = 0;
            }
            count++;
            return count;
        }

        private synchronized long windowStartMillis() {
            return windowStartMillis;
        }
    }
}
