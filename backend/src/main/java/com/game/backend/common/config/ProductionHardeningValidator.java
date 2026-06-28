package com.game.backend.common.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ProductionHardeningValidator implements SmartInitializingSingleton {
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");
    private static final Set<String> UNSAFE_ADMIN_TOKENS = Set.of("dev-admin-token", "change-me", "change-me-in-local-only");

    private final Environment environment;
    private final String adminToken;
    private final String jwtPrivateKey;
    private final String jwtPublicKey;
    private final String corsAllowedOrigins;
    private final String adminAllowedCidrs;
    private final boolean rateLimitEnabled;
    private final Duration rateLimitWindow;
    private final int authRateLimit;
    private final int serverRateLimit;
    private final int adminRateLimit;

    public ProductionHardeningValidator(
        Environment environment,
        @Value("${app.admin.token:}") String adminToken,
        @Value("${app.auth.jwt-private-key:}") String jwtPrivateKey,
        @Value("${app.auth.jwt-public-key:}") String jwtPublicKey,
        @Value("${app.cors.allowed-origins:}") String corsAllowedOrigins,
        @Value("${app.admin.allowed-cidrs:}") String adminAllowedCidrs,
        @Value("${app.rate-limit.enabled:false}") boolean rateLimitEnabled,
        @Value("${app.rate-limit.window:PT1M}") Duration rateLimitWindow,
        @Value("${app.rate-limit.auth-limit:60}") int authRateLimit,
        @Value("${app.rate-limit.server-limit:600}") int serverRateLimit,
        @Value("${app.rate-limit.admin-limit:120}") int adminRateLimit
    ) {
        this.environment = environment;
        this.adminToken = adminToken;
        this.jwtPrivateKey = jwtPrivateKey;
        this.jwtPublicKey = jwtPublicKey;
        this.corsAllowedOrigins = corsAllowedOrigins;
        this.adminAllowedCidrs = adminAllowedCidrs;
        this.rateLimitEnabled = rateLimitEnabled;
        this.rateLimitWindow = rateLimitWindow;
        this.authRateLimit = authRateLimit;
        this.serverRateLimit = serverRateLimit;
        this.adminRateLimit = adminRateLimit;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validateForStartup(
            environment.getActiveProfiles(),
            adminToken,
            jwtPrivateKey,
            jwtPublicKey,
            corsAllowedOrigins,
            adminAllowedCidrs,
            rateLimitEnabled,
            rateLimitWindow,
            authRateLimit,
            serverRateLimit,
            adminRateLimit
        );
    }

    public static void validateForStartup(String[] activeProfiles, String adminToken, String jwtSecret) {
        validateForStartup(
            activeProfiles,
            adminToken,
            "-----BEGIN PRIVATE KEY-----\nlocal\n-----END PRIVATE KEY-----",
            "-----BEGIN PUBLIC KEY-----\nlocal\n-----END PUBLIC KEY-----",
            "https://game.example",
            "127.0.0.1/32",
            true,
            Duration.ofMinutes(1),
            60,
            600,
            120
        );
    }

    public static void validateForStartup(
        String[] activeProfiles,
        String adminToken,
        String jwtPrivateKey,
        String jwtPublicKey,
        String corsAllowedOrigins
    ) {
        validateForStartup(activeProfiles, adminToken, jwtPrivateKey, jwtPublicKey, corsAllowedOrigins, "127.0.0.1/32");
    }

    public static void validateForStartup(
        String[] activeProfiles,
        String adminToken,
        String jwtPrivateKey,
        String jwtPublicKey,
        String corsAllowedOrigins,
        String adminAllowedCidrs
    ) {
        validateForStartup(
            activeProfiles,
            adminToken,
            jwtPrivateKey,
            jwtPublicKey,
            corsAllowedOrigins,
            adminAllowedCidrs,
            true,
            Duration.ofMinutes(1),
            60,
            600,
            120
        );
    }

    public static void validateForStartup(
        String[] activeProfiles,
        String adminToken,
        String jwtPrivateKey,
        String jwtPublicKey,
        String corsAllowedOrigins,
        String adminAllowedCidrs,
        boolean rateLimitEnabled,
        Duration rateLimitWindow,
        int authRateLimit,
        int serverRateLimit,
        int adminRateLimit
    ) {
        if (!hasProductionProfile(activeProfiles)) {
            return;
        }

        requireProductionSecret("app.admin.token", adminToken, UNSAFE_ADMIN_TOKENS);
        requireRequired("app.auth.jwt-private-key", jwtPrivateKey);
        requireRequired("app.auth.jwt-public-key", jwtPublicKey);
        rejectInlinePem("app.auth.jwt-private-key", jwtPrivateKey);
        rejectInlinePem("app.auth.jwt-public-key", jwtPublicKey);
        requireRequired("app.cors.allowed-origins", corsAllowedOrigins);
        requireRequired("app.admin.allowed-cidrs", adminAllowedCidrs);
        requireTrue("app.rate-limit.enabled", rateLimitEnabled);
        requirePositiveDuration("app.rate-limit.window", rateLimitWindow);
        requirePositiveInt("app.rate-limit.auth-limit", authRateLimit);
        requirePositiveInt("app.rate-limit.server-limit", serverRateLimit);
        requirePositiveInt("app.rate-limit.admin-limit", adminRateLimit);
    }

    private static void requireProductionSecret(String propertyName, String value, Set<String> unsafeValues) {
        requireRequired(propertyName, value);
        if (unsafeValues.contains(value.trim())) {
            throw new IllegalStateException("Production profile forbids dev value for " + propertyName);
        }
    }

    private static void requireRequired(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Production profile requires " + propertyName);
        }
    }

    private static void rejectInlinePem(String propertyName, String value) {
        if (value != null && value.contains("-----BEGIN ")) {
            throw new IllegalStateException("Production profile requires " + propertyName + " to reference external secret material, not inline PEM");
        }
    }

    private static void requireTrue(String propertyName, boolean value) {
        if (!value) {
            throw new IllegalStateException("Production profile requires " + propertyName + "=true");
        }
    }

    private static void requirePositiveDuration(String propertyName, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("Production profile requires positive " + propertyName);
        }
    }

    private static void requirePositiveInt(String propertyName, int value) {
        if (value < 1) {
            throw new IllegalStateException("Production profile requires positive " + propertyName);
        }
    }

    private static boolean hasProductionProfile(String[] activeProfiles) {
        Set<String> normalizedProfiles = Arrays.stream(activeProfiles == null ? new String[0] : activeProfiles)
            .map(profile -> profile.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        return normalizedProfiles.stream().anyMatch(PRODUCTION_PROFILES::contains);
    }
}
