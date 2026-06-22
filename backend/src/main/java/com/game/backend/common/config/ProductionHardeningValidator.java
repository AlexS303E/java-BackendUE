package com.game.backend.common.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

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

    public ProductionHardeningValidator(
        Environment environment,
        @Value("${app.admin.token:}") String adminToken,
        @Value("${app.auth.jwt-private-key:}") String jwtPrivateKey,
        @Value("${app.auth.jwt-public-key:}") String jwtPublicKey,
        @Value("${app.cors.allowed-origins:}") String corsAllowedOrigins,
        @Value("${app.admin.allowed-cidrs:}") String adminAllowedCidrs
    ) {
        this.environment = environment;
        this.adminToken = adminToken;
        this.jwtPrivateKey = jwtPrivateKey;
        this.jwtPublicKey = jwtPublicKey;
        this.corsAllowedOrigins = corsAllowedOrigins;
        this.adminAllowedCidrs = adminAllowedCidrs;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validateForStartup(
            environment.getActiveProfiles(),
            adminToken,
            jwtPrivateKey,
            jwtPublicKey,
            corsAllowedOrigins,
            adminAllowedCidrs
        );
    }

    public static void validateForStartup(String[] activeProfiles, String adminToken, String jwtSecret) {
        validateForStartup(
            activeProfiles,
            adminToken,
            "-----BEGIN PRIVATE KEY-----\nlocal\n-----END PRIVATE KEY-----",
            "-----BEGIN PUBLIC KEY-----\nlocal\n-----END PUBLIC KEY-----",
            "https://game.example",
            "127.0.0.1/32"
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

    private static boolean hasProductionProfile(String[] activeProfiles) {
        Set<String> normalizedProfiles = Arrays.stream(activeProfiles == null ? new String[0] : activeProfiles)
            .map(profile -> profile.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        return normalizedProfiles.stream().anyMatch(PRODUCTION_PROFILES::contains);
    }
}
