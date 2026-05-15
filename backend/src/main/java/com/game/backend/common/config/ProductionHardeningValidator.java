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
    private static final Set<String> UNSAFE_JWT_SECRETS = Set.of(
        "dev-only-change-me-dev-only-change-me",
        "change-me",
        "change-me-in-local-only"
    );

    private final Environment environment;
    private final String adminToken;
    private final String jwtSecret;
    private final String corsAllowedOrigins;

    public ProductionHardeningValidator(
        Environment environment,
        @Value("${app.admin.token:}") String adminToken,
        @Value("${app.auth.jwt-secret:}") String jwtSecret,
        @Value("${app.cors.allowed-origins:}") String corsAllowedOrigins
    ) {
        this.environment = environment;
        this.adminToken = adminToken;
        this.jwtSecret = jwtSecret;
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validateForStartup(environment.getActiveProfiles(), adminToken, jwtSecret, corsAllowedOrigins);
    }

    public static void validateForStartup(String[] activeProfiles, String adminToken, String jwtSecret) {
        validateForStartup(activeProfiles, adminToken, jwtSecret, "https://game.example");
    }

    public static void validateForStartup(String[] activeProfiles, String adminToken, String jwtSecret, String corsAllowedOrigins) {
        if (!hasProductionProfile(activeProfiles)) {
            return;
        }

        requireProductionSecret("app.admin.token", adminToken, UNSAFE_ADMIN_TOKENS);
        requireProductionSecret("app.auth.jwt-secret", jwtSecret, UNSAFE_JWT_SECRETS);
        if (jwtSecret.trim().length() < 32) {
            throw new IllegalStateException("Production profile requires app.auth.jwt-secret to be at least 32 characters");
        }
        if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
            throw new IllegalStateException("Production profile requires app.cors.allowed-origins");
        }
    }

    private static void requireProductionSecret(String propertyName, String value, Set<String> unsafeValues) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Production profile requires " + propertyName);
        }
        if (unsafeValues.contains(value.trim())) {
            throw new IllegalStateException("Production profile forbids dev value for " + propertyName);
        }
    }

    private static boolean hasProductionProfile(String[] activeProfiles) {
        Set<String> normalizedProfiles = Arrays.stream(activeProfiles == null ? new String[0] : activeProfiles)
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return normalizedProfiles.stream().anyMatch(PRODUCTION_PROFILES::contains);
    }
}
