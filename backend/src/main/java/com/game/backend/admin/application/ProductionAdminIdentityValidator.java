package com.game.backend.admin.application;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Component
public class ProductionAdminIdentityValidator implements SmartInitializingSingleton {
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");

    private final Environment environment;
    private final AdminSecurityProperties properties;

    public ProductionAdminIdentityValidator(Environment environment, AdminSecurityProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validateForStartup(environment.getActiveProfiles(), properties);
    }

    public static void validateForStartup(String[] activeProfiles, AdminSecurityProperties properties) {
        if (!hasProductionProfile(activeProfiles)) {
            return;
        }
        if (properties.getToken() != null && !properties.getToken().isBlank()) {
            throw new IllegalStateException("Production profile forbids app.admin.token; configure app.admin.identities instead");
        }
        if (properties.getIdentities().isEmpty()) {
            throw new IllegalStateException("Production profile requires at least one app.admin.identities credential");
        }

        Set<String> identifiers = new HashSet<>();
        for (AdminSecurityProperties.AdminCredential identity : properties.getIdentities()) {
            if (identity.getId() == null || identity.getId().isBlank()) {
                throw new IllegalStateException("Production admin identity requires id");
            }
            if (identity.getToken() == null || identity.getToken().isBlank()) {
                throw new IllegalStateException("Production admin identity requires token");
            }
            if (identity.getRoles().isEmpty()) {
                throw new IllegalStateException("Production admin identity requires at least one role");
            }
            if (!identifiers.add(identity.getId().trim())) {
                throw new IllegalStateException("Production admin identity ids must be unique");
            }
        }
    }

    private static boolean hasProductionProfile(String[] activeProfiles) {
        return Arrays.stream(activeProfiles == null ? new String[0] : activeProfiles)
            .map(profile -> profile.toLowerCase(Locale.ROOT))
            .anyMatch(PRODUCTION_PROFILES::contains);
    }
}
