package com.game.backend.common.config;

import com.game.backend.auth.application.JwtKeyRingProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
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
    private final JwtKeyRingProperties jwtKeyRingProperties;
    private final String corsAllowedOrigins;
    private final String adminAllowedCidrs;
    private final boolean rateLimitEnabled;
    private final Duration rateLimitWindow;
    private final int authRateLimit;
    private final int serverRateLimit;
    private final int adminRateLimit;
    private final boolean rateLimitFailClosedOnRedisError;

    public ProductionHardeningValidator(
        Environment environment,
        @Value("${app.admin.token:}") String adminToken,
        @Value("${app.auth.jwt-private-key:}") String jwtPrivateKey,
        @Value("${app.auth.jwt-public-key:}") String jwtPublicKey,
        JwtKeyRingProperties jwtKeyRingProperties,
        @Value("${app.cors.allowed-origins:}") String corsAllowedOrigins,
        @Value("${app.admin.allowed-cidrs:}") String adminAllowedCidrs,
        @Value("${app.rate-limit.enabled:false}") boolean rateLimitEnabled,
        @Value("${app.rate-limit.window:PT1M}") Duration rateLimitWindow,
        @Value("${app.rate-limit.auth-limit:60}") int authRateLimit,
        @Value("${app.rate-limit.server-limit:600}") int serverRateLimit,
        @Value("${app.rate-limit.admin-limit:120}") int adminRateLimit,
        @Value("${app.rate-limit.fail-closed-on-redis-error:false}") boolean rateLimitFailClosedOnRedisError
    ) {
        this.environment = environment;
        this.adminToken = adminToken;
        this.jwtPrivateKey = jwtPrivateKey;
        this.jwtPublicKey = jwtPublicKey;
        this.jwtKeyRingProperties = jwtKeyRingProperties;
        this.corsAllowedOrigins = corsAllowedOrigins;
        this.adminAllowedCidrs = adminAllowedCidrs;
        this.rateLimitEnabled = rateLimitEnabled;
        this.rateLimitWindow = rateLimitWindow;
        this.authRateLimit = authRateLimit;
        this.serverRateLimit = serverRateLimit;
        this.adminRateLimit = adminRateLimit;
        this.rateLimitFailClosedOnRedisError = rateLimitFailClosedOnRedisError;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validateForStartup(
            environment.getActiveProfiles(),
            adminToken,
            jwtPrivateKey,
            jwtPublicKey,
            jwtKeyRingProperties,
            corsAllowedOrigins,
            adminAllowedCidrs,
            rateLimitEnabled,
            rateLimitWindow,
            authRateLimit,
            serverRateLimit,
            adminRateLimit,
            rateLimitFailClosedOnRedisError,
            false
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
        validateForStartup(
            activeProfiles,
            adminToken,
            jwtPrivateKey,
            jwtPublicKey,
            corsAllowedOrigins,
            adminAllowedCidrs,
            rateLimitEnabled,
            rateLimitWindow,
            authRateLimit,
            serverRateLimit,
            adminRateLimit,
            true
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
        int adminRateLimit,
        boolean rateLimitFailClosedOnRedisError
    ) {
        validateForStartup(
            activeProfiles,
            adminToken,
            jwtPrivateKey,
            jwtPublicKey,
            corsAllowedOrigins,
            adminAllowedCidrs,
            rateLimitEnabled,
            rateLimitWindow,
            authRateLimit,
            serverRateLimit,
            adminRateLimit,
            rateLimitFailClosedOnRedisError,
            true
        );
    }

    static void validateForStartup(
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
        int adminRateLimit,
        boolean rateLimitFailClosedOnRedisError,
        boolean validateLegacyAdminToken
    ) {
        validateForStartup(
            activeProfiles,
            adminToken,
            jwtPrivateKey,
            jwtPublicKey,
            new JwtKeyRingProperties(),
            corsAllowedOrigins,
            adminAllowedCidrs,
            rateLimitEnabled,
            rateLimitWindow,
            authRateLimit,
            serverRateLimit,
            adminRateLimit,
            rateLimitFailClosedOnRedisError,
            validateLegacyAdminToken
        );
    }

    static void validateForStartup(
        String[] activeProfiles,
        String adminToken,
        String jwtPrivateKey,
        String jwtPublicKey,
        JwtKeyRingProperties keyRingProperties,
        String corsAllowedOrigins,
        String adminAllowedCidrs,
        boolean rateLimitEnabled,
        Duration rateLimitWindow,
        int authRateLimit,
        int serverRateLimit,
        int adminRateLimit,
        boolean rateLimitFailClosedOnRedisError,
        boolean validateLegacyAdminToken
    ) {
        if (!hasProductionProfile(activeProfiles)) {
            return;
        }

        if (validateLegacyAdminToken) {
            requireProductionSecret("app.admin.token", adminToken, UNSAFE_ADMIN_TOKENS);
        }
        validateJwtKeyMaterial(jwtPrivateKey, jwtPublicKey, keyRingProperties);
        requireRequired("app.cors.allowed-origins", corsAllowedOrigins);
        requireRequired("app.admin.allowed-cidrs", adminAllowedCidrs);
        requireTrue("app.rate-limit.enabled", rateLimitEnabled);
        requirePositiveDuration("app.rate-limit.window", rateLimitWindow);
        requirePositiveInt("app.rate-limit.auth-limit", authRateLimit);
        requirePositiveInt("app.rate-limit.server-limit", serverRateLimit);
        requirePositiveInt("app.rate-limit.admin-limit", adminRateLimit);
        requireTrue("app.rate-limit.fail-closed-on-redis-error", rateLimitFailClosedOnRedisError);
    }

    static void validateJwtKeyMaterial(
        String jwtPrivateKey,
        String jwtPublicKey,
        JwtKeyRingProperties keyRingProperties
    ) {
        if (keyRingProperties == null || keyRingProperties.getJwtKeys().isEmpty()) {
            requireRequired("app.auth.jwt-private-key", jwtPrivateKey);
            requireRequired("app.auth.jwt-public-key", jwtPublicKey);
            rejectInlinePem("app.auth.jwt-private-key", jwtPrivateKey);
            rejectInlinePem("app.auth.jwt-public-key", jwtPublicKey);
            return;
        }

        requireRequired("app.auth.jwt-active-key-id", keyRingProperties.getJwtActiveKeyId());
        Set<String> keyIds = new HashSet<>();
        for (JwtKeyRingProperties.JwtKey key : keyRingProperties.getJwtKeys()) {
            requireRequired("app.auth.jwt-keys[].id", key.getId());
            if (!keyIds.add(key.getId().trim())) {
                throw new IllegalStateException("Production profile forbids duplicate app.auth.jwt-keys[].id");
            }
            requireRequired("app.auth.jwt-keys[].private-key", key.getPrivateKey());
            requireRequired("app.auth.jwt-keys[].public-key", key.getPublicKey());
            rejectInlinePem("app.auth.jwt-keys[].private-key", key.getPrivateKey());
            rejectInlinePem("app.auth.jwt-keys[].public-key", key.getPublicKey());
        }
        if (!keyIds.contains(keyRingProperties.getJwtActiveKeyId().trim())) {
            throw new IllegalStateException("app.auth.jwt-active-key-id must reference configured jwt-keys");
        }
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
